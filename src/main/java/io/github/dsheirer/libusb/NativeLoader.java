package io.github.dsheirer.libusb;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Loads libusb, and the native libraries of projects that depend on it, into
 * the JVM.
 *
 * <p>Every library is extracted into one private temporary directory per JVM,
 * with libusb loaded first. A dependent library therefore finds libusb next to
 * itself ({@code $ORIGIN} on Linux, {@code @loader_path} on macOS, the DLL
 * directory on Windows) and the process holds a single copy of libusb however
 * many libraries use it.
 *
 * <p>A library such as jHackRF, whose jar holds
 * {@code /jhackrf/native/<platform>/libhackrf.so} and so on, loads its native
 * code with:
 * <pre>{@code
 * SymbolLookup lookup = NativeLoader.load(Native.class, "/jhackrf/native/",
 *     List.of(libraryName(NativeLoader.platform())), "jhackrf.library.path");
 * }</pre>
 *
 * <p>Set the {@value #PATH_PROPERTY} system property to a directory holding a
 * libusb build to use it in place of the bundled one, which the LGPL requires
 * to be possible.
 */
public final class NativeLoader {
    /** System property naming a directory that holds a replacement libusb. */
    public static final String PATH_PROPERTY = "jlibusb.library.path";

    private static final String RESOURCE_ROOT = "/jlibusb/native/";
    private static final String TEMP_PREFIX = "jlibusb-";

    private static final Map<Path, SymbolLookup> LOADED = new HashMap<>();
    private static Path directory;
    private static SymbolLookup libusb;

    private NativeLoader() {
    }

    /** Identifies the current operating system and CPU, such as {@code linux-x86_64}. */
    public static String platform() {
        return platform(System.getProperty("os.name"), System.getProperty("os.arch"));
    }

    /** Identifies an operating system and CPU, such as {@code linux-x86_64}. */
    public static String platform(String osName, String osArch) {
        String name = osName.toLowerCase(Locale.ROOT);
        String os;
        if (name.contains("linux")) {
            os = "linux";
        } else if (name.contains("mac") || name.contains("darwin")) {
            os = "macos";
        } else if (name.contains("windows")) {
            os = "windows";
        } else {
            throw new UnsatisfiedLinkError("Unsupported operating system: " + osName);
        }
        String arch = switch (osArch.toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64", "x64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> throw new UnsatisfiedLinkError("Unsupported CPU architecture: " + osArch);
        };
        return os + "-" + arch;
    }

    /** File name of the libusb library on a platform. */
    public static String libusbName(String platform) {
        if (platform.startsWith("linux")) {
            return "libusb-1.0.so.0";
        }
        if (platform.startsWith("macos")) {
            return "libusb-1.0.0.dylib";
        }
        return "libusb-1.0.dll";
    }

    /** Loads libusb, once, and returns a lookup for its symbols. */
    public static synchronized SymbolLookup libusb() {
        if (libusb == null) {
            String platform = platform();
            String name = libusbName(platform);
            Path target = directory().resolve(name);
            String override = System.getProperty(PATH_PROPERTY);
            try {
                if (override != null) {
                    // Copied rather than loaded in place, so that libraries
                    // extracted later still find it next to themselves.
                    Files.copy(Path.of(override).resolve(name), target, StandardCopyOption.REPLACE_EXISTING);
                    target.toFile().deleteOnExit();
                } else {
                    extract(NativeLoader.class, RESOURCE_ROOT + platform + "/", name);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to extract libusb", e);
            }
            libusb = open(target);
        }
        return libusb;
    }

    /**
     * Loads libusb, then a dependent project's libraries in the order given,
     * and returns a lookup for the symbols of the last one.
     *
     * @param owner class whose jar holds the libraries
     * @param resourceRoot resource directory holding a {@code <platform>/}
     *     directory per platform, such as {@code /jhackrf/native/}
     * @param names library file names for the current platform, dependencies
     *     first; libusb itself is not listed
     * @param overrideProperty system property that, when set, names a
     *     directory to load these libraries from instead of the jar, or null
     */
    public static synchronized SymbolLookup load(Class<?> owner, String resourceRoot, List<String> names,
                                                 String overrideProperty) {
        Objects.requireNonNull(owner, "owner");
        if (names.isEmpty()) {
            throw new IllegalArgumentException("No libraries to load");
        }
        libusb();
        String override = overrideProperty == null ? null : System.getProperty(overrideProperty);
        String root = resourceRoot.endsWith("/") ? resourceRoot : resourceRoot + "/";
        String platformRoot = root + platform() + "/";
        if (override == null && owner.getResource(platformRoot + names.getLast()) == null) {
            throw new UnsatisfiedLinkError("The jar of " + owner.getName() + " has no native libraries for "
                + platform());
        }
        SymbolLookup lookup = null;
        for (String name : names) {
            Path path;
            if (override != null) {
                path = Path.of(override).resolve(name);
            } else {
                try {
                    path = extract(owner, platformRoot, name);
                } catch (IOException e) {
                    throw new UncheckedIOException("Unable to extract " + name, e);
                }
            }
            lookup = open(path);
        }
        return lookup;
    }

    /** The private directory that native libraries are extracted to, created on first use. */
    public static synchronized Path directory() {
        if (directory == null) {
            if (platform().startsWith("windows")) {
                deleteStaleDirectories();
            }
            try {
                // createTempDirectory is private to this user, so other local
                // users cannot swap the libraries between extraction and loading.
                directory = Files.createTempDirectory(TEMP_PREFIX);
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to create a directory for native libraries", e);
            }
            directory.toFile().deleteOnExit();
        }
        return directory;
    }

    private static SymbolLookup open(Path path) {
        // The global arena keeps the libraries loaded for the life of the JVM.
        return LOADED.computeIfAbsent(path, p -> SymbolLookup.libraryLookup(p, Arena.global()));
    }

    /**
     * Extracts one library. Two projects may bundle a library of the same name;
     * that is accepted only when the files are identical.
     */
    private static Path extract(Class<?> owner, String platformRoot, String name) throws IOException {
        Path target = directory().resolve(name);
        try (InputStream in = owner.getResourceAsStream(platformRoot + name)) {
            if (in == null) {
                throw new UnsatisfiedLinkError("Missing native library " + platformRoot + name);
            }
            if (Files.exists(target)) {
                Path candidate = Files.createTempFile(directory(), name, ".tmp");
                try {
                    Files.copy(in, candidate, StandardCopyOption.REPLACE_EXISTING);
                    if (Files.mismatch(candidate, target) != -1) {
                        throw new UnsatisfiedLinkError(owner.getName() + " bundles a different " + name
                            + " than a library loaded earlier");
                    }
                } finally {
                    Files.deleteIfExists(candidate);
                }
                return target;
            }
            Files.copy(in, target);
        }
        target.toFile().deleteOnExit();
        return target;
    }

    /**
     * Windows locks loaded DLLs, so {@code deleteOnExit} cannot remove them.
     * Clears directories left by earlier runs; the age limit keeps this away
     * from a JVM that is starting up at the same time.
     */
    private static void deleteStaleDirectories() {
        Path temp = Path.of(System.getProperty("java.io.tmpdir"));
        Instant cutoff = Instant.now().minus(Duration.ofDays(1));
        try (Stream<Path> entries = Files.list(temp)) {
            entries.filter(path -> path.getFileName().toString().startsWith(TEMP_PREFIX))
                .filter(path -> isOlderThan(path, cutoff))
                .forEach(NativeLoader::deleteQuietly);
        } catch (IOException | RuntimeException e) {
            // Housekeeping only: failing here must not stop the library loading.
        }
    }

    private static boolean isOlderThan(Path path, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(path).toInstant().isBefore(cutoff);
        } catch (IOException e) {
            return false;
        }
    }

    private static void deleteQuietly(Path path) {
        try (Stream<Path> files = Files.walk(path)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            // A running JVM still has this directory's DLLs open.
        }
    }
}
