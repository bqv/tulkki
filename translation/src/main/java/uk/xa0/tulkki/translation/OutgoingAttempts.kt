package uk.xa0.tulkki.translation

import java.util.ArrayDeque
import java.util.ArrayList
import java.util.Locale

/**
 * What Tulkki's own check made of each outgoing translation attempt, kept in memory so a hold can be
 * explained instead of guessed at.
 *
 * <p><strong>Why this exists.</strong> A held row stores exactly one word - its failure cause - so
 * "why is this message still here?" had no answer beyond that word. The owner's own case is a short
 * message ("ok") held on doubt: whether the model returned a different answer on the tap, or the
 * check refused again, was invisible. Every attempt is therefore written down as it finishes: the
 * direction, the language that was asked for, the answer's length, each reader's own code and
 * confidence, what the check concluded, and the reason the message was held.
 *
 * <p><strong>The log line is the deliverable; this is the ring behind it.</strong> One greppable line
 * per attempt goes to logcat under the `tulkki` tag ([Attempt.logLine]), complete enough to read on
 * its own: no screen, no manifest entry and no notification action is involved, and none is added.
 * The ring keeps the same facts as values, so a future surface can read them through
 * [TranslationActivityPort.recentOutgoingAttempts] - the seam the app already holds - without new
 * wiring, and [describe] is that reading in plain English.
 *
 * <p><strong>In memory, and bounded.</strong> The newest [CAPACITY] attempts - a couple of dozen, not
 * a log file, and no database, no schema and no setting.
 *
 * <p><strong>It never holds the message text, and that is load-bearing.</strong> A record carries a
 * length and language codes, never a body, so [Attempt.logLine] is safe to write to logcat and
 * [describe] is safe for any surface. The one surface allowed to show the message itself is the
 * failures screen, and this class is not that.
 *
 * <p>Pure Kotlin, no Android types, so the recording and the wording are exercised by JVM unit tests.
 */
class OutgoingAttempts(private val limit: Int = CAPACITY) {

    init {
        require(limit > 0) { "a ring of $limit attempts would keep nothing" }
    }

    private val lock = Any()

    /** Newest last, so "the last attempt" is the tail. Guarded by [lock]. */
    private val entries = ArrayDeque<Attempt>()

    /**
     * Keeps one attempt, dropping the oldest when the ring is full.
     *
     * <p>A re-ask is linked to the newest earlier attempt for the same message as it is recorded, so
     * a reader can say "this is the second try" without the caller having to remember the first. A
     * caller that already linked the entry keeps its own link.
     */
    fun record(attempt: Attempt) {
        synchronized(lock) {
            if (attempt.request == Request.RE_ASK && attempt.reAskOf < 0) {
                attempt.reAskOf = lastAtLocked(attempt.messageUuid)
            }
            entries.addLast(attempt)
            while (entries.size > limit) {
                entries.removeFirst()
            }
        }
    }

    /** Everything still kept, oldest first. A copy, so a caller cannot reach into the ring. */
    fun recent(): List<Attempt> = synchronized(lock) { ArrayList(entries) }

    /** The attempts of one message, oldest first; empty when nothing is kept for it. */
    fun forMessage(messageUuid: String?): List<Attempt> {
        if (messageUuid == null) {
            return emptyList()
        }
        return synchronized(lock) {
            val own = ArrayList<Attempt>()
            for (entry in entries) {
                if (entry.messageUuid == messageUuid) {
                    own.add(entry)
                }
            }
            own
        }
    }

