@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.sidescreen.vdisplay

import cgvirtualdisplay.CGVirtualDisplay
import cgvirtualdisplay.CGVirtualDisplayDescriptor
import cgvirtualdisplay.CGVirtualDisplayMode
import cgvirtualdisplay.CGVirtualDisplaySettings
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import platform.CoreFoundation.CFRunLoopRunInMode
import platform.CoreFoundation.kCFRunLoopDefaultMode
import platform.CoreGraphics.CGBeginDisplayConfiguration
import platform.CoreGraphics.CGCancelDisplayConfiguration
import platform.CoreGraphics.CGCompleteDisplayConfiguration
import platform.CoreGraphics.CGConfigureDisplayOrigin
import platform.CoreGraphics.CGDisplayBounds
import platform.CoreGraphics.CGDisplayConfigRefVar
import platform.CoreGraphics.CGDisplayVendorNumber
import platform.CoreGraphics.CGGetOnlineDisplayList
import platform.CoreGraphics.CGMainDisplayID
import platform.CoreGraphics.CGSizeMake
import platform.CoreGraphics.kCGConfigureForSession
import platform.CoreGraphics.kCGErrorSuccess
import platform.posix.POLLIN
import platform.posix.fflush
import platform.posix.fprintf
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.read
import platform.posix.stderr
import platform.posix.stdout
import kotlin.system.exitProcess

/*
 * Creates a CGVirtualDisplay and keeps it alive until stdin closes.
 *
 *   sidescreen-vdisplay --width 1920 --height 1200 --refresh 60 [--hidpi] [--name SideScreen]
 *
 * Prints "READY <displayID>" once the display exists, or "ERROR <reason>" and
 * exits 1. The JVM host owns the process: closing our stdin, or the host dying,
 * ends it and macOS removes the display with it.
 */

private const val VENDOR_ID = 0xEEEEu

private fun fail(message: String): Nothing {
    println("ERROR $message")
    fflush(stdout)
    exitProcess(1)
}

private fun log(message: String) {
    fprintf(stderr, "vdisplay: %s\n", message)
}

fun main(args: Array<String>) {
    var width = 1920
    var height = 1200
    var refresh = 60
    var hiDPI = false
    var name = "SideScreen"
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--width" -> width = args.getOrNull(++i)?.toIntOrNull() ?: fail("bad --width")
            "--height" -> height = args.getOrNull(++i)?.toIntOrNull() ?: fail("bad --height")
            "--refresh" -> refresh = args.getOrNull(++i)?.toIntOrNull() ?: fail("bad --refresh")
            "--name" -> name = args.getOrNull(++i) ?: fail("bad --name")
            "--hidpi" -> hiDPI = true
            else -> fail("unknown argument ${args[i]}")
        }
        i++
    }
    if (width !in 640..7680 || height !in 480..4320 || refresh !in 1..240) fail("unsupported mode ${width}x$height@$refresh")

    val display = createDisplay(width, height, refresh, hiDPI, name) ?: fail("CGVirtualDisplay could not be created")
    val displayId = display.displayID
    println("READY $displayId")
    fflush(stdout)
    log("created display $displayId ${width}x$height@$refresh${if (hiDPI) " HiDPI" else ""}")

    // Keep the display (and run loop) alive until the host closes our stdin.
    var lastCheck = 0
    while (stdinOpen()) {
        CFRunLoopRunInMode(kCFRunLoopDefaultMode, 0.25, false)
        if (++lastCheck >= 4) {
            lastCheck = 0
            ensurePhysicalDisplayStaysMain(displayId)
        }
    }
    log("stdin closed; removing display $displayId")
    // Keep a reference until here so the display is not released early.
    display.hashCode()
    exitProcess(0)
}

private fun createDisplay(
    width: Int,
    height: Int,
    refresh: Int,
    hiDPI: Boolean,
    name: String,
): CGVirtualDisplay? {
    // Physical pixels are 2x logical with HiDPI (same as the Swift host).
    val physW = if (hiDPI) width * 2 else width
    val physH = if (hiDPI) height * 2 else height
    val ppi = if (hiDPI) 220.0 else 110.0

    val descriptor = CGVirtualDisplayDescriptor()
    descriptor.name = name
    descriptor.maxPixelsWide = physW.toUInt()
    descriptor.maxPixelsHigh = physH.toUInt()
    descriptor.sizeInMillimeters = CGSizeMake(physW * 25.4 / ppi, physH * 25.4 / ppi)
    // width * 10000 + height keeps portrait and landscape modes distinct.
    descriptor.productID = (physW * 10000 + physH).toUInt()
    descriptor.vendorID = VENDOR_ID
    descriptor.serialNum = 1u

    val settings = CGVirtualDisplaySettings()
    settings.hiDPI = if (hiDPI) 1u else 0u
    val modes = mutableListOf<CGVirtualDisplayMode>()
    // HiDPI: the physical "anchor" mode tells macOS the display is high density.
    if (hiDPI) modes += CGVirtualDisplayMode(physW.toUInt(), physH.toUInt(), refresh.toDouble())
    modes += CGVirtualDisplayMode(width.toUInt(), height.toUInt(), refresh.toDouble())
    settings.modes = modes

    val display = CGVirtualDisplay(descriptor)
    if (!display.applySettings(settings)) return null
    return display
}

private fun stdinOpen(): Boolean =
    memScoped {
        val fds = alloc<pollfd>()
        fds.fd = 0
        fds.events = POLLIN.toShort()
        if (poll(fds.ptr, 1u, 0) <= 0) return true
        val buf = allocArray<kotlinx.cinterop.ByteVar>(256)
        read(0, buf, 256u) > 0
    }

/**
 * Issue #39 safety net: with a physical display online, the main slot (menu
 * bar, Dock, keyboard focus) must never belong to the virtual display.
 */
private fun ensurePhysicalDisplayStaysMain(displayId: UInt) {
    if (CGMainDisplayID() != displayId) return
    memScoped {
        val ids = allocArray<UIntVar>(16)
        val count = alloc<UIntVar>()
        if (CGGetOnlineDisplayList(16u, ids, count.ptr) != kCGErrorSuccess) return
        val physical =
            (0 until count.value.toInt()).map { ids[it] }
                .firstOrNull { it != displayId && CGDisplayVendorNumber(it) != VENDOR_ID } ?: return
        val config = allocPointerTo<cnames.structs._CGDisplayConfigRef>()
        if (CGBeginDisplayConfiguration(config.ptr) != kCGErrorSuccess) return
        val physicalWidth = CGDisplayBounds(physical).useContents { size.width.toInt() }
        if (CGConfigureDisplayOrigin(config.value, physical, 0, 0) != kCGErrorSuccess ||
            CGConfigureDisplayOrigin(config.value, displayId, physicalWidth, 0) != kCGErrorSuccess
        ) {
            CGCancelDisplayConfiguration(config.value)
            return
        }
        if (CGCompleteDisplayConfiguration(config.value, kCGConfigureForSession) == kCGErrorSuccess) {
            log("physical display restored as main; virtual display parked beside it")
        }
    }
}
