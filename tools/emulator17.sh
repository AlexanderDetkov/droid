#!/bin/sh
# Boots an Android 17 emulator (system images android-37.x) so it stays up without a window, for tools/setup_e2e.py.
# Two things otherwise restart its system every minute or two:
#   - UWB: its hardware service finds no device, crashes every 5 s, and init restarts the system ("updatable crashing").
#     The emulator's Uwb feature goes off (-feature -Uwb).
#   - The gesture bar: SystemUI samples the screen behind it, and SurfaceFlinger aborts reading it back
#     ("Assertion failed: !rcEnc->featureInfo()->hasReadColorBufferDma"). 3-button navigation has no such bar; it's
#     set once the system is up and kept in the AVD's data.
# What still aborts the same way: screencap, and switching apps (Android reads the screen back for Recents), which
# restarts the system. So it's good for Droid's own screens and permission prompts, not yet for a whole setup.
# Usage: tools/emulator17.sh AVD PORT     (the AVD made with avdmanager; give it a few GB: disk.dataPartition.size=6G)
AVD=$1 PORT=$2
[ -n "$AVD" ] && [ -n "$PORT" ] || { sed -n 2,11p "$0"; exit 2; }
E=emulator-$PORT
"${ANDROID_HOME:-$HOME/Android/Sdk}/emulator/emulator" -avd "$AVD" -port "$PORT" -no-window -no-audio -no-snapshot \
  -no-boot-anim -feature -Uwb > "/tmp/emulator17-$PORT.log" 2>&1 &
adb -s "$E" wait-for-device
until [ "$(adb -s "$E" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done
for _ in $(seq 30); do
  adb -s "$E" shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.threebutton >/dev/null 2>&1
  adb -s "$E" shell cmd overlay list 2>/dev/null | grep -q '\[x\] com.android.internal.systemui.navbar.threebutton' && break
  sleep 2
done
echo "$E up: $(adb -s "$E" shell getprop ro.build.id | tr -d '\r'), 3-button navigation, no UWB"
