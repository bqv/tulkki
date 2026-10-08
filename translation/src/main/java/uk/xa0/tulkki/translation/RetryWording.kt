package uk.xa0.tulkki.translation

/**
 * The wording of the held send's retry action - the button that asks DeepSeek again - and the one
 * rule the owner's edit obeys: a blank field is the shipped wording.
 *
 * <p><strong>This is UI copy, not a prompt.</strong> It is the same setting shape as the three
 * DeepSeek instructions - the owner may rewrite it, and an all-blank field means "use what the build
 * ships", with no reset row to drift from the default it claims to restore - but it is deliberately
 * <em>not</em> one of them: nothing here is sent to anyone, so it must not enter the prompt cache
 * identity. [PromptBook] does not read it, which is why an edit to it moves no cache key and
 * re-buys nothing. Whatever a reader is tempted to fold in, that is the one thing this class exists
 * to keep out.
 *
 * <p>The shipped wording is the byte-for-byte text the button has always carried, so an install that
 * never opens the settings screen draws exactly what it drew before the setting existed.
 *
 * <p>Pure Kotlin, no Android types, so the default and the blank rule are exercised by JVM unit
 * tests.
 */
object RetryWording {

    /** The wording the build ships, byte for byte. */
    const val SHIPPED = "Translate again"

    /**
     * The wording in force. An all-blank stored value is the shipped wording: clearing the field is
     * how the owner puts it back, exactly as [PromptBook.template] reads its three prompts.
     *
     * <p>`trim()` is Java's own (`<= ' '`), as in the Java this replaces: Kotlin's removes Unicode
     * whitespace instead, and a field holding only a non-breaking space is blank to one and not the
     * other.
     */
    @JvmStatic
    fun inForce(stored: String?): String =
            if (stored == null || stored.javaTrim().isEmpty()) SHIPPED else stored

    /** Whether the owner has replaced the shipped wording. */
    @JvmStatic
    fun edited(stored: String?): Boolean = inForce(stored) != SHIPPED
}

/**
 * `String.trim()` with Java's own definition: everything at or below U+0020.
 */
private fun String.javaTrim(): String = trim { it <= ' ' }
