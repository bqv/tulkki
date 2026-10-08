package uk.xa0.tulkki.translation

import org.junit.Assert
import org.junit.Test

/**
 * The one rule between a note and an underline: where the marks go, and what a miss costs.
 *
 * <p>A stretch is found with a plain {@code indexOf} against the text the owner typed, so the tests
 * that matter are the ones a view could never be asked to prove: a verbatim slice lands on the right
 * characters, a slice the model paraphrased or tidied produces no mark and still leaves its remark
 * standing, and two notes pointing at the same stretch do not produce two tap targets on it.
 */
class ReviewMarksTest {

    private val SOURCE = "Mina opin suomea koska haluan oppia sita."

    @Test
    fun anAbsentReviewUnderlinesNothing() {
        Assert.assertTrue(ReviewMarks.`in`(Review.absent(), SOURCE).isEmpty())
        Assert.assertTrue(ReviewMarks.`in`(null, SOURCE).isEmpty())
    }

    @Test
    fun aReviewWithNoNotesUnderlinesNothing() {
        Assert.assertTrue(ReviewMarks.`in`(Review.of(emptyList()), SOURCE).isEmpty())
    }

    @Test
    fun aNoteWithoutStretchesUnderlinesNothingAndKeepsItsRemark() {
        val review = Review.of(listOf(Review.Note.of("word order is fine")))

        Assert.assertTrue(ReviewMarks.`in`(review, SOURCE).isEmpty())
        Assert.assertEquals(listOf("word order is fine"), review.notes())
    }

    @Test
    fun aVerbatimSliceIsMarkedWhereItActuallyIs() {
        val review =
                Review.of(
                        listOf(
                                Review.Note.of("the partitive form", listOf("suomea"))))

        val marks = ReviewMarks.`in`(review, SOURCE)

        Assert.assertEquals(1, marks.size)
        val mark = marks.get(0)
        Assert.assertEquals(SOURCE.indexOf("suomea"), mark.start())
        Assert.assertEquals(SOURCE.indexOf("suomea") + "suomea".length, mark.end())
        Assert.assertEquals("suomea", mark.slice())
        Assert.assertEquals("the partitive form", mark.remark())
        Assert.assertEquals("suomea", SOURCE.substring(mark.start(), mark.end()))
    }

    @Test
    fun aSliceThatIsNotInTheTextCostsOnlyTheUnderline() {
        // The model paraphrased or fixed the spelling instead of copying. The remark is the note and
        // has to survive; the missing pointer is not a reason to hide it.
        val review =
                Review.of(
                        listOf(
                                Review.Note.of(
                                        "the verb ending is wrong",
                                        listOf("opin suomea!", "haluan oppia"))))

        val marks = ReviewMarks.`in`(review, SOURCE)

        Assert.assertEquals("only the slice that is in the text is marked", 1, marks.size)
        Assert.assertEquals("haluan oppia", marks.get(0).slice())
        Assert.assertEquals(
                "the remark is untouched by the miss",
                listOf("the verb ending is wrong"),
                review.notes())
    }

    @Test
    fun aParaphrasedSliceIsNotForcedOntoTheText() {
        // "opin suomea" is nearly in the source, and a search that normalised whitespace or dropped
        // punctuation would find it. Finding the wrong characters is worse than finding none: the
        // rule is exact, and a near miss is a miss.
        val review =
                Review.of(
                        listOf(
                                Review.Note.of("the ending", listOf("opin suomea!"))))

        Assert.assertTrue(ReviewMarks.`in`(review, SOURCE).isEmpty())
    }

    @Test
    fun aSliceIsSearchedExactlyAsItWasKept() {
        // Review.Note keeps a slice verbatim - spaces and all - exactly so this search stays exact.
        // A padded slice is not found, because a search that trimmed it first would underline
        // characters the model did not name; the same words unpadded are found where they sit.
        val padded =
                Review.of(
                        listOf(
                                Review.Note.of("the ending", listOf("  opin suomea  "))))
        Assert.assertTrue(ReviewMarks.`in`(padded, SOURCE).isEmpty())

        val exact =
                Review.of(
                        listOf(
                                Review.Note.of("the ending", listOf("opin suomea"))))
        Assert.assertEquals(1, ReviewMarks.`in`(exact, SOURCE).size)
    }

    @Test
    fun aSliceInADifferentCaseIsNotAFoundSlice() {
        // The prompt asks for the same characters and the same case; case folding here would be the
        // class of normalisation that makes an underline land on text the note is not about.
        val review =
                Review.of(
                        listOf(
                                Review.Note.of("the ending", listOf("Opin suomea"))))

        Assert.assertTrue(ReviewMarks.`in`(review, SOURCE).isEmpty())
    }

    @Test
    fun everyStretchOfANoteIsMarkedInTheModelsOrder() {
        val review =
                Review.of(
                        listOf(
                                Review.Note.of(
                                        "two endings", listOf("koska", "haluan"))))

        val marks = ReviewMarks.`in`(review, SOURCE)

        Assert.assertEquals(2, marks.size)
        Assert.assertEquals("koska", marks.get(0).slice())
        Assert.assertEquals("haluan", marks.get(1).slice())
        Assert.assertTrue("the model's order is kept", marks.get(0).start() < marks.get(1).start())
    }

