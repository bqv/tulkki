package uk.xa0.tulkki.ui

import android.app.PendingIntent
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.common.base.Strings
import java.util.ArrayList
import uk.xa0.tulkki.data.FileBackend
import uk.xa0.tulkki.data.R as DataR
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Conversational
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.translation.ConversationName
import uk.xa0.tulkki.translation.DisplayedBody
import uk.xa0.tulkki.translation.ShareText
import uk.xa0.tulkki.translation.TranslationSettings
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import uk.xa0.tulkki.ui.host.UiHost
import uk.xa0.tulkki.ui.interfaces.OnSearchResultsAvailable
import uk.xa0.tulkki.ui.util.ChangeWatcher
import uk.xa0.tulkki.ui.util.DateSeparator
import uk.xa0.tulkki.ui.util.PendingItem
import uk.xa0.tulkki.ui.util.ShareUtil
import uk.xa0.tulkki.ui.util.SoftKeyboardUtils
import uk.xa0.tulkki.ui.utils.StylingHelper
import uk.xa0.tulkki.ui.utils.UIHelper
import uk.xa0.tulkki.xmpp.utils.FtsUtils
import uk.xa0.tulkki.xml.Element

/**
 * The message search: a term, and the results it matched.
 *
 * <p>**The layout is gone.** `activity_search.xml` held a toolbar and a `ListView`; the file is
 * deleted, the bar is the shared chrome ([uk.xa0.tulkki.ui.chrome.TulkkiChrome]) now, and the body
 * is [SearchScreen]. `setSupportActionBar` and `Activities.setStatusAndNavigationBarColors` went
 * with the bar, and the `R.menu.activity_search` inflation went with the toolbar's action view: its
 * one item was the search field, which [SearchScreen] draws itself, and the `actionview_search.xml`
 * the other three menus shared is deleted with them. `R.menu.search_result_context` is deleted too, its
 * five items are the row's own menu now, and `SearchResultAdapter` - this screen's only row
 * renderer - goes with them.
 *
 * <p>**The rows are Compose items now.** Every result is one [SearchResultItem.MessageRow] and every
 * `DateSeparator` row is one [SearchResultItem.DateSeparator]; the list is a `LazyColumn` with
 * `reverseLayout`, which is the deleted `ListView`'s `stackFromBottom` - the newest result sits at
 * the bottom and a short list hangs from there. The two backgrounds are the drawables the old
 * `changeBackground` swapped in: the magnifier while nothing is searched, the cancel mark while a
 * term has no results, and the plain surface once results arrive.
 *
 * <p>**The term, the branch and the quote are unchanged.** [onQueryChanged] is the old
 * `afterTextChanged`, `ChangeWatcher` still gates a repeated term, [onSearchResultsAvailable] still
 * builds the same `DateSeparator`-inserted list, and the matched words still take the same
 * `colorPrimaryFixedDim` background and `colorOnPrimaryFixed` foreground the old `StylingHelper`
 * span used. Nothing here resurrects a `copy_url` branch: the tree has none and this screen adds
 * none.
 */
class SearchActivity : XmppActivity(), OnSearchResultsAvailable {

    private var items by mutableStateOf<List<SearchResultItem>>(emptyList())
    private var highlighted by mutableStateOf<List<String>>(emptyList())
    private var query by mutableStateOf("")
    private var openWithKeyboard by mutableStateOf(true)
    private var uuid: String? = null
    private val currentSearch = ChangeWatcher<List<String>>()
    private val pendingSearchTerm = PendingItem<String>()
    private val pendingSearch = PendingItem<List<String>>()

