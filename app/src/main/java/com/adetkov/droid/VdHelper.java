// Hidden display for one agent task, run by phone-agent as shell from the app's APK (app_process, like scrcpy).
// Usage: VdHelper WIDTH HEIGHT DPI FLAGS ASSOCIATION_ID [AGENT_PID]
//   ASSOCIATION_ID: 0 for none, a plain virtual display
//   AGENT_PID: phone-agent's; the helper ends when it does (a vd-start.sh from before this passes none)
// Prints "pid=", "displayId=" and "port=" lines. Answers the agent's `shot` in /data/local/tmp/droid/vd/<displayId>
// once the display has settled (see Settle): shot.req with shot.png, text.req with ui.xml (what's on it, as
// `uiautomator dump --windows` writes it), fill.req by setting a text field (see fill), each then .done, holding
// "changing" if it never settled (and for fill, what it did). Streams the display
// to the app's Watch view on 127.0.0.1:<port>, which can send touches back (take over).
package com.adetkov.droid;

import android.annotation.SuppressLint;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

// Hidden APIs are the point: a display that keeps running while the phone is locked needs a VirtualDevice, which
// only the system or a shell-privileged process (like this one) can create.
@SuppressLint({"PrivateApi", "DiscouragedPrivateApi", "WrongConstant"})
@SuppressWarnings("deprecation")  // Looper.prepareMainLooper: still how a process without an app sets up its main thread
public class VdHelper {
    static final String PKG = "com.android.shell";
    static final String ROOT = "/data/local/tmp/droid/vd";
    static final int OWN_DISPLAY_GROUP = 1 << 11;

    static class FakeContext extends ContextWrapper {
        FakeContext(Context base) { super(base); }
        @Override public String getPackageName() { return PKG; }
        @Override public String getOpPackageName() { return PKG; }
        @Override public AttributionSource getAttributionSource() {
            return new AttributionSource.Builder(2000).setPackageName(PKG).build();
        }
        @Override public Context getApplicationContext() { return this; }
    }

