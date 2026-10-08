package org.signal.argon2

import java.util.Arrays
import java.util.Locale

class Argon2 private constructor(builder: Builder) {

    private val tCostIterations: Int = builder.tCostIterations
    private val mCostKiB: Int = builder.mCostKiB
    private val parallelism: Int = builder.parallelism
    private val hashLength: Int = builder.hashLength
    private val hashRaw: Boolean = builder.hashRaw
    private val type: Type = builder.type
    private val version: Version = builder.version

    companion object {
        /**
         * Finds the type from the encoded hash.
         * @param encoded
         * @param password
         * @return
         * @throws UnknownTypeException If it cannot determine the type from the encoded hash.
         */
        @JvmStatic
        @Throws(UnknownTypeException::class)
        fun verify(encoded: String?, password: ByteArray?): Boolean =
            verify(encoded, password, Type.fromEncoded(encoded))

        @JvmStatic
        fun verify(encoded: String?, password: ByteArray?, type: Type): Boolean {
            if (encoded == null) throw IllegalArgumentException()
            if (password == null) throw IllegalArgumentException()

            val defensivePasswordCopy = password.clone()

            val result = Argon2Native.verify(encoded, defensivePasswordCopy, type.nativeValue)

            Arrays.fill(defensivePasswordCopy, 0.toByte())

            return result == Argon2Native.OK
        }
    }

    class Builder(internal val version: Version) {
        internal var tCostIterations: Int = 3
        internal var mCostKiB: Int = 1 shl 12
        internal var parallelism: Int = 1
        internal var hashLength: Int = 32
        internal var hashRaw: Boolean = false
        internal var type: Type = Type.Argon2i

        /**
         * Type of Argon to use [Type.Argon2i] is the default.
         */
        fun type(type: Type): Builder {
            this.type = type
            return this
        }

        /**
         * Sets parallelism to [n] threads (default 1)
         */
        fun parallelism(n: Int): Builder {
            this.parallelism = n
            return this
        }

        /**
         * Sets the memory usage of 2^[n] KiB (default 12)
         *
         * @param n This function accepts [0..30]. 0 is 1 KiB and 30 is 1 TiB.
         */
        fun memoryCostOrder(n: Int): Builder {
            if (n < 0) throw IllegalArgumentException("n too small, minimum 0")
            if (n > 30) throw IllegalArgumentException("n too high, maximum 30")
            return memoryCostKiB(1 shl n)
        }

        /**
         * Sets the memory usage of [kib] KiB.
         */
        fun memoryCostKiB(kib: Int): Builder {
            if (kib < 8) throw IllegalArgumentException("kib too small, minimum 8")
            this.mCostKiB = kib
            return this
        }

        /**
         * Sets the memory usage using the [MemoryCost] enum.
         */
        fun memoryCost(memoryCost: MemoryCost): Builder = memoryCostKiB(memoryCost.getKiB())

        /**
         * Sets the number of iterations to [n] (default = 3)
         */
        fun iterations(n: Int): Builder {
            this.tCostIterations = n
            return this
        }

        /**
         * Output hash length, default 32.
         */
        fun hashLength(hashLength: Int): Builder {
            this.hashLength = hashLength
            return this
        }

        /**
         * Generate binary-only hash, default false.
         */
        fun hashRaw(hashRaw: Boolean): Builder {
            this.hashRaw = hashRaw
            return this
        }

        fun build(): Argon2 {
            if (mCostKiB < (8 * parallelism))
                throw IllegalArgumentException("memory cost too small for given value of parallelism")
            return Argon2(this)
        }
    }

    @Throws(Argon2Exception::class)
    fun hash(password: ByteArray?, salt: ByteArray?): Result {
        if (salt == null) throw IllegalArgumentException()
        if (password == null) throw IllegalArgumentException()

        val encoded: StringBuffer? = if (hashRaw) null else StringBuffer()

        val hash = ByteArray(hashLength)
        val passwordCopy = password.clone()

        val result = Argon2Native.hash(
            tCostIterations,
            mCostKiB,
            parallelism,
            passwordCopy,
            salt,
            hash,
            encoded,
            type.nativeValue,
            version.nativeValue
        )

        Arrays.fill(passwordCopy, 0.toByte())

        if (result != Argon2Native.OK) {
            throw Argon2Exception(result, Argon2Native.resultToString(result))
        }

        return Result(if (hashRaw) null else encoded?.toString(), hash)
    }

    inner class Result internal constructor(
        val encoded: String?,
        val hash: ByteArray
    ) {

        fun getHashHex(): String = toHex(hash)

        override fun toString(): String = String.format(
            Locale.US,
            "Type:           %s%n" +
                "Iterations:     %d%n" +
                "Memory:         %d KiB%n" +
                "Parallelism:    %d%n" +
                "Hash:           %s%n" +
                "Encoded:        %s%n",
            type,
            tCostIterations,
            mCostKiB,
            parallelism,
            getHashHex(),
            encoded
        )
    }

    private fun toHex(hash: ByteArray): String {
        val stringBuilder = StringBuilder(hash.size * 2)
        for (b in hash) {
            stringBuilder.append(String.format(Locale.US, "%02x", b))
        }
        return stringBuilder.toString()
    }
}
