package uk.xa0.tulkki.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The search screen's screenshot cells - the whole screen [SearchActivity] composes, in both themes
 * (docs/MIGRATION.md "Design: the Compose UI" §7.1/§7.2).
 *
 * <p>**What the two cells pin.** The chrome with the manifest's title and its up arrow, the search
 * field on its hint, a day heading between two results, the sender and body lines of a result, the
 * matched word marked with the fixed-dim background the old `StylingHelper` span used, and the cover
 * caption a body that needed translating and did not get one draws instead of its raw text. The
 * rows are the same [SearchResultItem]s the Activity builds; only the models they would act on are
 * absent, so the menu's own drawing is what a substitution would break.
 *
 * <p>**Why the chrome is in them.** The screen draws no background of its own - the `Scaffold`
 * inside [TulkkiChrome] paints it - so composing the body alone would render a transparent picture.
 * The title and the cover caption are the same resources the Activity reads.
 */
@PreviewTest
@Preview(
    name = "search-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun SearchDarkScreenshot() = Fixture(darkTheme = true)

@PreviewTest
@Preview(
    name = "search-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun SearchLightScreenshot() = Fixture(darkTheme = false)

@Composable
private fun Fixture(darkTheme: Boolean) {
    val cover = stringResource(TranslationText.coverCaption(null))
    val rows =
        listOf(
            SearchResultItem.MessageRow(
                key = "juliet",
                title = "Juliet Capulet",
                body = "Shall I hear more, or shall I speak at this?",
                cover = false,
                actions = SearchAction.entries.toList(),
                message = null,
            ),
            SearchResultItem.DateSeparator(
                key = "separator",
                label = stringResource(R.string.yesterday),
            ),
            SearchResultItem.MessageRow(
                key = "romeo",
                title = "Romeo Montague",
                body = cover,
                cover = true,
                actions = listOf(SearchAction.ViewConversation, SearchAction.ShareWith),
                message = null,
            ),
        )
    TulkkiTheme(darkTheme = darkTheme) {
        TulkkiChrome(title = stringResource(R.string.search_messages), onUp = {}) {
            SearchScreen(
                rows = rows,
                query = "speak",
                hasSearch = true,
                highlighted = listOf("speak"),
                openWithKeyboard = false,
                onQueryChange = {},
                onAction = { _, _ -> },
            )
        }
    }
}