    public override fun onCreate(bundle: Bundle?) {
        val intent = getIntent()
        this.uuid =
            if (intent == null) {
                null
            } else {
                Strings.emptyToNull(intent.getStringExtra(EXTRA_CONVERSATION_UUID))
            }
        val searchTerm = if (bundle == null) null else bundle.getString(EXTRA_SEARCH_TERM)
        if (searchTerm != null) {
            pendingSearchTerm.push(searchTerm)
        }
        super.onCreate(bundle)
        // The old `onCreateOptionsMenu` seeded the action view with the restored term; the field is
        // this screen's own now, so the seed lands on its text.
        val restored = pendingSearchTerm.pop()
        if (restored != null) {
            query = restored
            openWithKeyboard = false
            val term = FtsUtils.parse(restored)
            if (xmppConnectionService != null) {
                if (currentSearch.watch(term)) {
                    xmppConnectionService.search(term, uuid, this)
                }
            } else {
                pendingSearch.push(term)
            }
        }

        // The chrome draws its bar behind the system bars and hands the content the space they
        // leave, so the window must not inset itself for them first - on every API level, which is
        // what enableEdgeToEdge settles. See TulkkiChrome for the whole pattern.
        enableEdgeToEdge()
        setTulkkiContent(darkTheme = isDark()) {
            TulkkiChrome(
                // The title the XML action bar drew from the manifest label.
                title = stringResource(R.string.search_messages),
                // The old home branch hid the keyboard before the framework finished the activity.
                onUp = {
                    SoftKeyboardUtils.hideSoftKeyboard(this)
                    finish()
                },
            ) {
                SearchScreen(
                    rows = items,
                    query = query,
                    hasSearch = FtsUtils.parse(query.trim { it <= ' ' }).isNotEmpty(),
                    highlighted = highlighted,
                    openWithKeyboard = openWithKeyboard,
                    onQueryChange = { onQueryChanged(it) },
                    onAction = { row, action -> handleAction(row, action) },
                )
            }
        }
    }

    /**
     * Tulkki: the search result's quote is the message as the interface would have shown it - the
     * translation when there is one, the original when nothing needed translating, and a refusal
     * when it is covered. Upstream built this from the raw body (`MessageUtils.prepareQuote`),
     * which put a contact's original - reply fallback included - into the composer, where it is
     * readable, editable, kept as a draft and then sent as the owner's own words. The reply
     * mechanism itself still uses `prepareQuote`: there the quote is the wire's own fallback
     * and it is drawn from the referenced row, never from the reply's body.
     */
    private fun quote(message: Message) {
        val body = message.getBody()
        // Tulkki: the interpreter is the last input and the first thing read, so off the result is
        // quoted as it arrived - the plain client's search - and the cover below is unreachable.
        val interpreter = TranslationSettings.get(this).interpreter()
        val text =
            ShareText.of(
                DisplayedBody.of(
                    body,
                    message.getTranslatedBody(),
                    message.getTranslationState(),
                    DisplayedBody.needsTranslation(
                        message.getStatus(),
                        body,
                        ConversationName.of(message.getConversation()),
                        interpreter,
                    ),
                    interpreter,
                ),
                message.getBody(true),
            )
        if (ShareText.refused(text)) {
            Toast.makeText(this, R.string.tulkki_untranslated_reveal, Toast.LENGTH_SHORT).show()
            return
        }
        switchToConversationAndQuote(
            wrap(message.getConversation() ?: throw NullPointerException()),
            text,
        )
    }

    private fun wrap(conversational: Conversational): Conversation {
        return if (conversational is Conversation) {
            conversational
        } else {
            xmppConnectionService.findOrCreateConversation(
                conversational.getAccount() ?: throw NullPointerException(),
                conversational.getJid() ?: throw NullPointerException(),
                conversational.getMode() == Conversational.MODE_MULTI,
                true,
                true,
            ) as Conversation
        }
    }

    protected override fun refreshUiReal() {}

    protected override fun onBackendConnected() {
        val searchTerm = pendingSearch.pop()
        if (searchTerm != null && currentSearch.watch(searchTerm)) {
            xmppConnectionService.search(searchTerm, uuid, this)
        }
    }

    /** One context-menu item's work, the old `onContextItemSelected` branch. */
    private fun handleAction(row: SearchResultItem.MessageRow, action: SearchAction) {
        val message = row.message ?: return
        when (action) {
            SearchAction.ViewConversation -> {
                val thread: Element? = message.getThread()
                switchToConversationOnMessage(
                    wrap(message.getConversation() ?: throw NullPointerException()),
                    if (thread == null) null else thread.getContent(),
                    message.getUuid(),
                )
            }
            SearchAction.ShareWith -> ShareUtil.share(this, message)
            SearchAction.Copy -> ShareUtil.copyToClipboard(this, message)
            SearchAction.Quote -> quote(message)
            SearchAction.SaveToDownloads -> saveToDownloads(message)
        }
    }

