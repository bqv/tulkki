package uk.xa0.tulkki.ui

import org.junit.Assert
import org.junit.Test

/**
 * The two rules of a language row, on the JVM: what the picker offers, and that the sentinel is a mode
 * and never a language.
 *
 * **What this test no longer is.** Until `ui-4` part 2b it also held the settings tree's off state -
 * the two lists of keys that stayed and were removed, compared against `preferences_tulkki.xml`. That
 * file is deleted with the swap and the page is a function of the state, so the off state is now
 * asserted where it is decided, `uk.xa0.tulkki.ui.settings.SettingsPageTest`, against a frozen
 * contract; the three cells that lived here are gone with the tree they described rather than being
 * re-pointed at a tree that no longer exists.
 *
 * The two cells that stay are the ones the screen still asks `TulkkiSettingsRows` for: the picker's
 * own list, which the language dialog is built from, and the sentinel's rule, which every place that
 * would otherwise *name* a code consults.
 */
class TulkkiSettingsRowsTest {

    @Test
    fun theLanguagesPickTheSentinelFirstAndKeepAnUnknownStoredValue() {
        val values = TulkkiSettingsRows.pickerValues(null)
        Assert.assertEquals("the off state is the first thing the row offers", "none", values[0])
        Assert.assertEquals(
            "and it is offered once",
            1L,
            values.stream().filter { "none" == it }.count(),
        )
        Assert.assertTrue(values.contains("fi"))
        Assert.assertTrue(values.contains("en"))

        // A value set by something other than this screen stays selectable rather than being dropped
        // out of the list, and it still comes after the languages.
        val withKept = TulkkiSettingsRows.pickerValues(" zz ")
        Assert.assertEquals("zz", withKept[withKept.size - 1])
        Assert.assertEquals(
            "a stored sentinel is not added a second time",
            1L,
            TulkkiSettingsRows.pickerValues(" none ").stream().filter { "none" == it }.count(),
        )
    }

    @Test
    fun theSentinelIsAModeAndNeverALanguage() {
        Assert.assertTrue(TulkkiSettingsRows.isTheOffState("none"))
        Assert.assertTrue(TulkkiSettingsRows.isTheOffState("NONE"))
        Assert.assertTrue(TulkkiSettingsRows.isTheOffState(" none "))
        Assert.assertFalse(TulkkiSettingsRows.isTheOffState("en"))
        Assert.assertFalse(TulkkiSettingsRows.isTheOffState("fi"))
        Assert.assertFalse(TulkkiSettingsRows.isTheOffState(""))
        Assert.assertFalse(TulkkiSettingsRows.isTheOffState(null))
        // And it stays unknown to the languages list, which is what makes naming it the screen's own
        // job rather than the name path's.
        Assert.assertFalse(
            uk.xa0.tulkki.translation.TranslationLanguages.isKnown(
                uk.xa0.tulkki.translation.Interpreter.NONE,
            ),
        )
    }
}
