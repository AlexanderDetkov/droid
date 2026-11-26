#!/usr/bin/env python3
"""
End-to-end test of Droid's setup without root, as a person does it, on one emulator: Start, Settings (Build number
seven times if Developer options are off), Wireless debugging, the pairing code typed into Droid's notification, the
Free AI, the hidden display check, and one task that opens an app on the hidden display.

    tools/setup_e2e.py SERIAL APK [--hide] [--on] [--task] [--out DIR]

  SERIAL  an emulator (emulator-5650); its Droid is cleared first
  APK     a Droid build for it (a debug build: it's put in real mode with debug.droid.real)
  --hide  read Developer options' settings as Android 17 shows them to apps (debug.droid.hidesettings)
  --on    Developer options and Wireless debugging on before setup starts (Android allows turning them on with adb)
  --task  then run a task: open Clock and read the alarms
  --break-proof  Droid's Android/data folder a plain file, as when one left from an earlier install can't be written

Prints each step as it goes, and a JSON summary last; exits 1 on a failure, with a screenshot in --out.
Android 17 emulators need tools/emulator17.sh to stay up.
"""
import argparse, json, re, subprocess, sys, time, os

ap = argparse.ArgumentParser()
ap.add_argument("serial"); ap.add_argument("apk")
ap.add_argument("--hide", action="store_true"); ap.add_argument("--on", action="store_true")
ap.add_argument("--task", action="store_true"); ap.add_argument("--out", default="/tmp")
ap.add_argument("--break-proof", action="store_true")  # Droid's Android/data folder unusable, as one left from an earlier install
a = ap.parse_args()
PKG = "com.adetkov.droid"
T0 = time.time()
log = []


def adb(*args, check=False, timeout=120):
    r = subprocess.run(["adb", "-s", a.serial, *args], capture_output=True, text=True, timeout=timeout)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)}: {r.stderr.strip() or r.stdout.strip()}")
    return r.stdout


def sh(cmd, timeout=120):
    return adb("shell", cmd, timeout=timeout)


def step(msg):
    line = f"[{time.time() - T0:6.1f}s] {msg}"
    print(line, flush=True)
    log.append(line)


def shot(name):
    path = os.path.join(a.out, f"{a.serial}-{name}.png")
    with open(path, "wb") as f:
        f.write(subprocess.run(["adb", "-s", a.serial, "exec-out", "screencap", "-p"], capture_output=True).stdout)
    return path


def fail(msg):
    step("FAIL: " + msg)
    p = shot("fail")
    print(json.dumps({"serial": a.serial, "ok": False, "error": msg, "screenshot": p, "seconds": round(time.time() - T0)}))
    sys.exit(1)


