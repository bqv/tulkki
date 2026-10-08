package uk.xa0.tulkki.ui

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import uk.xa0.tulkki.data.AppSettings
import uk.xa0.tulkki.data.model.ListItem
import uk.xa0.tulkki.ui.chrome.ChromeMenuItem
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import uk.xa0.tulkki.ui.list.PickerList
import uk.xa0.tulkki.ui.list.PickerRow
import uk.xa0.tulkki.ui.list.pickerRow
import uk.xa0.tulkki.ui.searchable.SearchableListScreen

/**
 * The shape the three list pickers share: a filtered contact list, a search field above it and a
 * floating action button.
 *
 * <p>**The layout is gone.** `activity_choose_contact.xml` held a toolbar, a `ListView` and a
 * `FloatingActionButton`; the file is deleted, the bar is the shared chrome
 * ([uk.xa0.tulkki.ui.chrome.TulkkiChrome]) now, and the screen's body is
 * [SearchableListScreen]. `setSupportActionBar`, `configureActionBar`,
 * `Activities.setStatusAndNavigationBarColors` and the `R.menu.choose_contact` inflation went with
 * it; the menu's one live item (the QR scan of `ChooseContactActivity`) is in the chrome's
 * overflow, and its search action view is [SearchableListScreen]'s own field.
 *
 * <p>**The rows and the list are Compose now.** `ListView`, `ListItemAdapter` and
 * `item_contact.xml` are deleted; [rows] holds one [PickerRow] per [listItems] entry, resolved by
 * [pickerRow], and [PickerList] draws them in a `LazyColumn`. A subclass moves the list the way
 * `notifyDataSetChanged` did - by refilling [listItems] and calling [refreshList]. The gestures are
 * the host's: [setRowClickListener], [setRowLongClickListener] and [setTagClickListener] are the
 * `setOnItemClickListener`/`setOnItemLongClickListener`/`OnTagClickedListener` calls, and
 * [setCheckedKeysProvider] is the choice mode [ChooseContactActivity] drives.
 *
 * <p>**Gone with the `ListView`, and recorded.** The fast-scroll thumb (`setFastScrollEnabled(true)`)
 * and the divider the pickers zeroed have no `LazyColumn` counterpart. Nothing else moves: the
 * adapter's `refreshSettings` is [refreshSettings], and the delayed FAB work is the handler it was.
 *
 * <p>**The action bar's title is the chrome's.** `ChooseContactActivity` and `ShortcutActivity` set
 * it in `onStart`, `BlocklistActivity` relied on its manifest label; all three now answer
 * [titleRes], read once per composition.
 */
abstract class AbstractSearchableListItemActivity : XmppActivity() {

    /** The rows the list draws; every subclass fills it in [filterContacts]. */
    val listItems: MutableList<ListItem> = ArrayList()

    // The list was XML, then a hosted `ListView`; it is Compose now, so its rows and the gestures
    // its subclasses wire are this class's state.
    private var rows by mutableStateOf<List<PickerRow>>(emptyList())

    private var showDynamicTags = false

    private var rowClick: ((Int) -> Unit)? = null

    private var rowLongClick: ((Int) -> Unit)? = null

    private var tagClick: ((String) -> Unit)? = null

    // The choice mode's provider is state: a picker sets it after `super.onCreate` has already run
    // the first composition, and the recomposition that carries it is what lets the composition read
    // the picker's own selection state and subscribe to it. (The field is not named for the method
    // that fills it: a `checkedKeysProvider` property beside `setCheckedKeysProvider` would emit the
    // same `setCheckedKeysProvider(Function0)` and clash on the JVM.)
    private var checkedKeysSource by mutableStateOf<() -> Set<String>>({ emptySet() })

    // The FAB and the search field were XML; they are Compose now, so their state is this class's.
    private var fabIcon by mutableStateOf(R.drawable.ic_person_add_24dp)

    private var fabVisible by mutableStateOf(false)

    private var fabAction: (() -> Unit)? = null

    private var searchOpen by mutableStateOf(false)

    private var searchQuery by mutableStateOf("")

    private var chromeMenuItems by mutableStateOf<List<ChromeMenuItem>>(emptyList())

    private val mMainHandler = Handler(Looper.getMainLooper())

