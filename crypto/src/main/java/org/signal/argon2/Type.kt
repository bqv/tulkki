package org.signal.argon2

/**
 * Argon2 primitive type.
 */
enum class Type(@JvmField val nativeValue: Int) {
    Argon2d(0),
    Argon2i(1),
    Argon2id(2);

    companion object {
        @JvmStatic
        @Throws(UnknownTypeException::class)
        fun fromEncoded(encoded: String?): Type {
            if (encoded == null) throw IllegalArgumentException()

            if (encoded.startsWith("\$argon2id\$")) return Argon2id
            if (encoded.startsWith("\$argon2i\$")) return Argon2i
            if (encoded.startsWith("\$argon2d\$")) return Argon2d

            throw UnknownTypeException()
        }
    }
}
