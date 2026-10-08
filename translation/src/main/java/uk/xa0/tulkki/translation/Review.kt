package uk.xa0.tulkki.translation

import java.util.ArrayList
import java.util.Collections
import java.util.Objects

/**
 * What the model said about the owner's own wording - or that it said nothing.
 *
 * <p>This is the <em>input</em> side of a send: the notes are about the words the owner typed, and
 * nothing here can be about a message that came in. They exist at all because the outbound
 * translation call already holds those words in its hand, so asking about them in the same request
 * costs a sentence of prompt and nothing else - see [DeepSeekClient.translateWithReview]. A
 * second request would be a second purchase, so there is no path in this class, or in
 * [ReviewStore], that buys one: a review exists only where that call produced it.
 *
 * <p>A review is a list of [Note]s. Each note is a remark in the study language, plus the
 * <strong>stretches of the owner's own text the remark is about</strong>, which is what lets a
 * container underline those words in place instead of describing them. A list of bare remarks could
 * not: it carries no positions, so there is nothing to point at. A note with no stretches is a
 * perfectly good note about the input as a whole, and the stretches are kept <em>verbatim</em> - the
 * characters the model returned, untrimmed - because the container finds them again with a plain
 * `indexOf` against the text the owner typed, and any normalisation here would be the thing
 * that broke the match. Finding them is the container's job: a stretch that is not in the text drops
 * only the underline, never the remark (see [Note.flagged]).
 *
 * <p><strong>Absence is a value, not an empty string.</strong> [absent] is "there is no
 * review" - the call was never asked, the message was never translated, or the answer was unusable -
 * and [of] with no notes is the model saying the wording needs no remark. A container draws
 * nothing for either, which is the point: no remark is invented to fill the space, and a message
 * without notes is exactly the message it would have been before this feature existed. The two are
 * kept apart anyway, because "the model looked and had nothing to say" and "nobody looked" are
 * different facts, and a container that later wants to say so can.
 *
 * <p>The notes arrive in the <strong>study language</strong> - the same setting the reading aid
 * glosses into, and for the same reason: the app language is Finnish, and a remark about the owner's
 * Finnish written in Finnish is the one remark that cannot help.
 *
 * <p>Pure Kotlin, no Android types, so it is exercised by JVM unit tests. The two factory methods and
 * `onlyOwnWords` are `@JvmStatic`, `MAX_NOTES`/`MAX_FLAGGED` are `const val`s (Java reads them in
 * comparisons and a test), and the accessors stay methods because their Java names are the API the
 * Java callers (`ReviewParser`, `ReviewStore`, `:ui`'s adapter and projections) already call.
 */
