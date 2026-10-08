package uk.xa0.tulkki.translation

import java.util.ArrayList

/**
 * What a gloss shows, as a value the popup merely renders.
 *
 * <p>The container is deliberately not decided here. A popup anchored to the word is what the
 * interface uses, and it may change again, so this value says what there is to show - the word, the
 * fields with their values, or a reason there is nothing - and a container that renders
 * [rows] and [failure] can be replaced without touching the lookup or the decision.
 *
 * <p>The failure is a [DisplayedBody.Cover], the same vocabulary a covered bubble speaks, so a
 * gloss that could not be bought says "no key", "cap reached" or "unreachable" in the words the rest
 * of the app already uses rather than inventing a second set.
 *
 * <p>Pure Kotlin: the field labels are resources, and the container maps a [Field] to one, so
 * nothing here needs an Android type and the rules are exercised by JVM unit tests. `Row`'s two
 * values stay `@JvmField`s because the Java test and the popup read them as fields, while
 * `kind()`/`word()`/`rows()`/`failure()` stay methods.
 */
class GlossContent private constructor(
        private val kindValue: Kind,
        word: String?,
        rowList: List<Row>,
        private val failureValue: DisplayedBody.Cover?
) {

    /** What the popup is showing. */
    enum class Kind {
        /** The request is out and the answer is not back yet. */
        LOOKING_UP,
        /** A gloss to read. */
        GLOSS,
        /** The lookup could not happen, and [failure] says why. */
        FAILED,
        /** There was nothing to look up: not a word, or already in the study language. */
        NOTHING
    }

    /** One line of a gloss. The order the rows come in is the order they are shown in. */
    enum class Field {
        DICTIONARY,
        ENDING,
        CASE,
        /**
         * The aid's own statement, not a field of the answer: the model said the tapped word is its
         * own dictionary form and named neither an ending nor a case. The whole sentence is the
         * row's label and its value is empty, and it stands exactly where the ending and case rows
         * would have - so a basic form answers the question its silence used to raise.
         */
        BASIC_FORM,
        MEANING
    }

    /**
     * A labelled value: which field, and what to show for it. The one exception is
     * [Field.BASIC_FORM], whose line is a sentence rather than a label and whose value is empty.
     */
    class Row internal constructor(
            @JvmField val field: Field,
            @JvmField val value: String
    ) {

        override fun toString(): String = "" + field + "=" + value
    }

    /** The word that was tapped. Never `null`; empty when there is not one. */
    private val wordValue: String = word ?: ""

    private val rows: List<Row> = java.util.List.copyOf(rowList)

    companion object {

        /** The popup as it opens: the word, and a request that has not answered yet. */
        @JvmStatic
        fun lookingUp(word: String?): GlossContent =
                GlossContent(Kind.LOOKING_UP, word, emptyList(), null)

        /**
         * The answer.
         *
         * <p>A field the model left empty is not a row: an empty ending is not information, and a
         * line saying "Ending: " reads as a bug. The dictionary form is dropped when it is the
         * tapped word itself, because the popup already shows that word as its title.
         *
         * <p>One silence is made to speak, though. A word the model called its own dictionary form
         * and named no ending and no case for <em>is</em> the basic form, and the aid says so in its
         * own words - [Field.BASIC_FORM] - rather than showing a meaning with nothing to explain why
         * there is nothing else. The claim is the model's and never this method's: a word whose
         * dictionary form differs from the surface has an ending the answer did not name, which is
         * an answer with a hole in it, and the aid says nothing about it.
         */
        @JvmStatic
        fun of(gloss: Gloss?): GlossContent {
            if (gloss == null || !gloss.isUsable()) {
                return GlossContent(Kind.NOTHING, "", emptyList(), null)
            }
            val rows = ArrayList<Row>()
            if (!gloss.isDictionaryForm() && gloss.dictionary().isNotEmpty()) {
                rows.add(Row(Field.DICTIONARY, gloss.dictionary()))
            }
            if (gloss.ending().isNotEmpty()) {
                rows.add(Row(Field.ENDING, gloss.ending()))
            }
            if (gloss.grammaticalCase().isNotEmpty()) {
                rows.add(Row(Field.CASE, gloss.grammaticalCase()))
            }
            // Only where the model itself asserted the dictionary form, and only where it named
            // neither the ending nor the case: the two empty rows become one sentence instead of
            // nothing.
            if (gloss.isDictionaryForm() &&
                    gloss.ending().isEmpty() &&
                    gloss.grammaticalCase().isEmpty()) {
                rows.add(Row(Field.BASIC_FORM, ""))
            }
            if (gloss.meaning().isNotEmpty()) {
                rows.add(Row(Field.MEANING, gloss.meaning()))
            }
            return GlossContent(Kind.GLOSS, gloss.surface(), rows, null)
        }

        /** The lookup could not happen: the reason is said, once, in the cover's own words. */
        @JvmStatic
        fun failed(word: String?, reason: HeldSend.HoldReason?): GlossContent =
                GlossContent(
                        Kind.FAILED,
                        word,
                        emptyList(),
                        DisplayedBody.cover(false, reason, reason != null))

        /** Nothing was worth looking up. */
        @JvmStatic
        fun nothing(word: String?): GlossContent =
                GlossContent(Kind.NOTHING, word, emptyList(), null)
    }

    fun kind(): Kind = kindValue

    fun word(): String = wordValue

    /** The gloss's lines, in the order they should be shown. Empty for anything but a gloss. */
    fun rows(): List<Row> = rows

    /** Why the lookup could not happen, or `null`. Only ever set for [Kind.FAILED]. */
    fun failure(): DisplayedBody.Cover? = failureValue

    override fun toString(): String =
            "" + kindValue + "(" + wordValue + ")" + (if (rows.isEmpty()) "" else rows)
}
