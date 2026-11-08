package io.github.dsheirer.libusb;

import static io.github.dsheirer.libusb.internal.Native.invoke;
import static java.lang.foreign.ValueLayout.ADDRESS;

import io.github.dsheirer.libusb.internal.Layouts;
import io.github.dsheirer.libusb.internal.Native;
import java.lang.foreign.MemorySegment;

/** Information about the loaded libusb. Use {@link UsbContext} to work with devices. */
public final class LibUsb {
    private LibUsb() {
    }

    /** The version of libusb in use. */
    public static LibUsbVersion version() {
        MemorySegment v = invoke(() -> (MemorySegment) Native.GET_VERSION.invokeExact())
            .reinterpret(Layouts.VERSION.byteSize());
        String rc = Native.string(v.get(ADDRESS, Layouts.offset(Layouts.VERSION, "rc")));
        return new LibUsbVersion(
            Layouts.u16(v, Layouts.VERSION, "major"),
            Layouts.u16(v, Layouts.VERSION, "minor"),
            Layouts.u16(v, Layouts.VERSION, "micro"),
            Layouts.u16(v, Layouts.VERSION, "nano"),
            rc == null ? "" : rc);
    }

    /** True when libusb reports device arrival and removal itself, rather than {@link UsbHotplug} polling. */
    public static boolean hasHotplug() {
        return hasCapability(Layouts.CAP_HAS_HOTPLUG);
    }

    /** True when libusb can talk to HID devices without detaching the operating system's driver. */
    public static boolean hasHidAccess() {
        return hasCapability(Layouts.CAP_HAS_HID_ACCESS);
    }

    /**
     * True when libusb can detach a kernel driver from an interface: Linux and
     * macOS. On macOS a detach also needs root or the
     * {@code com.apple.vm.device-access} entitlement.
     */
    public static boolean supportsDetachKernelDriver() {
        return hasCapability(Layouts.CAP_SUPPORTS_DETACH_KERNEL_DRIVER);
    }

    /** The constant name of an error code, such as {@code LIBUSB_ERROR_ACCESS}. */
    public static String errorName(int code) {
        return Native.string(invoke(() -> (MemorySegment) Native.ERROR_NAME.invokeExact(code)));
    }

    /** A short English description of an error code. */
    public static String errorDescription(int code) {
        return Native.string(invoke(() -> (MemorySegment) Native.STRERROR.invokeExact(code)));
    }

    private static boolean hasCapability(int capability) {
        int result = invoke(() -> (int) Native.HAS_CAPABILITY.invokeExact(capability));
        return result != 0;
    }
}
