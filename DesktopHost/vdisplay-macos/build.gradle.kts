import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// Owns a CGVirtualDisplay for the JVM app (see MacDisplays.kt). Built for both
// Mac architectures and merged into one universal executable.
kotlin {
    val targets = listOf(macosArm64(), macosX64())
    targets.forEach { target: KotlinNativeTarget ->
        target.compilations.getByName("main").cinterops.create("CGVirtualDisplay") {
            includeDirs(file("src/nativeInterop/cinterop"))
        }
        target.binaries.executable {
            baseName = "sidescreen-vdisplay"
            entryPoint = "com.sidescreen.vdisplay.main"
        }
    }
}

val universalBinary by tasks.registering(Exec::class) {
    group = "build"
    description = "Merges the arm64 and x86_64 helpers into one universal binary."
    val arm = kotlin.macosArm64().binaries.getExecutable("RELEASE")
    val x64 = kotlin.macosX64().binaries.getExecutable("RELEASE")
    dependsOn(arm.linkTaskProvider, x64.linkTaskProvider)
    val output = layout.buildDirectory.file("universal/sidescreen-vdisplay")
    inputs.files(arm.outputFile, x64.outputFile)
    outputs.file(output)
    doFirst { output.get().asFile.parentFile.mkdirs() }
    commandLine("lipo", "-create", arm.outputFile.absolutePath, x64.outputFile.absolutePath, "-output", output.get().asFile.absolutePath)
}
