package io.github.dsheirer.libusb.internal;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import io.github.dsheirer.libusb.NativeLoader;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.Optional;

/**
 * Downcall handles for the libusb functions jlibusb uses.
 *
 * <p>C enums are passed as {@code int}, {@code uint8_t} as {@code byte},
 * {@code uint32_t} as {@code int} and {@code ssize_t} as {@code long}: every
 * supported platform is 64-bit. {@code LIBUSB_CALL} is {@code WINAPI} on
 * Windows, which x64 and ARM64 treat as the one native calling convention.
 *
 * <p>{@code native/check-bindings.sh} checks that every function named here is
 * declared in {@code libusb.h}.
 */
public final class Native {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LOOKUP = NativeLoader.libusb();

    // ---- Library ----
    public static final MethodHandle GET_VERSION = pointerCall("libusb_get_version");
    public static final MethodHandle HAS_CAPABILITY = call("libusb_has_capability", JAVA_INT);
    public static final MethodHandle ERROR_NAME = pointerCall("libusb_error_name", JAVA_INT);
    public static final MethodHandle STRERROR = pointerCall("libusb_strerror", JAVA_INT);
    /** {@code libusb_init_context}, added in libusb 1.0.27; empty with an older replacement libusb. */
    public static final Optional<MethodHandle> INIT_CONTEXT = optional("libusb_init_context",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
    public static final MethodHandle INIT = call("libusb_init", ADDRESS);
    public static final MethodHandle EXIT = voidCall("libusb_exit", ADDRESS);
    /** {@code libusb_set_option(ctx, option, int)}: the one variadic form jlibusb needs. */
    public static final MethodHandle SET_OPTION_INT = LINKER.downcallHandle(symbol("libusb_set_option"),
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT), Linker.Option.firstVariadicArg(2));

    // ---- Devices ----
    public static final MethodHandle GET_DEVICE_LIST = LINKER.downcallHandle(symbol("libusb_get_device_list"),
        FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS));
    public static final MethodHandle FREE_DEVICE_LIST = voidCall("libusb_free_device_list", ADDRESS, JAVA_INT);
    public static final MethodHandle REF_DEVICE = pointerCall("libusb_ref_device", ADDRESS);
    public static final MethodHandle UNREF_DEVICE = voidCall("libusb_unref_device", ADDRESS);
    public static final MethodHandle GET_DEVICE_DESCRIPTOR = call("libusb_get_device_descriptor", ADDRESS, ADDRESS);
    public static final MethodHandle GET_BUS_NUMBER = LINKER.downcallHandle(symbol("libusb_get_bus_number"),
        FunctionDescriptor.of(JAVA_BYTE, ADDRESS));
    public static final MethodHandle GET_DEVICE_ADDRESS = LINKER.downcallHandle(symbol("libusb_get_device_address"),
        FunctionDescriptor.of(JAVA_BYTE, ADDRESS));
    public static final MethodHandle GET_PORT_NUMBERS = call("libusb_get_port_numbers", ADDRESS, ADDRESS, JAVA_INT);
    public static final MethodHandle GET_DEVICE_SPEED = call("libusb_get_device_speed", ADDRESS);
    public static final MethodHandle OPEN = call("libusb_open", ADDRESS, ADDRESS);
    public static final MethodHandle CLOSE = voidCall("libusb_close", ADDRESS);
    public static final MethodHandle GET_STRING_DESCRIPTOR_ASCII =
        call("libusb_get_string_descriptor_ascii", ADDRESS, JAVA_BYTE, ADDRESS, JAVA_INT);

    // ---- Hotplug and events ----
    public static final MethodHandle HOTPLUG_REGISTER_CALLBACK = call("libusb_hotplug_register_callback",
        ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
    public static final MethodHandle HOTPLUG_DEREGISTER_CALLBACK =
        voidCall("libusb_hotplug_deregister_callback", ADDRESS, JAVA_INT);
    public static final MethodHandle HANDLE_EVENTS_TIMEOUT_COMPLETED =
        call("libusb_handle_events_timeout_completed", ADDRESS, ADDRESS, ADDRESS);
    public static final MethodHandle INTERRUPT_EVENT_HANDLER = voidCall("libusb_interrupt_event_handler", ADDRESS);

    /** {@code int (*libusb_hotplug_callback_fn)(libusb_context*, libusb_device*, libusb_hotplug_event, void*)}. */
    public static final FunctionDescriptor HOTPLUG_CALLBACK =
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS);

    private Native() {
    }

    /** A call to a downcall handle. */
    @FunctionalInterface
    public interface Call<T> {
        /** Makes the call. */
        T call() throws Throwable;
    }

    /**
     * Makes a downcall, rethrowing the checked {@code Throwable} that
     * {@code invokeExact} declares. Downcalls never throw one in practice.
     */
    public static <T> T invoke(Call<T> call) {
        try {
            return call.call();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** Reads a NUL-terminated string returned by libusb, or null for a null pointer. */
    public static String string(MemorySegment pointer) {
        if (pointer.equals(MemorySegment.NULL)) {
            return null;
        }
        return pointer.reinterpret(Long.MAX_VALUE).getString(0);
    }

    /** The native linker, for upcall stubs. */
    public static Linker linker() {
        return LINKER;
    }

    private static MemorySegment symbol(String name) {
        return LOOKUP.find(name).orElseThrow(() -> new UnsatisfiedLinkError("libusb has no function " + name));
    }

    private static Optional<MethodHandle> optional(String name, FunctionDescriptor descriptor) {
        return LOOKUP.find(name).map(address -> LINKER.downcallHandle(address, descriptor));
    }

    private static MethodHandle call(String name, MemoryLayout... arguments) {
        return LINKER.downcallHandle(symbol(name), FunctionDescriptor.of(JAVA_INT, arguments));
    }

    private static MethodHandle pointerCall(String name, MemoryLayout... arguments) {
        return LINKER.downcallHandle(symbol(name), FunctionDescriptor.of(ADDRESS, arguments));
    }

    private static MethodHandle voidCall(String name, MemoryLayout... arguments) {
        return LINKER.downcallHandle(symbol(name), FunctionDescriptor.ofVoid(arguments));
    }
}
