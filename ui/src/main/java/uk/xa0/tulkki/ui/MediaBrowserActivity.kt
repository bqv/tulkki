package uk.xa0.tulkki.ui

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.ArrayList
import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.FileBackend
import uk.xa0.tulkki.data.FileBackends
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Contact
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.utils.Attachment
import uk.xa0.tulkki.libs.AttachmentRef
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.ui.chrome.ChromeMenuItem
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import uk.xa0.tulkki.ui.interfaces.OnMediaLoaded
import uk.xa0.tulkki.ui.media.MediaBrowserScreen
import uk.xa0.tulkki.ui.util.ViewUtil

/**
 * The media browser: the account's or a conversation's images, videos, audio and files, in five tabs
 * over one grid.
 *
 * <p>**The shell is Compose and its two layouts are gone.** `activity_media_browser.xml` held a
 * toolbar over a `TabLayout` over a `ViewPager2` of five `MediaBrowserFragment`s, and
 * `fragment_media_browser.xml` was one of those fragments' `RecyclerView`; the fragment is deleted
 * and [MediaBrowserScreen] is the tabs, the pager and the grid. `setSupportActionBar`,
 * `configureActionBar` and `Activities.setStatusAndNavigationBarColors` went with the toolbar - the
 * shared chrome draws the bar, the title, the up arrow and the system-bar colours itself - and so did
 * both menus: `activity_media_browser.xml`'s search-by-date and clear-search and
 * `media_browser_context.xml`'s four selection actions are items in the chrome's overflow, which
 * is the only place a bar without an action mode can keep them reachable.
 *
 * <p>**What moved and what did not.** The selection is the Activity's, as it was: the old
 * `ActionMode` is not an affordance Compose has, so its title - the count - is the chrome's title
 * while something is selected, and its four actions are in the overflow, with Jump to message only
 * for a single selection exactly as `onPrepareActionMode` decided it. Back clears the selection
 * before it leaves, which is what `onBackPressed` did. Sharing, saving, deleting, jumping and the
 * date picker are the old methods, unchanged except that they read the selection set instead of a
 * shared `HashSet` and that a deleted row leaves the derived album rather than the adapter's list.
 *
 * <p>**The rows are Compose items.** The old grid was `MediaAdapter` inside a `GridLayoutManager`,
 * with `GridManager` computing the columns and the preview size from
 * `R.dimen.browser_media_size`; the Compose grid does the same arithmetic and `MediaAdapter`'s
 * `item_media.xml` rows are the screen's `MediaCell` now. The adapter itself survives for
 * the two Java screens that still draw it (contact details, group details).
 */
class MediaBrowserActivity : XmppActivity(), OnMediaLoaded {

    /** The loaded attachments, the old `mAttachments`; the pager's pages filter it. */
    private var attachments by mutableStateOf<List<AttachmentRef>>(emptyList())

    /** The old `selectedAttachments`, as Compose state so the chrome and the grid follow it. */
    private var selected by mutableStateOf<Set<AttachmentRef>>(emptySet())

    /** The old `mSearchQuery`: the `yyyy-MM-dd` a date-search picked, or null for everything. */
    private var dateFilter by mutableStateOf<String?>(null)

    /**
     * Whether the old `loadMedia` took its no-account branch, which is the one that renamed the bar
     * to `media_gallery`; the other branch kept the manifest's `media_browser` label.
     */
    private var gallery by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The chrome draws its bar behind the system bars and hands the content the space they
        // leave, so the window must not inset itself for them first. See TulkkiChrome.
        enableEdgeToEdge()

