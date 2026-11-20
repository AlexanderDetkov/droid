#!/bin/sh
# Builds the proot that Droid's no-root mode runs Alpine in, from source, into app/src/main/jniLibs/<abi>/libproot.so.
# Termux's fork (GPL-2.0), not upstream: Android forbids hard links to the shell user, and Termux's link2symlink fakes
# them, including the O_TMPFILE + linkat("/proc/self/fd/N") that apk 3 writes every file with. Statically linked with
# talloc (LGPL-3.0), so the binary needs nothing from the phone. The loader proot runs programs through is built in.
# Needs the Android NDK (ANDROID_NDK_HOME, or the newest under $ANDROID_HOME/ndk). Usage: tools/proot/build.sh
# `tools/proot/build.sh --source FILE.tar.gz` instead packs the exact sources it builds from (attached to each release,
# as proot's GPL-2.0 asks of anyone who ships its binary), and needs no NDK.
set -eu
PROOT_REPO=https://github.com/termux/proot
PROOT_COMMIT=52d65a4dc140ca554d241a1438ddd4f6b00c9813
TALLOC_VERSION=2.4.3
TALLOC_SHA256=dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd
API=34  # Droid's minSdk

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
APP=$HERE/../../app/src/main/jniLibs
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT

git clone -q "$PROOT_REPO" "$W/proot"
git -C "$W/proot" checkout -q "$PROOT_COMMIT"
# NDK r30's clang rejects the implicit strcmp/memset declarations in this one file.
sed -i '1i #include <string.h>' "$W/proot/src/extension/ashmem_memfd/ashmem_memfd.c"

curl -fsSL -o "$W/talloc.tgz" "https://www.samba.org/ftp/talloc/talloc-$TALLOC_VERSION.tar.gz"
echo "$TALLOC_SHA256  $W/talloc.tgz" | sha256sum -c - >/dev/null
tar -xzf "$W/talloc.tgz" -C "$W"

if [ "${1:-}" = --source ]; then
  OUT=$(realpath "${2:?usage: build.sh --source FILE.tar.gz}")
  mkdir "$W/src" && cp -r "$W/proot" "$W/talloc-$TALLOC_VERSION" "$HERE/build.sh" "$HERE/replace.h" "$W/src/"
  tar -czf "$OUT" -C "$W" --transform 's,^src,proot-source,' src
  echo "$OUT"; exit 0
fi

NDK=${ANDROID_NDK_HOME:-$(ls -d "${ANDROID_HOME:?set ANDROID_HOME or ANDROID_NDK_HOME}"/ndk/* | sort -V | tail -n 1)}
T=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin

for abi in arm64-v8a x86_64; do
  case $abi in arm64-v8a) triple=aarch64-linux-android ;; x86_64) triple=x86_64-linux-android ;; esac
  CC=$T/$triple$API-clang
  B=$W/$abi; mkdir -p "$B/lib"
  # talloc.c alone, with replace.h standing in for Samba's lib/replace.
  "$CC" -O2 -fPIC -I"$HERE" -I"$W/talloc-$TALLOC_VERSION" -c "$W/talloc-$TALLOC_VERSION/talloc.c" -o "$B/talloc.o"
  "$T/llvm-ar" rcs "$B/lib/libtalloc.a" "$B/talloc.o"
  cp "$W/talloc-$TALLOC_VERSION/talloc.h" "$B/"
  cp -r "$W/proot" "$B/proot"
  make -C "$B/proot/src" -j"$(nproc)" CC="$CC" STRIP="$T/llvm-strip" OBJCOPY="$T/llvm-objcopy" OBJDUMP="$T/llvm-objdump" \
    CPPFLAGS="-I$B -D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I." LDFLAGS="-L$B/lib -ltalloc -static -Wl,-z,noexecstack" >/dev/null
  mkdir -p "$APP/$abi"
  "$T/llvm-strip" -o "$APP/$abi/libproot.so" "$B/proot/src/proot"
  echo "$abi: $(sha256sum "$APP/$abi/libproot.so" | cut -d' ' -f1)"
done