    static Context systemContext() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Constructor<?> c = at.getDeclaredConstructor();
        c.setAccessible(true);
        Object thread = c.newInstance();
        Field f = at.getDeclaredField("sCurrentActivityThread");
        f.setAccessible(true);
        f.set(null, thread);
        return (Context) at.getDeclaredMethod("getSystemContext").invoke(thread);
    }

    static Object virtualDevice; // keep the VirtualDevice alive for the life of the process

    /**
     * Whether the app holds this display for the owner (its task answered, the screen kept for their reply or their
     * hands): set by a client's "h 1" / "h 0", cleared by the agent looking at its screen (it's working again). While
     * held and no one views it, the display sleeps: awake, it keeps the whole phone from suspending.
     */
    static volatile boolean held;
    static boolean asleep;
    static boolean noPower;  // this Android can't put a virtual device to sleep: never ask again

    /** Puts the VirtualDevice's displays to sleep, or wakes them; false if it can't. */
    static boolean power(boolean sleep) {
        if (virtualDevice == null || noPower) return false;
        try {
            virtualDevice.getClass().getMethod(sleep ? "goToSleep" : "wakeUp").invoke(virtualDevice);
            System.out.println(sleep ? "asleep" : "awake");
            return true;
        } catch (Throwable e) {
            System.out.println("power: " + e);
            noPower = true;
            return false;
        }
    }

    // Displays owned by a VirtualDevice get their own display/power group, so they keep running
    // while the phone's own screen is asleep (Android 17 ignores FLAG_OWN_DISPLAY_GROUP otherwise).
    static VirtualDisplay createOnVirtualDevice(Context ctx, int associationId, int w, int h, int dpi,
                                                ImageReader reader, int flags) throws Exception {
        Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "virtualdevice");
        Class<?> ivdm = Class.forName("android.companion.virtual.IVirtualDeviceManager");
        Object svc = Class.forName("android.companion.virtual.IVirtualDeviceManager$Stub")
                .getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
        Class<?> vdmClass = Class.forName("android.companion.virtual.VirtualDeviceManager");
        Constructor<?> vdmCtor = vdmClass.getDeclaredConstructor(ivdm, Context.class);
        vdmCtor.setAccessible(true);
        Object vdm = vdmCtor.newInstance(svc, ctx);

        Class<?> builderClass = Class.forName("android.companion.virtual.VirtualDeviceParams$Builder");
        Object builder = builderClass.getConstructor().newInstance();
        builderClass.getMethod("setLockState", int.class).invoke(builder, 1); // LOCK_STATE_ALWAYS_UNLOCKED
        builderClass.getMethod("setName", String.class).invoke(builder, "droid");
        Object params = builderClass.getMethod("build").invoke(builder);
        virtualDevice = vdmClass.getMethod("createVirtualDevice", int.class,
                Class.forName("android.companion.virtual.VirtualDeviceParams")).invoke(vdm, associationId, params);
        System.out.println("virtualDevice=" + virtualDevice);

        android.hardware.display.VirtualDisplayConfig config =
                new android.hardware.display.VirtualDisplayConfig.Builder("droid", w, h, dpi)
                        .setSurface(reader.getSurface()).setFlags(flags).build();
        return (VirtualDisplay) virtualDevice.getClass()
                .getMethod("createVirtualDisplay", android.hardware.display.VirtualDisplayConfig.class,
                        java.util.concurrent.Executor.class, VirtualDisplay.Callback.class)
                .invoke(virtualDevice, config, (java.util.concurrent.Executor) Runnable::run, null);
    }

    // Without a VirtualDevice: its own display group keeps it awake on Android 16 and earlier (17 ignores the flag,
    // so there the display sleeps with the phone).
    static VirtualDisplay createPlain(Context ctx, int w, int h, int dpi, ImageReader reader, int flags) throws Exception {
        Constructor<DisplayManager> c = DisplayManager.class.getDeclaredConstructor(Context.class);
        c.setAccessible(true);
        return c.newInstance(ctx).createVirtualDisplay("droid", w, h, dpi, reader.getSurface(), flags | OWN_DISPLAY_GROUP);
    }

    public static void main(String[] args) throws Exception {
        // First, so vd-stop.sh can end this helper however far it gets.
        System.out.println("pid=" + android.os.Process.myPid());
        System.out.flush();
        int w = Integer.parseInt(args[0]), h = Integer.parseInt(args[1]), dpi = Integer.parseInt(args[2]);
        int flags = Integer.parseInt(args[3]), associationId = Integer.parseInt(args[4]);
        Lifeline lifeline = new Lifeline(args.length > 5 ? args[5] : "");
        File apk = new File(System.getProperty("java.class.path", "").split(":")[0]);  // this helper's APK
        if (lifeline.gone()) {  // it gave up on this helper before it started
            System.out.println("phone-agent is gone");
            System.exit(0);
        }
        Looper.prepareMainLooper();
        Context ctx = new FakeContext(systemContext());

        ImageReader reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
        VirtualDisplay vd = null;
        if (associationId > 0) {
            try {
                vd = createOnVirtualDevice(ctx, associationId, w, h, dpi, reader, flags);
            } catch (Exception e) {
                System.out.println("virtualDevice: " + e);
            }
        }
        if (vd == null) vd = createPlain(ctx, w, h, dpi, reader, flags);
        int displayId = vd.getDisplay().getDisplayId();
        System.out.println("displayId=" + displayId);

        // Keep the keyboard on this display (0 = DISPLAY_IME_POLICY_LOCAL) instead of the user's screen.
        Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "window");
        Object wm = Class.forName("android.view.IWindowManager$Stub").getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
        try {
            wm.getClass().getMethod("setDisplayImePolicy", int.class, int.class).invoke(wm, displayId, 0);
            System.out.println("imePolicy=local");
        } catch (Exception e) {
            System.out.println("imePolicy: " + e);
        }
        // One helper per task: its own screenshot dir and frame port. The port is any free one, bound before it is
        // told: a fixed one (say 8700 + display) could already be another app's, which would then get the owner's
        // touches. 0: no live view.
        java.net.ServerSocket server = null;
        String token = System.getenv("DROID_TOKEN");
        if (token == null || token.isEmpty()) System.out.println("frame server: no DROID_TOKEN, live view off");
        else try {
            server = new java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"));
        } catch (java.io.IOException e) {
            System.out.println("frame server: " + e);
        }
        System.out.println("port=" + (server != null ? server.getLocalPort() : 0));
        System.out.flush();

        File dir = new File(ROOT, String.valueOf(displayId));
        dir.mkdirs();
        File shotReq = new File(dir, "shot.req"), textReq = new File(dir, "text.req"), fillReq = new File(dir, "fill.req");
        if (server != null) {
            java.net.ServerSocket ss = server;
            Thread t = new Thread(() -> serveFrames(ss, token, displayId));
            t.setDaemon(true);
            t.start();
        }
        // Pixels are only copied when someone needs them: a `shot` request, or a Watch client due for a frame.
        Image pending = null;
        Settle settle = new Settle();
        long nextCheck = 0;
        while (true) {
            long now = SystemClock.uptimeMillis();
            Image img = reader.acquireLatestImage();
            if (img != null) {
                if (pending != null) pending.close();
                pending = img;
                settle.frame(now);
            }
            boolean shot = shotReq.exists(), text = textReq.exists(), fill = fillReq.exists();
            if (shot || text || fill) { settle.ask(now); held = false; }
            // Held and unwatched: asleep (the next viewer, shot or task wakes it, and the app in front is resumed).
            boolean sleep = held && clients.isEmpty();
            if (sleep != asleep && power(sleep)) asleep = sleep;
            boolean due = settle.due(now);
            if (pending != null && ((due && shot) || frameDue())) {
                latest = toBitmap(pending, w, h);
                pending.close();
                pending = null;
            }
            if (now >= nextCheck) {
                nextCheck = now + 1000;
                if (!clients.isEmpty()) topApp = topPackage(displayId);
                // Normally vd-stop.sh ends this helper; if phone-agent ended without it (killed, or its `android` call
                // failed), the display would otherwise stay up, and keep the phone awake, until the phone restarts. An
                // update or reinstall of the app replaces this APK and leaves the run it started orphaned: the same.
                if (lifeline.gone() || !apk.exists()) {
                    System.out.println(lifeline.gone() ? "phone-agent is gone: ending the display" : "the app was updated: ending the display");
                    File[] left = dir.listFiles();
                    if (left != null) for (File f : left) f.delete();
                    dir.delete();
                    System.exit(0);
                }
            }
            if (due) {
                // What the agent gets besides the picture or text: whether the screen was still changing.
                byte[] note = (settle.settled(now) ? "" : "changing\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                settle.done();
                if (text) {
                    textReq.delete();
                    File out = new File(dir, "ui.xml");
                    try {
                        writeFile(out, windowsXml(displayId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    } catch (Throwable e) {
                        System.out.println("text: " + e);
                        out.delete();
                    }
                    writeFile(new File(dir, "text.done"), note);
                }
                if (fill) {
                    // fill.req: the field's name (or an empty line), then the text, written whole (by rename)
                    String said;
                    try {
                        String req = new String(java.nio.file.Files.readAllBytes(fillReq.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                        int nl = req.indexOf('\n');
                        said = fill(displayId, nl < 0 ? "" : req.substring(0, nl).trim(), nl < 0 ? req : req.substring(nl + 1));
                    } catch (Throwable e) {
                        System.out.println("fill: " + e);
                        said = "couldn't fill it: " + e;
                    }
                    fillReq.delete();
                    java.io.ByteArrayOutputStream done = new java.io.ByteArrayOutputStream();
                    done.write(note);
                    done.write((said + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    writeFile(new File(dir, "fill.done"), done.toByteArray());
                }
                if (shot) {
                    shotReq.delete();
                    File out = new File(dir, "shot.png");
                    if (latest != null) {
                        try (FileOutputStream os = new FileOutputStream(out)) {
                            latest.compress(Bitmap.CompressFormat.PNG, 100, os);
                        }
                    } else {
                        out.delete();
                    }
                    writeFile(new File(dir, "shot.done"), note);
                }
            }
            Thread.sleep(settle.waiting() ? 20 : 100);
        }
    }

    /**
     * When a `shot` (or `shot --text`) is due: once no new frame has come for [QUIET] ms (counted from the request too,
     * so a tap just before it has time to show), or after [MOST] ms however it looks. A blinking cursor (a frame every
     * 500 ms) still lets it settle; an animation or a page loading doesn't. Some screens never stop (a map, a video),
     * hence a short [MOST]: an agent that sees a page still loading looks again.
     */
    static final class Settle {
        static final long QUIET = 400, MOST = 2000;
        long lastFrame;  // uptime of the display's latest frame
        long asked;      // uptime the waiting request was first seen; 0: none

        void frame(long now) { lastFrame = now; }
        void ask(long now) { if (asked == 0) asked = now; }
        boolean waiting() { return asked != 0; }
        boolean settled(long now) { return now - Math.max(lastFrame, asked) >= QUIET; }
        boolean due(long now) { return asked != 0 && (settled(now) || now - asked >= MOST); }
        void done() { asked = 0; }
    }

    static void writeFile(File f, byte[] b) throws java.io.IOException {
        try (FileOutputStream os = new FileOutputStream(f)) {
            os.write(b);
        }
    }

    /**
     * What's on [displayId], as `uiautomator dump --windows` writes it (what `shot --text` reads): its windows' visible
     * nodes. Read here, in a helper that's already running, through a UiAutomation of its own for the moment it takes,
     * instead of uiautomator's, which starts a Java process and waits for a second of quiet first (2-3 s a dump).
     * Accessibility services the owner uses keep running (a plain UiAutomation would pause them), and only one
     * UiAutomation may be connected at a time, so another task's dump is waited out.
     */
    static String windowsXml(int displayId) throws Exception {
        return onWindows(displayId, windows -> {
            StringBuilder x = new StringBuilder("<?xml version='1.0' encoding='UTF-8'?><displays><display id=\"" + displayId + "\">");
            for (android.view.accessibility.AccessibilityWindowInfo win : windows) {
                x.append("<window>");
                android.view.accessibility.AccessibilityNodeInfo root = win.getRoot();
                if (root != null) node(x, root, 0);
                x.append("</window>");
            }
            return x.append("</display></displays>").toString();
        });
    }

    interface WindowsWork { String on(java.util.List<android.view.accessibility.AccessibilityWindowInfo> windows) throws Exception; }

    /** [work] on [displayId]'s windows (maybe none), read through a UiAutomation of this helper's (see [windowsXml]). */
    static String onWindows(int displayId, WindowsWork work) throws Exception {
        android.os.HandlerThread thread = new android.os.HandlerThread("droid-text");
        thread.start();
        android.app.UiAutomation ui = null;
        try {
            Class<?> iconn = Class.forName("android.app.IUiAutomationConnection");
            Object conn = Class.forName("android.app.UiAutomationConnection").getDeclaredConstructor().newInstance();
            Constructor<android.app.UiAutomation> c = android.app.UiAutomation.class.getDeclaredConstructor(Looper.class, iconn);
            c.setAccessible(true);
            ui = c.newInstance(thread.getLooper(), conn);
            java.lang.reflect.Method connect = android.app.UiAutomation.class.getMethod("connect", int.class);
            for (long give = SystemClock.uptimeMillis() + 3000; ; ) {
                try {
                    connect.invoke(ui, android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
                    break;
                } catch (java.lang.reflect.InvocationTargetException e) {
                    // "already registered": another task's dump (or uiautomator) has the one UiAutomation
                    if (!(e.getCause() instanceof IllegalStateException) || SystemClock.uptimeMillis() > give) throw e;
                    SystemClock.sleep(50);
                }
            }
            android.accessibilityservice.AccessibilityServiceInfo info = ui.getServiceInfo();
            info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    | android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            ui.setServiceInfo(info);
            // The windows come a moment after asking for them (uiautomator waits for a second of quiet instead).
            java.util.List<android.view.accessibility.AccessibilityWindowInfo> windows = null;
            for (long give = SystemClock.uptimeMillis() + 1000; SystemClock.uptimeMillis() < give; SystemClock.sleep(10)) {
                windows = ui.getWindowsOnAllDisplays().get(displayId);
                if (windows != null && !windows.isEmpty()) break;
            }
            // A web page (Chrome's, or an app's WebView) is read out only once something asks: the first look after it
            // loads finds it empty, so it's given a moment to fill in.
            for (long give = SystemClock.uptimeMillis() + 1000; windows != null && emptyWeb(windows) && SystemClock.uptimeMillis() < give; ) {
                SystemClock.sleep(100);
                windows = ui.getWindowsOnAllDisplays().get(displayId);
            }
            return work.on(windows != null ? windows : java.util.Collections.emptyList());
        } finally {
            if (ui != null) try { android.app.UiAutomation.class.getMethod("disconnect").invoke(ui); } catch (Exception ignored) {}
            thread.quitSafely();
        }
    }

    /** Whether [windows] show a web page with nothing in it read out yet. */
    static boolean emptyWeb(java.util.List<android.view.accessibility.AccessibilityWindowInfo> windows) {
        for (android.view.accessibility.AccessibilityWindowInfo win : windows) {
            android.view.accessibility.AccessibilityNodeInfo root = win.getRoot();
            if (root != null && emptyWeb(root, 0)) return true;
        }
        return false;
    }

    static boolean emptyWeb(android.view.accessibility.AccessibilityNodeInfo n, int depth) {
        // An app's WebView, or Chrome's page before it's read out: a bare frame it calls "Web View"
        CharSequence cls = n.getClassName(), desc = n.getContentDescription();
        if ((cls != null && cls.toString().endsWith("WebView")) || (desc != null && desc.toString().equals("Web View"))) return n.getChildCount() == 0;
        for (int i = 0; depth < 100 && i < n.getChildCount(); i++) {
            android.view.accessibility.AccessibilityNodeInfo child = n.getChild(i);
            if (child != null && child.isVisibleToUser() && emptyWeb(child, depth + 1)) return true;
        }
        return false;
    }

    /** [n] and its visible children as uiautomator's <node> elements, with the attributes `shot --text` reads. */
    static void node(StringBuilder x, android.view.accessibility.AccessibilityNodeInfo n, int depth) {
        android.graphics.Rect r = new android.graphics.Rect();
        n.getBoundsInScreen(r);
        x.append("<node");
        attr(x, "text", n.getText());
        attr(x, "content-desc", n.getContentDescription());
        attr(x, "hint", n.getHintText());
        attr(x, "class", n.getClassName());
        attr(x, "password", String.valueOf(n.isPassword()));
        attr(x, "checkable", String.valueOf(n.isCheckable()));
        attr(x, "checked", String.valueOf(n.isChecked()));
        attr(x, "selected", String.valueOf(n.isSelected()));
        attr(x, "enabled", String.valueOf(n.isEnabled()));
        attr(x, "focused", String.valueOf(n.isFocused()));
        attr(x, "scrollable", String.valueOf(n.isScrollable()));
        if (n.isScrollable()) {  // which way: a list offers to scroll only where it has more
            java.util.List<android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction> acts = n.getActionList();
            attr(x, "more-before", String.valueOf(acts.contains(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)));
            attr(x, "more-after", String.valueOf(acts.contains(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)));
        }
        attr(x, "bounds", "[" + r.left + "," + r.top + "][" + r.right + "," + r.bottom + "]");
        x.append(">");
        for (int i = 0; depth < 100 && i < n.getChildCount(); i++) {
            android.view.accessibility.AccessibilityNodeInfo child = n.getChild(i);
            if (child != null && child.isVisibleToUser()) node(x, child, depth + 1);
        }
        x.append("</node>");
    }

    /**
     * `fill [FIELD] TEXT`: sets a text field on [displayId] to [value] at once, as an accessibility service would (any
     * language, no keyboard): the field named [field] (by its hint, label, description or what it holds, as `shot
     * --text` lists it), or with none the one with the cursor, or the only one there is. Says what it now holds, or
     * why it didn't, with the fields there are.
     */
    static String fill(int displayId, String field, String value) throws Exception {
        return onWindows(displayId, windows -> {
            java.util.List<android.view.accessibility.AccessibilityNodeInfo> fields = new java.util.ArrayList<>(), words = new java.util.ArrayList<>();
            android.view.accessibility.AccessibilityNodeInfo focused = null;
            for (android.view.accessibility.AccessibilityWindowInfo win : windows) {
                android.view.accessibility.AccessibilityNodeInfo root = win.getRoot();
                if (root == null) continue;
                editable(root, fields, words, 0);
                android.view.accessibility.AccessibilityNodeInfo f = root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT);
                if (focused == null && f != null && isField(f)) focused = f;
            }
            android.view.accessibility.AccessibilityNodeInfo target;
            if (field.isEmpty()) {
                target = focused != null ? focused : fields.size() == 1 ? fields.get(0) : null;
                if (target == null) return fields.isEmpty() ? "no text field on the screen"
                        : "no field has the cursor: name one (fill FIELD TEXT). Fields:" + listFields(fields, words);
            } else {
                String want = plain(field);
                java.util.List<android.view.accessibility.AccessibilityNodeInfo> named = new java.util.ArrayList<>(), partly = new java.util.ArrayList<>();
                for (android.view.accessibility.AccessibilityNodeInfo f : fields) {
                    for (String name : names(f, words)) {
                        if (name.equals(want)) { named.add(f); break; }
                        if (name.contains(want) && !partly.contains(f)) partly.add(f);
                    }
                }
                if (named.isEmpty()) named = partly;
                if (named.size() != 1) return (named.isEmpty() ? "no field “" + field + "”" : "“" + field + "” names " + named.size() + " fields")
                        + (fields.isEmpty() ? " (no text field on the screen)" : ". Fields:" + listFields(named.isEmpty() ? fields : named, words));
                target = named.get(0);
            }
            // Focused first, so a key pressed next (Enter: search, send) goes to it
            if (!target.isFocused()) target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS);
            android.os.Bundle args = new android.os.Bundle();
            args.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
            if (!target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                text(value, displayId);
                return "this field doesn't take text set at once: typed it at its cursor instead (look to check)";
            }
            String name = fieldName(target, words);
            if (target.isPassword()) return "filled the password field" + (name.isEmpty() ? "" : " “" + name + "”");
            // What it holds now, once it shows (a web page's field takes a moment); an app may also change it (a format)
            CharSequence now = null;
            for (long give = SystemClock.uptimeMillis() + 600; ; SystemClock.sleep(50)) {
                target.refresh();
                now = target.getText();
                if ((now != null && now.length() > 0) == !value.isEmpty() || SystemClock.uptimeMillis() > give) break;
            }
            return (name.isEmpty() ? "the field" : "“" + name + "”") + " now holds: " + (now == null ? "" : now);
        });
    }

    /** The editable fields the owner could see under [n], and the words around them (what may label one). */
    static void editable(android.view.accessibility.AccessibilityNodeInfo n, java.util.List<android.view.accessibility.AccessibilityNodeInfo> fields, java.util.List<android.view.accessibility.AccessibilityNodeInfo> words, int depth) {
        if (isField(n)) fields.add(n);
        else if (n.getText() != null && n.getText().toString().trim().length() > 0) words.add(n);
        for (int i = 0; depth < 100 && i < n.getChildCount(); i++) {
            android.view.accessibility.AccessibilityNodeInfo child = n.getChild(i);
            if (child != null && child.isVisibleToUser()) editable(child, fields, words, depth + 1);
        }
    }

    /** A text field: editable, or an EditText (Chrome doesn't call a web page's fields editable), as `shot --text` sees it. */
    static boolean isField(android.view.accessibility.AccessibilityNodeInfo n) {
        return n.isEditable() || (n.getClassName() != null && n.getClassName().toString().contains("EditText"));
    }

    /** Words drawn inside field [f] (a label of its own that isn't its hint, as some forms have), or null. */
    static CharSequence inside(android.view.accessibility.AccessibilityNodeInfo f, java.util.List<android.view.accessibility.AccessibilityNodeInfo> words) {
        android.graphics.Rect r = new android.graphics.Rect(), w = new android.graphics.Rect();
        f.getBoundsInScreen(r);
        for (android.view.accessibility.AccessibilityNodeInfo n : words) {
            n.getBoundsInScreen(w);
            if (r.contains(w.centerX(), w.centerY())) return n.getText();
        }
        return null;
    }

    /** What a field goes by, plain (see [plain]): its hint, label, description, id and what it holds. */
    static java.util.List<String> names(android.view.accessibility.AccessibilityNodeInfo f, java.util.List<android.view.accessibility.AccessibilityNodeInfo> words) {
        java.util.List<String> n = new java.util.ArrayList<>();
        android.view.accessibility.AccessibilityNodeInfo label = f.getLabeledBy();
        for (CharSequence s : new CharSequence[]{f.getHintText(), label == null ? null : label.getText(), f.getContentDescription(), inside(f, words),
                f.getViewIdResourceName() == null ? null : f.getViewIdResourceName().replaceFirst(".*/", "").replace('_', ' '),
                f.isPassword() ? null : f.getText()}) {
            String p = plain(s);
            if (!p.isEmpty()) n.add(p);
        }
        return n;
    }

    /** A field's name as the owner would read it: its hint, label, description or the words inside it. */
    static String fieldName(android.view.accessibility.AccessibilityNodeInfo f, java.util.List<android.view.accessibility.AccessibilityNodeInfo> words) {
        android.view.accessibility.AccessibilityNodeInfo label = f.getLabeledBy();
        for (CharSequence s : new CharSequence[]{f.getHintText(), label == null ? null : label.getText(), f.getContentDescription(), inside(f, words)})
            if (s != null && !s.toString().trim().isEmpty()) return s.toString().trim().replaceAll("\\s+", " ");
        return "";
    }

    static String listFields(java.util.List<android.view.accessibility.AccessibilityNodeInfo> fields, java.util.List<android.view.accessibility.AccessibilityNodeInfo> words) {
        StringBuilder s = new StringBuilder();
        android.graphics.Rect r = new android.graphics.Rect();
        for (android.view.accessibility.AccessibilityNodeInfo f : fields) {
            f.getBoundsInScreen(r);
            String name = fieldName(f, words);
            CharSequence now = f.isPassword() ? null : f.getText();
            s.append("\n  ").append(name.isEmpty() ? "(unnamed)" : "“" + name + "”").append("  @").append(r.centerX()).append(',').append(r.centerY());
            if (now != null && now.length() > 0 && !now.toString().equals(name)) s.append("  holds: ").append(now.length() > 80 ? now.subSequence(0, 80) + "…" : now);
            if (f.isFocused()) s.append("  [focused]");
        }
        return s.toString();
    }

    /** [s] with its spaces collapsed, lower case, for matching names. */
    static String plain(CharSequence s) {
        return s == null ? "" : s.toString().trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    /** One XML attribute; characters XML can't hold (controls, a lone half of a surrogate pair) become spaces. */
    static void attr(StringBuilder x, String name, CharSequence value) {
        if (value == null) return;
        x.append(' ').append(name).append("=\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean pair = Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1));
            if (pair) { x.append(c).append(value.charAt(++i)); continue; }
            if (c == '&') x.append("&amp;");
            else if (c == '<') x.append("&lt;");
            else if (c == '>') x.append("&gt;");
            else if (c == '"') x.append("&quot;");
            else if (c == '\n') x.append("&#10;");
            else if ((c < 0x20 && c != '\t') || Character.isSurrogate(c) || c == 0xFFFE || c == 0xFFFF) x.append(' ');
            else x.append(c);
        }
        x.append('"');
    }

    /** The image as it comes when its rows are padded wider than the display: reused, only its cut to size is kept. */
    static Bitmap wide;

    static Bitmap toBitmap(Image img, int w, int h) {
        Image.Plane p = img.getPlanes()[0];
        int rowPx = p.getRowStride() / p.getPixelStride();
        if (rowPx == w) {
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            b.copyPixelsFromBuffer(p.getBuffer());
            return b;
        }
        if (wide == null || wide.getWidth() != rowPx || wide.getHeight() != h) wide = Bitmap.createBitmap(rowPx, h, Bitmap.Config.ARGB_8888);
        wide.copyPixelsFromBuffer(p.getBuffer());
        return Bitmap.createBitmap(wide, 0, 0, w, h);
    }

    /**
     * phone-agent, which this helper serves: gone once its pid is, or is another process's (another start time). Root
     * and proot share Android's pid namespace, so its pid is Android's. Never gone when there's none, or when this
     * helper can't tell: /proc may hide other users' processes from it, or not let it read them.
     */
    static final class Lifeline {
        final String pid;
        final String start;  // its start time, when it can be read

        Lifeline(String pid) {
            this.pid = pid.matches("[1-9][0-9]*") ? pid : null;
            this.start = this.pid != null ? startTime(this.pid) : null;
        }

        boolean gone() {
            if (pid == null) return false;
            if (start != null) return !start.equals(startTime(pid));
            // Not read from the start: gone once it isn't there at all, while other users' processes (init) are.
            return !new File("/proc/" + pid).exists() && new File("/proc/1").exists();
        }

        /** Field 22 of /proc/PID/stat, or null (none, or a zombie). The name, field 2, may hold spaces: count from its ")". */
        static String startTime(String pid) {
            try {
                String stat = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/proc", pid, "stat")),
                        java.nio.charset.StandardCharsets.US_ASCII);
                String[] f = stat.substring(stat.lastIndexOf(')') + 2).split(" ");  // from field 3, the state
                return f[0].equals("Z") || f[0].equals("X") ? null : f[19];
            } catch (Exception e) {
                return null;
            }
        }
    }

    /** Sent instead of an app's name when nothing is in front on the display. */
    static final int NO_APP = Integer.MIN_VALUE;

    /** The live view's clients, as they asked for it. */
    static final java.util.Set<View> clients = java.util.concurrent.ConcurrentHashMap.newKeySet();
    static volatile Bitmap latest;
    /** The package in front on this display, polled about once a second while someone watches. */
    static volatile String topApp;

    static Object taskManager;
    static java.lang.reflect.Method rootTasksOnDisplay;
    static boolean topFailed;

    // The visible activity on top of this display (its component, flattened), or null. IActivityTaskManager, as shell
    // (MANAGE_ACTIVITY_TASKS); its root tasks come top first.
    static String topActivity(int displayId) {
        try {
            if (rootTasksOnDisplay == null) {
                taskManager = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
                rootTasksOnDisplay = taskManager.getClass().getMethod("getAllRootTaskInfosOnDisplay", int.class);
            }
            for (Object info : (java.util.List<?>) rootTasksOnDisplay.invoke(taskManager, displayId)) {
                Object top = info.getClass().getField("topActivity").get(info);
                if (info.getClass().getField("visible").getBoolean(info) && top instanceof android.content.ComponentName)
                    return ((android.content.ComponentName) top).flattenToShortString();
            }
        } catch (Exception e) {
            if (!topFailed) System.out.println("topActivity: " + e);
            topFailed = true;
        }
        return null;
    }

    /** The package of the visible app on top of this display, or null. */
    static String topPackage(int displayId) {
        String top = topActivity(displayId);
        return top == null ? null : top.substring(0, top.indexOf('/'));
    }

    /** What one client asked for with its "v WIDTH FPS" line, and when it is due for its next frame. */
    static final class View {
        volatile int width;          // frames scaled to at most this many pixels wide; 0: full size
        volatile int interval = 100; // at least this many ms between frames
        volatile long due;           // uptime of its next frame
        volatile boolean gone;       // the client hung up

        /** Takes a "v WIDTH FPS" line; false if [line] isn't one. */
        boolean set(String line) {
            String[] a = line.trim().split(" ");
            if (a.length != 3 || !a[0].equals("v")) return false;
            try {
                width = Math.max(0, Integer.parseInt(a[1]));
                interval = 1000 / Math.max(1, Math.min(10, Integer.parseInt(a[2])));
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
    }

    /** Whether some client is due for a frame: only then is the display's image copied out. */
    static boolean frameDue() {
        long now = SystemClock.uptimeMillis();
        for (View v : clients) if (now >= v.due) return true;
        return false;
    }

    // Live view for the app on 127.0.0.1:<port>. A client first proves it knows this task's token (DROID_TOKEN) and
    // says how it wants frames (see handshake). It then gets what is in front on the display, right away and whenever
    // it changes, [negative length][the app's package name, UTF-8] or [NO_APP] when there is none (the agent hasn't
    // used its screen, or the owner took the app away), and [4-byte big-endian length][JPEG] frames at most WIDTH
    // pixels wide (0: full size), at most FPS a second, only when the screen changed. It may send touches for this
    // display (see readInput).
    static void serveFrames(java.net.ServerSocket server, String token, int displayId) {
        while (true) {
            try {
                java.net.Socket client = server.accept();
                Thread t = new Thread(() -> serve(client, token, displayId));
                t.setDaemon(true);
                t.start();
            } catch (Exception e) {
                // one client that couldn't be taken (or a thread that couldn't start): the next may be
                System.out.println("frame server: " + e);
                SystemClock.sleep(100);
            }
        }
    }

    static void serve(java.net.Socket client, String token, int displayId) {
        View view = new View();
        try (java.net.Socket c = client) {
            if (!handshake(c, token, view)) return;
            clients.add(view);
            topApp = topPackage(displayId);  // as it is now: polled only while someone watches
            Thread reader = new Thread(() -> readInput(c, displayId, view));
            reader.setDaemon(true);
            reader.start();
            Bitmap sent = null;
            String sentApp = null;
            boolean told = false;  // whether this client has heard what is in front
            java.io.DataOutputStream out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(c.getOutputStream()));
            while (!view.gone) {
                String app = topApp;
                if (!told || !java.util.Objects.equals(app, sentApp)) {
                    told = true;
                    sentApp = app;
                    if (app == null) out.writeInt(NO_APP);
                    else {
                        byte[] name = app.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        out.writeInt(-name.length);
                        out.write(name);
                    }
                    out.flush();
                }
                Bitmap frame = latest;
                long now = SystemClock.uptimeMillis();
                if (frame != null && frame != sent && now >= view.due) {
                    sent = frame;
                    view.due = now + view.interval;
                    int width = view.width;
                    Bitmap scaled = width > 0 && width < frame.getWidth()
                            ? Bitmap.createScaledBitmap(frame, width, Math.max(1, frame.getHeight() * width / frame.getWidth()), true)
                            : frame;
                    java.io.ByteArrayOutputStream jpeg = new java.io.ByteArrayOutputStream();
                    scaled.compress(Bitmap.CompressFormat.JPEG, 80, jpeg);
                    if (scaled != frame) scaled.recycle();
                    out.writeInt(jpeg.size());
                    jpeg.writeTo(out);
                    out.flush();
                }
                Thread.sleep(100);
            }
        } catch (Exception e) {
            // client went away
        } finally {
            clients.remove(view);
        }
    }

    // Touches from Watch's take over, one per line, in the display's pixels: "d X Y" (finger down), "m X Y" (move),
    // "u X Y" (up), "c" (cancel the touch: no tap), "k KEYCODE" (a key press, e.g. 4 for Back), "t TEXT" (typed text, from
    // the owner's own keyboard: this display never has one, see vd-start.sh). Injected into this display like `input -d`,
    // which shell may do. A touch left down (a new "d", or the client going away) is cancelled,
    // not lifted: a lift would count as a tap. A new "v WIDTH FPS" changes how this client's frames come; "h 1" / "h 0"
    // says whether the app holds the display (see held).
    static void readInput(java.net.Socket client, int displayId, View view) {
        long down = 0;  // the touch's down time; 0: no finger down
        float x = 0, y = 0;
        try {
            java.io.BufferedReader in = new java.io.BufferedReader(
                    new java.io.InputStreamReader(client.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            for (String line; (line = in.readLine()) != null; ) {
                String[] a = line.trim().split(" ");
                try {
                    switch (a[0]) {
                        case "t":
                            if (line.startsWith("t ")) text(line.substring(2), displayId);
                            break;
                        case "d":
                            if (down != 0) touch(MotionEvent.ACTION_CANCEL, down, x, y, displayId);
                            x = Float.parseFloat(a[1]); y = Float.parseFloat(a[2]);
                            down = SystemClock.uptimeMillis();
                            touch(MotionEvent.ACTION_DOWN, down, x, y, displayId);
                            break;
                        case "m":
                        case "u":
                            if (down == 0) break;
                            x = Float.parseFloat(a[1]); y = Float.parseFloat(a[2]);
                            boolean up = a[0].equals("u");
                            touch(up ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE, down, x, y, displayId);
                            if (up) down = 0;
                            break;
                        case "c":
                            if (down != 0) touch(MotionEvent.ACTION_CANCEL, down, x, y, displayId);
                            down = 0;
                            break;
                        case "k":
                            key(Integer.parseInt(a[1]), displayId);
                            break;
                        case "v":
                            view.set(line);
                            break;
                        case "h":
                            held = a.length > 1 && a[1].equals("1");
                            break;
                    }
                } catch (RuntimeException e) {
                    // a malformed line; skip it
                }
            }
        } catch (java.io.IOException e) {
            // client went away
        }
        if (down != 0) touch(MotionEvent.ACTION_CANCEL, down, x, y, displayId);
        view.gone = true;  // so it stops counting as a watcher now, not at its next frame
    }

    static void touch(int action, long downTime, float x, float y, int displayId) {
        MotionEvent.PointerProperties p = new MotionEvent.PointerProperties();
        p.id = 0;
        p.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords c = new MotionEvent.PointerCoords();
        c.x = x;
        c.y = y;
        c.pressure = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE ? 1f : 0f;
        c.size = 1f;
        MotionEvent e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1,
                new MotionEvent.PointerProperties[]{p}, new MotionEvent.PointerCoords[]{c},
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        inject(e, displayId);
        e.recycle();
    }

    static void key(int code, int displayId) {
        long now = SystemClock.uptimeMillis();
        inject(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD), displayId);
        inject(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD), displayId);
    }

    // Text as the key presses that type it, like `input text`. A character no key types (an emoji, say) is skipped.
    static void text(String s, int displayId) {
        KeyCharacterMap map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        StringBuilder other = new StringBuilder();  // what no key types (é, ñ, emoji, CJK): sent as characters
        for (int i = 0; i < s.length(); i = s.offsetByCodePoints(i, 1)) {
            char[] c = Character.toChars(s.codePointAt(i));
            KeyEvent[] keys = map.getEvents(c);
            if (keys == null) { other.append(c); continue; }
            characters(other, displayId);
            for (KeyEvent k : keys) inject(KeyEvent.changeTimeRepeat(k, SystemClock.uptimeMillis(), 0), displayId);
        }
        characters(other, displayId);
    }

    /** [s] as one key event that carries characters (ACTION_MULTIPLE), which text fields insert as typed. */
    static void characters(StringBuilder s, int displayId) {
        if (s.length() == 0) return;
        inject(new KeyEvent(SystemClock.uptimeMillis(), s.toString(), KeyCharacterMap.VIRTUAL_KEYBOARD, 0), displayId);
        s.setLength(0);
    }

    static Object inputManager;
    static java.lang.reflect.Method injectEvent, setDisplayId;

    // InputManagerGlobal (Android 14+) injects for the calling uid; shell holds INJECT_EVENTS. Asynchronous, like scrcpy.
    static synchronized void inject(InputEvent e, int displayId) {
        try {
            if (inputManager == null) {
                Class<?> c = Class.forName("android.hardware.input.InputManagerGlobal");
                inputManager = c.getMethod("getInstance").invoke(null);
                injectEvent = c.getMethod("injectInputEvent", InputEvent.class, int.class);
                setDisplayId = InputEvent.class.getMethod("setDisplayId", int.class);
            }
            setDisplayId.invoke(e, displayId);
            injectEvent.invoke(inputManager, e, 0);  // INJECT_INPUT_EVENT_MODE_ASYNC
        } catch (Exception x) {
            System.out.println("inject: " + x);
        }
    }

    // Any app may listen on a local port or connect to one, so both sides prove they know the task's token without
    // sending it, as with Droid's bridge (Bridge.java): the app checks that this is the helper before it shows frames
    // or sends touches, and the helper that it is the app. Then the client says how it wants frames. Within 2 s:
    //   client -> 32 random bytes A
    //   server -> HMAC(token, "droid-screen-server" + A), then 32 random bytes B
    //   client -> HMAC(token, "droid-screen-client" + B), then "v WIDTH FPS\n"
    static boolean handshake(java.net.Socket client, String token, View view) {
        try {
            client.setSoTimeout(2000);
            java.io.DataInputStream in = new java.io.DataInputStream(client.getInputStream());
            byte[] a = new byte[32];
            in.readFully(a);
            byte[] b = new byte[32];
            new java.security.SecureRandom().nextBytes(b);
            java.io.ByteArrayOutputStream hello = new java.io.ByteArrayOutputStream();
            hello.write(hmac(token, "droid-screen-server", a));
            hello.write(b);
            client.getOutputStream().write(hello.toByteArray());
            byte[] proof = new byte[32];
            in.readFully(proof);
            if (!java.security.MessageDigest.isEqual(proof, hmac(token, "droid-screen-client", b))) return false;
            // The "v" line, a byte at a time: what follows it is readInput's.
            java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
            for (int c; (c = in.read()) != '\n'; ) {
                if (c < 0 || line.size() > 64) return false;
                line.write(c);
            }
            if (!view.set(line.toString("US-ASCII"))) return false;
            client.setSoTimeout(0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static byte[] hmac(String token, String label, byte[] nonce) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII), "HmacSHA256"));
        mac.update(label.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        return mac.doFinal(nonce);
    }
}