    /** Forgets everything. The ring is a diagnostic aid, never state a message depends on. */
    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    /**
     * The text a future surface draws for one message: its last attempt and its re-ask, or an empty
     * string when nothing is kept for it.
     *
     * <p>Plain English, and it names the readers and their confidences: "Lingua said Finnish (fi)
     * with confidence 0.40, the script reader said nothing, so the check called it doubt". The
     * message's own words are never in it - a length stands in their place. Nothing in the app calls
     * this today: the log line is the surface, and this is the ring's own reading of the same facts
     * for whoever builds the screen next.
     */
    fun describe(messageUuid: String?): String {
        val own = forMessage(messageUuid)
        if (own.isEmpty()) {
            return ""
        }
        val lines = ArrayList<String>(2)
        own.lastOrNull { it.request == Request.NORMAL }?.let {
            lines.add("last attempt (" + it.requestWords() + "): " + it.sentence())
        }
        own.lastOrNull { it.request == Request.RE_ASK }?.let {
            lines.add("re-ask (" + it.requestWords() + "): " + it.sentence())
        }
        return lines.joinToString("\n")
    }

    /** The newest earlier attempt's time for this message, or -1 when there is none. */
    private fun lastAtLocked(messageUuid: String?): Long {
        val descending = entries.descendingIterator()
        while (descending.hasNext()) {
            val candidate = descending.next()
            if (candidate.messageUuid == messageUuid) {
                return candidate.at
            }
        }
        return -1L
    }

    /** Which question produced an attempt: the ordinary one, or the tap's re-ask. */
    enum class Request {
        /** The owner's own send, or the tap's first look. */
        NORMAL,
        /** The owner's tap on a held message: the same question with the app's own clause. */
        RE_ASK
    }

    /** What the check concluded, in the words the log line and the sentence use. */
    enum class Check {
        /** The answer is in the language that was asked for. */
        ACCEPTED,
        /** The answer is the text that was sent, unchanged, or its short form. */
        ECHO,
        /** Nothing came back. */
        EMPTY,
        /** The answer is in a language that was not asked for. */
        WRONG_LANGUAGE,
        /** No reader could vouch for the language, so the send path held it on doubt. */
        DOUBT,
        /** No answer came back to check at all - the request was never made, or it failed. */
        NOT_ATTEMPTED;

        /** The word the log line uses for this outcome. */
        fun words(): String =
                when (this) {
                    ACCEPTED -> "accepted"
                    ECHO -> "echo"
                    EMPTY -> "empty"
                    WRONG_LANGUAGE -> "wrong language"
                    DOUBT -> "doubt"
                    NOT_ATTEMPTED -> "not attempted"
                }
    }

    /** One reader's opinion, and the name of the reader that held it. */
    class ReaderVerdict(
            /** The reader as a diagnostic names it, from [LanguageCheck.Reader.label]. */
            @JvmField val reader: String,
            /** Its reading, or `null` when it said nothing. */
            @JvmField val reading: LanguageCheck.Reading?
    ) {

        /**
         * One clause of the line or the sentence: "Lingua said Finnish (fi) with confidence 0.40",
         * "the script reader said nothing". The language is named, never its code alone, and a
         * writing system is said to be one rather than given a confidence it does not have.
         */
        fun saidText(): String {
            val held = reading ?: return reader + " said nothing"
            val code = held.code
            if (code == null || ScriptReading.normalize(code) == null) {
                return reader + " said nothing"
            }
            val named = ConversationLanguage.languageName(code) + " (" + code + ")"
            if (held.script) {
                return reader + " said " + named + ", which is a writing system and not a guess"
            }
            return reader + " said " + named + " with confidence " + confidenceText(held.confidence)
        }
    }

