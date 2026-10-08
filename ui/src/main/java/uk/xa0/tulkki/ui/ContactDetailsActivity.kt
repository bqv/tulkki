package uk.xa0.tulkki.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.provider.ContactsContract.CommonDataKinds
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Intents
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.ArrayList
import java.util.Collections
import java.util.HashMap
import org.openintents.openpgp.util.OpenPgpUtils
import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.AppSettings
import uk.xa0.tulkki.data.DatabaseBackend
import uk.xa0.tulkki.data.model.Contact
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.ListItem
import uk.xa0.tulkki.data.model.Presence
import uk.xa0.tulkki.ui.adapter.MediaAdapter
import uk.xa0.tulkki.ui.chrome.ChromeMenuItem
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import uk.xa0.tulkki.ui.details.ContactDetailsEvents
import uk.xa0.tulkki.ui.details.ContactDetailsScreen
import uk.xa0.tulkki.ui.details.ContactDetailsState
import uk.xa0.tulkki.ui.details.ContactProfileRow
import uk.xa0.tulkki.ui.details.EphemeralRow
import uk.xa0.tulkki.ui.details.EphemeralWarningDialog
import uk.xa0.tulkki.ui.details.TagChip
import uk.xa0.tulkki.ui.details.ThreadRow
import uk.xa0.tulkki.ui.details.annotatedJid
import uk.xa0.tulkki.ui.extras.TagEditorState
import uk.xa0.tulkki.ui.host.UiHost
import uk.xa0.tulkki.ui.interfaces.OnMediaLoaded
import uk.xa0.tulkki.ui.omemo.ContactKeyRowState
import uk.xa0.tulkki.ui.text.FixedURLSpan
import uk.xa0.tulkki.ui.util.AvatarWorkerTask
import uk.xa0.tulkki.ui.util.GridManager
import uk.xa0.tulkki.ui.util.JidDialog
import uk.xa0.tulkki.ui.util.ShareUtil
import uk.xa0.tulkki.ui.util.SoftKeyboardUtils
import uk.xa0.tulkki.ui.utils.Emoticons
import uk.xa0.tulkki.ui.utils.UIHelper
import uk.xa0.tulkki.ui.utils.XEP0392Helper
import uk.xa0.tulkki.ui.widget.AvatarView
import uk.xa0.tulkki.ui.widget.PresenceIndicator
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.OnKeyStatusUpdated
import uk.xa0.tulkki.xmpp.OnUpdateBlocklist
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xmpp.utils.AccountUtils
import uk.xa0.tulkki.xmpp.utils.IrregularUnicodeDetector
import uk.xa0.tulkki.xmpp.utils.XmppUri
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace

/**
 * One contact's details screen.
 *
 * <p>**The layout is gone.** `activity_contact_details.xml` held the toolbar, the `ScrollView` and
 * every view inside it, and the file is deleted; the bar is the shared chrome
 * ([TulkkiChrome]) now, the body is [ContactDetailsScreen], and the menu
 * (`R.menu.contact_details`) is the chrome's overflow. `setSupportActionBar`,
 * `configureActionBar`, `Activities.setStatusAndNavigationBarColors` and the whole
 * `onCreateOptionsMenu`/`onOptionsItemSelected` pair went with the views they belonged to, and the
 * items the menu's `AccountUtils.showHideMenuItems` chose between are computed from the same
 * `AccountUtils.MANAGE_ACCOUNT_ACTIVITY` at composition time.
 *
 * <p>**The screen's own state is [ContactDetailsState].** `populateView` computes it from the
 * contact instead of writing into views; the two surfaces that are still views (the avatar and its
 * presence dot, the media grid) are built here, in the activity, and handed to the screen as slots -
 * the same shape `AbstractSearchableListItemActivity` uses for its list slot. The vCard profile list
 * is [ContactProfileRow]s this activity resolves and the screen draws, and the keys section is
 * [keyRows], the values `OmemoActivity.contactKeyRow` resolves for each session (the OpenPGP key
 * included), which the screen draws as Compose rows.
 *
 * <p>**The warning dialog is Compose.** `dialog_ephemeral_warning.xml` is deleted and
 * [EphemeralWarningDialog] is launched from the same arm of the ephemeral switch, with the same two
 * side effects: the check box writes `AppSettings.HIDE_EPHEMERAL_WARNING`, and cancel-and-dismiss
 * put the switch back off.
 *
 * <p>Ported from Java by the `ui43h` lane; the Java's unguarded dereferences of the `:data` model's
 * nullable members are kept as `?: throw NullPointerException()` exactly as they were.
 */
