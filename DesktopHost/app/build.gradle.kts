import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.zxing.core)
}

// One version for every artifact: CI passes -Psidescreen.version, local builds read VERSION.
val appVersion: String =
    (findProperty("sidescreen.version") as String?)
        ?: rootProject.file("../VERSION").readText().trim()

// macOS bundle versions must start at 1, so 0.x.y ships as 1.x.y in Info.plist only.
val macBundleVersion = appVersion.split(".").let { (major, minor, patch) -> "${maxOf(1, major.toInt())}.$minor.$patch" }

val isMac = System.getProperty("os.name").startsWith("Mac")

/**
 * Per-OS files shipped next to the app (compose.application.resources.dir):
 * the CGVirtualDisplay helper on macOS, and ffmpeg when -Psidescreen.bundleTools
 * points at a folder holding it (CI does this for Windows and Linux).
 */
val appResources = layout.buildDirectory.dir("appResources")
val stageAppResources by tasks.registering(Sync::class) {
    into(appResources)
    from("icons/icon.png") { into("common") }
    if (isMac) {
        val helper = project(":vdisplay-macos").tasks.named("universalBinary")
        from(helper) { into("macos") }
    }
    (findProperty("sidescreen.bundleTools") as String?)?.let { dir ->
        val os =
            when {
                isMac -> "macos"
                System.getProperty("os.name").startsWith("Windows") -> "windows"
                else -> "linux"
            }
        from(dir) { into(os) }
    }
}

compose.desktop {
    application {
        mainClass = "com.sidescreen.desktop.MainKt"
        jvmArgs += listOf("-Dsidescreen.version=$appVersion", "-Xss2m")

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "SideScreen"
            packageVersion = appVersion
            description = "Use an Android tablet as a second screen"
            vendor = "Side Screen"
            copyright = "© 2025 Side Screen. MIT License."
            licenseFile.set(rootProject.file("../LICENSE"))
            modules("jdk.unsupported", "java.naming")
            appResourcesRootDir.set(appResources)

            macOS {
                bundleID = "com.sidescreen.desktop"
                packageVersion = macBundleVersion
                dmgPackageVersion = macBundleVersion
                iconFile.set(project.file("icons/icon.icns"))
                infoPlist {
                    extraKeysRawXml =
                        """
                        <key>NSScreenCaptureUsageDescription</key>
                        <string>Side Screen captures the display you stream to your tablet.</string>
                        <key>NSLocalNetworkUsageDescription</key>
                        <string>Side Screen accepts connections from your tablet over Wi-Fi in wireless mode.</string>
                        """.trimIndent()
                }
            }
            windows {
                iconFile.set(project.file("icons/icon.ico"))
                menuGroup = "Side Screen"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                // Stable across versions so MSI upgrades replace the old install.
                upgradeUuid = "6f1d3b8a-6a43-4c5e-9d3e-2f7b0c1a9e51"
            }
            linux {
                iconFile.set(project.file("icons/icon.png"))
                packageName = "sidescreen"
                debMaintainer = "sidescreen@users.noreply.github.com"
                menuGroup = "Utility"
                appCategory = "Utility"
                shortcut = true
            }
        }

        buildTypes.release.proguard {
            isEnabled.set(false)
        }
    }
}

// Compose's own resource preparation (used by run and packaging) copies from appResourcesRootDir.
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(stageAppResources) }
