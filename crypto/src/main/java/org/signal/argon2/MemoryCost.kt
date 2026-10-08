package org.signal.argon2

/**
 * For use in the [Argon2.Builder.memoryCost] method for readability.
 */
class MemoryCost private constructor(private val kib: Int) {

    /** Number of bytes */
    fun toBytes(): Long = kib * 1024L

    /** Number of Kibibytes */
    fun getKiB(): Int = kib

    companion object {
        @JvmStatic
        fun Bytes(bytes: Long): MemoryCost = MemoryCost((bytes / 1024).toInt())

        @JvmStatic
        fun KiB(kib: Int): MemoryCost = MemoryCost(kib)

        @JvmStatic
        fun MiB(mib: Int): MemoryCost = MemoryCost(mib * 1024)
    }
}
