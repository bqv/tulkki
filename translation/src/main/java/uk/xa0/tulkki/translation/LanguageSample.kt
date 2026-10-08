package uk.xa0.tulkki.translation

import java.util.ArrayList
import java.util.LinkedHashMap
import uk.xa0.tulkki.data.model.Message

/**
 * The rule that turns a bounded window of the server's archive into a language for a conversation.
 *
 * <p>A conversation learns its language from what the other person writes. A conversation that has
 * never received a locally stored message - brand new, freshly installed, or one whose history the
 * app has not caught up - therefore has no language at all: the chip is empty and an outgoing message
 * would be translated into a guess. The archive already holds what the peer wrote, so a small,
 * bounded piece of it is read once, locally, and dropped. It is never inserted into a conversation,
 * never stored, never shown and never sent anywhere; detection is [TextLanguage], which is
 * offline and free. This class is that rule and nothing else: no network, no database, no Android
 * types apart from compile-time `Message` constants, so every part of it is exercised by JVM
 * unit tests.
 *
 * <p>The bounds are part of the design rather than tuning:
 *
 * <ul>
 *   <li>[MAX_MESSAGES] is the hard cap on how much is ever fetched. It is smaller than
 *       upstream's own `Config.PAGE_SIZE` (50), so one RSM page covers it with room to spare and
 *       the sampler never has a reason to ask again; it is large enough that a strict majority of the
 *       readings means something. It is a stanza bound, not a target.
 *   <li>[WINDOW_MILLIS] bounds the time range on the server side. The conversation's language
 *       is what the other person writes <em>now</em>; a language they used two years ago is not the
 *       language the owner's next message should be translated into.
 *   <li>[MAX_PER_RUN] is the global ceiling for one run of the app, so nothing can fan out
 *       across the whole roster. One sample per conversation per run is enforced by the sampler.
 * </ul>
 *
 * <p>Nothing here paginates. The caller sends exactly one query and stops on the first
 * `<fin>`, whatever the result set management says about what is left.
 *
 * <p>Pure Kotlin; the three bounds are `const val`s (Java callers and the tests read them).
 */
object LanguageSample {

    /**
     * The hard cap on how many archived messages are read for one conversation. It is enforced on
     * both sides - the query asks for at most this many and there is never a second query, and the
     * sampler keeps at most this many whatever the server chooses to send.
     */
    const val MAX_MESSAGES = 25

    /**
     * How far back the sample may reach: thirty days. A month is long enough that a quiet
     * conversation still has something in it, and short enough that the language read is the one
     * being written today.
     */
    const val WINDOW_MILLIS = 30L * 24L * 60L * 60L * 1000L

    /**
     * At most this many conversations are sampled in one run of the app. Opening conversations one
     * at a time gives at most one sample each, so this ceiling only exists so that no future caller
     * can turn the feature into a roster-wide burst.
     */
    const val MAX_PER_RUN = 5

    /**
     * May this archived message be read for detection?
     *
     * <p>This is deliberately stricter than the receive path's automatic pass, and stricter for a
     * reason: that path answers "may we spend money on this?", while this one answers "may this
     * become the target language of everything the owner sends?". A meaning can be bought again; a
     * wrong conversation language sends the owner's next message into a language the other person
     * may not read.
     *
     * <p>It is exactly one clause stricter, and it was a second copy of the rule until it was
     * written as the clause instead. The plaintext requirement is the encrypted-conversation
     * limitation, stated once: an archived message in an OMEMO or PGP conversation arrives as
     * ciphertext, and decrypting a sample would have side effects (sessions, device lists, ratchets)
     * that are not worth it here, so a ciphertext candidate is skipped rather than decrypted.
     *
     * <p>What it delegates to is [TranslationDecision.isRequestable], the tap path's rule, and
     * <em>not</em> [TranslationDecision.isEligible]: the sample's input <em>is</em> the archive, so
     * the archive refusal that belongs to the automatic pass would refuse every sample there is.
     */
    @JvmStatic
    fun isEligible(candidate: TranslationDecision.Candidate?, interpreter: Interpreter): Boolean =
            TranslationDecision.isRequestable(candidate, interpreter) &&
                    candidate?.encryption == Message.ENCRYPTION_NONE

