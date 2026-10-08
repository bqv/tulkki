package uk.xa0.tulkki.translation

import org.junit.Assert
import org.junit.Test

/**
 * Reading the model's gloss answer, strictly.
 *
 * <p>The failure direction is the point: a chatty answer, a missing field or a number where a string
 * belongs has to come back as "no gloss" so the sheet can say a lookup failed, rather than as half an
 * answer shown to a learner as if it were a dictionary.
 */
class GlossParserTest {

    @Test
    fun aFullAnswerBecomesAGloss() {
        val gloss =
                GlossParser.parse(
                        "talossa",
                        "{\"dictionary\":\"talo\",\"ending\":\"-ssa\",\"case\":\"inessive\","
                                + "\"gloss\":\"in the house\"}")!!
        Assert.assertNotNull(gloss)
        Assert.assertEquals("talossa", gloss.surface())
        Assert.assertEquals("talo", gloss.dictionary())
        Assert.assertEquals("-ssa", gloss.ending())
        Assert.assertEquals("inessive", gloss.grammaticalCase())
        Assert.assertEquals("in the house", gloss.meaning())
        Assert.assertTrue(gloss.isUsable())
    }

    @Test
    fun aWordWithNoEndingOrCaseIsStillAGloss() {
        val gloss =
                GlossParser.parse(
                        "talo", "{\"dictionary\":\"talo\",\"ending\":\"\",\"case\":\"\",\"gloss\":\"house\"}")!!
        Assert.assertNotNull(gloss)
        Assert.assertEquals("", gloss.ending())
        Assert.assertEquals("", gloss.grammaticalCase())
        Assert.assertTrue(gloss.isDictionaryForm())
    }

    @Test
    fun missingFieldsAreNotGuessedAt() {
        // No dictionary form and no meaning: an empty answer wearing a gloss's clothes.
        Assert.assertNull(GlossParser.parse("talo", "{\"ending\":\"-ssa\",\"case\":\"inessive\"}"))
        Assert.assertNull(GlossParser.parse("talo", "{}"))
    }

    @Test
    fun aMeaningAloneIsStillSomethingToShow() {
        val gloss = GlossParser.parse("talo", "{\"gloss\":\"house\"}")!!
        Assert.assertNotNull(gloss)
        Assert.assertTrue(gloss.isUsable())
        Assert.assertEquals("", gloss.dictionary())
    }

    @Test
    fun proseOrMalformedJsonIsAnUnusableAnswer() {
        Assert.assertNull(GlossParser.parse("talo", "The word talo means house."))
        Assert.assertNull(GlossParser.parse("talo", "{\"dictionary\": \"talo\","))
        Assert.assertNull(GlossParser.parse("talo", "[{\"gloss\":\"house\"}]"))
        Assert.assertNull(GlossParser.parse("talo", "\"house\""))
        Assert.assertNull(GlossParser.parse("talo", ""))
        Assert.assertNull(GlossParser.parse("talo", null))
    }

    @Test
    fun aNumberWhereAStringBelongsIsNotCoerced() {
        // "ending": 0 must not read as the string "0"; the field is empty and the gloss stands on
        // whatever else it has.
        val gloss =
                GlossParser.parse(
                        "talo",
                        "{\"dictionary\":\"talo\",\"ending\":0,\"case\":true,\"gloss\":\"house\"}")!!
        Assert.assertNotNull(gloss)
        Assert.assertEquals("", gloss.ending())
        Assert.assertEquals("", gloss.grammaticalCase())
        Assert.assertEquals("house", gloss.meaning())
    }

    @Test
    fun extraFieldsAndWhitespaceAreHarmless() {
        val gloss =
                GlossParser.parse(
                        "talo",
                        "  {\"dictionary\": \"  talo  \", \"gloss\": \"house\", \"note\": \"extra\"}  ")!!
        Assert.assertNotNull(gloss)
        Assert.assertEquals("talo", gloss.dictionary())
        Assert.assertEquals("house", gloss.meaning())
    }
}
