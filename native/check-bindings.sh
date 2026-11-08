#!/usr/bin/env bash
# Checks that every function bound in Native.java is declared in libusb.h, so a
# rename or removal upstream fails the build. jlibusb binds only part of
# libusb, so functions without a binding are expected and not reported.
#
# Usage: native/check-bindings.sh <path-to-libusb.h>
set -euo pipefail
export LC_ALL=C

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HEADER="$1"
BINDINGS="$ROOT/src/main/java/io/github/dsheirer/libusb/internal/Native.java"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Declarations look like: int LIBUSB_CALL libusb_name( or, for variadic
# functions, LIBUSB_CALLV.
grep -oE 'LIBUSB_CALLV?[[:space:]]+libusb_[a-z0-9_]+[[:space:]]*\(' "$HEADER" \
    | sed -E 's/LIBUSB_CALLV?[[:space:]]+(libusb_[a-z0-9_]+).*/\1/' | sort -u > "$WORK/header"
grep -oE '"libusb_[a-z0-9_]+"' "$BINDINGS" | tr -d '"' | sort -u > "$WORK/bindings"

STALE="$(comm -13 "$WORK/header" "$WORK/bindings")"
if [ -n "$STALE" ]; then
    printf 'Bindings not in libusb.h:\n%s\n' "$STALE" >&2
    exit 1
fi
echo "All $(wc -l < "$WORK/bindings" | tr -d ' ') bindings are declared in libusb.h"
