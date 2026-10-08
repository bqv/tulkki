package uk.xa0.tulkki.xmpp.utils

import java.security.SecureRandom

/**
 * The one `SecureRandom` the island shares.
 *
 * Ported from Java by the `px` lane. Decisions taken rather than inherited:
 *
 * 1. **The class stays a `class` with a `private constructor()`, not a Kotlin `object`.** Java's
 *    constructor was private and the type was never instantiable; Kotlin's `object` would add an
 *    `INSTANCE` field and make the class final in a way Java cannot see, which is a different
 *    class-file shape for no gain. The private constructor is the same statement Java made.
 * 2. **`SECURE_RANDOM` keeps a Java-visible static field**, so it is an `@JvmField val` in the
 *    companion: Java callers (`TLSSocketFactory` today, at `:23`) read `Random.SECURE_RANDOM`, and a
 *    companion `val` without `@JvmField` would answer only `getSECURE_RANDOM()`.
 */
class Random private constructor() {

    companion object {
        @JvmField val SECURE_RANDOM: SecureRandom = SecureRandom()
    }
}
