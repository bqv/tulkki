package uk.xa0.tulkki.translation

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

/**
 * The three instructions Tulkki sends, and the identity of every answer they bought.
 *
 * <p>The owner can rewrite any of them in the settings - "even if they default to what you set" - so
 * this is the one place that answers "which instruction is in force": the owner's wording when they
 * have written one, and otherwise the wording this build ships, byte for byte, so an install that
 * never opens that screen sends exactly what it sent before the settings existed.
 *
 * <p><strong>The trap this class exists for.</strong> A cached answer is only as good as the prompt
 * that asked for it, so no instruction may be served an answer bought under a different one. The
 * version is the prompt itself, folded into the cache namespace as a short fingerprint of its wording
 * ([identity]), and the rule is one line: <strong>the wording in force is part of the key</strong>.
 * An edit and a new shipped wording are the same event to a cache, so both move the key - which is
 * what the hand-bumped versions used to do for two of the three prompts, and never did at all for
 * translate, whose answers were keyed by text and target language alone. Nothing is deleted, nothing
 * is migrated, and nobody has to remember to bump anything when the build's own wording changes: the
 * answers bought under the old text are simply not where the new question looks.
 *
 * <p>The one exception is the wordings that shipped <em>before</em> the fingerprint entered the key
 * ([PRE_FINGERPRINT_IDENTITIES]), together with the two cache namespaces `GlossKey` and
 * `ReviewKey` already carried by hand. Every answer already in the table was bought under those,
 * so they keep the keys they have always had - no namespace at all for translate, and the hand-versioned
 * namespaces for the other two - and an upgrade that changes nobody's wording finds everything where it
 * left it. That list is <strong>frozen history</strong>: it is not a list of the current defaults, it
 * must never be regenerated from them, and dropping an entry would orphan exactly the rows it exists to
 * keep. `PromptBookTest` pins the list and the wordings in force, so changing what the app sends
 * is a visible decision in the diff rather than a silent one at run time.
 *
 * <p>Pure Kotlin, and it reads the settings through the process-wide instance rather than a
 * `Context`, because the three callers that need a cache key - the queue, the send path's
 * suggestion cache, the reading aid - have no settings object and no `Context` at hand. An
 * untouched install, and a process that has never read the settings, get the shipped wording.
 */
object PromptBook {

    /** Which instruction is being asked about. Named for the call, never for a direction. */
    enum class Kind {
        /** The receive path, the composer's suggestion and the English row. */
        TRANSLATE,
        /** The outbound call, which brings the notes on the owner's own wording back with it. */
        REVIEW,
        /** The reading aid's one-word lookup. */
        GLOSS
    }

    /**
     * How much of the wording's fingerprint goes into a namespace: 16 hex characters, 64 bits. Long
     * enough that two prompts colliding onto one paid answer is not a thing that happens, short
     * enough to read in a database column.
     */
    private const val IDENTITY_LENGTH = 16

    /** The namespace an edited translate prompt buys under. The shipped wording has none. */
    private const val TRANSLATE_NAMESPACE = "translate"

    /**
     * The namespace a re-ask buys under, beside the plain one.
     *
     * <p>A re-ask is not the question the request it follows asked, so it must not share that
     * question's cache identity: [DeepSeekClient.RE_ASK_CLAUSE] changes what is asked - a
     * literal, short rendering instead of an ordinary one - and an answer bought under the owner's
     * template alone does not answer it. The clause is folded in <em>here</em>, at the one site that
     * builds an item's key, rather than carried as a durable flag, because a flag would be a column
     * in `:data`. Nothing is evicted and nothing is migrated: the refused attempt's rows stay in the
     * table under their own keys, unfound, exactly as an edited prompt's rows do.
     *
     * <p>It carries the clause contract's version the way [GlossKey] and [ReviewKey]
     * carry theirs, because the clause is app-owned text this build ships: changing what it asks is a
     * change to what the key identifies.
     */
    private const val TRANSLATE_RE_ASK_NAMESPACE = "translate-reask-v1"

    /**
     * The wordings that shipped before the fingerprint entered the cache key, as their identities.
     *
     * <p>They are here because the cache rows bought under them are keyed without a fingerprint, so
     * these three strings are the difference between an upgrade finding its answers and losing every
     * one of them. They are a record of what this app used to send, not a copy of what it sends now:
     * the translate and gloss wordings below happen to still be on the list and the review wording no
     * longer is (it changed when the notes stopped punishing puhekieli), and that is the list working,
     * not drifting. `PromptBookTest` pins it so that an accidental edit to it - or a well-meant
     * regeneration from the current defaults - fails rather than quietly re-buying months of answers.
     */
    private val PRE_FINGERPRINT_IDENTITIES: Set<String> =
            setOf(
                    "6a81d6504554e7c8", // translate
                    "2130a33339d44e7c", // review, before the colloquial-wording rule
                    "03b9bfbd2207577a") // gloss

    /** The frozen list, for the test that keeps it frozen. */
    @JvmStatic fun preFingerprintIdentities(): Set<String> = PRE_FINGERPRINT_IDENTITIES

    /** The wording this build ships for `kind`. */
    @JvmStatic
    fun shipped(kind: Kind): String =
            when (kind) {
                Kind.REVIEW -> DeepSeekClient.DEFAULT_REVIEW_PROMPT
                Kind.GLOSS -> DeepSeekClient.DEFAULT_GLOSS_PROMPT
                Kind.TRANSLATE -> DeepSeekClient.DEFAULT_SYSTEM_PROMPT
            }

