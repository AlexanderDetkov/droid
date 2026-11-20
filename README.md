# Droid

Claude Code on your Android phone, rooted or not. Tell it what to do, by voice or text, and it uses your apps on a hidden screen while you keep using the phone, even while it's locked.

- Chats like ChatGPT; each one is a Claude Code session you can come back to. Search (titles and messages), pin, rename, share or delete them.
- Several tasks at once; send more while one runs; stop any of them, or retry one that failed.
- **The agent designs what you see**: an answer can be a page it builds for the task (choices to tap, a draft to edit and send, a timeline, a table to filter), seamless with the chat. It can show one mid-task and wait for your answer, put one full screen or over the app you're in, post notifications as rich as Android allows (buttons that act, a reply field, a picture, progress, reminders), say how Dash looks and what he says (only it makes it your turn), and wake itself up later.
- Each turn's work folds into "Worked for 1m 05s"; tap it for every step on a timeline.
- **Attach** photos or files, or show Droid what's in front of you with its own **camera** (+ → Camera): the chat becomes the view, in Dash's frame, and a tap puts the picture in your message. Send while it's still live and the picture is taken then. The agent gets a copy it can open or send on.
- **Agents**: Claude Code (default), OpenAI's Codex or OpenCode (Anthropic, OpenAI, Google, OpenRouter, local models), chosen in Settings and set up in Setup. A chat keeps the agent it started with.
- **Model**: pick Opus, Sonnet, Haiku or your plan's default from the title bar.
- **Dash**, Droid's character, shows what Droid is doing: a soft periwinkle creature with a hooked antenna and two dark eyes. He sits beside "Working" while a task runs without an app; when an app opens on the hidden screen he grows into the frame of a small live window of it, his eyes in a rounded head on its top edge that slides toward where the agent taps. A task that uses no app (Linux, the web, files) keeps him small. Tap the window to **Watch** it full size: the same frame grows to fill the phone (his head clear of the status bar and the camera cutout) and shrinks back when you close it, with its controls in a bar below it so nothing covers the screen. **Take over** puts your touches on it, and its keyboard button your typing, to sign, log in or pay where the agent leaves that to you, and the agent is told you did; Dash goes pale and closes his eyes while you drive. His pose comes from the commands the agent runs, mostly in his eyes and a little in his body: reading a screen line by line, eyes down and moving like a caret as he types, riding a scroll, sweeping side to side as he searches the web, half-lidded and slowly rocking while an app loads, looking up and away while he thinks. A step gets a small nod, something it says a look up at you, an app opening a start. After the answer he shrinks with the window to a still of where Droid left off, his edge draining over the time the screen stays open, and lets go when it closes. How he ends a turn is the agent's to say (`droid dash`, below), never guessed from its answer: `needs_you` has him call you, beside **Your turn** and the agent's words in his periwinkle (a page the agent waits on does too, mid-task): he stands tall, eyes on you, softly lit all round, signal rings going out from his tip, and now and then his antenna waves (the first two times with a little hop). Any other feeling it picks (`oops` for bad news, `celebrate`, `shy`, a face of its own) stays beside the answer with its words. A turn that couldn't finish leaves him tilted and puzzled above the error; one the agent said nothing about gets one happy hop, and he goes. While he waits on you he reads along as you type and listens while you talk. The drawer's **Now** row shows every task that is up, framed by Dash while an app is on its screen, and says **Your turn** when one waits on you; chats that asked you something or couldn't finish in the last 12 hours keep a small Dash in the list. Notifications carry his picture in the same pose. He greets you on a new chat and at start-up, and his head is the assistant panel's grabber. He is drawn to stay smooth and clean: every number he's drawn from is on an exact damped spring, gestures are short curves that start and end at rest (nothing kicks him, nothing is random frame to frame), his body is one outline whose corners and head are clamped to the room they have and his eyes are filled shapes (no path booleans, clips or strokes on closed shapes, which could fold, tear or alias), and all of him stays inside his own box, so lists don't cut him off and he never covers what's beside him. With Android's animations off he doesn't move on his own (no hops, glances or calls) but still eases into each new pose. The debug build's DashLab checks this frame by frame (`adb shell am start -n com.adetkov.droid/.DashLab --es page smooth`). Rig and drawing: [`Dash.kt`](app/src/main/java/com/adetkov/droid/Dash.kt); the site's `dash.js` (droid-demo) is the same rig and drawing for the web. The launcher icon (Dash peeking up from the bottom of a periwinkle field), the themed and status bar icons and the splash are drawn from the same proportions by [`tools/icons.py`](tools/icons.py).
- After an answer with an app on the hidden screen, the screen stays as the agent left it for 10 minutes (as long as you watch it): a reply continues there, and you can take it over. Without one the task simply ends, and a reply resumes it.
- **Voice**, all on the phone: the mic dictates into the box; voice mode sends when you stop talking and reads the answer aloud.
- **Assistant**: long-press power to talk to it from any app or the lock screen. Over an app, your first request takes a screenshot of it along; over the home screen (Android 15 and later) or the lock screen it doesn't (tap ✕ on "Your screen" to leave it out, or turn it off in Settings). Or tap **Camera**: the screen behind the panel becomes the camera, in Dash's frame, and you point it while you talk. Its picture goes in place of your screen, from your last word (not from when Droid decides you're done); tap the view to take it sooner, again to retake. It works on the lock screen too; the request still waits for voice match or your fingerprint.
- **Voice match** (optional): on the lock screen, a request in your voice goes through without your fingerprint.
- **Instructions and memory**: your instructions (Claude Code's `CLAUDE.md`), which every agent gets at the start of every task, and Claude's memory, which Claude Code keeps by itself in its own way (notes and an index it reads in every task). Droid adds nothing to how memory is made; in Settings you can edit your instructions, and read, change or delete any of Claude's notes. Codex and OpenCode keep no memory in Droid: OpenCode has none, and Codex's learns only from its interactive sessions, not `codex exec`.
- **Answers that do things**: Droid can show a picture or file in its answer (a screenshot of the draft it wrote, a PDF it made), offer replies you tap (in the chat or straight from the notification), and hand you its screen for something you do yourself, then carry on when you tap Done.
- **Settings**: light, dark or system theme, haptics, reading answers aloud. On Pixels the app uses the system's Google Sans. **About** has the version, the source and the licenses. When a newer Droid is out, Settings says so at the top, linked to its release; it asks GitHub at most once a day and installs nothing itself.

## Requirements

- Android 14 or later, arm64. Tested on a Pixel 6a with Android 17.
- Either root (Magisk, KernelSU or APatch), or none: Developer options with Wireless debugging, which Droid pairs with
  once. Without root, Droid needs Wi-Fi when it starts its helper (once after each restart); after that, mobile data is
  fine. Droid turns USB debugging on and needs it, and Developer options, left on (see Security).
- A Claude account: a Pro or Max subscription, or an Anthropic Console account billed per use.
- About 350 MB of storage, plus 820 MB if you want voice, and 38 MB more for voice match.

## Install

1. Install the APK for your phone (`arm64`) from [Releases](https://github.com/AlexanderDetkov/droid/releases).
2. Open Droid. Setup walks you through the rest, all from the app:
   - **Root access**: allow Droid in your root manager. Or, without root, **Wireless debugging**: tap Pair, turn on
     Developer options and Wireless debugging (allow it on your network, with Always allow ticked), tap Pair device with
     pairing code, and type the code into Droid's notification. Droid asks for Nearby devices first (Android 17 needs it
     to find Wireless debugging on the network) and for notifications. Droid uses root if it has it when it first
     checks; Setup can switch either way later. Root and Wireless debugging each have their own Linux, sign-in and
     history, and a chat from the other one goes on in a new session.
   - **Linux and Claude Code**: downloads Alpine Linux (checked against Alpine's SHA-256), installs a few packages and Claude Code (from `claude.ai/install.sh`) into `/data/local/droid` (without root, `/data/local/tmp/droid`). Takes about a minute.
   - **Claude account**: opens the Claude sign-in page in your browser; paste the code it shows back into Droid.
   - **Voice** (optional): downloads the speech models.
   - **Assistant** (optional): makes Droid the long-press-power assistant, with Android's "Use screenshot" on.

Voice match is set up later, from Settings → **Voice match**: download its model, then read six sentences aloud.

Setup → **Update** upgrades Alpine's packages and updates Claude Code (and Codex or OpenCode, if you set them up); Droid's own scripts in its Linux come with each app update. Setup → **Remove Droid's Linux** deletes everything Droid put on the phone: with root both Linuxes (`/data/local/droid` and `/data/local/tmp/droid`), without root everything under `/data/local/tmp/droid` but the helper; then uninstall the app as usual. Without root, also remove "droid@…" from Wireless debugging's paired devices, and turn USB debugging off if you don't otherwise use it.

## Security

Droid gives Claude a root shell on your phone, with web access (without root, a shell with adb's rights: it can use
your apps and change settings, but not read other apps' private data). Anything it reads (web pages, messages, files) could try to steer it into doing something you didn't ask for, and as root it can read every app's data. Give it tasks you'd trust it with, and keep an eye on Watch for anything sensitive. The default instructions tell it to confirm the recipient and the content on screen before sending anything, and to ask when unsure.

**Without root, Droid keeps USB debugging on**, since its helper ends when adbd stops. A computer you plug the phone into can then ask to debug it, which still needs your OK on the phone, and some apps (banking apps, for example) refuse to run while it's on.

**Voice match** replaces your fingerprint with your voice, which is a much weaker lock. It checks how a voice sounds, not that you're the one speaking, so a recording or an AI copy of your voice can pass, and whoever passes gets everything Droid can do. In a test (below), copies of a voice made by Pocket TTS, Droid's own voice model, from one 10-second clip passed 7% of the time; better cloning tools will do better. After 3 misses in a row it pauses until the phone is unlocked. It's off until you set it up.

**Privacy**: the agent works with whatever its provider (Anthropic, OpenAI or the one you pick in OpenCode) is sent: your requests, screenshots of the hidden screen and anything else it reads. Droid itself sends nothing anywhere except Setup's downloads and, once a day, a request for GitHub's latest release. Voice, voice match and the camera run on the phone.

## How it works

```
root:     Droid app ──su──▶ /data/local/droid/enter.sh ──chroot──▶ Alpine: phone-agent ──▶ claude -p (stream-json)
                              private mount namespace                 │
no root:  Droid app ──Bridge──▶ /data/local/tmp/droid/enter.sh ──proot──┤
          (the shell user,                                           └─ VdHelper (app_process, as shell): hidden display
           started over Wireless debugging)
```

Everything the hidden display needs (a VirtualDevice, launching apps on its display, input, screenshots) works as
Android's shell user, uid 2000; root is only one way to become it. The other is Wireless debugging, which an app on the
phone can use on itself, as Shizuku does. So Droid runs the same agent either way, and the app reaches it through
`Host` ([`Host.kt`](app/src/main/java/com/adetkov/droid/Host.kt)): `su -c`, or Droid's helper.

- **`enter.sh`** enters the chroot as root inside a private mount namespace (the root manager's busybox `unshare --propagation private`). `/proc`, `/sys`, `/dev` and `/sdcard` are mounted for that run only, so nothing leaks into Android's namespaces and removing the rootfs never reaches through a mount. It also sets the run's `oom_score_adj` to 200: root shells inherit -1000 (unkillable) from the root daemon, so a runaway command would otherwise leave the kernel nothing to kill and Android would reboot.
- **`phone-agent`** starts one task: it creates a hidden display the size of your screen, prints `{"type":"agent","pid","display","port","token"}`, then runs Claude Code with stream-json in and out and only general tools (Bash, Read, Write, Edit, Grep, Glob, WebSearch, WebFetch). Besides the chat, the agent gets two texts: the app's instructions ([`system.md`](app/src/main/assets/setup/system.md), which the app writes to `~/.droid/system.md` for this phone, with root or not, at each start) and the owner's instructions (`~/.claude/CLAUDE.md`). Claude gets `system.md` with `--append-system-prompt-file` and reads `CLAUDE.md` itself, along with its own memory; Codex and OpenCode get both in their `AGENTS.md`.
- **What the agent shows** is the agent's to design ([`Pages.kt`](app/src/main/java/com/adetkov/droid/Pages.kt), [`PageView.kt`](app/src/main/java/com/adetkov/droid/PageView.kt), [`system.md`](app/src/main/assets/setup/system.md)). Its answers are Markdown that can hold any HTML, CSS and JS: an answer with HTML renders in the chat as one web page, seamless with it (no frame, the chat's background, its text set exactly like a Markdown answer: [`droid.css`](app/src/main/assets/page/droid.css) gives plain HTML the app's font, colors as theme variables, and a few classes the agent may use, such as `option`, `primary` and `card`). The Markdown in it goes through [marked](https://github.com/markedjs/marked) 15.0.12 (MIT, `assets/page/marked.min.js`), with a line break kept as one, as in Droid's own answers; a block of HTML stays markup, indented lines included, while a part of it after a blank line that doesn't start with a tag is Markdown again (as in CommonMark: a `<div>` around a list), except inside a `<textarea>`, `<pre>`, `<script>` or `<style>`, which stays whole. The stylesheet's colors are variables named for what they're for (`--surface` a card or field, `--raised` one on a card, `--ink`/`--on-ink` a main button and its text, `--call`/`--on-call` Dash's accent and text on it), and controls get their size from padding, so a page's own padding makes a compact chip. A page's script has `droid` ([`droid.js`](app/src/main/assets/page/droid.js)): `send(text, data)` is a message from you (the chat shows the text, the agent also gets the data), `run(command)` runs a command in the Linux and returns its output without an agent turn, `dash(feeling, words)`, `screen()` hands over the hidden screen, `open(url)`, `close()`. Each page lives at its own https origin (`p….droid.page`, served by the app), so what it keeps in localStorage is its own; `droid` reaches only those origins' main frames (androidx.webkit's message listener, not a JavaScript interface every frame gets); send, run, screen and open work only within 15 s of your tap on the page; a page answers once: after it sends, is answered, or a later turn begins, its controls dim and stop acting (so a second tap can't repeat what the first did); and of the agent's Linux a page can load only the files its own HTML names, so it can't read the rest (the Claude sign-in, say); and it runs scripts only from its own origin (a Content-Security-Policy), so none from the web gets `droid`: the agent downloads a library into its Linux and names it by its path. In the chat a page is as tall as its content and a vertical drag that starts on it scrolls and flings the chat, measured by the finger's moves (the page moves with the chat, so its place on the page says nothing); a touch while the chat still moves stops it. A tap or drag on the chat puts the keyboard away, except a tap in a page, which may be on its own field. A page in the latest turn may set Dash. `![caption](path)` still shows a picture or file: the app copies it out of the Linux (through `enter.sh`, so the path is the agent's) into `files/shown/<chat>/`. Links an earlier Droid made buttons of (`[label](reply:text)`, `[label](screen:)`) now read as their labels. Stdin takes messages at any time; `kill <pid>` stops the task. A run with no first message within 3 minutes ends itself: the app starts one early while you talk and drops it after 2 minutes unused (and never gives one older than 2.5 minutes a message), but it can't while Android has the app frozen, and the hidden display keeps the phone from sleeping.
- **`droid`**, the agent's command ([`bin/droid`](app/src/main/assets/setup/bin/droid), Python): `droid dash FEELING [WORDS]` sets Dash and the words beside him (`droid feelings` lists them; a JSON face, such as `{"smile": 0.6}`, works too, every number kept to the range the rig draws well); `droid show FILE [--full|--float] [--wait] [--id ID]` shows a page now, in the chat (a PAGE message), full screen ([`PageActivity`](app/src/main/java/com/adetkov/droid/PageActivity.kt): Back, the page, a message box), or floating over the app you're in ([`FloatPage`](app/src/main/java/com/adetkov/droid/FloatPage.kt): rising from the bottom edge with Dash peeking over it; it takes the keyboard only once touched); one that can't open there (Android lets an app open a screen from the background only with the right to draw over apps) comes as a notification that opens it; `--wait` prints what the page sends, as `{"text", "data"}`, so the agent goes on in the same turn (the app writes it to `/root/.droid/replies/ID.json`; `droid wait ID` keeps waiting; a wait that ends unanswered tells the app, and a later answer comes as a message), and `--id` shows a page again in place, full screen or floating too; while a page waits, what the owner types in the chat's or the page's message box answers it; `droid notify TITLE [TEXT]` posts a notification the agent shapes as it likes ([`Notify.kt`](app/src/main/java/com/adetkov/droid/Notify.kt); `droid --help` lists it all): up to three buttons, each a message to the agent or `run:COMMAND` (in the Linux, no turn), `open:URL` (a link or app), `page:FILE` (a page full screen), `chat:`, `screen:` (the hidden screen handed over) or `dismiss:`, the same for its tap; a reply field (what's typed comes as a message; the notification shows it sent, then goes); a picture; a progress bar; ongoing; quiet or urgent (a channel each: From Droid, quietly, urgent); private on the lock screen; Dash's feeling as its picture; `--id` to update or take it away; `--at WHEN` to post it later (kept like a wake-up); `--wait` to get back, in the same turn, what the owner did: the button or tap, the reply, `dismissed` if swiped away, or what a `page:` button's page sends. Taps that open a screen go through `NoteActivity` (Android lets only an activity start one), the rest through AgentService; nothing acts from the lock screen but dismiss. The answer's own notification is the id `chat`: the agent's `--id chat` replaces it and `--cancel chat` leaves it out. `droid later WHEN MESSAGE` sets a wake-up in this chat ([`Later.kt`](app/src/main/java/com/adetkov/droid/Later.kt): an exact alarm, kept in `files/later.json`, put back when the phone restarts; its message starts a turn as the agent's own). Its events reach the app as `{"type":"droid",...}` lines in `phone-agent`'s output: the command reopens that pipe through `/proc` (`$DROID_OUT`), so they arrive at once and in order among Claude's; an output that isn't a pipe gets them through a file `phone-agent` copies in. Droid grants itself drawing over other apps and exact alarms through `appops` (as root or the shell user) the first time a page or wake-up needs them. The app writes its scripts (`droid`, `phone-agent`, `droid-engine`, `android`, `shot`, the rest of `bin/`, and `vd-start.sh` and `vd-stop.sh` beside the Linux) at its first run after starting, so a newer app's need no Setup Update.
- **`VdHelper`** (in this APK, started with `app_process` as the shell user, like scrcpy) owns the hidden display. It saves a screenshot when the agent runs `shot`, and streams JPEG frames to the app's Watch view on a free local port (127.0.0.1), bound before the helper prints it. Any app may listen on or connect to a local port, so the app and the helper each prove they know that task's random token without sending it (HMAC challenges each way, as with the bridge), and the app then says `v WIDTH FPS` for frames at most WIDTH pixels wide and FPS a second (the chat's window asks for 300 px at 2 a second, the drawer's for 240 px at 1, Watch for full size); it then also gets what is in front on the display (`IActivityTaskManager`, read when it connects and polled once a second while anyone watches), right away and whenever it changes: `[negative length][package name]`, or `Int.MIN_VALUE` for no app. On the same connection Watch's take over sends touches (`d|m|u X Y`, `c` to cancel), keys (`k KEYCODE`) and what the owner types with their own keyboard (`t TEXT`, as the key presses that type it), which the helper injects into its display through `InputManagerGlobal`, as `input -d` does. The chat keeps each turn's last frame, small, in `files/stills/` ([`ScreenWindow.kt`](app/src/main/java/com/adetkov/droid/ScreenWindow.kt)), so the turn still shows it after the screen is gone. The helper also ends by itself when phone-agent is gone without stopping it (killed, say), so a display can't keep the phone awake after its task.
- **Holding the screen**: after an answer, if an app is in front on the run's screen (the app asks the helper, `screenInUse` in [`ScreenFeed.kt`](app/src/main/java/com/adetkov/droid/ScreenFeed.kt)), the app keeps the run's stdin open for 10 minutes (`HOLD_FOR` in [`Runner.kt`](app/src/main/java/com/adetkov/droid/Runner.kt); paused while Watch is open), so a reply goes to the same process on the same screen. Then it closes stdin, the agent exits and the display goes with what was on it (`DESTROY_CONTENT_ON_REMOVAL`); a later reply starts a fresh `--resume` run on a new screen. A held screen keeps the phone awake like a working one, and keeps the foreground service and an ongoing notification (countdown, Watch, Close screen) up; so an answer with nothing on the screen ends the run at once.
- The model choice is passed as `phone-agent --model <alias>`; `phone-agent` hands its arguments to `claude -p`. A `--resume` of a session this Linux doesn't have (after switching between root and Wireless debugging, or reinstalling the Linux) starts a new session instead of failing, and the chat notes it.
- Attachments are copied into the app's own shared-storage folder, `Android/data/com.adetkov.droid/files/uploads/<id>/`, which other apps can't read. The message tells the agent where each one is, as `[Attached: /sdcard/Android/data/…]` (a picture from Droid's camera as `[Photo I just took: …]`): `enter.sh` bind-mounts shared storage at the chroot's `/sdcard`, and root (or the shell user, whose groups include `ext_data_rw`) can read app folders there.
- **The camera** ([`Camera.kt`](app/src/main/java/com/adetkov/droid/Camera.kt)) is CameraX's preview only (a `PreviewView` on a TextureView, 16:9, pinch to zoom): the picture is the view's own pixels, exactly what was in the frame. While you talk, the voice loop reports each pause, a quarter of a second after your last word, and the view's frame then is kept; the request takes that one, or the one you tapped. The camera runs only while its view is up.
- **Voice match** ([`VoiceMatch.kt`](app/src/main/java/com/adetkov/droid/VoiceMatch.kt)): NVIDIA TitaNet small (sherpa-onnx's ONNX export, checked against its SHA-256) turns speech into 192 numbers. Enrollment averages six read sentences into a voiceprint, kept in the app's private `files/voiceprint.txt` with the model's name. On the lock screen, a request whose cosine similarity to the voiceprint is at least 0.50 is sent without unlocking. A miss, speech under 1.5 s, or a 4th try after 3 misses goes to the fingerprint as before. The miss count starts over when the phone is unlocked (Android's `USER_PRESENT`, whatever app it unlocks to), and when one of Droid's screens resumes only if the keyguard itself says the phone is unlocked: Android briefly resumes an app's other activities behind the lock screen when the assistant panel closes, so "an activity resumed" doesn't mean "unlocked".
- The assistant is a `VoiceInteractionService` ([`AssistService.kt`](app/src/main/java/com/adetkov/droid/AssistService.kt)) whose session has no window: it opens the panel and, over an app, receives the screenshot Android takes before the panel appears. The screenshot becomes an attachment, sent as `[Screenshot of my screen: /sdcard/…]`. Android keeps the service bound, so Droid's process stays running.
### Without root

- **Pairing** ([`adb/`](app/src/main/java/com/adetkov/droid/adb)): Droid's own small adb client: TLS 1.3 with a
  self-signed certificate for its RSA key, the exported keying material tied into SPAKE2 (a BigInteger implementation
  of BoringSSL's, checked against adbd), and the key sent as adb's PeerInfo. [`PairingService`](app/src/main/java/com/adetkov/droid/PairingService.kt)
  finds Settings' pairing dialog by mDNS (`_adb-tls-pairing._tcp`, only at this phone's own addresses) and asks for
  the code in a notification, since the dialog closes when you leave Settings.
- **The helper** ([`Bridge.java`](app/src/main/java/com/adetkov/droid/Bridge.java)): over one adb connection
  (`_adb-tls-connect._tcp`), Droid grants itself `WRITE_SECURE_SETTINGS` and starts `app_process … Bridge` as the shell
  user in its own session. The bridge runs shell commands for the app on 127.0.0.1, streaming stdin, stdout, stderr and
  the exit code; both sides prove they know a random token (HMAC challenges each way), which is never sent. The bridge
  stays in adbd's cgroup, and Android stops adbd, and that whole cgroup, when neither USB nor Wireless debugging is on;
  so the launch first turns USB debugging on. The bridge then runs until the phone restarts, without Wi-Fi or Wireless
  debugging, unless USB debugging or Developer options go off. A bridge that isn't listening within 30 s is killed, and
  Droid pings it before using it.
- **After a restart** ([`HelperJob`](app/src/main/java/com/adetkov/droid/HelperJob.kt)): once there is Wi-Fi, Droid
  switches Wireless debugging on, connects, starts the bridge, switches it off again and checks that the bridge still
  answers (a few seconds). On a Wi-Fi network Wireless debugging doesn't trust yet, Android asks first; if nobody allows
  it, Droid doesn't ask again on that connection until you tap Start in Setup. After an app
  update it starts a bridge with the new code, but only while the old one runs nothing. Until the helper runs (say,
  after a restart away from Wi-Fi), the chats stay open and a task says what it's waiting for.
- **Is it adbd?** adbd's certificate for connections changes with every boot, so it can't be pinned, and any app may
  advertise a port under adbd's mDNS name. So the launch command also writes a fresh nonce into Droid's own
  `Android/data` folder, which the shell user may write and other apps may not; without it, Droid never uses the token.
- **Linux**: Alpine in `/data/local/tmp/droid/rootfs`, where the shell user may run programs, under proot (Termux's
  fork, built from source by [`tools/proot/build.sh`](tools/proot/build.sh) and carried in the APK as `libproot.so`).
  `android` reaches the bridge with `droid-bridge` (Python); `shot` reads the display's folder, bound at its own path.

- Inside Alpine, `android '<command>'` runs a command in Android as root, or as the shell user without root (`input -d $AGENT_DISPLAY tap ...`, `am start --display ...`). The agent gets nothing else: no per-app tools, recipes or UI-tree scaffolding. It works the phone out from screenshots and a shell.

| On the phone | What |
|---|---|
| `/data/local/droid/enter.sh` | enters the chroot (no command: a login shell) |
| `/data/local/droid/rootfs` | Alpine with Claude Code in `/root/.local/bin` |
| `rootfs/usr/local/bin/{phone-agent,android,shot,droid,droid-engine,droid-bridge,droid-install-engine}` | the scripts above |
| `rootfs/root/.droid/replies/` | the owner's answers to pages the agent waits on (`droid show --wait`), one file each until read |
| `rootfs/root/.claude/CLAUDE.md`, `settings.json` | the owner's instructions (edited in the app), and tool permissions |
| `rootfs/root/.claude/projects/-root/memory/` | Claude Code's own memory: `MEMORY.md` (its index) and one note per file; shown in the app's Memory page |
| `rootfs/root/.droid/system.md` | the app's instructions for the agent, written by the app |
| `/data/local/tmp/droid/vd/<display>` | a running task's screenshots (and its helper's log) |
| `/data/local/droid/vd-start.sh`, `vd-stop.sh` | start and end a task's hidden display, run in Android by `phone-agent` |
| `/data/local/tmp/droid/{enter.sh,rootfs,bin/proot}` | the same, without root (mode 700, the shell user's) |
| `/data/local/tmp/droid/bridge.log` | the helper's pid and port |

Sources: [`app/src/main/assets/setup`](app/src/main/assets/setup) (scripts and `install.sh`), [`VdHelper.java`](app/src/main/java/com/adetkov/droid/VdHelper.java), [`Setup.kt`](app/src/main/java/com/adetkov/droid/Setup.kt).

### Agent engines

`phone-agent --engine claude|codex|opencode` (Claude if omitted). Codex and OpenCode have no stream-json input, so `droid-engine` (Python, in the chroot) runs `codex exec --json` / `opencode run --format json` once per message and prints Claude's stream-json subset (init, assistant text and tool_use with a short description, result), so the app's parser is unchanged. A message sent mid-turn is queued and sent as the next turn of the same session; only the last turn of a run reports a result. Closing stdin, the 3-minute first-message wait and SIGTERM behave as for Claude.

| | Claude Code | Codex | OpenCode |
|---|---|---|---|
| Install | claude.ai installer | GitHub release, `*-unknown-linux-musl` | GitHub release, `opencode-linux-{arm64,x64-baseline}-musl` |
| Sign-in | paste-the-code OAuth | ChatGPT device code (`codex login --device-auth`) or API key | provider API keys (`auth.json`); free models need none |
| Screenshot | `Read` on the PNG | `view_image` tool | `read` tool, needs a vision model |
| Session data | `~/.claude/projects/-root/ID.jsonl` | `~/.codex/sessions/**/rollout-*-ID.jsonl` | `~/.local/share/opencode/opencode.db` (`opencode session delete ID`) |
| Instructions | `~/.claude/CLAUDE.md`, plus `--append-system-prompt-file` | `~/.codex/AGENTS.md` (all three, written at each start) | `~/.config/opencode/AGENTS.md` (the same) |

Codex runs with its sandbox off and OpenCode with `--auto`: the chroot is the boundary. Recorded event streams for the adapter tests are in `app/src/test/resources/engines`.

### Android findings

- **Plain virtual displays sleep with the phone on Android 17**: every virtual display joins the default display group, and `VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP` is ignored. A display on a **VirtualDevice** (`LOCK_STATE_ALWAYS_UNLOCKED`) gets its own group and keeps running while the phone is locked. That needs a companion association: `cmd companiondevice associate USER PACKAGE MAC PROFILE` takes positional arguments, despite its help text. `cmd companiondevice list` prints `Association{mId=…, mDeviceMacAddress=…}` on Android 17 but a table (`ID | Package Name | Mac Address`) on Android 15; reading only the first left Android 15 waiting 3 s for an association that was there, then on a plain display. Without one, Droid falls back to a plain display with its own display group, which works on Android 16 and earlier (untested).
- **The agent's tools can starve Android**: what runs in the chroot is invisible to Android's low-memory killer, so when a tool takes the RAM, Android closes apps instead, Droid's too (despite its foreground service). The agent goes on and finishes, but Droid's restarted app shows the chat as "Interrupted" and posts no notification. Seen with ImageMagick: `montage` of 45 camera photos peaked at 4.07 GB on a 6 GB phone and emptied it. `phone-agent` now caps ImageMagick (`MAGICK_MEMORY_LIMIT=256MiB`, `MAGICK_MAP_LIMIT=512MiB`, `MAGICK_AREA_LIMIT=64MP`): 0.59 GB, nothing killed. Other heavy tools aren't capped; a memory cgroup for the chroot would cover them, and Droid doesn't yet pick up a run that outlived its app.
- A display on a VirtualDevice keeps the whole phone awake: its own display group stays on, so PowerManager holds its display suspend blocker and the phone never suspends, though the phone's own screen is off. Battery stats show no wakelock for it, only "Interactive" time well above "Screen on" time.
- **There is one keyboard, and it goes with the phone's focus.** Opening an app on the hidden display (`am start --display`), or tapping there, made it the top-focused display: the keyboard left the owner's screen mid-sentence and came up on the agent's. The display is now made with `VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED` (with `OWN_FOCUS`, which it needs; a VirtualDevice keeps it on Android 17), so it never takes the phone's focus. Its apps still have their own focus, and key presses aimed at the display (`input -d N text`/`keyevent`) reach them, but no keyboard ever shows there; take over types through the owner's own keyboard instead (Watch's keyboard button, sent as `t TEXT` and `k KEYCODE`). Before that, `IWindowManager.setDisplayImePolicy` (local) at least kept the agent's keyboard drawn on its own display.
- Toybox's `mount --make-rprivate` exits 0 and changes nothing, so `enter.sh` uses the root manager's busybox, and refuses to mount if the namespace is still shared.
- Alpine needs `USE_BUILTIN_RIPGREP=0` and `libgcc libstdc++ ripgrep` for Claude Code.
- The assistant role can't be requested with a dialog. Setup grants it as root or the shell user (`cmd role add-role-holder`).
- **The shell user may not make named pipes or hard links** (SELinux), and proot's fake root reports success for both
  (`mkfifo` then makes nothing). So `phone-agent` feeds Claude through an ordinary pipe, and proot's `--link2symlink`
  fakes hard links. Upstream proot can't do the `O_TMPFILE` + `linkat("/proc/self/fd/N")` that apk 3 writes every
  file with ("failed to write database"); Termux's fork can.
- **Other ways around root are closed on Android 16 and 17** (from their framework code): `cmd role
  set-bypassing-role-qualification` used to let an ordinary app hold the companion app-streaming role (its own
  trusted, always-unlocked displays, no adb afterwards), but Android 16 ignores the bypass for the app-streaming and
  nearby-streaming roles; the role that still allows it grants only `CREATE_VIRTUAL_DEVICE`, so its displays are
  untrusted and take no input. An app's own virtual display hosts only activities that opt in (`allowEmbedded`).
  Android 17's Computer Control (the API behind background agents) needs a privileged permission and Google's allowlist.
- **Android 17 gates mDNS**: without `ACCESS_LOCAL_NETWORK` (Nearby devices), each `NsdManager` search opens a "Choose a
  device to connect" picker. Wireless debugging trusts a network by BSSID or SSID, so "Always allow" covers a campus's
  many access points; it needs Wi-Fi, and turning it off doesn't end processes started through it while USB debugging keeps adbd running (it does when neither is on: seen on an Android 16 emulator).
- **Screenshots for the assistant on Android 17**: an assistant targeting API 37 gets only the context its `voice-interaction-service` XML declares (`usesAssistData`, `usesAssistScreenshots`, `usesAssistStructureScreenContent`). Without them, long-press shows the session with no screenshot. Asking again from the session with `show()` doesn't help either: Android strips data flags from an assistant's own requests. The screenshot also needs "Use text from screen" (`assist_structure_enabled`) on, because Android skips it when assist data isn't allowed. Droid doesn't declare `usesAssistStructureScreenContent`, so that setting still gets it no text from the screen.
- **The screenshot can come before the show**: when no screenshot is allowed ("Use screenshot" off), Android hands the session its null screenshot synchronously, before it sends the show itself. A `hide()` at that point removes the show still queued, so long-press only vibrates. The session therefore keeps a screenshot that comes before `onShow` for `onShow` to handle.

### Voice match model

Tested on LibriSpeech test-clean (40 speakers): each speaker enrolled with 6 five-second clips, then tested with clips of other utterances against every voiceprint (~800 genuine and ~31,000 impostor trials per row). Error rates are at the equal-error threshold; "babble" mixes in three other talkers at 10 dB SNR.

| sherpa-onnx model | 1.5 s | 2.5 s | 4 s | 2.5 s + babble | ms per 5 s (desktop CPU) |
|---|---|---|---|---|---|
| **nemo_en_titanet_small** (38 MB, used) | 0.75% | 0.37% | 0.12% | 2.3% | 190 |
| nemo_en_titanet_large (96 MB) | 0.90% | 0.38% | 0.25% | 1.8% | 500 |
| 3dspeaker eres2net_sv_en_voxceleb (25 MB) | 1.1% | 0.52% | 0.37% | 2.4% | 545 |
| wespeaker resnet34_LM (25 MB) | 11.5% | 7.0% | 5.1% | 33% | 390 |
| wespeaker CAM++_LM (27 MB) | 45% | 46% | 45% | 45% | 250 |

The WeSpeaker models do far worse through sherpa-onnx 1.13.8 than their published numbers; CAM++ is at chance, probably a feature mismatch. LibriSpeech's speakers each used their own microphone, which makes impostors easier to tell apart than on one phone's mic, so expect more errors in real use. TitaNet small embeds a 4 s request in 90–160 ms on the x86_64 emulator.

Clones: Pocket TTS, given one 10 s clip of each speaker, said three short commands (120 clips). Against the speaker's own voiceprint they scored 0.34 on average (others' voiceprints: 0.06); 6.7% reached 0.50 and 18% reached 0.45.

## Build

Needs JDK 17 or later and the Android SDK (`ANDROID_HOME`, or `sdk.dir` in `local.properties`).

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest lintDebug
./gradlew assembleRelease      # signed if keystore.properties exists (storeFile, storePassword, keyAlias, keyPassword)
tools/proot/build.sh --source proot-source.tar.gz  # the exact proot and talloc sources the APK's proot is built from
tools/proot/build.sh           # rebuilds app/src/main/jniLibs/*/libproot.so from source (needs the NDK)
```

The first build downloads the sherpa-onnx AAR from its GitHub release into `app/libs/`, checked against the SHA-256 in `app/build.gradle.kts`. Debug builds on an emulator replay a canned agent with a fake hidden screen, so the UI can be worked on without a phone (to replay a real run, put its frames and scripts, one per turn, in Droid's `files/fake-screen`: see [`FakeScreen.kt`](app/src/debug/java/com/adetkov/droid/FakeScreen.kt)); `adb shell setprop debug.droid.real 1` (then restart Droid) makes an emulator a phone without root, Wireless debugging and all (an emulator's Wi-Fi is "AndroidWifi"). Debug builds also take `am broadcast -n com.adetkov.droid/.AdbTestReceiver -a pair|shell|ensure` from adb (see `AdbTestReceiver.kt`).

Pushing a tag such as `v0.9` runs [`release.yml`](.github/workflows/release.yml): tests, lint, the release APKs (signed when the repository has the `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` secrets) and proot's source archive, drafted as a GitHub release. Every release has to be signed with the same key, or phones can't update to it.

## Credits and licenses

Droid is Apache-2.0; the app lists all of this under Settings → About. Everything below is downloaded at setup rather than shipped, except sherpa-onnx and proot, whose exact source is attached to each release:

- [Claude Code](https://claude.com/claude-code) by Anthropic, under Anthropic's terms; installed from claude.ai.
- [Alpine Linux](https://alpinelinux.org) minirootfs and packages.
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0), built into the APK.
- [proot](https://github.com/termux/proot) (Termux's fork, GPL-2.0) with [talloc](https://talloc.samba.org) (LGPL-3.0),
  in the APK as a separate program for phones without root; `tools/proot/build.sh` builds it from the pinned sources.
- [Silero VAD](https://github.com/snakers4/silero-vad) (MIT), NVIDIA [Parakeet TDT 0.6B v2](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v2) (CC-BY-4.0), NVIDIA [TitaNet small](https://catalog.ngc.nvidia.com/orgs/nvidia/teams/nemo/models/titanet_small) (under the NeMo Toolkit's license, Apache-2.0), and Kyutai [Pocket TTS](https://github.com/kyutai-labs/pocket-tts) in sherpa-onnx's ONNX export, whose README limits it to non-commercial use. The voice imitates the "bria" sample shipped with it.