    private fun saveToDownloads(message: Message) {
        xmppConnectionService.copyAttachmentToDownloadsFolder(
            message,
            object : UiCallback<Int> {
                override fun success(obj: Int) {
                    runOnUiThread {
                        Toast.makeText(
                            this@SearchActivity,
                            R.string.save_to_downloads_success,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }

                override fun error(errorCode: Int, obj: Int?) {
                    runOnUiThread {
                        Toast.makeText(
                            this@SearchActivity,
                            obj ?: throw NullPointerException(),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }

                override fun userInputRequired(pi: PendingIntent?, obj: Int) {}
            },
        )
    }

    public override fun onSaveInstanceState(bundle: Bundle) {
        val term = currentSearch.get()
        if (term != null && term.isNotEmpty()) {
            bundle.putString(EXTRA_SEARCH_TERM, FtsUtils.toUserEnteredString(term))
        }
        super.onSaveInstanceState(bundle)
    }

    /** The old `afterTextChanged`: the same parse, the same watcher, the same two branches. */
    private fun onQueryChanged(value: String) {
        query = value
        val term = FtsUtils.parse(value.trim { it <= ' ' })
        if (!currentSearch.watch(term)) {
            return
        }
        if (term.isEmpty()) {
            UiHost.installed().cancelRunningSearches()
            items = emptyList()
            highlighted = emptyList()
        } else {
            if (xmppConnectionService != null) {
                xmppConnectionService.search(term, uuid, this)
            }
        }
    }

    @JvmSuppressWildcards
    override fun onSearchResultsAvailable(
        term: List<String>,
        messageRefs: List<uk.xa0.tulkki.xmpp.refs.MessageRef>,
    ) {
        runOnUiThread {
            // Tulkki: 3.7 pair 9, part 16 - the hook carries the island's refs and this screen's own
            // list and date separator work in the model type (`DateSeparator.addAll` *inserts*
            // separator rows, so a `List<MessageRef>` cannot be passed through at all). Every element
            // really is a `Message`: `MessageSearchTask` built it with `IndividualMessage.fromCursor`.
            val messages = ArrayList<Message>()
            for (message in messageRefs) {
                messages.add(message as Message)
            }
            DateSeparator.addAll(messages)
            highlighted = StylingHelper.filterHighlightedWords(term)
            items = searchItems(messages)
        }
    }

    /** The old adapter's `getView`, one item per row. */
    private fun searchItems(messages: List<Message>): List<SearchResultItem> {
        val built = ArrayList<SearchResultItem>(messages.size)
        for ((index, message) in messages.withIndex()) {
            if (message.getType() == Message.TYPE_STATUS &&
                Message.DATE_SEPARATOR_BODY == message.getBody()
            ) {
                built.add(
                    SearchResultItem.DateSeparator(
                        key = "separator@$index",
                        label = dateLabel(message.getTimeSent()),
                    )
                )
            } else {
                val displayed = displayedBody(message)
                val cover = displayed.isBlurred()
                built.add(
                    SearchResultItem.MessageRow(
                        key = "${message.getUuid() ?: message.getTimeSent()}@$index",
                        title = UIHelper.getMessageDisplayName(message) ?: "",
                        body =
                            if (cover) {
                                getString(TranslationText.coverCaption(null))
                            } else {
                                displayed.text().toString()
                            },
                        cover = cover,
                        actions = actionsFor(message),
                        message = message,
                    )
                )
            }
        }
        return built
    }

    /** The date separator's own words: today, yesterday, or the full date, as the bubble said it. */
    private fun dateLabel(timeSent: Long): String =
        when {
            UIHelper.today(timeSent) -> getString(R.string.today)
            UIHelper.yesterday(timeSent) -> getString(R.string.yesterday)
            else ->
                DateUtils.formatDateTime(
                    this,
                    timeSent,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR,
                )
        }

    /** The old `onCreateContextMenu`'s visibility arithmetic, as the row's menu items. */
    private fun actionsFor(message: Message): List<SearchAction> {
        val deleted = message.isDeleted()
        val waitingOfferedSending =
            message.getStatus() == Message.STATUS_WAITING ||
                message.getStatus() == Message.STATUS_UNSEND ||
                message.getStatus() == Message.STATUS_OFFERED
        val cancelable =
            message.getTransferable() != null && !deleted ||
                waitingOfferedSending && message.needsUploading()
        val actions =
            mutableListOf(
                SearchAction.ViewConversation,
                SearchAction.ShareWith,
                SearchAction.Copy,
                SearchAction.Quote,
            )
        if (message.isGeoUri()) {
            actions.remove(SearchAction.Copy)
            actions.remove(SearchAction.Quote)
        }
        if (message.isFileOrImage() && !deleted && !cancelable) {
            val path = message.getRelativeFilePath()
            if (path == null ||
                !path.startsWith("/") ||
                FileBackend.inAppStorageDirectory(this, path)
            ) {
                actions.add(SearchAction.SaveToDownloads)
            }
        }
        return actions
    }

    /**
     * The one display decision, as the deleted `SearchResultAdapter` made it: the interpreter is
     * read last and consulted first, so off this is the plain client's original whatever the row
     * holds.
     */
    private fun displayedBody(message: Message): DisplayedBody {
        val interpreter = TranslationSettings.get(this).interpreter()
        return DisplayedBody.of(
            message.getBody(),
            message.getTranslatedBody(),
            message.getTranslationState(),
            DisplayedBody.needsTranslation(
                message.getStatus(),
                message.getBody(),
                ConversationName.of(message.getConversation()),
                interpreter,
            ),
            interpreter,
        )
    }

    companion object {
        private const val EXTRA_SEARCH_TERM = "search-term"
        const val EXTRA_CONVERSATION_UUID = "uuid"
    }
}

/** One row of the results: a date separator, or a message. */
sealed interface SearchResultItem {
    /** The row's identity, unique inside one result list. */
    val key: String

    /** A day's heading, inserted by [DateSeparator]. */
    data class DateSeparator(override val key: String, val label: String) : SearchResultItem

    /**
     * One message: its sender, its body as the interface would draw it, the items its menu carries
     * and the model they act on ([message] is `null` in a screenshot cell).
     */
    data class MessageRow(
        override val key: String,
        val title: String,
        val body: String,
        val cover: Boolean,
        val actions: List<SearchAction>,
        val message: Message?,
    ) : SearchResultItem
}

/** The five items `search_result_context.xml` carried, with the labels it gave them. */
enum class SearchAction(@StringRes val labelRes: Int) {
    ViewConversation(R.string.view_conversation),
    ShareWith(R.string.share_with),
    Copy(R.string.copy_to_clipboard),
    Quote(R.string.quote),
    SaveToDownloads(R.string.save_to_downloads),
}

/**
 * The search screen: the term's field, the watermark behind the results, and the results.
 *
 * <p>The watermark is the deleted `ListView`'s two `changeBackground` drawables - the search
 * magnifier while the field is empty and the cancel mark while a term matched nothing - over the
 * `?colorSurface` the layer lists also carried. Each result is a two-line row, the shape the
 * framework's `simple_list_item_2` gave the old adapter: the sender, then the body with the matched
 * words highlighted or the translation's cover caption. The long press is the result's menu, whose
 * items the old `onCreateContextMenu` chose.
 *
 * @param rows the rows to draw, separators included, oldest first.
 * @param query the field's text, owned by the Activity.
 * @param hasSearch whether a term is being searched: `false` draws the search watermark.
 * @param highlighted the words to mark in a body, already filtered by `StylingHelper`.
 * @param openWithKeyboard whether the field takes focus and raises the IME as it appears, which is
 *     the old `SoftKeyboardUtils.showKeyboard` branch for a fresh search.
 * @param onQueryChange every edit, which is the old `afterTextChanged`.
 * @param onAction one menu item's work.
 */
@Composable
fun SearchScreen(
    rows: List<SearchResultItem>,
    query: String,
    hasSearch: Boolean,
    highlighted: List<String>,
    openWithKeyboard: Boolean,
    onQueryChange: (String) -> Unit,
    onAction: (SearchResultItem.MessageRow, SearchAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        if (!hasSearch) {
            Image(
                painter = painterResource(R.drawable.ic_search_128dp),
                contentDescription = null,
                modifier = Modifier.align(Alignment.Center),
            )
        } else if (rows.isEmpty()) {
            Image(
                painter = painterResource(DataR.drawable.ic_cancel_96dp),
                contentDescription = null,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Column(modifier = Modifier.fillMaxSize()) {
            SearchMessageField(
                query = query,
                onQueryChange = onQueryChange,
                openWithKeyboard = openWithKeyboard,
            )
            val listState = rememberLazyListState()
            // `ListViewUtils.scrollToBottom`: a new result set lands on the newest row, which with
            // the reversed layout is index 0.
            LaunchedEffect(rows) {
                if (rows.isNotEmpty()) {
                    listState.scrollToItem(0)
                }
            }
            // `stackFromBottom`: the last row is at the bottom and a short list hangs from there,
            // which is the reversed drawing of the list in its own order.
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                items(rows.asReversed(), key = { it.key }) { item ->
                    when (item) {
                        is SearchResultItem.DateSeparator -> SearchDateSeparatorRow(item.label)
                        is SearchResultItem.MessageRow ->
                            SearchMessageRow(
                                row = item,
                                highlighted = highlighted,
                                onAction = onAction,
                            )
                    }
                }
            }
        }
    }
}

/** One day's heading, the old separator row's title with the framework's empty second line. */
@Composable
private fun SearchDateSeparatorRow(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** One result: the sender, the body, and the menu the old context menu opened. */
@Composable
private fun SearchMessageRow(
    row: SearchResultItem.MessageRow,
    highlighted: List<String>,
    onAction: (SearchResultItem.MessageRow, SearchAction) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .combinedClickable(onClick = {}, onLongClick = { menuOpen = true })
                    .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.body.isNotEmpty()) {
                Text(
                    text = highlightedBody(row.body, if (row.cover) emptyList() else highlighted),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            for (action in row.actions) {
                DropdownMenuItem(
                    text = { Text(stringResource(action.labelRes)) },
                    onClick = {
                        menuOpen = false
                        onAction(row, action)
                    },
                )
            }
        }
    }
}

/**
 * A body with the matched words marked: the same `BackgroundColorSpan(colorPrimaryFixedDim)` and
 * `ForegroundColorSpan(colorOnPrimaryFixed)` pair `StylingHelper.highlight` set, over the same
 * case-insensitive, non-overlapping occurrences of each word.
 */
@Composable
private fun highlightedBody(text: String, needles: List<String>): AnnotatedString {
    if (needles.isEmpty()) {
        return AnnotatedString(text)
    }
    val scheme = MaterialTheme.colorScheme
    return buildAnnotatedString {
        append(text)
        for (needle in needles) {
            if (needle.isEmpty()) {
                continue
            }
            var start = text.indexOf(needle, 0, ignoreCase = true)
            while (start >= 0) {
                addStyle(
                    SpanStyle(
                        color = scheme.onPrimaryFixed,
                        background = scheme.primaryFixedDim,
                    ),
                    start,
                    start + needle.length,
                )
                start = text.indexOf(needle, start + needle.length, ignoreCase = true)
            }
        }
    }
}

/** The search field: the old `actionview_search` `EditText`, always open, in Compose. */
@Composable
private fun SearchMessageField(
    query: String,
    onQueryChange: (String) -> Unit,
    openWithKeyboard: Boolean,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val description = stringResource(R.string.search_messages)
    LaunchedEffect(Unit) {
        if (openWithKeyboard) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = 12.dp)
                .focusRequester(focusRequester)
                .semantics { contentDescription = description },
        placeholder = { Text(description) },
        leadingIcon = {
            Icon(
                painter = painterResource(R.drawable.ic_search_24dp),
                contentDescription = null,
            )
        },
        trailingIcon = {
            IconButton(onClick = { onQueryChange("") }) {
                Icon(
                    painter = painterResource(R.drawable.ic_clear_24dp),
                    contentDescription = stringResource(R.string.clear_search),
                )
            }
        },
        singleLine = true,
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Search,
            ),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
    )
}
