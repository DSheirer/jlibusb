package io.github.dsheirer.libusb;

import static io.github.dsheirer.libusb.internal.Native.invoke;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;

import io.github.dsheirer.libusb.internal.Native;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An open USB device, from {@link UsbContext#open}. Close it before closing
 * the context that opened it.
 *
 * <p>Opening a device gives access to its string descriptors and lets an
 * application check whether another program is using it, by claiming an
 * interface. Close it again before handing the device to a driver library
 * such as LimeSuiteNG, which opens the device itself.
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
