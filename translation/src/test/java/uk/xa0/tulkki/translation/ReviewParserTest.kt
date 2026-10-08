package uk.xa0.tulkki.translation

import org.junit.Assert
import org.junit.Test

/**
 * Reading the model's notes on the owner's own wording, strictly.
 *
 * <p>The failure direction is the point, and it is the same one {@link GlossParserTest} pins: a
 * chatty answer, a missing field, a wrong type, a string where the note object belongs, a blank
 * remark or a blank slice, or more notes or slices than the shape asks for has to come back as
 * <em>no review</em> rather than as half of one shown to a learner as if it were the whole remark.
 * "Absent" is a value here, so nothing downstream has to guess what a half-filled answer meant.
 *
 * <p>The positive direction is narrower than it looks. Each note carries the stretch of the owner's
 * text it is about, and that stretch has to be usable <strong>character for character</strong>: the
 * container finds it with {@code indexOf} against what the owner typed, so the parse may not trim it,
 * fold its case or otherwise improve it. The parser cannot check that a slice is really in the text -
 * it has never seen the text - and it must not try: a slice that is not there costs the underline,
 * which is the container's decision, and never the remark.
 */
class ReviewParserTest {

    /** The text a slice would have been copied out of, for the tests that check findability. */
    private val SOURCE = "Mina opin suomea ja haluan puhua sinulle"

