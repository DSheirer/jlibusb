package io.github.dsheirer.libusb;

/** How much libusb writes to stderr, {@code enum libusb_log_level}. */
public enum UsbLogLevel {
    /** No messages. The default. */
    NONE,
    /** Errors only. */
    ERROR,
    /** Errors and warnings. */
    WARNING,
    /** Errors, warnings and information. */
    INFO,
    /** Everything, including debug messages. */
    DEBUG
}
