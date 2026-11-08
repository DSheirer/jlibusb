plugins {
    `java-library`
}

// The version follows the libusb release pinned in native/versions.env.
// Workflows pass -Pversion for tagged, nightly and pull request builds.
val pinnedVersion = file("native/versions.env").readLines()
    .first { it.startsWith("LIBUSB_VERSION=") }
    .substringAfter("=")
version = providers.gradleProperty("version").getOrElse(pinnedVersion)

repositories {
    mavenCentral()
}

// Directory holding <platform>/ subdirectories created by native/build-*.
val nativeDir = layout.projectDirectory.dir(
    providers.gradleProperty("nativeDir").getOrElse("dist/native"))
val requireAllPlatforms = providers.gradleProperty("requireAllPlatforms").isPresent
val platforms = listOf(
    "linux-x86_64", "linux-aarch64",
    "macos-x86_64", "macos-aarch64",
    "windows-x86_64", "windows-aarch64")

java {
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    // Java 23 is the newest Temurin release available on every CI platform.
    options.release = 23
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-restricted", "-Werror"))
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:all,-missing", "-quiet")
}

val checkNativeLibraries = tasks.register("checkNativeLibraries") {
    description = "Fails when the native library for a required platform is missing."
    val dir = nativeDir.asFile
    val required = requireAllPlatforms
    doLast {
        val missing = platforms.filterNot { dir.resolve(it).isDirectory }
        if (required && missing.isNotEmpty()) {
            throw GradleException("Missing native libraries for: $missing (in $dir)")
        }
        if (missing.isNotEmpty()) {
            logger.warn("Jar will not contain native libraries for: $missing")
        }
    }
}

val nativeResources = copySpec {
    from(nativeDir) {
        exclude("licenses/**")
        into("jlibusb/native")
    }
    from(nativeDir.dir("licenses")) {
        into("META-INF/licenses")
    }
    from(files("LICENSE", "THIRD-PARTY-NOTICES.md")) {
        into("META-INF")
    }
}

tasks.jar {
    dependsOn(checkNativeLibraries)
    with(nativeResources)
    manifest {
        attributes(
            "Implementation-Title" to "jlibusb",
            "Implementation-Version" to project.version,
            "Automatic-Module-Name" to "io.github.dsheirer.libusb",
            "Main-Class" to "io.github.dsheirer.libusb.UsbInfo",
            "Enable-Native-Access" to "ALL-UNNAMED")
    }
}

// Tests see the native libraries the same way a consumer of the jar does.
val testNativeResources = tasks.register<Sync>("testNativeResources") {
    with(nativeResources)
    into(layout.buildDirectory.dir("test-native"))
}
sourceSets.test {
    output.dir(mapOf("builtBy" to testNativeResources), layout.buildDirectory.dir("test-native"))
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("libusb.hardware.test", providers.gradleProperty("hardwareTest").getOrElse("false"))
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
