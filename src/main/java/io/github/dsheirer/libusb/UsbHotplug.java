package io.github.dsheirer.libusb;

import static io.github.dsheirer.libusb.internal.Native.invoke;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import io.github.dsheirer.libusb.internal.Layouts;
import io.github.dsheirer.libusb.internal.Native;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Reports USB devices as they are connected and disconnected.
 *
 * <pre>{@code
 * UsbHotplug hotplug = UsbHotplug.start(d -> d.vendorId() == 0x1d50, new UsbHotplugListener() {
 *     public void arrived(UsbDeviceInfo device) { ... }
 *     public void left(UsbDeviceInfo device) { ... }
 * });
 * ...
 * hotplug.close();
 * }</pre>
 *
 * <p>Devices already connected are reported as arrivals when monitoring
 * starts, so a listener sees every device through one path.
 *
 * <p>Where libusb supports hotplug (Linux and macOS) events come from libusb
 * as they happen. Elsewhere, Windows included, the device list is polled and
 * compared with the previous one, once a second by default. {@link #mode()}
 * tells which is in use.
 */
public final class UsbHotplug implements AutoCloseable {
    /** How changes are detected. */
    public enum Mode {
        /** libusb hotplug callbacks. */
        NATIVE,
        /** Comparing successive device lists. */
        POLLING
    }

    private static final System.Logger LOG = System.getLogger(UsbHotplug.class.getName());
    private static final Duration EVENT_TIMEOUT = Duration.ofSeconds(1);

    private final UsbContext context;
    private final Predicate<UsbDeviceInfo> filter;
    private final UsbHotplugListener listener;
    private final Mode mode;
    private final Duration pollInterval;
    private final Map<Key, UsbDeviceInfo> known = new LinkedHashMap<>();
    private final ExecutorService dispatcher;
    private final Object lock = new Object();
    private volatile Thread dispatchThread;
    private volatile boolean running = true;
    private Thread monitor;
    private Arena arena;
    private int callbackHandle;

    /** Identity of one connection of a device. */
    private record Key(int bus, int address, List<Integer> ports) {
        static Key of(UsbDeviceInfo device) {
            return new Key(device.busNumber(), device.address(), device.portNumbers());
        }
    }

    private UsbHotplug(Builder builder, UsbHotplugListener listener, UsbContext context) {
        this.context = context;
        this.filter = builder.filter;
        this.listener = listener;
        this.pollInterval = builder.pollInterval;
        this.mode = builder.forcePolling || !LibUsb.hasHotplug() ? Mode.POLLING : Mode.NATIVE;
        this.dispatcher = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "jlibusb-hotplug-dispatch");
            thread.setDaemon(true);
            dispatchThread = thread;
            return thread;
        });
    }

    /** Starts reporting every device. */
    public static UsbHotplug start(UsbHotplugListener listener) throws UsbException {
        return builder().start(listener);
    }

    /** Starts reporting the devices that pass a filter. */
    public static UsbHotplug start(Predicate<UsbDeviceInfo> filter, UsbHotplugListener listener) throws UsbException {
        return builder().filter(filter).start(listener);
    }

    /** Options for starting a monitor. */
    public static Builder builder() {
        return new Builder();
    }

    /** How changes are detected. */
    public Mode mode() {
        return mode;
    }

    /** The devices passing the filter that are currently connected, as last reported to the listener. */
    public List<UsbDeviceInfo> devices() {
        synchronized (known) {
            return List.copyOf(known.values());
        }
    }

    /**
     * Stops monitoring and waits for the listener to return from the event it
     * is handling. No events are delivered afterwards. May be called from the
     * listener.
     */
    @Override
    public void close() {
        synchronized (lock) {
            if (!running) {
                return;
            }
            running = false;
            lock.notifyAll();
        }
        if (mode == Mode.NATIVE) {
            MemorySegment ctx = context.handle();
            invoke(() -> {
                Native.INTERRUPT_EVENT_HANDLER.invokeExact(ctx);
                return null;
            });
        }
        join(monitor);
        if (mode == Mode.NATIVE) {
            MemorySegment ctx = context.handle();
            int handle = callbackHandle;
            invoke(() -> {
                Native.HOTPLUG_DEREGISTER_CALLBACK.invokeExact(ctx, handle);
                return null;
            });
        }
        context.close();
        if (arena != null) {
            arena.close();
        }
        dispatcher.shutdown();
        if (Thread.currentThread() != dispatchThread) {
            try {
                dispatcher.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void startNative() throws UsbException {
        arena = Arena.ofShared();
        MethodHandle target;
        try {
            target = MethodHandles.lookup().findVirtual(UsbHotplug.class, "onHotplug", MethodType.methodType(
                int.class, MemorySegment.class, MemorySegment.class, int.class, MemorySegment.class)).bindTo(this);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        MemorySegment stub = Native.linker().upcallStub(target, Native.HOTPLUG_CALLBACK, arena);
        MemorySegment handle = arena.allocate(JAVA_INT);
        MemorySegment ctx = context.handle();
        // ENUMERATE reports the devices already connected, through the
        // callback, before this call returns.
        int result = invoke(() -> (int) Native.HOTPLUG_REGISTER_CALLBACK.invokeExact(ctx,
            Layouts.HOTPLUG_EVENT_DEVICE_ARRIVED | Layouts.HOTPLUG_EVENT_DEVICE_LEFT, Layouts.HOTPLUG_ENUMERATE,
            Layouts.HOTPLUG_MATCH_ANY, Layouts.HOTPLUG_MATCH_ANY, Layouts.HOTPLUG_MATCH_ANY, stub,
            MemorySegment.NULL, handle));
        if (result < 0) {
            arena.close();
            arena = null;
            throw new UsbException("libusb_hotplug_register_callback", result);
        }
        callbackHandle = handle.get(JAVA_INT, 0);
        monitor = Thread.ofPlatform().name("jlibusb-hotplug-events").daemon().start(this::handleEvents);
    }

    private void handleEvents() {
        MemorySegment ctx = context.handle();
        try (Arena local = Arena.ofConfined()) {
            MemorySegment timeout = Layouts.timeval(local, EVENT_TIMEOUT);
            while (running) {
                int result = invoke(() -> (int) Native.HANDLE_EVENTS_TIMEOUT_COMPLETED.invokeExact(ctx, timeout,
                    MemorySegment.NULL));
                if (result < 0 && running) {
                    LOG.log(System.Logger.Level.WARNING, "libusb_handle_events failed: " + LibUsb.errorName(result));
                    sleep(EVENT_TIMEOUT);
                }
            }
        }
    }

    /**
     * The libusb hotplug callback. Runs on the event thread, or on the starting
     * thread for the initial enumeration. Must not throw: an exception leaving
     * an upcall ends the JVM. Returning 0 keeps the callback registered.
     */
    @SuppressWarnings("unused")
    private int onHotplug(MemorySegment ctx, MemorySegment device, int event, MemorySegment userData) {
        try {
            try (Arena local = Arena.ofConfined()) {
                UsbDeviceInfo info = UsbContext.describe(device, local);
                if (info == null) {
                    return 0;
                }
                if (event == Layouts.HOTPLUG_EVENT_DEVICE_ARRIVED) {
                    arrived(info);
                } else if (event == Layouts.HOTPLUG_EVENT_DEVICE_LEFT) {
                    left(info);
                }
            }
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.WARNING, "Hotplug callback failed", t);
        }
        return 0;
    }

    private void startPolling() throws UsbException {
        // The first list is read before returning, as the native mode does,
        // so that start() fails here rather than on the monitor thread.
        update(context.devices());
        monitor = Thread.ofPlatform().name("jlibusb-hotplug-poll").daemon().start(this::poll);
    }

    private void poll() {
        while (running) {
            synchronized (lock) {
                if (!running) {
                    return;
                }
                try {
                    lock.wait(pollInterval.toMillis());
                } catch (InterruptedException e) {
                    return;
                }
            }
            if (!running) {
                return;
            }
            try {
                update(context.devices());
            } catch (UsbException | RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Unable to list USB devices", e);
            }
        }
    }

    /** Reports the differences between a device list and the devices last reported. */
    private void update(List<UsbDeviceInfo> current) {
        Map<Key, UsbDeviceInfo> present = new LinkedHashMap<>();
        for (UsbDeviceInfo device : current) {
            present.put(Key.of(device), device);
        }
        List<UsbDeviceInfo> gone = new ArrayList<>();
        synchronized (known) {
            for (Map.Entry<Key, UsbDeviceInfo> entry : known.entrySet()) {
                if (!present.containsKey(entry.getKey())) {
                    gone.add(entry.getValue());
                }
            }
        }
        gone.forEach(this::left);
        present.values().forEach(this::arrived);
    }

    private void arrived(UsbDeviceInfo device) {
        if (!filter.test(device)) {
            return;
        }
        synchronized (known) {
            if (known.putIfAbsent(Key.of(device), device) != null) {
                return;
            }
        }
        dispatch(() -> listener.arrived(device));
    }

    private void left(UsbDeviceInfo device) {
        UsbDeviceInfo removed;
        synchronized (known) {
            removed = known.remove(Key.of(device));
        }
        if (removed != null) {
            dispatch(() -> listener.left(removed));
        }
    }

    private void dispatch(Runnable event) {
        if (!running) {
            return;
        }
        try {
            dispatcher.execute(() -> {
                if (!running) {
                    return;
                }
                try {
                    event.run();
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "USB hotplug listener failed", e);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // Closed while the event was being reported.
        }
    }

    private static void join(Thread thread) {
        if (thread == null || thread == Thread.currentThread()) {
            return;
        }
        try {
            thread.join(Duration.ofSeconds(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Options for {@link UsbHotplug}. */
    public static final class Builder {
        private Predicate<UsbDeviceInfo> filter = device -> true;
        private Duration pollInterval = Duration.ofSeconds(1);
        private boolean forcePolling;

        private Builder() {
        }

        /** Reports only devices that pass this test. Default: every device. */
        public Builder filter(Predicate<UsbDeviceInfo> filter) {
            this.filter = Objects.requireNonNull(filter, "filter");
            return this;
        }

        /** How often to list devices when polling. Default: one second. */
        public Builder pollInterval(Duration interval) {
            if (interval.isNegative() || interval.isZero()) {
                throw new IllegalArgumentException("Poll interval must be positive: " + interval);
            }
            this.pollInterval = interval;
            return this;
        }

        /** Polls even where libusb supports hotplug. Default: false. */
        public Builder forcePolling(boolean forcePolling) {
            this.forcePolling = forcePolling;
            return this;
        }

        /** Starts monitoring. */
        public UsbHotplug start(UsbHotplugListener listener) throws UsbException {
            Objects.requireNonNull(listener, "listener");
            UsbContext context = UsbContext.create();
            UsbHotplug hotplug = new UsbHotplug(this, listener, context);
            try {
                if (hotplug.mode == Mode.NATIVE) {
                    hotplug.startNative();
                } else {
                    hotplug.startPolling();
                }
            } catch (UsbException | RuntimeException e) {
                hotplug.running = false;
                hotplug.dispatcher.shutdownNow();
                context.close();
                throw e;
            }
            return hotplug;
        }
    }
}
