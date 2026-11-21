// Droid's helper on phones without root: started once per boot as the shell user (uid 2000) through Wireless
// debugging (see adb/Adb.kt), from this APK with app_process, like VdHelper. It stands in for `su`: the app asks it to
// run shell commands, and streams their stdin, stdout and stderr over 127.0.0.1.
//
// Usage: DROID_TOKEN=<hex> app_process / com.adetkov.droid.Bridge   (prints "port=N" once listening)
//
// Any app may connect to a local port, so both sides first prove they know DROID_TOKEN without sending it:
//   client -> 32 random bytes A
//   server -> HMAC(token, "droid-bridge-server" + A), then 32 random bytes B
//   client -> HMAC(token, "droid-bridge-client" + B)
// Then frames, [type: 1 byte][length: 4 bytes big-endian][payload], one request per connection:
//   client -> a command (UTF-8, run with sh -c) as 'x' (stderr goes to stdout) or 'X' (stderr kept apart): the app's
//             requests, which carry on if the app goes away (their stdin ends, their output is read and dropped);
//             or as 'a' (stderr kept apart): the agent's `android` command, which ends like a command in a pipe when
//             its caller goes (its output is closed, so it dies at its next write). The server answers 'a' (empty)
//             first, so the client knows this helper takes 'a' (an older one closes the connection instead).
//             Then any of 'i' stdin bytes, 'e' end of stdin, 'k' kill (SIGTERM to sh)
//   server -> 'o' stdout bytes, 'r' stderr bytes, then 'x' exit code (4 bytes) and the connection closes
//   client -> 'p' (ping): server -> 'p' with "version=<APK path> active=<commands running>"; 'q' (quit): the helper exits.
// Children get DROID_BRIDGE=<port> and DROID_TOKEN, so a client inside proot (the `android` command) can come back.
package com.adetkov.droid;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class Bridge {
    static byte[] token;
    static int port;
    static final java.util.concurrent.atomic.AtomicInteger active = new java.util.concurrent.atomic.AtomicInteger();
    /** Android's shell; tests run the bridge with their own. */
    static String sh = "/system/bin/sh";

    public static void main(String[] args) throws Exception {
        String t = System.getenv("DROID_TOKEN");
        if (t == null || t.length() < 32) {
            System.out.println("error=no DROID_TOKEN");
            System.exit(1);
        }
        token = t.getBytes(StandardCharsets.US_ASCII);
        ServerSocket ss = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        port = ss.getLocalPort();
        System.out.println("pid=" + android.os.Process.myPid());
        System.out.println("port=" + port);
        System.out.flush();
        listen(ss);
    }

    static void listen(ServerSocket ss) throws IOException {
        while (true) {
            Socket s = ss.accept();
            Thread th = new Thread(() -> serve(s));
            th.setDaemon(true);
            th.start();
        }
    }

    static byte[] hmac(String label, byte[] nonce) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(token, "HmacSHA256"));
        mac.update(label.getBytes(StandardCharsets.US_ASCII));
        return mac.doFinal(nonce);
    }

    /** The challenges each way (see the top); true if the client knows the token. */
    static boolean handshake(Socket sock, DataInputStream in, DataOutputStream out) throws Exception {
        sock.setSoTimeout(10_000);
        byte[] a = new byte[32];
        in.readFully(a);
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        out.write(hmac("droid-bridge-server", a));
        out.write(b);
        out.flush();
        byte[] proof = new byte[32];
        in.readFully(proof);
        sock.setSoTimeout(0);
        return MessageDigest.isEqual(proof, hmac("droid-bridge-client", b));
    }

    static void serve(Socket s) {
        try (Socket sock = s) {
            DataInputStream in = new DataInputStream(sock.getInputStream());
            DataOutputStream out = new DataOutputStream(sock.getOutputStream());
            if (!handshake(sock, in, out)) return;

            int type = in.readUnsignedByte();
            byte[] payload = new byte[in.readInt()];
            in.readFully(payload);
            switch (type) {
                case 'p':
                    send(out, 'p', ("version=" + System.getProperty("java.class.path") + " active=" + active.get()).getBytes(StandardCharsets.UTF_8));
                    break;
                case 'q':
                    send(out, 'q', new byte[0]);
                    System.exit(0);
                    break;
                case 'x':
                case 'X':
                case 'a':
                    active.incrementAndGet();
                    try {
                        run(new String(payload, StandardCharsets.UTF_8), type, in, out);
                    } finally {
                        active.decrementAndGet();
                    }
                    break;
            }
        } catch (Exception e) {
            // client went away or failed the handshake
        }
    }

    static void send(DataOutputStream out, int type, byte[] b, int off, int len) throws IOException {
        synchronized (out) {
            out.writeByte(type);
            out.writeInt(len);
            out.write(b, off, len);
            out.flush();
        }
    }

    static void send(DataOutputStream out, int type, byte[] b) throws IOException {
        send(out, type, b, 0, b.length);
    }

    /** Runs request [type] ('x', 'X' or 'a', see the top). */
    static void run(String cmd, int type, DataInputStream in, DataOutputStream out) throws Exception {
        boolean merge = type == 'x', pipe = type == 'a';
        if (pipe) send(out, 'a', new byte[0]);  // understood (an older helper would have closed the connection)
        ProcessBuilder pb = new ProcessBuilder(sh, "-c", cmd).redirectErrorStream(merge);
        pb.environment().put("DROID_BRIDGE", String.valueOf(port));
        pb.environment().put("DROID_TOKEN", new String(token, StandardCharsets.US_ASCII));
        Process p = pb.start();
        Thread stdout = pump(p.getInputStream(), out, 'o', pipe);
        Thread stderr = merge ? null : pump(p.getErrorStream(), out, 'r', pipe);
        Thread stdin = new Thread(() -> {
            OutputStream pin = p.getOutputStream();
            try {
                while (true) {
                    int kind = in.readUnsignedByte();
                    byte[] payload = new byte[in.readInt()];
                    in.readFully(payload);
                    if (kind == 'i') {
                        pin.write(payload);
                        pin.flush();
                    } else if (kind == 'e') {
                        pin.close();
                    } else if (kind == 'k') {
                        p.destroy();
                    }
                }
            } catch (IOException e) {
                // The client went away: like a closed pipe, the command sees the end of its input and carries on.
                try { pin.close(); } catch (IOException ignored) {}
            }
        });
        stdin.setDaemon(true);
        stdin.start();
        int code = p.waitFor();
        // A command's background children may hold its stdout open long after it exits (`cmd &` without redirecting):
        // pass on what is already there, then report the exit instead of waiting for them.
        stdout.join(2000);
        if (stderr != null) stderr.join(2000);
        byte[] c = new byte[]{(byte) (code >> 24), (byte) (code >> 16), (byte) (code >> 8), (byte) code};
        try { send(out, 'x', c); } catch (IOException ignored) {}
    }

    // Copies a process stream into frames. Once the client is gone, an app's request carries on: the rest is read and
    // dropped, so the command never stalls on a full pipe (an install, or an agent finishing its turn after the app was
    // closed). With [pipe] (the agent's `android`) the stream is closed instead, as a pipe's would be: the command ends
    // at its next write (SIGPIPE) rather than running on with nobody reading, as `android 'logcat' | head` would.
    static Thread pump(InputStream src, DataOutputStream out, int type, boolean pipe) {
        Thread th = new Thread(() -> {
            byte[] buf = new byte[16384];
            boolean open = true;
            try {
                for (int n; (n = src.read(buf)) > 0; ) {
                    if (!open) continue;
                    try {
                        send(out, type, buf, 0, n);
                    } catch (IOException gone) {
                        open = false;
                        if (pipe) {
                            src.close();
                            return;
                        }
                    }
                }
            } catch (IOException ignored) {}
        });
        th.setDaemon(true);
        th.start();
        return th;
    }
}
