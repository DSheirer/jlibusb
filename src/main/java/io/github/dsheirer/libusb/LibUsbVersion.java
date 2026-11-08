package io.github.dsheirer.libusb;

/**
 * The version of the loaded libusb, from {@code libusb_get_version}.
 *
 * @param major major version
 * @param minor minor version
 * @param micro micro version
 * @param nano nano version, the upstream commit count
 * @param releaseCandidate release candidate suffix such as {@code -rc1}, or empty
 */
public record LibUsbVersion(int major, int minor, int micro, int nano, String releaseCandidate) {
    @Override
    public String toString() {
        return major + "." + minor + "." + micro + releaseCandidate;
    }
}