    @Test
    fun marksFollowTheOrderOfTheNotes() {
        val review =
                Review.of(
                        listOf(
                                Review.Note.of("later in the message", listOf("sita")),
                                Review.Note.of("earlier in the message", listOf("Mina"))))

        val marks = ReviewMarks.`in`(review, SOURCE)

        Assert.assertEquals(2, marks.size)
        Assert.assertEquals("sita", marks.get(0).slice())
        Assert.assertEquals("Mina", marks.get(1).slice())
        Assert.assertTrue(marks.get(0).start() > marks.get(1).start())
    }

    @Test
    fun onlyTheFirstOccurrenceOfARepeatedStretchIsMarked() {
        val repeated = "talossa on kylma ja talossa on pimea"
        val review =
                Review.of(
                        listOf(
                                Review.Note.of("the inessive", listOf("talossa"))))

        val marks = ReviewMarks.`in`(review, repeated)

        Assert.assertEquals("one pointer, one underline", 1, marks.size)
        Assert.assertEquals(0, marks.get(0).start())
    }

    @Test
    fun twoNotesOnTheSameStretchAreOneTapTarget() {
        // Two span ranges crossing each other would make a tap on the overlap arbitrary. The first
        // note's underline stands, and the second remark is still a remark - it is in the review,
        // and the message's own review surface shows it.
        val review =
                Review.of(
                        listOf(
                                Review.Note.of("first remark", listOf("opin suomea")),
                                Review.Note.of("second remark", listOf("opin suomea"))))

        val marks = ReviewMarks.`in`(review, SOURCE)

        Assert.assertEquals(1, marks.size)
        Assert.assertEquals("first remark", marks.get(0).remark())
        Assert.assertEquals(
                listOf("first remark", "second remark"), review.notes())
    }

    @Test
    fun aStretchSwallowedByAnEarlierOneIsNotASecondTarget() {
        val review =
                Review.of(
                        listOf(
                                Review.Note.of("the phrase", listOf("opin suomea")),
                                Review.Note.of("the word", listOf("suomea"))))

        val marks = ReviewMarks.`in`(review, SOURCE)

        Assert.assertEquals(1, marks.size)
        Assert.assertEquals("the phrase", marks.get(0).remark())
        Assert.assertEquals("opin suomea", marks.get(0).slice())
    }

    @Test
    fun twoNotesThatDoNotOverlapBothGetTheirUnderline() {
        val review =
                Review.of(
                        listOf(
                                Review.Note.of("the first word", listOf("Mina")),
                                Review.Note.of("the last word", listOf("sita"))))

        Assert.assertEquals(2, ReviewMarks.`in`(review, SOURCE).size)
    }

    @Test
    fun aStretchAtEitherEndOfTheTextIsMarked() {
        val first =
                Review.of(
                        listOf(
                                Review.Note.of("the opening", listOf("Mina"))))
        val last =
                Review.of(
                        listOf(
                                Review.Note.of("the closing", listOf("sita."))))

        val opening = ReviewMarks.`in`(first, SOURCE).get(0)
        val closing = ReviewMarks.`in`(last, SOURCE).get(0)

        Assert.assertEquals(0, opening.start())
        Assert.assertEquals(4, opening.end())
        Assert.assertEquals(SOURCE.length - "sita.".length, closing.start())
        Assert.assertEquals(SOURCE.length, closing.end())
    }

    @Test
    fun offsetsAreCharacterUnitsSoASurrogatePairDoesNotShiftTheMark() {
        // The view sets spans by the same offsets indexOf returned, so a slice after an emoji has to
        // carry the code-unit offset rather than a count of code points.
        val text = "hyva \uD83D\uDC4D mutta vaarin"
        val review =
                Review.of(
                        listOf(
                                Review.Note.of("the word order", listOf("mutta"))))

        val mark = ReviewMarks.`in`(review, text).get(0)

        Assert.assertEquals("mutta", text.substring(mark.start(), mark.end()))
    }

    @Test
    fun anEmptySliceIsNotAMarkAtTheStartOfEverything() {
        val review =
                Review.of(
                        listOf(
                                Review.Note.of("built by hand", listOf(""))))

        Assert.assertTrue(ReviewMarks.`in`(review, SOURCE).isEmpty())
    }

    @Test
    fun thereIsNothingToMarkInNoText() {
        val review =
                Review.of(
                        listOf(
                                Review.Note.of("the ending", listOf("suomea"))))

        Assert.assertTrue(ReviewMarks.`in`(review, null).isEmpty())
        Assert.assertTrue(ReviewMarks.`in`(review, "").isEmpty())
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun theMarksCannotBeChangedFromOutside() {
        val review =
                Review.of(
                        listOf(
                                Review.Note.of("the ending", listOf("suomea"))))

        try {
            (ReviewMarks.`in`(review, SOURCE) as MutableList<ReviewMarks.Mark?>).add(null)
            Assert.fail("a mark list must not be mutable from outside")
        } catch (expected: UnsupportedOperationException) {
            // as intended
        }
    }

    @Test
    fun marksCompareByEverythingTheyCarry() {
        val review =
                Review.of(
                        listOf(
                                Review.Note.of("first", listOf("suomea")),
                                Review.Note.of("second", listOf("suomea"))))

        val only = ReviewMarks.`in`(review, SOURCE).get(0)

        Assert.assertEquals(only, ReviewMarks.`in`(review, SOURCE).get(0))
        Assert.assertNotEquals(only, ReviewMarks.Mark(only.start(), only.end(), "suomea", "other"))
        Assert.assertNotEquals(only, ReviewMarks.Mark(only.start(), only.end(), "other", "first"))
    }
}
