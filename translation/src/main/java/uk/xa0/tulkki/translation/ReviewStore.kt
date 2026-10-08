package uk.xa0.tulkki.translation

import android.content.Context
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import uk.xa0.tulkki.data.model.Message

/**
 * Where the notes on the owner's own wording live, and how a bubble finds them again.
 *
 * <p>The notes are written the moment they arrive - they ride on the outbound translation call, so
 * that moment is the only one there will ever be - and read back under a key derived from the words
 * they are about, exactly the way [GlossLookup] keeps a gloss. They share the translation
 * cache table through [TranslationCache] and [TranslationStore] rather than getting a
 * table of their own: an answer keyed by its own content, already paid for, is what that table is
 * for, and it is what keeps this feature from needing a schema migration.
 *
 * <p><strong>Storing is best effort, and that is deliberate.</strong> The notes are commentary
 * riding on a call that was made for its translation; if the row cannot be written, the message must
 * still go out with its translation. A failure here is logged and nothing else, so no database
 * problem can turn a send into a held message.
 *
 * <p>A review with no notes is not stored, because a row that would draw nothing is not worth
 * keeping: the bubble shows the same nothing whether the model had no remark or was never asked.
 *
 * <p><strong>With the interpreter off this class does nothing at all.</strong> Both entry points ask
 * the mode before they touch anything: [record] returns without writing - no memory entry and
 * no cache row, so a turned-off interpreter's bubble cannot read back a review the write side just
 * kept - and [of] answers [Review.absent] without reading the cache or the memo. A
 * plain XMPP client has no notes, and the store is where every caller of the review path comes
 * together, so this is the one place the off state has to hold.
 *
 * <p><strong>Package-private in the Java this replaces; public `@JvmStatic` members of the
 * `object` here</strong>, because Kotlin's `internal` mangles the JVM name and the Java tests
 * (`HeldReviewNotesTest`, `ReviewKeyTest`, `InterpreterOffSpendsNothingTest`, `RetryStoppedTest`)
 * call the two storage overloads and `rawOf` across the package. The widening is the one deliberate
 * API change in this file and it changes no behaviour. The public `Context` entry points and the
 * package-private storage ones keep their two arities, and every parameter the Java checked or
 * passed through stays nullable. `of`'s `Context` is nullable to match the Java's own
 * `context == null` check, which `ReviewKeyTest` exercises by passing null; `record`'s stays
 * non-null because every caller passes a real one; `reemit`'s stays nullable because the Java
 * returned on a null one first.
 */
object ReviewStore {

    private const val TAG = "Tulkki"

    /**
     * The answers this process has already read, so scrolling does not repeat a lookup. Process-wide
     * because a conversation is drawn from one process and the reviews outlive no one.
     */
    private val MEMO = ReviewMemo()

    /** The language the notes are written in: the study language, the reading aid's own setting. */
    @JvmStatic
    fun reviewLanguage(context: Context): String =
            TranslationSettings.get(context).studyLanguage()

    /**
     * Keeps what the model said about these words, and nothing when it said nothing usable.
     *
     * <p>With the interpreter off this is the first thing asked and the only thing that happens: no
     * memory entry, no cache row, and no store is even built. A plain client has no notes, so there is
     * nothing to keep - and the write side matters even though its production caller already sits
     * behind a guard, because the memory entry outlives that caller and would otherwise hand a review
     * to a bubble drawn by the [of] side of a turned-off interpreter.
     *
     * @param ownerText the owner's own words, exactly as the translation call was given them
     * @param reviewLanguage the language the notes are in
     * @param review the parsed answer; `null` and an absent review are both "nothing to keep"
     */
    @JvmStatic
    fun record(
            context: Context,
            ownerText: String?,
            reviewLanguage: String?,
            review: Review?
    ) {
        val interpreter = TranslationSettings.get(context).interpreter()
        // Off before a null is dereferenced and before anything is written: the answer a plain client
        // gives is that there is nothing here to keep.
        if (!keeps(interpreter, review)) {
            return
        }
        record(
                TranslationCache(TranslationStore(context)),
                ownerText,
                reviewLanguage,
                review,
                interpreter)
    }

    /**
     * The same write over storage that is already in hand, with the mode as a required argument.
     *
     * <p>Package-private in the Java, and the only place a row is built. It exists so the off state
     * has somewhere a JVM test can reach: the public entry point reads a `Context` for the store and
     * for nothing else, while this takes the store and the mode together - the seam the whole review
     * path is otherwise missing, because [ReviewStore] is the first class on it that reads a database.
     *
     * <p>Off is the first statement, above the memory entry and above the cache: nothing is kept in
     * the memo and nothing is written, so a bubble drawn while the interpreter is off cannot read a
     * review back through [of] either.
     */
    @JvmStatic
    fun record(
            cache: TranslationCache,
            ownerText: String?,
            reviewLanguage: String?,
            review: Review?,
            interpreter: Interpreter?
    ) {
        if (!keeps(interpreter, review)) {
            return
        }
        // `keeps` answered yes, so there is an answer; naming it here is what the row is built from.
        val kept = review ?: return
        val key = ReviewKey.of(ownerText, reviewLanguage)
        // In memory first: the bubble that is about to be re-bound is the one that just sent this
        // message, and it must show the notes without waiting for a database round trip.
        MEMO.put(key, kept)
        try {
            cache.store(
                    key,
                    reviewLanguage,
                    rawOf(kept),
                    // Zero: the notes cost no call of their own. The tokens of the call that
                    // carried them belong to the translation, and are counted where
                    // translations are counted - counting them here as well would say the
                    // same spend twice.
                    0,
                    System.currentTimeMillis())
        } catch (e: RuntimeException) {
            // The message sends anyway; only the notes are lost.
            Log.w(TAG, "the notes on a sent message could not be kept: " + e)
        }
    }

