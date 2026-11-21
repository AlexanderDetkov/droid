#!/system/bin/sh
# Starts one task's hidden display; phone-agent runs it in Android through `android`, as root or as the shell user.
# Usage: vd-start.sh MAC TOKEN APK [AGENT_PID]
#   MAC:   the task's own companion association (a made-up address)
#   TOKEN: the live view's secret (DROID_TOKEN for VdHelper)
#   APK:   Droid's APK, which holds VdHelper
#   AGENT_PID: phone-agent's pid: the helper ends by itself when phone-agent is gone without vd-stop.sh
# Prints the helper's "displayId=", "pid=" and "port=" lines once it listens, or nothing if it didn't start.
MAC=$1 TOKEN=$2 APK=$3 AGENT=$4
case "$AGENT" in *[!0-9]*) AGENT= ;; esac
TMP=/data/local/tmp/droid
LOG=$TMP/vd/helper-$(echo "$MAC" | tr -d :).log
[ -f "$APK" ] || { echo "error=no APK at $APK"; exit 1; }

# The display is shaped like the phone's screen (an override set with `wm size` wins) and lays apps out the same, but
# 720 pixels across: about the size a model sees a picture at (Claude Code shrinks anything over 2000 pixels, ChatGPT
# to 768 across), so a spot in a screenshot is the spot to tap, with no scaling for the agent to get wrong.
SIZE=$(wm size | tail -n 1 | sed 's/.*: *//')
DPI=$(wm density | tail -n 1 | sed 's/.*: *//')
W=${SIZE%x*} H=${SIZE#*x}
S=$W; [ "$H" -lt "$S" ] && S=$H
if [ "$S" -gt 720 ]; then
  W=$(( (W * 720 + S / 2) / S )) H=$(( (H * 720 + S / 2) / S )) DPI=$(( (DPI * 720 + S / 2) / S ))
fi

# A display owned by a VirtualDevice keeps running while the phone is locked (Android 17 puts every other virtual
# display in the phone's own display group, which sleeps). Without an association the helper falls back to a plain one.
cmd companiondevice associate 0 com.android.shell "$MAC" android.app.role.COMPANION_DEVICE_APP_STREAMING >/dev/null 2>&1
for _ in $(seq 30); do  # the association shows up in the list a moment after `associate` returns
  # Its line: "Association{mId=12, ... mDeviceMacAddress=<MAC>, ...}", or on Android 15 "12 | com.android.shell | <MAC>".
  ASSOC=$(cmd companiondevice list 0 | grep -i "$MAC" | sed -n 's/.*mId=\([0-9]*\).*/\1/p; s/^ *\([0-9][0-9]*\) |.*/\1/p' | head -n 1)
  [ -n "$ASSOC" ] && break
  sleep 0.1
done

# The display's flags (DisplayManager.VIRTUAL_DISPLAY_FLAG_*): public, presentation, own content only, touch, rotates with its
# content, content destroyed with it, trusted, always unlocked, no touch feedback, its own focus, and never the phone's
# focus (STEAL_TOP_FOCUS_DISABLED): the apps the agent opens and taps there never take the keyboard from the owner's screen.
FLAGS=95691
# The helper runs as the shell user (uid 2000), like scrcpy, from the app's own APK.
# The whole group's output goes to the log: anything left holding this script's stdout would keep `android` waiting.
# The folder is made as the shell user too, so the helper can write into it.
START="mkdir -p $TMP/vd; (DROID_TOKEN=$TOKEN CLASSPATH=$APK exec nohup app_process / com.adetkov.droid.VdHelper $W $H $DPI $FLAGS ${ASSOC:-0} $AGENT) > $LOG 2>&1 < /dev/null &"
if [ "$(id -u)" = 0 ]; then su 2000 -c "$START"; else sh -c "$START"; fi
for _ in $(seq 50); do
  grep -q '^port=' "$LOG" 2>/dev/null && break
  sleep 0.1
done
grep -E '^(displayId|pid|port)=' "$LOG"
