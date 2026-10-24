package com.sidescreen.host.platform

/** Cryptographically secure random bytes. */
expect fun secureRandomBytes(count: Int): ByteArray
