package uk.xa0.tulkki.ui

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.util.Log
import android.widget.FrameLayout
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.atomic.AtomicInteger
import me.drakeet.support.toast.ToastCompat
import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.AppSettings
import uk.xa0.tulkki.data.DatabaseBackend
import uk.xa0.tulkki.data.FileBackends
import uk.xa0.tulkki.data.model.Bookmark
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.ListItem
import uk.xa0.tulkki.data.model.MucOptions
import uk.xa0.tulkki.ui.adapter.MediaAdapter
import uk.xa0.tulkki.ui.chrome.ChromeMenuItem
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import uk.xa0.tulkki.ui.details.ConferenceDetailsEvents
import uk.xa0.tulkki.ui.details.ConferenceDetailsScreen
import uk.xa0.tulkki.ui.details.ConferenceDetailsState
import uk.xa0.tulkki.ui.details.ConferenceUserRow
import uk.xa0.tulkki.ui.details.EphemeralRow
import uk.xa0.tulkki.ui.details.EphemeralWarningDialog
import uk.xa0.tulkki.ui.details.TagChip
import uk.xa0.tulkki.ui.details.ThreadRow
import uk.xa0.tulkki.ui.extras.TagEditorState
import uk.xa0.tulkki.ui.host.UiHost
import uk.xa0.tulkki.ui.interfaces.OnMediaLoaded
import uk.xa0.tulkki.ui.util.AvatarWorkerTask
import uk.xa0.tulkki.ui.util.GridManager
import uk.xa0.tulkki.ui.util.MucConfiguration
import uk.xa0.tulkki.ui.util.MucDetailsContextMenuHelper
import uk.xa0.tulkki.ui.util.MucUserMenu
import uk.xa0.tulkki.ui.util.MyLinkify
import uk.xa0.tulkki.ui.util.SoftKeyboardUtils
import uk.xa0.tulkki.ui.utils.StylingHelper
import uk.xa0.tulkki.ui.utils.UIHelper
import uk.xa0.tulkki.ui.utils.XEP0392Helper
import uk.xa0.tulkki.ui.widget.AvatarView
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.utils.AccountUtils
import uk.xa0.tulkki.xmpp.utils.StringUtils

/**
 * The group-chat and channel details screen.
 *
 * <p>**The layout is gone.** `activity_muc_details.xml` held the toolbar, the `ScrollView` and
 * every view inside it, and the file is deleted; the bar is the shared chrome ([TulkkiChrome]) now,
 * the body is [ConferenceDetailsScreen], and the menu (`R.menu.muc_details`) is the chrome's
 * overflow. `setSupportActionBar`, `configureActionBar`,
 * `Activities.setStatusAndNavigationBarColors` and the options-menu pair went with the views they
 * belonged to; the advanced-mode item's check mark is the composition's, because the flag is
 * `mAdvancedMode` and the chrome's overflow has no checkable row.
 *
 * <p>**The editor is Compose.** `muc_display` and `muc_editor` swapped visibility inside one
 * RelativeLayout, and the two `EditText`s under their `TextInputLayout`s were read back by the
 * save path; they are [ConferenceDetailsScreen]'s own fields now, driven by the same state. The tag
 * editor stays a view (it is shared), hosted inside the editor group.
 *
 * <p>**The warning dialog is Compose.** `dialog_ephemeral_warning.xml` is deleted and
 * [EphemeralWarningDialog] is launched from the same arm of the ephemeral switch, with the same two
 * side effects as on the one-to-one screen.
 *
 * <p>The participants grid is Compose now: `item_user_preview.xml` and `UserPreviewAdapter` are
 * deleted, this class resolves each row's facts and the `muc_details_context` menu is a Compose
 * `DropdownMenu` in the long-pressed row ([MucUserMenu]) - the shape `MucUsersActivity` uses. The
 * media grid stays a `RecyclerView` (`MediaAdapter` is the shared bridge) and is built here and
 * handed to the screen as a slot.
 *
 * <p>Ported from Java by the `ui43g` lane. The Java's unguarded dereferences of the `:data` model's
 * nullable members are kept as `?: throw NullPointerException()` exactly as they were.
 */
