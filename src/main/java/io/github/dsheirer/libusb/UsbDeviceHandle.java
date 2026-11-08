package io.github.dsheirer.libusb;

import static io.github.dsheirer.libusb.internal.Native.invoke;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import io.github.dsheirer.libusb.internal.Layouts;
import io.github.dsheirer.libusb.internal.Native;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An open USB device, from {@link UsbContext#open}. Close it before closing
 * the context that opened it. Thread safe.
 *
 * <p>Opening a device gives access to its string descriptors, lets an
 * application check whether another program is using it by claiming an
 * interface, and lets it talk to the device with interrupt transfers. When a
 * driver library such as LimeSuiteNG talks to the device instead, close the
 * handle before the library opens it.
 *
 * <pre>{@code
 * try (UsbDeviceHandle handle = usb.open(device)) {
 *     handle.claimInterface(0);      // LIBUSB_ERROR_BUSY when another program has it
 *     handle.releaseInterface(0);
 *     String serial = handle.readString(device.serialNumberIndex());
 * }
 * }</pre>
 */
public final class UsbDeviceHandle implements AutoCloseable {
    private static final int STRING_BUFFER = 256;

    private final MemorySegment handle;
    private final AtomicBoolean closed = new AtomicBoolean();

    UsbDeviceHandle(MemorySegment handle) {
        this.handle = handle;
    }

    /**
     * Reads a string descriptor, such as the serial number at
     * {@link UsbDeviceInfo#serialNumberIndex()}, in ASCII with surrounding
     * whitespace removed.
     *
     * @param index string descriptor index; 0 means the device has no such string
     * @return the string, or null for index 0 or when the device cannot return it
     */
    public String readString(int index) {
        if (index == 0) {
            return null;
        }
        MemorySegment h = handle();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(STRING_BUFFER);
            int length = invoke(() -> (int) Native.GET_STRING_DESCRIPTOR_ASCII.invokeExact(h, (byte) index, buffer,
                STRING_BUFFER));
            if (length < 0) {
                return null;
            }
            byte[] bytes = buffer.asSlice(0, length).toArray(JAVA_BYTE);
            return new String(bytes, StandardCharsets.US_ASCII).trim();
        }
    }

    /**
     * Claims an interface for this handle.
     *
     * @throws UsbException with {@code LIBUSB_ERROR_BUSY} (-6) when another
     *     program or driver has claimed it
     */
    public void claimInterface(int interfaceNumber) throws UsbException {
        MemorySegment h = handle();
        int result = invoke(() -> (int) Native.CLAIM_INTERFACE.invokeExact(h, interfaceNumber));
        if (result < 0) {
            throw new UsbException("libusb_claim_interface", result);
        }
    }

    /** Releases an interface claimed with {@link #claimInterface}. */
    public void releaseInterface(int interfaceNumber) throws UsbException {
        MemorySegment h = handle();
        int result = invoke(() -> (int) Native.RELEASE_INTERFACE.invokeExact(h, interfaceNumber));
        if (result < 0) {
            throw new UsbException("libusb_release_interface", result);
        }
    }

    /**
     * Whether a kernel driver, such as Linux's usbhid, is bound to an
     * interface. Claiming the interface fails until it is detached.
     *
     * @return false also where the platform cannot report it: Windows, whose
     *     drivers are chosen per device rather than attached by the kernel
     */
    public boolean isKernelDriverActive(int interfaceNumber) throws UsbException {
        MemorySegment h = handle();
        int result = invoke(() -> (int) Native.KERNEL_DRIVER_ACTIVE.invokeExact(h, interfaceNumber));
        if (result == Layouts.ERROR_NOT_SUPPORTED) {
            return false;
        }
        if (result < 0) {
            throw new UsbException("libusb_kernel_driver_active", result);
        }
        return result == 1;
    }

    /**
     * Detaches the kernel driver from an interface. Linux and macOS; macOS
     * needs root or the {@code com.apple.vm.device-access} entitlement.
     */
    public void detachKernelDriver(int interfaceNumber) throws UsbException {
        MemorySegment h = handle();
        int result = invoke(() -> (int) Native.DETACH_KERNEL_DRIVER.invokeExact(h, interfaceNumber));
        if (result < 0) {
            throw new UsbException("libusb_detach_kernel_driver", result);
        }
    }

    /** Re-attaches a kernel driver detached with {@link #detachKernelDriver}. */
    public void attachKernelDriver(int interfaceNumber) throws UsbException {
        MemorySegment h = handle();
        int result = invoke(() -> (int) Native.ATTACH_KERNEL_DRIVER.invokeExact(h, interfaceNumber));
        if (result < 0) {
            throw new UsbException("libusb_attach_kernel_driver", result);
        }
    }

    /**
     * Performs a synchronous interrupt transfer on an endpoint of a claimed
     * interface. For an IN endpoint (bit 7 set, such as {@code 0x81}) the
     * received bytes are written to {@code data}; for an OUT endpoint the
     * first {@code length} bytes of {@code data} are sent.
     *
     * @param endpoint endpoint address, including the direction bit
     * @param data buffer to send from or receive into
     * @param length bytes to send, or the most bytes to receive
     * @param timeoutMillis how long to wait; 0 waits indefinitely
     * @return bytes transferred
     * @throws UsbException with {@code LIBUSB_ERROR_TIMEOUT} (-7) when the
     *     timeout passes, and {@code LIBUSB_ERROR_NO_DEVICE} (-4) when the
     *     device has been disconnected
     */
    public int interruptTransfer(int endpoint, byte[] data, int length, int timeoutMillis) throws UsbException {
        if (length < 0 || length > data.length) {
            throw new IllegalArgumentException("Length " + length + " outside buffer of " + data.length + " bytes");
        }
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("Negative timeout: " + timeoutMillis);
        }
        MemorySegment h = handle();
        boolean in = (endpoint & 0x80) != 0;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(Math.max(length, 1));
            if (!in && length > 0) {
                MemorySegment.copy(data, 0, buffer, JAVA_BYTE, 0, length);
            }
            MemorySegment transferred = arena.allocate(JAVA_INT);
            int result = invoke(() -> (int) Native.INTERRUPT_TRANSFER.invokeExact(h, (byte) endpoint, buffer,
                length, transferred, timeoutMillis));
            if (result < 0) {
                throw new UsbException("libusb_interrupt_transfer", result);
            }
            int count = transferred.get(JAVA_INT, 0);
            if (in && count > 0) {
                MemorySegment.copy(buffer, JAVA_BYTE, 0, data, 0, count);
            }
            return count;
        }
    }

    /** Closes the device. Interfaces still claimed are released. Further calls fail. */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            invoke(() -> {
                Native.CLOSE.invokeExact(handle);
                return null;
            });
        }
    }

    private MemorySegment handle() {
        if (closed.get()) {
            throw new IllegalStateException("The device handle is closed");
        }
        return handle;
    }
}