class Review private constructor(
        private val present: Boolean,
        private val itemList: List<Note>
) {

    /** [notes], derived once here so a scrolling list does not rebuild it per bind. */
    private val remarkList: List<String> =
            if (itemList.isEmpty()) {
                emptyList()
            } else {
                val texts = ArrayList<String>(itemList.size)
                for (note in itemList) {
                    texts.add(note.remark())
                }
                Collections.unmodifiableList(texts)
            }

    companion object {

        /**
         * How many notes a review may carry.
         *
         * <p>Two, because this is a footnote under a message and not an essay: the prompt asks for at
         * most this many, and [ReviewParser] treats a longer list as an answer that did not keep
         * the contract - a stated absence rather than a wall of text under the owner's bubble.
         */
        const val MAX_NOTES = 2

        /**
         * How many stretches of the text one note may point at.
         *
         * <p>A note is about a word or a phrase, so one stretch is the ordinary answer and a handful is
         * generous - a repeated word, two endings of the same mistake. The prompt states this figure, and
         * [ReviewParser] treats a longer list as an answer that ignored the shape rather than as an
         * essay's worth of underlines in the owner's own sentence.
         */
        const val MAX_FLAGGED = 4

        private val ABSENT = Review(false, emptyList())

        /** There is no review: nobody asked, the message was never translated, or the answer was unusable. */
        @JvmStatic
        fun absent(): Review = ABSENT

        /**
         * The model reviewed the wording and said this. An empty list is a complete answer - the wording
         * needs no remark - and is not the same value as [absent], though a container draws the
         * same nothing for both.
         *
         * <p>The count is not checked here: [ReviewParser] is the one place that refuses more notes
         * than [MAX_NOTES], and a value built by hand is not an answer from the model.
         */
        @JvmStatic
        fun of(notes: List<Note>?): Review {
            if (notes == null || notes.isEmpty()) {
                return Review(true, emptyList())
            }
            val copy = ArrayList<Note>(notes.size)
            for (note in notes) {
                copy.add(note ?: Note.of(""))
            }
            return Review(true, Collections.unmodifiableList(copy))
        }

        /**
         * Whether everything a translation call is about to be given is the owner's own words - the one
         * condition under which notes may be asked for at all.
         *
         * <p>A reply's body is a verbatim copy of the message it answers with the owner's own text after
         * it, and `ComposedBody` finds the boundary from the span the sender declared. That span
         * can be declared and unreadable, and then the whole composed body - the copy of somebody else's
         * message included - is what gets translated; that is the accepted direction for the
         * <em>translation</em>, because the alternative is sending half a message untranslated. It is
         * not acceptable for the notes: a remark about a quoted sentence is a remark about the received
         * side, drawn under the owner's own bubble. So the call asks for none, and this is the rule
         * rather than a caller's judgement.
         *
         * <p>The stretches a note points at are slices of that same text, so "the call's input is all the
         * owner's own words" is exactly what makes an underline land on their sentence rather than on
         * somebody else's.
         *
         * @param replyFallbackDeclared whether the message declares a `urn:xmpp:reply:0` fallback
         * @param quotePlaced whether that fallback's span could be read, so the translatable part is
         *     exactly the owner's own text
         */
        @JvmStatic
        fun onlyOwnWords(replyFallbackDeclared: Boolean, quotePlaced: Boolean): Boolean =
                !replyFallbackDeclared || quotePlaced
    }

    /** True when the model was asked and answered; false for [absent]. */
    fun isPresent(): Boolean = present

    /**
     * The notes themselves, in the order the model gave them: each remark together with the stretches
     * of the owner's own text it is about. Never `null`; empty for anything with nothing to
     * show, whatever the reason.
     *
     * <p>This is the value a container that draws the underlines wants; [notes] is the same
     * list with the stretches dropped, which is what a container that only prints the remarks wants.
     */
    fun items(): List<Note> = itemList

    /**
     * The remarks alone, in the order the model gave them. Never `null`; empty for anything
     * with nothing to show, whatever the reason.
     *
     * <p>Exactly the [Note.remark] of every element of [items]: the two can never
     * disagree, because this is derived from that one list and not from a second read of the answer.
     */
    fun notes(): List<String> = remarkList

    override fun toString(): String = if (present) "review" + itemList else "no review"

    /**
     * One remark, and the stretches of the owner's own text it is about.
     *
     * <p>The stretches are what a word processor's spellcheck has: the words the remark is about,
     * kept as they appear in the text so the container can find them with `indexOf` and
     * underline them in place. They are therefore <strong>not</strong> trimmed, collapsed or
     * otherwise tidied - only a character-for-character copy can be found again, and a slice that
     * cannot be found costs the underline and nothing else. Whether a slice is in the text at all is
     * not decided here: this class has never seen the text, and the container is the side that does.
     *
     * <p>The empty list is a real answer: the remark is about the input as a whole, or the model
     * named no words for it. The remark is the note either way, and a note without an underline is
     * still worth reading.
     */
    class Note private constructor(
            private val remarkText: String,
            private val flaggedList: List<String>
    ) {

        companion object {

            /**
             * A remark with nothing underlined. The model may answer this way on purpose - a note about
             * the input as a whole - and [ReviewParser] also reads a missing `flagged` field
             * as this rather than as a broken note.
             */
            @JvmStatic fun of(remark: String?): Note = of(remark, emptyList())

            /**
             * A remark and the stretches of the owner's text it is about.
             *
             * <p><strong>Only the remark is trimmed</strong> - with Java's own `trim()`
             * (`<= ' '`), as in the Java this replaces. A slice is exactly what the model wrote,
             * down to its leading and trailing spaces, because trimming is precisely what would stop it
             * matching the owner's text. A `null` list is the empty list.
             */
            @JvmStatic
            fun of(remark: String?, flagged: List<String>?): Note {
                val slices: List<String> =
                        if (flagged == null || flagged.isEmpty()) {
                            emptyList()
                        } else {
                            val copy = ArrayList<String>(flagged.size)
                            for (slice in flagged) {
                                copy.add(slice ?: "")
                            }
                            Collections.unmodifiableList(copy)
                        }
                return Note(if (remark == null) "" else remark.javaTrim(), slices)
            }
        }

        /** The remark, in the study language. Trimmed; empty only for a value built by hand. */
        fun remark(): String = remarkText

        /**
         * The stretches of the owner's own text this remark is about, in the order the model gave
         * them, exactly as it wrote them. Never `null`; empty when there is nothing to
         * underline.
         */
        fun flagged(): List<String> = flaggedList

        override fun equals(other: Any?): Boolean {
            if (this === other) {
                return true
            }
            if (other !is Note) {
                return false
            }
            return remarkText == other.remarkText && flaggedList == other.flaggedList
        }

        override fun hashCode(): Int = Objects.hash(remarkText, flaggedList)

        override fun toString(): String =
                if (flaggedList.isEmpty()) {
                    remarkText
                } else {
                    remarkText + " @ " + flaggedList
                }
    }
}

/**
 * `String.trim()` with Java's own definition: everything at or below U+0020.
 */
private fun String.javaTrim(): String = trim { it <= ' ' }
