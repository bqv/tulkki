package uk.xa0.tulkki.ui.conversation

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.translation.GlossContent
import uk.xa0.tulkki.ui.R

/**
 * The reading aid's labels: one string per field, and the same strings the deleted `GlossPopup.labelFor`
 * named.
 *
 * <p>`GlossContent` deliberately carries no resource, so the field-to-word mapping is a rule of its own
 * and this is the cell that keeps a renamed or reordered field from silently drawing the wrong label -
 * the one mistake a screenshot would show but not explain.
 */
class GlossFieldsTest {

    @Test
    fun everyFieldGetsItsOwnLabel() {
        val labels = GlossContent.Field.entries.map { GlossFields.label(it) }
        Assert.assertEquals(
            "two fields sharing a label would draw the same word twice",
            GlossContent.Field.entries.size,
            labels.toSet().size,
        )
    }

    @Test
    fun theLabelsAreTheOnesTheTreeUsed() {
        Assert.assertEquals(
            R.string.tulkki_gloss_dictionary,
            GlossFields.label(GlossContent.Field.DICTIONARY),
        )
        Assert.assertEquals(R.string.tulkki_gloss_ending, GlossFields.label(GlossContent.Field.ENDING))
        Assert.assertEquals(R.string.tulkki_gloss_case, GlossFields.label(GlossContent.Field.CASE))
        Assert.assertEquals(
            "the basic form is a whole sentence, not a label beside a value",
            R.string.tulkki_gloss_basic_form,
            GlossFields.label(GlossContent.Field.BASIC_FORM),
        )
        Assert.assertEquals(R.string.tulkki_gloss_meaning, GlossFields.label(GlossContent.Field.MEANING))
    }
}
