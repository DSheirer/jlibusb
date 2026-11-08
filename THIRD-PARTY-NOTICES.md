# Third-party notices

jlibusb is licensed under the Apache License 2.0. The jar also contains the
native library below, which keeps its own license. The full license text is in
`META-INF/licenses/` inside the jar.

## libusb

Source: <https://github.com/libusb/libusb>

Licensed under the GNU Lesser General Public License version 2.1 or later. See
`META-INF/licenses/libusb-COPYING`. The exact version in each jar is recorded in
`jlibusb/native/<platform>/build-info.properties`, and `native/versions.env` in
this repository records the release it was built from. The Linux and macOS
libraries are built from the release tarball; the Windows DLLs are the prebuilt
binaries the libusb project publishes with each release.

libusb is shipped as a separate shared library so it can be replaced: point the
`jlibusb.library.path` system property at a directory containing your own
build. Libraries that load through jlibusb then use that build too.
