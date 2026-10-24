pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "SideScreenDesktop"

include(":shared")
include(":app")

// The CGVirtualDisplay helper is a Kotlin/Native macOS executable; it can only
// be built on a Mac, so other hosts skip it entirely.
if (System.getProperty("os.name").startsWith("Mac")) {
    include(":vdisplay-macos")
}