    /**
     * What one archived body says the conversation's language is, or `null` when it does not
     * say anything worth acting on.
     *
     * <p>This <em>is</em> the live path's rule - [ConversationLanguage.read] with no model
     * answer to fall back on, because a sample never calls DeepSeek - and it is written as that call
     * rather than as a second copy of it.
     *
     * @param body the archived body
     * @param appLanguage the app language, which is never a conversation's language
     * @param conversationName the name the interface shows for the conversation being sampled
     *     ([ConversationName]), or `null` when the caller has none
     */
    @JvmStatic
    fun readOne(body: String?, appLanguage: String?, conversationName: String?): String? =
            ConversationLanguage.read(body, null, appLanguage, null, conversationName).language

    /**
     * The one reading this sample is allowed to produce, or `null` when there is not one.
     *
     * <p>A sample is many messages, so "the last one wins" - which is what the live path does, one
     * message at a time - is not available: the messages did not arrive in a meaningful order and
     * several of them may disagree. The rule is a <em>strict majority</em> of the readings that were
     * worth acting on. A conversation whose archive is genuinely split between two languages has no
     * single send target, and the honest answer there is that the sample learned nothing.
     */
    @JvmStatic
    fun verdict(readings: List<String?>?): String? {
        if (readings == null || readings.isEmpty()) {
            return null
        }
        val tally = LinkedHashMap<String, Int>()
        var counted = 0
        for (code in readings) {
            if (code == null) {
                continue
            }
            tally.merge(code, 1) { a, b -> a + b }
            counted++
        }
        if (counted == 0) {
            return null
        }
        for (entry in tally.entries) {
            if (entry.value * 2 > counted) {
                return entry.key
            }
        }
        return null
    }

    /**
     * The whole rule, over a window's worth of archived messages: which of them may be read, what
     * each one says, and what the majority of those say. `null` means the sample learned
     * nothing, and the caller must change nothing.
     */
    @JvmStatic
    fun read(
            candidates: List<TranslationDecision.Candidate>?,
            appLanguage: String?,
            interpreter: Interpreter
    ): String? {
        if (candidates == null || candidates.isEmpty()) {
            return null
        }
        val readings = ArrayList<String?>()
        for (candidate in candidates) {
            if (!isEligible(candidate, interpreter)) {
                continue
            }
            val reading = readOne(candidate.body, appLanguage, candidate.conversationName)
            if (reading != null) {
                readings.add(reading)
            }
        }
        return verdict(readings)
    }

    /**
     * Is this conversation's language genuinely unknown, so that the archive is worth asking at all?
     *
     * <p>Both stored values have to be empty. An override is the owner's own answer and a detection
     * is what the app already read from their messages; either one means the sample has nothing to
     * add, and the sampler must not query, and must never touch either value. A sample only ever
     * fills a gap.
     *
     * <p><strong>With the interpreter off the answer is `false`, and that is the answer before
     * the two values are read at all.</strong> A conversation's language is the target of everything
     * the owner sends, so learning it is part of interpreting; a plain XMPP client has no send target
     * to learn and must send nothing to the archive.
     *
     * @param interpreter the interpreter's mode, last like every other rule's; `null` is off (a
     *     caller with nothing to say, not a licence)
     */
    @JvmStatic
    fun shouldSample(
            detectedLanguage: String?,
            languageOverride: String?,
            interpreter: Interpreter?
    ): Boolean {
        if (interpreter == null || !interpreter.enabled()) {
            return false
        }
        return ComposerGate.isUnknownLanguage(detectedLanguage) &&
                ComposerGate.isUnknownLanguage(languageOverride)
    }

    /** The oldest moment a sample taken at `now` may reach back to. */
    @JvmStatic fun windowStart(now: Long): Long = now - WINDOW_MILLIS
}