        setTulkkiContent(darkTheme = isDark()) {
            TulkkiChrome(
                // The action mode's title was the bare count; the search's title was the toolbar's.
                title =
                    if (selected.isNotEmpty()) {
                        selected.size.toString()
                    } else {
                        stringResource(
                            if (gallery) R.string.media_gallery else R.string.media_browser)
                    },
                // The arrow `configureActionBar(supportActionBar)` switched on, which ran `finish()`.
                onUp = { finish() },
                menu = chromeMenu(),
            ) {
                MediaBrowserScreen(
                    attachments = attachments,
                    selected = selected,
                    onOpen = { openMedia(it) },
                    onToggleSelection = { toggleSelection(it) },
                    onDeleteFile = { deleteFile(it) },
                    deleteFileLabel = stringResource(R.string.delete_file),
                )
            }
        }
    }

    /**
     * The chrome's overflow: the selection actions while something is selected, and the options menu
     * the deleted `activity_media_browser.xml` held. Clear search is only there when a date filter is
     * set, which is what `onCreateOptionsMenu` made `action_clear_search`'s visibility mean.
     */
    @Composable
    private fun chromeMenu(): List<ChromeMenuItem> {
        val items = ArrayList<ChromeMenuItem>()
        if (selected.isNotEmpty()) {
            if (selected.size == 1) {
                items.add(ChromeMenuItem(stringResource(R.string.jump_to_message)) { jumpToSelectedMessage() })
            }
            items.add(ChromeMenuItem(stringResource(R.string.share)) { shareSelectedMedia() })
            items.add(
                ChromeMenuItem(stringResource(R.string.action_save_to_downloads)) {
                    confirmSaveSelectedMedia()
                })
            items.add(ChromeMenuItem(stringResource(R.string.delete)) { confirmDeleteSelectedMedia() })
        }
        items.add(ChromeMenuItem(stringResource(R.string.search_by_date)) { showDatePicker() })
        if (dateFilter != null) {
            items.add(ChromeMenuItem(stringResource(R.string.clear_search)) { clearDateFilter() })
        }
        return items
    }

    override fun refreshUiReal() {}

    override fun onBackendConnected() {
        loadMedia()
    }

    override fun onBackPressed() {
        if (selected.isNotEmpty()) {
            // The action mode's `finish()`, which `onDestroyActionMode` answered with a clear.
            clearSelection()
        } else {
            super.onBackPressed()
        }
    }

    private fun loadMedia() {
        if (xmppConnectionService == null) return
        val accountUuid = intent.getStringExtra("account")
        val jidString = intent.getStringExtra("jid")
        if (accountUuid != null && jidString != null) {
            gallery = false
            val jid = Jid.of(jidString)
            xmppConnectionService.getAttachments(accountUuid, jid, dateFilter, 0, this)

            if (intent.getStringExtra("conversation_uuid") == null) {
                val account = AccountRegistry.get().findAccountByUuid(accountUuid)
                if (account != null) {
                    val conversation =
                        xmppConnectionService.findOrCreateConversation(
                            account,
                            jid,
                            false,
                            false,
                        ) as Conversation
                    intent.putExtra("conversation_uuid", conversation.getUuid())
                }
            }
        } else {
            xmppConnectionService.getAttachments(dateFilter, 0, this)
            gallery = true
        }
    }

    override fun onMediaLoaded(
        attachments: List<AttachmentRef>,
    ) {
        runOnUiThread { this@MediaBrowserActivity.attachments = attachments }
    }

    /** A row's tap while nothing is selected is still `ViewUtil.view`, model and extras and all. */
    private fun openMedia(attachment: AttachmentRef) {
        val model = attachment as? Attachment ?: return
        ViewUtil.view(
            this,
            model,
            intent.getStringExtra("conversation_uuid"),
            intent.getStringExtra("account"),
            intent.getStringExtra("jid"),
        )
    }

    /** The old adapter's `toggleSelection`, on the Activity's own set. */
    private fun toggleSelection(attachment: AttachmentRef) {
        val next = selected.toMutableSet()
        if (!next.add(attachment)) {
            next.remove(attachment)
        }
        selected = next
    }

    private fun clearSelection() {
        selected = emptySet()
    }

    /**
     * A long-press on a row that is already in selection mode: the old context menu's one item, which
     * deleted the original file and dropped the row from the list.
     */
    private fun deleteFile(attachment: AttachmentRef) {
        val path = FileBackends.get().getOriginalPath(attachment.getUri()) ?: return
        val file = File(path)
        if (file.delete()) {
            xmppConnectionService.evictPreview(file)
            attachments = attachments.filterNot { it == attachment }
        }
    }

    private fun shareSelectedMedia() {
        if (selected.isEmpty()) return
        val intent = Intent()
        if (selected.size == 1) {
            val attachment = selected.iterator().next()
            val path = FileBackends.get().getOriginalPath(attachment.getUri()) ?: return
            val file = File(path)
            val uri = FileBackend.getUriForFile(this, file, file.getName())
            intent.action = Intent.ACTION_SEND
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.type = attachment.getMime()
        } else {
            val uris = ArrayList<Uri>()
            for (attachment in selected) {
                val path = FileBackends.get().getOriginalPath(attachment.getUri())
                if (path != null) {
                    val file = File(path)
                    uris.add(FileBackend.getUriForFile(this, file, file.getName()))
                }
            }
            intent.action = Intent.ACTION_SEND_MULTIPLE
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            intent.type = "*/*"
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.share_with)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                this,
                R.string.no_application_found_to_open_file,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun jumpToSelectedMessage() {
        if (selected.size != 1) return
        val attachment = selected.iterator().next()
        val conversationUuid = attachment.getConversationUuid() ?: return
        val conversation = xmppConnectionService.findConversationByUuid(conversationUuid) as Conversation?
        if (conversation != null) {
            switchToConversationOnMessage(conversation, attachment.getUuid().toString())
        }
    }

    /** The Delete's confirmation, which the action mode raised before it deleted. */
    private fun confirmDeleteSelectedMedia() {
        val count = selected.size
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_file_dialog)
            .setMessage(getString(R.string.delete_files_dialog_msg, count))
            .setPositiveButton(R.string.confirm) { _, _ -> deleteSelectedMedia() }
            .setNegativeButton(uk.xa0.tulkki.data.R.string.cancel, null)
            .show()
    }

    /** The Save's confirmation, which the action mode raised before it copied. */
    private fun confirmSaveSelectedMedia() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_save_to_downloads)
            .setMessage(R.string.save_to_downloads_warning)
            .setPositiveButton(R.string.confirm) { _, _ -> saveSelectedMedia() }
            .setNegativeButton(uk.xa0.tulkki.data.R.string.cancel, null)
            .show()
    }

    private fun saveSelectedMedia() {
        for (attachment in selected) {
            val path = FileBackends.get().getOriginalPath(attachment.getUri())
            if (path != null) {
                val file = File(path)
                xmppConnectionService.copyAttachmentToDownloadsFolder(
                    file,
                    object : UiCallback<Int> {
                        override fun success(obj: Int) {
                            // ignore
                        }

                        override fun error(errorCode: Int, obj: Int?) {
                            runOnUiThread {
                                Toast.makeText(
                                    this@MediaBrowserActivity,
                                    obj ?: throw NullPointerException(),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }

                        override fun userInputRequired(pi: PendingIntent?, obj: Int) {
                            // ignore
                        }
                    },
                )
            }
        }
        Toast.makeText(
            this,
            getString(R.string.save_to_downloads_success),
            Toast.LENGTH_SHORT,
        ).show()
        clearSelection()
    }

    private fun deleteSelectedMedia() {
        xmppConnectionService.deleteMedia(ArrayList(selected))
        clearSelection()
        loadMedia()
    }

    private fun showDatePicker() {
        val datePicker =
            MaterialDatePicker.Builder.datePicker()
                .setTitleText(R.string.search_by_date)
                .setSelection(MaterialDatePicker.todayInUtcMilliseconds())
                .build()
        datePicker.addOnPositiveButtonClickListener { selection ->
            val calendar =
                java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            calendar.setTimeInMillis(selection)
            val year = calendar.get(java.util.Calendar.YEAR)
            val month = calendar.get(java.util.Calendar.MONTH) + 1
            val day = calendar.get(java.util.Calendar.DAY_OF_MONTH)
            dateFilter = String.format(java.util.Locale.US, "%04d-%02d-%02d", year, month, day)
            loadMedia()
        }
        datePicker.show(supportFragmentManager, "date_picker")
    }

    private fun clearDateFilter() {
        dateFilter = null
        loadMedia()
    }

    companion object {
        @JvmStatic
        fun launch(context: Context, contact: Contact) {
            launch(context, contact.getAccount(), contact.getJid().asBareJid().toString())
        }

        @JvmStatic
        fun launch(context: Context, conversation: Conversation) {
            launch(
                context,
                conversation.getAccount() ?: throw NullPointerException(),
                (conversation.getJid() ?: throw NullPointerException()).asBareJid().toString(),
            )
        }

        private fun launch(context: Context, account: Account, jid: String) {
            val intent = Intent(context, MediaBrowserActivity::class.java)
            intent.putExtra("account", account.getUuid())
            intent.putExtra("jid", jid)
            context.startActivity(intent)
        }
    }
}
