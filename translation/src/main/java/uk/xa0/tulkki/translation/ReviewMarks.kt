package uk.xa0.tulkki.translation

import java.util.ArrayList
import java.util.Collections
import java.util.Objects

/**
 * Where the notes' underlines go in the owner's own text - the one rule that turns a [Review]
 * into spans, kept out of the view so it can be tested.
 *
 * <p>A note's stretches are the model's own characters, copied verbatim exactly so they can be found
 * again. Finding them is a plain `indexOf` against the text the owner typed: no trimming, no
 * case folding, no normalisation, because the value was kept untrimmed for this moment and any
 * tidying here would be the thing that broke the match.
 *
 * <p><strong>A stretch that is not in the text costs the underline and nothing else.</strong> The
 * model paraphrased instead of copying, or the bubble was clipped at its display limit, or the slice
 * sits in a part of the message the view is not drawing: whatever the reason, the remark is the note
 * and the stretch is a pointer into the text. Dropping a remark because its pointer missed would
 * hide the one thing worth reading, so a miss is silently one fewer mark and the caller is expected
 * to keep showing the remarks - which is why [Review.notes] is untouched by any of this.
 *
 * <p><strong>Each stretch is underlined once.</strong> Two notes can point at overlapping text, and
 * two span ranges crossing each other would make a tap on the overlap arbitrary - the same class of
 * ambiguity the message menu's long-press guard exists to avoid. The first note's mark stands, a
 * later note's overlapping one is not drawn, and the later remark is still in
 * [Review.items] for whatever shows the whole review. Exact duplicates behave the same way.
 *
 * <p>Only the first occurrence of a stretch is marked, because the model named the words rather
 * than a position and one pointer deserves one underline. Pure Kotlin, no Android types, so it is
 * exercised by JVM unit tests.
 *
 * <p>`in` is a Kotlin keyword, so the member is declared as `` `in` ``; `@JvmStatic` keeps the JVM
 * name `in` for the one Java caller (`MessageAdapter`), and no Kotlin code calls it. `Mark`'s
 * constructor is `internal` - package-private in the Java this replaces, and only `ReviewMarks`
 * builds one - while the class and its accessors stay exactly the shape Java sees.
 */
object ReviewMarks {

    /**
     * The places to underline in `text` for `review`, in the order the model gave them.
     *
     * @param review the notes; `null` and an absent review both yield nothing
     * @param text what the owner typed, exactly as the review's key was taken from it; `null`
     *     yields nothing
     * @return never `null`; empty when there is nothing to underline
     */
    @JvmStatic
    fun `in`(review: Review?, text: String?): List<Mark> {
        if (review == null || text == null || text.isEmpty() || review.items().isEmpty()) {
            return emptyList()
        }
        val marks = ArrayList<Mark>()
        for (note in review.items()) {
            for (slice in note.flagged()) {
                if (slice.isEmpty()) {
                    // Nothing to look for. ReviewParser refuses a blank slice, so this is a value
                    // built by hand, and an empty needle would "match" at every offset.
                    continue
                }
                val start = text.indexOf(slice)
                if (start < 0) {
                    // The miss. Only the underline is lost - see the class comment.
                    continue
                }
                val end = start + slice.length
                if (overlaps(marks, start, end)) {
                    // Already underlined by an earlier note; one stretch is one tap target.
                    continue
                }
                marks.add(Mark(start, end, slice, note.remark()))
            }
        }
        return Collections.unmodifiableList(marks)
    }

    /** Whether [start, end) crosses a mark that is already there, either end exclusive. */
    private fun overlaps(marks: List<Mark>, start: Int, end: Int): Boolean {
        for (mark in marks) {
            if (start < mark.end() && end > mark.start()) {
                return true
            }
        }
        return false
    }

    /**
     * One stretch to underline: the characters, and the remark a tap on them is about.
     *
     * <p>Carries the slice as well as its range so a container can label what was flagged without
     * slicing the text again, and so two marks for the same remark at different places still compare
     * as different values.
     */
    class Mark internal constructor(
            private val startOffset: Int,
            private val endOffset: Int,
            private val sliceText: String,
            private val remarkText: String
    ) {

        /** The first character of the stretch, as an offset into the text that was searched. */
        fun start(): Int = startOffset

        /** One past the stretch's last character. */
        fun end(): Int = endOffset

        /** The stretch as the model wrote it, which is what was found, verbatim. */
        fun slice(): String = sliceText

        /** The remark this stretch is about, in the study language. */
        fun remark(): String = remarkText

        override fun equals(other: Any?): Boolean {
            if (this === other) {
                return true
            }
            if (other !is Mark) {
                return false
            }
            return startOffset == other.startOffset &&
                    endOffset == other.endOffset &&
                    sliceText == other.sliceText &&
                    remarkText == other.remarkText
        }

        override fun hashCode(): Int = Objects.hash(startOffset, endOffset, sliceText, remarkText)

        override fun toString(): String =
                "[" + startOffset + "," + endOffset + ") " + sliceText + " -> " + remarkText
    }
}
