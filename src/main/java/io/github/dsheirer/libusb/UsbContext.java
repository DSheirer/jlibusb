package io.github.dsheirer.libusb;

import static io.github.dsheirer.libusb.internal.Native.invoke;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;

import io.github.dsheirer.libusb.internal.Layouts;
import io.github.dsheirer.libusb.internal.Native;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A libusb context: an independent session with its own device list. Thread
 * safe. Close it when done.
 *
 * <pre>{@code
 * try (UsbContext usb = UsbContext.create()) {
 *     for (UsbDeviceInfo device : usb.devices()) {
 *         System.out.println(device);
 *     }
 * }
 * }</pre>
 *
 * <p>Libraries built on libusb, such as libhackrf, create contexts of their
 * own, so a context here never interferes with devices they have open.
 */
public final class UsbContext implements AutoCloseable {
    private final MemorySegment context;
    private final AtomicBoolean closed = new AtomicBoolean();

    private UsbContext(MemorySegment context) {
        this.context = context;
    }

    /** Creates a context. */
    public static UsbContext create() throws UsbException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pointer = arena.allocate(ADDRESS);
            int result;
            String function;
            if (Native.INIT_CONTEXT.isPresent()) {
                MethodHandle init = Native.INIT_CONTEXT.get();
                result = invoke(() -> (int) init.invokeExact(pointer, MemorySegment.NULL, 0));
                function = "libusb_init_context";
            } else {
                result = invoke(() -> (int) Native.INIT.invokeExact(pointer));
                function = "libusb_init";
            }
            if (result < 0) {
                throw new UsbException(function, result);
            }
            return new UsbContext(pointer.get(ADDRESS, 0));
        }
    }

    /** Sets how much this context writes to stderr. */
    public void setLogLevel(UsbLogLevel level) throws UsbException {
        int result = invoke(() -> (int) Native.SET_OPTION_INT.invokeExact(handle(), Layouts.OPTION_LOG_LEVEL,
            level.ordinal()));
        if (result < 0) {
            throw new UsbException("libusb_set_option", result);
        }
    }

    /** Lists the connected devices. Devices whose descriptor cannot be read are left out. */
    public List<UsbDeviceInfo> devices() throws UsbException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pointer = arena.allocate(ADDRESS);
            MemorySegment ctx = handle();
            long count = invoke(() -> (long) Native.GET_DEVICE_LIST.invokeExact(ctx, pointer));
            if (count < 0) {
                throw new UsbException("libusb_get_device_list", (int) count);
            }
            MemorySegment list = pointer.get(ADDRESS, 0).reinterpret(ADDRESS.byteSize() * (count + 1));
            try {
                List<UsbDeviceInfo> devices = new ArrayList<>((int) count);
                for (long i = 0; i < count; i++) {
                    UsbDeviceInfo info = describe(list.getAtIndex(ADDRESS, i), arena);
                    if (info != null) {
                        devices.add(info);
                    }
                }
                return devices;
            } finally {
                invoke(() -> {
                    Native.FREE_DEVICE_LIST.invokeExact(list, 1);
                    return null;
                });
            }
        }
    }

    /**
     * Opens a device. Fails with {@code LIBUSB_ERROR_ACCESS} (-3) when the
     * user lacks permission, and on Windows when the device has no WinUSB
     * driver. Close the handle before this context.
     *
     * @throws UsbException with {@code LIBUSB_ERROR_NO_DEVICE} (-4) when the device is no longer connected
     */
    public UsbDeviceHandle open(UsbDeviceInfo device) throws UsbException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pointer = arena.allocate(ADDRESS);
            MemorySegment ctx = handle();
            long count = invoke(() -> (long) Native.GET_DEVICE_LIST.invokeExact(ctx, pointer));
            if (count < 0) {
                throw new UsbException("libusb_get_device_list", (int) count);
            }
            MemorySegment list = pointer.get(ADDRESS, 0).reinterpret(ADDRESS.byteSize() * (count + 1));
            try {
                for (long i = 0; i < count; i++) {
                    MemorySegment candidate = list.getAtIndex(ADDRESS, i);
                    UsbDeviceInfo info = describe(candidate, arena);
                    if (info != null && info.sameConnection(device)) {
                        MemorySegment handlePointer = arena.allocate(ADDRESS);
                        int result = invoke(() -> (int) Native.OPEN.invokeExact(candidate, handlePointer));
                        if (result < 0) {
                            throw new UsbException("libusb_open", result);
                        }
                        // The handle holds its own reference to the device, so the list can be freed.
                        return new UsbDeviceHandle(handlePointer.get(ADDRESS, 0));
                    }
                }
                throw new UsbException("libusb_open", Layouts.ERROR_NO_DEVICE);
            } finally {
                invoke(() -> {
                    Native.FREE_DEVICE_LIST.invokeExact(list, 1);
                    return null;
                });
            }
        }
    }

    /**
     * Opens a device briefly to read its manufacturer, product and serial
     * number strings. Fails as {@link #open} does.
     */
    public UsbStrings readStrings(UsbDeviceInfo device) throws UsbException {
        try (UsbDeviceHandle handle = open(device)) {
            return new UsbStrings(
                handle.readString(device.manufacturerIndex()),
                handle.readString(device.productIndex()),
                handle.readString(device.serialNumberIndex()));
        }
    }

    /** Releases the context. Further calls fail with {@link IllegalStateException}. */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            invoke(() -> {
                Native.EXIT.invokeExact(context);
                return null;
            });
        }
    }

    /** The {@code libusb_context*}, for use within jlibusb. */
    MemorySegment handle() {
        if (closed.get()) {
            throw new IllegalStateException("The context is closed");
        }
        return context;
    }

    /**
     * Describes a {@code libusb_device*}. Safe to call from a hotplug callback.
     * Returns null when the descriptor cannot be read.
     */
    static UsbDeviceInfo describe(MemorySegment device, Arena arena) {
        MemorySegment descriptor = arena.allocate(Layouts.DEVICE_DESCRIPTOR);
        int result = invoke(() -> (int) Native.GET_DEVICE_DESCRIPTOR.invokeExact(device, descriptor));
        if (result < 0) {
            return null;
        }
        int bus = Byte.toUnsignedInt(invoke(() -> (byte) Native.GET_BUS_NUMBER.invokeExact(device)));
        int address = Byte.toUnsignedInt(invoke(() -> (byte) Native.GET_DEVICE_ADDRESS.invokeExact(device)));
        MemorySegment ports = arena.allocate(Layouts.MAX_PORT_DEPTH);
        int depth = invoke(() -> (int) Native.GET_PORT_NUMBERS.invokeExact(device, ports, Layouts.MAX_PORT_DEPTH));
        List<Integer> portNumbers = new ArrayList<>();
        for (int i = 0; i < depth; i++) {
            portNumbers.add(Byte.toUnsignedInt(ports.get(JAVA_BYTE, i)));
        }
        int speed = invoke(() -> (int) Native.GET_DEVICE_SPEED.invokeExact(device));
        return new UsbDeviceInfo(bus, address, portNumbers,
            Layouts.u16(descriptor, Layouts.DEVICE_DESCRIPTOR, "idVendor"),
            Layouts.u16(descriptor, Layouts.DEVICE_DESCRIPTOR, "idProduct"),
            Layouts.u16(descriptor, Layouts.DEVICE_DESCRIPTOR, "bcdDevice"),
            Layouts.u16(descriptor, Layouts.DEVICE_DESCRIPTOR, "bcdUSB"),
            Layouts.u8(descriptor, Layouts.DEVICE_DESCRIPTOR, "bDeviceClass"),
            UsbSpeed.fromCode(speed),
            Layouts.u8(descriptor, Layouts.DEVICE_DESCRIPTOR, "iManufacturer"),
            Layouts.u8(descriptor, Layouts.DEVICE_DESCRIPTOR, "iProduct"),
            Layouts.u8(descriptor, Layouts.DEVICE_DESCRIPTOR, "iSerialNumber"));
    }
}
