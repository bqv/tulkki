package uk.xa0.tulkki.translation

import org.junit.Assert
import org.junit.Test

/**
 * What a gloss sheet shows, and what it refuses to show.
 *
 * <p>Three rules earn their tests: an empty field is not a row (a line reading "Ending:" is a bug a
 * reader would report), a word the model called its own dictionary form with no ending and no case
 * says so rather than showing a lone meaning, and a failure carries a cover reason rather than a blank
 * sheet.
 */
class GlossContentTest {

    @Test
    fun aFullGlossBecomesItsLinesInOrder() {
        val content =
                GlossContent.of(
                        Gloss.of("talossa", "talo", "-ssa", "inessive", "in the house"))
        Assert.assertEquals(GlossContent.Kind.GLOSS, content.kind())
        Assert.assertEquals("talossa", content.word())
        Assert.assertEquals(4, content.rows().size)
        Assert.assertEquals(GlossContent.Field.DICTIONARY, content.rows().get(0).field)
        Assert.assertEquals("talo", content.rows().get(0).value)
        Assert.assertEquals(GlossContent.Field.ENDING, content.rows().get(1).field)
        Assert.assertEquals(GlossContent.Field.CASE, content.rows().get(2).field)
        Assert.assertEquals(GlossContent.Field.MEANING, content.rows().get(3).field)
        Assert.assertNull(content.failure())
    }

    @Test
    fun aBasicFormSaysSoWhereTheEndingAndCaseWouldHaveBeen() {
        // The owner's decision: a word that is already its basic form must say so rather than showing
        // a meaning and nothing else. The sentence is the row's label and there is no value behind it;
        // the container renders that as one line where Ending and Case would have been.
        val content =
                GlossContent.of(Gloss.of("talo", "talo", "", "", "house"))
        Assert.assertEquals(GlossContent.Kind.GLOSS, content.kind())
        Assert.assertEquals("talo", content.word())
        Assert.assertEquals(2, content.rows().size)
        Assert.assertEquals(GlossContent.Field.BASIC_FORM, content.rows().get(0).field)
        Assert.assertEquals("", content.rows().get(0).value)
        Assert.assertEquals(GlossContent.Field.MEANING, content.rows().get(1).field)
        Assert.assertEquals("house", content.rows().get(1).value)
    }

    @Test
    fun aCapitalisedBasicFormIsStillTheBasicForm() {
        // The signal is Gloss.isDictionaryForm(), which ignores case: "Talo" opening a sentence is the
        // same word "talo" a dictionary lists, and the aid must not stay silent about it.
        val content =
                GlossContent.of(Gloss.of("Talo", "talo", "", "", "house"))
        Assert.assertEquals(2, content.rows().size)
        Assert.assertEquals(GlossContent.Field.BASIC_FORM, content.rows().get(0).field)
    }

    @Test
    fun emptyEndingAndCaseWithADifferentDictionaryFormAreNotABasicForm() {
        // The model named a dictionary form that is not the tapped word and then named no ending: that
        // is an answer with a hole in it, not a word in its basic form. Saying otherwise would be
        // putting a claim in the model's mouth, so the silence stays silence.
        val content =
                GlossContent.of(Gloss.of("talossa", "talo", "", "", "in the house"))
        Assert.assertEquals(2, content.rows().size)
        Assert.assertEquals(GlossContent.Field.DICTIONARY, content.rows().get(0).field)
        Assert.assertEquals("talo", content.rows().get(0).value)
        Assert.assertEquals(GlossContent.Field.MEANING, content.rows().get(1).field)
        Assert.assertFalse(saysBasicForm(content))
    }

    @Test
    fun anEndingOrACaseTheModelNamedIsShownAndNoNoteIsAdded() {
        val ending =
                GlossContent.of(Gloss.of("talo", "talo", "no ending", "", "house"))
        Assert.assertEquals(2, ending.rows().size)
        Assert.assertEquals(GlossContent.Field.ENDING, ending.rows().get(0).field)
        Assert.assertEquals("no ending", ending.rows().get(0).value)
        Assert.assertEquals(GlossContent.Field.MEANING, ending.rows().get(1).field)
        Assert.assertFalse(saysBasicForm(ending))

        val grammaticalCase =
                GlossContent.of(Gloss.of("talo", "talo", "", "nominative, the basic form", "house"))
        Assert.assertEquals(2, grammaticalCase.rows().size)
        Assert.assertEquals(GlossContent.Field.CASE, grammaticalCase.rows().get(0).field)
        Assert.assertEquals(GlossContent.Field.MEANING, grammaticalCase.rows().get(1).field)
        Assert.assertFalse(saysBasicForm(grammaticalCase))
    }

    @Test
    fun aWordWhoseDictionaryFormTheModelNeverNamedMakesNoClaim() {
        // Nothing asserted the dictionary form, so there is nothing to conclude about the ending: the
        // meaning alone, exactly as before.
        val content = GlossContent.of(Gloss.of("talo", "", "", "", "house"))
        Assert.assertEquals(1, content.rows().size)
        Assert.assertEquals(GlossContent.Field.MEANING, content.rows().get(0).field)
    }

    @Test
    fun aWordThatIsItsOwnDictionaryFormDoesNotRepeatItself() {
        // The sheet's title is the word; a row saying the same thing is noise.
        val content =
                GlossContent.of(Gloss.of("talo", "talo", "-n", "genitive", "house"))
        Assert.assertEquals(3, content.rows().size)
        Assert.assertFalse(
                content.rows().stream()
                        .anyMatch { row -> row.field == GlossContent.Field.DICTIONARY })
    }

    /** Whether the sheet's own basic-form line is among the rows. */
    private fun saysBasicForm(content: GlossContent): Boolean {
        return content.rows().stream()
                .anyMatch { row -> row.field == GlossContent.Field.BASIC_FORM }
    }

    @Test
    fun aFailureSaysWhyInTheCoversVocabulary() {
        val noKey =
                GlossContent.failed("talo", HeldSend.HoldReason.NO_KEY)
        Assert.assertEquals(GlossContent.Kind.FAILED, noKey.kind())
        Assert.assertEquals(DisplayedBody.Cover.NO_KEY, noKey.failure())
        Assert.assertEquals("talo", noKey.word())
        Assert.assertTrue(noKey.rows().isEmpty())

        val capped =
                GlossContent.failed("talo", HeldSend.HoldReason.CAP_REACHED)
        Assert.assertEquals(DisplayedBody.Cover.CAP_REACHED, capped.failure())

        val unreachable =
                GlossContent.failed("talo", HeldSend.HoldReason.UNREACHABLE)
        Assert.assertEquals(DisplayedBody.Cover.UNREACHABLE, unreachable.failure())
    }

    @Test
    fun anUnusableGlossIsNothingRatherThanABlankSheet() {
        val content = GlossContent.of(Gloss.of("talo", "", "", "", ""))
        Assert.assertEquals(GlossContent.Kind.NOTHING, content.kind())
        Assert.assertTrue(content.rows().isEmpty())
    }

    @Test
    fun lookingUpCarriesTheWordAndNothingElse() {
        val content = GlossContent.lookingUp("talossa")
        Assert.assertEquals(GlossContent.Kind.LOOKING_UP, content.kind())
        Assert.assertEquals("talossa", content.word())
        Assert.assertTrue(content.rows().isEmpty())
        Assert.assertNull(content.failure())
    }

    @Test
    fun nothingToGlossIsItsOwnAnswer() {
        val content = GlossContent.nothing("42")
        Assert.assertEquals(GlossContent.Kind.NOTHING, content.kind())
        Assert.assertNull(content.failure())
    }
}
