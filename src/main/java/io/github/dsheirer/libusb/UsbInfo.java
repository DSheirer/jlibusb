package io.github.dsheirer.libusb;

import java.io.IOException;
import java.util.List;

/**
 * Command line tool that prints the libusb version, its capabilities and the
 * connected devices. With {@code --watch} it then reports devices as they are
 * connected and disconnected until Enter is pressed.
 */
public final class UsbInfo {
    private UsbInfo() {
    }

    /** Runs the tool. */
    public static void main(String[] args) throws IOException {
        LibUsbVersion version = LibUsb.version();
        System.out.println("libusb " + version + " (nano " + version.nano() + ")");
        System.out.println("Platform: " + NativeLoader.platform());
        System.out.println("Hotplug: " + (LibUsb.hasHotplug() ? "native" : "polling"));
        System.out.println("HID access: " + LibUsb.hasHidAccess());
        System.out.println("Detach kernel driver: " + LibUsb.supportsDetachKernelDriver());

        List<UsbDeviceInfo> devices;
        try (UsbContext usb = UsbContext.create()) {
            devices = usb.devices();
            System.out.println("Devices found: " + devices.size());
            for (UsbDeviceInfo device : devices) {
                System.out.println("  " + device + strings(usb, device));
            }
        } catch (UsbException e) {
            // Machines without USB access, such as containers, end up here.
            System.out.println("Unable to list devices: " + e.getMessage());
            return;
        }

        if (args.length > 0 && args[0].equals("--watch")) {
            UsbHotplugListener listener = new UsbHotplugListener() {
                @Override
                public void arrived(UsbDeviceInfo device) {
                    System.out.println("Arrived: " + device);
                }

                @Override
                public void left(UsbDeviceInfo device) {
                    System.out.println("Left:    " + device);
                }
            };
            try (UsbHotplug hotplug = UsbHotplug.start(listener)) {
                System.out.println("Watching (" + hotplug.mode() + "); press Enter to stop");
                System.in.read();
            } catch (UsbException e) {
                System.out.println("Unable to watch devices: " + e.getMessage());
            }
        }
    }

    private static String strings(UsbContext usb, UsbDeviceInfo device) {
        try {
            UsbStrings strings = usb.readStrings(device);
            StringBuilder text = new StringBuilder();
            append(text, strings.manufacturer());
            append(text, strings.product());
            if (strings.serialNumber() != null) {
                append(text, "serial " + strings.serialNumber());
            }
            return text.isEmpty() ? "" : " -" + text;
        } catch (UsbException e) {
            return "";
        }
    }

    private static void append(StringBuilder text, String value) {
        if (value != null && !value.isBlank()) {
            text.append(' ').append(value);
        }
    }
}