    /**
     * The instruction in force. An all-blank setting is the shipped wording: clearing the field is
     * how the owner puts it back, and nothing in the app has to offer a "reset" that could drift
     * from the default it claims to restore.
     */
    @JvmStatic
    fun template(kind: Kind): String {
        val stored = stored(kind)
        return if (stored == null || stored.javaTrim().isEmpty()) shipped(kind) else stored
    }

    /** Whether the owner has replaced the shipped wording. */
    @JvmStatic fun edited(kind: Kind): Boolean = template(kind) != shipped(kind)

    /**
     * A short fingerprint of the wording in force, for a cache namespace. Two different prompts
     * differ here, and the same prompt always gives the same reading - which is all a cache identity
     * needs.
     */
    @JvmStatic fun identity(kind: Kind): String = fingerprint(template(kind))

    /**
     * What a namespace gains from the instruction in force: nothing at all for a wording the cache
     * already holds rows under, and `@<fingerprint>` for every other wording - an edit the owner
     * made and a wording this build started shipping alike.
     */
    @JvmStatic
    fun suffix(kind: Kind): String {
        val identity = identity(kind)
        return if (PRE_FINGERPRINT_IDENTITIES.contains(identity)) "" else "@" + identity
    }

    /**
     * The cache identity of one translation, under the instruction in force.
     *
     * <p>The shipped key - text and target language, exactly the shape translations have always been
     * cached under - while the wording is one the table already holds rows for, and a namespaced key
     * for every other wording. That asymmetry is the whole point: the answers already bought stay
     * findable, and no question asked under different words can be handed one of them.
     */
    @JvmStatic
    fun translationKey(text: String?, targetLanguage: String?): String =
            translationKey(text, targetLanguage, false)

    /**
     * The same identity for a re-ask, which is a different question and so a different key.
     *
     * <p><strong>The clause is in the key; the batching envelope is not.</strong> They are the same
     * mechanism - app-owned text appended after the rendered template - on opposite sides of this
     * identity. [DeepSeekClient.BATCH_CLAUSE] describes how a <em>request carrying several
     * translations</em> is shaped, so its own KDoc says it is deliberately outside the key.
     * [DeepSeekClient.RE_ASK_CLAUSE] changes what is
     * <em>asked</em> - a literal, short rendering instead of an ordinary one - so it is deliberately
     * inside it: without that, the owner's tap on a refused message could be handed the refused
     * answer back, which is the dead end docs/MIGRATION.md item 17 exists to remove. Do not make the two
     * consistent by moving this clause out of the key.
     *
     * <p>The fingerprint of the wording in force is folded in on both paths, so an edited prompt and
     * a re-ask are two independent facts about one key.
     */
    @JvmStatic
    fun translationKey(text: String?, targetLanguage: String?, reAsk: Boolean): String {
        val suffix = suffix(Kind.TRANSLATE)
        if (reAsk) {
            return CacheKey.of(TRANSLATE_RE_ASK_NAMESPACE + suffix, text, targetLanguage)
        }
        return if (suffix.isEmpty()) CacheKey.of(text, targetLanguage)
        else CacheKey.of(TRANSLATE_NAMESPACE + suffix, text, targetLanguage)
    }

    /**
     * Whether `cacheKey` is this text's re-ask identity under the instruction in force - the
     * durable half of "this row is a re-ask", read back at the buy. The flag itself is not stored,
     * because that would be a column in `:data`; the key is what carries it.
     *
     * <p>Worked out from the instruction in force, exactly as [translationKey] is: a row that
     * was queued under one wording and is bought under another is asking the new question, and the
     * re-ask is answered for the wording it is actually sent with.
     */
    @JvmStatic
    fun isReAsk(cacheKey: String?, text: String?, targetLanguage: String?): Boolean =
            cacheKey != null && cacheKey == translationKey(text, targetLanguage, true)

    private fun stored(kind: Kind): String? {
        val settings = TranslationSettings.current()
        if (settings == null) {
            return null
        }
        return when (kind) {
            Kind.REVIEW -> settings.reviewPrompt()
            Kind.GLOSS -> settings.glossPrompt()
            Kind.TRANSLATE -> settings.translatePrompt()
        }
    }

    private fun fingerprint(wording: String?): String {
        val digest =
                try {
                    MessageDigest.getInstance("SHA-256")
                } catch (e: NoSuchAlgorithmException) {
                    // Required of every Java platform; losing it is a broken runtime.
                    throw IllegalStateException("SHA-256 is not available", e)
                }
        val bytes = digest.digest((wording ?: "").toByteArray(StandardCharsets.UTF_8))
        val builder = StringBuilder(IDENTITY_LENGTH)
        for (i in 0 until IDENTITY_LENGTH / 2) {
            val value = bytes[i].toInt() and 0xff
            builder.append(Character.forDigit(value shr 4, 16))
            builder.append(Character.forDigit(value and 0xf, 16))
        }
        return builder.toString()
    }
}

/**
 * `String.trim()` with Java's own definition: everything at or below U+0020.
 *
 * <p>The setting this reads is the owner's own prompt text, and a prompt that is only whitespace
 * must read as blank exactly as the Java's `trim()` made it. Kotlin's `trim()` strips
 * `Char.isWhitespace()`, a different set, so the Java call is kept.
 */
private fun String.javaTrim(): String = trim { it <= ' ' }