    /**
     * Re-emits notes that were bought once already: a held answer's notes, kept with the hold, put
     * back where a bubble reads them when that answer is finally sent.
     *
     * <p>This is a write, not a purchase: it goes through exactly the ordinary [record] path,
     * under the same key the write side uses, so the underline surface finds the notes after a kill
     * as it would have found them on the call that bought them. An entry that no longer parses is
     * dropped rather than guessed at, and the mode still gates it - a plain client keeps no notes.
     */
    @JvmStatic
    fun reemit(
            context: Context?,
            ownerText: String?,
            reviewLanguage: String?,
            storedNotes: String?
    ) {
        if (context == null || storedNotes == null || storedNotes.javaTrim().isEmpty()) {
            return
        }
        reemit(
                TranslationCache(TranslationStore(context)),
                ownerText,
                reviewLanguage,
                storedNotes,
                TranslationSettings.get(context).interpreter())
    }

    /** The same re-emission over storage that is already in hand, for the JVM cells. */
    @JvmStatic
    fun reemit(
            cache: TranslationCache,
            ownerText: String?,
            reviewLanguage: String?,
            storedNotes: String?,
            interpreter: Interpreter?
    ) {
        if (storedNotes == null || storedNotes.javaTrim().isEmpty()) {
            return
        }
        val stored = ReviewParser.parse(storedNotes)
        if (stored == null || stored.items().isEmpty()) {
            return
        }
        record(cache, ownerText, reviewLanguage, stored, interpreter)
    }

    /**
     * The notes on the owner's own message, or an absent review.
     *
     * <p>Which messages can have one at all is [ReviewKey.forBubble]'s rule, not this
     * method's, so a received message never reaches the cache and never shows a note.
     *
     * <p>A null `Context` or `Message` answers [Review.absent] as the first statement: that is the
     * Java's own `context == null || message == null` rule, and the Kotlin has to answer it above
     * [TranslationSettings.get] because that call takes a non-null `Context` and must not be widened
     * to take the null the Java's early return never used.
     *
     * <p>With the interpreter off this answers [Review.absent] as well, before the cache is read and
     * before [ReviewKey.forBubble] is even called - that method would answer `null` for every row
     * anyway, but the point of asking here is that no `translation_cache` read happens for a plain
     * client at all.
     */
    @JvmStatic
    fun of(context: Context?, message: Message?): Review {
        if (context == null || message == null) {
            return Review.absent()
        }
        val interpreter = TranslationSettings.get(context).interpreter()
        if (!enabled(interpreter)) {
            return Review.absent()
        }
        val language = reviewLanguage(context)
        val key =
                ReviewKey.forBubble(
                        message.getTranslatedBody(),
                        message.getTranslationState(),
                        message.getStatus(),
                        language,
                        interpreter)
        if (key == null) {
            return Review.absent()
        }
        val remembered = MEMO.get(key)
        if (remembered != null) {
            return remembered
        }
        try {
            val entry = TranslationCache(TranslationStore(context)).get(key)
            if (entry == null) {
                // Not remembered, so a bubble drawn before the notes land asks again the next time
                // it is bound rather than hiding them until the process restarts.
                return Review.absent()
            }
            val stored = ReviewParser.parse(entry.translatedBody)
            if (stored == null || stored.items().isEmpty()) {
                // An entry that no longer parses or says nothing is treated as absent rather than as
                // an answer: the shape of a review could have changed since it was stored, and that
                // is exactly why ReviewKey's namespace carries the contract's version.
                return Review.absent()
            }
            MEMO.put(key, stored)
            return stored
        } catch (e: RuntimeException) {
            Log.w(TAG, "the notes on a sent message could not be read: " + e)
            return Review.absent()
        }
    }

    /** The answer as it will be kept: the same JSON [ReviewParser] reads back. */
    @JvmStatic
    fun rawOf(review: Review): String {
        val notes = JsonArray()
        for (note in review.items()) {
            val flagged = JsonArray()
            for (slice in note.flagged()) {
                // Verbatim, spaces and all. This row is what the bubble reads the stretch out of to
                // find it in the owner's own text, so storing is the last place it may be tidied.
                flagged.add(slice)
            }
            val one = JsonObject()
            one.addProperty(ReviewParser.FIELD_NOTE, note.remark())
            one.add(ReviewParser.FIELD_FLAGGED, flagged)
            notes.add(one)
        }
        val root = JsonObject()
        root.add(ReviewParser.FIELD_NOTES, notes)
        return root.toString()
    }

    /**
     * Whether there is anything to keep at all, on both sides of the store: the mode first, then the
     * answer. One home for "nothing to record", shared by the two `record` shapes so the
     * `Context` one cannot drift from the one a JVM test reaches.
     */
    private fun keeps(interpreter: Interpreter?, review: Review?): Boolean =
            enabled(interpreter) && review != null && review.isPresent() && review.notes().isNotEmpty()

    /**
     * The mode, said once. `null` is off - a caller with nothing to say, not a licence - the
     * same reading [GlossLookups.mayBegin] takes.
     */
    private fun enabled(interpreter: Interpreter?): Boolean =
            interpreter != null && interpreter.enabled()
}

/**
 * `String.trim()` with Java's own definition: everything at or below U+0020.
 */
private fun String.javaTrim(): String = trim { it <= ' ' }
