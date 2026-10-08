package uk.xa0.tulkki.ui.startchat

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import uk.xa0.tulkki.data.model.ListItem
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.details.TagChip
import uk.xa0.tulkki.ui.list.PickerList
import uk.xa0.tulkki.ui.list.PickerRow
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The new-chat screen's screenshot cells, in both themes (docs/MIGRATION.md "Design: the Compose
 * UI" §7.1/§7.2, the harness the chrome, the welcome mark and the conversation screen use).
 *
 * <p>**What the cells pin.** The chrome around the screen - the title the manifest gave the XML bar
 * and the up arrow - and everything the conversion made Compose: the search field the old
 * `action_search` action view drew, with its `search_chats` hint, its search IME action and its
 * clear affordance; the dynamic tag row beside it, harmonised with the theme's primary exactly as
 * the deleted `TagsAdapter` row was; and the FAB, opened
 * onto the four items of the deleted `start_conversation_fab_submenu`, with their icons and labels.
 *
 * <p>**The two pages' rows are in the cells too.** They are `PickerList`s now - `ListView`,
 * `ListItemAdapter` and `item_contact.xml` are deleted - so the contacts page draws real rows: the
 * avatar plate, the display name, the JID, the dynamic tag pill and the account line.
 *
 * <p>**Why the chrome is in them.** The screen draws no background of its own - the `Scaffold`
 * inside [TulkkiChrome] paints it - so composing the body alone would render a transparent picture.
 * The cell therefore composes the screen where production composes it, and the title is read from
 * the same resource the Activity reads.
 */
@PreviewTest
@Preview(
    name = "start-chat-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun StartChatDarkScreenshot() = Fixture(darkTheme = true)

@PreviewTest
@Preview(
    name = "start-chat-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun StartChatLightScreenshot() = Fixture(darkTheme = false)

@Composable
private fun Fixture(darkTheme: Boolean) {
    TulkkiTheme(darkTheme = darkTheme) {
        TulkkiChrome(title = stringResource(R.string.title_activity_new_chat), onUp = {}) {
            StartChatScreen(
                searchOpen = true,
                query = "",
                tags = listOf(ListItem.Tag("Friends"), ListItem.Tag("Channel")),
                showTags = true,
                contactsList = { PickerList(rows = ContactRows, onRowClick = {}, onRowLongClick = { _, _ -> }, onTagClick = {}) },
                conferencesList = { PickerList(rows = ConferenceRows, onRowClick = {}, onRowLongClick = { _, _ -> }, onTagClick = {}) },
                fabActions = StartChatAction.entries,
                fabOpen = true,
                events = NoEvents,
            )
        }
    }
}

/** The contacts page's rows: a tagged contact, and one with an account line and no tag. */
private val ContactRows: List<PickerRow> =
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
            key = "romeo@montague.example@1",
            jidValue = "romeo@montague.example",
            displayName = "Romeo Montague",
            jid = AnnotatedString("romeo@montague.example"),
            tags = emptyList(),
            metaTag = null,
            accountLine = "owner@example.org",
            presenceColor = null,
            avatarable = null,
            containerColor = null,
        ),
    )

/** The group-chats page's rows: a bookmark with a JID and no presence dot. */
private val ConferenceRows: List<PickerRow> =
    listOf(
        PickerRow(
            key = "room@conference.example@0",
            jidValue = "room@conference.example",
            displayName = "The Tavern",
            jid = AnnotatedString("room@conference.example"),
            tags = listOf(TagChip("Channel", Color(0xFF1565C0).toArgb())),
            metaTag = null,
            accountLine = null,
            presenceColor = null,
            avatarable = null,
            containerColor = null,
        )
    )

/** A screen with no host: every gesture is a no-op in a cell. */
private val NoEvents =
    object : StartChatEvents {
        override fun onQueryChange(query: String) {}

        override fun onSearchClose() {}

        override fun onSearchSubmit(page: Int) {}

        override fun onTagChip(query: String) {}

        override fun onFabToggle(open: Boolean) {}

        override fun onFabAction(action: StartChatAction) {}
    }
