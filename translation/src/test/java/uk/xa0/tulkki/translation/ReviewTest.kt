package uk.xa0.tulkki.translation

import org.junit.Assert
import org.junit.Test

/**
 * The review as a value: what it can be, and what it is not.
 *
 * <p>Two absences live here and they are deliberately different values: {@link Review#absent()} is
 * "nobody asked, or nobody could", and an empty note list is "the model looked and the wording needs
 * no remark". A container draws nothing for either - that is the rule that keeps the notes from
 * being invented - but a value that could not tell them apart would be hiding a fact the model
 * actually stated.
 *
 * <p>The other half is the note itself: a remark plus the stretches of the owner's own text it is
 * about. Those stretches are what makes an underline possible at all, and they have to survive this
 * class untouched - untrimmed, uncased - because the container finds them in the owner's text with a
 * plain {@code indexOf}. Nothing here looks at any text, so "the slice is not in the source" is not a
 * defect this value can see, let alone one it may act on: dropping the underline is the container's
 * decision, and dropping the remark is nobody's.
 */
class ReviewTest {

    @Test
    fun anAbsentReviewIsNotAReview() {
        val absent = Review.absent()

        Assert.assertFalse(absent.isPresent())
        Assert.assertTrue(absent.notes().isEmpty())
        Assert.assertTrue(absent.items().isEmpty())
    }

    @Test
    fun aReviewWithNoNotesIsStillAReview() {
        val empty = Review.of(emptyList())
        val fromNull = Review.of(null)

        Assert.assertTrue(empty.isPresent())
        Assert.assertTrue(empty.notes().isEmpty())
        Assert.assertTrue(fromNull.isPresent())
        Assert.assertTrue(fromNull.items().isEmpty())
    }

    @Test
    fun aNoteIsARemarkAndTheStretchesItIsAbout() {
        val review =
                Review.of(
                        listOf(
                                Review.Note.of(
                                        "the inessive ending is -ssa",
                                        listOf("talossa"))))

        Assert.assertTrue(review.isPresent())
        Assert.assertEquals(1, review.items().size)
        val note = review.items().get(0)
        Assert.assertEquals("the inessive ending is -ssa", note.remark())
        Assert.assertEquals(listOf("talossa"), note.flagged())
    }

    @Test
    fun aNoteWithNoStretchesIsStillANote() {
        // A remark about the input as a whole has nothing to underline, and that is an answer rather
        // than a defect: the remark is the note.
        val note = Review.Note.of("word order is fine here")

        Assert.assertEquals("word order is fine here", note.remark())
        Assert.assertTrue(note.flagged().isEmpty())
    }

    @Test
    fun theRemarksAreTheNotesAndCannotDisagreeWithThem() {
        val review =
                Review.of(
                        listOf(
                                Review.Note.of("first", listOf("a")),
                                Review.Note.of("second")))

        Assert.assertEquals(listOf("first", "second"), review.notes())
        Assert.assertEquals("first", review.items().get(0).remark())
        Assert.assertEquals("second", review.items().get(1).remark())
    }

    @Test
    fun remarksAreTrimmedAndKeptInOrder() {
        val review =
                Review.of(listOf(Review.Note.of("  first  "), Review.Note.of("second")))

        Assert.assertEquals(listOf("first", "second"), review.notes())
    }

    @Test
    fun theStretchesAreNeverTrimmed() {
        // Trimming is exactly what would stop a slice matching the owner's text, and this class is
        // the last one that touches the value before the container looks for it. Only the remark is
        // tidied.
        val note =
                Review.Note.of("  the ending  ", listOf(" talossa", "ssa "))

        Assert.assertEquals("the ending", note.remark())
        Assert.assertEquals(listOf(" talossa", "ssa "), note.flagged())
    }

    @Test
    fun aStretchThatIsNotInTheTextIsStillCarried() {
        // The model paraphrased instead of copying. This value cannot know that, and it is not its
        // judgement to make: it keeps the slice, the container tries indexOf, and only the underline
        // is lost. Dropping the remark here would be the wrong answer arrived at in the wrong place.
        val source = "Mina opin suomea"
        val note =
                Review.Note.of("the verb ending", listOf("opin suomea!"))

        val flagged: List<String> = note.flagged()
        Assert.assertEquals(listOf("opin suomea!"), flagged)
        Assert.assertEquals(
                "the UI decides that this cannot be placed", -1, source.indexOf(flagged.get(0)))
        Assert.assertEquals("the remark survives regardless", "the verb ending", note.remark())
    }

    @Test
    fun aNoteWithSeveralStretchesKeepsThemAllInOneValue() {
        val note = Review.Note.of("two endings", listOf("talossa", "kadulla"))

        Assert.assertEquals(listOf("talossa", "kadulla"), note.flagged())
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun theNotesAndTheirStretchesCannotBeChangedThroughTheLists() {
        val review =
                Review.of(
                        listOf(
                                Review.Note.of("one", listOf("a"))))

        try {
            (review.notes() as MutableList<String>).add("two")
            Assert.fail("a review's remarks must not be mutable from outside")
        } catch (expected: UnsupportedOperationException) {
            // as intended
        }
        try {
            (review.items() as MutableList<Review.Note>).add(Review.Note.of("two"))
            Assert.fail("a review's notes must not be mutable from outside")
        } catch (expected: UnsupportedOperationException) {
            // as intended
        }
        try {
            (review.items().get(0).flagged() as MutableList<String>).add("b")
            Assert.fail("a note's stretches must not be mutable from outside")
        } catch (expected: UnsupportedOperationException) {
            // as intended
        }
    }

    @Test
    fun aReviewIsAFewShortRemarksNotAnEssay() {
        Assert.assertEquals(2, Review.MAX_NOTES)
    }

    @Test
    fun oneNotePointsAtAWordOrAPhraseAndNotAtAPage() {
        // The prompt states this figure and the parser refuses a longer list, so the value is part of
        // the contract rather than an implementation detail.
        Assert.assertEquals(4, Review.MAX_FLAGGED)
    }

    @Test
    fun notesCompareByTheirWholeValue() {
        // A review crosses a cache, so its elements have to compare the way values do - and the
        // stretches are part of the value, not decoration on it.
        Assert.assertEquals(
                Review.Note.of("same", listOf("talossa")),
                Review.Note.of("same", listOf("talossa")))
        Assert.assertNotEquals(
                Review.Note.of("same", listOf("talossa")),
                Review.Note.of("same", listOf("kadulla")))
        Assert.assertNotEquals(
                Review.Note.of("same", listOf("talossa")), Review.Note.of("same"))
    }

    @Test
    fun notesAreAskedForOnlyWhenTheCallIsAllTheOwnersOwnWords() {
        Assert.assertTrue(
                "no reply fallback: the composed body is the owner's text",
                Review.onlyOwnWords(false, false))
        Assert.assertTrue(
                "a placed quote: the translatable part is exactly the owner's text",
                Review.onlyOwnWords(true, true))
    }

    @Test
    fun aQuoteThatCouldNotBePlacedIsNeverReviewed() {
        // Declared but unreadable: the whole composed body, quote included, is what gets translated,
        // and a note about somebody else's sentence must never be drawn under the owner's bubble.
        Assert.assertFalse(Review.onlyOwnWords(true, false))
    }
}
