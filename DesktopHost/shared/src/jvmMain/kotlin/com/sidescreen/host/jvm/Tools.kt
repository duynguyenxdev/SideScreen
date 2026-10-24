package com.sidescreen.host.jvm

import java.io.File
import java.util.concurrent.TimeUnit

enum class Os { MAC, WINDOWS, LINUX }

val currentOs: Os by lazy {
    val name = System.getProperty("os.name").lowercase()
    when {
        name.startsWith("mac") || name.startsWith("darwin") -> Os.MAC
        name.startsWith("windows") -> Os.WINDOWS
        else -> Os.LINUX
    }
}

/** Locates external tools (ffmpeg, adb) the host drives. */
object Tools {
    private val home = System.getProperty("user.home")

    fun find(
        name: String,
        override: String = "",
        extraDirs: List<String> = emptyList(),
    ): String? {
        if (override.isNotBlank()) return override.takeIf { File(it).canExecute() }
        val exe = if (currentOs == Os.WINDOWS) "$name.exe" else name
        // Next to the app (bundled tools), then PATH, then common install locations.
        // Linux prefers the distro's build: it matches the system's X11 and VA-API libraries.
        val bundled = System.getProperty("sidescreen.toolsDir")?.let { File(it, exe) }
        if (currentOs == Os.LINUX && bundled != null) {
            findInstalled(exe, extraDirs)?.let { return it }
        }
        if (bundled != null && bundled.isFile) {
            if (bundled.canExecute()) return bundled.absolutePath
            runnableCopy(bundled)?.let { return it }
        }
        return findInstalled(exe, extraDirs)
    }

    private fun findInstalled(
        exe: String,
        extraDirs: List<String>,
    ): String? {
        val path = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        val common =
            when (currentOs) {
                Os.MAC -> listOf("/opt/homebrew/bin", "/usr/local/bin", "/opt/local/bin")
                Os.WINDOWS ->
                    listOfNotNull(
                        System.getenv("ProgramFiles")?.let { "$it\\ffmpeg\\bin" },
                        System.getenv("LOCALAPPDATA")?.let { "$it\\Microsoft\\WinGet\\Links" },
                        "$home\\scoop\\shims",
                        "C:\\ProgramData\\chocolatey\\bin",
                        "C:\\ffmpeg\\bin",
                    )
                Os.LINUX -> listOf("/usr/bin", "/usr/local/bin", "/snap/bin")
            }
        return (path + common + extraDirs).asSequence()
            .map { File(it, exe) }
            .firstOrNull { it.isFile && it.canExecute() }
            ?.absolutePath
    }

    /**
     * Packaging can drop the executable bit from bundled resources, and the install
     * dir may be read-only, so run a copy from the config dir instead.
     */
    private fun runnableCopy(bundled: File): String? =
        synchronized(this) {
            runCatching {
                val copy = File(File(configDirectory(), "tools"), bundled.name)
                if (!copy.isFile || copy.length() != bundled.length() || copy.lastModified() < bundled.lastModified()) {
                    copy.parentFile.mkdirs()
                    bundled.copyTo(copy, overwrite = true)
                }
                copy.setExecutable(true)
                copy.absolutePath.takeIf { copy.canExecute() }
            }.getOrNull()
        }

    fun ffmpeg(override: String = ""): String? = find("ffmpeg", override)

    fun adb(override: String = ""): String? {
        val sdkDirs =
            listOfNotNull(
                System.getenv("ANDROID_HOME"),
                System.getenv("ANDROID_SDK_ROOT"),
                when (currentOs) {
                    Os.MAC -> "$home/Library/Android/sdk"
                    Os.WINDOWS -> System.getenv("LOCALAPPDATA")?.let { "$it\\Android\\Sdk" }
                    Os.LINUX -> "$home/Android/Sdk"
                },
            ).map { File(it, "platform-tools").path }
        return find("adb", override, sdkDirs)
    }

    class Result(val exitCode: Int, val output: String)

    /** Runs a short command, returning null if it could not start or timed out. */
    fun run(
        command: List<String>,
        timeoutSeconds: Long = 10,
    ): Result? =
        try {
            val p = ProcessBuilder(command).redirectErrorStream(true).start()
            p.outputStream.close()
            val out = StringBuilder()
            val reader = Thread { out.append(p.inputStream.bufferedReader().readText()) }.apply { isDaemon = true; start() }
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                null
            } else {
                reader.join(1000)
                Result(p.exitValue(), out.toString())
            }
        } catch (_: Exception) {
            null
        }
}
