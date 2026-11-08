package io.github.dsheirer.libusb;

/**
 * Receives device arrivals and removals from {@link UsbHotplug}.
 *
 * <p>Calls arrive in order on one dispatch thread, never on the libusb event
 * thread, so a listener may open the device or call other libraries. A slow
 * listener delays later events but loses none. Exceptions are logged and
 * otherwise ignored.
 */
public interface UsbHotplugListener {
    /** A device matching the filter was connected, or was already connected when monitoring started. */
    void arrived(UsbDeviceInfo device);

    /** A device reported to {@link #arrived} was disconnected. */
    void left(UsbDeviceInfo device);
}
