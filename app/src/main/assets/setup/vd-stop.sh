#!/system/bin/sh
# Ends one task's hidden display (see vd-start.sh): its helper, association, screenshots and log.
# Usage: vd-stop.sh MAC [HELPER_PID] [DISPLAY]
MAC=$1 PID=$2 DISPLAY_ID=$3
TMP=/data/local/tmp/droid
LOG=$TMP/vd/helper-$(echo "$MAC" | tr -d :).log
[ -n "$PID" ] || PID=$(sed -n 's/^pid=//p' "$LOG" 2>/dev/null)  # a helper that came up after phone-agent stopped waiting
# Only a display helper: a pid read from an old log may be another process's by now
[ -n "$PID" ] && grep -q com.adetkov.droid.VdHelper "/proc/$PID/cmdline" 2>/dev/null && kill "$PID" 2>/dev/null
cmd companiondevice disassociate 0 com.android.shell "$MAC" >/dev/null 2>&1
[ -n "$DISPLAY_ID" ] && rm -rf "$TMP/vd/$DISPLAY_ID"
rm -f "$LOG"