    /**
     * One outgoing attempt, as a value: when, which message, which direction, which question, and
     * what came back.
     *
     * <p>Built through [OutgoingAttempts.of] (an answer the check read) or
     * [OutgoingAttempts.notAttempted] (a hold before any answer), so the mapping from the check's own
     * verdict lives in one place. The `reAskOf` link is filled in by the ring as the entry is
     * recorded.
     */
    class Attempt
    internal constructor(
            /** When the attempt finished, in the phone's own clock. */
            @JvmField val at: Long,
            /** The message it was made for, or `null` when none was involved. */
            @JvmField val messageUuid: String?,
            /** The question that was asked. */
            @JvmField val request: Request,
            /** The language the owner wrote in: the app language, the outgoing direction's source. */
            @JvmField val appLanguage: String?,
            /** The language the answer was asked for: the conversation's own. */
            @JvmField val target: String?,
            /** How long the answer was, in characters - never the answer itself. */
            @JvmField val answerLength: Int,
            /** Every reader's own opinion, in the readers' own order. */
            @JvmField val readers: List<ReaderVerdict>,
            /** What the check concluded. */
            @JvmField val outcome: Check,
            /** The reason the message was held, or `null` when it was sent. */
            @JvmField val reason: HeldSend.HoldReason?,
            /** The time of the attempt this one re-asks, or -1. Filled in by [OutgoingAttempts]. */
            @JvmField var reAskOf: Long
    ) {

        /**
         * The one greppable line per attempt: the whole story, and never the text.
         *
         * <p>It is meant to be read on its own out of context, so it names the direction, the target,
         * the answer's length, what each reader said and with what confidence, the check's outcome,
         * the hold reason and which question was asked. A line that re-asks says so and points at the
         * attempt it re-asks. There is no field here that could carry a body, which is the point
         * rather than a promise.
         *
         * <p>Written under the `tulkki` tag, so `adb logcat -s tulkki` or a grep finds it.
         */
        fun logLine(): String {
            val line = StringBuilder("outgoing attempt")
            if (reAskOf >= 0) {
                line.append("; re-ask of the attempt at ").append(reAskOf)
            }
            line.append("; from the app language ").append(named(appLanguage))
            line.append("; into the conversation's language ").append(named(target))
            line.append("; answer length ")
                    .append(answerLength)
                    .append(if (answerLength == 1) " character" else " characters")
            line.append("; readers: ").append(readersWords())
            line.append("; check: ").append(outcome.words())
            line.append("; held: ").append(reason?.let { reasonWords(it) } ?: "nothing")
            line.append("; question: ").append(requestWords())
            return line.toString()
        }

        override fun toString(): String = logLine()

        /** Which question this was, in the words the line and the sentence use. */
        internal fun requestWords(): String =
                when (request) {
                    Request.NORMAL -> "the ordinary question"
                    Request.RE_ASK -> "the same question asked again with the app's own clause"
                }

        /**
         * The attempt as one sentence a person reads, naming languages and readers and never the
         * text. Used by [describe]; internal because the only public rendering is the whole block.
         */
        internal fun sentence(): String {
            val parts = ArrayList<String>(4)
            parts.add("target " + named(target))
            parts.add(
                    "answer " +
                            answerLength +
                            (if (answerLength == 1) " character" else " characters"))
            parts.add(readersWords())
            parts.add(conclusionSentence())
            return parts.joinToString("; ") + "."
        }

        private fun readersWords(): String =
                when {
                    outcome == Check.NOT_ATTEMPTED -> "no answer came back to read"
                    readers.isEmpty() -> "not asked, the check answered first"
                    else -> readers.joinToString(", ") { it.saidText() }
                }

        private fun conclusionSentence(): String {
            val conclusion =
                    when (outcome) {
                        Check.ACCEPTED -> "the check accepted it"
                        Check.ECHO ->
                                "the check called it an echo: the answer is the text that was sent, unchanged"
                        Check.EMPTY -> "the check called it empty: nothing came back"
                        Check.WRONG_LANGUAGE ->
                                "the check called it another language, not the one that was asked for"
                        Check.DOUBT ->
                                "the check called it doubt: no reader could vouch for the language"
                        Check.NOT_ATTEMPTED -> "no check ran"
                    }
            return if (reason == null) {
                conclusion + ", so nothing was held"
            } else {
                conclusion + ", so the message was held: " + reasonWords(reason)
            }
        }
    }

