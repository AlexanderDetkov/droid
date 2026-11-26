#!/system/bin/sh
# Enter Droid's Alpine Linux. Usage: enter.sh [command [args...]] (no command: a login shell).
# As root: a chroot in /data/local/droid. As the shell user, on phones without root (Droid's helper, see Bridge.java
# in the app): proot in /data/local/tmp/droid, which needs neither root nor mounts.

if [ "$(id -u)" != 0 ]; then
  B=/data/local/tmp/droid R=/data/local/tmp/droid/rootfs
  mkdir -p $B/tmp $B/vd
  # The shell user may make itself killable, as root runs do below.
  echo 200 > /proc/self/oom_score_adj 2>/dev/null
  [ $# -eq 0 ] && set -- /bin/bash -l
  # -0: look like root inside (apk, and the same paths as the chroot); --link2symlink: Android forbids the shell user
  # hard links, which apk and git make; --kill-on-exit: a task's leftovers end with it, as proot would otherwise wait.
  # Shared storage at /sdcard as in the chroot, and the hidden displays' folder at its own path, for `shot`. The
  # environment is set before proot rather than on its command line, where `ps` would show the token.
  exec /system/bin/env -i PROOT_TMP_DIR=$B/tmp HOME=/root USE_BUILTIN_RIPGREP=0 TERM="${TERM:-xterm-256color}" LANG=C.UTF-8 \
    DROID_APK="$DROID_APK" DROID_BASE=$B DROID_HOST= DROID_BRIDGE="$DROID_BRIDGE" DROID_TOKEN="$DROID_TOKEN" \
    PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    $B/bin/proot -0 --link2symlink --kill-on-exit -r $R \
    -b /dev -b /proc -b /sys -b /storage/emulated/0:/sdcard -b $B/vd -w /root /usr/bin/env "$@"
fi

# Root. Each call gets a private mount namespace: /proc, /sys, /dev and /sdcard are mounted for this run only and
# vanish with it, so nothing leaks into Android's own namespaces and deleting the rootfs never reaches through a mount.
R=/data/local/droid/rootfs

# unshare execs in place, so the re-run below keeps this pid: an inherited DROID_NS never matches.
if [ "$DROID_NS" != "$$" ]; then
  for B in /data/adb/magisk/busybox /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox; do
    [ -x "$B" ] && break
  done
  [ -x "$B" ] || { echo "droid: no busybox from Magisk, KernelSU or APatch" >&2; exit 1; }
  # Toybox unshare can't do this: its `mount --make-rprivate` reports success and changes nothing.
  DROID_NS=$$ DROID_BB=$B exec "$B" unshare -m --propagation private "$0" "$@"
fi
M="$DROID_BB mount"
unset DROID_NS DROID_BB

# Mount only in a namespace of our own that really is private; anywhere else these mounts would reach other apps.
if [ "$(readlink /proc/self/ns/mnt)" = "$(readlink /proc/1/ns/mnt)" ] ||
   grep -qE '^[^ ]+ [^ ]+ [^ ]+ [^ ]+ /(data)? .* shared:' /proc/self/mountinfo; then
  echo "droid: mount namespace is not private; not mounting" >&2
  exit 1
fi
$M -t proc proc $R/proc
$M -o bind /sys $R/sys
$M -o rbind /dev $R/dev
# Android's /dev itself, so read-only here, with every mount under it: its devices still open for reading and
# writing, but nothing in here can create, delete or replace a file in it. (An agent's `sed -i ... /dev/null` once
# replaced the phone's /dev/null with a plain file, and every app and shell command that wrote to it failed until a
# reboot.) DROID_DEV=rw leaves it writable: Setup's "Protect Android's /dev", turned off, for the agent's runs only.
# Always remount,bind,ro: a plain remount,ro would make Android's /dev read-only everywhere.
[ "$DROID_DEV" = rw ] || for m in $(cut -d' ' -f5 /proc/self/mountinfo); do
  case "$m" in "$R/dev" | "$R/dev/"*) $M -o remount,bind,ro "$m" ;; esac
done
[ -d /storage/emulated/0 ] && mkdir -p $R/sdcard && $M -o bind /storage/emulated/0 $R/sdcard

# Android's environment (BOOTCLASSPATH etc.), for the `android` command inside the chroot.
# Written via rename so concurrent tasks never read a half-written file.
export -p > $R/etc/android-env.$$ && mv $R/etc/android-env.$$ $R/etc/android-env

# Root shells inherit oom_score_adj -1000 (unkillable) from the root daemon. A runaway command in here (on a test phone,
# an ImageMagick montage of the whole camera roll took 1.7 GB) then leaves the kernel nothing it may kill, and Android
# reboots. 200: killable, and picked before the app you're using.
echo 200 > /proc/self/oom_score_adj 2>/dev/null

[ $# -eq 0 ] && set -- /bin/bash -l
exec chroot $R /usr/bin/env -i HOME=/root USE_BUILTIN_RIPGREP=0 TERM="${TERM:-xterm-256color}" LANG=C.UTF-8 \
  DROID_APK="$DROID_APK" DROID_BASE=/data/local/droid DROID_HOST=/proc/1/root \
  PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin "$@"
