package uk.xa0.tulkki.translation

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Locale

/**
 * The identity of a bought translation.
 *
 * <p>The cache is what keeps a retry from being a second purchase: the same text going into the same
 * target language is the same answer, so it must have the same key no matter when, or for which
 * message, it is asked for. Hashing keeps the key a fixed size for arbitrarily long bodies, and
 * SHA-256 is enough that two different messages are not going to collide onto one paid answer.
 *
 * <p>Pure Kotlin, no Android types, so it is exercised by JVM unit tests.
 */
object CacheKey {

    /**
     * A hex SHA-256 over the target language and the text. The language is part of the key because
     * "Hello" translated into Finnish and into German are two different answers.
     *
     * <p>Its byte layout is fixed: translations cached by an older build have to stay findable, so
     * the namespaced overload below hashes a *different* shape rather than extending this one.
     *
     * @param text the original body; may be `null`
     * @param targetLanguage an ISO 639-1 code; may be `null`
     * @return 64 lowercase hex characters
     */
    @JvmStatic
    fun of(text: String?, targetLanguage: String?): String {
        val language = if (targetLanguage == null) "" else targetLanguage.javaLowerCase()
        val body = text ?: ""
        val digest = sha256()
        // A separator that cannot occur in a language code keeps the two fields unambiguous.
        digest.update(language.toByteArray(StandardCharsets.UTF_8))
        digest.update(0.toByte())
        digest.update(body.toByteArray(StandardCharsets.UTF_8))
        return toHex(digest.digest())
    }

    /**
     * A key in a namespace, for a second kind of answer sharing this table.
     *
     * <p>A word's gloss and a message's translation live in the same cache, and the namespace is what
     * keeps them apart: it is hashed as its own field, so `of("gloss", "talossa", "en")` cannot
     * equal `of("talossa", "en")` even though both are built from the same three strings. That is a
     * guarantee, not a likelihood - two answers costing the same key would mean one of them is
     * silently the wrong answer.
     *
     * @param namespace which cache the answer belongs to; may be `null` for none
     * @param text the text asked about; may be `null`
     * @param targetLanguage the language the answer is for; may be `null`
     */
    @JvmStatic
    fun of(namespace: String?, text: String?, targetLanguage: String?): String {
        val space = namespace ?: ""
        val language = if (targetLanguage == null) "" else targetLanguage.javaLowerCase()
        val body = text ?: ""
        val digest = sha256()
        digest.update(space.toByteArray(StandardCharsets.UTF_8))
        digest.update(0.toByte())
        digest.update(language.toByteArray(StandardCharsets.UTF_8))
        digest.update(0.toByte())
        digest.update(body.toByteArray(StandardCharsets.UTF_8))
        return toHex(digest.digest())
    }

    private fun sha256(): MessageDigest =
            try {
                MessageDigest.getInstance("SHA-256")
            } catch (e: NoSuchAlgorithmException) {
                // SHA-256 is required of every Java platform; losing it is a broken runtime.
                throw IllegalStateException("SHA-256 is not available", e)
            }

    private fun toHex(bytes: ByteArray): String {
        val builder = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            builder.append(Character.forDigit((b.toInt() shr 4) and 0xf, 16))
            builder.append(Character.forDigit(b.toInt() and 0xf, 16))
        }
        return builder.toString()
    }
}

/**
 * `String.toLowerCase()` with Java's own definition: the **default locale's** rules.
 *
 * <p>Kotlin's no-argument `lowercase()` folds with `Locale.ROOT` instead, and the two disagree in
 * exactly the locales a language code can meet: in Turkish `"FI"` is `"fı"` under Java's rule and
 * `"fi"` under Kotlin's. This is a cache identity, so the difference is two keys for one language
 * rather than a cosmetic one, and the Java this replaces used `String.toLowerCase()`, so this does
 * too. Named after the Java method rather than left to the reader's assumption about which
 * `lowercase` ran.
 */
private fun String.javaLowerCase(): String = lowercase(Locale.getDefault())
