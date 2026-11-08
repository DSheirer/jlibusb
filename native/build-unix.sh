#!/usr/bin/env bash
# Builds libusb as a shared library for the host platform and copies it to
# dist/native/<platform>. Works on Linux and macOS.
#
# Usage: native/build-unix.sh
#
# libusb is built without udev so the Linux library does not need libudev at
# runtime. Hotplug detection then uses the kernel's netlink uevents directly.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=versions.env
source "$ROOT/native/versions.env"
WORK="${JLIBUSB_WORK_DIR:-$ROOT/build/native-work}"
JOBS="$(getconf _NPROCESSORS_ONLN)"

case "$(uname -s)" in
    Linux)  OS=linux ;;
    Darwin) OS=macos ;;
    *) echo "Unsupported OS: $(uname -s)" >&2; exit 1 ;;
esac
case "$(uname -m)" in
    x86_64|amd64)  ARCH=x86_64 ;;
    aarch64|arm64) ARCH=aarch64 ;;
    *) echo "Unsupported architecture: $(uname -m)" >&2; exit 1 ;;
esac
PLATFORM="$OS-$ARCH"
OUT="$ROOT/dist/native/$PLATFORM"

rm -rf "$WORK" "$OUT"
mkdir -p "$WORK" "$OUT" "$ROOT/dist/native/licenses"
cd "$WORK"

echo "=== libusb $LIBUSB_VERSION ==="
TARBALL="libusb-$LIBUSB_VERSION.tar.bz2"
# Downloads are kept outside the work directory so rebuilds can reuse them;
# the checksum below is verified either way.
CACHE="${JLIBUSB_CACHE_DIR:-$ROOT/build/native-cache}"
mkdir -p "$CACHE"
if [ ! -f "$CACHE/$TARBALL" ]; then
    curl -fsSL -o "$CACHE/$TARBALL.part" \
        "https://github.com/libusb/libusb/releases/download/v$LIBUSB_VERSION/$TARBALL"
    mv "$CACHE/$TARBALL.part" "$CACHE/$TARBALL"
fi
cp "$CACHE/$TARBALL" "$TARBALL"
if command -v sha256sum >/dev/null; then
    echo "$LIBUSB_TARBALL_SHA256  $TARBALL" | sha256sum -c -
else
    echo "$LIBUSB_TARBALL_SHA256  $TARBALL" | shasum -a 256 -c -
fi
tar xjf "$TARBALL"

MIN_MACOS=11.0
USB_PREFIX="$WORK/usb"
USB_ARGS=(--prefix="$USB_PREFIX" --disable-static --enable-shared)
if [ "$OS" = linux ]; then
    USB_ARGS+=(--disable-udev)
else
    export CFLAGS="-O2 -mmacosx-version-min=$MIN_MACOS"
    export LDFLAGS="-mmacosx-version-min=$MIN_MACOS"
fi
(cd "libusb-$LIBUSB_VERSION" && ./configure "${USB_ARGS[@]}" >/dev/null && make -j"$JOBS" install >/dev/null)
unset CFLAGS LDFLAGS

echo "=== packaging $PLATFORM ==="
if [ "$OS" = linux ]; then
    cp -L "$USB_PREFIX/lib/libusb-1.0.so.0" "$OUT/libusb-1.0.so.0"
    chmod 755 "$OUT"/*
    # Only glibc may remain as a dependency.
    readelf -d "$OUT/libusb-1.0.so.0" | grep -E 'NEEDED|SONAME|RUNPATH|RPATH'
    if readelf -d "$OUT/libusb-1.0.so.0" | grep -q 'libudev'; then
        echo "libusb links libudev" >&2
        exit 1
    fi
else
    cp -L "$USB_PREFIX/lib/libusb-1.0.0.dylib" "$OUT/libusb-1.0.0.dylib"
    chmod 755 "$OUT"/*
    # Dependent libraries refer to libusb as @loader_path/libusb-1.0.0.dylib.
    # Re-sign because install_name_tool invalidates the ad-hoc signature.
    install_name_tool -id @rpath/libusb-1.0.0.dylib "$OUT/libusb-1.0.0.dylib"
    codesign -f -s - "$OUT/libusb-1.0.0.dylib"
    otool -L "$OUT/libusb-1.0.0.dylib"
    if otool -L "$OUT/libusb-1.0.0.dylib" | grep -qF "$WORK"; then
        echo "libusb still refers to the build directory" >&2
        exit 1
    fi
fi

if ! "$ROOT/native/check-bindings.sh" "$USB_PREFIX/include/libusb-1.0/libusb.h"; then
    exit 1
fi

cp "libusb-$LIBUSB_VERSION/COPYING" "$ROOT/dist/native/licenses/libusb-COPYING"
cat > "$OUT/build-info.properties" <<INFO
libusb.version=$LIBUSB_VERSION
libusb.source=libusb-$LIBUSB_VERSION.tar.bz2
INFO
ls -l "$OUT"
