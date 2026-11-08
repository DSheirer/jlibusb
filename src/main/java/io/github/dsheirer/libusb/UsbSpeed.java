package io.github.dsheirer.libusb;

/** The speed a device is operating at, {@code enum libusb_speed}. */
public enum UsbSpeed {
    /** The operating system does not report the speed. */
    UNKNOWN(0),
    /** 1.5 Mbit/s. */
    LOW(1),
    /** 12 Mbit/s. */
    FULL(2),
    /** 480 Mbit/s. */
    HIGH(3),
    /** 5 Gbit/s. */
    SUPER(4),
    /** 10 Gbit/s. */
    SUPER_PLUS(5),
    /** 20 Gbit/s. */
    SUPER_PLUS_X2(6);

    private final int code;

    UsbSpeed(int code) {
        this.code = code;
    }

    /** The {@code enum libusb_speed} value. */
    public int code() {
        return code;
    }

    /** The speed for an {@code enum libusb_speed} value; unrecognized values are {@link #UNKNOWN}. */
    public static UsbSpeed fromCode(int code) {
        for (UsbSpeed speed : values()) {
            if (speed.code == code) {
                return speed;
            }
        }
        return UNKNOWN;
    }
}
