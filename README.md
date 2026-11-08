# jlibusb

[libusb](https://github.com/libusb/libusb) for Java, built on the Java Foreign
Function & Memory API, with USB device plug and unplug detection. The jar
contains libusb for every supported platform, so there is nothing else to
install.

| OS | x86-64 | aarch64 |
|---------|----|----|
| Linux (glibc 2.34+, such as Ubuntu 22.04, Debian 12, RHEL 9) | ✓ | ✓ |
| macOS 11+ | ✓ | ✓ |
| Windows | ✓ | ✓ |

Requires Java 23 or later.

jlibusb is also the shared base of the SDR libraries jAirspyHF, jBladeRF,
jHackRF, jHydraSDR, jLimeSDR and jRTL: it loads one copy of libusb that all of
them use. See [Building a library on jlibusb](#building-a-library-on-jlibusb).

## Getting it

Download `jlibusb-<version>.jar` from
[Releases](https://github.com/DSheirer/jlibusb/releases). The rolling
[`nightly`](https://github.com/DSheirer/jlibusb/releases/tag/nightly)
pre-release tracks `main`.

List the devices your machine can see, then watch for changes:

```
java -jar jlibusb-<version>.jar --watch
```

## Using it

Watch for devices being connected and disconnected:

```java
UsbHotplug hotplug = UsbHotplug.start(
    device -> device.matches(0x1d50, 0x6089),      // HackRF One; omit to see everything
    new UsbHotplugListener() {
        public void arrived(UsbDeviceInfo device) { ... }
        public void left(UsbDeviceInfo device) { ... }
    });
...
hotplug.close();
```

Devices already connected are reported as arrivals when monitoring starts.
Listener calls arrive in order on a single dispatch thread, so a listener may
open the device or call another library. On Linux and macOS libusb reports
changes as they happen; elsewhere the device list is polled once a second
(`UsbHotplug.builder().pollInterval(...)` changes that). `hotplug.mode()` says
which is in use.

List devices and read their strings:

```java
try (UsbContext usb = UsbContext.create()) {
    for (UsbDeviceInfo device : usb.devices()) {
        UsbStrings strings = usb.readStrings(device);   // opens the device briefly
        System.out.println(device + " " + strings.serialNumber());
    }
}
```

`UsbDeviceInfo.portPath()` names the physical socket, such as `1-2.3`, and
stays the same when a device is plugged into the same socket again.

### Platform notes

- **Linux:** libusb is built without libudev, and hotplug uses kernel netlink
  events. Opening a device needs a udev rule granting access; listing and
  hotplug do not.
- **Windows:** reading strings, like any open, needs the device to use the
  WinUSB driver.

## Building a library on jlibusb

A library that wraps a libusb-based driver depends on jlibusb and loads its
own native code through `NativeLoader`:

```java
SymbolLookup lookup = NativeLoader.load(Native.class, "/jhackrf/native/",
    List.of("libhackrf.so"), "jhackrf.library.path");
```

`NativeLoader` loads libusb first, then extracts the library into the same
private directory. A library linked the usual way (`RUNPATH $ORIGIN` on Linux,
`@loader_path/libusb-1.0.0.dylib` on macOS, plain `libusb-1.0.dll` on Windows)
then finds jlibusb's libusb next to itself, and the process holds one copy of
libusb however many libraries use it. The dependent jar no longer bundles
libusb; it still links against libusb headers and an import library when it is
built. libusb keeps its ABI stable, so a library built against one 1.0.x
release runs with any later one.

## Building it

```
native/build-unix.sh                         # Linux or macOS
native\build-windows.ps1 -Arch x64|ARM64     # Windows
./gradlew build
```

The scripts put the library in `dist/native/<platform>`, which the jar
includes. The GitHub workflows build all six platforms and publish releases;
a tag must match `LIBUSB_VERSION` in `native/versions.env`, as `v1.0.30` or
`v1.0.30.1` for a Java-only fix.

## License

Apache License 2.0. libusb is LGPL-2.1-or-later; see
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
