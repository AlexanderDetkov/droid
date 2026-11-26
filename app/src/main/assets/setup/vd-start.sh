#!/system/bin/sh
# Starts one task's hidden display; phone-agent runs it in Android through `android`, as root or as the shell user.
# Usage: vd-start.sh MAC TOKEN APK [AGENT_PID]
#   MAC:   the task's own companion association (a made-up address)
#   TOKEN: the live view's secret (DROID_TOKEN for VdHelper)
#   APK:   Droid's APK, which holds VdHelper
#   AGENT_PID: phone-agent's pid: the helper ends by itself when phone-agent is gone without vd-stop.sh
# Prints the helper's "displayId=", "pid=" and "port=" lines once it listens; if it didn't start, its "pid=" (if any) and
# an "error=" line saying why.
MAC=$1 TOKEN=$2 APK=$3 AGENT=$4
case "$AGENT" in *[!0-9]*) AGENT= ;; esac
TMP=/data/local/tmp/droid
LOG=$TMP/vd/helper-$(echo "$MAC" | tr -d :).log
[ -f "$APK" ] || { echo "error=no APK at $APK"; exit 1; }

# The display matches the phone's screen (an override set with `wm size` wins).
SIZE=$(wm size | tail -n 1 | sed 's/.*: *//')
DPI=$(wm density | tail -n 1 | sed 's/.*: *//')

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
# The folder is made as the shell user too, so the helper can write into it. Its pid ($!: the exec'd subshell is
# app_process) goes beside the log, to tell when it has ended, even before it could say anything.
START="mkdir -p $TMP/vd; (DROID_TOKEN=$TOKEN CLASSPATH=$APK exec nohup app_process / com.adetkov.droid.VdHelper ${SIZE%x*} ${SIZE#*x} $DPI $FLAGS ${ASSOC:-0} $AGENT) > $LOG 2>&1 < /dev/null & echo \$! > $LOG.pid"
# (ERR: what the shell said starting it, if that failed; the helper's own output never comes here)
if [ "$(id -u)" = 0 ]; then ERR=$(su 2000 -c "$START" 2>&1); else ERR=$(sh -c "$START" 2>&1); fi
PID=$(cat "$LOG.pid" 2>/dev/null); rm -f "$LOG.pid"
case "$PID" in ''|*[!0-9]*) PID= ;; esac
# Up to 15 s (a phone's first start of it can be slow), or until it fails: it says why ("error="), it has ended, or
# it never started (no pid: the start itself failed).
for _ in $(seq 150); do
  grep -qE '^(port|error)=' "$LOG" 2>/dev/null && break
  [ -z "$PID" ] || [ ! -d "/proc/$PID" ] && break
  sleep 0.1
done
if grep -q '^port=' "$LOG" 2>/dev/null; then
  grep -E '^(displayId|pid|port)=' "$LOG"
  # and what this phone refused on the way, which the display does without (Setup's diagnostics show it)
  grep -E '^(virtualDevice: |flags: )' "$LOG" | sed 's/^/note=/'
else
  # Not started: its pid (phone-agent's cleanup ends it, if it's still going) and why, as far as can be told.
  [ -n "$PID" ] && echo "pid=$PID"
  WHY=$(sed -n 's/^error=//p' "$LOG" 2>/dev/null | head -n 1)
  LAST=$(grep -vE '^(pid=|$)' "$LOG" 2>/dev/null | tail -n 1)
  if [ -n "$WHY" ]; then echo "error=$WHY"
  elif [ -z "$PID" ]; then ERR=$(echo "$ERR" | grep -v '^$' | tail -n 1); echo "error=its helper didn't start${ERR:+: $ERR}"
  elif [ ! -d "/proc/$PID" ]; then
    # Ended without a word: why Android ended it, where its log says (a crash's signal, or a kill)
    SYS=$(logcat -d -b crash -b main -b system -t 3000 2>/dev/null | grep -E "[ (]$PID[ ):]" |
      grep -E "Fatal signal|Abort message|FATAL EXCEPTION|Killing|[Kk]ill |died|lowmemorykiller|lmkd" | tail -n 1 |
      sed -E 's/^[0-9-]+ [0-9:.]+ +[0-9]+ +[0-9]+ [A-Z] //')
    echo "error=its helper ended${SYS:+: $SYS}${LAST:+ (its last words: $LAST)}"
  else echo "error=no answer from its helper in 15 s${LAST:+, last: $LAST}"; fi
fi
