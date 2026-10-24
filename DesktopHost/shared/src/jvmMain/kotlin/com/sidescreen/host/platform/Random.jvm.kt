package com.sidescreen.host.platform

import java.security.SecureRandom

private val secureRandom = SecureRandom()

actual fun secureRandomBytes(count: Int): ByteArray = ByteArray(count).also { secureRandom.nextBytes(it) }