    /** A JSON string literal, so a test can hand in a slice with quotes or backslashes in it. */
    private fun quote(text: String): String {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    /** The answer the request asks for: one object holding the note objects. */
    private fun answer(vararg notes: String): String {
        return "{\"notes\":[" + java.lang.String.join(",", *notes) + "]}"
    }

    /** One note object, with raw JSON handed in so a test can break it on purpose. */
    private fun note(remark: String, vararg rawSlices: String): String {
        return "{\"note\":" +
                quote(remark) +
                ",\"flagged\":[" +
                java.lang.String.join(",", *rawSlices) +
                "]}"
    }

    /** One note object that copies its slices verbatim into the JSON. */
    private fun noteOf(remark: String, vararg slices: String): String {
        val raw = Array(slices.size) { "" }
        for (i in raw.indices) {
            raw[i] = quote(slices[i])
        }
        return note(remark, *raw)
    }

    // ---- the shape: a remark, and the stretch of the owner's text it is about ----

    @Test
    fun aNoteCarriesTheStretchOfTheOwnersTextItIsAbout() {
        val review = ReviewParser.parse(answer(noteOf("the inessive ending is -ssa", "talossa")))!!

        Assert.assertNotNull(review)
        Assert.assertTrue(review.isPresent())
        Assert.assertEquals(1, review.items().size)
        val parsed = review.items().get(0)
        Assert.assertEquals("the inessive ending is -ssa", parsed.remark())
        Assert.assertEquals(listOf("talossa"), parsed.flagged())
    }

    @Test
    fun aNotePointsAtAWordOrAtAPhraseOrAtSeveralStretches() {
        val review =
                ReviewParser.parse(
                        answer(
                                noteOf("one ending", "talossa"),
                                noteOf("two mistakes", "opin", "haluan")))!!

        Assert.assertNotNull(review)
        Assert.assertEquals(listOf("talossa"), review.items().get(0).flagged())
        Assert.assertEquals(listOf("opin", "haluan"), review.items().get(1).flagged())
    }

    @Test
    fun aNoteWithoutAFlaggedFieldIsANoteWithoutAnUnderline() {
        // The remark is the note and the stretch is a pointer into the text. A model that answers the
        // remark and forgets the pointer still gets its remark read; only the underline is missing.
        val review = ReviewParser.parse(answer("{\"note\":\"word order is fine here\"}"))!!

        Assert.assertNotNull(review)
        Assert.assertTrue(review.isPresent())
        Assert.assertEquals(listOf("word order is fine here"), review.notes())
        Assert.assertTrue(review.items().get(0).flagged().isEmpty())
    }

    @Test
    fun anEmptyFlaggedListIsAlsoANoteWithoutAnUnderline() {
        val review = ReviewParser.parse(answer(noteOf("the input as a whole")))!!

        Assert.assertNotNull(review)
        Assert.assertTrue(review.items().get(0).flagged().isEmpty())
    }

    // ---- the stretch is kept verbatim, because that is what makes it findable ----

    @Test
    fun theStretchIsNotTrimmed() {
        // Trimming is exactly what would stop this matching the owner's text, and the container is
        // going to look for it there. The remark may be tidied; the stretch may not.
        val review = ReviewParser.parse(answer(note("  the ending  ", quote(" talossa "))))!!

        Assert.assertNotNull(review)
        Assert.assertEquals("the ending", review.notes().get(0))
        Assert.assertEquals(listOf(" talossa "), review.items().get(0).flagged())
    }

    @Test
    fun theStretchKeepsItsCaseAndItsPunctuation() {
        val review = ReviewParser.parse(answer(noteOf("word order", "Mina opin", "suomea!")))!!

        Assert.assertNotNull(review)
        Assert.assertEquals(listOf("Mina opin", "suomea!"), review.items().get(0).flagged())
    }

    @Test
    fun aStretchThatIsNotInTheSourceSurvivesTheParse() {
        // A paraphrase instead of a copy. The parser has never seen the source and cannot know, and
        // this is not its judgement to make: the value survives so the container can try indexOf and
        // drop only the underline. A parser that dropped the note here would lose the remark, and one
        // that silently tidied the slice into a match would put the underline in the wrong place.
        val review = ReviewParser.parse(answer(noteOf("the verb ending", "opin suomea!")))!!

        Assert.assertNotNull(review)
        val slice = review.items().get(0).flagged().get(0)
        Assert.assertEquals("opin suomea!", slice)
        Assert.assertEquals("the UI decides that this cannot be placed", -1, SOURCE.indexOf(slice))
        Assert.assertEquals("the remark survives", "the verb ending", review.notes().get(0))
    }

    @Test
    fun aStretchMayBeTheWholeInputOrOneCharacter() {
        val review =
                ReviewParser.parse(answer(noteOf("as a whole", SOURCE), noteOf("just the s", "s")))!!

        Assert.assertNotNull(review)
        val whole = review.items().get(0).flagged().get(0)
        Assert.assertEquals(SOURCE, whole)
        Assert.assertEquals("the whole input is found at its start", 0, SOURCE.indexOf(whole))
        Assert.assertEquals("s", review.items().get(1).flagged().get(0))
        Assert.assertTrue("a single character is findable too", SOURCE.indexOf("s") > 0)
    }

    // ---- the strict refusals: anything that is not the shape is a stated absence ----

    @Test
    fun aFlaggedValueOfTheWrongTypeIsNothing() {
        // Present and not an array is a wrong type, null included - the same refusal the notes field
        // itself gets. Only the field being absent at all means "no underline".
        Assert.assertNull(
                ReviewParser.parse("{\"notes\":[{\"note\":\"x\",\"flagged\":\"talossa\"}]}"))
        Assert.assertNull(ReviewParser.parse("{\"notes\":[{\"note\":\"x\",\"flagged\":null}]}"))
        Assert.assertNull(ReviewParser.parse("{\"notes\":[{\"note\":\"x\",\"flagged\":0}]}"))
        Assert.assertNull(
                ReviewParser.parse("{\"notes\":[{\"note\":\"x\",\"flagged\":{\"a\":\"b\"}}]}"))
    }

    @Test
    fun aSliceThatIsNotAStringIsNothing() {
        Assert.assertNull(ReviewParser.parse(answer(note("x", "0"))))
        Assert.assertNull(ReviewParser.parse(answer(note("x", "true"))))
        Assert.assertNull(ReviewParser.parse(answer(note("x", "null"))))
        Assert.assertNull(ReviewParser.parse(answer(note("x", "[\"talossa\"]"))))
    }

    @Test
    fun aBlankSliceIsNothing() {
        // A blank slice cannot underline anything, and "" would match at position 0 of any text. The
        // model has an empty list for "no stretch at all", so a blank is an answer that ignored the
        // shape rather than a slice to carry.
        Assert.assertNull(ReviewParser.parse(answer(note("x", quote("")))))
        Assert.assertNull(ReviewParser.parse(answer(note("x", quote("   ")))))
        Assert.assertNull(ReviewParser.parse(answer(note("x", quote("talossa"), quote("   ")))))
    }

    @Test
    fun moreStretchesThanTheShapeAsksForIsNothing() {
        val full = Array(Review.MAX_FLAGGED) { "" }
        for (i in full.indices) {
            full[i] = quote("s" + i)
        }
        Assert.assertNotNull(ReviewParser.parse(answer(note("x", *full))))

        val tooMany = Array(Review.MAX_FLAGGED + 1) { "" }
        for (i in tooMany.indices) {
            tooMany[i] = quote("s" + i)
        }
        Assert.assertNull(ReviewParser.parse(answer(note("x", *tooMany))))
    }

    @Test
    fun theOldShapeOfBareStringsIsNothing() {
        // The contract changed: a note is an object now. An answer to the old contract carries no
        // positions, so reading it as a note without an underline would hide the fact that the model
        // is answering the prompt that was replaced.
        Assert.assertNull(ReviewParser.parse("{\"notes\":[\"the ending is -ssa\"]}"))
    }

    @Test
    fun aNoteThatIsNotAnObjectIsNothing() {
        Assert.assertNull(ReviewParser.parse("{\"notes\":[0]}"))
        Assert.assertNull(ReviewParser.parse("{\"notes\":[true]}"))
        Assert.assertNull(ReviewParser.parse("{\"notes\":[null]}"))
        Assert.assertNull(ReviewParser.parse("{\"notes\":[[\"talossa\"]]}"))
    }

    @Test
    fun aNoteWithoutARemarkIsNothing() {
        Assert.assertNull(ReviewParser.parse("{\"notes\":[{\"flagged\":[\"talossa\"]}]}"))
        Assert.assertNull(ReviewParser.parse("{\"notes\":[{\"note\":null}]}"))
        Assert.assertNull(ReviewParser.parse("{\"notes\":[{\"note\":0}]}"))
        Assert.assertNull(ReviewParser.parse("{\"notes\":[{\"note\":\"   \"}]}"))
        Assert.assertNull(ReviewParser.parse("{\"notes\":[{\"note\":\"\"}]}"))
    }

    @Test
    fun aBlankRemarkIsNothingEvenWhenTheStretchIsGood() {
        // Not skipped: dropping it would show the remaining notes as if they were the whole review.
        Assert.assertNull(
                ReviewParser.parse(answer(noteOf("  ", "talossa"), noteOf("a real note", "opin"))))
    }

    @Test
    fun moreNotesThanTheShapeAsksForIsNothing() {
        Assert.assertNotNull(
                ReviewParser.parse(
                        answer(
                                noteOf("one", "a"),
                                noteOf("two", "b"))))
        Assert.assertNull(
                ReviewParser.parse(
                        answer(
                                noteOf("one", "a"),
                                noteOf("two", "b"),
                                noteOf("three", "c"))))
    }

    @Test
    fun anUnknownFieldInsideANoteIsIgnored() {
        // The known fields are read and the rest is not invented meaning for. The same treatment the
        // translation's own lang/text get when the notes are read out of the same object.
        val review =
                ReviewParser.parse(
                        "{\"notes\":[{\"note\":\"x\",\"severity\":\"high\",\"flagged\":[\"a\"]}]}")!!

        Assert.assertNotNull(review)
        Assert.assertEquals(listOf("a"), review.items().get(0).flagged())
    }

    // ---- the rest of the contract, unchanged ----

    @Test
    fun theTranslationFieldsAreIgnoredAndOnlyNotesAreRead() {
        val review =
                ReviewParser.parse(
                        "{\"lang\":\"fi\",\"text\":\"I am learning Finnish\"," +
                                "\"notes\":[{\"note\":\"word order is fine here\"," +
                                "\"flagged\":[\"Mina opin\"]}]}")!!

        Assert.assertNotNull(review)
        Assert.assertEquals(listOf("word order is fine here"), review.notes())
        Assert.assertEquals(listOf("Mina opin"), review.items().get(0).flagged())
    }

    @Test
    fun remarksAreTrimmed() {
        val review = ReviewParser.parse(answer(noteOf("  the partitive is -a  ", "kaksi")))!!

        Assert.assertNotNull(review)
        Assert.assertEquals(listOf("the partitive is -a"), review.notes())
    }

    @Test
    fun anEmptyListIsAReviewThatSaysNothing() {
        val review = ReviewParser.parse(answer())!!

        Assert.assertNotNull(review)
        Assert.assertTrue(review.isPresent())
        Assert.assertTrue(review.notes().isEmpty())
        Assert.assertTrue(review.items().isEmpty())
    }

    @Test
    fun anAnswerWithoutNotesIsNotAReview() {
        Assert.assertNull(ReviewParser.parse("{\"lang\":\"fi\",\"text\":\"Hei\"}"))
    }

    @Test
    fun proseInsteadOfJsonIsNothingRatherThanAGuess() {
        Assert.assertNull(ReviewParser.parse("Sure! Your Finnish is quite good."))
    }

    @Test
    fun jsonThatIsNotAnObjectIsNothing() {
        Assert.assertNull(ReviewParser.parse("[\"a note\"]"))
        Assert.assertNull(ReviewParser.parse("\"a note\""))
    }

    @Test
    fun notesThatAreNotAListAreNothing() {
        Assert.assertNull(ReviewParser.parse("{\"notes\":\"the ending is -ssa\"}"))
        Assert.assertNull(ReviewParser.parse("{\"notes\":null}"))
        Assert.assertNull(ReviewParser.parse("{\"notes\":{}}"))
    }

    @Test
    fun anEmptyOrAbsentBodyIsNothing() {
        Assert.assertNull(ReviewParser.parse(null))
        Assert.assertNull(ReviewParser.parse(""))
        Assert.assertNull(ReviewParser.parse("   "))
    }

    @Test
    fun aStoredReviewGoesBackThroughTheSameParse() {
        // What ReviewStore keeps is exactly this shape - ReviewStoreTest cannot exist, because the
        // store needs an Android Context - so this is the pin that the row written and the row read
        // are the same document, slices and all.
        val stored =
                Review.of(
                        listOf(
                                Review.Note.of(
                                        "use the partitive after 'kaksi'",
                                        listOf("kaksi", " talossa "))))
        val readBack =
                ReviewParser.parse(
                        "{\"notes\":[{\"note\":\"use the partitive after 'kaksi'\"," +
                                "\"flagged\":[\"kaksi\",\" talossa \"]}]}")!!

        Assert.assertNotNull(readBack)
        Assert.assertEquals(stored.notes(), readBack.notes())
        Assert.assertEquals(stored.items(), readBack.items())
    }

    @Test
    fun theRemarksAndTheStretchesAreNotTranslatedOrMangled() {
        val review =
                ReviewParser.parse(
                        answer(
                                noteOf(
                                        "'talossa' on inessiivi - sisällä talossa",
                                        "talossa"),
                                noteOf("hyvä!", "Mina")))!!

        Assert.assertNotNull(review)
        val notes = review.notes()
        Assert.assertEquals("'talossa' on inessiivi - sisällä talossa", notes.get(0))
        Assert.assertEquals("hyvä!", notes.get(1))
        Assert.assertEquals(listOf("talossa"), review.items().get(0).flagged())
        Assert.assertEquals(listOf("Mina"), review.items().get(1).flagged())
    }

    @Test
    fun aSliceWithQuotesOrBackslashesSurvivesTheJsonToo() {
        val review = ReviewParser.parse(answer(noteOf("the quote is unmatched", "\"talossa")))!!

        Assert.assertNotNull(review)
        Assert.assertEquals(listOf("\"talossa"), review.items().get(0).flagged())
    }
}
