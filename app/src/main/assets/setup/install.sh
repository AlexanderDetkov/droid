#!/system/bin/sh
# Install or update Droid's Linux environment. Usage: install.sh SRC PACKAGE APK [ROOTFS_TARBALL]
#   SRC:            these setup files (the app unpacks them from its assets)
#   PACKAGE:        the app's package name, exempted from battery limits so long tasks keep running
#   APK:            the app's APK, which carries proot for phones without root
#   ROOTFS_TARBALL: an Alpine minirootfs (checked by the app), needed only for the first install
# Installs or updates Claude Code, and updates the other engines already installed (the app adds those with
# droid-install-engine).
# As root it sets up a chroot in /data/local/droid; as the shell user (phones without root, through Droid's helper)
# a proot in /data/local/tmp/droid, where the shell user may run programs.
# Safe to re-run: keeps the rootfs, the sign-in, chats and the agent's instructions; refreshes the scripts,
# upgrades the packages and updates Claude Code. Prints "step: ..." lines for the app's progress view.
set -e
SRC=$1 PKG=$2 APK=$3 TARBALL=$4
if [ "$(id -u)" = 0 ]; then
  BASE=/data/local/droid OWNER=0:0 MODE=755
else
  BASE=/data/local/tmp/droid OWNER= MODE=700  # nothing else on the phone needs in, and the sign-in lives here
fi
R=$BASE/rootfs

# Files are replaced by rename: running tasks read them.
put() { cp "$1" "$2.new" && { [ -z "$OWNER" ] || chown $OWNER "$2.new"; } && chmod "$3" "$2.new" && mv "$2.new" "$2"; }

mkdir -p $BASE
chmod $MODE $BASE

# One install at a time: without root, Droid's helper carries on with an install after the app that started it is gone.
LOCK=$BASE/install.lock
if ! mkdir $LOCK 2>/dev/null; then
  pid=$(cat $LOCK/pid 2>/dev/null)
  if [ -n "$pid" ] && tr '\0' ' ' 2>/dev/null < /proc/$pid/cmdline | grep -q install.sh; then
    echo "An earlier install is still running; try again once it's done" >&2
    exit 1
  fi
  rm -rf $LOCK && mkdir $LOCK  # left by an install that was killed
fi
echo $$ > $LOCK/pid
trap 'rm -rf $LOCK' EXIT

if [ -z "$OWNER" ]; then
  # proot, from the APK (built by tools/proot/build.sh), for this phone's CPU.
  ABI=$(getprop ro.product.cpu.abi)
  mkdir -p $BASE/bin
  unzip -p "$APK" "lib/$ABI/libproot.so" > $BASE/bin/proot.new
  [ -s $BASE/bin/proot.new ] || { echo "No proot for $ABI in $APK" >&2; exit 1; }
  chmod 755 $BASE/bin/proot.new && mv $BASE/bin/proot.new $BASE/bin/proot
fi

if [ ! -x $R/bin/busybox ]; then
  echo "step: Unpacking Alpine Linux"
  [ -f "$TARBALL" ] || { echo "No Alpine tarball given" >&2; exit 1; }
  if [ -d $R ]; then
    # Damaged (by an interrupted Remove, say): unpack over it, keeping what is left in its /root.
    tar -xzf "$TARBALL" -C $R
  else
    rm -rf $R.new && mkdir -p $R.new
    tar -xzf "$TARBALL" -C $R.new
    mv $R.new $R
  fi
fi
put "$SRC/enter.sh" $BASE/enter.sh 755
for f in vd-start.sh vd-stop.sh; do put "$SRC/$f" $BASE/$f 755; done
for f in phone-agent android shot droid droid-bridge droid-engine droid-install-engine; do put "$SRC/bin/$f" $R/usr/local/bin/$f 755; done
put "$SRC/resolv.conf" $R/etc/resolv.conf 644
mkdir -p $R/root/.claude
put "$SRC/settings.json" $R/root/.claude/settings.json 644
[ -f $R/root/.claude/CLAUDE.md ] || put "$SRC/CLAUDE.md" $R/root/.claude/CLAUDE.md 644  # the owner edits this one
# One brief for every engine: Codex and OpenCode read AGENTS.md from their own folders, which link to CLAUDE.md. The
# app writes CLAUDE.md by rename, so the links keep following it. phone-agent replaces the link of the engine it starts
# with a file that also holds the app's instructions.
for l in .codex/AGENTS.md .config/opencode/AGENTS.md; do
  mkdir -p "$R/root/$(dirname $l)"
  rm -rf "$R/root/$l"
  ln -s "$(echo $l | sed 's|[^/]*$||; s|[^/]*/|../|g').claude/CLAUDE.md" "$R/root/$l"
done

echo "step: Installing packages"
$BASE/enter.sh sh -c 'apk update -q && apk upgrade -q && apk add -q bash ca-certificates curl git jq libgcc libstdc++ python3 ripgrep sqlite'

if [ -L $R/root/.local/bin/claude ]; then  # a link into the rootfs's own /root, so test the link
  echo "step: Updating Claude Code"
  $BASE/enter.sh claude update
else
  echo "step: Installing Claude Code"
  # Downloaded whole before it runs: piped into bash, a failed download would run as an empty script that succeeds.
  $BASE/enter.sh sh -c 'curl -fsSL -o /tmp/claude-install.sh https://claude.ai/install.sh && bash /tmp/claude-install.sh; s=$?; rm -f /tmp/claude-install.sh; exit $s'
fi
[ -L $R/root/.local/bin/claude ] || { echo "Claude Code didn't install: its installer left no ~/.local/bin/claude" >&2; exit 1; }

for e in codex opencode; do
  if [ -x $R/root/.local/bin/$e ]; then
    echo "step: Updating $e"
    $BASE/enter.sh droid-install-engine $e
  fi
done

echo "step: Letting Droid run in the background"
cmd deviceidle whitelist "+$PKG" >/dev/null
appops set "$PKG" SYSTEM_EXEMPT_FROM_DISMISSIBLE_NOTIFICATIONS allow 2>/dev/null || true  # Android 14+: running tasks' notifications stay put
echo "step: Done"
