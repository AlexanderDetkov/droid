#!/system/bin/sh
# Setup's look at a task's hidden display: starts one as a task does (vd-start.sh), asks it for a screenshot as a
# task's `shot` does, and ends it (vd-stop.sh). Setup runs it from the app, `sh -s BASE MAC APK` with this on stdin,
# as root or the shell user, one at a time.
# Prints vd-start.sh's lines, then "answered=1" if the display answered, or an "error=" line saying why not.
B=$1 MAC=$2 APK=$3
[ -f "$B/vd-start.sh" ] || exit 3
# What a check cut short left under this address goes first.
sh "$B/vd-stop.sh" "$MAC"
OUT=$(sh "$B/vd-start.sh" "$MAC" check "$APK" $$)
echo "$OUT"
D=$(echo "$OUT" | sed -n 's/^displayId=//p')
P=$(echo "$OUT" | sed -n 's/^pid=//p' | head -n 1)
if [ -n "$D" ]; then
  V=/data/local/tmp/droid/vd/$D
  # (the helper makes its folder just after it says its port)
  for _ in $(seq 30); do [ -d "$V" ] && break; sleep 0.1; done
  : > "$V/shot.req" 2>/dev/null
  # It answers once the display has settled, within 2 s.
  for _ in $(seq 50); do [ -f "$V/shot.done" ] && break; sleep 0.1; done
  if [ -f "$V/shot.done" ]; then echo "answered=1"
  elif [ -d "/proc/$P" ]; then echo "error=it started, but didn't answer"
  else
    # why Android ended it, where its log says (as in vd-start.sh)
    SYS=$(logcat -d -b crash -b main -b system -t 3000 2>/dev/null | grep -E "[ (]$P[ ):]" |
      grep -E "Fatal signal|Abort message|FATAL EXCEPTION|Killing|[Kk]ill |died|lowmemorykiller|lmkd" | tail -n 1 |
      sed -E 's/^[0-9-]+ [0-9:.]+ +[0-9]+ +[0-9]+ [A-Z] //')
    echo "error=it started, then ended${SYS:+: $SYS}"
  fi
fi
sh "$B/vd-stop.sh" "$MAC" '' "$D"
