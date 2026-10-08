package uk.xa0.tulkki.ui.settings

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.translation.PromptBook

/**
 * `ui-4` part 2a's cells: the page's two routes, and the one table that says what tapping a row does.
 *
 * <p>Why this is JVM and not the screen: the screen itself is a Composable, and this module hosts no
 * Activity, no Fragment and no Robolectric - so what is pinned here is the deciding the screen does
 * *before* it draws: which of `SettingsPage`'s rows belong to the prompts sub-screen, and which editor
 * each row opens. A row whose key grows a new spelling therefore fails here rather than rendering as a
 * row that taps into nothing.
 */
class SettingsNavigationTest {

    @Test
    fun theTwoRoutesPartitionThePageAndKeepTheTreesOrder() {
        val all = SettingsPage.rows(state(interpreter = true))
        val page = SettingsNavigation.rowsFor(SettingsRoute.PAGE, all)
        val prompts = SettingsNavigation.rowsFor(SettingsRoute.PROMPTS, all)

        Assert.assertEquals("every row is on exactly one route", all.size, page.size + prompts.size)
        Assert.assertEquals("and none is on both", emptyList<SettingsRow>(), page.filter { it in prompts })
        val runAt = all.indexOfFirst { it in prompts }
        Assert.assertTrue("the sub-screen's rows are the container's own children", runAt > 0)
        Assert.assertEquals(
            "and they are one contiguous run, where the tree nests them",
            all.slice(runAt until runAt + prompts.size),
            prompts,
        )
        Assert.assertEquals(
            "the page is everything else, in the tree's order",
            all.filterIndexed { at, _ -> at !in runAt until runAt + prompts.size },
            page,
        )
    }

    @Test
    fun theContainerStaysOnThePageAndItsChildrenMoveToTheSubScreen() {
        val all = SettingsPage.rows(state(interpreter = true))
        val prompts = SettingsNavigation.rowsFor(SettingsRoute.PROMPTS, all)
        Assert.assertEquals(
            "the sub-screen is the three instructions",
            listOf("tulkki_prompt_translate", "tulkki_prompt_review", "tulkki_prompt_gloss"),
            prompts.mapNotNull { it.key },
        )
        val page = SettingsNavigation.rowsFor(SettingsRoute.PAGE, all)
        Assert.assertTrue(
            "the container row keeps something to do: it opens that sub-screen",
            page.any { it.key == "tulkki_prompts" },
        )
        for (key in prompts.mapNotNull { it.key }) {
            Assert.assertFalse("$key is not drawn on both", page.any { it.key == key })
        }
    }

    @Test
    fun everyRowKnowsWhatTappingItDoes() {
        val editors = SettingsPage.rows(state(interpreter = true)).map { SettingsEditors.editorFor(it) }
        Assert.assertFalse("an unrecognised row is a red test, not a row that does nothing", editors.contains(RowEditor.NONE))
        for (editor in
            listOf(
                RowEditor.TOGGLE,
                RowEditor.TEXT,
                RowEditor.NUMBER,
                RowEditor.LANGUAGE,
                RowEditor.PROMPT,
                RowEditor.SCREEN,
                RowEditor.HEADER,
            )) {
            Assert.assertTrue("$editor is exercised by the page", editors.contains(editor))
        }
    }

    @Test
    fun everySwitchRowHasAValueToDrawAndNoOtherRowDoes() {
        val state = state(interpreter = true)
        for (row in SettingsPage.rows(state)) {
            if (SettingsEditors.editorFor(row) == RowEditor.TOGGLE) {
                Assert.assertNotNull(
                    "${row.key} is drawn as a switch and must have a value",
                    SettingsEditors.checked(state, row.key),
                )
            } else {
                Assert.assertNull("${row.key} is not a switch", SettingsEditors.checked(state, row.key))
            }
        }
    }

    private fun state(interpreter: Boolean): TulkkiSettingsState =
        TulkkiSettingsState(
            interpreter = interpreter,
            appLanguage = "fi",
            studyLanguage = "en",
            keyPresent = true,
            keyStorageAvailable = true,
            baseUrl = "https://api.deepseek.com",
            dailyTokenCap = 100_000,
            revealSuggestionFirst = false,
            showSecondHalf = true,
            showConcealedOriginal = true,
            concealOwnSecondHalf = false,
            showBlurredEnglish = true,
            unblurEnglishOnTap = true,
            showEnglishRetranslation = false,
            promptEdited = mapOf(PromptBook.Kind.TRANSLATE to false, PromptBook.Kind.REVIEW to false, PromptBook.Kind.GLOSS to false),
            promptLength = mapOf(PromptBook.Kind.TRANSLATE to 10, PromptBook.Kind.REVIEW to 20, PromptBook.Kind.GLOSS to 5),
        )
}
