package uk.xa0.tulkki.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.details.TagChip
import uk.xa0.tulkki.ui.list.PickerList
import uk.xa0.tulkki.ui.list.PickerRow
import uk.xa0.tulkki.ui.searchable.SearchableListScreen
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The three searchable list pickers' screenshot cells - the screens [ChooseContactActivity],
 * [BlocklistActivity] and [ShortcutActivity] compose, in both themes (docs/MIGRATION.md "Design:
 * the Compose UI" §7.1/§7.2, the harness the chrome, the welcome mark and the conversation screen
 * use).
 *
 * <p>**What the cells pin.** The chrome with each screen's own title and up arrow, and everything
 * the screen owns around the shared list: the search field (open for `ChooseContactActivity`, whose
 * `direct_search` flow opens it, closed for the other two), its hint and its clear affordance, and
 * the FAB's position and icon (shown for the picker and the block list, hidden for the shortcut
 * picker, exactly as each host leaves it).
 *
 * <p>**The rows are in the cells too.** They are a [uk.xa0.tulkki.ui.list.PickerList] now - the
 * platform `ListView` and `ListItemAdapter` and their `item_contact.xml` are deleted - so the cells
 * draw real [uk.xa0.tulkki.ui.list.PickerRow]s: the avatar plate, the display name, the JID, the
 * dynamic tag pill and the meta tag, the account line and the presence dot. The choose-contact
 * cells pass a checked key too, which is the `CHOICE_MODE_MULTIPLE` shade the deleted
 * `state_activated` selector resolved to; the screens' own substitutions still break the shell, and
 * the rows' do now as well.
 *
 * <p>**Why the chrome is in them.** These three screens draw no background of their own: the
 * `Scaffold` inside [TulkkiChrome] paints it, so composing the body alone would render a transparent
 * picture. The cell therefore composes the screen where production composes it, and the title is
 * read from the same resource the Activity reads.
 */
@PreviewTest
@Preview(
    name = "choose-contact-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun ChooseContactDarkScreenshot() =
    Fixture(
        darkTheme = true,
        title = stringResource(R.string.title_activity_choose_contact),
        searchOpen = true,
        fabVisible = true,
        checkedKeys = setOf("juliet@capulet.example"),
    )

@PreviewTest
@Preview(
    name = "choose-contact-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun ChooseContactLightScreenshot() =
    Fixture(
        darkTheme = false,
        title = stringResource(R.string.title_activity_choose_contact),
        searchOpen = true,
        fabVisible = true,
        checkedKeys = setOf("juliet@capulet.example"),
    )

@PreviewTest
@Preview(
    name = "blocklist-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun BlocklistDarkScreenshot() =
    Fixture(
        darkTheme = true,
        title = stringResource(R.string.title_activity_block_list),
        searchOpen = false,
        fabVisible = true,
    )

@PreviewTest
@Preview(
    name = "blocklist-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun BlocklistLightScreenshot() =
    Fixture(
        darkTheme = false,
        title = stringResource(R.string.title_activity_block_list),
        searchOpen = false,
        fabVisible = true,
    )

@PreviewTest
@Preview(
    name = "shortcut-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun ShortcutDarkScreenshot() =
    Fixture(
        darkTheme = true,
        title = stringResource(R.string.create_shortcut),
        searchOpen = false,
        fabVisible = false,
    )

@PreviewTest
@Preview(
    name = "shortcut-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun ShortcutLightScreenshot() =
    Fixture(
        darkTheme = false,
        title = stringResource(R.string.create_shortcut),
        searchOpen = false,
        fabVisible = false,
    )

@Composable
private fun Fixture(
    darkTheme: Boolean,
    title: String,
    searchOpen: Boolean,
    fabVisible: Boolean,
    checkedKeys: Set<String> = emptySet(),
) {
    TulkkiTheme(darkTheme = darkTheme) {
        TulkkiChrome(title = title, onUp = {}) {
            SearchableListScreen(
                list = {
                    PickerList(
                        rows = SampleRows,
                        onRowClick = {},
                        onRowLongClick = { _, _ -> },
                        onTagClick = {},
                        checkedKeys = checkedKeys,
                    )
                },
                searchOpen = searchOpen,
                query = "",
                onQueryChange = {},
                onSearchClose = {},
                onSearchSubmit = { false },
                fabIcon = R.drawable.ic_person_add_24dp,
                fabVisible = fabVisible,
                fabDescription = stringResource(R.string.add_contact),
                onFab = {},
            )
        }
    }
}

/**
 * Three rows the cells draw: a checked contact with a dynamic tag and an online meta tag, a blocked
 * contact with an account line, and a row with neither tag. No avatarable, so no `AndroidView`.
 */
private val SampleRows: List<PickerRow> =
    listOf(
        PickerRow(
            key = "juliet@capulet.example@0",
            jidValue = "juliet@capulet.example",
            displayName = "Juliet Capulet",
            jid = AnnotatedString("juliet@capulet.example"),
            tags = listOf(TagChip("Friends", Color(0xFF2E7D32).toArgb())),
            metaTag = TagChip("Online", Color(0xFF2E7D32).toArgb()),
            accountLine = null,
            presenceColor = 0xFF259B24.toInt(),
            avatarable = null,
            containerColor = null,
        ),
        PickerRow(
            key = "tybalt@capulet.example@1",
            jidValue = "tybalt@capulet.example",
            displayName = "Tybalt",
            jid = AnnotatedString("tybalt@capulet.example"),
            tags = listOf(TagChip("Family", Color(0xFF1565C0).toArgb())),
            metaTag = TagChip("Blocked", Color(0xFF424242).toArgb()),
            accountLine = "owner@example.org",
            presenceColor = null,
            avatarable = null,
            containerColor = null,
        ),
        PickerRow(
            key = "mercutio@verona.example@2",
            jidValue = "mercutio@verona.example",
            displayName = "Mercutio",
            jid = AnnotatedString("mercutio@verona.example"),
            tags = emptyList(),
            metaTag = null,
            accountLine = null,
            presenceColor = 0xFFFF9800.toInt(),
            avatarable = null,
            containerColor = null,
        ),
    )
