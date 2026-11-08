package io.github.dsheirer.libusb;

import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A USB device as libusb sees it, without opening it.
 *
 * <p>The bus number and port path identify the physical socket a device is
 * plugged into and stay the same when it is plugged in again. The address is
 * assigned by the host on each connection.
 *
 * @param busNumber bus the device is on
 * @param address address on that bus
 * @param portNumbers ports from the root hub to the device; empty for a root hub
 * @param vendorId {@code idVendor}
 * @param productId {@code idProduct}
 * @param deviceRelease {@code bcdDevice}, the device release number in BCD
 * @param usbRelease {@code bcdUSB}, the USB specification release in BCD
 * @param deviceClass {@code bDeviceClass}
 * @param speed the speed the device is operating at
 * @param manufacturerIndex {@code iManufacturer}, 0 for none
 * @param productIndex {@code iProduct}, 0 for none
 * @param serialNumberIndex {@code iSerialNumber}, 0 for none
 */
public record UsbDeviceInfo(int busNumber, int address, List<Integer> portNumbers, int vendorId, int productId,
                            int deviceRelease, int usbRelease, int deviceClass, UsbSpeed speed,
                            int manufacturerIndex, int productIndex, int serialNumberIndex) {
    /** Copies the port list. */
    public UsbDeviceInfo {
        portNumbers = List.copyOf(portNumbers);
    }

    /** True when this is the given vendor and product. */
    public boolean matches(int vendorId, int productId) {
        return this.vendorId == vendorId && this.productId == productId;
    }

    /** The vendor and product IDs as {@code vvvv:pppp} in hexadecimal. */
    public String vendorProduct() {
        HexFormat hex = HexFormat.of();
        return hex.toHexDigits((short) vendorId) + ":" + hex.toHexDigits((short) productId);
    }

    /**
     * The socket a device is plugged into, as Linux names it in sysfs:
     * {@code 1-2.3} for bus 1, port 2 of the root hub, port 3 of the hub there.
     * A root hub is {@code usb1}.
     */
    public String portPath() {
        if (portNumbers.isEmpty()) {
            return "usb" + busNumber;
        }
        return busNumber + "-" + portNumbers.stream().map(String::valueOf).collect(Collectors.joining("."));
    }

    /** True when both describe the same connection: the same bus, address and port path. */
    public boolean sameConnection(UsbDeviceInfo other) {
        return busNumber == other.busNumber && address == other.address && portNumbers.equals(other.portNumbers);
    }

    @Override
    public String toString() {
        return vendorProduct() + " at " + portPath() + " (bus " + busNumber + " address " + address + ", "
            + speed + ")";
    }
}
