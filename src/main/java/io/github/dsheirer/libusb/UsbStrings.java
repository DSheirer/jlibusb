package io.github.dsheirer.libusb;

/**
 * The string descriptors of a device. A field is null when the device has no
 * such string or it could not be read.
 *
 * @param manufacturer manufacturer name
 * @param product product name
 * @param serialNumber serial number
 */
public record UsbStrings(String manufacturer, String product, String serialNumber) {
}
