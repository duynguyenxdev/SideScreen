package com.sidescreen.host.platform

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun monotonicNanos(): Long = System.nanoTime()
