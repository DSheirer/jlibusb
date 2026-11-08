package io.github.dsheirer.libusb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.libusb.internal.Layouts;
import io.github.dsheirer.libusb.internal.Native;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** Tests that need the native library for the current platform but no particular device. */
class LibraryTest {
    @Test
    void structureSizesMatchHeader() {
        assertEquals(24, Layouts.VERSION.byteSize());
        assertEquals(8, Layouts.offset(Layouts.VERSION, "rc"));
        assertEquals(18, Layouts.DEVICE_DESCRIPTOR.byteSize());
        assertEquals(8, Layouts.offset(Layouts.DEVICE_DESCRIPTOR, "idVendor"));
        assertEquals(12, Layouts.offset(Layouts.DEVICE_DESCRIPTOR, "bcdDevice"));
        assertEquals(16, Layouts.offset(Layouts.DEVICE_DESCRIPTOR, "iSerialNumber"));
        boolean windows = NativeLoader.platform().startsWith("windows");
        assertEquals(windows ? 8 : 16, Layouts.TIMEVAL.byteSize());
    }

    @Test
    void namesPlatforms() {
        assertEquals("linux-x86_64", NativeLoader.platform("Linux", "amd64"));
        assertEquals("linux-aarch64", NativeLoader.platform("Linux", "aarch64"));
        assertEquals("macos-aarch64", NativeLoader.platform("Mac OS X", "aarch64"));
        assertEquals("macos-x86_64", NativeLoader.platform("Mac OS X", "x86_64"));
        assertEquals("windows-x86_64", NativeLoader.platform("Windows 11", "amd64"));
        assertEquals("windows-aarch64", NativeLoader.platform("Windows 11", "aarch64"));
        assertThrows(UnsatisfiedLinkError.class, () -> NativeLoader.platform("FreeBSD", "amd64"));
        assertThrows(UnsatisfiedLinkError.class, () -> NativeLoader.platform("Linux", "arm"));
        assertEquals("libusb-1.0.so.0", NativeLoader.libusbName("linux-aarch64"));
        assertEquals("libusb-1.0.0.dylib", NativeLoader.libusbName("macos-x86_64"));
        assertEquals("libusb-1.0.dll", NativeLoader.libusbName("windows-aarch64"));
    }

    @Test
    void loadsLibusbIntoSharedDirectory() {
        assertNotNull(Native.GET_VERSION);
        String name = NativeLoader.libusbName(NativeLoader.platform());
        assertTrue(Files.isRegularFile(NativeLoader.directory().resolve(name)));
        assertTrue(Native.INIT_CONTEXT.isPresent());
    }

    @Test
    void dependentLibraryWithoutNativesFails() {
        UnsatisfiedLinkError error = assertThrows(UnsatisfiedLinkError.class,
            () -> NativeLoader.load(LibraryTest.class, "/no-such-library/native", List.of("libnothing.so"), null));
        assertTrue(error.getMessage().contains("has no native libraries"), error.getMessage());
    }

    @Test
    void reportsVersion() {
        LibUsbVersion version = LibUsb.version();
        assertEquals(1, version.major());
        assertEquals(0, version.minor());
        assertTrue(version.micro() >= 27, version.toString());
    }

    @Test
    void decodesErrors() {
        assertEquals("LIBUSB_ERROR_ACCESS", LibUsb.errorName(-3));
        assertFalse(LibUsb.errorDescription(-3).isBlank());
        UsbException e = new UsbException("libusb_open", -3);
        assertEquals(-3, e.code());
        assertEquals("libusb_open", e.function());
        assertTrue(e.getMessage().startsWith("libusb_open failed: LIBUSB_ERROR_ACCESS (-3): "), e.getMessage());
    }

    @Test
    void reportsCapabilities() {
        // libusb implements hotplug on Linux and macOS; Windows support
        // depends on the release, so it is only reported.
        boolean windows = NativeLoader.platform().startsWith("windows");
        if (windows) {
            System.out.println("Windows hotplug capability: " + LibUsb.hasHotplug());
        } else {
            assertTrue(LibUsb.hasHotplug(), "hotplug capability");
        }
        // The Linux and macOS (darwin) backends both declare kernel driver detach.
        assertEquals(!windows, LibUsb.supportsDetachKernelDriver(), "detach kernel driver capability");
    }

    @Test
    void formatsDeviceInfo() {
        UsbDeviceInfo device = new UsbDeviceInfo(1, 7, List.of(2, 3), 0x1d50, 0x6089, 0x0102, 0x0200, 0,
            UsbSpeed.HIGH, 1, 2, 3);
        assertEquals("1d50:6089", device.vendorProduct());
        assertEquals("1-2.3", device.portPath());
        assertTrue(device.matches(0x1d50, 0x6089));
        assertEquals("usb2", new UsbDeviceInfo(2, 1, List.of(), 0x1d6b, 2, 0, 0x0200, 9, UsbSpeed.HIGH, 0, 0, 0)
            .portPath());
        assertEquals(UsbSpeed.SUPER_PLUS, UsbSpeed.fromCode(5));
        assertEquals(UsbSpeed.UNKNOWN, UsbSpeed.fromCode(99));
    }

    @Test
    void listsDevices() throws UsbException {
        try (UsbContext usb = UsbContext.create()) {
            usb.setLogLevel(UsbLogLevel.NONE);
            List<UsbDeviceInfo> devices = usb.devices();
            devices.forEach(device -> System.out.println("  " + device));
            for (UsbDeviceInfo device : devices) {
                assertTrue(device.busNumber() >= 0 && device.address() >= 0, device.toString());
            }
        }
    }

    @Test
    void closedContextRejectsCalls() throws UsbException {
        UsbContext usb = UsbContext.create();
        usb.close();
        usb.close();
        assertThrows(IllegalStateException.class, usb::devices);
    }

    @Test
    void hotplugStartsAndStops() throws UsbException {
        assertHotplugMatchesDeviceList(UsbHotplug.builder());
    }

    @Test
    void pollingHotplugStartsAndStops() throws UsbException {
        assertHotplugMatchesDeviceList(UsbHotplug.builder().forcePolling(true).pollInterval(Duration.ofMillis(50)));
    }

    /** The initial arrivals are the connected devices; closing returns promptly. */
    private static void assertHotplugMatchesDeviceList(UsbHotplug.Builder builder) throws UsbException {
        List<UsbDeviceInfo> arrived = new CopyOnWriteArrayList<>();
        List<UsbDeviceInfo> left = new CopyOnWriteArrayList<>();
        int expected;
        try (UsbContext usb = UsbContext.create()) {
            expected = usb.devices().size();
        }
        long start = System.nanoTime();
        try (UsbHotplug hotplug = builder.start(new UsbHotplugListener() {
            @Override
            public void arrived(UsbDeviceInfo device) {
                arrived.add(device);
            }

            @Override
            public void left(UsbDeviceInfo device) {
                left.add(device);
            }
        })) {
            assertEquals(expected, hotplug.devices().size(), hotplug.mode().toString());
            sleep(300);
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        assertTrue(elapsed.compareTo(Duration.ofSeconds(3)) < 0, "close took " + elapsed);
        assertEquals(expected, arrived.size());
        assertTrue(left.isEmpty(), left.toString());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
