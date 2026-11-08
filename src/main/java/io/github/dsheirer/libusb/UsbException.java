package io.github.dsheirer.libusb;

/** A libusb function returned an error code. */
public final class UsbException extends Exception {
    private static final long serialVersionUID = 1L;

    private final String function;
    private final int code;

    /**
     * @param function the libusb function that failed
     * @param code the negative {@code enum libusb_error} value it returned
     */
    public UsbException(String function, int code) {
        super(function + " failed: " + LibUsb.errorName(code) + " (" + code + "): " + LibUsb.errorDescription(code));
        this.function = function;
        this.code = code;
    }

    /** The libusb function that failed. */
    public String function() {
        return function;
    }

    /** The {@code enum libusb_error} value, such as -3 for {@code LIBUSB_ERROR_ACCESS}. */
    public int code() {
        return code;
    }
}