class ConferenceDetailsActivity :
    XmppActivity(),
    uk.xa0.tulkki.xmpp.services.OnConversationUpdate,
    uk.xa0.tulkki.xmpp.services.OnMucRosterUpdate,
    uk.xa0.tulkki.xmpp.services.OnAffiliationChanged,
    uk.xa0.tulkki.xmpp.services.OnConfigurationPushed,
    uk.xa0.tulkki.xmpp.services.OnRoomDestroy,
    OnMediaLoaded {
    private var mConversation: Conversation? = null
    private var screen by mutableStateOf(ConferenceDetailsState())

    /**
     * The participant whose long-press menu is open, and the entries the host resolved for them: the
     * deleted `PopupMenu` + `muc_details_context.xml`, held as state because the screen draws the
     * menu where the row is.
     */
    private var userMenu by mutableStateOf<MucUserMenu?>(null)

    private var menuUser: MucOptions.User? = null

    /** The deleted dialog's visibility, now a flag the composition reads. */
    private var ephemeralWarning by mutableStateOf(false)

    private lateinit var mMediaAdapter: MediaAdapter
    private lateinit var mMediaView: RecyclerView

    /** The participants the grid previews, sorted by the host exactly as the adapter's list was. */
    private var mucUsers by mutableStateOf<List<MucOptions.User>>(emptyList())
    private lateinit var mAvatar: AvatarView
    private lateinit var mSubject: TextView
    private lateinit var mTagEditor: TagEditorState
    private var uuid: String? = null

    private var mAdvancedMode = false
    private var showDynamicTags = true
    private var ephemeralDurationValues: IntArray = IntArray(0)

    protected fun deleteBookmark() {
        try {
            val bookmark = (mConversation ?: throw NullPointerException()).getBookmark()
            val account = (bookmark ?: throw NullPointerException()).getAccount()
            bookmark.setConversation(null)
            xmppConnectionService.deleteBookmark(account, bookmark)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            updateView()
        }
    }

    private val renameCallback:
        uk.xa0.tulkki.xmpp.services.UiCallbackPort<uk.xa0.tulkki.xmpp.refs.ConversationRef> =
        object : uk.xa0.tulkki.xmpp.services.UiCallbackPort<uk.xa0.tulkki.xmpp.refs.ConversationRef> {
            override fun success(object2: uk.xa0.tulkki.xmpp.refs.ConversationRef) {
                displayToast(getString(R.string.your_nick_has_been_changed))
                runOnUiThread { updateView() }
            }

            override fun error(errorCode: Int, object2: uk.xa0.tulkki.xmpp.refs.ConversationRef?) {
                displayToast(getString(errorCode))
            }

            override fun userInputRequired(
                pi: PendingIntent?,
                object2: uk.xa0.tulkki.xmpp.refs.ConversationRef,
            ) {}
        }

    /** The deleted notification button's dialog: the four choices, then the attributes they set. */
    private fun onNotifyStatus() {
        val builder = MaterialAlertDialogBuilder(this@ConferenceDetailsActivity)
        builder.setTitle(R.string.pref_notification_settings)
        val choices =
            arrayOf(
                getString(R.string.notify_on_all_messages),
                getString(R.string.notify_only_when_highlighted),
                getString(R.string.notify_only_when_highlighted_or_replied),
                getString(R.string.notify_never),
            )
        val conversation = mConversation ?: throw NullPointerException()
        val choice: AtomicInteger
        if (conversation.getLongAttribute(Conversation.ATTRIBUTE_MUTED_TILL, 0) == Long.MAX_VALUE) {
            choice = AtomicInteger(3)
        } else {
            choice =
                AtomicInteger(
                    if (conversation.alwaysNotify()) 0 else if (conversation.notifyReplies()) 2 else 1,
                )
        }
        builder.setSingleChoiceItems(choices, choice.get()) { _, which -> choice.set(which) }
        builder.setNegativeButton(uk.xa0.tulkki.data.R.string.cancel, null)
        builder.setPositiveButton(R.string.ok) { _, _ ->
            if (choice.get() == 3) {
                conversation.setMutedTill(Long.MAX_VALUE)
            } else {
                conversation.setMutedTill(0)
                conversation.setAttribute(
                    Conversation.ATTRIBUTE_ALWAYS_NOTIFY,
                    (choice.get() == 0).toString(),
                )
                conversation.setAttribute(
                    Conversation.ATTRIBUTE_NOTIFY_REPLIES,
                    (choice.get() == 2).toString(),
                )
            }
            xmppConnectionService.updateConversation(conversation)
            updateView()
        }
        builder.create().show()
    }

    /** The deleted settings button's dialog: `MucConfiguration`'s multi-choice form. */
    private fun onChangeConferenceSettings() {
        val mucOptions = (mConversation ?: throw NullPointerException()).getMucOptions()
        val builder = MaterialAlertDialogBuilder(this@ConferenceDetailsActivity)
        val configuration =
            MucConfiguration.get(this@ConferenceDetailsActivity, mAdvancedMode, mucOptions)
        builder.setTitle(configuration.title)
        val values = configuration.values
        builder.setMultiChoiceItems(configuration.names, values) { _, which, isChecked ->
            values[which] = isChecked
        }
        builder.setNegativeButton(uk.xa0.tulkki.data.R.string.cancel, null)
        builder.setPositiveButton(R.string.confirm) { _, _ ->
            val options = configuration.toBundle(values)
            options.putString("muc#roomconfig_persistentroom", "1")
            if (options.containsKey("muc#roomconfig_allowinvites")) {
                options.putString(
                    "{http://prosody.im/protocol/muc}roomconfig_allowmemberinvites",
                    options.getString("muc#roomconfig_allowinvites"),
                )
            }
            xmppConnectionService.pushConferenceConfiguration(
                mConversation ?: throw NullPointerException(),
                options,
                this@ConferenceDetailsActivity,
            )
        }
        builder.create().show()
    }

    override fun onConversationUpdate() {
        refreshUi()
    }

    override fun onMucRosterUpdate() {
        refreshUi()
    }

    override fun refreshUiReal() {
        updateView()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        showDynamicTags =
            preferences.getBoolean(
                "show_dynamic_tags",
                resources.getBoolean(R.bool.show_dynamic_tags),
            )

        // The two surfaces that stay views, built and wired here exactly as the deleted layout's
        // views were. The tag editor is Compose now (`TagEditorState`), so it needs no parent:
        // `item_tag.xml` and `TagEditorView` are deleted, and the old `TextWatcher` is the state's
        // own change callback.
        mMediaView = RecyclerView(this)
        mMediaAdapter = MediaAdapter(this, R.dimen.media_size, false)
        mMediaView.adapter = mMediaAdapter
        GridManager.setupLayoutManager(this, mMediaView, R.dimen.media_size)
        mSubject =
            TextView(this).apply {
                autoLinkMask = 0
            }
        mTagEditor =
            TagEditorState().apply {
                hint = getString(R.string.details_tags_hint)
                onChanged = { applyEditorButtonState() }
            }
        mAvatar =
            AvatarView(this).apply {
                setOnClickListener { onAvatarClicked() }
                setOnLongClickListener {
                    onAvatarLongClicked()
                    true
                }
            }

        ephemeralDurationValues = resources.getIntArray(R.array.ephemeral_duration_values)
        mAdvancedMode = getPreferences().getBoolean("advanced_muc_mode", false)
        screen = screen.copy(infoMoreVisible = mAdvancedMode)

        // The chrome draws its bar behind the system bars and hands the content the space they
        // leave, so the window must not inset itself for them first - on every API level, which is
        // what enableEdgeToEdge settles. See TulkkiChrome for the whole pattern.
        enableEdgeToEdge()
        setTulkkiContent(darkTheme = isDark()) {
            val state = screen
            TulkkiChrome(
                title = stringResource(state.titleRes),
                onUp = { finish() },
                menu = menuItems(),
            ) {
                ConferenceDetailsScreen(
                    state = state,
                    events = events(),
                    avatar = mAvatar,
                    subjectLine = mSubject,
                    tagEditor = mTagEditor,
                    users = mucUsers,
                    userRow = { user -> userPreviewRow(user) },
                    onUserOpen = { user -> openUser(user) },
                    userMenu = userMenu,
                    onUserMenu = { user -> showUserMenu(user) },
                    onUserMenuDismiss = { closeUserMenu() },
                    onUserMenuSelected = { action ->
                        val user = menuUser
                        closeUserMenu()
                        if (user != null) {
                            MucDetailsContextMenuHelper.onMucDetailsAction(
                                action,
                                user,
                                this@ConferenceDetailsActivity,
                                null,
                            )
                        }
                    },
                    mediaGrid = mMediaView,
                )
            }
            if (ephemeralWarning) {
                EphemeralWarningDialog(
                    onDismiss = { onEphemeralCancelled() },
                    onConfirm = { dontShowAgain -> onEphemeralConfirmed(dontShowAgain) },
                )
            }
        }
    }

    /**
     * The deleted `R.menu.muc_details`, item for item with `onCreateOptionsMenu`'s visibility arms:
     * the share pair only for a private, non-anonymous room, advanced mode always (the old item was
     * a checkable row; the flag is the state now), custom notifications from Android R.
     */
    @Composable
    private fun menuItems(): List<ChromeMenuItem> {
        val items = ArrayList<ChromeMenuItem>()
        val conversation = mConversation
        val groupChat = conversation != null && conversation.isPrivateAndNonAnonymous()
        if (!groupChat) {
            items.add(ChromeMenuItem(stringResource(R.string.share_as_uri)) { shareLink() })
            items.add(ChromeMenuItem(stringResource(R.string.show_qr_code)) { showQrCode() })
        }
        items.add(
            ChromeMenuItem(stringResource(R.string.advanced_mode)) {
                mAdvancedMode = !mAdvancedMode
                getPreferences().edit().putBoolean("advanced_muc_mode", mAdvancedMode).apply()
                screen = screen.copy(infoMoreVisible = mAdvancedMode)
                updateView()
            },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            items.add(
                ChromeMenuItem(stringResource(R.string.custom_notifications)) {
                    mConversation?.let { configureCustomNotifications(it) }
                },
            )
        }
        if (AccountUtils.MANAGE_ACCOUNT_ACTIVITY != null) {
            items.add(
                ChromeMenuItem(stringResource(R.string.action_accounts)) {
                    AccountUtils.launchManageAccounts(this)
                },
            )
        } else {
            items.add(
                ChromeMenuItem(stringResource(R.string.action_account)) {
                    AccountUtils.getFirst(AccountRegistry.get().getAccounts())?.let { switchToAccount(it) }
                },
            )
        }
        items.add(
            ChromeMenuItem(stringResource(R.string.action_settings)) {
                startActivity(Intent(this, uk.xa0.tulkki.ui.activity.SettingsActivity::class.java))
            },
        )
        return items
    }

    /** Every listener the deleted views carried, rebuilt per composition because it closes over none. */
    private fun events(): ConferenceDetailsEvents =
        ConferenceDetailsEvents(
            onEditNameOrTopic = { onEditNameOrTopic() },
            onLeave = { onLeave() },
            onAddToContacts = { onAddToContacts() },
            onDestroy = { onDestroyRoom() },
            onChangeConference = { onChangeConferenceSettings() },
            onBrowseSpace = { onBrowseSpace() },
            onInvite = { inviteToConversation(mConversation) },
            onShowUsers = { onShowUsers() },
            onEditNick = { onEditNick() },
            onNotifications = { onNotifyStatus() },
            onEphemeralToggled = { onEphemeralToggled(it) },
            onEphemeralDurationSelected = { onEphemeralDurationSelected(it) },
            onThread = { openThread(it) },
            onStoreSecurelyChanged = { onStoreSecurelyChanged(it) },
            onShowMedia = {
                MediaBrowserActivity.launch(this, mConversation ?: throw NullPointerException())
            },
            onEditNameChanged = { onEditorTextChanged(it, null) },
            onEditSubjectChanged = { onEditorTextChanged(null, it) },
            onShowAvatar = { onShowAvatar() },
            onBlockAvatar = { onBlockAvatar() },
            onPhotoMenuDismiss = { screen = screen.copy(photoMenuOpen = false) },
        )

    private fun onAvatarClicked() {
        val mucOptions = (mConversation ?: throw NullPointerException()).getMucOptions()
        if (!mucOptions.hasVCards()) {
            Toast.makeText(
                this,
                R.string.host_does_not_support_group_chat_avatars,
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        if (!mucOptions.getSelf().getAffiliation().ranks(MucOptions.Affiliation.OWNER)) {
            Toast.makeText(
                this,
                R.string.only_the_owner_can_change_group_chat_avatar,
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val intent = Intent(this, PublishGroupChatProfilePictureActivity::class.java)
        intent.putExtra("uuid", (mConversation ?: throw NullPointerException()).getUuid())
        startActivity(intent)
    }

    /**
     * The deleted `your_photo` long press: `R.menu.conference_photo` is a Compose `DropdownMenu`
     * now, anchored at the avatar, so the long press only opens it and the two items run below.
     */
    private fun onAvatarLongClicked() {
        screen = screen.copy(photoMenuOpen = true)
    }

    private fun onShowAvatar() {
        screen = screen.copy(photoMenuOpen = false)
        ShowAvatarPopup(mConversation)
    }

    private fun onBlockAvatar() {
        screen = screen.copy(photoMenuOpen = false)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.block_media)
            .setMessage(R.string.block_avatar_question)
            .setPositiveButton(uk.xa0.tulkki.data.R.string.yes) { _, _ ->
                val conversation = mConversation ?: throw NullPointerException()
                xmppConnectionService.blockMedia(
                    FileBackends
                        .get()
                        .getAvatarFile(conversation.getContact().getAvatarFilename()),
                )
                FileBackends
                    .get()
                    .getAvatarFile(conversation.getContact().getAvatarFilename())
                    .delete()
                avatarService().clear(conversation)
                conversation.getContact().setAvatar(null)
                xmppConnectionService.updateConversationUi()
            }
            .setNegativeButton(uk.xa0.tulkki.data.R.string.no, null)
            .show()
    }

    private fun onBrowseSpace() {
        val conversation = mConversation ?: throw NullPointerException()
        val intent = Intent(this, ChannelDiscoveryActivity::class.java)
        intent.putExtra(
            "services",
            arrayOf(
                (conversation.getJid() ?: throw NullPointerException())
                    .getDomain()
                    .toString(),
                (conversation.getAccount() ?: throw NullPointerException())
                    .getJid()
                    .toString(),
            ),
        )
        startActivity(intent)
    }

    private fun onShowUsers() {
        val intent = Intent(this, MucUsersActivity::class.java)
        intent.putExtra("uuid", (mConversation ?: throw NullPointerException()).getUuid())
        startActivity(intent)
    }

    private fun onEditNick() {
        quickEdit(
            (mConversation ?: throw NullPointerException())
                .getMucOptions()
                .getActualNick(),
            R.string.nickname,
        ) { value ->
            if (xmppConnectionService.renameInMuc(mConversation ?: throw NullPointerException(), value, renameCallback)) {
                null
            } else {
                getString(R.string.invalid_muc_nick)
            }
        }
    }

    private fun onStoreSecurelyChanged(checked: Boolean) {
        val conversation = mConversation ?: return
        conversation.setStoreSecurely(checked)
        xmppConnectionService.updateConversation(conversation)
    }

    /** The ephemeral switch, arm for arm: on with the warning, on without it, or off. */
    private fun onEphemeralToggled(isChecked: Boolean) {
        val conversation = mConversation ?: return
        if (isChecked) {
            screen = screen.copy(ephemeral = screen.ephemeral.copy(enabled = true))
            val sp = PreferenceManager.getDefaultSharedPreferences(this)
            if (sp.getBoolean(AppSettings.HIDE_EPHEMERAL_WARNING, false)) {
                applyEphemeralEnabled(conversation)
            } else {
                ephemeralWarning = true
            }
        } else {
            applyEphemeralDisabled(conversation)
        }
    }

    private fun applyEphemeralEnabled(conversation: Conversation) {
        screen =
            screen.copy(
                ephemeral = screen.ephemeral.copy(enabled = true, durationVisible = true),
            )
        val seconds = ephemeralDurationValues[screen.ephemeral.selectedIndex]
        if (conversation.setEphemeralTimer(seconds)) {
            conversation.setEphemeralBy(null)
            DatabaseBackend.get().updateConversation(conversation)
            xmppConnectionService.sendEphemeralImplicitNegotiation(
                conversation,
                seconds,
            )
        }
    }

    private fun applyEphemeralDisabled(conversation: Conversation) {
        screen =
            screen.copy(
                ephemeral = screen.ephemeral.copy(enabled = false, durationVisible = false),
            )
        if (conversation.setEphemeralTimer(0)) {
            DatabaseBackend.get().updateConversation(conversation)
            xmppConnectionService.sendEphemeralIWantOut(conversation)
        }
    }

    /** The deleted dialog's two "off" arms: the negative button and the cancel listener. */
    private fun onEphemeralCancelled() {
        ephemeralWarning = false
        screen =
            screen.copy(
                ephemeral = screen.ephemeral.copy(enabled = false, durationVisible = false),
            )
    }

    /** The deleted dialog's positive button: the check box writes the preference, then the switch takes. */
    private fun onEphemeralConfirmed(dontShowAgain: Boolean) {
        ephemeralWarning = false
        if (dontShowAgain) {
            PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putBoolean(AppSettings.HIDE_EPHEMERAL_WARNING, true)
                .apply()
        }
        val conversation = mConversation ?: return
        applyEphemeralEnabled(conversation)
    }

    /** The deleted spinner's `onItemSelected`, guarded on the timer actually changing. */
    private fun onEphemeralDurationSelected(position: Int) {
        val conversation = mConversation ?: return
        screen = screen.copy(ephemeral = screen.ephemeral.copy(selectedIndex = position))
        val seconds = ephemeralDurationValues[position]
        if (conversation.getEphemeralTimer() != seconds && screen.ephemeral.enabled) {
            if (conversation.setEphemeralTimer(seconds)) {
                conversation.setEphemeralBy(null)
                DatabaseBackend.get().updateConversation(conversation)
                xmppConnectionService.sendEphemeralImplicitNegotiation(
                    conversation,
                    seconds,
                )
            }
        }
    }

    private fun openThread(thread: ThreadRow) {
        switchToConversation(
            mConversation,
            null,
            false,
            null,
            false,
            true,
            null,
            thread.threadId,
            null,
        )
    }

    private fun onLeave() {
        val conversation = mConversation ?: throw NullPointerException()
        val leaveMucDialog = MaterialAlertDialogBuilder(this@ConferenceDetailsActivity)
        leaveMucDialog.setTitle(getString(R.string.action_end_conversation_muc))
        leaveMucDialog.setMessage(getString(R.string.leave_conference_warning))
        leaveMucDialog.setNegativeButton(
            getString(uk.xa0.tulkki.data.R.string.cancel),
            null,
        )
        leaveMucDialog.setPositiveButton(
            getString(R.string.action_end_conversation_muc),
        ) { _, _ ->
            startActivity(
                Intent(xmppConnectionService, ConversationListActivity::class.java),
            )
            overridePendingTransition(R.animator.fade_in, R.animator.fade_out)
            xmppConnectionService.archiveConversation(conversation)
            finish()
        }
        leaveMucDialog.create().show()
    }

    private fun onAddToContacts() {
        val conversation = mConversation ?: throw NullPointerException()
        if (conversation.getBookmark() != null) {
            val deleteFromRosterDialog =
                MaterialAlertDialogBuilder(this@ConferenceDetailsActivity)
            deleteFromRosterDialog.setNegativeButton(
                getString(uk.xa0.tulkki.data.R.string.cancel),
                null,
            )
            deleteFromRosterDialog.setTitle(getString(R.string.action_delete_contact))
            deleteFromRosterDialog.setMessage(
                getString(
                    R.string.remove_bookmark_text,
                    (conversation.getBookmark() ?: throw NullPointerException())
                        .getBookmarkName(),
                ),
            )
            deleteFromRosterDialog.setPositiveButton(getString(R.string.delete)) { _, _ ->
                deleteBookmark()
                recreate()
            }
            deleteFromRosterDialog.create().show()
        } else {
            saveAsBookmark()
            recreate()
        }
    }

    /** The deleted `destroy` button's dialog, with the destroy intent and the bookmark deletion. */
    private fun onDestroyRoom() {
        val destroyMucDialog = MaterialAlertDialogBuilder(this@ConferenceDetailsActivity)
        destroyMucDialog.setNegativeButton(getString(uk.xa0.tulkki.data.R.string.cancel), null)
        val conversation = mConversation
        val groupChat =
            conversation != null && conversation.isPrivateAndNonAnonymous()
        destroyMucDialog.setTitle(
            if (groupChat) R.string.destroy_room else R.string.destroy_channel,
        )
        destroyMucDialog.setMessage(
            getString(
                if (groupChat) R.string.destroy_room_dialog else R.string.destroy_channel_dialog,
                (conversation ?: throw NullPointerException()).getName(),
            ),
        )
        destroyMucDialog.setPositiveButton(getString(R.string.delete)) { _, _ ->
            val intent = Intent(xmppConnectionService, ConversationListActivity::class.java)
            intent.setAction(ConversationListActivity.ACTION_DESTROY_MUC)
            intent.putExtra("MUC_UUID", (conversation ?: throw NullPointerException()).getUuid())
            Log.d(
                Config.LOGTAG,
                "Sending DESTROY intent for " +
                    (conversation ?: throw NullPointerException()).getName(),
            )
            startActivity(intent)
            overridePendingTransition(R.animator.fade_in, R.animator.fade_out)
            deleteBookmark()
            finish()
        }
        destroyMucDialog.create().show()
    }

    public override fun onStart() {
        super.onStart()
        screen =
            screen.copy(
                mediaVisible = UiHost.installed().hasStoragePermission(this),
            )
    }

    private fun configureCustomNotifications(conversation: Conversation) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            conversation.getMode() != Conversation.MODE_MULTI
        ) {
            return
        }
        val shortcut =
            xmppConnectionService
                .getShortcutService()
                .getShortcutInfo(conversation.getMucOptions())
        configureCustomNotification(shortcut)
    }

    /**
     * One participant's row facts, the values `UserPreviewAdapter.onBindViewHolder` loaded: the
     * `show_contact_status` preference and the account's connection state decide the presence dot,
     * exactly as `PresenceIndicator` did, and the avatar is the user `AvatarWorkerTask` loaded.
     */
    private fun userPreviewRow(user: MucOptions.User): ConferenceUserRow {
        val contact = user.getContact()
        val showStatus =
            contact != null &&
                getPreferences()
                    .getBoolean("show_contact_status", resources.getBoolean(R.bool.show_contact_status)) &&
                contact.account?.isOnlineAndConnected() == true
        return ConferenceUserRow(
            key = user.getFullJid()?.toString() ?: user.getRealJid()?.toString() ?: user.getNick() ?: "",
            presenceColor =
                if (showStatus && contact != null) {
                    UIHelper.getColorForStatus(contact.shownStatus)
                } else {
                    null
                },
            avatarable = user,
        )
    }

    /** The adapter's row tap: the left-conference toast, then `highlightInMuc`. */
    private fun openUser(user: MucOptions.User) {
        val contact = user.getContact()
        if (user.getRole() == MucOptions.Role.NONE && contact != null) {
            Toast.makeText(
                this,
                getString(R.string.user_has_left_conference, contact.getDisplayName()),
                Toast.LENGTH_SHORT,
            ).show()
        }
        highlightInMuc(user.getConversation(), user.getName())
    }

    /**
     * The row's long press: the deleted `PopupMenu` + `inflate(R.menu.muc_details_context)`, as the
     * Compose menu the screen draws at the row. The entries are the helper's own visibility
     * arithmetic; nothing is inflated.
     */
    private fun showUserMenu(user: MucOptions.User) {
        val conversation = mConversation ?: return
        menuUser = user
        userMenu =
            MucUserMenu(
                key = userPreviewRow(user).key,
                entries = MucDetailsContextMenuHelper.visibleEntries(this, conversation, user),
            )
    }

    /** The menu's own dismissal, or the end of a selection: nothing is left for the next row. */
    private fun closeUserMenu() {
        userMenu = null
        menuUser = null
    }

    /**
     * The deleted `muc_edit_name_button`: opening fills the fields from the room, a second press
     * saves them. The old code read the `EditText`s back; the fields are the state's now.
     */
    private fun onEditNameOrTopic() {
        val conversation = mConversation ?: throw NullPointerException()
        if (!screen.editorVisible) {
            openEditor(conversation)
        } else {
            val subject =
                if (screen.editorSubjectEnabled) {
                    screen.editSubject.trim { it <= ' ' }
                } else {
                    null
                }
            val name =
                if (screen.editorNameEnabled) {
                    screen.editName.trim { it <= ' ' }
                } else {
                    null
                }
            onMucInfoUpdated(subject, name)

            val bookmark = conversation.getBookmark()
            if (bookmark != null &&
                (conversation.getAccount() ?: throw NullPointerException())
                    .getXmppConnection()
                    ?.getFeatures()
                    ?.bookmarks2() == true
            ) {
                bookmark.setGroups(mTagEditor.getObjects().map { tag -> tag.name })
                xmppConnectionService.createBookmark(bookmark.getAccount(), bookmark)
            }

            SoftKeyboardUtils.hideSoftKeyboard(this)
            hideEditor()
            updateView()
        }
    }

    private fun openEditor(conversation: Conversation) {
        val mucOptions = conversation.getMucOptions()
        val owner =
            mucOptions.getSelf().getAffiliation().ranks(MucOptions.Affiliation.OWNER)
        val name = mucOptions.getName()
        val nameVisible = owner || Bookmark.printableValue(name)
        val subject = mucOptions.getSubject()
        val bookmark = conversation.getBookmark()
        val tagsVisible =
            bookmark != null &&
                (conversation.getAccount() ?: throw NullPointerException())
                    .getXmppConnection()
                    ?.getFeatures()
                    ?.bookmarks2() == true &&
                showDynamicTags
        screen =
            screen.copy(
                editorVisible = true,
                editorNameVisible = nameVisible,
                editorNameEnabled = owner,
                editName = if (nameVisible && name != null) name else "",
                editorSubjectEnabled = mucOptions.canChangeSubject(),
                editSubject = subject ?: "",
                editorTagsVisible = tagsVisible,
                editorButtonIconRes = R.drawable.ic_cancel_24dp,
                editorButtonDescriptionRes = uk.xa0.tulkki.data.R.string.cancel,
            )
        if (tagsVisible) {
            mTagEditor.clearSync()
            for (group in bookmark.getGroupTags()) {
                mTagEditor.addObjectSync(group)
            }
            val tags = ArrayList<ListItem.Tag>()
            for (account in AccountRegistry.get().getAccounts()) {
                for (contact in account.getRoster().getContacts()) {
                    tags.addAll(contact.getTags(this))
                }
                for (bmark in account.getBookmarks()) {
                    tags.addAll(bmark.getTags(this))
                }
            }
            // The Java counted the tags through `Collectors.toMap` and re-sorted the entry set
            // by descending count then by name; the same multiset and total order are built here
            // without the stream chain.
            val tagCounts = HashMap<ListItem.Tag, Int>()
            for (tag in tags) {
                tagCounts[tag] = (tagCounts[tag] ?: 0) + 1
            }
            val sortedTags =
                tagCounts.entries
                    .sortedWith(
                        compareByDescending<Map.Entry<ListItem.Tag, Int>> { it.value }
                            .thenBy { it.key.name },
                    )
                    .map { it.key }
            val adapter =
                android.widget.ArrayAdapter(
                    this,
                    android.R.layout.simple_list_item_1,
                    sortedTags,
                )
            mTagEditor.setAdapter(adapter)
        }
    }

    private fun hideEditor() {
        screen =
            screen.copy(
                editorVisible = false,
                editorButtonIconRes = R.drawable.ic_edit_24dp,
                editorButtonDescriptionRes = R.string.edit_name_and_topic,
            )
    }

    private fun onMucInfoUpdated(subject: String?, name: String?) {
        val conversation = mConversation ?: throw NullPointerException()
        val mucOptions = conversation.getMucOptions()
        if (mucOptions.canChangeSubject() && StringUtils.changed(mucOptions.getSubject(), subject)) {
            xmppConnectionService.pushSubjectToConference(conversation, subject)
        }
        if (mucOptions.getSelf().getAffiliation().ranks(MucOptions.Affiliation.OWNER) &&
            StringUtils.changed(mucOptions.getName(), name)
        ) {
            val options = Bundle()
            options.putString("muc#roomconfig_persistentroom", "1")
            options.putString("muc#roomconfig_roomname", StringUtils.nullOnEmpty(name))
            xmppConnectionService.pushConferenceConfiguration(conversation, options, this)
        }
    }

    protected override fun getShareableUri(): String? {
        val conversation = mConversation
        return if (conversation != null) {
            // The `xmpp:` join URI only: the web-invite form was a third-party invite host and
            // there is no Tulkki page to replace it with.
            "xmpp:" +
                Uri.encode(
                    (conversation.getJid() ?: throw NullPointerException())
                        .asBareJid()
                        .toString(),
                    "@/+",
                ) +
                "?join"
        } else {
            null
        }
    }

    override fun onMediaLoaded(attachments: List<uk.xa0.tulkki.libs.AttachmentRef>) {
        runOnUiThread {
            val limit = GridManager.getCurrentColumnCount(mMediaView)
            mMediaAdapter.setAttachments(
                attachments.subList(0, minOf(limit, attachments.size)),
            )
            screen = screen.copy(showMediaVisible = attachments.isNotEmpty())
        }
    }

    protected fun saveAsBookmark() {
        val conversation = mConversation ?: throw NullPointerException()
        xmppConnectionService.saveConversationAsBookmark(
            conversation,
            conversation.getMucOptions().getName(),
        )
    }

    protected fun destroyRoom() {
        val conversation = mConversation
        val groupChat = conversation != null && conversation.isPrivateAndNonAnonymous()
        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle(if (groupChat) R.string.destroy_room else R.string.destroy_channel)
        builder.setMessage(
            if (groupChat) R.string.destroy_room_dialog else R.string.destroy_channel_dialog,
        )
        builder.setPositiveButton(R.string.ok) { _, _ ->
            xmppConnectionService.destroyRoom(
                mConversation ?: throw NullPointerException(),
                this@ConferenceDetailsActivity,
            )
        }
        builder.setNegativeButton(uk.xa0.tulkki.data.R.string.cancel, null)
        val dialog = builder.create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
    }

    override fun onBackendConnected() {
        val invite = mPendingConferenceInvite
        if (invite != null) {
            invite.execute(this)
            mPendingConferenceInvite = null
        }
        if (getIntent().getAction().equals(ACTION_VIEW_MUC)) {
            uuid = (getIntent().getExtras() ?: throw NullPointerException()).getString("uuid")
        }
        val uuid = this.uuid
        if (uuid != null) {
            mConversation = xmppConnectionService.findConversationByUuid(uuid) as Conversation?
            val conversation = mConversation
            if (conversation != null) {
                if (UiHost.installed().hasStoragePermission(this)) {
                    val limit = GridManager.getCurrentColumnCount(mMediaView)
                    xmppConnectionService.getAttachments(conversation, limit, this)
                }

                val storeSecurely = conversation.storeSecurely(xmppConnectionService)
                screen = screen.copy(storeSecurely = storeSecurely)

                updateView()
            }
        }
    }

    public override fun onBackPressed() {
        if (screen.editorVisible) {
            hideEditor()
        } else {
            super.onBackPressed()
        }
    }

    /**
     * The deleted `updateView`: every value the layout was given becomes [ConferenceDetailsState].
     * The deleted code's two assignments to `usersWrapper.visibility` are kept in the same order, so
     * the later one - users present or invitable - is the one that stands, offline included.
     */
    private fun updateView() {
        val conversation = mConversation ?: return
        val previous = screen
        val mucOptions = conversation.getMucOptions()
        val self = mucOptions.getSelf()
        val account =
            (conversation.getAccount() ?: throw NullPointerException())
                .getJid()
                .asBareJid()
                .toString()

        var next =
            screen.copy(
                titleRes =
                    if (mucOptions.isPrivateAndNonAnonymous()) {
                        R.string.action_muc_details
                    } else {
                        R.string.channel_details
                    },
                accountLine = getString(R.string.using_account, account),
                trueJid = null,
            )

        val bookmark = conversation.getBookmark()
        val connection =
            (conversation.getAccount() ?: throw NullPointerException()).getXmppConnection()
        next =
            next.copy(
                editButtonVisible =
                    self.getAffiliation().ranks(MucOptions.Affiliation.OWNER) ||
                        mucOptions.canChangeSubject() ||
                        (bookmark != null &&
                            connection != null &&
                            connection.getFeatures().bookmarks2()),
            )

        if (conversation.isPrivateAndNonAnonymous()) {
            next =
                next.copy(
                    jid =
                        getString(
                            R.string.hosted_on,
                            (conversation.getJid() ?: throw NullPointerException()).getDomain(),
                        ),
                    trueJid =
                        if (mAdvancedMode) {
                            (conversation.getJid() ?: throw NullPointerException())
                                .asBareJid()
                                .toString()
                        } else {
                            null
                        },
                )
        } else {
            next =
                next.copy(
                    jid =
                        (conversation.getJid() ?: throw NullPointerException())
                            .asBareJid()
                            .toString(),
                )
        }

        AvatarWorkerTask.loadAvatar(
            conversation,
            mAvatar,
            R.dimen.avatar_on_details_screen_size,
        )
        val roomName = mucOptions.getName()
        val subject = mucOptions.getSubject()
        val hasTitle: Boolean
        if (Bookmark.printableValue(roomName)) {
            next = next.copy(title = roomName)
            hasTitle = true
        } else if (!Bookmark.printableValue(subject)) {
            // Tulkki: `Conversation.getName()` is its `CharSequence`, and the Compose title is a
            // `String?`; the deleted `TextView` took the `CharSequence` as it came.
            next = next.copy(title = conversation.getName().toString())
            hasTitle = true
        } else {
            hasTitle = false
            next = next.copy(title = null)
        }
        if (Bookmark.printableValue(subject)) {
            val subjectText = subject ?: throw NullPointerException()
            val spannable = SpannableStringBuilder(subjectText)
            StylingHelper.format(spannable, mSubject.currentTextColor)
            MyLinkify.addLinks(spannable, false)
            mSubject.setText(spannable)
            mSubject.setTextAppearance(
                if (subjectText.length > (if (hasTitle) 128 else 196)) {
                    com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
                } else {
                    com.google.android.material.R.style.TextAppearance_Material3_BodyLarge
                },
            )
            mSubject.autoLinkMask = 0
            mSubject.movementMethod = LinkMovementMethod.getInstance()
            next = next.copy(subject = subjectText)
        } else {
            next = next.copy(subject = null)
        }

        next = next.copy(nick = mucOptions.getActualNick() ?: "")
        if (mucOptions.online()) {
            next = next.copy(usersVisible = true)
            next = next.copy(infoMoreVisible = mAdvancedMode)
            next = next.copy(role = getStatus(self))
            if (self.getAffiliation().ranks(MucOptions.Affiliation.OWNER)) {
                next =
                    next.copy(
                        settingsVisible = true,
                        changeConferenceVisible = true,
                        conferenceType = MucConfiguration.describe(this, mucOptions),
                    )
            } else if (!mucOptions.isPrivateAndNonAnonymous() && mucOptions.nonanonymous()) {
                next =
                    next.copy(
                        settingsVisible = true,
                        changeConferenceVisible = false,
                        conferenceType =
                            getString(R.string.group_chat_will_make_your_jabber_id_public),
                    )
            } else {
                next =
                    next.copy(
                        settingsVisible = false,
                        changeConferenceVisible = false,
                        conferenceType = previous.conferenceType,
                    )
            }
            next =
                next.copy(
                    mamTextRes =
                        if (mucOptions.mamSupport()) {
                            R.string.server_info_available
                        } else {
                            R.string.server_info_unavailable
                        },
                )
            if (self.getAffiliation().ranks(MucOptions.Affiliation.OWNER)) {
                if (mAdvancedMode) {
                    next =
                        next.copy(
                            destroyLabelRes =
                                if (conversation.isPrivateAndNonAnonymous()) {
                                    R.string.destroy_room
                                } else {
                                    R.string.destroy_channel
                                },
                            destroyContainerColor =
                                resources.getColor(R.color.md_theme_dark_error),
                        )
                } else {
                    next = next.copy(destroyLabelRes = null)
                }
                next = next.copy(changeConferenceVisible = true)
            } else {
                next = next.copy(destroyLabelRes = null, changeConferenceVisible = false)
            }
            next =
                next.copy(
                    leaveLabelRes =
                        if (conversation.isPrivateAndNonAnonymous()) {
                            R.string.action_end_conversation_muc
                        } else {
                            R.string.action_end_conversation_channel
                        },
                    leaveContainerColor = resources.getColor(R.color.md_theme_dark_error),
                    addLabelRes =
                        if (conversation.getBookmark() != null) {
                            R.string.delete_bookmark
                        } else {
                            R.string.save_as_bookmark
                        },
                    addContainerColor =
                        if (conversation.getBookmark() != null) {
                            resources.getColor(R.color.md_theme_dark_error)
                        } else {
                            resources.getColor(R.color.md_theme_light_surface)
                        },
                )
        } else {
            next =
                next.copy(
                    usersVisible = false,
                    infoMoreVisible = false,
                    settingsVisible = false,
                    mamTextRes = previous.mamTextRes,
                    role = previous.role,
                )
        }

        val mutedTill =
            conversation.getLongAttribute(Conversation.ATTRIBUTE_MUTED_TILL, 0)
        if (mutedTill == Long.MAX_VALUE) {
            next =
                next.copy(
                    notificationTextRes = R.string.notify_never,
                    notificationIconRes = R.drawable.ic_notifications_off_24dp,
                )
        } else if (System.currentTimeMillis() < mutedTill) {
            next =
                next.copy(
                    notificationTextRes = R.string.notify_paused,
                    notificationIconRes = R.drawable.ic_notifications_paused_24dp,
                )
        } else if (conversation.alwaysNotify()) {
            next =
                next.copy(
                    notificationTextRes = R.string.notify_on_all_messages,
                    notificationIconRes = R.drawable.ic_notifications_24dp,
                )
        } else if (conversation.notifyReplies()) {
            next =
                next.copy(
                    notificationTextRes = R.string.notify_only_when_highlighted_or_replied,
                    notificationIconRes = R.drawable.ic_notifications_none_24dp,
                )
        } else {
            next =
                next.copy(
                    notificationTextRes = R.string.notify_only_when_highlighted,
                    notificationIconRes = R.drawable.ic_notifications_none_24dp,
                )
        }

        val users = mucOptions.getUsers()
        users.sortWith { a, b ->
            if (b.getAffiliation().outranks(a.getAffiliation())) {
                1
            } else if (a.getAffiliation().outranks(b.getAffiliation())) {
                -1
            } else {
                if (a.getAvatar() != null && b.getAvatar() == null) {
                    -1
                } else if (a.getAvatar() == null && b.getAvatar() != null) {
                    1
                } else {
                    a.getComparableName().compareTo(b.getComparableName(), ignoreCase = true)
                }
            }
        }
        mucUsers = users
        next =
            next.copy(
                inviteVisible = mucOptions.canInvite(),
                showUsersVisible =
                    mucOptions
                        .getUsers(
                            true,
                            mucOptions
                                .getSelf()
                                .getAffiliation()
                                .ranks(MucOptions.Affiliation.ADMIN),
                        )
                        .size > 0,
                showUsersLabel =
                    resources.getQuantityString(R.plurals.view_users, users.size, users.size),
                usersVisible = users.size > 0 || mucOptions.canInvite(),
                noUsersHintRes =
                    if (users.size == 0) {
                        if (mucOptions.isPrivateAndNonAnonymous()) {
                            R.string.no_users_hint_group_chat
                        } else {
                            R.string.no_users_hint_channel
                        }
                    } else {
                        null
                    },
            )

        if (bookmark == null) {
            next = next.copy(tags = emptyList())
            screen = next
            return
        }

        val recentThreads = conversation.recentThreads()
        next = next.copy(threads = recentThreads.map { ThreadRow(it.getThreadId(), it.getDisplay() ?: "") })

        val timer = conversation.getEphemeralTimer()
        val ephemeralEntries = resources.getStringArray(R.array.ephemeral_durations).toList()
        val isPublic = !conversation.isPrivateAndNonAnonymous()
        if (isPublic) {
            next =
                next.copy(
                    ephemeral =
                        EphemeralRow(
                            visible = false,
                            enabled = false,
                            durationVisible = false,
                            entries = ephemeralEntries,
                            selectedIndex = 0,
                        ),
                )
            if (timer > 0) {
                conversation.setEphemeralTimer(0)
                DatabaseBackend.get().updateConversation(conversation)
                xmppConnectionService.sendEphemeralIWantOut(conversation)
            }
        } else {
            next =
                next.copy(
                    ephemeral =
                        EphemeralRow(
                            visible = true,
                            enabled = timer > 0,
                            durationVisible = timer > 0,
                            entries = ephemeralEntries,
                            selectedIndex = if (timer > 0) ephemeralIndex(timer) else 0,
                        ),
                )
        }

        next = next.copy(tags = bookmarkTags(bookmark))
        screen = next
    }

    /** The deleted spinner's `setSelection`: the entry whose value array slot is the live timer. */
    private fun ephemeralIndex(timer: Int): Int {
        for (i in ephemeralDurationValues.indices) {
            if (ephemeralDurationValues[i] == timer) {
                return i
            }
        }
        return 0
    }

    /** The bookmark's tag chips, exactly as the deleted `updateView` built the `item_tag` views. */
    private fun bookmarkTags(bookmark: Bookmark): List<TagChip> {
        val tagList = bookmark.getTags(this)
        if (tagList.isEmpty() || !showDynamicTags) {
            return emptyList()
        }
        val chips = ArrayList<TagChip>()
        for (tag in tagList) {
            chips.add(
                TagChip(
                    tag.name,
                    MaterialColors.harmonizeWithPrimary(
                        this,
                        XEP0392Helper.rgbFromNick(tag.name),
                    ),
                ),
            )
        }
        return chips
    }

    private fun getStatus(user: MucOptions.User): String = getStatus(this, user, mAdvancedMode)

    override fun onAffiliationChangedSuccessful(jid: Jid) {
        refreshUi()
    }

    override fun onAffiliationChangeFailed(jid: Jid, resId: Int) {
        displayToast(getString(resId, jid.asBareJid().toString()))
    }

    override fun onRoomDestroySucceeded() {
        finish()
    }

    override fun onRoomDestroyFailed() {
        val conversation = mConversation
        val groupChat = conversation != null && conversation.isPrivateAndNonAnonymous()
        displayToast(
            getString(
                if (groupChat) R.string.could_not_destroy_room else R.string.could_not_destroy_channel,
            ),
        )
    }

    override fun onPushSucceeded() {
        displayToast(getString(R.string.modified_conference_options))
    }

    override fun onPushFailed() {
        displayToast(getString(R.string.could_not_modify_conference_options))
    }

    private fun displayToast(msg: String) {
        runOnUiThread {
            if (isFinishing) {
                return@runOnUiThread
            }
            ToastCompat.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    /** One of the editor's two fields changed: the same recalculation the deleted `TextWatcher` ran. */
    private fun onEditorTextChanged(name: String?, subject: String?) {
        screen =
            screen.copy(
                editName = name ?: screen.editName,
                editSubject = subject ?: screen.editSubject,
            )
        applyEditorButtonState()
    }

    /**
     * The deleted `afterTextChanged`: while the editor is open the button is a save icon when
     * anything differs from the room, a cancel icon when nothing does.
     */
    private fun applyEditorButtonState() {
        val conversation = mConversation ?: return
        val mucOptions = conversation.getMucOptions()
        if (!screen.editorVisible) {
            return
        }
        val subjectChanged =
            StringUtils.changed(screen.editSubject, mucOptions.getSubject())
        val nameChanged =
            StringUtils.changed(screen.editName, mucOptions.getName())
        val bookmark = conversation.getBookmark()
        val changed =
            subjectChanged ||
                nameChanged ||
                (bookmark != null &&
                    (conversation.getAccount() ?: throw NullPointerException())
                        .getXmppConnection()
                        ?.getFeatures()
                        ?.bookmarks2() == true)
        screen =
            screen.copy(
                editorButtonIconRes =
                    if (changed) R.drawable.ic_save_24dp else R.drawable.ic_cancel_24dp,
                editorButtonDescriptionRes =
                    if (changed) R.string.save else uk.xa0.tulkki.data.R.string.cancel,
            )
    }

    companion object {
        public const val ACTION_VIEW_MUC = "view_muc"

        @JvmStatic
        public fun open(activity: Activity, conversation: Conversation) {
            val intent = Intent(activity, ConferenceDetailsActivity::class.java)
            intent.setAction(ACTION_VIEW_MUC)
            intent.putExtra("uuid", conversation.getUuid())
            activity.startActivity(intent)
        }

        @JvmStatic
        public fun getStatus(context: Context, user: MucOptions.User, advanced: Boolean): String {
            var hats = context.getString(user.getRole().getResId())
            if (advanced) {
                for (hat in user.getHats()) {
                    hats += ", " + hat
                }
                return String.format(
                    "%s, %s",
                    context.getString(user.getAffiliation().getResId()),
                    hats,
                )
            } else {
                return context.getString(user.getAffiliation().getResId())
            }
        }
    }
}