class ContactDetailsActivity :
    OmemoActivity(),
    uk.xa0.tulkki.xmpp.services.OnAccountUpdate,
    uk.xa0.tulkki.xmpp.services.OnRosterUpdate,
    OnUpdateBlocklist,
    OnKeyStatusUpdated,
    OnMediaLoaded {

    private var screen by mutableStateOf(ContactDetailsState())

    /** The deleted dialog's visibility, now a flag the composition reads. */
    private var ephemeralWarning by mutableStateOf(false)

    private lateinit var mMediaAdapter: MediaAdapter
    private lateinit var mMediaView: RecyclerView
    private var keyRows by mutableStateOf<List<ContactKeyRowState>>(emptyList())
    private lateinit var mTagEditor: TagEditorState
    private lateinit var mAvatar: AvatarView
    private lateinit var mPresenceIndicator: PresenceIndicator

    private var contact: Contact? = null
    private var accountJid: Jid? = null
    private var contactJid: Jid? = null
    private var showDynamicTags = false
    private var showLastSeen = false
    private var showInactiveOmemo = false
    private var messageFingerprint: String? = null
    private var ephemeralDurationValues: IntArray = IntArray(0)

    private val removeFromRoster =
        DialogInterface.OnClickListener { _, _ ->
            xmppConnectionService.deleteContactOnServer(contact ?: throw NullPointerException())
        }

    private fun archiveContact() {
        val thisAccount =
            AccountRegistry.get().findAccountByJid(accountJid ?: throw NullPointerException())
        if (thisAccount == null) {
            return
        }
        val conversation =
            xmppConnectionService.findOrCreateConversation(
                thisAccount,
                (contact ?: throw NullPointerException()).getJid(),
                false,
                true,
            ) as Conversation
        xmppConnectionService.archiveConversation(conversation)
    }

    /** The one conversation this screen is about, exactly as the deleted call sites built it. */
    private fun conversation(): Conversation? {
        val thisAccount =
            AccountRegistry.get().findAccountByJid(accountJid ?: return null) ?: return null
        val contact = contact ?: return null
        return xmppConnectionService.findOrCreateConversation(
            thisAccount,
            contact.getJid(),
            false,
            true,
        ) as Conversation
    }

    private fun onSendCheckedChanged(isChecked: Boolean) {
        if (isChecked) {
            if ((contact ?: throw NullPointerException())
                    .getOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST)
            ) {
                xmppConnectionService.stopPresenceUpdatesTo(contact ?: throw NullPointerException())
            } else {
                (contact ?: throw NullPointerException()).setOption(Contact.Options.PREEMPTIVE_GRANT)
            }
        } else {
            (contact ?: throw NullPointerException()).resetOption(Contact.Options.PREEMPTIVE_GRANT)
            xmppConnectionService.sendPresencePacket(
                (contact ?: throw NullPointerException()).getAccount(),
                xmppConnectionService
                    .getPresenceGenerator()
                    .stopPresenceUpdatesTo(contact ?: throw NullPointerException()),
            )
        }
    }

    private fun onReceiveCheckedChanged(isChecked: Boolean) {
        if (isChecked) {
            xmppConnectionService.sendPresencePacket(
                (contact ?: throw NullPointerException()).getAccount(),
                xmppConnectionService
                    .getPresenceGenerator()
                    .requestPresenceUpdatesFrom(contact ?: throw NullPointerException()),
            )
        } else {
            xmppConnectionService.sendPresencePacket(
                (contact ?: throw NullPointerException()).getAccount(),
                xmppConnectionService
                    .getPresenceGenerator()
                    .stopPresenceUpdatesFrom(contact ?: throw NullPointerException()),
            )
        }
    }

    /**
     * The deleted `mOnFollowFeedCheckedChange`: the switch is disabled while the request is in
     * flight, a result sets `followed` and the activity result, and a failure puts the switch back
     * where it was. The "detach the listener to prevent a loop" dance is gone because the Compose
     * switch is controlled - leaving its state alone *is* the revert.
     */
    private fun onFollowFeedCheckedChanged(isChecked: Boolean) {
        val contact = contact ?: return
        if (isChecked) {
            screen = screen.copy(followFeedEnabled = false)
            xmppConnectionService.subscribeTo(
                contact.getAccount(),
                contact.getJid(),
                Namespace.MICROBLOG,
            ) { packet ->
                runOnUiThread {
                    if (packet.getType() == Iq.Type.RESULT) {
                        (contact ?: throw NullPointerException()).setFollowed(true)
                        xmppConnectionService.updateContact(contact ?: throw NullPointerException())
                        setResult(Activity.RESULT_OK)
                        screen = screen.copy(followFeedEnabled = true, followFeedChecked = true)
                    } else {
                        screen = screen.copy(followFeedEnabled = true, followFeedChecked = false)
                        Toast.makeText(
                            this@ContactDetailsActivity,
                            R.string.error_subscribing_to_feed,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        } else {
            screen = screen.copy(followFeedEnabled = false)
            xmppConnectionService.unsubscribeFrom(
                contact.getAccount(),
                contact.getJid(),
                Namespace.MICROBLOG,
            ) { packet ->
                runOnUiThread {
                    if (packet.getType() == Iq.Type.RESULT) {
                        (contact ?: throw NullPointerException()).setFollowed(false)
                        xmppConnectionService.updateContact(contact ?: throw NullPointerException())
                        setResult(Activity.RESULT_OK)
                        screen = screen.copy(followFeedEnabled = true, followFeedChecked = false)
                    } else {
                        screen = screen.copy(followFeedEnabled = true, followFeedChecked = true)
                        Toast.makeText(
                            this@ContactDetailsActivity,
                            R.string.error_unsubscribing_from_feed,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }
    }

    private fun onCallsDisabledChanged(isChecked: Boolean) {
        (contact ?: throw NullPointerException()).setCallsDisabled(isChecked)
        xmppConnectionService.updateContact(contact ?: throw NullPointerException())
    }

    private fun checkContactPermissionAndShowAddDialog() {
        if (hasContactsPermission()) {
            showAddToPhoneBookDialog()
        } else if (UiHost.installed().contactListIntegration(this) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.READ_CONTACTS),
                REQUEST_SYNC_CONTACTS,
            )
        }
    }

    private fun hasContactsPermission(): Boolean {
        return if (UiHost.installed().contactListIntegration(this) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
        ) {
            checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun showAddToPhoneBookDialog() {
        val jid = (contact ?: throw NullPointerException()).getJid()
        val quicksyContact =
            UiHost.installed().quicksy() &&
                Config.QUICKSY_DOMAIN == jid.getDomain() &&
                jid.getLocal() != null
        val value: String
        if (quicksyContact) {
            value = UiHost.installed().formattedPhoneNumber(this, jid) ?: throw NullPointerException()
        } else {
            value = jid.toString()
        }
        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle(getString(R.string.action_add_phone_book))
        builder.setMessage(getString(R.string.add_phone_book_text, value))
        builder.setNegativeButton(getString(uk.xa0.tulkki.data.R.string.cancel), null)
        builder.setPositiveButton(getString(R.string.add)) { _, _ ->
            val intent = Intent(Intent.ACTION_INSERT_OR_EDIT)
            intent.setType(Contacts.CONTENT_ITEM_TYPE)
            if (quicksyContact) {
                intent.putExtra(Intents.Insert.PHONE, value)
            } else {
                intent.putExtra(Intents.Insert.IM_HANDLE, value)
                intent.putExtra(
                    Intents.Insert.IM_PROTOCOL,
                    CommonDataKinds.Im.PROTOCOL_JABBER,
                )
                // TODO for modern use we want PROTOCOL_CUSTOM and an extra field with a value of
                // 'XMPP'; however we do not have such a field and thus have to use the legacy
                // PROTOCOL_JABBER
            }
            intent.putExtra("finishActivityOnSaveCompleted", true)
            try {
                startActivityForResult(intent, 0)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(
                    this@ContactDetailsActivity,
                    R.string.no_application_found_to_view_contact,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        builder.create().show()
    }

    // Tulkki: 3.7 pair 9, part 15 - OnRosterUpdate is retyped with the island's ref, so this
    // override names it fully qualified and imports nothing: a :ui import of an island type would
    // be one more ui-reaches-island site, and this signature needs no member of the contact at all.
    public override fun onRosterUpdate(
        reason: uk.xa0.tulkki.xmpp.services.UpdateRosterReason,
        contact: uk.xa0.tulkki.xmpp.refs.ContactRef?,
    ) {
        refreshUi()
    }

    public override fun onAccountUpdate() {
        refreshUi()
    }

    public override fun OnUpdateBlocklist(status: OnUpdateBlocklist.Status) {
        refreshUi()
    }

    protected override fun refreshUiReal() {
        populateView()
    }

    protected override fun getShareableUri(): String {
        // The standard `xmpp:` URI, which is the form the tree already knows how to build. The
        // web-invite alternative was a third-party invite host; there is no Tulkki invite page and
        // we do not invent one.
        return "xmpp:" +
            Uri.encode(
                (contact ?: throw NullPointerException()).getJid().asBareJid().toString(),
                "@/+",
            )
    }

    protected override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showInactiveOmemo =
            savedInstanceState != null &&
                savedInstanceState.getBoolean("show_inactive_omemo", false)
        if (getIntent().getAction().equals(ACTION_VIEW_CONTACT)) {
            try {
                this.accountJid =
                    Jid.of(
                        (getIntent().getExtras() ?: throw NullPointerException())
                            .getString(XmppActivity.EXTRA_ACCOUNT) ?: throw NullPointerException(),
                    )
            } catch (ignored: IllegalArgumentException) {
            }
            try {
                this.contactJid =
                    Jid.of(
                        (getIntent().getExtras() ?: throw NullPointerException())
                            .getString("contact") ?: throw NullPointerException(),
                    )
            } catch (ignored: IllegalArgumentException) {
            }
        }
        this.messageFingerprint = getIntent().getStringExtra("fingerprint")

        // The two surfaces that stay views are built here, because the activity is what wires them,
        // exactly as the deleted layout's views were wired. The tag editor is Compose now
        // (`TagEditorState`), so it needs no parent: `item_tag.xml` and `TagEditorView` are deleted.
        mMediaView = RecyclerView(this)
        mMediaAdapter = MediaAdapter(this, R.dimen.media_size, false)
        mMediaView.adapter = mMediaAdapter
        GridManager.setupLayoutManager(this, mMediaView, R.dimen.media_size)
        mTagEditor = TagEditorState().apply { hint = getString(R.string.details_tags_hint) }
        mAvatar =
            AvatarView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setOnClickListener { onBadgeClick() }
                setOnLongClickListener {
                    ShowAvatarPopup(contact ?: throw NullPointerException())
                    true
                }
            }
        mPresenceIndicator = PresenceIndicator(this)

        ephemeralDurationValues = resources.getIntArray(R.array.ephemeral_duration_values)

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
                actions = {
                    if (state.editing) {
                        IconButton(onClick = { saveEdits() }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_save_24dp),
                                contentDescription = stringResource(R.string.save),
                            )
                        }
                    }
                },
            ) {
                ContactDetailsScreen(
                    state = state,
                    events = events(),
                    avatar = mAvatar,
                    presence = mPresenceIndicator,
                    tagEditor = mTagEditor,
                    keyRows = keyRows,
                    mediaGrid = mMediaView,
                    onEditNameChanged = { screen = screen.copy(editName = it) },
                    onEditDone = { saveEdits() },
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
     * The deleted `R.menu.contact_details`, item for item, with the visibility arms
     * `onCreateOptionsMenu` computed: the edit item only for a contact in the roster, the block or
     * the unblock item whichever the connection's blocking feature and the contact's state allow,
     * the custom-notifications item only from Android R, and the accounts/account pair chosen by
     * `AccountUtils.MANAGE_ACCOUNT_ACTIVITY` exactly as `showHideMenuItems` chose it.
     */
    @Composable
    private fun menuItems(): List<ChromeMenuItem> {
        val items = ArrayList<ChromeMenuItem>()
        if (screen.editVisible) {
            items.add(ChromeMenuItem(stringResource(R.string.action_edit_contact)) { onEditItem() })
        }
        items.add(ChromeMenuItem(stringResource(R.string.share_as_uri)) { shareLink() })
        items.add(ChromeMenuItem(stringResource(R.string.show_qr_code)) { showQrCode() })
        if (screen.blockVisible) {
            items.add(
                ChromeMenuItem(stringResource(R.string.action_block_contact)) {
                    BlockContactDialog.show(this, contact)
                },
            )
        }
        if (screen.unblockVisible) {
            items.add(
                ChromeMenuItem(stringResource(R.string.action_unblock_contact)) {
                    BlockContactDialog.show(this, contact)
                },
            )
        }
        if (screen.customNotificationsVisible) {
            items.add(
                ChromeMenuItem(stringResource(R.string.custom_notifications)) {
                    configureCustomNotifications(contact ?: throw NullPointerException())
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
    private fun events(): ContactDetailsEvents =
        ContactDetailsEvents(
            onArchive = { onArchiveButton() },
            onExtraButton = { onExtraButton() },
            onFollowFeedChanged = { onFollowFeedCheckedChanged(it) },
            onSendPresenceChanged = { onSendCheckedChanged(it) },
            onReceivePresenceChanged = { onReceiveCheckedChanged(it) },
            onCallsDisabledChanged = { onCallsDisabledChanged(it) },
            onEphemeralToggled = { onEphemeralToggled(it) },
            onEphemeralDurationSelected = { onEphemeralDurationSelected(it) },
            onThread = { openThread(it) },
            onProfileRowClick = { onProfileRowClicked(it) },
            onProfileRowLongClick = { onProfileRowCopied(it) },
            onStoreSecurelyChanged = { onStoreSecurelyChanged(it) },
            onShowMedia = { MediaBrowserActivity.launch(this, contact ?: throw NullPointerException()) },
            onScan = { ScanActivity.scan(this) },
            onToggleInactiveDevices = {
                showInactiveOmemo = !showInactiveOmemo
                populateView()
            },
        )

    /**
     * The vCard rows the screen draws: one per element the vCard publishes, with the label, the
     * icon, the URI and the copy text the deleted `VcardAdapter.getView` resolved, in the same
     * order and with the same filter.
     */
    private fun profileRows(vcard4: Element): List<ContactProfileRow> =
        vcard4
            .getChildren()
            .filter { el ->
                el.findChildEnsureSingle("uri", Namespace.VCARD4) != null ||
                    el.findChildEnsureSingle("text", Namespace.VCARD4) != null
            }
            .map { el -> profileRow(el) }

    private fun profileRow(item: Element): ContactProfileRow {
        val nameIcon =
            when (item.getName()) {
                "org" -> R.drawable.ic_business_24dp
                "impp" -> R.drawable.ic_chat_black_24dp
                "url" -> R.drawable.rounded_link_24
                else -> null
            }
        val uri = vcardUri(item)
        val copyText = uri?.toString() ?: item.findChildContent("text", Namespace.VCARD4)
        val scheme = uri?.getScheme()
        if (uri == null || scheme == null) {
            return ContactProfileRow(
                label = item.findChildContent("text", Namespace.VCARD4),
                iconRes = nameIcon,
                uri = null,
                copyText = copyText,
            )
        }
        val label: String
        val icon: Int?
        when (scheme) {
            "xmpp" -> {
                label = uri.getSchemeSpecificPart()
                icon = R.drawable.xmpp_logo
            }
            "tel" -> {
                label = uri.getSchemeSpecificPart()
                icon = R.drawable.ic_call_24dp
            }
            "mailto" -> {
                label = uri.getSchemeSpecificPart()
                icon = R.drawable.ic_email_24dp
            }
            "bitcoin" -> {
                label = uri.getSchemeSpecificPart()
                icon = R.drawable.bitcoin_24dp
            }
            "bitcoincash" -> {
                label = uri.getSchemeSpecificPart()
                icon = R.drawable.bitcoin_cash_24dp
            }
            "ethereum" -> {
                label = uri.getSchemeSpecificPart()
                icon = R.drawable.eth_24dp
            }
            "monero" -> {
                label = uri.getSchemeSpecificPart()
                icon = R.drawable.monero_24dp
            }
            "wownero" -> {
                label = uri.getSchemeSpecificPart()
                icon = R.drawable.wownero_24dp
            }
            "taler" -> {
                label = uri.getSchemeSpecificPart()
                icon = R.drawable.taler_icon_24dp
            }
            "https" -> {
                when (uri.getHost()) {
                    "liberapay.com" -> {
                        label = (uri.getPath() ?: throw NullPointerException()).substring(1)
                        icon = R.drawable.liberapay
                    }
                    "www.patreon.com", "patreon.com" -> {
                        label =
                            (uri.getPath() ?: throw NullPointerException())
                                .replace(Regex("^/(?:c/)?"), "")
                        icon = R.drawable.patreon
                    }
                    else -> {
                        label = uri.toString()
                        icon = R.drawable.rounded_link_24
                    }
                }
            }
            "http" -> {
                label = uri.toString()
                icon = R.drawable.rounded_link_24
            }
            else -> {
                label = uri.toString()
                icon = nameIcon
            }
        }
        return ContactProfileRow(
            label = label,
            iconRes = icon,
            uri = uri.toString(),
            copyText = copyText,
        )
    }

    /** The deleted `VcardAdapter.getUri`: the `uri` child, or a built `mailto:` for an email field. */
    private fun vcardUri(item: Element): Uri? {
        val uriS = item.findChildContent("uri", Namespace.VCARD4)
        if (uriS != null) return Uri.parse(uriS).normalizeScheme()
        if (item.getName() == "email") {
            return Uri.parse("mailto:" + item.findChildContent("text", Namespace.VCARD4))
        }
        return null
    }

    /** The deleted row click: open the row's URI. */
    private fun onProfileRowClicked(row: ContactProfileRow) {
        val uri = row.uri ?: return
        FixedURLSpan.open(uri, null, window.decorView)
    }

    /** The deleted long click: copy the row's URI, or its `text` child when it has none. */
    private fun onProfileRowCopied(row: ContactProfileRow) {
        val copy = row.copyText ?: return
        if (ShareUtil.copyTextToClipboard(this, copy, uk.xa0.tulkki.data.R.string.message)) {
            Toast.makeText(
                this,
                uk.xa0.tulkki.data.R.string.message_copied_to_clipboard,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun onArchiveButton() {
        val contact = contact ?: throw NullPointerException()
        val archiveDialog = MaterialAlertDialogBuilder(this@ContactDetailsActivity)
        val showInRoster = contact.showInRoster()
        archiveDialog.setTitle(
            getString(if (showInRoster) R.string.action_archive_chat else R.string.close_chat),
        )
        archiveDialog.setMessage(
            getString(if (showInRoster) R.string.archive_contact_text else R.string.close_chat_text),
        )
        archiveDialog.setNegativeButton(getString(uk.xa0.tulkki.data.R.string.cancel), null)
        archiveDialog.setPositiveButton(
            getString(if (showInRoster) R.string.action_archive_chat else R.string.close_chat),
        ) { _, _ ->
            startActivity(
                Intent(xmppConnectionService, ConversationListActivity::class.java),
            )
            overridePendingTransition(R.animator.fade_in, R.animator.fade_out)
            archiveContact()
            finish()
        }
        archiveDialog.create().show()
    }

    private fun onExtraButton() {
        val contact = contact ?: throw NullPointerException()
        if (contact.showInRoster()) {
            MaterialAlertDialogBuilder(this@ContactDetailsActivity)
                .setNegativeButton(getString(uk.xa0.tulkki.data.R.string.cancel), null)
                .setTitle(getString(R.string.action_delete_contact))
                .setMessage(
                    JidDialog.style(
                        this,
                        R.string.remove_contact_text,
                        contact.getJid().toString(),
                    ),
                )
                .setPositiveButton(getString(R.string.delete), removeFromRoster)
                .create()
                .show()
        } else {
            showAddToRosterDialog(contact)
        }
    }

    private fun onStoreSecurelyChanged(checked: Boolean) {
        val conversation = conversation() ?: return
        conversation.setStoreSecurely(checked)
        xmppConnectionService.updateConversation(conversation)
    }

    private fun openThread(thread: ThreadRow) {
        val conversation = conversation() ?: return
        switchToConversation(
            conversation,
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

    /**
     * The deleted `action_edit_contact` item's body: a contact with no system account goes straight
     * into the in-place editor, one with a system account is asked which of the two the owner
     * means.
     */
    private fun onEditItem() {
        val contact = contact ?: throw NullPointerException()
        val systemAccount = contact.getSystemAccount()
        if (systemAccount == null) {
            startEditingContact()
        } else {
            val editContactBuilder = MaterialAlertDialogBuilder(this@ContactDetailsActivity)
            editContactBuilder.setTitle(R.string.action_edit_contact)
            editContactBuilder.setMessage(R.string.edit_sytemaccount_or_xmppaccount)
            editContactBuilder.setPositiveButton(R.string.edit_sytemaccount) { _, _ ->
                screen = screen.copy(editing = false)
                val intent = Intent(Intent.ACTION_EDIT)
                intent.setDataAndType(systemAccount, Contacts.CONTENT_ITEM_TYPE)
                intent.putExtra("finishActivityOnSaveCompleted", true)
                try {
                    startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(
                        this@ContactDetailsActivity,
                        R.string.no_application_found_to_view_contact,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            editContactBuilder.setNegativeButton(R.string.edit_chat_contact) { _, _ ->
                startEditingContact()
            }
            editContactBuilder.create().show()
        }
    }

    /** The deleted action view expanded: the field is filled, the chips hide and the tags editor shows. */
    private fun startEditingContact() {
        val contact = contact ?: throw NullPointerException()
        screen =
            screen.copy(
                editing = true,
                editName = contact.getServerName() ?: "",
                tagsEditable = showDynamicTags,
            )
        mTagEditor.clearSync()
        for (group in contact.getGroupTags()) {
            mTagEditor.addObjectSync(group)
        }
        mTagEditor.setAdapter(tagAdapter(collectTags()))
    }

    public override fun onSaveInstanceState(savedInstanceState: Bundle) {
        savedInstanceState.putBoolean("show_inactive_omemo", showInactiveOmemo)
        super.onSaveInstanceState(savedInstanceState)
    }

    public override fun onStart() {
        super.onStart()
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        this.showDynamicTags =
            preferences.getBoolean(
                "show_dynamic_tags",
                getResources().getBoolean(R.bool.show_dynamic_tags),
            )
        this.showLastSeen =
            preferences.getBoolean(
                "last_activity",
                getResources().getBoolean(uk.xa0.tulkki.xmpp.R.bool.last_activity),
            )
        screen =
            screen.copy(
                mediaVisible = UiHost.installed().hasStoragePermission(this),
            )
        mMediaAdapter.setAttachments(Collections.emptyList())
    }

    public override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        // TODO check for Camera / Scan permission
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty()) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (requestCode == REQUEST_SYNC_CONTACTS && xmppConnectionServiceBound) {
                    showAddToPhoneBookDialog()
                    xmppConnectionService.loadPhoneContacts()
                    xmppConnectionService.startContactObserver()
                }
            }
        }
    }

    /**
     * The deleted action view's "done" action: the typed name and the tag editor's objects are
     * pushed to the server, the editor closes and the screen repopulates. The old code read the
     * name out of the action view's `EditText`; the field is [ContactDetailsScreen]'s now, so the
     * name is the state's.
     */
    protected fun saveEdits() {
        if (screen.editing) {
            (contact ?: throw NullPointerException()).setServerName(screen.editName)
            (contact ?: throw NullPointerException())
                .setGroups(mTagEditor.getObjects().map { tag -> tag.name })
            this.xmppConnectionService.pushContactToServer(contact ?: throw NullPointerException())
        }
        collapseEdit()
    }

    /** The deleted action view's collapse listener: the keyboard, the editor and the save icon go. */
    private fun collapseEdit() {
        SoftKeyboardUtils.hideSoftKeyboard(this)
        screen = screen.copy(editing = false)
        populateView()
    }

    /**
     * The old toolbar owned this: `AppCompatDelegateImpl.onBackPressed` asks the action bar to
     * `collapseActionView()` first, which consumed the back press and fired the collapse listener
     * rather than finishing the activity. The chrome has no action view, so the screen guards it -
     * the same shape the group screen's editor has.
     */
    public override fun onBackPressed() {
        if (screen.editing) {
            collapseEdit()
        } else {
            super.onBackPressed()
        }
    }

    /**
     * The Java built this adapter twice, inline, from a `Collectors.toMap` chain that counted each
     * tag, then sorted the entries by count descending and name ascending. The chain is rebuilt
     * here without streams (recorded in the port commit); the count and the ordering are the same.
     */
    private fun collectTags(): List<ListItem.Tag> {
        val tags = ArrayList<ListItem.Tag>()
        for (account in AccountRegistry.get().getAccounts()) {
            for (contact in account.getRoster().getContacts()) {
                tags.addAll(contact.getTags(this))
            }
            for (bookmark in account.getBookmarks()) {
                tags.addAll(bookmark.getTags(this))
            }
        }
        return tags
    }

    private fun tagAdapter(tags: List<ListItem.Tag>): ArrayAdapter<ListItem.Tag> {
        val tagCounts = HashMap<ListItem.Tag, Int>()
        for (tag in tags) {
            tagCounts[tag] = (tagCounts[tag] ?: 0) + 1
        }
        val sortedTags =
            tagCounts.entries
                .sortedWith(
                    compareByDescending<MutableMap.MutableEntry<ListItem.Tag, Int>> { it.value }
                        .thenBy { it.key.name },
                )
                .map { it.key }
        return ArrayAdapter(this, android.R.layout.simple_list_item_1, sortedTags)
    }

    private fun configureCustomNotifications(contact: Contact) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return
        }
        val shortcut = xmppConnectionService.getShortcutService().getShortcutInfo(contact)
        configureCustomNotification(shortcut)
    }

    /**
     * The deleted `populateView`: every value the layout was given becomes [ContactDetailsState],
     * and the four view islands are refilled in place. The two guards are the old ones - no contact,
     * or the tags editor is open (`screen.editing`) - and the old `invalidateOptionsMenu` is gone
     * because the menu is this composition's.
     */
    private fun populateView() {
        if (contact == null) {
            return
        }
        if (screen.editing) return
        val contact = contact ?: throw NullPointerException()

        var next =
            screen.copy(
                titleRes = R.string.action_contact_details,
                customNotificationsVisible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
            )

        if (contact.showInRoster()) {
            next =
                next.copy(
                    archiveLabelRes = R.string.action_archive_chat,
                    archiveContainerColor = getResources().getColor(R.color.md_theme_dark_error),
                    extraLabelRes = R.string.action_delete_contact,
                    extraContainerColor = getResources().getColor(R.color.md_theme_dark_error),
                    sendPresenceVisible = true,
                    receivePresenceVisible = true,
                    followFeedVisible = true,
                    followFeedChecked = contact.isFollowed(),
                )
            val statusMessages = contact.getPresences().getStatusMessages()
            val singleStatus = if (statusMessages.size == 1) statusMessages[0] else null
            next =
                next.copy(
                    statusMessage =
                        when {
                            statusMessages.isEmpty() -> null
                            singleStatus != null -> singleStatus
                            else -> {
                                val builder = StringBuilder()
                                val size = statusMessages.size
                                for (i in 0 until size) {
                                    builder.append(statusMessages[i])
                                    if (i < size - 1) {
                                        builder.append("\n")
                                    }
                                }
                                builder.toString()
                            }
                        },
                    // The deleted `RelativeSizeSpan(2.0f)` was applied only on the single-message
                    // arm; a joined list of statuses was drawn at its own size.
                    statusMessageEmojiOnly =
                        singleStatus != null && Emoticons.isOnlyEmoji(singleStatus),
                )

            if (contact.getOption(Contact.Options.FROM)) {
                next =
                    next.copy(
                        sendPresenceLabelRes = R.string.send_presence_updates,
                        sendPresenceChecked = true,
                    )
            } else if (contact.getOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST)) {
                next =
                    next.copy(
                        sendPresenceLabelRes = R.string.send_presence_updates,
                        sendPresenceChecked = false,
                    )
            } else {
                next =
                    next.copy(
                        sendPresenceLabelRes = R.string.preemptively_grant,
                        sendPresenceChecked =
                            contact.getOption(Contact.Options.PREEMPTIVE_GRANT),
                    )
            }
            if (contact.getOption(Contact.Options.TO)) {
                next =
                    next.copy(
                        receivePresenceLabelRes = R.string.receive_presence_updates,
                        receivePresenceChecked = true,
                    )
            } else {
                next =
                    next.copy(
                        receivePresenceLabelRes = R.string.ask_for_presence_updates,
                        receivePresenceChecked = contact.getOption(Contact.Options.ASKING),
                    )
            }
            next = next.copy(presenceEnabled = contact.getAccount().isOnlineAndConnected())
            next = next.copy(callsDisabled = contact.areCallsDisabled())
        } else {
            next =
                next.copy(
                    archiveLabelRes = R.string.close_chat,
                    archiveContainerColor = getResources().getColor(R.color.md_theme_dark_error),
                    extraLabelRes = R.string.add_contact,
                    extraContainerColor =
                        getResources().getColor(R.color.md_theme_light_surface),
                    sendPresenceVisible = false,
                    receivePresenceVisible = false,
                    followFeedVisible = false,
                    statusMessage = null,
                )
        }

        next = next.copy(editVisible = contact.showInRoster())

        val connection = contact.getAccount().getXmppConnection()
        if (connection != null && connection.getFeatures().blocking()) {
            next = next.copy(blockVisible = !contact.isBlocked(), unblockVisible = contact.isBlocked())
        } else {
            next = next.copy(blockVisible = false, unblockVisible = false)
        }

        if (contact.isBlocked() && !this.showDynamicTags) {
            next = next.copy(lastSeen = getString(R.string.contact_blocked))
        } else {
            if (showLastSeen && contact.getLastseen() > 0 &&
                contact.getPresences().allOrNonSupport(Namespace.IDLE)
            ) {
                next =
                    next.copy(
                        lastSeen =
                            UIHelper.lastseen(
                                getApplicationContext(),
                                contact.isActive(),
                                contact.getLastseen(),
                            ),
                    )
            } else {
                next = next.copy(lastSeen = null)
            }
        }

        next = next.copy(name = contact.getDisplayName())
        next = next.copy(jid = annotatedJid(IrregularUnicodeDetector.style(this, contact.getJid())))
        val account =
            contact.getAccount().getJid().asBareJid().toString()
        next = next.copy(accountLine = getString(R.string.using_account, account))

        AvatarWorkerTask.loadAvatar(
            contact,
            mAvatar,
            R.dimen.avatar_on_details_screen_size,
        )
        mPresenceIndicator.setStatus(contact)

        // The keys section, exactly as the deleted `populateView` built it: one row per session,
        // then the unverified warning, the scan and inactive-device buttons.
        val rows = ArrayList<ContactKeyRowState>()
        var hasKeys = false
        var showUnverifiedWarning = false
        var showInactiveButton = false
        var showsInactive = false
        val axolotlService = contact.getAccount().getAxolotlService()
        if (Config.supportOmemo() && axolotlService != null) {
            val sessions = axolotlService.findSessionsForContact(contact)
            var anyActive = false
            for (session in sessions) {
                anyActive = session.getTrust().isActive()
                if (anyActive) {
                    break
                }
            }
            var skippedInactive = false
            for (session in sessions) {
                val trust = session.getTrust()
                hasKeys = hasKeys || !trust.isCompromised()
                if (!trust.isActive() && anyActive) {
                    if (showInactiveOmemo) {
                        showsInactive = true
                    } else {
                        skippedInactive = true
                        continue
                    }
                }
                if (!trust.isCompromised()) {
                    val highlight = session.getFingerprint() == messageFingerprint
                    rows.add(contactKeyRow(session, highlight))
                }
                if (trust.isUnverified()) {
                    showUnverifiedWarning = true
                }
            }
            if (showsInactive || skippedInactive) {
                showInactiveButton = true
            }
        }
        val isCameraFeatureAvailable = isCameraFeatureAvailable()
        var scanVisible = false
        if (hasKeys && isCameraFeatureAvailable) {
            scanVisible = true
        }
        if (Config.supportOpenPgp() && contact.getPgpKeyId() != 0L) {
            hasKeys = true
            val pgpKeyId = contact.getPgpKeyId()
            rows.add(
                ContactKeyRowState(
                    key = OpenPgpUtils.convertKeyIdToHex(pgpKeyId),
                    typeLabel = getString(R.string.openpgp_key_id),
                    typeHighlighted = "pgp" == messageFingerprint,
                    onTap = { launchOpenKeyChain(pgpKeyId) },
                )
            )
        }
        keyRows = rows
        next =
            next.copy(
                keysVisible = hasKeys,
                unverifiedVisible = showUnverifiedWarning,
                scanVisible = scanVisible,
                inactiveLabelRes =
                    if (showInactiveButton) {
                        if (showsInactive) R.string.hide_inactive_devices
                        else R.string.show_inactive_devices
                    } else {
                        null
                    },
            )

        next = next.copy(tags = contactTags(contact))

        val jid = accountJid
        if (jid == null) {
            screen = next
            return
        }
        val thisAccount = AccountRegistry.get().findAccountByJid(jid)
        if (thisAccount == null) {
            screen = next
            return
        }
        val conversation =
            xmppConnectionService.findOrCreateConversation(
                thisAccount,
                contact.getJid(),
                false,
                true,
            ) as Conversation
        val recentThreads = conversation.recentThreads()
        next =
            next.copy(
                threads = recentThreads.map { ThreadRow(it.getThreadId(), it.getDisplay() ?: "") },
            )
        next = next.copy(followFeedChecked = contact.isFollowed())

        val timer = conversation.getEphemeralTimer()
        val ephemeralEntries =
            resources.getStringArray(R.array.ephemeral_durations).toList()
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

    /** The tag chips: the tag list, then the blocked chip or the status chip, as the deleted code built them. */
    private fun contactTags(contact: Contact): List<TagChip> {
        val tagList = contact.getTags(this)
        val hasMetaTags = contact.isBlocked() || contact.shownStatus != Presence.Status.OFFLINE
        if ((tagList.isEmpty() && !hasMetaTags) || !this.showDynamicTags) {
            return emptyList()
        }
        val chips = ArrayList<TagChip>()
        for (tag in tagList) {
            chips.add(TagChip(tag.name, harmonized(XEP0392Helper.rgbFromNick(tag.name))))
        }
        if (contact.isBlocked()) {
            chips.add(
                TagChip(
                    getString(uk.xa0.tulkki.data.R.string.blocked),
                    harmonized(ContextCompat.getColor(this, R.color.gray_800)),
                ),
            )
        } else {
            val status = contact.shownStatus
            if (status != Presence.Status.OFFLINE) {
                val (textRes, colorRes) = statusLabel(status)
                chips.add(TagChip(getString(textRes), harmonized(ContextCompat.getColor(this, colorRes))))
            }
        }
        if (contact.getJid().isDomainJid()) {
            for (p in contact.getPresences().getPresences()) {
                val disco = p.getServiceDiscoveryResult() ?: continue
                for (identity in disco.getIdentities()) {
                    val txt = identity.getCategory() + "/" + identity.getType()
                    chips.add(TagChip(txt, harmonized(XEP0392Helper.rgbFromNick(txt))))
                }
            }
        }
        return chips
    }

    /** The deleted `UIHelper.setStatus`'s two columns, read here because the chip is Compose now. */
    private fun statusLabel(status: Presence.Status): Pair<Int, Int> =
        when (status) {
            Presence.Status.CHAT -> R.string.presence_chat to R.color.green_800
            Presence.Status.ONLINE -> R.string.presence_online to R.color.green_800
            Presence.Status.AWAY -> R.string.presence_away to R.color.amber_800
            Presence.Status.XA -> R.string.presence_xa to R.color.orange_800
            Presence.Status.DND -> R.string.presence_dnd to R.color.red_800
            else -> throw IllegalStateException()
        }

    private fun harmonized(color: Int): Int = MaterialColors.harmonizeWithPrimary(this, color)

    public override fun onBackendConnected() {
        val boundAccountJid = this.accountJid
        val boundContactJid = this.contactJid
        if (boundAccountJid != null && boundContactJid != null) {
            val account = AccountRegistry.get().findAccountByJid(boundAccountJid)
            if (account == null) {
                return
            }
            this.contact = account.getRoster().getContact(boundContactJid)
            val pendingUri = mPendingFingerprintVerificationUri
            if (pendingUri != null) {
                processFingerprintVerification(pendingUri)
                mPendingFingerprintVerificationUri = null
            }

            if (UiHost.installed().hasStoragePermission(this)) {
                val limit = GridManager.getCurrentColumnCount(mMediaView)
                xmppConnectionService.getAttachments(
                    account,
                    (contact ?: throw NullPointerException()).getJid().asBareJid(),
                    limit,
                    this,
                )
            }

            xmppConnectionService.fetchVcard4(account, contact ?: throw NullPointerException()) { vcard4 ->
                if (vcard4 == null) return@fetchVcard4

                runOnUiThread { screen = screen.copy(profile = profileRows(vcard4)) }
            }

            val conversation =
                xmppConnectionService.findOrCreateConversation(
                    account,
                    (contact ?: throw NullPointerException()).getJid(),
                    false,
                    true,
                ) as Conversation
            val storeSecurely = conversation.storeSecurely(xmppConnectionService)
            screen = screen.copy(storeSecurely = storeSecurely)

            // Show used clients of contact
            val resources =
                (contact ?: throw NullPointerException()).getPresences().toResourceArray()
            if (resources.isEmpty()) {
                screen = screen.copy(clients = null)
            } else {
                val clientsText = StringBuilder()
                for (value in resources) {
                    clientsText.append(value).append("\n")
                }
                screen = screen.copy(clients = clientsText.toString())
            }

            populateView()
        }
    }

    public override fun onKeyStatusUpdated(
        report: uk.xa0.tulkki.xmpp.crypto.OmemoSessionPort.FetchStatus?,
    ) {
        refreshUi()
    }

    protected override fun processFingerprintVerification(uri: XmppUri) {
        if (contact != null &&
            (contact ?: throw NullPointerException()).getJid().asBareJid() == uri.getJid() &&
            uri.hasFingerprints()
        ) {
            if (xmppConnectionService.verifyFingerprints(contact ?: throw NullPointerException(), uri.getFingerprints())) {
                Toast.makeText(this, R.string.verified_fingerprints, Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, R.string.invalid_barcode, Toast.LENGTH_SHORT).show()
        }
    }

    // The wildcard is forced: the interface hands over List<? extends AttachmentRef>, and Kotlin's
    // own List is covariant, so the Kotlin spelling needs no `out` written at all. The compiled
    // parameter stays java.util.List<? extends AttachmentRef>.
    public override fun onMediaLoaded(
        attachments: List<uk.xa0.tulkki.libs.AttachmentRef>,
    ) {
        runOnUiThread {
            val limit = GridManager.getCurrentColumnCount(mMediaView)
            mMediaAdapter.setAttachments(
                attachments.subList(0, Math.min(limit, attachments.size)),
            )
            screen = screen.copy(showMediaVisible = attachments.size > 0)
        }
    }

    private fun onBadgeClick() {
        if (UiHost.installed().contactListIntegration(this)) {
            val systemAccount = (contact ?: throw NullPointerException()).getSystemAccount()
            if (systemAccount == null) {
                checkContactPermissionAndShowAddDialog()
            } else {
                val intent = Intent(Intent.ACTION_VIEW)
                intent.setData(systemAccount)
                try {
                    startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(
                        this,
                        R.string.no_application_found_to_view_contact,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        } else {
            Toast.makeText(
                this,
                R.string.contact_list_integration_not_available,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    /** The ephemeral switch, arm for arm: on with the warning, on without it, or off. */
    private fun onEphemeralToggled(isChecked: Boolean) {
        val conversation = conversation() ?: return
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
        val sp = PreferenceManager.getDefaultSharedPreferences(this)
        if (dontShowAgain) {
            sp.edit()
                .putBoolean(AppSettings.HIDE_EPHEMERAL_WARNING, true)
                .apply()
        }
        val conversation = conversation() ?: return
        applyEphemeralEnabled(conversation)
    }

    /** The deleted spinner's `onItemSelected`, guarded on the timer actually changing. */
    private fun onEphemeralDurationSelected(position: Int) {
        val conversation = conversation() ?: return
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

    companion object {
        const val ACTION_VIEW_CONTACT = "view_contact"
        private const val REQUEST_SYNC_CONTACTS = 0x28cf
    }
}