def nodes():
    """What's on screen: (text or description, x, y, clickable, checked), password fields left out."""
    for _ in range(6):
        out = sh("uiautomator dump /sdcard/e2e.xml >/dev/null 2>&1 && cat /sdcard/e2e.xml && rm /sdcard/e2e.xml")
        if out.startswith("<?xml"):
            break
        time.sleep(1)
    else:
        return []
    res = []
    for m in re.finditer(r"<node [^>]*>", out):
        n = m.group(0)
        if 'password="true"' in n:
            continue
        g = lambda k: (re.search(k + r'="([^"]*)"', n) or [None, ""])[1]
        t = g(" text") or g("content-desc")
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if not b:
            continue
        x1, y1, x2, y2 = map(int, b.groups())
        res.append((t, (x1 + x2) // 2, (y1 + y2) // 2, 'clickable="true"' in n, 'checked="true"' in n, g("resource-id")))
    return res


def find(text, exact=True, ns=None):
    ns = ns if ns is not None else nodes()
    for n in ns:
        if (n[0] == text) if exact else (text.lower() in n[0].lower()):
            return n
    return None


def tap(x, y):
    sh(f"input tap {x} {y}")


def tap_text(text, exact=True, wait=10):
    end = time.time() + wait
    while time.time() < end:
        n = find(text, exact)
        if n:
            tap(n[1], n[2])
            return n
        time.sleep(1)
    fail(f"not on screen: {text!r}")


def wait_for(pred, what, timeout):
    end = time.time() + timeout
    while time.time() < end:
        v = pred()
        if v:
            return v
        time.sleep(1.5)
    fail(f"timed out waiting for {what}")


def top():
    m = re.search(r"topResumedActivity=ActivityRecord\{\S+ \S+ (\S+)", sh("dumpsys activity activities"))
    return m.group(1) if m else ""


def note_title():
    """The title of Droid's pairing notification, or None."""
    out = sh("dumpsys notification --noredact")
    i = out.find(f"pkg={PKG}")
    titles = re.findall(r"android\.title=String \(([^)]*)\)", out[i:i + 20000]) if i >= 0 else []
    return titles[0] if titles else None


def setting(k):
    return sh(f"settings get global {k}").strip()


def droid_words():
    """Droid's headline on the setup screen."""
    ns = nodes()
    return ns[0][0] if ns else ""


# 1. Install, real mode, fresh data
step(f"Android {sh('getprop ro.build.version.release').strip()} (API {sh('getprop ro.build.version.sdk').strip()}), "
     f"build {sh('getprop ro.build.id').strip()}, {'hidden settings' if a.hide else 'settings as Android shows them'}")
out = subprocess.run(["adb", "-s", a.serial, "install", "-r", "-g", a.apk], capture_output=True, text=True, timeout=300)
if "UPDATE_INCOMPATIBLE" in out.stdout + out.stderr:  # signed with another key (a release over a debug build)
    adb("uninstall", PKG)
    out = subprocess.run(["adb", "-s", a.serial, "install", "-g", a.apk], capture_output=True, text=True, timeout=300)
if "Success" not in out.stdout:
    fail("install: " + (out.stdout + out.stderr).strip()[-300:])
sh(f"setprop debug.droid.real 1; setprop debug.droid.hidesettings {1 if a.hide else 0}")
# (screens that keep animating can't be read: uiautomator waits for them to be still)
sh("settings put global window_animation_scale 0; settings put global transition_animation_scale 0; settings put global animator_duration_scale 0")
sh(f"am force-stop {PKG}; pm clear {PKG}; pm grant {PKG} android.permission.POST_NOTIFICATIONS")
if a.on:
    sh("settings put global development_settings_enabled 1; settings put global adb_wifi_enabled 1")
    time.sleep(3)
    if find("Allow wireless debugging on this network?"):  # a network not trusted yet asks first
        tap_text("Always allow on this network")
        tap_text("Allow")
        time.sleep(2)
step(f"Developer options {setting('development_settings_enabled')}, Wireless debugging {setting('adb_wifi_enabled')} (as Android has them)")

# 2. Hello, then Open Settings: Settings opens where the first step is
sh(f"am start -n {PKG}/.MainActivity")
tap_text("Start", wait=90)
if a.break_proof:
    sh(f"rm -rf /sdcard/Android/data/{PKG}/files; touch /sdcard/Android/data/{PKG}/files")
    step("Droid's Android/data/files is now a plain file: its helper's proof can't be written there")
wait_for(lambda: find("Open Settings"), "the Connect page", 20)
step(f"Connect page: {droid_words()!r}")
tap_text("Open Settings")
time.sleep(4)
notes = []


def seen(t):
    if t and (not notes or notes[-1] != t):
        notes.append(t)
        step(f"notification: {t!r}")


# 3. Settings until the code is asked for
deadline = time.time() + 300
code_typed = False
build_taps = 0
while time.time() < deadline and not code_typed:
    seen(note_title())
    t = top()
    if "GrantPermissions" in t:  # Android 17 asks for Nearby devices (local network) as Droid starts pairing
        n = find("Allow")
        step(f"permission asked: {nodes()[0][0][:80] if nodes() else '?'}: Allow")
        if n:
            tap(n[1], n[2])
        time.sleep(3)
        continue
    if "MyDeviceInfo" in t or "DeviceInfo" in t:
        step("Settings opened About phone (Developer options read as off)")
        if build_taps:
            fail("About phone again after Developer options were turned on")
        # About phone can't be read (its Up time never stops changing), so Build number is tapped where it is once
        # scrolled to the end (the last row, on this 1080 x 2400 screen), eight times, as a person does.
        for _ in range(6):
            sh("input swipe 540 1900 540 400 150")
        time.sleep(1)
        for _ in range(8):
            tap(220, 2166)
            time.sleep(0.35)
        time.sleep(2)
        if setting("development_settings_enabled") != "1":
            fail("Build number's taps didn't turn Developer options on")
        wait_for(lambda: "Disabled" not in sh("cmd package resolve-activity --brief -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS"),
                 "Settings to take Developer options as on", 30)
        build_taps = 7
        step(f"Build number tapped: Developer options {setting('development_settings_enabled')}")
        sh(f"am start -n {PKG}/.MainActivity")
        time.sleep(3)
        continue
    if "Development" in t:
        ns = nodes()
        wd = [n for n in ns if n[0] == "Wireless debugging"]
        sw = [n for n in ns if n[5].endswith(":id/switchWidget")]
        on = setting("adb_wifi_enabled") == "1"
        if not on:
            if not wd:
                sh("input swipe 540 1900 540 700 200")
                time.sleep(1)
                continue
            row = wd[0]
            # the switch on the Wireless debugging row: the one nearest it
            near = min(sw, key=lambda n: abs(n[2] - row[2]), default=None)
            tap(*((near[1], near[2]) if near and abs(near[2] - row[2]) < 120 else (970, row[2] + 24)))
            time.sleep(2)
            ok = wait_for(lambda: find("Allow wireless debugging on this network?") or (setting("adb_wifi_enabled") == "1" and "x"), "the allow dialog", 15)
            if ok != "x":
                tap_text("Always allow on this network")
                time.sleep(0.5)
                tap_text("Allow")
            wait_for(lambda: setting("adb_wifi_enabled") == "1", "Wireless debugging on", 15)
            step("Wireless debugging turned on")
            time.sleep(3)
            seen(note_title())
            continue
        if wd:
            tap(wd[0][1], wd[0][2])  # into its page
            time.sleep(3)
            continue
        sh("input swipe 540 1900 540 700 200")
        time.sleep(1)
        continue
    if "AdbWireless" in t or find("Pair device with pairing code"):
        tap_text("Pair device with pairing code")
        time.sleep(3)
        code = wait_for(lambda: next((n[0] for n in nodes() if re.fullmatch(r"\d{6}", n[0])), None), "the pairing code", 15)
        step(f"pairing code {code}")
        time.sleep(3)
        seen(note_title())
        sh("cmd statusbar expand-notifications")
        time.sleep(2)
        if not find("Enter code"):  # collapsed under others: pulled open, as a person does
            n = wait_for(lambda: find("Type the code here"), "Droid's code notification", 15)
            sh(f"input swipe {n[1]} {n[2]} {n[1]} {n[2] + 400} 300")
            time.sleep(1.5)
        tap_text("Enter code", wait=20)
        time.sleep(2)
        sh(f"input text {code}")
        sh("input keyevent 66")
        code_typed = True
        break
    if PKG in t:
        # back in Droid before the code: open Settings again from its screen
        tap_text("Open Settings")
        time.sleep(4)
        continue
    time.sleep(2)
if not code_typed:
    fail("never reached the pairing code")

# 4. Paired: Droid comes back by itself and picks up the AI
wait_for(lambda: PKG in top(), "Droid back in front", 60)
sh("cmd statusbar collapse")
seen(note_title())
w = wait_for(lambda: find("Which AI should I use?") or find("All set!") or find("Something went wrong"), "the page after pairing", 90)
if w[0] == "Something went wrong":
    fail("after pairing: " + " | ".join(n[0] for n in nodes()[:4]))
step(f"paired and connected: {w[0]!r}")
if w[0] == "Which AI should I use?":
    tap_text("Free")
w = wait_for(lambda: find("All set!") or find("My own screen won't open") or find("Something went wrong") or find("One more switch"),
             "the end of setup", 600)
if w[0] != "All set!":
    fail("setup ended on " + " | ".join(n[0] for n in nodes()[:4]))
prefs = sh(f"run-as {PKG} cat shared_prefs/setup.xml 2>&1")
# (a release build can't be read with run-as: then Android's own log of the display the check made, and All set! itself,
# which it shows only once the check didn't fail)
works = re.search(r'displayWorks">([^<]*)', prefs) if "not debuggable" not in prefs else \
    re.search(r'Display device added: DisplayDeviceInfo\{"droid"', sh("logcat -d"))
step(f"setup done; hidden display check: {'passed' if works else 'not recorded'}")
if not works:
    fail("the hidden display check didn't pass")
tap_text("Let's go")
time.sleep(4)

# 5. A task on the hidden display
answer = None
if a.task:
    n = find("Type instead") or find("Tap to type")
    if not n:
        fail("no message bar")
    tap(n[1], n[2])
    time.sleep(2)
    sh("input text " + "Open%sthe%sClock%sapp%sand%stell%sme%swhat%salarms%sare%sset")
    time.sleep(1)
    tap_text("Send")
    t1 = time.time()
    def done():
        ns = nodes()
        for n in ns[:12]:
            if re.search(r"alarm", n[0], re.I) and "Open the Clock" not in n[0]:
                return n[0]
            if re.search(r"couldn't|wouldn't|went wrong|didn't work", n[0], re.I):
                fail("task: " + n[0])
        return None
    answer = wait_for(done, "the task's answer", 300)
    step(f"task answered in {time.time() - t1:.0f}s: {answer!r}")

print(json.dumps({"serial": a.serial, "ok": True, "notifications": notes, "answer": answer, "seconds": round(time.time() - T0)}))
