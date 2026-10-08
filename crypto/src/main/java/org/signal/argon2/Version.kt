package org.signal.argon2

/**
 * Version of the Argon2 algorithm.
 */
enum class Version(@JvmField val nativeValue: Int) {
    V10(0x10),
    V13(0x13),
    LATEST(0x13)
}