    companion object {

        /**
         * How many attempts are kept. A couple of dozen is enough to cover a conversation's recent
         * trouble without holding the text of anything: a record is lengths and codes, so the memory
         * this bounds is negligible and the number is a judgement about how far back a person looks,
         * not about bytes.
         */
        const val CAPACITY = 24

        /**
         * An attempt whose answer the check read, with every reader's reading beside the verdict.
         *
         * <p>This is the only place the check's own verdict is turned into a [Check], so the log line
         * and any surface built from it cannot say something the check did not decide.
         */
        @JvmStatic
        fun of(
                messageUuid: String?,
                appLanguage: String?,
                target: String?,
                answerLength: Int,
                inspection: LanguageCheck.Inspection,
                reason: HeldSend.HoldReason?,
                request: Request,
                at: Long
        ): Attempt {
            val verdicts = ArrayList<ReaderVerdict>(inspection.readings.size)
            for (index in inspection.readings.indices) {
                val reader = inspection.readers.getOrElse(index) { "reader" }
                verdicts.add(ReaderVerdict(reader, inspection.readings[index]))
            }
            return Attempt(
                    at,
                    messageUuid,
                    request,
                    appLanguage,
                    target,
                    answerLength,
                    verdicts,
                    outcomeOf(inspection.report),
                    reason,
                    -1L)
        }

        /**
         * An attempt that never reached the check: a local hold (no key, the cap, an unknown target)
         * or a failed request. It is still recorded, because "held, and why" is exactly what the
         * owner cannot otherwise see, and its readers are honestly empty.
         */
        @JvmStatic
        fun notAttempted(
                messageUuid: String?,
                appLanguage: String?,
                target: String?,
                reason: HeldSend.HoldReason?,
                request: Request,
                at: Long
        ): Attempt =
                Attempt(
                        at,
                        messageUuid,
                        request,
                        appLanguage,
                        target,
                        0,
                        emptyList(),
                        Check.NOT_ATTEMPTED,
                        reason,
                        -1L)

        /**
         * The check's verdict as the one word the record keeps. A short echo - [LanguageCheck.Doubt.
         * NOTHING_TRANSLATED] - is an [Check.ECHO] here: the answer really is the text that was sent,
         * and that is the fact the owner is looking for, while a doubtful language is the [Check.
         * DOUBT] the question is about.
         */
        private fun outcomeOf(report: LanguageCheck.Report): Check =
                when {
                    report.doubt == LanguageCheck.Doubt.NOTHING_TRANSLATED -> Check.ECHO
                    report.doubt != null -> Check.DOUBT
                    report.outcome == LanguageCheck.Outcome.ECHOED -> Check.ECHO
                    report.outcome == LanguageCheck.Outcome.NOTHING_CAME_BACK -> Check.EMPTY
                    report.outcome == LanguageCheck.Outcome.WRONG_LANGUAGE -> Check.WRONG_LANGUAGE
                    else -> Check.ACCEPTED
                }
    }
}

/** A language as the log line names it: "Finnish (fi)", or that it is not known. */
private fun named(code: String?): String =
        if (code.isNullOrBlank()) {
            "an unknown language"
        } else {
            ConversationLanguage.languageName(code) + " (" + code + ")"
        }

/** Two decimal places, the same shape the checks' own confidences are written in. */
private fun confidenceText(confidence: Double): String =
        String.format(Locale.ROOT, "%.2f", confidence)

/** Why a message is held, in plain words for the line. */
private fun reasonWords(reason: HeldSend.HoldReason): String =
        when (reason) {
            HeldSend.HoldReason.DOUBT -> "doubt"
            HeldSend.HoldReason.FAILED -> "a failure"
            HeldSend.HoldReason.NO_KEY -> "no key"
            HeldSend.HoldReason.CAP_REACHED -> "the daily cap"
            HeldSend.HoldReason.NO_CREDIT -> "no credit"
            HeldSend.HoldReason.UNREACHABLE -> "DeepSeek was unreachable"
            HeldSend.HoldReason.REJECTED_KEY -> "the key was refused"
            HeldSend.HoldReason.UNKNOWN_LANGUAGE -> "the language is not known yet"
        }