    /**
     * The search field's collapse: while it is open the system back button closes it instead of the
     * screen, which is what the framework's action view did for the menu item.
     */
    private val mSearchBackCallback =
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                closeSearch()
            }
        }

    /** The title the chrome draws. */
    protected open fun titleRes(): Int = R.string.title_activity_choose_contact

    /** The live items the chrome's overflow carries. Empty unless a subclass sets some. */
    protected fun setChromeMenu(items: List<ChromeMenuItem>) {
        chromeMenuItems = items
    }

    /** One row's tap, with its place in [listItems]: the deleted `setOnItemClickListener`. */
    protected fun setRowClickListener(action: (Int) -> Unit) {
        rowClick = action
    }

    /** One row's long press, with its place in [listItems]: the old `setOnItemLongClickListener`. */
    protected fun setRowLongClickListener(action: (Int) -> Unit) {
        rowLongClick = action
    }

    /** One dynamic tag's tap, with its word: the deleted `setOnTagClickedListener`. */
    protected fun setTagClickListener(action: (String) -> Unit) {
        tagClick = action
    }

    /**
     * The JIDs drawn as checked, for a picker that has a choice mode. The provider is read during
     * composition, so the picker's own selection state is what drives the redraw.
     */
    protected fun setCheckedKeysProvider(provider: () -> Set<String>) {
        checkedKeysSource = provider
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The old action view collapsed on back; the chrome has no action view, so the dispatcher
        // does it. Registered before the composition so the state it toggles is never stale.
        onBackPressedDispatcher.addCallback(this, mSearchBackCallback)

        // The chrome draws its bar behind the system bars and hands the content the space they
        // leave, so the window must not inset itself for them first - on every API level, which is
        // what enableEdgeToEdge settles. See TulkkiChrome for the whole pattern.
        enableEdgeToEdge()
        setTulkkiContent(darkTheme = isDark()) {
            TulkkiChrome(
                title = stringResource(titleRes()),
                onUp = { finish() },
                menu = chromeMenuItems,
                actions = {
                    IconButton(onClick = { openSearch() }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_search_24dp),
                            contentDescription = stringResource(R.string.search),
                        )
                    }
                },
            ) {
                SearchableListScreen(
                    list = {
                        PickerList(
                            rows = rows,
                            checkedKeys = checkedKeysSource(),
                            onRowClick = { position -> rowClick?.invoke(position) },
                            onRowLongClick = { position, _ -> rowLongClick?.invoke(position) },
                            onTagClick = { tag -> tagClick?.invoke(tag) },
                        )
                    },
                    searchOpen = searchOpen,
                    query = searchQuery,
                    onQueryChange = { onSearchTextChanged(it) },
                    onSearchClose = { closeSearch() },
                    onSearchSubmit = { onSearchAction() },
                    fabIcon = fabIcon,
                    fabVisible = fabVisible,
                    fabDescription =
                        stringResource(
                            if (fabIcon == R.drawable.ic_person_add_24dp) R.string.add_contact
                            else R.string.select
                        ),
                    onFab = { fabAction?.invoke() },
                )
            }
        }
    }

    /**
     * The deleted `ListItemAdapter.refreshSettings`, and the repaint `notifyDataSetChanged` asked
     * for in one call: `show_dynamic_tags` is re-read and every row resolved again.
     */
    protected fun refreshSettings() {
        showDynamicTags =
            getPreferences()
                .getBoolean(
                    AppSettings.SHOW_DYNAMIC_TAGS,
                    resources.getBoolean(R.bool.show_dynamic_tags),
                )
        refreshList()
    }

    /** The deleted `ListItemAdapter.notifyDataSetChanged`: resolve [listItems] into drawn rows. */
    protected fun refreshList() {
        rows =
            listItems.mapIndexed { index, item ->
                pickerRow(this, item, index, showDynamicTags)
            }
    }

    /** The chrome's search icon: the old `action_search` action view, opened. */
    protected fun openSearch() {
        searchOpen = true
        mSearchBackCallback.isEnabled = true
    }

    /** The old action view's collapse: the field goes, its text clears and the list resets. */
    private fun closeSearch() {
        searchOpen = false
        mSearchBackCallback.isEnabled = false
        searchQuery = ""
        filterContacts()
    }

    /** The old `TextWatcher.afterTextChanged`, on the field's own text. */
    private fun onSearchTextChanged(query: String) {
        searchQuery = query
        filterContacts(query)
    }

    /** The IME's search action, the old `OnEditorActionListener` hook. */
    protected open fun onSearchAction(): Boolean = false

    /** The current search text, for a subclass that refreshes through it (`BlocklistActivity`). */
    protected val searchQueryText: String
        get() = searchQuery

    /** A tag tap: open the field on the tag, exactly as the old action view was expanded on it. */
    protected fun showTagInSearch(tag: String) {
        searchOpen = true
        mSearchBackCallback.isEnabled = true
        searchQuery = tag
        filterContacts(tag)
    }

    protected fun showFab() {
        fabVisible = true
    }

    protected fun hideFab() {
        fabVisible = false
    }

    protected fun applyFabIcon(@DrawableRes icon: Int) {
        fabIcon = icon
    }

    protected fun setFabClickListener(action: () -> Unit) {
        fabAction = action
    }

    /** `FloatingActionButton.postDelayed`, on the handler the FAB no longer has. */
    protected fun postDelayed(delayMillis: Long, action: () -> Unit) {
        mMainHandler.postDelayed({ action() }, delayMillis)
    }

    protected fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(
            window.decorView.windowToken,
            InputMethodManager.HIDE_IMPLICIT_ONLY,
        )
    }

    protected fun filterContacts() {
        filterContacts(null)
    }

    protected abstract fun filterContacts(needle: String?)

    override fun onBackendConnected() {
        filterContacts()
    }
}
