package uk.xa0.tulkki.ui

import android.app.Activity.RESULT_CANCELED
import android.view.View.GONE
import android.view.View.VISIBLE
import uk.xa0.tulkki.ui.ConversationRequests.ATTACHMENT_CHOICE_CHOOSE_FILE
import uk.xa0.tulkki.ui.ConversationRequests.ATTACHMENT_CHOICE_CHOOSE_IMAGE
import uk.xa0.tulkki.ui.ConversationRequests.ATTACHMENT_CHOICE_EDIT_PHOTO
import uk.xa0.tulkki.ui.ConversationRequests.ATTACHMENT_CHOICE_LIVE_LOCATION
import uk.xa0.tulkki.ui.ConversationRequests.ATTACHMENT_CHOICE_LOCATION
import uk.xa0.tulkki.ui.ConversationRequests.ATTACHMENT_CHOICE_RECORD_VIDEO
import uk.xa0.tulkki.ui.ConversationRequests.ATTACHMENT_CHOICE_RECORD_VOICE
import uk.xa0.tulkki.ui.ConversationRequests.ATTACHMENT_CHOICE_TAKE_PHOTO
import uk.xa0.tulkki.ui.ConversationRequests.RECENTLY_USED_QUICK_ACTION
import uk.xa0.tulkki.ui.ConversationRequests.REQUEST_ADD_EDITOR_CONTENT
import uk.xa0.tulkki.ui.ConversationRequests.REQUEST_COMMIT_ATTACHMENTS
import uk.xa0.tulkki.ui.ConversationRequests.REQUEST_DECRYPT_PGP
import uk.xa0.tulkki.ui.ConversationRequests.REQUEST_ENCRYPT_MESSAGE
import uk.xa0.tulkki.ui.ConversationRequests.REQUEST_PICK_DATE
import uk.xa0.tulkki.ui.ConversationRequests.REQUEST_SEND_MESSAGE
import uk.xa0.tulkki.ui.ConversationRequests.REQUEST_START_AUDIO_CALL
import uk.xa0.tulkki.ui.ConversationRequests.REQUEST_START_DOWNLOAD
import uk.xa0.tulkki.ui.ConversationRequests.REQUEST_START_VIDEO_CALL
import uk.xa0.tulkki.ui.ConversationRequests.REQUEST_TRUST_KEYS_ATTACHMENTS
import uk.xa0.tulkki.ui.ConversationRequests.REQUEST_TRUST_KEYS_NONE
import uk.xa0.tulkki.ui.ConversationRequests.REQUEST_TRUST_KEYS_TEXT
import uk.xa0.tulkki.ui.XmppActivity.Companion.EXTRA_ACCOUNT
import uk.xa0.tulkki.ui.XmppActivity.Companion.REQUEST_INVITE_TO_CONVERSATION
import uk.xa0.tulkki.ui.util.SoftKeyboardUtils.hideSoftKeyboard
import uk.xa0.tulkki.ui.utils.PermissionUtils.allGranted
import uk.xa0.tulkki.ui.utils.PermissionUtils.audioGranted
import uk.xa0.tulkki.ui.utils.PermissionUtils.cameraGranted
import uk.xa0.tulkki.ui.utils.PermissionUtils.getFirstDenied
import uk.xa0.tulkki.ui.utils.PermissionUtils.writeGranted

import android.Manifest
import android.app.Activity
import android.app.Fragment
import android.app.PendingIntent
import android.app.ProgressDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentSender.SendIntentException
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.icu.util.Calendar
import android.icu.util.TimeZone
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.ImageSpan
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.View.OnClickListener
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

import androidx.activity.OnBackPressedCallback
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.annotation.StringRes
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView.Adapter
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager

import kotlinx.coroutines.CoroutineScope

import com.google.android.material.tabs.TabLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.collect.Collections2
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableSet
import com.google.common.collect.Ordering

import me.drakeet.support.toast.ToastCompat
import net.java.otr4j.session.SessionStatus

import io.ipfs.cid.Cid

import java.io.File
import java.lang.ref.WeakReference
import java.net.URISyntaxException
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.AbstractMap
import java.util.ArrayList
import java.util.Arrays
import java.util.Collection
import java.util.Collections
import java.util.Date
import java.util.HashSet
import java.util.Iterator
import java.util.Locale
import java.util.Map
import java.util.Objects
import java.util.Set
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern
import java.util.stream.Collectors

import uk.xa0.tulkki.crypto.OmemoSetting
import uk.xa0.tulkki.crypto.axolotl.AxolotlService
import uk.xa0.tulkki.crypto.axolotl.FingerprintStatus
import uk.xa0.tulkki.data.AppSettings
import uk.xa0.tulkki.data.DatabaseBackend
import uk.xa0.tulkki.data.FileBackend
import uk.xa0.tulkki.data.FileBackends
import uk.xa0.tulkki.data.messages.MessageSnapshot
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Blockable
import uk.xa0.tulkki.data.model.Bookmark
import uk.xa0.tulkki.data.model.Contact
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Conversational
import uk.xa0.tulkki.data.model.Emoji
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.data.model.MucOptions
import uk.xa0.tulkki.data.model.MucOptions.User
import uk.xa0.tulkki.data.model.Presence
import uk.xa0.tulkki.data.model.ReadByMarker
import uk.xa0.tulkki.data.model.TransferablePlaceholder
import uk.xa0.tulkki.data.utils.Attachment
import uk.xa0.tulkki.data.utils.BobCid
import uk.xa0.tulkki.data.utils.MessageUtils
import uk.xa0.tulkki.data.utils.QuickLoader
import uk.xa0.tulkki.libs.Transferable
import uk.xa0.tulkki.translation.BubbleDisplaySettings
import uk.xa0.tulkki.translation.ComposerGate
import uk.xa0.tulkki.translation.ConversationLanguage
import uk.xa0.tulkki.translation.ConversationName
import uk.xa0.tulkki.translation.HeldSend
import uk.xa0.tulkki.translation.OutgoingTranslation
import uk.xa0.tulkki.translation.ReplyQuote
import uk.xa0.tulkki.translation.SecondHalf
import uk.xa0.tulkki.translation.TranslationLanguages
import uk.xa0.tulkki.translation.TranslationSettings
import uk.xa0.tulkki.ui.AddReactionActivity
import uk.xa0.tulkki.ui.BuildConfig
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.TulkkiSettingsFragment
import uk.xa0.tulkki.ui.activity.SettingsActivity
import uk.xa0.tulkki.ui.adapter.MessageAdapter
import uk.xa0.tulkki.ui.command.CommandFormHost
import uk.xa0.tulkki.ui.composer.ComposerBarController
import uk.xa0.tulkki.ui.composer.ComposerSend
import uk.xa0.tulkki.ui.composer.CorrectionBarController
import uk.xa0.tulkki.ui.composer.HeldSendSurfaces
import uk.xa0.tulkki.ui.composer.HeldSendText
import uk.xa0.tulkki.ui.conversation.BarVerb
import uk.xa0.tulkki.ui.conversation.ChatAppearance
import uk.xa0.tulkki.ui.conversation.ClearHistoryDialog
import uk.xa0.tulkki.ui.conversation.ConversationBar
import uk.xa0.tulkki.ui.conversation.CommandsSession
import uk.xa0.tulkki.ui.conversation.ConversationEvents
import uk.xa0.tulkki.ui.conversation.ConversationHost
import uk.xa0.tulkki.ui.conversation.ConversationMedia
import uk.xa0.tulkki.ui.conversation.ConversationNotice
import uk.xa0.tulkki.ui.conversation.ConversationPagerController
import uk.xa0.tulkki.ui.conversation.ConversationRead
import uk.xa0.tulkki.ui.conversation.DraftMarkup
import uk.xa0.tulkki.ui.conversation.LanguagePickerDialog
import uk.xa0.tulkki.ui.conversation.NoticeAction
import uk.xa0.tulkki.ui.conversation.StagedAttachments
import uk.xa0.tulkki.ui.conversation.UiBar
import uk.xa0.tulkki.ui.conversation.UiCommand
import uk.xa0.tulkki.ui.conversation.UiEphemeralLine
import uk.xa0.tulkki.ui.conversation.UiMenuItem
import uk.xa0.tulkki.ui.conversation.UiNotice
import uk.xa0.tulkki.ui.conversation.UiPendingAttachment
import uk.xa0.tulkki.ui.conversation.UiSubjectLine
import uk.xa0.tulkki.ui.conversation.UiThread
import uk.xa0.tulkki.ui.conversation.UiTuneLine
import uk.xa0.tulkki.ui.conversation.VoiceRecordingFailure
import uk.xa0.tulkki.ui.conversation.VoiceRecordingState
import uk.xa0.tulkki.ui.dialogs.showTulkkiDialog
import uk.xa0.tulkki.ui.emoji.EmojiPanelController
import uk.xa0.tulkki.ui.encryption.EncryptionBlock
import uk.xa0.tulkki.ui.encryption.EncryptionChoice
import uk.xa0.tulkki.ui.encryption.EncryptionSelectionState
import uk.xa0.tulkki.ui.host.UiHost
import uk.xa0.tulkki.ui.imageeditor.ImageEditorActivity
import uk.xa0.tulkki.ui.pinnedmessage.PinnedBarController
import uk.xa0.tulkki.ui.projection.ConversationFacts
import uk.xa0.tulkki.ui.projection.MessageProjection
import uk.xa0.tulkki.ui.projection.ProjectionSettings
import uk.xa0.tulkki.ui.projection.UiDoubtHold
import uk.xa0.tulkki.ui.projection.UiLanguagePair
import uk.xa0.tulkki.ui.projection.UiQuote
import uk.xa0.tulkki.ui.util.ActivityResult
import uk.xa0.tulkki.ui.util.DateSeparator
import uk.xa0.tulkki.ui.util.MenuDoubleTabUtil
import uk.xa0.tulkki.ui.util.MucDetailsContextMenuHelper
import uk.xa0.tulkki.ui.util.MucUserMenu
import uk.xa0.tulkki.ui.util.PendingItem
import uk.xa0.tulkki.ui.util.PresenceSelector
import uk.xa0.tulkki.ui.util.SendButtonAction
import uk.xa0.tulkki.ui.util.SendButtonTool
import uk.xa0.tulkki.ui.utils.ChatBackgroundHelper
import uk.xa0.tulkki.ui.utils.Emoticons
import uk.xa0.tulkki.ui.utils.GeoHelper
import uk.xa0.tulkki.ui.utils.KeyboardHeightProvider
import uk.xa0.tulkki.ui.utils.PermissionUtils
import uk.xa0.tulkki.ui.utils.TimeFrameUtils
import uk.xa0.tulkki.ui.utils.UIHelper
import uk.xa0.tulkki.ui.widget.EditMessage
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.XmppConnection
import uk.xa0.tulkki.xmpp.chatstate.ChatState
import uk.xa0.tulkki.xmpp.jingle.AbstractJingleConnection
import uk.xa0.tulkki.xmpp.jingle.JingleConnectionManager
import uk.xa0.tulkki.xmpp.jingle.Media
import uk.xa0.tulkki.xmpp.jingle.RtpCapability
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xmpp.pep.UserTune
import uk.xa0.tulkki.xmpp.services.MessageArchiveService
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.xmpp.utils.AccountUtils
import uk.xa0.tulkki.xmpp.utils.NickValidityChecker

/**
 * The Java field shape (`private T x;`, null until a lifecycle verb writes it) is kept exactly:
 * every such field is declared with the Java's own platform type and parked on [unsafeNull], so a
 * read before the write returns null as the Java did, a Java `x == null` check keeps working, and a
 * dereference NPEs where the Java NPE'd. No `Intrinsics.checkNotNullParameter` is inserted on a
 * field read and no `?: throw` changes which null is tolerated - which is the whole reason a
 * converted Java class uses this and not `lateinit`. `lateinit` is deliberately not used: it throws
 * a different exception for the tolerated null, and Kotlin forbids it on the fields Java left
 * primitive-initialised.
 */
private fun <T> unsafeNull(): T = null as T

public class ConversationFragment :
    XmppFragment(),
    EditMessage.KeyboardListener,
    ConversationEvents,
    uk.xa0.tulkki.xmpp.services.OnConversationUpdate {

    companion object {
        private const val REQUEST_EDIT_BACKGROUND = 9124

        /**
         * Tulkki: the room-configuration row's words, the deleted `CommandAdapter.MucConfig.getName()`'s
         * own literal, which is the one command row that is not a service's answer.
         */
        private const val MUC_CONFIG_LABEL = "\u2699\ufe0f Configure Channel"

        @JvmField
        val STATE_CONVERSATION_UUID: String = ConversationFragment::class.java.name + ".uuid"

        @JvmField
        val STATE_PHOTO_URI: String = ConversationFragment::class.java.name + ".media_previews"

        @JvmField
        val STATE_MEDIA_PREVIEWS: String = ConversationFragment::class.java.name + ".take_photo_uri"

        private const val STATE_LAST_MESSAGE_UUID = "state_last_message_uuid"
    }

    //Voice recorder
    private var mRecorder: MediaRecorder = unsafeNull()
    private var oldOrientation: Int? = null
    private var mStartTime = 0
    private var recording = false

    /**
     * Tulkki: the rest of the recording session the Compose bar draws, one field per
     * [VoiceRecordingState] member. They were the `recordingVoiceActivity`, `timer` and
     * `shareButton` view states before the bar moved into the composer; the recorder itself, its
     * thread and its file stay Java.
     */
    private var tulkkiRecordingActive = false
    private var tulkkiRecordingCanShare = false
    private var tulkkiRecordingFailure: VoiceRecordingFailure = unsafeNull()

    // Java reassigns this in setupFileObserver (`new CountDownLatch(1)`, chunk11.java:111) and the
    // reset is load-bearing: a second recording must begin on a fresh latch, or Finisher's await
    // returns immediately. It was declared `val` here and had to become `var`.
    private var outputFileWrittenLatch = CountDownLatch(1)

    private val mHandler = Handler()

    private val mTickExecutor: Runnable =
        object : Runnable {
            override fun run() {
                tick()
                mHandler.postDelayed(this, 1000)
            }
        }

    private var mOutputFile: File = unsafeNull()

    private var mFileObserver: FileObserver = unsafeNull()

    private var pendingLiveLocationDuration = 0L

    // The Java field kept the activity under the name `activity`; Kotlin cannot, because a property
    // of that name would generate `getActivity()` beside `Fragment.getActivity()`. It is private and
    // no outside file names it, so it is renamed for the Kotlin compiler and `requireActivity()`
    // reads both this and the framework's own.
    private var hostActivity: ConversationListActivity = unsafeNull()

    private val messageList: MutableList<Message> = ArrayList()
    private val postponedActivityResult = PendingItem<ActivityResult>()
    private val pendingConversationUuid = PendingItem<String>()
    private val pendingMediaPreviews = PendingItem<ArrayList<Attachment>>()
    private val pendingExtras = PendingItem<Bundle>()
    private val pendingTakePhotoUri = PendingItem<Uri>()
    private val pendingLastMessageUuid = PendingItem<String>()
    var mPendingEditorContent: Uri = unsafeNull()
    protected var messageListAdapter: MessageAdapter = unsafeNull()
    /**
     * Tulkki: the command page's state - the rows, the onboarding note and the fetch's progress - as
     * the Compose `ConversationCommands` draws them. It replaces the `CommandAdapter` that filled the
     * deleted `commands_view` `ListView` and the two views beside it (`commands_note`,
     * `commands_view_progressbar`).
     */
    private val tulkkiCommandsSession = CommandsSession()


    /**
     * Tulkki: whether this fragment has adopted its pager yet - the deleted `commandAdapter == null`
     * test, which was the only thing that said "the pages are set up".
     */
    private var tulkkiCommandsInitialised = false

    /**
     * Tulkki: the files and images staged for the next send. It was the Java
     * `mediaPreviewAdapter`'s own list and is now the Compose strip's state
     * ([StagedAttachments]); the send path reads this list, so there is one staged set and not
     * a Java one drawn beside a Compose one.
     */
    private var stagedAttachments: StagedAttachments = unsafeNull()

    private var lastMessageUuid: String = unsafeNull()

    /**
     * Tulkki: whether the Compose list last drew its final row. The deleted Java `OnScrollListener`
     * read this off the `ListView`; the list that draws now answers it through
     * [ConversationEvents.onViewport], and it is the whole of "am I at the latest message".
     */
    private var tulkkiAtBottom = true

    /**
     * Tulkki: whether the Compose list has reported its viewport once. The first fact is the list's
     * opening layout, not a scroll - and because the list opens at its latest row, that first fact
     * names the top of the list and must not fetch an older page.
     */
    private var tulkkiViewportSeen = false

    /** Tulkki: the bottom-most row the Compose list last drew, which the read receipt names. */
    private var tulkkiLastVisibleUuid: String = unsafeNull()

    private var currentConversation: Conversation = unsafeNull()
    /** The root: the wall, the pager and the conversation's popups, as one composition. */
    private var tulkkiView: ComposeView = unsafeNull()

    /**
     * Tulkki: the pager the commands page and the conversation page live in, built in code
     * (`ConversationPagerController`). It is views and not Compose - `:data`'s
     * `ConversationPagerAdapter` adopts the two pages and every command page it opens - so the
     * fragment keeps the controller and the three views it is read through.
     */
    private var pagerController: ConversationPagerController = unsafeNull()

    private var conversationViewPager: LockedViewPager = unsafeNull()

    private var tabLayout: TabLayout = unsafeNull()

    /** The command page's `ComposeView`, which carries the `commandsViewId` `:data` searches for. */
    private var commandsView: ComposeView = unsafeNull()

    /** Everything the conversation page draws from, handed over once when its content is set. */
    private var tulkkiPageInputs: ConversationHost.PageInputs = unsafeNull()

    /**
     * Tulkki: whether this view tree has its one composition set. The seven per-surface flags - the
     * header's, the wall's, the popups', the snackbar's, the composer's, the notice's and the command
     * page's - were one fact all along ("the page is up"), and the page is one composition now.
     */
    private var tulkkiPageHosted = false

    private var messageLoaderToast: Toast = unsafeNull()
    private var reInitRequiredOnStart = true

    /**
     * Tulkki: the composer's own bar, in Compose (`composer/ComposerBar.kt`). It holds the base
     * sentence, the two flags the language refresh reads back (`isBusy`/`isPinned`) and the bar
     * itself, so the three fields the Java kept for that state are gone.
     */
    private var composerBarController: ComposerBarController = unsafeNull()

    /**
     * Tulkki: the held and failed send surfaces, in Kotlin (`composer/HeldSendSurfaces.kt`). They
     * read the rows and draw the bar; the transport they ask for is this fragment's own, handed in at
     * construction.
     */
    private var heldSendSurfaces: HeldSendSurfaces = unsafeNull()

    /**
     * Tulkki: item 17's banner, the Compose surface `ui-9` wrote, hosted in the Java conversation
     * until the whole screen is Compose. The view is `tulkki_notice` in the conversation layout,
     * above the Java bar; this is the state it draws and the one flag that says whether the content
     * has been set on the current view yet.
     */
    private val tulkkiNoticeSession = ConversationHost.Session()


    /**
     * Tulkki: the conversation's three at-a-glance lines - the room's subject or the contact's status
     * message, the tune the peer is listening to, and the ephemeral-messages hint - as the Compose
     * `ConversationHeader` draws them. It replaces the three `LinearLayout`s (`muc_subject`,
     * `tune_subject`, `ephemeral_hint`) and their eleven child views, whose `visibility` writes were
     * the whole of the old behaviour: an absent line is `null` here and composes nothing.
     */
    private val tulkkiHeaderSession = ConversationHost.HeaderSession()


    /**
     * Tulkki: the presence colour the header's three icons take, as an ARGB int.
     *
     * It is [updateSendButton]'s own reading: the Java tinted `muc_subject_icon`,
     * `status_message_icon` and `tune_subject_icon` from `SendButtonTool.getSendButtonColor` on every
     * send-button update, and the header cannot re-derive that status without a second copy of the
     * arithmetic. `0` is "no reading yet", which the header draws as the theme's own `onSurface`.
     */
    private var tulkkiHeaderTint: Int = 0

    /**
     * Tulkki: the conversation's resting bar - the account's state, a block, a stranger, a pending
     * decryption - as the Compose `ConversationSnackbar` draws it. It replaces the `snackbar`
     * `RelativeLayout` and its two `TextView`s, and it holds the listeners themselves: the surface
     * hands them its host view, which is what the block submenus anchor at.
     */
    private val tulkkiSnackbarSession = ConversationHost.SnackbarSession()


    /**
     * Tulkki: the conversation's own background picture - the deleted `background_image`
     * `ImageView` - as the Compose `ConversationBackground` draws it. `null` is no picture, which is
     * the ordinary conversation and the deleted `View.GONE`.
     */
    private val tulkkiBackgroundSession = ConversationHost.BackgroundSession()


    /**
     * Tulkki: the conversation's popups - the room occupant's moderation rows, a one-to-one contact's
     * details and QR code, the owner's account menu and the block submenu - as the Compose
     * `ConversationRowMenu` draws them. It replaces the four `PopupMenu`s
     * (`one_on_one_context`, `account_context`, `block`, `block_muc`).
     */
    private val tulkkiRowMenuSession = ConversationHost.RowMenuSession()


    /**
     * Tulkki: the live message list, `ui-9`'s swap - the Compose surface
     * [ConversationHost.showPage] draws, inside the page's own composition,
     * Compose. The rows are `ConversationRead.stream`'s, the same read the whole screen is built on,
     * and the projector decides every content fact; this object only installs the view and answers the
     * row's taps.
     *
     * `messages_view` is still in the layout and still carries the fragment's own scroll
     * arithmetic, but it is `GONE`: the Compose list is the visible one. That is the recorded
     * viewport gap of this slice - the read receipts, the back-pagination and the jump-to controls
     * were driven by that `AbsListView`'s scrolling, and they come back with item 4.
     */
    private var tulkkiMessagesSession: ConversationHost.MessagesSession = unsafeNull()

    /** The scope the list's subscription runs in, built by [ConversationRead.viewScope]. */
    private var tulkkiMessagesScope: CoroutineScope = unsafeNull()

    /** The Compose list itself, added beside the hidden `messages_view`. */

    /** The conversation the Compose list is watching, or `null` before the first show. */
    private var tulkkiMessagesUuid: String = unsafeNull()

    /** The display switches the Compose list was shown with, so a changed one re-shows the rows. */
    private var tulkkiMessagesSettings: ProjectionSettings = unsafeNull()

    /** The drawing switches the Compose list was shown with, so a changed one re-shows the rows. */
    private var tulkkiMessagesAppearance: ChatAppearance = unsafeNull()

    /**
     * Tulkki: the Compose composer's own state, drawn into `tulkki_composer`. It holds the draft
     * the gate will read and the pair the chip names, and the fragment fills it from the field and the
     * conversation it already has, so the chip and the send path cannot disagree.
     */
    private val tulkkiComposerSession = ConversationHost.ComposerSession()


    /**
     * Tulkki: whether the draft's formatting bar is shown. The Java row this used to switch is gone,
     * so the bar is the Compose [uk.xa0.tulkki.ui.conversation.TextFormatBar] and this flag is
     * its visibility: the same two-part condition the Java row was shown under (the IME is up and
     * `showTextFormatting()` is on) still decides it, in `updateinputfield`.
     */
    private var tulkkiFormatting = false

    /** The uuids a covered-body tap has already asked for, so a second tap is not a second purchase. */
    private val tulkkiAskingBodies: MutableSet<String> = HashSet()

    /**
     * Tulkki: the display settings the message list was last bound with - the bubbles' halves and
     * English rows - so a resume can tell whether the settings screen has changed what those rows
     * should draw. `null` means no row has been decided yet, which is a repaint that costs
     * nothing because the list is about to be bound anyway.
     */
    private var tulkkiBubbleDisplay: BubbleDisplaySettings = unsafeNull()

    /**
     * Tulkki: the draft the model's own app-language answer turned out to be, trimmed. The send path
     * asks whether the draft in hand is that same text before refusing it, so a draft the gate got
     * wrong goes out as typed while every other draft is still refused. It is the app correcting a
     * false refusal, not an escape hatch: nothing an owner can type reaches this without the model
     * having answered with their own words - and with their exact words, because
     * [ComposerGate.suggestionIsTheDraft] ignores only surrounding whitespace, so the record
     * has to keep the draft's own case, spacing and punctuation or it would match a later draft the
     * model never confirmed.
     */
    private var tulkkiConfirmedDraft: String = unsafeNull()

    /**
     * Tulkki: the draft, and the single home of it.
     *
     * It is a [TextFieldValue] and not a `String`, because the Compose field owns the
     * draft's **selection** as well as its words: a marker's position *is* a selection, so the
     * formatting row's four markers, `/me` at the head and `> ` at the caret can only be
     * expressed against one. Every decision this fragment makes about the draft reads this value -
     * [tulkkiComposerText] for the words, [sendMessage] for the wire body - and every verb that
     * changes it goes through [writeComposerDraft], so there is one draft and no mirror.
     *
     * The Compose send affordance emits its draft and its send as two verbs
     * ([ConversationEvents.onDraftChanged] and [ConversationEvents.onSend]), and this is
     * where the first one lands. The Java `EditMessage` that used to be the fallback home is
     * deleted: it was `GONE` behind the composer, unreachable to the owner, and its only live
     * job - the typing-timeout machine that sends the conversation's chat state - is the fragment's
     * own now (`tulkkiTypingTimeout`), driven by the same draft writes that used to fire its
     * watcher.
     */
    private var tulkkiDraft: TextFieldValue = TextFieldValue("")

    /**
     * Tulkki: the outgoing subject, and the single home of it.
     *
     * The Java held it in the `textinput_subject` `EditMessage`, a `GONE` field inside the row the
     * composer hides. Nothing the owner could reach typed into it - the `attach_subject` entry only
     * toggled its `visibility` inside an already invisible parent - but [correctMessage] fills it from
     * the message being corrected, and the send path copies it onto the outgoing message, so the
     * state is kept and the field is not.
     */
    private var tulkkiSubject: String = ""

    /**
     * Tulkki: the deleted `EditMessage`'s typing machine, kept because its effects are not the
     * field's.
     *
     * The Java field's watcher sent the conversation's chat state - `COMPOSING` on the first
     * character, `PAUSED` at `Config.TYPING_TIMEOUT` seconds, the default state on an empty field -
     * and it also stored the draft as the conversation's next message when the owner emptied it. Every
     * one of those is driven by a draft write now ([writeComposerDraft]), through the same three
     * members the deleted watcher called, so the timings and the states are unchanged.
     */
    private val tulkkiTypingHandler: Handler = Handler()

    private var tulkkiIsUserTyping: Boolean = false

    /**
     * Tulkki: whether the typing machine is detached, which the deleted `EditMessage` spelled
     * `setKeyboardListener(null)`.
     *
     * [reInit] detached its listener around the fill it does on every thread switch, so filling the
     * field with a thread's next message sent nothing: no `COMPOSING` to the peer and no
     * `storeNextMessage` of the draft it had just read. The machine is the fragment's own now, so the
     * same window is a flag, and it is the only place that silences a draft write.
     */
    private var tulkkiTypingDetached: Boolean = false

    /**
     * Tulkki: how many times the owner has been put in front of the field. It is the deleted Java
     * field's own `requestFocus()`: [onResume] moves it and the Compose field takes the caret
     * when it does, so opening a conversation still offers the keyboard. `0` is "no request yet",
     * which is what every surface but this fragment's composer passes.
     */
    private var tulkkiFocusRequest: Int = 0

    private val tulkkiTypingTimeout: Runnable =
        Runnable {
            if (tulkkiIsUserTyping) {
                tulkkiIsUserTyping = false
                onTypingStopped()
            }
        }

    /**
     * Tulkki: the composer's reply quote, or `null`. It is the referenced row as Compose draws it -
     * [MessageProjection.replyPreview] has already asked `ReplyQuote` - and it is held
     * here rather than rebuilt on every keystroke: only a reply change moves it, and
     * [refreshTulkkiComposer] then draws it.
     */
    private var tulkkiReply: UiQuote = unsafeNull()

    /**
     * Tulkki: the sentence the Compose field draws while it is empty, or `null` for the shipped
     * placeholder.
     *
     * It is [updateChatMsgHint]'s own reading, held for the composer so the one method
     * that knows why a conversation wants no message is also the one that states it. The Java
     * `EditText` and the `textInputHint` strip that used to receive the same sentence are deleted with
     * the row they lived in, so the Compose field is the one delivery and there is no second copy of
     * it.
     */
    private var tulkkiComposerHint: String = unsafeNull()

    /** The Compose pinned bar's two live inputs, drawn by `PinnedBarContent`. */
    private var pinnedBarController: PinnedBarController = unsafeNull()

    private val backgroundExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var keyboardHeightProvider: KeyboardHeightProvider = unsafeNull()

    /** The Compose emoji picker's open state, drawn by `EmojiPanelContent`. */
    private var emojiPanelController: EmojiPanelController = unsafeNull()

    /** The Compose correction bar's words, drawn by `CorrectionBarContent`. */
    private var correctionBarController: CorrectionBarController = unsafeNull()

    /**
     * Tulkki: the OTR verification choices, as the Compose row menu.
     *
     * It is `ConversationListActivity.verifyOtrSessionDialog` moved into the fragment that was its
     * only caller - the deleted `verification_choices.xml` was inflated there, and the snackbar's own
     * `clickToVerify` is the one trigger. The session check, the toast and the three bodies are the
     * moved function's own; the one substitute is the blind-trust branch's refresh, which was the
     * activity's `refreshUiReal()` (a `protected` member the fragment cannot name) and is the
     * service's own conversation-UI update, the same route every other fragment-side write takes.
     */
    private fun verifyOtrSessionMenu(anchor: View?) {
        if (hostActivity == null || currentConversation == null) {
            return
        }
        val conversation = this.currentConversation
        if (!conversation.hasValidOtrSession() ||
            conversation.getOtrSession()?.getSessionStatus() != SessionStatus.ENCRYPTED
        ) {
            ToastCompat.makeText(hostActivity, R.string.otr_session_not_started, Toast.LENGTH_LONG)
                .show()
            return
        }
        showRowMenu(
            anchor,
            listOf(
                UiMenuItem(R.id.ask_question, getString(R.string.ask_question)),
                UiMenuItem(R.id.manual_verification, getString(R.string.manually_verify)),
                UiMenuItem(R.id.blind_trust, getString(R.string.otr_blind_trust)),
            ),
        ) { menuId ->
            if (menuId == R.id.blind_trust) {
                conversation.verifyOtrFingerprint()
                hostActivity.xmppConnectionService.syncRosterToDisk(
                    conversation.getAccount() ?: throw NullPointerException(),
                )
                hostActivity.xmppConnectionService.updateConversationUi()
            } else {
                val intent = Intent(hostActivity, VerifyOTRActivity::class.java)
                intent.setAction(VerifyOTRActivity.ACTION_VERIFY_CONTACT)
                intent.putExtra(
                    "contact",
                    conversation.getContact().getJid().asBareJid().toString(),
                )
                intent.putExtra(
                    "counterpart",
                    (conversation.getNextCounterpart() ?: throw NullPointerException()).toString(),
                )
                intent.putExtra(
                    EXTRA_ACCOUNT,
                    (conversation.getAccount() ?: throw NullPointerException())
                        .getJid()
                        .asBareJid()
                        .toString(),
                )
                if (menuId == R.id.ask_question) {
                    intent.putExtra("mode", VerifyOTRActivity.MODE_ASK_QUESTION)
                }
                startActivity(intent)
                hostActivity.overridePendingTransition(R.animator.fade_in, R.animator.fade_out)
            }
        }
    }

    protected var clickToVerify: OnClickListener =
        object : OnClickListener {
            override fun onClick(v: View) {
                verifyOtrSessionMenu(v)
            }
        }

    private val clickToMuc: OnClickListener =
        object : OnClickListener {
            override fun onClick(v: View) {
                ConferenceDetailsActivity.open(hostActivity, currentConversation)
            }
        }

    private val leaveMuc: OnClickListener =
        object : OnClickListener {
            override fun onClick(v: View) {
                hostActivity.xmppConnectionService.archiveConversation(currentConversation)
            }
        }

    private val joinMuc: OnClickListener =
        object : OnClickListener {
            override fun onClick(v: View) {
                hostActivity.xmppConnectionService.joinMuc(currentConversation)
            }
        }

    private val acceptJoin: OnClickListener =
        object : OnClickListener {
            override fun onClick(v: View) {
                currentConversation.setAttribute("accept_non_anonymous", true)
                hostActivity.xmppConnectionService.updateConversation(currentConversation)
                hostActivity.xmppConnectionService.joinMuc(currentConversation)
            }
        }

    private val enterPassword: OnClickListener =
        object : OnClickListener {
            override fun onClick(v: View) {
                val muc = currentConversation.getMucOptions()
                var password = muc.getPassword()
                if (password == null) {
                    password = ""
                }
                hostActivity.quickPasswordEdit(
                    password,
                ) { value ->
                    hostActivity.xmppConnectionService.providePasswordForMuc(
                        currentConversation,
                        value,
                    )
                    null
                }
            }
        }

    // Tulkki: the Java field's `OnCommitContentListener` - the `image/*` drop and paste handler - is
    // deleted with the field it was handed to. It was registered on an `EditMessage` inside the row
    // the composer hides, so no image could ever be committed through it; `attachEditorContentToConversation`
    // itself stays, because the activity-result path still reaches it.

    private val mEnableAccountListener: OnClickListener =
        object : OnClickListener {
            override fun onClick(v: View) {
                val account = if (currentConversation == null) null else currentConversation.getAccount()
                if (account != null) {
                    account.setOption(Account.OPTION_SOFT_DISABLED, false)
                    account.setOption(Account.OPTION_DISABLED, false)
                    hostActivity.xmppConnectionService.updateAccount(account)
                }
            }
        }

    private val mUnblockClickListener: OnClickListener =
        object : OnClickListener {
            override fun onClick(v: View) {
                // Tulkki: the Java hid the very button it was handed (`v.setVisibility(INVISIBLE)`,
                // posted). The Compose bar has no button to hand back and keeps the button's place
                // instead: the session marks the action used, and the bar draws it in the same box
                // with no pixels and no touch target - the same reserved space, the same one-press
                // action.
                tulkkiSnackbarSession.markActionUsed()
                if (currentConversation.isDomainBlocked()) {
                    BlockContactDialog.show(hostActivity, currentConversation)
                } else {
                    unblockConversation(currentConversation)
                }
            }
        }

    private val mBlockClickListener: OnClickListener =
        OnClickListener { v -> showBlockSubmenu(v) }

    private val mAddBackClickListener: OnClickListener =
        object : OnClickListener {
            override fun onClick(v: View) {
                val contact = if (currentConversation == null) null else currentConversation.getContact()
                if (contact != null) {
                    hostActivity.xmppConnectionService.createContact(contact, true)
                    hostActivity.switchToContactDetails(contact)
                }
            }
        }

    private val mLongPressBlockListener: View.OnLongClickListener =
        View.OnLongClickListener { v -> showBlockSubmenu(v) }

    private val mAllowPresenceSubscription: OnClickListener =
        object : OnClickListener {
            override fun onClick(v: View) {
                val contact = if (currentConversation == null) null else currentConversation.getContact()
                if (contact != null) {
                    hostActivity.xmppConnectionService.sendPresencePacket(
                        contact.getAccount(),
                        hostActivity.xmppConnectionService
                            .getPresenceGenerator()
                            .sendPresenceUpdatesTo(contact),
                    )
                    hideSnackbar()
                }
            }
        }

    private var mAnswerSmpClickListener: OnClickListener =
        object : OnClickListener {
            override fun onClick(view: View) {
                val intent = Intent(hostActivity, VerifyOTRActivity::class.java)
                intent.setAction(VerifyOTRActivity.ACTION_VERIFY_CONTACT)
                intent.putExtra(
                    EXTRA_ACCOUNT,
                    (currentConversation.getAccount() ?: throw NullPointerException())
                        .getJid()
                        .asBareJid()
                        .toString(),
                )
                intent.putExtra(
                    XmppActivity.EXTRA_ACCOUNT,
                    (currentConversation.getAccount() ?: throw NullPointerException())
                        .getJid()
                        .asBareJid()
                        .toString(),
                )
                intent.putExtra("mode", VerifyOTRActivity.MODE_ANSWER_QUESTION)
                startActivity(intent)
                hostActivity.overridePendingTransition(R.animator.fade_in, R.animator.fade_out)
            }
        }

    protected var clickToDecryptListener: OnClickListener =
        object : OnClickListener {
            override fun onClick(v: View) {
                val account = currentConversation.getAccount() ?: throw NullPointerException()
                val pgpDecryptionService =
                    account.getPgpDecryptionService() ?: throw NullPointerException()
                val pendingIntent = pgpDecryptionService.getPendingIntent()
                if (pendingIntent != null) {
                    try {
                        hostActivity
                            .startIntentSenderForResult(
                                pendingIntent.getIntentSender(),
                                REQUEST_DECRYPT_PGP,
                                null,
                                0,
                                0,
                                0,
                                UiHost.installed().pgpStartIntentSenderOptions(),
                            )
                    } catch (e: SendIntentException) {
                        Toast.makeText(
                            hostActivity,
                            uk.xa0.tulkki.xmpp.R.string.unable_to_connect_to_keychain,
                            Toast.LENGTH_SHORT,
                        )
                            .show()
                        pgpDecryptionService.continueDecryption(true)
                    }
                }
                updateSnackBar(currentConversation)
            }
        }

    private val mSendingPgpMessage = AtomicBoolean(false)

    // Tulkki: the Java field's `OnEditorActionListener` is deleted with the field. It ran `sendMessage`
    // on `IME_ACTION_SEND` and hid a fullscreen IME - both the Compose `DraftField`'s own now: its
    // `keyboardActions` emit the same `sendMessage` through the composer's send verb.

    /**
     * Tulkki: the Compose jump-to-latest control's host half. The move itself is the list's (its own
     * `LazyListState` is not a thing Java can move), so the one effect left here is the one the list
     * cannot perform: a conversation whose history part is open has only the loaded page at its end,
     * so the latest page is loaded and the read refreshed exactly as the deleted Java button's
     * listener did before it moved the `ListView`.
     */
    private fun jumpToTheBottom() {
        if (currentConversation == null) {
            return
        }
        if (currentConversation.isInHistoryPart()) {
            currentConversation.jumpToLatest()
            refresh(false)
        }
    }

    private var backPressedLeaveSingleThread: OnBackPressedCallback =
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                currentConversation.setLockThread(false)
                this.isEnabled = false
                currentConversation.setUserSelectedThread(false)
                setThread(null)
                refresh()
                updateThreadFromLastMessage()
            }
        }

    private val backPressedLeaveVoiceRecorder: OnBackPressedCallback =
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (tulkkiRecordingActive) {
                    mHandler.removeCallbacks(mTickExecutor)
                    stopRecording(false)
                    hostActivity.setResult(RESULT_CANCELED)
                    //activity.finish();
                    clearRecording()
                }
                this.isEnabled = false
                refresh()
            }
        }

    private var completionIndex = 0
    private var lastCompletionLength = 0
    private var incomplete: String = unsafeNull()
    private var lastCompletionCursor = 0
    private var firstWord = false
    private var mPendingDownloadableMessage: Message = unsafeNull()
    private var fetchHistoryDialog: ProgressDialog = unsafeNull()


// Two places where the Java is followed over a rule, both visible to whoever assembles this file:
//
// 1. RULES §3 turns a Java `List<X>` into `MutableList<X>`; `addMediaPreviews` keeps the Java's own
//    `final List<Attachment>`, because every call site hands it `Attachment.of(...)` /
//    `Attachment.extractAttachments(...)` (a Kotlin `List`) and `StagedAttachments.add` takes a
//    `List`, so a `MutableList` parameter would accept neither.
//
// 2. Java's `protected` carries same-package access and Kotlin's does not: the Java's
//    `activity.delegateUriPermissionsToService(uri)` / `activity.replaceToast(...)` /
//    `activity.hideToast()` are kept with the Java's own call spelling (`hostActivity...`), but those
//    three are Kotlin `protected` members of `XmppActivity`, invisible from this class (as is
//    `quickPasswordEdit`, which head.kt calls). They, and the other members this file reads the same
//    way, were widened to `internal` before the file compiled.

    /**
     * Fetches one page of older (or newer) messages. The deleted Java `OnScrollListener` called this
     * with its own `ListView` and then preserved the reader's place through
     * `setSelectionFromTop`/`getChildAt`; the trigger is now the Compose list's own `onViewport`, and
     * the place is preserved by the Compose `Anchor` (which anchors on item keys), so the arithmetic
     * below is the model's alone.
     */
    private fun loadMoreMessages(paginateBackward: Boolean, paginationForward: Boolean) {
        // Kotlin parameters are `val`, so the Java's reassignment of this one moves to a copy.
        var backward = paginateBackward
        if (backward && (currentConversation != null && !currentConversation.messagesLoaded.get())) {
            backward = false
        }

        if (currentConversation != null &&
            messageList.size > 0 &&
            ((backward && currentConversation.messagesLoaded.compareAndSet(true, false)) ||
                (paginationForward &&
                    currentConversation.historyPartLoadedForward.compareAndSet(true, false)))
        ) {
            val timestamp: Long

            if (backward) {
                if (messageList[0].getType() == Message.TYPE_STATUS && messageList.size >= 2) {
                    timestamp = messageList[1].getTimeSent()
                } else {
                    timestamp = messageList[0].getTimeSent()
                }
            } else {
                if (messageList[messageList.size - 1].getType() == Message.TYPE_STATUS &&
                    messageList.size >= 2
                ) {
                    timestamp = messageList[messageList.size - 2].getTimeSent()
                } else {
                    timestamp = messageList[messageList.size - 1].getTimeSent()
                }
            }

            val finalPaginateBackward = backward
            hostActivity.xmppConnectionService.loadMoreMessages(
                currentConversation,
                timestamp,
                !backward,
                object : uk.xa0.tulkki.xmpp.services.OnMoreMessagesLoaded {
                    override fun onMoreMessagesLoaded(
                        count: Int,
                        conversation: uk.xa0.tulkki.xmpp.refs.ConversationRef?,
                    ) {
                        if (currentConversation != conversation) {
                            (conversation ?: throw NullPointerException()).messagesLoaded().set(true)
                            return
                        }
                        runOnUiThread {
                            synchronized(messageList) {
                                // Tulkki: the place is the Compose list's, and it restores
                                // it from the item keys itself (`Anchor`), so this only has
                                // to hand the page to the model every reader shares.
                                currentConversation.populateWithMessages(
                                    messageList,
                                    (if (hostActivity == null) null else hostActivity.xmppConnectionService)
                                        ?: throw NullPointerException(),
                                )
                                try {
                                    updateStatusMessages()
                                } catch (e: IllegalStateException) {
                                    Log.d(
                                        Config.LOGTAG,
                                        "caught illegal state exception while updating status messages",
                                    )
                                }
                                messageListAdapter.notifyDataSetChanged()
                                if (messageLoaderToast != null) {
                                    messageLoaderToast.cancel()
                                }

                                if (!finalPaginateBackward) {
                                    (conversation ?: throw NullPointerException())
                                        .historyPartLoadedForward()
                                        .set(true)
                                } else {
                                    (conversation ?: throw NullPointerException())
                                        .messagesLoaded()
                                        .set(true)
                                }
                            }
                        }
                    }

                    override fun informUser(r: Int) {
                        runOnUiThread {
                            if (messageLoaderToast != null) {
                                messageLoaderToast.cancel()
                            }
                            // The Java guarded the toast with
                            // `ConversationFragment.this.conversation != conversation`, and both
                            // sides of that comparison were the same enclosing field - this callback
                            // has no conversation of its own - so the condition was provably false
                            // and the guard never fired. Its sibling `onMoreMessagesLoaded` compares
                            // against that callback's own argument and keeps that check; this one is
                            // deleted rather than translated, because a guard that cannot fire is a
                            // lie about what the conversation is.
                            if (hostActivity == null) {
                                return@runOnUiThread
                            }
                            messageLoaderToast = Toast.makeText(hostActivity, r, Toast.LENGTH_LONG)
                            messageLoaderToast.show()
                        }
                    }
                },
            )
        }
    }

    /**
     * Tulkki: the correction is set aside and the draft it replaced comes back.
     *
     * It was the send button's `CANCEL` branch and `mCancelCorrectionListener` - the same eleven
     * lines twice. The Compose correction bar asks for it once now, and so does the send button.
     */
    private fun cancelCorrection() {
        if (currentConversation == null) {
            return
        }
        if (correctionBarController != null) {
            correctionBarController.hide()
        }
        currentConversation.setUserSelectedThread(false)
        if (currentConversation.setCorrectingMessage(null)) {
            // The draft the correction replaced comes back, with the caret after it - what the
            // deleted `setText("")` + `append(draftMessage)` left in the field.
            val draft = currentConversation.getDraftMessage().orEmpty()
            writeComposerDraft(draft, draft.length)
            currentConversation.setDraftMessage(null)
        } else if (currentConversation.getMode() == Conversation.MODE_MULTI) {
            currentConversation.setNextCounterpart(null)
            writeComposerDraft("", 0)
        } else {
            writeComposerDraft("", 0)
        }
        this.tulkkiSubject = ""
        setupReply(null)
        updateChatMsgHint()
        updateSendButton()
        updateEditablity()
    }

    /**
     * Tulkki: the jump-to-latest control is the Compose list's own drawing now, and this keeps the
     * one fact it needs in step: how many messages arrived while the reader was away.
     *
     * The Java `FloatingActionButton` and its `UnreadCountCustomView` are never shown again. The
     * button's `RelativeLayout` anchor was `messages_view`, which is `GONE` behind the Compose list -
     * a zero-height box near the top - which is why the owner saw the control "floating somewhere at
     * the top of the screen and moving about randomly"; the drawn control is [ConversationScreen]'s
     * `ScrollToBottom`, and the count travels to it through the session. The guards are the deleted
     * pair's own: an empty list counted as at the bottom, and a conversation in its history part never
     * hides the control. The at-bottom fact arrives through [ConversationEvents.onViewport], and it is
     * the strict one - the newest row is fully on screen, not merely drawn.
     */
    private fun toggleScrollDownButton(atBottom: Boolean) {
        if (currentConversation == null) {
            return
        }
        // Tulkki: the three writes on the Java `FloatingActionButton` and the unread badge are
        // deleted. They are `GONE` for good and the control the owner sees is the Compose list's own
        // (`ScrollToBottom`), drawn from `tulkkiMessagesSession.unread` and the list's bottom fact.
        if (atBottom && !currentConversation.isInHistoryPart()) {
            lastMessageUuid = unsafeNull()
            hideUnreadMessagesCount()
        } else {
            if (lastMessageUuid == null) {
                // `getUuid()` is nullable and the Java assigned it as it came; `unsafeNull()` keeps
                // that tolerance on the non-null-typed field.
                lastMessageUuid = currentConversation.getLatestMessage().getUuid() ?: unsafeNull()
            }
            if (tulkkiMessagesSession != null) {
                tulkkiMessagesSession.unread(
                    currentConversation.getReceivedMessagesCountSinceUuid(lastMessageUuid),
                )
            }
        }
    }

    private fun getIndexOf(uuid: String?, messages: MutableList<Message>): Int {
        if (uuid == null) {
            return messages.size - 1
        }
        for (i in 0 until messages.size) {
            if (uuid.equals(messages[i].getUuid())) {
                return i
            }
        }
        return -1
    }

    private fun getIndexOfExtended(uuid: String?, messages: MutableList<Message>): Int {
        if (uuid == null) {
            return messages.size - 1
        }
        for (i in 0 until messages.size) {
            if (uuid.equals(messages[i].getServerMsgId())) {
                return i
            }

            if (uuid.equals(messages[i].getRemoteMsgId())) {
                return i
            }

            if (uuid.equals(messages[i].getUuid())) {
                return i
            }
        }
        return -1
    }

    private fun attachLocationToConversation(conversation: Conversation?, uri: Uri) {
        if (conversation == null) {
            return
        }
        val subject = this.tulkkiSubject
        hostActivity.xmppConnectionService.attachLocationToConversation(
            conversation,
            uri,
            subject,
            // Tulkki: 3.7 pair 9, part 16 - the island's port takes its own ref now, so this
            // anonymous callback is parameterised over it. Written **fully qualified and with no
            // import** (rounds 151/161): an import of an island type here would be one more
            // `ui-reaches-island` site. None of the three bodies reads the object.
            object : UiCallback<uk.xa0.tulkki.xmpp.refs.MessageRef> {
                override fun success(obj: uk.xa0.tulkki.xmpp.refs.MessageRef) {
                    messageSent()
                }

                override fun error(errorCode: Int, obj: uk.xa0.tulkki.xmpp.refs.MessageRef?) {
                    // TODO show possible pgp error
                }

                override fun userInputRequired(
                    pi: PendingIntent?,
                    obj: uk.xa0.tulkki.xmpp.refs.MessageRef,
                ) {}
            },
        )
    }

    private fun attachFileToConversation(
        conversation: Conversation?,
        uri: Uri,
        type: String?,
        next: Runnable?,
    ) {
        if (conversation == null) {
            return
        }
        val subject = this.tulkkiSubject
        val prepareFileToast =
            Toast.makeText(hostActivity, getText(R.string.preparing_file), Toast.LENGTH_LONG)
        prepareFileToast.show()
        hostActivity.delegateUriPermissionsToService(uri)
        hostActivity.xmppConnectionService.attachFileToConversation(
            conversation,
            uri,
            type,
            subject,
            // Tulkki: part 16 - the same widening as above, on the informable variant.
            object : UiInformableCallback<uk.xa0.tulkki.xmpp.refs.MessageRef> {
                override fun inform(text: String) {
                    hidePrepareFileToast(prepareFileToast)
                    runOnUiThread { hostActivity.replaceToast(text) }
                }

                override fun success(obj: uk.xa0.tulkki.xmpp.refs.MessageRef) {
                    val nextRunnable = next
                    if (nextRunnable == null) {
                        runOnUiThread {
                            hostActivity.hideToast()
                            messageSent()
                        }
                    } else {
                        runOnUiThread(nextRunnable)
                    }
                    hidePrepareFileToast(prepareFileToast)
                }

                override fun error(errorCode: Int, obj: uk.xa0.tulkki.xmpp.refs.MessageRef?) {
                    hidePrepareFileToast(prepareFileToast)
                    runOnUiThread { hostActivity.replaceToast(getString(errorCode)) }
                }

                override fun userInputRequired(
                    pi: PendingIntent?,
                    obj: uk.xa0.tulkki.xmpp.refs.MessageRef,
                ) {
                    hidePrepareFileToast(prepareFileToast)
                }
            },
        )
    }

    /**
     * Tulkki: stage attachments for the next send. It is the Java
     * `MediaPreviewAdapter.addMediaPreviews` plus its `notifyDataSetChanged`: the list
     * lives in [StagedAttachments] and the re-read is the strip's, so the two halves cannot
     * drift.
     */
    private fun addMediaPreviews(attachments: List<Attachment>) {
        if (stagedAttachments == null) {
            return
        }
        stagedAttachments.add(attachments)
    }

    fun attachEditorContentToConversation(uri: Uri) {
        addMediaPreviews(
            Attachment.of(hostActivity, uri, Attachment.Type.FILE),
        )
        toggleInputMethod()
    }

    private fun attachImageToConversation(
        conversation: Conversation?,
        uri: Uri,
        type: String?,
        next: Runnable?,
    ) {
        if (conversation == null) {
            return
        }
        val subject = this.tulkkiSubject
        val prepareFileToast =
            Toast.makeText(hostActivity, getText(R.string.preparing_image), Toast.LENGTH_LONG)
        prepareFileToast.show()
        hostActivity.delegateUriPermissionsToService(uri)
        hostActivity.xmppConnectionService.attachImageToConversation(
            conversation,
            uri,
            type,
            subject,
            object : UiCallback<uk.xa0.tulkki.xmpp.refs.MessageRef> {
                override fun userInputRequired(
                    pi: PendingIntent?,
                    obj: uk.xa0.tulkki.xmpp.refs.MessageRef,
                ) {
                    hidePrepareFileToast(prepareFileToast)
                }

                override fun success(obj: uk.xa0.tulkki.xmpp.refs.MessageRef) {
                    hidePrepareFileToast(prepareFileToast)
                    val nextRunnable = next
                    if (nextRunnable == null) {
                        runOnUiThread { messageSent() }
                    } else {
                        runOnUiThread(nextRunnable)
                    }
                }

                override fun error(errorCode: Int, obj: uk.xa0.tulkki.xmpp.refs.MessageRef?) {
                    hidePrepareFileToast(prepareFileToast)
                    val activity = hostActivity
                    if (activity == null) {
                        return
                    }
                    activity.runOnUiThread { activity.replaceToast(getString(errorCode)) }
                }
            },
        )
    }

    private fun hidePrepareFileToast(prepareFileToast: Toast?) {
        if (prepareFileToast != null && hostActivity != null) {
            hostActivity.runOnUiThread(Runnable { prepareFileToast.cancel() })
        }
    }

    private fun sendMessage() {
        sendMessage(null as Long?)
    }

// Rule/Java conflicts in this slice, kept on the Java's side as the brief asks:
// * Rule 3's SAM shapes: `OutgoingTranslation.Listener` and `.Sender` are plain Kotlin interfaces
//   (not `fun interface`), so Kotlin cannot SAM-convert the Java lambda and the
//   `this::sendTranslated` method reference; both are object expressions, which is exactly the
//   shape the Java's conversion produced at that call site.
// * `ConversationLanguage.Resolved.isKnown` is a Kotlin property, so the Java's `.isKnown()` is
//   spelled `.isKnown` (rule 9 - the spelling the declaration has). No behaviour change.
// * `anyNeedsExternalStoragePermission` was Java's `private static`; head.kt owns the companion
//   object (rule 8), so it is a private instance member here. Nothing outside the class could
//   name the static, so no call site changes.
// * `ActivityResult.data` is `Intent?` and the Java null-checks `data` in the CHOOSE_IMAGE case,
//   so `handlePositiveActivityResult` keeps `data: Intent?` and guards the Java's own
//   dereferences (the Java's NPE points are preserved; `parse`/`extractAttachments` take `Intent?`).
// * Cross-chunk signature dependencies, kept as the Java's tolerance requires: `setupReply(null)`
//   needs `Message?`; `addMediaPreviews` needs `List<Attachment>` (`Attachment.of` answers Kotlin
//   `List`); `attachImage/FileToConversation`'s `type` needs `String?` (the Java passes
//   `Attachment.getMime()`, a `String?`, straight through, as the service's `String?` takes it).
// * `XmppActivity.mToast` is `protected`, and Kotlin has no package-visible `protected` as Java
//   does, so `hostActivity.mToast` cannot compile until that declaration is widened (the same
//   `public` where upstream had `protected` that `Conversation.messages` records) - and the same
//   widening `activity.mUseTor`/`activity.mUseI2P` and `activity.replaceToast(...)`/`hideToast()`
//   need in the other chunks. Kept as the Java wrote it rather than silently dropping the
//   activity's stored toast.
// * `var sendAt = sendAt` shadows the parameter because the Java reassigned it and Kotlin's
//   parameters are `val`; the local keeps the Java's name for the rest of the body.
// * Harness note: `assemble.py`'s `strip_trailing_class_brace` pops *any* trailing line whose
//   `strip()` is `}` - not only the class brace - so it would eat the last member's closing brace
//   of every chunk. This file therefore ends with a comment line (below) that is not a brace, so
//   the brace survives the pop; the pop should be narrowed to head.kt's and chunk11's class brace.

    private fun sendMessage(sendAt: Long?) {
        var sendAt = sendAt
        if (sendAt != null && sendAt < System.currentTimeMillis()) {
            sendAt = null // No sending in past plz
        }
        // Tulkki: the body is the Compose draft's own words, in a `Spannable` because the send path
        // below edits them in place (`@here`'s six characters) and hands the result to the message.
        // The deleted Java field was the `Spannable`; a `SpannableStringBuilder` over the one draft is
        // the same value with the same two edits, and it carries no inline-image spans - the Compose
        // field inserts characters, never `ImageSpan`s, so the sticker branch below is as unreachable
        // as it was from the field the owner actually typed in.
        val body: Editable = SpannableStringBuilder(tulkkiComposerText())
        val conversation = this.currentConversation
        val hasAttachments = stagedAttachments != null && stagedAttachments.hasAttachments()
        val hasSubject = this.tulkkiSubject.length > 0

        // Tulkki: the send path's own questions, in the order that is behaviour - length, then
        // emptiness, then the gate. The order and the four answers live in Kotlin
        // (`composer/ComposerSend.kt`), because the Compose composer's send affordance asks exactly
        // the same ones; only the drawing of each answer is here.
        //
        // The gate runs before trust discovery, on purpose. Trust is what the send needs in order to
        // encrypt; it is not what the draft needs in order to be judged. Asking trust first let an
        // untrusted or undiscoverable OMEMO device list pre-empt the gate silently - trustKeysIfNeeded
        // opened TrustKeysActivity and returned, the draft was never looked at, and the owner was
        // never told they had written in the wrong language. The gate is a decision about the text,
        // not about the transport, so it gets its say first.
        //
        // The one exception is not a bypass the owner can reach: when the model's own app-language
        // version of the draft turned out to be the draft itself, the verdict was wrong and
        // suggestionIsTheDraft says so, so the message goes out as it was typed. That is the app
        // correcting a false refusal, not the owner overriding a correct one. An edit is gated too,
        // and it used to be the one exception: corrections were sent straight out without Tulkki
        // being asked anything, so an edit was never checked and never translated. An edit is
        // something the owner sends, so it is subject to the same rule.
        val verdict: ComposerGate.Verdict? =
            if (body.length > 0 && hostActivity != null) {
                OutgoingTranslation.verdict(hostActivity, conversation, body.toString())
            } else {
                null
            }
        when (
            ComposerSend.carriage(
                body,
                conversation != null,
                hasAttachments,
                hasSubject,
                conversation != null && conversation.getThread() != null,
                Config.MAX_DISPLAY_MESSAGE_CHARS,
                verdict,
                ComposerGate.suggestionIsTheDraft(body.toString(), this.tulkkiConfirmedDraft),
            )
        ) {
            ComposerSend.Carriage.TOO_LONG -> {
                Toast.makeText(
                    hostActivity,
                    hostActivity.getString(R.string.message_is_too_long),
                    Toast.LENGTH_SHORT,
                )
                    .show()
                return
            }
            ComposerSend.Carriage.NOTHING -> {
                // The empty draft's answer was the attach menu; it is the composer's anchor now, and
                // both routes to it go through one method.
                popAttachMenu()
                return
            }
            ComposerSend.Carriage.PROMPT -> {
                // The field is emptied first and the next message is stored from what is in it, which
                // is what the deleted `setText("")` + `storeNextMessage()` did: the prompt owns the
                // draft from here, and an empty draft stores as nothing.
                writeComposerDraft("", 0)
                storeNextMessage()
                updateChatMsgHint()
                showComposerPrompt(body.toString())
                return
            }
            else -> Unit
        }

        // Trust comes last, immediately above the send it is needed for: resolving a device list
        // re-enters this method, and asking the gate again about a draft it already accepted answers
        // the same way. It cannot pre-empt the gate from here, because the gate has already spoken.
        if (trustKeysIfNeeded(conversation, REQUEST_TRUST_KEYS_TEXT)) {
            return
        }

        if (hasAttachments) {
            conversation.setCaption(if (body.length > 0) body.toString() else null)
            commitAttachments()
            messageSent()
            return
        }

        val message: Message
        if (conversation.getCorrectingMessage() == null) {
            var attention = false
            if (Pattern.compile("\\A@here\\s.*").matcher(body).find()) {
                attention = true
                body.delete(0, 6)
                while (body.length > 0 && Character.isWhitespace(body[0])) {
                    body.delete(0, 1)
                }
            }

            val replyTo = conversation.getReplyTo()
            if (replyTo != null) {
                if (((hostActivity.getBooleanPreference("allow_unencrypted_reactions", R.bool.allow_unencrypted_reactions) && conversation.getNextEncryption() == Message.ENCRYPTION_AXOLOTL) || conversation.getNextEncryption() == Message.ENCRYPTION_NONE)
                    && Emoticons.isEmoji(body.toString().replace(Regex("\\s"), ""))
                    && conversation.getNextCounterpart() == null && !replyTo.isPrivateMessage()
                ) {
                    val aggregated = replyTo.getAggregatedReactions()
                    val reactionBuilder = ImmutableSet.Builder<String>()
                    reactionBuilder.addAll(aggregated.ourReactions)
                    reactionBuilder.add(body.toString().replace(Regex("\\s"), ""))
                    hostActivity.xmppConnectionService.sendReactions(replyTo, reactionBuilder.build())
                    messageSent()
                    return
                } else {
                    message = replyTo.reply()
                    message.appendBody(body)
                }
                message.setEncryption(conversation.getNextEncryption())
            } else {
                message = Message(conversation, body.toString(), conversation.getNextEncryption())
                message.setBody(if (hasSubject && body.length == 0) null else body)
                if (message.bodyIsOnlyEmojis()) {
                    val spannable = message.getSpannableBody(null, null)
                    val imageSpans = spannable.getSpans(0, spannable.length, ImageSpan::class.java)
                    for (span in imageSpans) {
                        val start = spannable.getSpanStart(span)
                        val end = spannable.getSpanEnd(span)
                        spannable.delete(start, end)
                    }
                    if (imageSpans.size == 1 && spannable.toString().replace(Regex("\\s"), "").length < 1) {
                        // Only one inline image, so it's a sticker
                        val source = imageSpans[0].getSource()
                        if (source != null && source.length > 0 && source.substring(0, 4).equals("cid:")) {
                            try {
                                val cid = BobCid.cid(Uri.parse(source))
                                val f = hostActivity.xmppConnectionService.getFileForCid(cid)
                                // Only convert to file upload for E2EE (BoB is not encrypted)
                                if (f != null && (message.getEncryption() == Message.ENCRYPTION_AXOLOTL || message.getEncryption() == Message.ENCRYPTION_PGP || message.getEncryption() == Message.ENCRYPTION_OTR)) {
                                    message.setBody("")
                                    message.setRelativeFilePath(f.getAbsolutePath())
                                    FileBackends.get().updateFileParams(message)
                                }
                            } catch (e: Exception) {
                            }
                        }
                    }
                }
            }

            if (hasSubject) {
                message.setSubject(this.tulkkiSubject)
            }
            if (hostActivity.xmppConnectionService != null && hostActivity.xmppConnectionService.getBooleanPreference("show_thread_feature", uk.xa0.tulkki.xmpp.R.bool.show_thread_feature)) {
                message.setThread(conversation.getThread())
            }
            if (attention) {
                message.addPayload(Element("attention", "urn:xmpp:attention:0"))
            }
            Message.configurePrivateMessage(message)

        } else {
            message = conversation.getCorrectingMessage() ?: throw NullPointerException()
            // Tulkki: an edit is not the text this message was translated from. The stored pair
            // belongs to the version that was sent, so a "reuse" of it would put this text on the
            // wire untranslated; forgetting it here - before the body is replaced and before the uuid
            // moves - is what makes the decision below about the edit rather than about its
            // predecessor, and it takes any queue item keyed by the old uuid with it.
            OutgoingTranslation.forgetTranslation(hostActivity.xmppConnectionService, message)
            if (hasSubject) {
                message.setSubject(this.tulkkiSubject)
            }
            if (hostActivity.xmppConnectionService != null && hostActivity.xmppConnectionService.getBooleanPreference("show_thread_feature", uk.xa0.tulkki.xmpp.R.bool.show_thread_feature)) {
                message.setThread(conversation.getThread())
            }
            val replyTo = conversation.getReplyTo()
            if (replyTo != null) {
                if (Emoticons.isEmoji(body.toString().replace(Regex("\\s"), ""))) {
                    message.updateReaction(replyTo, body.toString().replace(Regex("\\s"), ""))
                } else {
                    message.updateReplyTo(replyTo, body)
                }
            } else {
                message.clearReplyReact()
                message.setBody(if (hasSubject && body.length == 0) null else body)
            }
            if (message.getStatus() == Message.STATUS_WAITING) {
                if (sendAt != null) message.setTime(sendAt)
                hostActivity.xmppConnectionService.updateMessage(message)
                messageSent()
                return
            } else {
                // Preserve the original stanza ID before the UUID changes, so recipients
                // can still look up this message by the ID they stored on their side.
                if (message.getRemoteMsgId() == null) {
                    message.setRemoteMsgId(message.getUuid())
                }
                message.putEdited(message.getUuid(), message.getServerMsgId())
                // Do not clear serverMsgId: the edit reflection sets reflectedServerMsgId=null
                // for edits, so markMessage won't overwrite it, and keeping the original
                // server-assigned ID lets recipients look up this message after editing.
                message.setUuid(UUID.randomUUID().toString())
            }
        }
        if (sendAt != null) message.setTime(sendAt)
        if (hostActivity != null
            && hostActivity.xmppConnectionService != null
        ) {
            // Tulkki: nothing is sent untranslated. The message goes into the conversation straight
            // away, unsent, and leaves once it has been translated - or stays there and says why.
            // An edit is no exception: it is a message the owner is sending, so it is translated and
            // held exactly like a new one, and the peer reads the correction in their own language.
            hideTulkkiBar()
            OutgoingTranslation.submit(
                hostActivity.xmppConnectionService,
                message,
                object : OutgoingTranslation.Listener {
                    override fun onHeld(reason: HeldSend.HoldReason?) {
                        heldSendSurfaces.showHeldReason(message, reason, tulkkiLanguage().isKnown)
                    }
                },
                object : OutgoingTranslation.Sender {
                    override fun send(message: Message) {
                        sendTranslated(message)
                    }
                },
            )
            setupReply(null)
            correctionBarController.hide()
            messageSent()
            return
        }
        when (conversation.getNextEncryption()) {
            Message.ENCRYPTION_OTR ->
                sendOtrMessage(message)
            Message.ENCRYPTION_PGP ->
                sendPgpMessage(message)
            else ->
                sendMessage(message)
        }
        setupReply(null)
        correctionBarController.hide()
    }


    fun requireTrustKeys(): Boolean {
        return trustKeysIfNeeded(currentConversation, REQUEST_TRUST_KEYS_NONE)
    }


    private fun trustKeysIfNeeded(conversation: Conversation, requestCode: Int): Boolean {
        return conversation.getNextEncryption() == Message.ENCRYPTION_AXOLOTL
                && trustKeysIfNeeded(requestCode)
    }


    protected fun trustKeysIfNeeded(requestCode: Int): Boolean {
        val axolotlService = (currentConversation.getAccount() ?: throw NullPointerException()).getAxolotlService()
        if (axolotlService == null) return false
        val targets = axolotlService.getCryptoTargets(currentConversation)
        val hasUnaccepted = !currentConversation.getAcceptedCryptoTargets().containsAll(targets)
        val hasUndecidedOwn =
                !axolotlService
                        .getKeysWithTrust(FingerprintStatus.createActiveUndecided())
                        .isEmpty()
        val hasUndecidedContacts =
                !axolotlService
                        .getKeysWithTrust(FingerprintStatus.createActiveUndecided(), targets)
                        .isEmpty()
        val hasPendingKeys = !axolotlService.findDevicesWithoutSession(currentConversation).isEmpty()
        val hasNoTrustedKeys = axolotlService.anyTargetHasNoTrustedKeys(targets)
        val downloadInProgress = axolotlService.hasPendingKeyFetches(targets)
        if (hasUndecidedOwn
                || hasUndecidedContacts
                || hasPendingKeys
                || hasNoTrustedKeys
                || hasUnaccepted
                || downloadInProgress) {
            axolotlService.createSessionsIfNeeded(currentConversation)
            val intent = Intent(hostActivity, TrustKeysActivity::class.java)
            val contacts = arrayOfNulls<String>(targets.size)
            for (i in contacts.indices) {
                contacts[i] = targets.get(i).toString()
            }
            intent.putExtra("contacts", contacts)
            intent.putExtra(
                EXTRA_ACCOUNT,
                (currentConversation.getAccount() ?: throw NullPointerException()).getJid().asBareJid().toString(),
            )
            intent.putExtra("conversation", currentConversation.getUuid())
            startActivityForResult(intent, requestCode)
            return true
        } else {
            return false
        }
    }


    fun updateChatMsgHint() {
        val multi = currentConversation.getMode() == Conversation.MODE_MULTI
        val hint: String
        if (currentConversation.getCorrectingMessage() != null) {
            hint = getString(R.string.send_corrected_message)
            conversationViewPager.setCurrentItem(0)
        } else if (multi && currentConversation.getNextCounterpart() != null) {
            val user =
                currentConversation
                    .getMucOptions()
                    .findUserByName(
                        (currentConversation.getNextCounterpart() ?: throw NullPointerException()).getResource(),
                    )
            var nick = if (user == null) null else user.getNick()
            if (nick == null) {
                nick = (currentConversation.getNextCounterpart() ?: throw NullPointerException()).getResource()
            }
            hint = getString(R.string.send_private_message_to, nick)
            conversationViewPager.setCurrentItem(0)
        } else if (multi && !currentConversation.getMucOptions().participating()) {
            hint = getString(R.string.you_are_not_participating)
        } else {
            if (hostActivity == null) return
            hint = UIHelper.getMessageHint(hostActivity, currentConversation)
            hostActivity.invalidateOptionsMenu()
        }
        // Tulkki: the sentence is the Compose field's, which is the field the owner can see. The Java
        // `EditText` and the `text_input_hint` strip that used to receive it are deleted with the row
        // they lived in, so this is the one delivery and there is no second copy of it.
        this.tulkkiComposerHint = hint
        refreshTulkkiComposer()

        tulkkiView.post(Runnable { updateThreadFromLastMessage() })
    }


    /**
     * Tulkki: the Java `EditMessage`'s IME refresh is gone with the field. The Compose
     * `DraftField` owns its own input type, and the deleted call re-applied a hint the framework had
     * already been given; the method stays because [reInit] asks for it on every thread switch.
     */
    fun setupIme() {
    }


    private fun handleActivityResult(activityResult: ActivityResult) {
        if (activityResult.resultCode == Activity.RESULT_OK) {
            handlePositiveActivityResult(activityResult.requestCode, activityResult.data)
        } else {
            handleNegativeActivityResult(activityResult.requestCode)
        }
    }


    private fun handlePositiveActivityResult(requestCode: Int, data: Intent?) {
        when (requestCode) {
            REQUEST_TRUST_KEYS_NONE -> Unit
            REQUEST_TRUST_KEYS_TEXT -> sendMessage()
            REQUEST_TRUST_KEYS_ATTACHMENTS -> commitAttachments()
            REQUEST_START_AUDIO_CALL ->
                triggerRtpSession(RtpSessionActivity.ACTION_MAKE_VOICE_CALL)
            REQUEST_START_VIDEO_CALL ->
                triggerRtpSession(RtpSessionActivity.ACTION_MAKE_VIDEO_CALL)
            REQUEST_PICK_DATE -> {
                val data = data ?: throw NullPointerException()
                val messageUuid = data.getStringExtra(ConversationListActivity.EXTRA_MESSAGE_UUID)
                if (messageUuid != null) {
                    updateSelection(messageUuid, false, false)
                }
            }
            ATTACHMENT_CHOICE_CHOOSE_IMAGE -> {
                val takePhotoUri = pendingTakePhotoUri.pop()
                if (takePhotoUri != null && (data == null || (data.getData() == null && data.getClipData() == null))) {
                    addMediaPreviews(
                        Attachment.of(hostActivity, takePhotoUri, Attachment.Type.IMAGE),
                    )
                }
                val imageUris =
                    Attachment.extractAttachments(hostActivity, data, Attachment.Type.IMAGE)
                if (imageUris.size == 1
                    && (imageUris.get(0).getMime() ?: throw NullPointerException()).startsWith("image/")
                    && !(imageUris.get(0).getMime() ?: throw NullPointerException()).equals("image/gif")
                    && !skipImageEditor()
                ) {
                    editImage(imageUris.get(0).getUri())
                } else {
                    addMediaPreviews(imageUris)
                    toggleInputMethod()
                }
            }
            ATTACHMENT_CHOICE_TAKE_PHOTO -> {
                val takePhotoUri = pendingTakePhotoUri.pop()
                if (takePhotoUri != null) {
                    if (!skipImageEditor()) {
                        editImage(takePhotoUri)
                    } else {
                        addMediaPreviews(
                            Attachment.of(hostActivity, takePhotoUri, Attachment.Type.IMAGE),
                        )
                        toggleInputMethod()
                    }
                } else {
                    Log.d(Config.LOGTAG, "lost take photo uri. unable to to attach")
                }
            }
            ATTACHMENT_CHOICE_EDIT_PHOTO -> {
                val data = data ?: throw NullPointerException()
                val editedUriPhoto: Uri? =
                    data.getParcelableExtra(ImageEditorActivity.KEY_EDITED_URI)
                if (editedUriPhoto != null) {
                    stagedAttachments.replaceOrAdd(
                        hostActivity,
                        data.getData(),
                        editedUriPhoto,
                        Attachment.Type.IMAGE,
                    )
                    toggleInputMethod()
                } else {
                    Log.d(Config.LOGTAG, "lost take photo uri. unable to to attach")
                }
            }
            ATTACHMENT_CHOICE_CHOOSE_FILE,
            ATTACHMENT_CHOICE_RECORD_VIDEO,
            ATTACHMENT_CHOICE_RECORD_VOICE -> {
                val type =
                    if (requestCode == ATTACHMENT_CHOICE_RECORD_VOICE) {
                        Attachment.Type.RECORDING
                    } else {
                        Attachment.Type.FILE
                    }
                val fileUris =
                    Attachment.extractAttachments(hostActivity, data, type)
                addMediaPreviews(fileUris)
                toggleInputMethod()
            }
            ATTACHMENT_CHOICE_LOCATION -> {
                val data = data ?: throw NullPointerException()
                val latitude = data.getDoubleExtra("latitude", 0.0)
                val longitude = data.getDoubleExtra("longitude", 0.0)
                val accuracy = data.getIntExtra("accuracy", 0)
                val geo: Uri
                if (accuracy > 0) {
                    geo = Uri.parse(String.format("geo:%s,%s;u=%s", latitude, longitude, accuracy))
                } else {
                    geo = Uri.parse(String.format("geo:%s,%s", latitude, longitude))
                }
                addMediaPreviews(
                    Attachment.of(hostActivity, geo, Attachment.Type.LOCATION),
                )
                toggleInputMethod()
            }
            ATTACHMENT_CHOICE_LIVE_LOCATION -> {
                val data = data ?: throw NullPointerException()
                val liveLat = data.getDoubleExtra("latitude", 0.0)
                val liveLon = data.getDoubleExtra("longitude", 0.0)
                val liveAccuracy = data.getIntExtra("accuracy", 0).toFloat()
                val liveDuration = pendingLiveLocationDuration
                pendingLiveLocationDuration = 0L
                if (liveDuration > 0 && currentConversation != null && hostActivity.xmppConnectionService != null) {
                    hostActivity.xmppConnectionService.startLiveLocationSharing(
                        currentConversation,
                        liveDuration,
                        liveLat,
                        liveLon,
                        liveAccuracy,
                    )
                }
            }
            REQUEST_INVITE_TO_CONVERSATION -> {
                val invite = XmppActivity.ConferenceInvite.parse(data)
                if (invite != null) {
                    if (invite.execute(hostActivity)) {
                        hostActivity.mToast =
                            Toast.makeText(
                                hostActivity,
                                R.string.creating_conference,
                                Toast.LENGTH_LONG,
                            )
                        (hostActivity.mToast ?: throw NullPointerException()).show()
                    }
                }
            }
        }
    }


    fun editImage(uri: Uri) {
        val intent = Intent(hostActivity, ImageEditorActivity::class.java)
        intent.setData(uri)
        intent.putExtra(ImageEditorActivity.KEY_CHAT_NAME, currentConversation.getName())
        startActivityForResult(intent, ATTACHMENT_CHOICE_EDIT_PHOTO)
    }




    /**
     * Tulkki: the strip's tap, from the Compose surface - the Java `MediaPreviewAdapter`'s own two
     * answers. An image opens the editor; anything else is opened by the application that takes it
     * ([StagedAttachments.open]). The row travels as its opaque uuid and is resolved again
     * here, so nothing hands an entity across the boundary.
     */
    override fun onAttachmentTap(attachmentId: String) {
        if (stagedAttachments == null || hostActivity == null) {
            return
        }
        val attachment = stagedAttachments.byId(attachmentId)
        if (attachment == null) {
            return
        }
        if (attachment.getType() == Attachment.Type.IMAGE) {
            editImage(attachment.getUri())
        } else {
            stagedAttachments.open(hostActivity, attachment)
        }
    }




    /**
     * Tulkki: the strip's remove corner, from the Compose surface: drop exactly that one from the
     * draft. It never deletes the file, so there is nothing to undo on disk; the composer's bar is
     * re-read and the send button re-answered, which is what the adapter's own removal did.
     */
    override fun onAttachmentRemoved(attachmentId: String) {
        if (stagedAttachments == null) {
            return
        }
        stagedAttachments.remove(attachmentId)
        refreshTulkkiComposer()
        toggleInputMethod()
    }


    private fun commitAttachments() {
        val attachments = stagedAttachments.attachments()
        if (anyNeedsExternalStoragePermission(attachments)
            && !hasPermissions(
                REQUEST_COMMIT_ATTACHMENTS,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            )
        ) {
            return
        }
        if (trustKeysIfNeeded(currentConversation, REQUEST_TRUST_KEYS_ATTACHMENTS)) {
            return
        }
        val callback: PresenceSelector.OnPresenceSelected =
            PresenceSelector.OnPresenceSelected {
                val i = attachments.iterator()
                val next: Runnable =
                    object : Runnable {
                        override fun run() {
                            try {
                                if (!i.hasNext()) return
                                val attachment = i.next()
                                if (attachment.getType() == Attachment.Type.LOCATION) {
                                    attachLocationToConversation(currentConversation, attachment.getUri())
                                    if (i.hasNext()) runOnUiThread(this)
                                } else if (attachment.getType() == Attachment.Type.IMAGE) {
                                    Log.d(
                                        Config.LOGTAG,
                                        "ConversationListActivity.commitAttachments() - attaching image to a conversation. CHOOSE_IMAGE",
                                    )
                                    attachImageToConversation(
                                        currentConversation,
                                        attachment.getUri(),
                                        attachment.getMime(),
                                        this,
                                    )
                                } else {
                                    Log.d(
                                        Config.LOGTAG,
                                        "ConversationListActivity.commitAttachments() - attaching file to a conversation. CHOOSE_FILE/RECORD_VOICE/RECORD_VIDEO",
                                    )
                                    attachFileToConversation(
                                        currentConversation,
                                        attachment.getUri(),
                                        attachment.getMime(),
                                        this,
                                    )
                                }
                                i.remove()
                                if (!i.hasNext()) messageSent()
                            } catch (e: java.util.ConcurrentModificationException) {
                                // Abort, leave any unsent attachments alone for the user to try again
                                Toast.makeText(hostActivity, "Sometimes went wrong with some attachments. Try again?", Toast.LENGTH_SHORT).show()
                            }
                            // The strip is the Compose one; a removed attachment is a re-read, not a
                            // `notifyDataSetChanged` on a RecyclerView nothing draws.
                            refreshTulkkiComposer()
                            toggleInputMethod()
                        }
                    }
                next.run()
            }
        if (currentConversation == null
            || currentConversation.getMode() == Conversation.MODE_MULTI
            || Attachment.canBeSendInBand(attachments)
            || ((currentConversation.getAccount() ?: throw NullPointerException()).httpUploadAvailable()
                && FileBackend.allFilesUnderSize(
                    hostActivity,
                    attachments,
                    getMaxHttpUploadSize(currentConversation),
                    !"uncompressed".equals(
                        UiHost.installed().videoCompression(hostActivity),
                    ),
                ))
        ) {
            callback.onPresenceSelected()
        } else {
            hostActivity.selectPresence(currentConversation, callback)
        }
    }


    private fun anyNeedsExternalStoragePermission(attachments: MutableCollection<Attachment>): Boolean {
        for (attachment in attachments) {
            if (attachment.getType() != Attachment.Type.LOCATION) {
                return true
            }
        }
        return false
    }
// end of chunk01: the line above is this slice's last member's closing brace (see the harness note
// at the top of the file: it is here so `assemble.py`'s trailing-brace pop cannot remove it).

// chunk02: two places where the Java's own text cannot be written literally in Kotlin, both kept
// faithful to the Java rather than quietly changed.
//
// 1. `onCreateOptionsMenu` calls `activity.isCameraFeatureAvailable()`, as the Java did. The Java
//    compiled it under Java's same-package `protected` access (`XmppActivity.kt` declares the
//    function `protected`, and this file shares that package). Kotlin has no package-level
//    `protected`, so that declaration has to be widened in `XmppActivity.kt` (e.g. `internal` plus
//    `@JvmName`, or public) before the assembled file compiles; this slice may not edit that file,
//    so the Java's call is written out and flagged here instead of being changed.
// 2. The Java nulls four non-null fields after their declaration (`activity = null` in `onDetach`,
//    `this.tulkkiMessagesView = null` and `stagedAttachments = null` in `onDestroyView`,
//    `this.tulkkiReply = null` in `setupReply`). Rule 2 types those fields non-null and parks them
//    on `unsafeNull()`, so the null writes are spelled that way too - RULES 17's "no `unsafeNull`"
//    is read as "do not redeclare it", since `head.kt` already provides it and no other spelling
//    writes the Java's null and still compiles.

    fun toggleInputMethod() {
        // Tulkki: nothing about the Java text row is left to toggle. The staged strip is the Compose
        // one (`UiComposer.attachments`), drawn inside the composer, and the `textinputLayoutNew`
        // caption row - the last thing this method moved - is deleted with the field: its state was a
        // `View.VISIBLE` inside an already `GONE` row, and the composer draws the caption field it
        // gates. The method stays because the attach paths ask for it after staging.
        updateSendButton()
    }


    private fun handleNegativeActivityResult(requestCode: Int) {
        when (requestCode) {
            ATTACHMENT_CHOICE_TAKE_PHOTO -> {
                if (pendingTakePhotoUri.clear()) {
                    Log.d(
                            Config.LOGTAG,
                            "cleared pending photo uri after negative activity result")
                }
            }
        }
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val activityResult = ActivityResult.of(requestCode, resultCode, data)
        if (hostActivity != null && hostActivity.xmppConnectionService != null) {
            handleActivityResult(activityResult)
        } else {
            this.postponedActivityResult.push(activityResult)
        }
        if (resultCode == Activity.RESULT_OK) {
            if (requestCode == ChatBackgroundHelper.REQUEST_IMPORT_BACKGROUND) {
                val imageUri: Uri? = data?.getData()
                if (imageUri != null) {
                    val editIntent = Intent(getActivity(), ImageEditorActivity::class.java)
                    editIntent.setData(imageUri)
                    editIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivityForResult(editIntent, REQUEST_EDIT_BACKGROUND)
                    return
                }
            } else if (requestCode == REQUEST_EDIT_BACKGROUND) {
                var uri: Uri? =
                        data?.getParcelableExtra<Uri>(ImageEditorActivity.KEY_EDITED_URI)
                if (uri == null && data != null) {
                    uri = data.getData()
                }
                if (uri != null) {
                    val resultIntent = Intent()
                    resultIntent.setData(uri)
                    if (currentConversation != null && currentConversation.getUuid() != null) {
                        ChatBackgroundHelper.onActivityResult(
                                hostActivity,
                                ChatBackgroundHelper.REQUEST_IMPORT_BACKGROUND,
                                resultCode,
                                resultIntent,
                                currentConversation.getUuid())
                    }
                }
                return
            }
        }

        if (requestCode == ChatBackgroundHelper.REQUEST_IMPORT_BACKGROUND) {
            refresh()
        }
    }


    fun unblockConversation(conversation: Blockable) {
        hostActivity.xmppConnectionService.sendUnblockRequest(
            conversation.getAccount(),
            conversation.getJid(),
            conversation.getBlockedJid(),
        )
    }


    override fun onAttach(activity: Activity) {
        super<XmppFragment>.onAttach(activity)
        Log.d(Config.LOGTAG, "ConversationFragment.onAttach()")
        if (activity is ConversationListActivity) {
            this.hostActivity = activity
        } else {
            throw IllegalStateException(
                    "Trying to attach fragment to activity that is not the ConversationListActivity")
        }
    }


    override fun onDetach() {
        super.onDetach()
        this.hostActivity = unsafeNull() // TODO maybe not a good idea since some callbacks really need it
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ui-9's command-form slice: :data's CommandSession draws through :ui's Compose
        // renderer, and this is where the renderer is installed. Idempotent.
        CommandFormHost.install()
        hostActivity.onBackPressedDispatcher.addCallback(this, backPressedLeaveSingleThread)
        hostActivity.onBackPressedDispatcher.addCallback(this, backPressedLeaveVoiceRecorder)
        oldOrientation = hostActivity.getRequestedOrientation()

        if (savedInstanceState == null && currentConversation != null) {
            currentConversation.jumpToLatest()
        }
    }


    // Tulkki: `onCreateOptionsMenu` was here, and it is deleted with its menu
    // (`fragment_conversation.xml`). The app's theme is a NoActionBar one and the activity's own bar
    // is the Compose `ConversationListChrome`, so this override was never called - the options menu
    // was a place the conversation's actions were written and could not be reached. Its one live side
    // effect, the navigation bar's visibility, is the activity's own (`showNavigationBar` is written
    // by `ConversationListFragment` and by `invalidateActionBarTitle`).
    //
    // What was reachable of that menu is the composer's context menu - the attach rows - and that is
    // the Compose attach menu now (`popAttachMenu`). `onOptionsItemSelected` stays: it is not only
    // this fragment's, the conversation list's row menu routes eight of its verbs into the fragment
    // through it (`ConversationListFragment.performRowAction`).


    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?,
    ): View? {
        // Tulkki: the conversation's screen is built here, in code, and there is no layout file any
        // more. `fragment_conversation.xml` was deleted with this slice: the pager - its tab strip,
        // its `LockedViewPager` and its two pages - is `ConversationPagerController`'s, and every
        // surface that used to be a `ComposeView` in the layout is one composition inside the page
        // (`ConversationHost.showRoot`, `showPage` and `showCommands`).
        //
        // The root is the `ComposeView` this returns: the wall behind everything, the `AndroidView`
        // holding the pager, and the conversation's popups. The conversation page is the pager's
        // first page and the command page its second, and the id `:data` searches the second page for
        // is the one `ConversationPagerController` sets on it.
        val pager = ConversationPagerController(hostActivity)
        this.pagerController = pager
        this.conversationViewPager = pager.pager
        this.tabLayout = pager.tabs
        this.commandsView = pager.page2
        this.tulkkiView = ComposeView(hostActivity)
        tulkkiView.setOnClickListener(null) // TODO why the fuck did we do this?

        // Check if we should adjust the soft keyboard
        conversationViewPager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrollStateChanged(state: Int) {}

            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}

            override fun onPageSelected(position: Int) {
                if (hostActivity != null) {
                    hostActivity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                }
            }
        })

        // Tulkki: the four bars' controllers are the fragment's own now. Their content is drawn inside
        // the page by the doors (`PinnedBarContent`, `EmojiPanelContent`, `CorrectionBarContent`,
        // `ComposerBarContent`), so nothing installs content on a `ComposeView` of its own - and the
        // pinned bar still reads the pins itself, decides its own content and owns its list, exactly
        // as the deleted Java that filled and hid a `LinearLayout` here did not.
        this.pinnedBarController = PinnedBarController()
        this.emojiPanelController = EmojiPanelController()
        this.correctionBarController = CorrectionBarController()
        this.composerBarController = ComposerBarController()
        refreshPinnedBar()

        // Tulkki: the six lines that configured the Java `EditMessage` are deleted with it - its IME
        // action listener (the Compose field's own IME action runs the same send), its rich-content
        // commit listener for a dropped `image/*` (the field was `GONE`, so nothing could be dropped
        // into it), its max height and its two text sizes (both were the field's own, and the Compose
        // field's typography is the theme's). The `StylingHelper.MessageEditorStyler` that decorated
        // its text goes with them: the Compose field draws plain text and the markup verbs write the
        // characters themselves.
        // Tulkki: the Java send button is deleted. Its drawing was already `GONE` with the row, its
        // click listener only dispatched the action tag it set on itself (the Compose send affordance
        // runs the one send path), and its one live job - anchoring the attach context menu - is the
        // composer's now (`onAttach`, `sendMessage`'s NOTHING branch).
        // Tulkki: the recording bar's cancel, share and timer gestures are the Compose
        // `VoiceRecordingBar`'s now (`ConversationEvents.onRecordingCancel`/`onRecordingShare`/
        // `onRecordingTogglePause`), and the `recordingVoiceActivity` block that carried the three
        // views is deleted with them - a bar drawn nowhere the owner could see it beside the composer.
        // The request-to-speak button is the Compose composer's
        // (`ConversationEvents.onRequestVoice`), drawn exactly when `canWrite()` says the request is
        // possible. The Java button sat in the row the composer hides, so the ask was unreachable
        // from a muted room; the listener, the view and its visibility write are gone with it.
        // Tulkki: the jump-to-latest control is the Compose list's now (`ConversationScreen`'s
        // `ScrollToBottom`), so the Java `FloatingActionButton`'s click listener is deleted with the
        // showing of the view: `toggleScrollDownButton` kept the view `GONE` for good. The one effect
        // the listener owned that the list cannot do for itself - leaving a loaded history part - is
        // `jumpToTheBottom`, handed to the page as its own callback.
        // Tulkki: the composer's own bar carries the held and failed sentences and the correction
        // bar the draft being corrected; both are inside the page's composition now.
        this.heldSendSurfaces = HeldSendSurfaces(
                hostActivity,
                composerBarController,
                { message -> translateHeldNow(message) },
                { message -> sendAsWrittenNow(message) },
                Runnable { raiseCapDialog() },
                Runnable { refreshTulkkiLanguageBar() })
        // Tulkki: the card's touch-outside contract is the Compose list's now. The Java listener below
        // was the last reader of the `DraggableListView`'s touches, and the card it dismissed
        // (`ReviewPopup`) had already lost its only caller with the adapter stub: the reading aid's
        // own overlay takes the tap that lands outside it, and the anchored-popup dismissal moves to
        // the Compose list's scroll (`AnchoredPopup.dismiss()` in `MessageList`). The scroll listener,
        // the transcript mode and the adapter wiring go with the same arithmetic.
        // Tulkki: the staged list is the Compose strip's now. The Java `media_preview` RecyclerView is
        // gone with the layout: the strip is `ConversationComposer`'s `PendingAttachments`, drawn from
        // `UiComposer.attachments`, and this is the list it draws (and the send path reads).
        stagedAttachments =
                StagedAttachments(
                        Math.round(getResources().getDimension(R.dimen.media_preview_size)),
                        Runnable { refreshTulkkiComposer() },
                        ConversationRead.viewScope())
        // Tulkki: the Java list the message adapter fills is not in the tree any more - the drawn
        // rows have been the Compose list's since `ui-9` - but the adapter is still instantiated and
        // mutated (`refresh()` notifies it), so it is kept on the same list of messages.
        messageListAdapter = MessageAdapter(hostActivity as XmppActivity, this.messageList)

        // Tulkki: the page's own state, then its one composition. It is written once and read by the
        // composition, and the one member that moves afterwards is the rows' session
        // (`refreshTulkkiMessages`).
        this.tulkkiPageInputs = ConversationHost.PageInputs(
            header = tulkkiHeaderSession,
            notices = tulkkiNoticeSession,
            composer = tulkkiComposerSession,
            snackbar = tulkkiSnackbarSession,
            rowMenu = tulkkiRowMenuSession,
            bar = composerBarController,
            pinned = pinnedBarController,
            pinnedRepository = hostActivity.getPinnedMessageRepository(),
            pinnedMedia = { cid ->
                val file: uk.xa0.tulkki.libs.DownloadableFileRef? =
                        if (hostActivity.xmppConnectionService == null)
                            null
                        else
                            hostActivity.xmppConnectionService.getFileForCid(cid)
                if (file == null) null else file.getAbsolutePath()
            },
            pinnedJump = java.util.function.Consumer<String> { uuid -> updateSelection(uuid, false, false) },
            emoji = emojiPanelController,
            emojiPicked = java.util.function.Consumer<String> { emoji ->
                // The emoji lands where the draft's caret is: the draft's selection is the one
                // value, so the insert is arithmetic on it and nothing else moves.
                val current = tulkkiComposerDraft()
                val at = ConversationHost.selectionEnd(current)
                val text = current.text
                writeComposerDraft(text.substring(0, at) + emoji + text.substring(at), at + emoji.length)
            },
            correction = correctionBarController,
            correctionCancel = Runnable { cancelCorrection() },
        )
        setupTulkkiPage()

        // Tulkki: the attach menu's rows are the Compose popup's now (`popAttachMenu`), anchored at
        // the page the composer is drawn in; the context menu they used to be copied into is gone
        // with the layout.
        // Tulkki: thread selection is the Compose marker's now (`ConversationComposer`'s
        // `ThreadMarker`, from `UiComposer.thread`): the tap is `ConversationEvents.onThreadTap` and
        // the long press `onThreadLongPress`, both answered below.
        // Tulkki: the two `Autocomplete` popups (`@` mentions and `:shortcode:` emoji) were bound to
        // the Java `EditMessage`, which the Compose composer hides and which therefore receives no
        // input: neither popup could fire, and the `message_autocomplete` setting they read could not
        // make them. They are retired with the field rather than re-hosted - the rule is
        // `docs/MIGRATION.md` "Design: the Compose UI" §3.6/§3.7: a Java container that is a stub is
        // deleted, not ported - and the drawn field's own IME action is the Compose `DraftField`'s.

        // Tulkki: the attach menu's context menu is registered on the composer, the view the owner
        // actually uses. It used to be the `GONE` send button, so `showContextMenu(0, 0)` anchored the
        // popup at a hidden view's origin rather than at the composer.

        // Tulkki: thread selection is the Compose marker's now (`ConversationComposer`'s
        // `ThreadMarker`, from `UiComposer.thread`): the tap is `ConversationEvents.onThreadTap` and
        // the long press `onThreadLongPress`, both answered below. The Java marker, its lock badge and
        // their two listeners sat in the row the composer hides, so the whole thread surface was
        // drawn and tappable nowhere the owner could see.

        // Tulkki: the two `Autocomplete` popups (`@` mentions and `:shortcode:` emoji) were bound to
        // the Java `EditMessage`, which the Compose composer hides and which therefore receives no
        // input: neither popup could fire, and the `message_autocomplete` setting they read could not
        // make them. They are retired with the field rather than re-hosted - the rule is
        // `docs/MIGRATION.md` "Design: the Compose UI" §3.6/§3.7: a Java container that is a stub is
        // deleted, not ported - and the drawn field's own IME action is the Compose `DraftField`'s.
        return tulkkiView
    }


    protected fun newThreadTutorialToast(s: String) {
        if (hostActivity == null) return
        val p = PreferenceManager.getDefaultSharedPreferences(hostActivity)
        val tutorialCount = p.getInt("thread_tutorial", 0)
        if (tutorialCount < 5) {
            Toast.makeText(hostActivity, s, Toast.LENGTH_SHORT).show()
            p.edit().putInt("thread_tutorial", tutorialCount + 1).apply()
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        if (backgroundExecutor != null && !backgroundExecutor.isShutdown()) {
            backgroundExecutor.shutdownNow() // Attempt to stop all actively executing tasks
        }
    }


    override fun onDestroyView() {
        super.onDestroyView()
        Log.d(Config.LOGTAG, "ConversationFragment.onDestroyView()")
        if (hostActivity != null) {
            hostActivity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        releaseTulkkiMessages()
        conversationViewPager.setAdapter(null)
        // The whole page goes with the view tree: a new tree is a new root `ComposeView`, a new pager
        // and three contents that have not been set yet. The commands' adopted pages go with their
        // pager, so the next tree adopts them again.
        tulkkiPageHosted = false
        tulkkiCommandsInitialised = false
        tulkkiFormatting = false
        // The staged strip's thumbnail loads run in a scope of their own; it goes with the view.
        if (stagedAttachments != null) {
            stagedAttachments.release()
            stagedAttachments = unsafeNull()
        }
        if (currentConversation != null) currentConversation.setupViewPager(null, null, false, null, R.id.commands_view)
    }


    fun quoteMessage(message: Message) {
        if (message.isPrivateMessage()) privateMessageWith(message.getCounterpart())
        if (hostActivity.xmppConnectionService != null && hostActivity.xmppConnectionService.getBooleanPreference("show_thread_feature", uk.xa0.tulkki.xmpp.R.bool.show_thread_feature)) {
            setThread(message.getThread())
        }
        currentConversation.setUserSelectedThread(true)
        if (!forkNullThread(message)) newThread()
        setupReply(message)
    }


    private fun forkNullThread(message: Message): Boolean {
        if (message.getThread() != null || currentConversation.getMode() != Conversation.MODE_MULTI) return true
        for (m in currentConversation.findReplies(message.getServerMsgId())) {
            val thread = m.getThread()
            if (thread != null) {
                if (hostActivity.xmppConnectionService != null && hostActivity.xmppConnectionService.getBooleanPreference("show_thread_feature", uk.xa0.tulkki.xmpp.R.bool.show_thread_feature)) {
                    setThread(thread)
                }
                return true
            }
        }

        return false
    }


    /**
     * Tulkki: opens or closes the composer's reply, and nothing else.
     *
     * **The state is the fragment's, the drawing is Compose's.** The reply is
     * `conversation.setReplyTo(...)` - what the send path reads to build the quoted message -
     * and the preview is the [UiQuote] the composer session draws. The Java preview view, its
     * image thumbnail, its expand button and its second-half strip are gone with it: the Compose
     * preview draws the app-language half or a cover strip, and it reads no bottom half at all, so
     * the referenced row's original has no field to reach. That is one preview, not two.
     *
     * What the Compose preview cannot carry is deliberately not rebuilt here. There is no image
     * thumbnail (upstream fetched it, and this is the redesign), no expand-to-twelve-lines (the
     * Compose preview ellipsises at two) and no second half - all three were the Java view's, and the
     * quote's own decision is unchanged.
     */
    private fun setupReply(message: Message?) {
        currentConversation.setReplyTo(message)
        this.tulkkiReply =
                if (message == null) unsafeNull()
                else
                    MessageProjection.replyPreview(
                            replyPreviewQuote(message),
                            message.getUuid() ?: throw NullPointerException())
        refreshTulkkiComposer()
    }


    /**
     * Tulkki: the composer's reply preview is a quote block - the message being answered, displayed
     * like any other - so it asks the same question the reply quote asks.
     *
     * The reply itself does not exist yet, so the only direction the composer can have is
     * outgoing, and what decides is whose text is quoted: `referenced.getStatus()` is what says
     * whether the message being answered is somebody else's or the owner's own. Somebody else's
     * original is concealed by the rule; the owner's own goes through the conceal-own setting.
     */
    private fun replyPreviewQuote(referenced: Message): ReplyQuote {
        return ReplyQuote.of(
                Message.STATUS_SEND,
                referenced.getBody(true),
                referenced.getTranslatedBody(),
                referenced.getTranslationState(),
                referenced.getStatus(),
                ConversationName.of(referenced.getConversation()),
                TranslationSettings.get(hostActivity).interpreter())
    }


    private fun setThread(thread: Element?) {
        this.currentConversation.setThread(thread)
        // Tulkki: the Java identicon, its lock badge and their two drawing writes are deleted. The
        // marker is the Compose composer's now (`UiComposer.thread`, built from this same
        // `conversation.getThread()` and `getLockThread()`), and `updateSendButton` re-reads the
        // composer so the marker follows the switch.
        updateSendButton()
    }


    /**
     * Tulkki: brings a row on screen. The deleted Java mechanism was
     * `setTranscriptMode(DISABLED)` + `setSelectionFromTop` + `post(TRANSCRIPT_MODE_NORMAL)` on the
     * `ListView`, and it could not work on a list that is `GONE` and draws nothing; a `LazyListState`
     * cannot be moved from Java either, so the row is named to the session and the screen scrolls.
     * The reader's place is the Compose `Anchor`'s, which is why the old midpoint offset is gone.
     *
     * The old callers handed in a `postSelectionRunnable` that pulsed the Java bubble; the Java
     * list draws nothing, so the only thing it ever did was name the empty `highlightMessage`, and
     * both are gone. The highlight is the Compose list's.
     */
    private fun updateSelection(uuid: String, populateFromMam: Boolean, recursiveFetch: Boolean) {
        if (recursiveFetch && (fetchHistoryDialog == null || !fetchHistoryDialog.isShowing())) return

        val pos = getIndexOfExtended(uuid, messageList)

        if (pos != -1) {
            hideFetchHistoryDialog()
            if (tulkkiMessagesSession != null) {
                tulkkiMessagesSession.requestScroll(uuid)
            }
            return
        }
        if (hostActivity != null && hostActivity.xmppConnectionService != null) {
            hostActivity.xmppConnectionService.jumpToMessage(
                    currentConversation,
                    uuid,
                    object : uk.xa0.tulkki.xmpp.services.JumpToMessageListener {
                        override fun onSuccess() {
                            hostActivity.runOnUiThread {
                                refresh(false)
                                currentConversation.messagesLoaded.set(true)
                                currentConversation.historyPartLoadedForward.set(true)
                                toggleScrollDownButton(tulkkiAtBottom)
                                updateSelection(uuid, populateFromMam, false)
                            }
                        }

                        override fun onNotFound() {
                            hostActivity.runOnUiThread {
                                if (populateFromMam && currentConversation.hasMessagesLeftOnServer()) {
                                    showFetchHistoryDialog()
                                    loadMoreMessages(true, false)
                                    tulkkiView
                                            .postDelayed({ updateSelection(uuid, populateFromMam, true) }, 500L)
                                } else {
                                    hideFetchHistoryDialog()
                                }
                            }
                        }
                    })
        }
    }


    private fun showFetchHistoryDialog() {
        if (fetchHistoryDialog != null && fetchHistoryDialog.isShowing()) return

        fetchHistoryDialog = ProgressDialog(hostActivity)
        fetchHistoryDialog.setIndeterminate(true)
        fetchHistoryDialog.setMessage(getString(R.string.please_wait))
        fetchHistoryDialog.setCancelable(true)
        fetchHistoryDialog.show()
    }


    private fun hideFetchHistoryDialog() {
        if (fetchHistoryDialog != null && fetchHistoryDialog.isShowing()) {
            fetchHistoryDialog.hide()
        }
    }


    // Tulkki: `onCreateContextMenu` and `onContextItemSelected` were here. They were the composer's
    // attach menu - the one live half of the deleted `fragment_conversation.xml`, reached by
    // `registerForContextMenu(tulkkiComposer)` and `showContextMenu()` - and the attach rows
    // are the Compose attach menu now (`popAttachMenu`, `handleAttachmentSelection`). With the
    // registration gone there is no context menu left to make, and the per-message menu went with the
    // Java list: `MessageAdapter.getView` answers an empty view and the drawn rows are the Compose
    // list's, whose long press is section 4.5's selection.


    /**
     * Tulkki: the conversation's verbs, by menu id.
     *
     * It is the deleted options menu's and the composer's context menu's one dispatcher, and it is
     * **not this fragment's alone**: the conversation list's own row menu builds a throwaway
     * `ConversationFragment` and calls this with eight of these ids
     * (`ConversationListFragment.performRowAction`), which is why the ids live in `values/ids.xml`
     * rather than in the menu that used to declare them.
     *
     * The attach rows are no longer among them - they are the Compose attach menu's own dispatcher
     * (`handleAttachmentSelection`), which is where the menu now is.
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (MenuDoubleTabUtil.shouldIgnoreTap()) {
            return false
        } else if (currentConversation == null) {
            return super.onOptionsItemSelected(item)
        }
        val id = item.getItemId()
        if (id == R.id.action_search) {
            startSearch()
        } else if (id == R.id.action_archive) {
            hostActivity.xmppConnectionService.archiveConversation(currentConversation)
        } else if (id == R.id.action_open_calendar) {
            startActivityForResult(
                ConversationCalendarActivity.Companion.createIntent(
                    hostActivity,
                    currentConversation.getUuid() ?: throw NullPointerException(),
                    null,
                ),
                REQUEST_PICK_DATE,
            )
        } else if (id == R.id.action_contact_details) {
            hostActivity.switchToContactDetails(currentConversation.getContact())
        } else if (id == R.id.action_muc_details) {
            ConferenceDetailsActivity.open(hostActivity, currentConversation)
        } else if (id == R.id.action_muc_participants) {
            val intent_user = Intent(hostActivity, MucUsersActivity::class.java)
            intent_user.putExtra("uuid", currentConversation.getUuid())
            hostActivity.startActivity(intent_user)
        } else if (id == R.id.action_invite) {
            startActivityForResult(
                ChooseContactActivity.create(hostActivity, currentConversation),
                REQUEST_INVITE_TO_CONVERSATION,
            )
        } else if (id == R.id.action_clear_history) {
            clearHistoryDialog(currentConversation)
        } else if (id == R.id.action_mute) {
            muteConversationDialog(currentConversation)
        } else if (id == R.id.action_unmute) {
            unMuteConversation(currentConversation)
        } else if (id == R.id.action_set_custom_bg) {
            if (hostActivity.hasStoragePermission(ChatBackgroundHelper.REQUEST_IMPORT_BACKGROUND)) {
                ChatBackgroundHelper.openBGPicker(this)
            }
        } else if (id == R.id.action_delete_custom_bg) {
            try {
                val bgfile =
                    ChatBackgroundHelper.getBgFile(hostActivity, currentConversation.getUuid())
                if (bgfile.exists()) {
                    bgfile.delete()
                    Toast.makeText(hostActivity, R.string.delete_background_success, Toast.LENGTH_LONG)
                        .show()
                } else {
                    Toast.makeText(hostActivity, R.string.no_background_set, Toast.LENGTH_LONG).show()
                }
                refresh()
            } catch (e: Exception) {
                Toast.makeText(hostActivity, R.string.delete_background_failed, Toast.LENGTH_LONG)
                    .show()
                throw RuntimeException(e)
            }
        } else if (id == R.id.action_block || id == R.id.action_unblock) {
            BlockContactDialog.show(hostActivity, currentConversation)
        } else if (id == R.id.action_audio_call) {
            checkPermissionAndTriggerAudioCall()
        } else if (id == R.id.action_video_call) {
            checkPermissionAndTriggerVideoCall()
        } else if (id == R.id.action_ongoing_call) {
            returnToOngoingCall()
        } else if (id == R.id.action_toggle_pinned) {
            togglePinned()
        } else if (id == R.id.action_add_shortcut) {
            addShortcut()
        } else if (id == R.id.action_block_avatar) {
            MaterialAlertDialogBuilder(hostActivity)
                .setTitle(R.string.block_media)
                .setMessage(R.string.block_avatar_question)
                .setPositiveButton(uk.xa0.tulkki.data.R.string.yes) { dialog, whichButton ->
                    hostActivity.xmppConnectionService.blockMedia(
                        FileBackends.get()
                            .getAvatarFile(currentConversation.getContact().getAvatarFilename()),
                    )
                    FileBackends.get()
                        .getAvatarFile(currentConversation.getContact().getAvatarFilename())
                        .delete()
                    hostActivity.avatarService().clear(currentConversation)
                    currentConversation.getContact().setAvatar(null)
                    hostActivity.xmppConnectionService.updateConversationUi()
                }
                .setNegativeButton(uk.xa0.tulkki.data.R.string.no, null)
                .show()
            refreshFeatureDiscovery()
        } else if (id == R.id.action_refresh_feature_discovery) {
            refreshFeatureDiscovery()
        }
        return super.onOptionsItemSelected(item)
    }

    fun onBackPressed(): Boolean {
        val wasLocked = currentConversation.getLockThread()
        currentConversation.setLockThread(false)
        backPressedLeaveSingleThread.isEnabled = false
        if (wasLocked) {
            setThread(null)
            currentConversation.setUserSelectedThread(false)
            refresh()
            updateThreadFromLastMessage()
            return true
        }
        if (emojiPanelController != null && emojiPanelController.isOpen()) {
            closeEmojiPanel()
            hideSoftKeyboard(hostActivity)
            return false
        }
        if (tulkkiRecordingActive) {
            mHandler.removeCallbacks(mTickExecutor)
            stopRecording(false)
            hostActivity.setResult(RESULT_CANCELED)
            //activity.finish();
            clearRecording()
            return false
        }
        return false
    }

    private fun startSearch() {
        val intent = Intent(hostActivity, SearchActivity::class.java)
        intent.putExtra(SearchActivity.EXTRA_CONVERSATION_UUID, currentConversation.getUuid())
        startActivity(intent)
    }

    private fun showLiveLocationDurationPicker() {
        val options = arrayOf(
            getString(R.string.live_location_15min),
            getString(R.string.live_location_1hour),
            getString(R.string.live_location_8hours),
            getString(R.string.live_location_custom),
        )
        val durations = longArrayOf(15 * 60 * 1000L, 60 * 60 * 1000L, 8 * 60 * 60 * 1000L, -1L)
        MaterialAlertDialogBuilder(hostActivity)
            .setTitle(R.string.live_location_duration)
            .setItems(options) { dialog, which ->
                if (which == 3) {
                    showCustomLiveLocationDurationInput()
                } else {
                    pendingLiveLocationDuration = durations[which]
                    attachFile(ATTACHMENT_CHOICE_LIVE_LOCATION)
                }
            }
            .show()
    }

    private fun showCustomLiveLocationDurationInput() {
        val input = EditText(hostActivity)
        input.setInputType(InputType.TYPE_CLASS_NUMBER)
        input.setHint(R.string.live_location_minutes_hint)
        input.setMinEms(4)
        MaterialAlertDialogBuilder(hostActivity)
            .setTitle(R.string.live_location_duration)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { dialog, which ->
                val text = input.getText().toString().trim()
                if (!text.isEmpty()) {
                    try {
                        val minutes = Integer.parseInt(text)
                        if (minutes > 0) {
                            pendingLiveLocationDuration = minutes * 60 * 1000L
                            attachFile(ATTACHMENT_CHOICE_LIVE_LOCATION)
                        }
                    } catch (ignored: NumberFormatException) {
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun scheduleMessage() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            val datePicker =
                com.google.android.material.datepicker.MaterialDatePicker.Builder.datePicker()
                    .setTitleText("Schedule Message")
                    .setSelection(
                        com.google.android.material.datepicker.MaterialDatePicker
                            .todayInUtcMilliseconds(),
                    )
                    .setCalendarConstraints(
                        com.google.android.material.datepicker.CalendarConstraints.Builder()
                            .setStart(
                                com.google.android.material.datepicker.MaterialDatePicker
                                    .todayInUtcMilliseconds(),
                            )
                            .build(),
                    )
                    .build()
            datePicker.addOnPositiveButtonClickListener { date ->
                val now = Calendar.getInstance()
                val timePicker = com.google.android.material.timepicker.MaterialTimePicker.Builder()
                    .setTitleText("Schedule Message")
                    .setHour(now.get(Calendar.HOUR_OF_DAY))
                    .setMinute(now.get(Calendar.MINUTE))
                    .setTimeFormat(
                        if (android.text.format.DateFormat.is24HourFormat(hostActivity)) {
                            com.google.android.material.timepicker.TimeFormat.CLOCK_24H
                        } else {
                            com.google.android.material.timepicker.TimeFormat.CLOCK_12H
                        },
                    )
                    .build()
                timePicker.addOnPositiveButtonClickListener { v2 ->
                    val dateCal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                    dateCal.setTimeInMillis(date)
                    val time = Calendar.getInstance()
                    time.set(
                        dateCal.get(Calendar.YEAR),
                        dateCal.get(Calendar.MONTH),
                        dateCal.get(Calendar.DAY_OF_MONTH),
                        timePicker.getHour(),
                        timePicker.getMinute(),
                        0,
                    )
                    val timestamp = time.getTimeInMillis()
                    sendMessage(timestamp)
                    val account = currentConversation.getAccount() ?: throw NullPointerException()
                    Log.d(
                        Config.LOGTAG,
                        "${account.getJid().asBareJid()}: scheduled message for $timestamp",
                    )
                }
                timePicker.show(hostActivity.getSupportFragmentManager(), "schedulMessageTime")
            }
            datePicker.show(hostActivity.getSupportFragmentManager(), "schedulMessageDate")
        }
    }

    private fun returnToOngoingCall() {
        val ongoingRtpSession =
            hostActivity.xmppConnectionService
                .getJingleConnectionManager()
                .getOngoingRtpConnection(currentConversation.getContact())
        if (ongoingRtpSession.isPresent()) {
            val id = ongoingRtpSession.get()
            val intent = Intent(hostActivity, RtpSessionActivity::class.java)
            intent.setAction(Intent.ACTION_VIEW)
            intent.putExtra(
                RtpSessionActivity.EXTRA_ACCOUNT,
                id.getAccount().getJid().asBareJid().toString(),
            )
            intent.putExtra(RtpSessionActivity.EXTRA_WITH, id.getWith().toString())
            if (id is AbstractJingleConnection) {
                intent.putExtra(RtpSessionActivity.EXTRA_SESSION_ID, id.getSessionId())
                startActivity(intent)
            } else if (id is JingleConnectionManager.RtpSessionProposal) {
                if (Media.audioOnly(id.media)) {
                    intent.putExtra(
                        RtpSessionActivity.EXTRA_LAST_ACTION,
                        RtpSessionActivity.ACTION_MAKE_VOICE_CALL,
                    )
                } else {
                    intent.putExtra(
                        RtpSessionActivity.EXTRA_LAST_ACTION,
                        RtpSessionActivity.ACTION_MAKE_VIDEO_CALL,
                    )
                }
                intent.putExtra(RtpSessionActivity.EXTRA_PROPOSED_SESSION_ID, id.sessionId)
                startActivity(intent)
            }
        }
    }

    private fun refreshFeatureDiscovery() {
        // The element is the read-only `Map.Entry` (covariant in `V`) because the Java's
        // `new AbstractMap.SimpleEntry("", null)` below puts a null presence in the entry.
        var presences: MutableSet<kotlin.collections.Map.Entry<String, Presence?>> =
            HashSet(currentConversation.getContact().getPresences().getPresencesMap().entries)
        if (presences.isEmpty()) {
            presences = HashSet()
            presences.add(AbstractMap.SimpleEntry<String, Presence?>("", null))
        }
        for (entry in presences) {
            var jid = currentConversation.getContact().getJid()
            if (entry.key != "") jid = jid.withResource(entry.key)
            hostActivity.xmppConnectionService.fetchCaps(
                currentConversation.getAccount() ?: throw NullPointerException(),
                jid,
                entry.value,
                Runnable {
                    if (hostActivity == null) return@Runnable
                    hostActivity.runOnUiThread {
                        refresh()
                        refreshCommands(true)
                    }
                },
            )
        }
    }

    private fun addShortcut() {
        val info: ShortcutInfoCompat
        if (currentConversation.getMode() == Conversation.MODE_MULTI) {
            info =
                hostActivity.xmppConnectionService
                    .getShortcutService()
                    .getShortcutInfo(currentConversation.getMucOptions())
        } else {
            info =
                hostActivity.xmppConnectionService
                    .getShortcutService()
                    .getShortcutInfo(currentConversation.getContact())
        }
        ShortcutManagerCompat.requestPinShortcut(hostActivity, info, null)
    }

    private fun togglePinned() {
        val pinned =
            currentConversation.getBooleanAttribute(Conversation.ATTRIBUTE_PINNED_ON_TOP, false)
        currentConversation.setAttribute(Conversation.ATTRIBUTE_PINNED_ON_TOP, !pinned)
        hostActivity.xmppConnectionService.updateConversation(currentConversation)
        hostActivity.invalidateOptionsMenu()
    }

    private fun checkPermissionAndTriggerAudioCall() {
        if (hostActivity.mUseTor ||
            (currentConversation.getAccount() ?: throw NullPointerException()).isOnion()
        ) {
            Toast.makeText(hostActivity, R.string.disable_tor_to_make_call, Toast.LENGTH_SHORT).show()
            return
        }
        if (hostActivity.mUseI2P ||
            (currentConversation.getAccount() ?: throw NullPointerException()).isI2P()
        ) {
            Toast.makeText(hostActivity, R.string.no_i2p_calls, Toast.LENGTH_SHORT).show()
            return
        }
        val permissions: MutableList<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions =
                Arrays.asList(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.BLUETOOTH_CONNECT,
                )
        } else {
            permissions = Collections.singletonList(Manifest.permission.RECORD_AUDIO)
        }
        if (hasPermissions(REQUEST_START_AUDIO_CALL, permissions)) {
            triggerRtpSession(RtpSessionActivity.ACTION_MAKE_VOICE_CALL)
        }
    }

    private fun checkPermissionAndTriggerVideoCall() {
        if (hostActivity.mUseTor ||
            (currentConversation.getAccount() ?: throw NullPointerException()).isOnion()
        ) {
            Toast.makeText(hostActivity, R.string.disable_tor_to_make_call, Toast.LENGTH_SHORT).show()
            return
        }
        if (hostActivity.mUseI2P ||
            (currentConversation.getAccount() ?: throw NullPointerException()).isI2P()
        ) {
            Toast.makeText(hostActivity, R.string.no_i2p_calls, Toast.LENGTH_SHORT).show()
            return
        }
        val permissions: MutableList<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions =
                Arrays.asList(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.CAMERA,
                    Manifest.permission.BLUETOOTH_CONNECT,
                )
        } else {
            permissions =
                Arrays.asList(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        }
        if (hasPermissions(REQUEST_START_VIDEO_CALL, permissions)) {
            triggerRtpSession(RtpSessionActivity.ACTION_MAKE_VIDEO_CALL)
        }
    }

    private fun triggerRtpSession(action: String) {
        if (hostActivity.xmppConnectionService.getJingleConnectionManager().isBusy()) {
            Toast.makeText(hostActivity, R.string.only_one_call_at_a_time, Toast.LENGTH_LONG).show()
            return
        }
        val account = currentConversation.getAccount() ?: throw NullPointerException()
        if (account.setOption(Account.OPTION_SOFT_DISABLED, false)) {
            hostActivity.xmppConnectionService.updateAccount(account)
        }
        val contact = currentConversation.getContact()
        if (Config.USE_JINGLE_MESSAGE_INIT && RtpCapability.jmiSupport(contact)) {
            triggerRtpSession(contact.getAccount(), contact.getJid().asBareJid(), action)
        } else {
            val capability: RtpCapability.Capability
            if (action == RtpSessionActivity.ACTION_MAKE_VIDEO_CALL) {
                capability = RtpCapability.Capability.VIDEO
            } else {
                capability = RtpCapability.Capability.AUDIO
            }
            PresenceSelector.selectFullJidForDirectRtpConnection(
                hostActivity,
                contact,
                capability,
            ) { fullJid ->
                triggerRtpSession(contact.getAccount(), fullJid, action)
            }
        }
    }

    private fun triggerRtpSession(account: Account, with: Jid, action: String) {
        UiHost.installed().placeCall(hostActivity.xmppConnectionService, account, with, action)
    }

    /**
     * The attach menu's rows, dispatched. Two of them are Tulkki's: `attach_take_picture` and
     * `attach_record_voice` were the `takePictureButton`/`recordVoiceButton` in the composer row the
     * Compose composer hides, so their capabilities lived only on a `GONE` view; the menu the live
     * `onAttach` affordance opens is where those two verbs are reachable now, and the buttons and
     * their listeners are deleted.
     */
    /**
     * Tulkki: the attach menu's six file-ish rows, by id. It was `(item: MenuItem)` - the deleted
     * context menu's own item - and the Compose menu hands the row's id instead, because there is no
     * `MenuItem` any more.
     */
    private fun handleAttachmentSelection(id: Int) {
        if (id == R.id.attach_choose_picture) {
            attachFile(ATTACHMENT_CHOICE_CHOOSE_IMAGE)
        } else if (id == R.id.attach_record_video) {
            attachFile(ATTACHMENT_CHOICE_RECORD_VIDEO)
        } else if (id == R.id.attach_take_picture) {
            attachFile(ATTACHMENT_CHOICE_TAKE_PHOTO)
        } else if (id == R.id.attach_record_voice) {
            attachFile(ATTACHMENT_CHOICE_RECORD_VOICE)
        } else if (id == R.id.attach_choose_file) {
            attachFile(ATTACHMENT_CHOICE_CHOOSE_FILE)
        } else if (id == R.id.attach_location) {
            attachFile(ATTACHMENT_CHOICE_LOCATION)
        } else if (id == R.id.attach_live_location) {
            showLiveLocationDurationPicker()
        } else if (id == R.id.attach_subject) {
            // Tulkki: the subject row is deleted, so there is no field to reveal. The entry's whole
            // Java body flipped a `visibility` on a view inside the `GONE` input area - a field the
            // owner could neither see nor type into - so nothing that was reachable is lost, and the
            // subject state itself is `tulkkiSubject`, filled by a correction. A Compose subject
            // affordance belongs with the composer.
        } else if (id == R.id.attach_schedule) {
            scheduleMessage()
        }
    }

    fun attachFile(attachmentChoice: Int) {
        attachFile(attachmentChoice, true, false)
    }

    fun attachFile(attachmentChoice: Int, updateRecentlyUsed: Boolean) {
        attachFile(attachmentChoice, updateRecentlyUsed, false)
    }

    fun attachFile(attachmentChoice: Int, updateRecentlyUsed: Boolean, fromPermissions: Boolean) {
        if (attachmentChoice == ATTACHMENT_CHOICE_RECORD_VOICE) {
            if (
                !hasPermissions(
                    attachmentChoice,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.RECORD_AUDIO,
                )
            ) {
                return
            }
        } else if (attachmentChoice == ATTACHMENT_CHOICE_TAKE_PHOTO ||
            attachmentChoice == ATTACHMENT_CHOICE_RECORD_VIDEO ||
            (attachmentChoice == ATTACHMENT_CHOICE_CHOOSE_IMAGE && !fromPermissions)
        ) {
            if (
                !hasPermissions(
                    attachmentChoice,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.CAMERA,
                )
            ) {
                return
            }
        } else if (attachmentChoice != ATTACHMENT_CHOICE_LOCATION &&
            attachmentChoice != ATTACHMENT_CHOICE_LIVE_LOCATION
        ) {
            if (!hasPermissions(attachmentChoice, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                return
            }
        }
        if (updateRecentlyUsed) {
            storeRecentlyUsedQuickAction(attachmentChoice)
        }
        val encryption = currentConversation.getNextEncryption()
        val mode = currentConversation.getMode()
        if (encryption == Message.ENCRYPTION_PGP) {
            if (hostActivity.hasPgp()) {
                if (mode == Conversation.MODE_SINGLE &&
                    currentConversation.getContact().getPgpKeyId() != 0L
                ) {
                    hostActivity.xmppConnectionService
                        .getPgpEngine()
                        ?.hasKey(
                            currentConversation.getContact(),
                            object : UiCallback<Contact> {
                                override fun userInputRequired(pi: PendingIntent?, contact: Contact) {
                                    startPendingIntent(
                                        pi ?: throw NullPointerException(),
                                        attachmentChoice,
                                    )
                                }

                                override fun success(contact: Contact) {
                                    invokeAttachFileIntent(attachmentChoice)
                                }

                                override fun error(errorCode: Int, contact: Contact?) {
                                    hostActivity.replaceToast(getString(errorCode))
                                }
                            },
                        )
                } else if (mode == Conversation.MODE_MULTI &&
                    currentConversation.getMucOptions().pgpKeysInUse()
                ) {
                    if (!currentConversation.getMucOptions().everybodyHasKeys()) {
                        val warning =
                            Toast.makeText(
                                hostActivity,
                                R.string.missing_public_keys,
                                Toast.LENGTH_LONG,
                            )
                        warning.setGravity(Gravity.CENTER_VERTICAL, 0, 0)
                        warning.show()
                    }
                    invokeAttachFileIntent(attachmentChoice)
                } else {
                    showNoPGPKeyDialog(false) { dialog, which ->
                        currentConversation.setNextEncryption(Message.ENCRYPTION_NONE)
                        hostActivity.xmppConnectionService.updateConversation(currentConversation)
                        invokeAttachFileIntent(attachmentChoice)
                    }
                }
            } else {
                hostActivity.showInstallPgpDialog()
            }
        } else {
            invokeAttachFileIntent(attachmentChoice)
        }
    }

    private fun storeRecentlyUsedQuickAction(attachmentChoice: Int) {
        try {
            hostActivity.getPreferences()
                .edit()
                .putString(
                    RECENTLY_USED_QUICK_ACTION,
                    SendButtonAction.of(attachmentChoice).toString(),
                )
                .apply()
        } catch (e: IllegalArgumentException) {
            // just do not save
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        val permissionResult = PermissionUtils.removeBluetoothConnect(permissions, grantResults)
        if (grantResults.size > 0) {
            if (allGranted(permissionResult.grantResults) ||
                requestCode == ATTACHMENT_CHOICE_CHOOSE_IMAGE
            ) {
                when (requestCode) {
                    REQUEST_START_DOWNLOAD -> if (this.mPendingDownloadableMessage != null) {
                        startDownloadable(this.mPendingDownloadableMessage)
                    }
                    REQUEST_ADD_EDITOR_CONTENT -> if (this.mPendingEditorContent != null) {
                        attachEditorContentToConversation(this.mPendingEditorContent)
                    }
                    REQUEST_COMMIT_ATTACHMENTS -> commitAttachments()
                    REQUEST_START_AUDIO_CALL ->
                        triggerRtpSession(RtpSessionActivity.ACTION_MAKE_VOICE_CALL)
                    REQUEST_START_VIDEO_CALL ->
                        triggerRtpSession(RtpSessionActivity.ACTION_MAKE_VIDEO_CALL)
                    else -> attachFile(requestCode, true, true)
                }
            } else {
                val res: Int
                val firstDenied =
                    getFirstDenied(permissionResult.grantResults, permissionResult.permissions)
                if (Manifest.permission.RECORD_AUDIO == firstDenied) {
                    res = R.string.no_microphone_permission
                } else if (Manifest.permission.CAMERA == firstDenied) {
                    res = R.string.no_camera_permission
                } else {
                    res = R.string.no_storage_permission
                }
                Toast.makeText(
                    hostActivity,
                    getString(res, BuildConfig.APP_NAME),
                    Toast.LENGTH_SHORT,
                )
                    .show()
            }
            ChatBackgroundHelper.onRequestPermissionsResult(
                this,
                requestCode,
                permissions,
                grantResults,
            )
        }
        if (writeGranted(grantResults, permissions)) {
            if (hostActivity != null && hostActivity.xmppConnectionService != null) {
                hostActivity.xmppConnectionService.getDrawableCache().evictAll()
                hostActivity.xmppConnectionService.restartFileObserver()
            }
            refresh()
        }
        if (cameraGranted(grantResults, permissions) || audioGranted(grantResults, permissions)) {
            // Pair 11 (D4): the island's `toggleForegroundService(ConversationListActivity)` overload
            // existed only for this caller and named a `:ui` type to do it. The `XmppConnectionService`
            // overload it forwarded to is the island's own vocabulary and stays.
            uk.xa0.tulkki.xmpp.services.ForegroundServiceLifecycle.toggleForegroundService(
                hostActivity.xmppConnectionService,
            )
        }
    }

    // This trailing line is here on purpose: assemble.py pops a final non-empty line whose stripped
    // text is `}`, which would otherwise be the `}` closing onRequestPermissionsResult above.

// Where the mechanical Java -> Kotlin mapping does not apply, this slice still follows the Java, so
// the deviations are named here:
//   * `transferable.javaClass.name` stands in for `transferable.getClass().getName()` and
//     `(expr).toString()` for `String.valueOf(expr)`: Kotlin maps `Object.getClass()` to the
//     `javaClass` property, and `String`'s Java statics are not reachable through Kotlin's `String`.
//   * `message.getConversation() !== currentConversation` keeps the Java's `!=`, which compares
//     object references; Kotlin's `!=` would call `equals`.
//   * `arrayOfNulls<CharSequence>(durations.size)` keeps the Java's `new CharSequence[n]`, whose
//     slots are null until each one is written, so the element type stays `CharSequence?` rather
//     than the rule's narrower `Array<CharSequence>`.

    private fun updateChatBG() {
        if (hostActivity == null || currentConversation == null) {
            tulkkiBackgroundSession.update(null)
            return
        }
        // Tulkki: upstream's tiled chat wallpaper (`R.drawable.chatbg`, the `?attr/chat_bg` pixels)
        // carried its watermark and is deleted, and the root's own background is the theme's - the
        // deleted `setBackgroundResource(0)` cleared a background nothing set. What is left is the
        // conversation's own picture: it is drawn for an image the owner gave this conversation and
        // for nothing else.
        if (hostActivity.unicoloredBG() || currentConversation.getUuid() == null) {
            tulkkiBackgroundSession.update(null)
            return
        }
        val uri = ChatBackgroundHelper.getBgUri(hostActivity, currentConversation.getUuid())
        tulkkiBackgroundSession.update(uri?.toString())
    }


    fun startDownloadable(message: Message) {
        if (!hasPermissions(REQUEST_START_DOWNLOAD, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            this.mPendingDownloadableMessage = message
            return
        }
        // Tulkki: C5-C - the ref; the `instanceof TransferablePlaceholder` below still tests the
        // `:data` class, which is a legal `:ui -> :data` read and is why the placeholder stays there.
        val transferable = message.getTransferable()
        if (transferable != null) {
            if (transferable is TransferablePlaceholder && message.hasFileOnRemoteHost()) {
                createNewConnection(message)
                return
            }
            if (!transferable.start()) {
                Log.d(Config.LOGTAG, "type: " + transferable.javaClass.name)
                Toast.makeText(hostActivity, uk.xa0.tulkki.xmpp.R.string.not_connected_try_again, Toast.LENGTH_SHORT)
                    .show()
            }
        } else if (message.treatAsDownloadable() ||
            message.hasFileOnRemoteHost() ||
            MessageUtils.unInitiatedButKnownSize(message)) {
            createNewConnection(message)
        } else {
            message.setDeleted(true)
            Log.d(
                Config.LOGTAG,
                "" + (message.getConversation() ?: throw NullPointerException()).getAccount() +
                    ": unable to start downloadable",
            )
        }
    }


    private fun createNewConnection(message: Message) {
        if (!hostActivity.xmppConnectionService.hasInternetConnection()) {
            Toast.makeText(hostActivity, uk.xa0.tulkki.xmpp.R.string.not_connected_try_again, Toast.LENGTH_SHORT)
                .show()
            return
        }
        val oob = message.getOob()
        if (oob != null && "cid".equals(oob.getScheme(), ignoreCase = true)) {
            try {
                val transfer =
                    UiHost.installed()
                        .bobTransferForMessage(hostActivity.xmppConnectionService, message)
                message.setTransferable(transfer)
                transfer.start()
            } catch (e: URISyntaxException) {
                Log.d(Config.LOGTAG, "BobTransfer failed to parse URI")
            }
        } else {
            hostActivity.xmppConnectionService
                .getHttpConnectionManager()
                .createNewDownloadConnection(message, true)
        }
    }


    /**
     * The clear-history prompt, a Compose dialog now (`conversation/ConversationDialogs.kt`).
     *
     * <p>It was `MaterialAlertDialogBuilder` over the inflated `dialog_clear_history.xml`; the
     * strings, the title, Confirm/Cancel and the two branches are the same, and the checkbox's
     * answer travels back as the confirm's own argument. The host is [showTulkkiDialog], which the
     * rest of the tree's view-based screens use.
     */
    protected fun clearHistoryDialog(conversation: Conversation) {
        hostActivity.showTulkkiDialog { dismiss ->
            ClearHistoryDialog(
                onDismiss = dismiss,
                onConfirm = { endConversation ->
                    dismiss()
                    hostActivity.xmppConnectionService.clearConversationHistory(conversation)
                    if (endConversation) {
                        hostActivity.xmppConnectionService.archiveConversation(conversation)
                        hostActivity.onConversationArchived(conversation)
                    } else {
                        hostActivity.onConversationListItemUpdated()
                        refresh()
                    }
                },
            )
        }
    }


    protected fun muteConversationDialog(conversation: Conversation) {
        val builder =
            MaterialAlertDialogBuilder(requireActivity())
        builder.setTitle(R.string.disable_notifications)
        val durations = hostActivity.getResources().getIntArray(R.array.mute_options_durations)
        val labels = arrayOfNulls<CharSequence>(durations.size)
        for (i in durations.indices) {
            if (durations[i] == -1) {
                labels[i] = hostActivity.getString(R.string.until_further_notice)
            } else {
                labels[i] = TimeFrameUtils.resolve(hostActivity, 1000L * durations[i])
            }
        }
        builder.setItems(labels) { dialog, which ->
            val till: Long
            if (durations[which] == -1) {
                till = Long.MAX_VALUE
            } else {
                till = System.currentTimeMillis() + (durations[which] * 1000L)
            }
            conversation.setMutedTill(till)
            hostActivity.xmppConnectionService.updateConversation(conversation)
            hostActivity.onConversationListItemUpdated()
            refresh()
            hostActivity.invalidateOptionsMenu()
        }
        builder.create().show()
    }


    private fun hasPermissions(requestCode: Int, permissions: MutableList<String>): Boolean {
        val missingPermissions = ArrayList<String>()
        for (permission in permissions) {
            if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ||
                    Config.ONLY_INTERNAL_STORAGE) &&
                permission == Manifest.permission.WRITE_EXTERNAL_STORAGE) {
                continue
            }
            if (hostActivity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(permission)
            }
        }
        if (missingPermissions.size == 0) {
            return true
        } else {
            requestPermissions(missingPermissions.toTypedArray(), requestCode)
            return false
        }
    }


    private fun hasPermissions(requestCode: Int, vararg permissions: String): Boolean {
        return hasPermissions(requestCode, ImmutableList.copyOf(permissions))
    }


    fun unMuteConversation(conversation: Conversation) {
        conversation.setMutedTill(0L)
        hostActivity.xmppConnectionService.updateConversation(conversation)
        hostActivity.onConversationListItemUpdated()
        refresh()
        hostActivity.invalidateOptionsMenu()
    }


    protected fun invokeAttachFileIntent(attachmentChoice: Int) {
        var intent = Intent()

        val takePhotoIntent = Intent()
        val takePhotoUri = FileBackends.get().getTakePhotoUri()
        pendingTakePhotoUri.push(takePhotoUri)
        takePhotoIntent.putExtra(MediaStore.EXTRA_OUTPUT, takePhotoUri)
        takePhotoIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        takePhotoIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        takePhotoIntent.setAction(MediaStore.ACTION_IMAGE_CAPTURE)

        val takeVideoIntent = Intent()
        takeVideoIntent.setAction(MediaStore.ACTION_VIDEO_CAPTURE)

        when (attachmentChoice) {
            ATTACHMENT_CHOICE_CHOOSE_IMAGE -> {
                intent.setAction(Intent.ACTION_GET_CONTENT)
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                intent.setType("*/*")
                intent.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                intent = Intent.createChooser(intent, getString(R.string.perform_action_with))
                if (hostActivity.checkSelfPermission(Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED) {
                    intent.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(takePhotoIntent, takeVideoIntent))
                }
            }
            ATTACHMENT_CHOICE_RECORD_VIDEO -> intent = takeVideoIntent
            ATTACHMENT_CHOICE_TAKE_PHOTO -> intent = takePhotoIntent
            ATTACHMENT_CHOICE_CHOOSE_FILE -> {
                intent.setAction(Intent.ACTION_GET_CONTENT)
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                intent.setType("*/*")
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                intent = Intent.createChooser(intent, getString(R.string.perform_action_with))
            }
            ATTACHMENT_CHOICE_RECORD_VOICE -> {
                backPressedLeaveVoiceRecorder.isEnabled = true
                recordVoice()
                return
            }
            ATTACHMENT_CHOICE_LOCATION -> intent = GeoHelper.getFetchIntent(hostActivity)
            ATTACHMENT_CHOICE_LIVE_LOCATION -> intent = GeoHelper.getFetchIntent(hostActivity)
        }
        val context = hostActivity
        if (context == null) {
            return
        }
        try {
            startActivityForResult(intent, attachmentChoice)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.no_application_found, Toast.LENGTH_LONG).show()
        }
    }


    override fun onResume() {
        super.onResume()
        tulkkiView.post(Runnable { fireReadEvent() })
        updateChatBG()
        // Tulkki: the field takes the caret through the composer's own focus request - the deleted
        // Java field's `requestFocus()` is the method this replaces, and that field is gone.
        this.tulkkiFocusRequest++
        refreshTulkkiComposer()
        // Tulkki: only the detector and the settings are warmed up here. A held message is NOT
        // re-attempted when the conversation comes back: the retry is the owner's, and it is a tap on
        // the unsent message's own bubble. Coming back to a conversation therefore cannot spend a
        // token or put a message on the wire.
        OutgoingTranslation.warmUp(hostActivity)
        // Tulkki: the conversation's language is learned from what the other person writes, so a
        // conversation with no stored inbound message has none at all. One bounded, silent archive
        // sample fills that gap - read for detection and dropped, never stored and never shown - and
        // it does nothing at all unless the language is genuinely unknown. The refresh it may ask for
        // is the bar below redrawing itself once a language has landed.
        if (hostActivity != null &&
            hostActivity.xmppConnectionService != null &&
            currentConversation != null) {
            UiHost.installed()
                .considerLanguageSample(
                    hostActivity.xmppConnectionService,
                    currentConversation,
                    Runnable { refreshTulkkiLanguageBar() },
                )
        }
        // Tulkki: the chip names the two ends of the pair - the app language and the conversation's -
        // and how the conversation's end is known. It is redrawn on every resume, which is also what
        // picks up a change to the app language made in the settings.
        refreshTulkkiLanguageBar()
        // Tulkki: the bubbles are the screen's other half of the same problem. Their halves and their
        // English rows are decided while a row is bound, and the settings screen is another activity,
        // so a display setting changed there leaves the rows already drawn as they were until
        // something binds them again - which is here, on the way back.
        refreshTulkkiBubbles()
        // Tulkki: the Compose list is the same problem again - its rows are projected with the display
        // switches read once, so a switch changed while the settings screen was open re-shows it here,
        // which is the resume the chip and the Java bubbles are redrawn on too.
        refreshTulkkiMessages()
        // Tulkki: and it is redrawn when the language changes while the composer is open. The inbound
        // worker names it from a received message and the MAM sample names it asynchronously; both go
        // through the service's own conversation-update broadcast, so the chip follows them instead
        // of waiting for the next resume. The send target was already read fresh - a chip that lags
        // behind it is a chip that lies about which language the message will go out in.
        if (hostActivity != null && hostActivity.xmppConnectionService != null) {
            hostActivity.xmppConnectionService.setOnConversationListChangedListener(this)
        }
    }


    /**
     * Tulkki: something about a conversation changed. Only the composer's language chip is of
     * interest here, and only when this fragment's view exists; [refreshTulkkiLanguageBar] is
     * idempotent, so being called for every conversation update is cheap and never leaves the chip
     * showing a language the conversation no longer has. Posted because the caller may be the
     * translation worker's thread.
     */
    override fun onConversationUpdate(newCaps: Boolean) {
        if (tulkkiView == null) {
            return
        }
        tulkkiView.post(Runnable { refreshTulkkiLanguageBar() })
    }


    /**
     * Tulkki: re-decide the bubbles when a display setting may have changed.
     *
     * `MessageAdapter` reads "show the second half", "conceal your own second half", both
     * English switches and *both* language settings while it draws a row
     * ([BubbleDisplaySettings] is the list). A row that has been bound is not bound again by
     * itself, so a setting turned off on the settings screen used to reach the bubbles already on
     * screen only through an unrelated re-bind - a new message, a scroll, reopening the conversation
     * - which is the owner's "the blurred rows still show". Coming back is the one moment that always
     * happens, and this fragment already redraws its language chip there for exactly that reason.
     *
     * The study language is on that list as a value, not as the interpreter's answer, and it is
     * the one field this comparison was missing: it is half of the rule that decides whether a bubble
     * has a second half or an English row at all, so flipping it from a real language to `en` or
     * `none` has to repaint - and it also reaches two decisions a bind makes by itself, the reading
     * aid's tap targets and which review the owner's own words read back. Carrying only the derived
     * mode would notice the first and miss the second.
     *
     * The comparison is what keeps it cheap and honest: an unchanged store means the rows were
     * decided by these same settings and nothing is touched. A re-bind cannot buy anything - the
     * English row's lookup off this path is a `peek`, which consults the cache and never the
     * API (`MessageAdapter.peekEnglish`) - and the adapter's per-message memos are left alone,
     * which is what makes the second bind free.
     */
    private fun refreshTulkkiBubbles() {
        if (tulkkiView == null || hostActivity == null || messageListAdapter == null) {
            return
        }
        val display =
            BubbleDisplaySettings.of(TranslationSettings.get(hostActivity))
        if (display.equals(this.tulkkiBubbleDisplay)) {
            return
        }
        this.tulkkiBubbleDisplay = display
        this.messageListAdapter.notifyDataSetChanged()
    }


    /**
     * Tulkki: the message is ready to go - translated, or found to need no translation. It leaves by
     * the same route as any other message, so this conversation's encryption still applies.
     */
    private fun sendTranslated(message: Message) {
        if (hostActivity == null || hostActivity.xmppConnectionService == null) {
            return
        }
        if (message.getConversation() !is Conversation ||
            message.getConversation() !== currentConversation) {
            // The owner has moved on to another conversation; the service can carry on without us.
            sendMessage(message)
            return
        }
        when (message.getEncryption()) {
            Message.ENCRYPTION_OTR -> sendOtrMessage(message)
            Message.ENCRYPTION_PGP -> sendPgpMessage(message)
            else -> sendMessage(message)
        }
    }


    /**
     * Tulkki: the owner tapped an unsent message that is still waiting for its translation. That tap
     * means "translate this one now and send it" - the outgoing mirror of a tap on a covered received
     * message - and it is the only retry a held send has: nothing re-attempts one when the account
     * reconnects, when the conversation comes back or when the cap is raised.
     *
     * The message leaves by this fragment's own send route, so this conversation's encryption is
     * still applied, and a translation that already succeeded is reused rather than bought again -
     * `HeldSend.mayReuse` is asked before any request. Whatever stops it - no key, no language,
     * the cap reached, no credit, an unreachable API - is said in the composer's bar, in the same
     * words as any other held send. It never sends the message untranslated, and it never shows an
     * original.
     */
    fun translateHeldNow(message: Message?) {
        if (hostActivity == null || hostActivity.xmppConnectionService == null || message == null) {
            return
        }
        OutgoingTranslation.sendHeldNow(
            hostActivity.xmppConnectionService,
            message,
            object : OutgoingTranslation.Listener {
                override fun onHeld(reason: HeldSend.HoldReason?) {
                    heldSendSurfaces.showHeldReason(message, reason, tulkkiLanguage().isKnown)
                }
            },
            object : OutgoingTranslation.Sender {
                override fun send(message: Message) {
                    sendTranslated(message)
                }
            },
        )
    }


    /**
     * Tulkki: the composer gate refused the draft. It was not sent and it never became a message: the
     * draft is the prompt, the composer is empty for the answer, and the suggested translation is
     * hidden behind a tap or shown at once, per the setting.
     */
    private fun showComposerPrompt(draft: String) {
        if (hostActivity == null || tulkkiView == null) {
            return
        }
        val settings = TranslationSettings.get(hostActivity)
        val language = ComposerGate.languageName(settings.appLanguage())
        val prompt = getString(R.string.tulkki_gate_prompt, language) + "\n" + draft
        if (settings.revealSuggestionFirst()) {
            showTulkkiBar(prompt, null, null, true)
            requestSuggestion(draft)
        } else {
            showTulkkiBar(
                prompt,
                getString(R.string.tulkki_gate_show, language),
                View.OnClickListener { view -> requestSuggestion(draft) },
                true,
                true,
            )
        }
    }


    /** Tulkki: the suggestion is a translation into the app language, and it counts against the cap. */
    private fun requestSuggestion(draft: String) {
        if (hostActivity == null || hostActivity.xmppConnectionService == null || tulkkiView == null) {
            return
        }
        composerBarController.hideActions()
        OutgoingTranslation.suggest(
            hostActivity.xmppConnectionService,
            draft,
            object : OutgoingTranslation.Suggestions {

                override fun onSuggestion(text: String?) {
                    if (tulkkiView == null || hostActivity == null) return
                    appendTulkkiBar(
                        "\n" +
                            getString(
                                R.string.tulkki_gate_suggestion,
                                ComposerGate.languageName(
                                    TranslationSettings.get(hostActivity)
                                        .appLanguage(),
                                ),
                            ) +
                            " " +
                            text,
                    )
                }

                override fun onConfirmsDraft() {
                    if (tulkkiView == null || hostActivity == null || currentConversation == null) {
                        return
                    }
                    // The model's app-language version of the draft is the draft: it was the app
                    // language all along, so the refusal was wrong. Remember it (the send path
                    // asks suggestionIsTheDraft before gating), put the words back and send them
                    // the way the owner wrote them - no prompt, no retyping, nothing bought. The
                    // record is the draft trimmed, not its comparison form: the equality is now
                    // strict, so a normalised record would fail to match its own draft.
                    hideTulkkiBar()
                    tulkkiConfirmedDraft = draft.trim()
                    writeComposerDraft(draft, draft.length)
                    // The deleted Java send button's click ran the send path; the call is direct
                    // now, and it reads the draft just written above.
                    sendMessage()
                }

                override fun onUnavailable(reason: HeldSend.HoldReason?) {
                    if (tulkkiView == null || hostActivity == null) return
                    appendTulkkiBar("\n" + HeldSendText.holdMessage(hostActivity, reason))
                }
            },
        )
    }


    /**
     * Tulkki: the owner asked for this one message to go as they wrote it. The decision and the
     * record are `OutgoingTranslation.sendAsWritten`'s; this is the screen's own send route,
     * so this conversation's encryption still applies.
     */
    private fun sendAsWrittenNow(message: Message?) {
        if (hostActivity == null || hostActivity.xmppConnectionService == null || message == null) {
            return
        }
        OutgoingTranslation.sendAsWritten(
            hostActivity.xmppConnectionService,
            message,
            object : OutgoingTranslation.Sender {
                override fun send(message: Message) {
                    sendTranslated(message)
                }
            },
        )
    }


    /**
     * Tulkki: the cap is a setting, and hitting it has to offer a way out on the spot rather than
     * silently freezing the composer.
     */
    private fun raiseCapDialog() {
        if (hostActivity == null) {
            return
        }
        val settings = TranslationSettings.get(hostActivity)
        val cap = settings.dailyTokenCap()
        val input = EditText(hostActivity)
        input.setInputType(InputType.TYPE_CLASS_NUMBER)
        input.setHint(R.string.tulkki_cap_hint)
        input.setText(Math.max(cap * 2, cap + 10_000).toString())
        MaterialAlertDialogBuilder(hostActivity)
            .setTitle(R.string.tulkki_cap_title)
            .setMessage(
                getString(
                    R.string.tulkki_cap_message,
                    cap,
                    OutgoingTranslation.todayUsed(settings),
                ),
            )
            .setView(input)
            .setPositiveButton(android.R.string.ok) { dialog, which ->
                try {
                    settings.setDailyTokenCap(
                        Integer.parseInt(input.getText().toString().trim()),
                    )
                } catch (e: NumberFormatException) {
                    return@setPositiveButton
                }
                // Tulkki: raising the cap does not re-attempt anything. The owner asked
                // for that explicitly - "cap raised" is not a trigger - so the held
                // messages stay held until the owner taps them. What the bar was saying
                // is no longer true, so the bar goes; the retry that follows is theirs.
                hideTulkkiBar()
            }
            .setNegativeButton(uk.xa0.tulkki.data.R.string.cancel, null)
            .create()
            .show()
    }


    private fun showTulkkiBar(
        text: String,
        actionLabel: String?,
        action: View.OnClickListener?,
        dismissible: Boolean,
    ) {
        showTulkkiBar(text, actionLabel, action, dismissible, false)
    }


    /**
     * @param flow the gate's prompt owns the bar until its flow ends, so nothing may talk over it.
     *     It is the only caller that passes `true`. Deriving this from "dismissible and has an
     *     action" was wrong: the language's "Change" and the cap's "Raise cap" are reasons with an
     *     action, not flows, and marking them busy meant [refreshTulkkiLanguageBar] - the
     *     thing that redraws the bar and the menu entry after the owner picks a language - returned
     *     early, so the screen kept saying the conversation had no language.
     */
    private fun showTulkkiBar(
        text: String,
        actionLabel: String?,
        action: View.OnClickListener?,
        dismissible: Boolean,
        flow: Boolean,
    ) {
        showTulkkiBar(text, actionLabel, action, dismissible, flow, true)
    }


    private fun showTulkkiBar(
        text: String,
        actionLabel: String?,
        action: View.OnClickListener?,
        dismissible: Boolean,
        flow: Boolean,
        pinned: Boolean,
    ) {
        showTulkkiBar(text, actionLabel, action, null, null, dismissible, flow, pinned)
    }


    /**
     * A bar with two actions, for the one surface that has two: a send failure offers the retry and
     * "send as written" side by side. The second button is hidden unless a caller names it, so every
     * one-action bar is drawn exactly as it was.
     */
    private fun showTulkkiBar(
        text: String,
        actionLabel: String?,
        action: View.OnClickListener?,
        altLabel: String?,
        altAction: View.OnClickListener?,
        dismissible: Boolean,
    ) {
        showTulkkiBar(text, actionLabel, action, altLabel, altAction, dismissible, false, false)
    }

// Notes where a rule and the Java disagree, and the Java is followed:
// - RULES.md rule 3 maps a Java `List<X>` to `MutableList<X>`; `stagedStrip()` keeps the Java's
//   `List<UiPendingAttachment>`, because its value is `StagedAttachments.strip()`'s read-only
//   Kotlin `List` and a `MutableList` return would not compile against it.
// - Members that exist as Kotlin properties are read as properties, not with the Java getter
//   spellings the Java used: `TextFieldValue.text`, `ChatAppearance.avatarsOn`/`.colorful`,
//   `ConversationLanguage.Resolved.isKnown`/`isUnknown`/`isSet`, and `MessagesSession.onRows`
//   (Kotlin has no `setOnRows` function to call). The Java `this::onNoticeAction` and the Java
//   `rows -> ...` lambda become `java.util.function.Consumer { ... }` SAM constructions.
// - `tulkkiDraft` is a non-null `TextFieldValue` now, so the Java `this.tulkkiDraft = null` the
//   deleted resync wrote has no spelling left: that method and the mirror it resynced are gone with
//   the Java field, and the empty draft is `ConversationHost.draft("", 0, 0)`.

private fun showTulkkiBar(
    text: String,
    actionLabel: String?,
    action: OnClickListener?,
    altLabel: String?,
    altAction: OnClickListener?,
    dismissible: Boolean,
    flow: Boolean,
    pinned: Boolean,
) {
    if (composerBarController == null) {
        return
    }
    composerBarController.show(text, actionLabel, action, altLabel, altAction, dismissible, flow, pinned)
}


private fun appendTulkkiBar(extra: String?) {
    if (composerBarController == null) {
        return
    }
    composerBarController.append(extra)
}


private fun hideTulkkiBar() {
    if (composerBarController == null) {
        return
    }
    // Whatever the bar was doing, it is not doing it any more.
    composerBarController.hide()
}


// -- Tulkki: the conversation's language -----------------------------------------------------
//
// The language lives in the composer's own chip: always there, quiet, one tap from the picker
// ({@link #onLanguageChipTap}). The bar is kept for states that block something - no key, no
// language, a held or failed message, a reached cap - because those are the ones worth a
// sentence. The overflow menu carries the language too, and the picker is the way out of an
// unknown language: it sets one, or clears the override back to detection.

/**
 * Draws the composer's language chip for whatever this conversation is, item 17's banner when
 * there is something at rest to say, and the Java bar for a held or failed send.
 *
 * The language is a normal, always-present fact about a conversation, so it lives in the
 * composer's chip and says nothing more once it is known. The at-rest lines - no key, an unknown
 * language, a message that could not be translated - are [ConversationNotice]'s, drawn by
 * [refreshTulkkiNotice] into the bar's own slot; the Java bar keeps the states that carry
 * a row's own action: a held or failed send.
 *
 * An unknown language is only ever blamed on the language when setting one would actually
 * send the message: without an API key the send path refuses before the language is looked at, so
 * the honest thing there is the key's own reason.
 */
private fun refreshTulkkiLanguageBar() {
    if (tulkkiView == null || composerBarController == null || currentConversation == null) {
        return
    }
    // The Compose composer's chip names the pair and its top row is item 16's switch; both are the
    // same state write now, and this is the only one they need.
    refreshTulkkiComposer()
    if (composerBarController.isBusy()) {
        // The gate owns the bar until its flow ends; the notice would talk over it, so it stands
        // down with it rather than drawing in the same slot.
        hideTulkkiNotice()
        return
    }
    if (!interpreting()) {
        // Off, the language line goes with the chip: both name the pair, and the line's action is
        // a way into the picker. Nothing else puts any bar up while off - the gate answers SEND,
        // no send is held and no cap is read - so there is no line to draw and nothing to say.
        // Item 17's banner is part of the same off state: it draws nothing at all.
        hideTulkkiNotice()
        if (!composerBarController.isPinned()) {
            hideTulkkiBar()
        }
        activityInvalidateOptionsMenu()
        return
    }
    // Item 17's decision one: the at-rest sentence is ConversationNotice's, drawn as the Compose
    // banner in the bar's own slot. Off it says nothing, a known language with nothing failed
    // says nothing, and an unknown language, a missing key and a failure each get their own line
    // with the fix inline. When it has something to say the Java bar stands down, so the same
    // sentence is never drawn twice; what is left for the bar is a held or failed send.
    if (refreshTulkkiNotice()) {
        if (!composerBarController.isPinned()) {
            hideTulkkiBar()
        }
        activityInvalidateOptionsMenu()
        return
    }
    // Tulkki: a row that cannot send owns the bar before it can go quiet. A send failure offers
    // both the retry and "send as written"; a held row offers the retry. Both are read from the
    // row's own persisted state, so the orphan the fixed-point measurement left - a row found
    // after a restart with no bar and no affordance at all - is reachable again.
    val failed = heldSendSurfaces.restingSendFailure(currentConversation)
    if (failed != null) {
        heldSendSurfaces.showSendFailure(failed)
        activityInvalidateOptionsMenu()
        return
    }
    val held = heldSendSurfaces.restingHeldSend(currentConversation)
    if (held != null) {
        heldSendSurfaces.showRestingHeldSend(currentConversation, held, tulkkiLanguage().isKnown)
        activityInvalidateOptionsMenu()
        return
    }
    // A known language is not news: the chip carries it, and the bar goes quiet.
    if (!composerBarController.isPinned()) {
        hideTulkkiBar()
    }
    activityInvalidateOptionsMenu()
}


/**
 * Item 17's decision one, hosted: draws the banner [ConversationNotice] decided, or puts the
 * surface away when there is nothing true to say.
 *
 * It is [ConversationHost.notices] over the four facts - the mode, the language, the key
 * and this conversation's failures - so the live conversation draws the very surface `ui-9` wrote
 * for the Compose screen rather than a second banner that could drift from it. The content is set
 * once per view and re-read into the session afterwards, the way `ConversationListFragment`
 * hosts its screen.
 *
 * @return whether a notice is drawn, so the caller knows the Java bar must stand down
 */
private fun refreshTulkkiNotice(): Boolean {
    if (tulkkiView == null || currentConversation == null) {
        return false
    }
    val notices =
        ConversationHost.notices(
            interpreting(),
            tulkkiLanguage().isKnown,
            apiKeyConfigured(),
            hasTranslationFailure(),
        )
    if (notices.isEmpty()) {
        hideTulkkiNotice()
        return false
    }
    // The banner is drawn by the page's composition from this session (`ConversationNotices`), so
    // there is one write and no host to set: the content was set with the page.
    tulkkiNoticeSession.update(notices)
    return true
}


/** Puts the notice surface away, which is the whole of "off draws none of it". */
private fun hideTulkkiNotice() {
    // Tulkki: the Java `setVisibility(GONE)` on the host is deleted with the write that showed it. The
    // surface draws nothing while the session has no lines, and clearing them is the same fact the
    // `GONE` carried - including across a view recreation, where a hidden-but-stale session would
    // have re-drawn the line the owner had put away.
    tulkkiNoticeSession.update(emptyList())
}


/**
 * Item 16's switch was moved: stores the owner's own answer for this conversation, the way the
 * language override is stored, so it survives the process being killed.
 *
 * It stores the answer and nothing else. A message the hold already touched is not revisited
 * here - the retry is the owner's tap on the row, and the send path reads the column on its next
 * decision - which is what `UiDoubtHold`'s own KDoc records. The redraw is
 * [refreshTulkkiComposer]'s, because the switch is the composer's own row now: it shows
 * the value now in force, so the switch never reads back as the answer the owner did not give.
 */
private fun storeDoubtHold(hold: Boolean?) {
    if (currentConversation == null) {
        return
    }
    currentConversation.setDoubtHold(hold)
    if (hostActivity != null && hostActivity.xmppConnectionService != null) {
        hostActivity.xmppConnectionService.updateConversation(currentConversation)
    }
    refreshTulkkiComposer()
}


/**
 * Tulkki: whether a received message in this conversation genuinely could not be translated.
 *
 * It is the same fact a covered bubble draws - translation was needed and did not happen - read
 * from the loaded rows' own persisted state, and only for messages that arrived: a send that could
 * not be translated already owns the composer's bar and does not need this line as well. The read
 * is in memory, so a refresh costs no query, and it is the condition the failures screen lists, so
 * the banner's link lands on rows about what the banner names.
 */
private fun hasTranslationFailure(): Boolean {
    if (currentConversation == null) {
        return false
    }
    val messages = currentConversation.messages
    synchronized(messages) {
        for (at in messages.size - 1 downTo 0) {
            val message = messages[at]
            if (message.getStatus() == Message.STATUS_RECEIVED &&
                message.getTranslationState() == Message.TRANSLATION_FAILED
            ) {
                return true
            }
        }
    }
    return false
}


/**
 * Tulkki: a banner's fix, taken. Navigation only - the picker, the settings screen, the failures
 * list. Nothing here re-attempts a translation or a send: the banner names a state and offers a way
 * to change it, and the retry stays the owner's own tap on a row.
 */
override fun onNoticeAction(action: NoticeAction) {
    when (action) {
        NoticeAction.CHANGE_LANGUAGE -> showLanguagePicker()
        NoticeAction.OPEN_SETTINGS -> openTulkkiSettings()
        NoticeAction.SEE_FAILURES -> openTranslationFailures()
        else -> {}
    }
}


/**
 * Tulkki: the header's subject line was tapped.
 *
 * A room's subject opens the room's details and a one-to-one contact's status message opens the
 * contact's, which is the deleted `muc_subject` row's own listener - the row and its icon shared one
 * body there, and they share this one now, because the icon and the text are the same row again.
 */
override fun onSubjectOpen() {
    if (currentConversation == null || hostActivity == null) return
    if (currentConversation.getMode() == Conversational.MODE_MULTI) {
        ConferenceDetailsActivity.open(hostActivity, currentConversation)
    } else {
        hostActivity.switchToContactDetails(currentConversation.getContact())
    }
}


/**
 * Tulkki: the header's subject line was put away.
 *
 * The write is the deleted `muc_subject_hide` listener's own - the room's subject marked hidden, or
 * the contact's status message - and the re-read is what the deleted `setVisibility(GONE)` stood for:
 * the line goes because the conversation says it is hidden, not because a view was told to hide.
 */
override fun onSubjectHide() {
    if (currentConversation == null) return
    if (currentConversation.getMode() == Conversational.MODE_MULTI) {
        currentConversation.getMucOptions().hideSubject()
    } else {
        currentConversation.hideStatusMessage()
    }
    refreshTulkkiHeader()
}


/** Tulkki: the header's tune line was tapped - the deleted `tune_subject` row's listener. */
override fun onTuneOpen() {
    if (currentConversation == null || hostActivity == null) return
    hostActivity.switchToContactDetails(currentConversation.getContact())
}


/** Tulkki: the header's tune line was put away - the deleted `tune_subject_hide` listener's body. */
override fun onTuneHide() {
    if (currentConversation == null) return
    currentConversation.getContact().setUserTune(null)
    refreshTulkkiHeader()
}


/**
 * Tulkki: the header's ephemeral hint was put away - the deleted `ephemeral_hint_hide` listener's
 * body, which hid the sentence and stored the flag. The timer itself is untouched: this is a
 * statement about the sentence, never about the ephemeral setting.
 */
override fun onEphemeralHide() {
    if (currentConversation == null) return
    currentConversation.setEphemeralHintHidden(true)
    DatabaseBackend.get().updateConversation(currentConversation)
    refreshTulkkiHeader()
}


/*
 * ui-9's message list, the swap: the Compose surface the page's composition draws, and
 * the taps `ConversationEvents` names. The rows are `ConversationRead.stream`'s read of the file
 * - the same read the whole Compose screen is built on - and nothing below decides a content fact:
 * the projector decides every one of them from the snapshot, before any setting is read.
 */





/**
 * Tulkki: sets the view tree's one composition, once per view.
 *
 * The root draws the wall, the `AndroidView` holding the pager and the conversation's popups; the
 * pager's first page draws the conversation (`ConversationHost.showPage`, the whole screen) and its
 * second the command page. Setting the three contents is the whole of the installation, and the three
 * re-reads after it are the surfaces whose content depends on the conversation rather than on the
 * view: the header's three lines, the wall's picture, and the composer's own reading.
 */
private fun setupTulkkiPage() {
    if (hostActivity == null || tulkkiView == null || tulkkiPageHosted) {
        return
    }
    this.tulkkiFormatting = false
    ConversationHost.showRoot(
        tulkkiView,
        pagerController,
        tulkkiBackgroundSession,
        tulkkiRowMenuSession,
        darkTheme(),
    )
    ConversationHost.showPage(
        pagerController.page1,
        tulkkiPageInputs,
        darkTheme(),
        this,
        Runnable { jumpToTheBottom() },
    )
    ConversationHost.showCommands(
        commandsView,
        tulkkiCommandsSession,
        darkTheme(),
        java.util.function.Consumer<UiCommand> { command ->
            if (hostActivity == null || currentConversation == null) return@Consumer
            command.start(hostActivity, currentConversation)
        },
    )
    this.tulkkiPageHosted = true
    refreshTulkkiHeader()
    updateChatBG()
    refreshTulkkiComposer()
}


/**
 * Tulkki: opens the participant menu for one room occupant - the deleted `muc_details_context.xml`,
 * whose last inflater was this file.
 *
 * <p>It decides nothing: [MucDetailsContextMenuHelper.visibleEntries] resolves which rows exist for
 * this occupant, in the menu's own order, and [MucDetailsContextMenuHelper.onMucDetailsAction] answers
 * the one that is taken, with the same `user` and `fingerprint` the deleted
 * `setOnMenuItemClickListener` was handed. The menu is drawn by `:ui`'s `MucUserDropdownMenu` inside
 * the conversation's one popup host, so the file is gone and the rows still come from one rule.
 *
 * @param key the row that opened it - the message's own uuid, which is the key the Compose rows use
 * @param user the occupant the long press resolved
 * @param fingerprint the row's encryption fingerprint, or `null`
 */
private fun showMucUserMenu(key: String, user: MucOptions.User, fingerprint: String?) {
    if (hostActivity == null || currentConversation == null || !tulkkiPageHosted) {
        return
    }
    tulkkiRowMenuSession.showMucUser(
        MucUserMenu(
            key = key,
            entries =
                MucDetailsContextMenuHelper.visibleEntries(
                    hostActivity,
                    currentConversation,
                    user,
                ),
        ),
    ) { action ->
        MucDetailsContextMenuHelper.onMucDetailsAction(action, user, hostActivity, fingerprint)
    }
}






/**
 * Tulkki: opens one of the conversation's popups, at the corner of [anchor] - the view the deleted
 * `PopupMenu(hostActivity, view)` hung off.
 *
 * The offset is measured rather than guessed: the anchor's and the host's own positions on screen,
 * subtracted, in dp. A caller with no view (the avatar long-press, whose rows are Compose) passes
 * `null` and the menu opens at the host's own corner, which is the recorded placement gap the
 * long-press names.
 */
private fun showRowMenu(anchor: View?, items: List<UiMenuItem>, onSelected: (Int) -> Unit) {
    if (hostActivity == null || tulkkiView == null || !tulkkiPageHosted || items.isEmpty()) {
        return
    }
    // The popup's host is the root: the menu is a Compose window, so the offset is measured from the
    // root's own screen position - which is the deleted 1 dp menu host's, at the page's top-start.
    val host = tulkkiView
    val density = hostActivity.resources.displayMetrics.density
    val hostAt = IntArray(2)
    host.getLocationOnScreen(hostAt)
    val anchorAt = IntArray(2)
    (anchor ?: host).getLocationOnScreen(anchorAt)
    showRowMenuAt(
        DpOffset(
            ((anchorAt[0] - hostAt[0]) / density).dp,
            ((anchorAt[1] - hostAt[1]) / density).dp,
        ),
        items,
        onSelected,
    )
}


/**
 * Tulkki: the same popup at a corner the caller measured itself, in dp against the root.
 *
 * A Compose surface that is not a view has no `getLocationOnScreen`, so its own measurement reaches
 * the session here. The guard, the one session and the menu's dismissal are [showRowMenu]'s own.
 */
private fun showRowMenuAt(offset: DpOffset, items: List<UiMenuItem>, onSelected: (Int) -> Unit) {
    if (hostActivity == null || tulkkiView == null || !tulkkiPageHosted || items.isEmpty()) {
        return
    }
    tulkkiRowMenuSession.show(items, offset, onSelected)
}


/**
 * Tulkki: the drawn composer's own top-left corner, in dp against the root - the attach menu's anchor.
 *
 * The composer is inside the page's composition and not a view, so the page measures it
 * (`ConversationHost.ComposerSession.anchorInWindow`) where the deleted `tulkki_composer` ComposeView
 * used to be read. The page's `ComposeView` (`pagerController.page1`) is not a substitute for it: that
 * is the whole screen, so anchoring there put the attach menu at the top of the page rather than over
 * the input row the owner tapped - the same misplacement, at the other end, that the deleted `GONE`
 * send button was. A measurement the composition has not drawn yet is the root's own corner, which is
 * where a menu asked for before the first layout would have landed either way.
 */
private fun tulkkiComposerAnchor(): DpOffset {
    val host = tulkkiView
    val density = hostActivity.resources.displayMetrics.density
    val rootAt = IntArray(2)
    host.getLocationInWindow(rootAt)
    val at = tulkkiComposerSession.anchorInWindow
    return DpOffset(((at.x - rootAt[0]) / density).dp, ((at.y - rootAt[1]) / density).dp)
}






/**
 * Tulkki: the header's next reading, from the conversation the fragment already has. It is the only
 * caller of [ConversationHost.header], so the three lines and the icon tint are one state write.
 *
 * The three decisions are the deleted block's own, in its own order: a room's printable and not
 * hidden subject; a one-to-one contact's status message while `pinned_status_message` is on and the
 * status either changed or is not hidden; the peer's `UserTune` unless it is `STOP`; and the
 * ephemeral hint while a timer is in force and the hint has not been put away. `null` is "no line",
 * which is the same fact the deleted `View.GONE` carried.
 */
private fun refreshTulkkiHeader() {
    if (tulkkiView == null || hostActivity == null || !tulkkiPageHosted) {
        return
    }
    val conversation = this.currentConversation
    if (conversation == null) {
        tulkkiHeaderSession.update(ConversationHost.header(null, null, null, tulkkiHeaderTint))
        return
    }
    var subject: UiSubjectLine? = null
    if (conversation.getMode() == Conversational.MODE_MULTI) {
        val roomSubject = conversation.getMucOptions().getSubject()
        if (Bookmark.printableValue(roomSubject) && !conversation.getMucOptions().subjectHidden()) {
            // Tulkki: `printableValue` is a call, not a Kotlin null check, so it does not
            // smart-cast `roomSubject`; it already guarantees non-null, so `?: ""` is the
            // unreachable fallback that the header's non-null `UiSubjectLine.text` asks for.
            subject = UiSubjectLine(roomSubject ?: "", true)
        }
    } else if (conversation.getMode() == Conversational.MODE_SINGLE &&
        hostActivity.xmppConnectionService != null &&
        hostActivity.xmppConnectionService.getBooleanPreference(
            "pinned_status_message",
            R.bool.pinned_status_message,
        )
    ) {
        val statusChange = conversation.onContactUpdatedAndCheckStatusChange(conversation.getContact())
        val statusText = conversation.getLastProcessedStatusText()
        if (statusText != null && (statusChange || !conversation.statusMessageHidden())) {
            subject = UiSubjectLine(statusText, false)
        }
    }
    val tune = conversation.getContact().getUserTune()
    val tuneLine =
        if (tune == null || tune == UserTune.STOP) {
            null
        } else {
            UiTuneLine(getString(R.string.user_tune_listening_to, tune.title, tune.artist))
        }
    val timer = conversation.getEphemeralTimer()
    val ephemeralLine =
        if (timer > 0 && !conversation.ephemeralHintHidden()) {
            val by = conversation.getEphemeralBy()
            val duration = UIHelper.getReadableEphemeralDuration(hostActivity, timer)
            UiEphemeralLine(
                if (by != null) {
                    getString(R.string.ephemeral_messages_active_by_hint, by, duration)
                } else {
                    getString(R.string.ephemeral_messages_active_hint, duration)
                },
            )
        } else {
            null
        }
    tulkkiHeaderSession.update(
        ConversationHost.header(subject, tuneLine, ephemeralLine, tulkkiHeaderTint),
    )
}




/**
 * Tulkki: the composer's next reading, from the field and the conversation the fragment already
 * has. It is the only caller of [ConversationHost.composer], so the chip, the draft, item
 * 16's switch and the send affordance are one state write and cannot drift.
 */
private fun refreshTulkkiComposer() {
    if (tulkkiView == null || hostActivity == null || !tulkkiPageHosted) {
        return
    }
    // Item 16's switch is the composer's own top row, so its reading is part of the same state
    // write as the chip beside it: `ConversationHost.doubtHold` resolves the stored tri-state in
    // the one place this module allows, and a null conversation draws no switch at all.
    val doubtHold: UiDoubtHold? =
        if (currentConversation == null) {
            null
        } else {
            ConversationHost.doubtHold(interpreting(), currentConversation.getDoubtHold())
        }
    tulkkiComposerSession.update(
        ConversationHost.composer(
            tulkkiComposerDraft(),
            tulkkiLanguagePair(),
            doubtHold,
            this.tulkkiReply,
            stagedStrip(),
            this.tulkkiFormatting,
            this.tulkkiComposerHint,
            currentConversation == null || canWrite(),
            tulkkiThread(),
            tulkkiEncryption(),
            tulkkiRecording(),
            this.tulkkiFocusRequest,
        ),
    )
}


/**
 * Tulkki: the encryption selector's state, from the facts
 * `ConversationMenuConfigurator.configureEncryptionMenu` read. The rule is
 * [ConversationHost.encryption], never a second copy of the visibility chain here, and a
 * fragment with no conversation, service or activity to read answers `null`.
 */
private fun tulkkiEncryption(): EncryptionSelectionState? {
    if (currentConversation == null || hostActivity == null || hostActivity.xmppConnectionService == null) {
        return null
    }
    return ConversationHost.encryption(
        currentConversation.getNextEncryption(),
        currentConversation.getMode() == Conversation.MODE_MULTI,
        currentConversation.getMode() == Conversation.MODE_SINGLE ||
            currentConversation.getMucOptions().participating(),
        currentConversation.isPrivateAndNonAnonymous(),
        currentConversation.getBooleanAttribute(
            Conversation.ATTRIBUTE_FORMERLY_PRIVATE_NON_ANONYMOUS,
            false,
        ),
        Config.supportUnencrypted(),
        Config.supportOpenPgp(),
        Config.supportOmemo(),
        Config.supportOtr(),
        OmemoSetting.isAlways(),
        hostActivity.xmppConnectionService.getBooleanPreference(
            "enable_otr_encryption",
            R.bool.enable_otr,
        ),
        hostActivity.hasPgp(),
        (currentConversation.getAccount() ?: throw NullPointerException()).getPgpSignature() != null,
    )
}


/**
 * Tulkki: the thread marker's reading, or `null` when there is no marker to draw - the
 * thread feature is off, the conversation cannot be written in, or the marker is not this
 * surface's. It is the same two-part condition `updateSendButton` used to resolve for the
 * `GONE` Java `threadIdenticonLayout`, moved to the surface that is drawn.
 */
private fun tulkkiThread(): UiThread? {
    if (currentConversation == null || hostActivity == null || !canWrite()) {
        return null
    }
    val preferences = PreferenceManager.getDefaultSharedPreferences(hostActivity)
    if (!preferences.getBoolean(
            "show_thread_feature",
            getResources().getBoolean(uk.xa0.tulkki.xmpp.R.bool.show_thread_feature),
        )
    ) {
        return null
    }
    val thread = currentConversation.getThread()
    return ConversationHost.thread(
        currentConversation.getLockThread(),
        if (thread == null) null else thread.getContent(),
    )
}


/**
 * Tulkki: the staged strip's state for the composer, or an empty strip before the view built the
 * holder. It is the one reading of the staged list, so the chip, the draft and the strip are one
 * state write and cannot disagree.
 */
private fun stagedStrip(): List<UiPendingAttachment> =
    if (stagedAttachments == null) ArrayList<UiPendingAttachment>() else stagedAttachments.strip()


/**
 * The draft's one reading: the value [writeComposerDraft] and [onDraftChanged] keep. It is not a
 * projection of any field, because there is no field - the Compose composer is the only place the
 * owner types and this is what they typed, their caret included.
 */
private fun tulkkiComposerDraft(): TextFieldValue = this.tulkkiDraft


/**
 * Tulkki: the draft's words, from the one reading of it. Every decision this fragment makes about
 * the draft - whether the attach menu offers a caption, whether the send button's icon is the
 * voice one, whether pausing stores a message, whether the chat state is `composing` - asks this
 * rather than a view, so the draft is never a buffer that can disagree with itself.
 */
private fun tulkkiComposerText(): String = tulkkiComposerDraft().text


/**
 * Tulkki: a draft edit, written into the one draft. The caret lands after the inserted text, which
 * is where a field that had just been typed into would leave it.
 */
private fun writeComposerDraft(text: String, selection: Int) {
    writeComposerDraft(ConversationHost.draft(text, selection, selection))
}


/**
 * Tulkki: the same write, for a verb that already holds the draft's own value - the text **and** its
 * caret, which the `> ` quote moves on its own. [DraftMarkup] answers the whole value, so a caller
 * that took the pair apart to hand it back would be rebuilding what it was just given.
 *
 * <p>It is also where the deleted `EditMessage`'s watcher used to fire. The Java field's mirror write
 * was a `setText`, so every draft write ran the typing machine; this runs it directly, with the same
 * three calls and the same timings, so a draft that emptied still stores the conversation's next
 * message and a draft that started still sends the conversation's chat state.
 */
private fun writeComposerDraft(draft: TextFieldValue) {
    this.tulkkiDraft = draft
    triggerKeyboardEvents(draft.text.length)
    refreshTulkkiComposer()
}


/**
 * Tulkki: the deleted `EditMessage.triggerKeyboardEvents`, kept because what it drove is not the
 * field's.
 *
 * <p>Every draft write posts the same `Config.TYPING_TIMEOUT` callback, says `COMPOSING` the first
 * time a non-empty draft appears and stores the next message the moment the draft empties - exactly
 * the three branches the deleted watcher ran, in its own order, on the same executor-free handler.
 */
private fun triggerKeyboardEvents(length: Int) {
    if (tulkkiTypingDetached) {
        return
    }
    tulkkiTypingHandler.removeCallbacks(tulkkiTypingTimeout)
    tulkkiTypingHandler.postDelayed(tulkkiTypingTimeout, Config.TYPING_TIMEOUT * 1000L)
    if (!tulkkiIsUserTyping && length > 0) {
        tulkkiIsUserTyping = true
        onTypingStarted()
    } else if (length == 0) {
        tulkkiIsUserTyping = false
        onTextDeleted()
    }
    onTextChanged()
}


/**
 * The pair the composer's chip names, from the two facts the fragment already reads: the
 * conversation's resolved language (its own or the detected one, and whether the owner set it) and
 * the app language. Off, there is no chip at all - `null`, the composer's own
 * "no language, no chip" convention - rather than a chip that would say nothing.
 */
private fun tulkkiLanguagePair(): UiLanguagePair? {
    if (!interpreting()) {
        return null
    }
    val language = tulkkiLanguage()
    return UiLanguagePair(
        if (language.isUnknown) null else language.code(),
        TranslationSettings.get(hostActivity).appLanguage(),
        language.isSet,
    )
}


/**
 * Tulkki: binds the Compose list to the conversation the fragment just set, or re-binds it after a
 * thread switch. The session is new because the rows are - the old watcher is released and the new
 * stream watched - and a second call for the same conversation is a no-op so a refresh does not
 * rebuild the composition.
 */
private fun refreshTulkkiMessages() {
    if (tulkkiView == null ||
        currentConversation == null ||
        hostActivity == null ||
        tulkkiPageInputs == null
    ) {
        return
    }
    val uuid = currentConversation.getUuid() ?: throw NullPointerException()
    val projection = tulkkiProjectionSettings()
    // The drawing switches are part of "shown with", beside the content ones: a list drawn while
    // `show_avatars` was off must be re-shown when the owner turns it on, or the reserved column and
    // the avatar edge stay missing until the conversation is left and entered again.
    val appearance = ChatAppearance.of(AppSettings(hostActivity))
    if (uuid.equals(tulkkiMessagesUuid) &&
        projection.equals(tulkkiMessagesSettings) &&
        appearance.equals(tulkkiMessagesAppearance)
    ) {
        return
    }
    releaseTulkkiMessages()
    this.tulkkiMessagesUuid = uuid
    this.tulkkiMessagesSettings = projection
    this.tulkkiMessagesAppearance = appearance
    this.tulkkiMessagesSession = ConversationHost.MessagesSession()
    this.tulkkiMessagesScope = ConversationRead.viewScope()
    // The reply seam's rows, written with the session's own state change and never separately:
    // `MessageFacts.quoted` is asked during the recomposition that change schedules, so the cache
    // it resolves a quote from must never be a write behind the rows being drawn.
    val cacheHost: XmppActivity = this.hostActivity
    this.tulkkiMessagesSession.onRows =
        java.util.function.Consumer<List<MessageSnapshot>> { rows ->
            cacheHost.cacheMessageRows(uuid, rows)
        }
    // The page's composition was set once (`setupTulkkiPage`); what moves here is what it reads. The
    // rows' session is the one member that is Compose state, so writing it redraws the list; the
    // facts and the switches beside it are written first, in the same pass, so the recomposition
    // that write schedules reads them all.
    this.tulkkiPageInputs.conversation = tulkkiConversationFacts()
    this.tulkkiPageInputs.facts = hostActivity.messageFacts()
    this.tulkkiPageInputs.settings = projection
    this.tulkkiPageInputs.avatarsOn = appearance.avatarsOn
    this.tulkkiPageInputs.colorful = appearance.colorful
    this.tulkkiPageInputs.formattingMarks = appearance.formattingMarks
    this.tulkkiPageInputs.locale = Locale.getDefault()
    this.tulkkiPageInputs.zone = ZoneId.systemDefault()
    this.tulkkiMessagesSession.watch(
        ConversationRead.stream(hostActivity, uuid),
        this.tulkkiMessagesScope,
    )
    this.tulkkiPageInputs.messages = this.tulkkiMessagesSession
    // Tulkki: the first fill, from the same read the update path pushes. A session that only
    // watched the file would open on the loading shape until the watch's first emission landed -
    // and stay there if it never did - so the read the fragment already makes fills it now.
    refreshTulkkiMessageRows()
}

// Chunk06 note (rule vs Java), for the lane that assembles and compiles:
// - `XmppActivity.announcePgp` (XmppActivity.kt:1491) and `XmppActivity.showQrCode(uri: String?)`
//   (:1932) are `protected`. Java's `protected` also reaches the package, which is how the Java file
//   may call them; Kotlin's `protected` is subclass-only, so the Java's call shape is kept here and
//   those two declarations must be widened before the assembled file compiles (head.kt's own
//   `quickPasswordEdit` call is the same case).
// - `showContextMenu(0, 0)` takes floats in Kotlin (`0f, 0f`), and `Config.MESSAGE_MERGE_WINDOW` is
//   an `Int` const where `ConversationFacts.mergeWindow` is a `Long`, so the Java's implicit
//   widening becomes `.toLong()`.
// - `highlightInConference(...)` and `privateMessageWith(...)` are handed the Java's own nullable
//   reads, without a `?: throw`: the Java never dereferenced them here, so the tolerance is kept and
//   those declaring chunks own their parameter types. The read's `List` stays the Kotlin
//   declaration's `List` (`ConversationRead.read` returns it, `MessagesSession.update` takes it).

/**
 * Tulkki: the drawn list's rows, pushed from the fragment's own update path.
 *
 * `refresh()` is the app's one message-update notification - a send, an arrival and a thread
 * switch all end there - and it used to re-read the Java list and notify the `GONE`
 * `MessageAdapter` alone. The Compose list is the one that is drawn, and its session was fed by
 * `ConversationRead.stream` alone: a row appended after that one subscription was the file's to
 * announce, so a send or an arrival the watch did not re-emit left the drawn rows as the first
 * emission had them - the new bubble absent, the list not following it, and the day/run arithmetic
 * computed over the old rows. This writes the same state with the same read, so the drawn list
 * learns what the update path learned.
 *
 * The read runs on `backgroundExecutor`, not here: `MessageSnapshots.read` is a Room blocking
 * query and Room refuses the main thread (`performBlocking`'s own `assertNotMainThread`), which is
 * also why `ConversationListRead.rows` runs on its own executor. The single-thread executor keeps
 * the reads in submission order, and each result lands only if it is still this session's and this
 * conversation's.
 *
 * The session's own `onRows` listener (`refreshTulkkiMessages` installs it) hands each write to
 * `XmppActivity.cacheMessageRows`, so the reply seam's quote answer - asked during the
 * recomposition this schedules, and forbidden from opening the database - resolves the row it names
 * out of a cache written with the rows, never beside them. The entry goes with the list
 * (`releaseTulkkiMessages`).
 *
 * It is a no-op before the list is hosted, after it is released, and for a conversation this
 * session is not bound to: the rows are only ever the current conversation's.
 */
private fun refreshTulkkiMessageRows() {
    if (tulkkiView == null ||
        hostActivity == null ||
        currentConversation == null ||
        tulkkiMessagesSession == null ||
        backgroundExecutor.isShutdown()
    ) {
        return
    }
    val uuid = tulkkiMessagesUuid
    if (uuid == null || uuid != currentConversation.getUuid()) {
        return
    }
    val session = this.tulkkiMessagesSession
    val host = this.hostActivity
    backgroundExecutor.execute {
        val rows: List<MessageSnapshot> = ConversationRead.read(host, uuid)
        host.runOnUiThread {
            if (tulkkiMessagesSession === session && uuid == tulkkiMessagesUuid) {
                // The session's `onRows` listener caches these for the reply seam
                // in the same write, before the state the composition draws moves.
                session.update(rows)
            }
        }
    }
}


/**
 * Tulkki: releases the list's watcher and its scope with the view that owned them. The rows stay
 * in the session, so a view set up again watches a fresh stream rather than the dead one.
 */
private fun releaseTulkkiMessages() {
    if (tulkkiMessagesUuid != null && hostActivity != null) {
        // The reply seam's cached rows go with the list that drew them, so the cache holds the
        // conversations on screen rather than every one ever opened.
        hostActivity.clearMessageRows(tulkkiMessagesUuid)
    }
    if (tulkkiMessagesSession != null) {
        tulkkiMessagesSession.release()
        tulkkiMessagesSession = unsafeNull()
    }
    if (tulkkiMessagesScope != null) {
        ConversationRead.releaseScope(tulkkiMessagesScope)
        tulkkiMessagesScope = unsafeNull()
    }
    this.tulkkiMessagesUuid = unsafeNull()
    this.tulkkiMessagesSettings = unsafeNull()
    this.tulkkiMessagesAppearance = unsafeNull()
    this.tulkkiAskingBodies.clear()
    // A re-shown list is a fresh list: its first viewport fact is an opening again.
    this.tulkkiViewportSeen = false
    this.tulkkiLastVisibleUuid = unsafeNull()
    this.tulkkiAtBottom = true
}


/**
 * The facts every row of this conversation shares, from the entities the fragment already reads.
 * The projector is handed them, so the list cannot decide the pair, the room or the merge window
 * for itself. `forceNames` is the tree's own `mForceNames`, which the Java list passed
 * as `false` for this screen.
 */
private fun tulkkiConversationFacts(): ConversationFacts {
    val settings = TranslationSettings.get(hostActivity)
    return ConversationFacts(
        settings.interpreter(),
        settings.appLanguage(),
        settings.studyLanguage(),
        tulkkiLanguage().storedCode(),
        if (currentConversation == null) null else currentConversation.getName().toString(),
        Config.MESSAGE_MERGE_WINDOW.toLong(),
        currentConversation != null && currentConversation.getMode() == Conversational.MODE_MULTI,
        false,
    )
}


/**
 * The five display switches the projection's rules take, read here and nowhere else: the same
 * settings `MessageAdapter` bound its rows with, so a list that changed one of them changes
 * the same way the Java list did.
 */
private fun tulkkiProjectionSettings(): ProjectionSettings {
    val settings = TranslationSettings.get(hostActivity)
    return ProjectionSettings(
        settings.showSecondHalf(),
        settings.showConcealedOriginal(),
        settings.concealOwnSecondHalf(),
        settings.showBlurredEnglish(),
        settings.showEnglishRetranslation(),
    )
}


/**
 * The row a tap or a swipe named, by its local uuid, or `null` when the fragment's own list
 * no longer holds it. The Compose rows come from the file's read and the fragment's list from the
 * conversation; the two name the same row while the list is loaded, which is the same assumption
 * the Java list's own taps made.
 */
private fun findMessage(uuid: String): Message? {
    synchronized(messageList) {
        for (message in messageList) {
            if (uuid == message.getUuid()) {
                return message
            }
        }
    }
    return null
}


/**
 * A bubble was tapped. The tap means "translate this one, now" - never "show the original" - and
 * for a row Tulkki is holding for its translation it means "translate this one and send it", the
 * outgoing mirror the design gives its own method. Which of the two it is is a fact about the row,
 * so `OutgoingTranslation` is asked rather than the surface guessing.
 *
 * One request per row at a time: the marker is what stops a second tap being a second purchase,
 * and it is the fragment's now that the tap is. The failure the queue reports is not drawn here -
 * the row's cover reason is the live facts seam's (`MessageFacts`), which the list is handed
 * `NONE` of in this slice - so the marker is dropped and the next tap may ask again.
 */
override fun onBodyTap(messageUuid: String) {
    if (hostActivity == null || hostActivity.xmppConnectionService == null) {
        return
    }
    val message = findMessage(messageUuid)
    if (message == null) {
        return
    }
    val settings = TranslationSettings.get(hostActivity)
    if (OutgoingTranslation.isHeldForTranslation(
            message,
            settings.appLanguage(),
            settings.interpreter(),
        )
    ) {
        translateHeldNow(message)
        return
    }
    if (!tulkkiAskingBodies.add(messageUuid)) {
        return
    }
    UiHost.installed()
        .requestTranslation(
            hostActivity.xmppConnectionService,
            message,
            { refusal ->
                hostActivity.runOnUiThread { tulkkiAskingBodies.remove(messageUuid) }
            },
        )
}


/**
 * Tulkki: the Compose list's viewport facts, which are the three jobs the deleted Java
 * `OnScrollListener` did off the `ListView` - the read receipt (the bottom-most drawn row), the
 * jump-to-latest control (at-bottom) and the older page (at-start). The scroll-dismiss of the
 * anchored card is the screen's, beside this.
 *
 * **The opening layout is not a scroll.** The list opens at its latest row (the screen's own
 * `TRANSCRIPT_MODE_NORMAL`), so its first viewport fact is the pre-scroll one and it names the
 * top of the list; fetching there would buy a page on every conversation opened. It is ignored,
 * and every later at-start is a real scroll to the top.
 */
override fun onViewport(lastVisibleUuid: String?, atBottom: Boolean, atStart: Boolean) {
    if (tulkkiView == null || currentConversation == null) {
        return
    }
    val opening = !tulkkiViewportSeen
    tulkkiViewportSeen = true
    this.tulkkiAtBottom = atBottom
    if (lastVisibleUuid != null) {
        this.tulkkiLastVisibleUuid = lastVisibleUuid
    }
    toggleScrollDownButton(atBottom)
    fireReadEvent()
    if (opening) {
        return
    }
    synchronized(messageList) {
        if (atStart) {
            loadMoreMessages(true, false)
        } else if (atBottom && currentConversation.isInHistoryPart()) {
            loadMoreMessages(false, true)
        }
    }
}


/**
 * Tulkki: the row's avatar was tapped. It is the deleted tree's `onContactPictureClicked`, whose
 * body is the only implementation of the gesture: the conversation's thread follows the tapped
 * row, a private message switches the composer's counterpart, and in a room the row's sender is
 * highlighted (or the composer says why they cannot be).
 */
override fun onAvatarTap(messageUuid: String) {
    if (hostActivity == null || hostActivity.xmppConnectionService == null || currentConversation == null) {
        return
    }
    val message = findMessage(messageUuid)
    if (message == null) {
        return
    }
    if (hostActivity.xmppConnectionService.getBooleanPreference(
            "show_thread_feature",
            uk.xa0.tulkki.xmpp.R.bool.show_thread_feature,
        )
    ) {
        setThread(message.getThread())
    }
    if (message.isPrivateMessage()) {
        privateMessageWith(message.getCounterpart())
        return
    }
    forkNullThread(message)
    currentConversation.setUserSelectedThread(true)

    val received = message.getStatus() <= Message.STATUS_RECEIVED
    if (!received) {
        return
    }
    val conversational = message.getConversation()
    if (conversational !is Conversation) {
        return
    }
    if (conversational.getMode() != Conversation.MODE_MULTI) {
        return
    }
    val trueCounterpart = message.getTrueCounterpart()
    val counterpart = message.getCounterpart()
    if (counterpart == null || counterpart.isBareJid()) {
        return
    }
    val mucOptions = conversational.getMucOptions()
    if (mucOptions.participating() || conversational.getNextCounterpart() != null) {
        val mucUser = mucOptions.findUserByFullJid(counterpart)
        val trueMucUser =
            mucOptions.findUserByRealJid(
                if (trueCounterpart == null) null else trueCounterpart.asBareJid(),
            )
        if (mucUser == null && trueMucUser == null) {
            Toast.makeText(
                hostActivity,
                hostActivity.getString(
                    R.string.user_has_left_conference,
                    counterpart.getResource(),
                ),
                Toast.LENGTH_SHORT,
            )
                .show()
        }
        highlightInConference(
            if (mucUser == null || mucUser.getNick() == null) {
                if (trueMucUser == null || trueMucUser.getNick() == null) {
                    counterpart.getResource()
                } else {
                    trueMucUser.getNick()
                }
            } else {
                mucUser.getNick()
            },
        )
    } else {
        Toast.makeText(hostActivity, R.string.you_are_not_participating, Toast.LENGTH_SHORT).show()
    }
}


/**
 * Tulkki: the row's avatar was long-pressed. It is the deleted tree's
 * `onContactPictureLongClicked`, the context menu that was the only implementation of the
 * avatar menu: the room occupant's moderation rows in a room, contact details and the QR code in
 * a one-to-one, and the account menu on the owner's own rows.
 *
 * The anchor is the Compose list, not the avatar: the popup used to hang off the tapped
 * `AvatarView`, and the Compose row has no view to hand across. The menu's placement is therefore
 * a recorded gap (it opens at the list's edge), which `ui-10`'s menu slice would remove by
 * owning the popup's position too.
 */
override fun onAvatarLongPress(messageUuid: String) {
    if (hostActivity == null || currentConversation == null || tulkkiView == null) {
        return
    }
    val message = findMessage(messageUuid)
    if (message == null) {
        return
    }
    val fingerprint: String?
    if (message.getEncryption() == Message.ENCRYPTION_PGP ||
        message.getEncryption() == Message.ENCRYPTION_DECRYPTED
    ) {
        fingerprint = "pgp"
    } else {
        fingerprint = message.getFingerprint()
    }
    val contact = message.getContact()
    if (message.getStatus() <= Message.STATUS_RECEIVED &&
        (contact == null || !contact.isSelf())
    ) {
        val conversational = message.getConversation()
        if (conversational is Conversation && conversational.getMode() == Conversation.MODE_MULTI) {
            // Tulkki: the room occupant's moderation rows are `muc_details_context.xml`'s, whose last
            // inflater was here; they are `:ui`'s `MucUserDropdownMenu` now, in the conversation's one
            // popup host.
            val counterpart = message.getCounterpart()
            if (counterpart == null || counterpart.isBareJid()) {
                return
            }
            val trueCounterpart = message.getTrueCounterpart()
            val occupantId = message.getOccupantId()
            val userByRealJid =
                if (trueCounterpart != null) {
                    currentConversation
                        .getMucOptions()
                        .findOrCreateUserByRealJid(trueCounterpart, counterpart, occupantId)
                } else {
                    null
                }
            val userByOccupantId =
                if (occupantId != null) {
                    currentConversation.getMucOptions().findUserByOccupantId(occupantId, counterpart)
                } else {
                    null
                }
            val user =
                if (userByRealJid != null) {
                    userByRealJid
                } else {
                    if (userByOccupantId != null) {
                        userByOccupantId
                    } else {
                        currentConversation.getMucOptions().findUserByFullJid(counterpart)
                    }
                }
            if (user == null) {
                return
            }
            showMucUserMenu(messageUuid, user, fingerprint)
        } else {
            showRowMenu(
                null,
                listOf(
                    UiMenuItem(R.id.action_contact_details, getString(R.string.action_contact_details)),
                    UiMenuItem(R.id.action_show_qr_code, getString(R.string.show_qr_code)),
                ),
            ) { menuId ->
                if (menuId == R.id.action_contact_details) {
                    hostActivity.switchToContactDetails(message.getContact(), fingerprint)
                } else if (menuId == R.id.action_show_qr_code) {
                    hostActivity.showQrCode(
                        "xmpp:" +
                            (message.getContact() ?: throw NullPointerException())
                                .getJid()
                                .asBareJid()
                                .toString(),
                    )
                }
            }
        }
    } else {
        showRowMenu(
            null,
            listOf(
                UiMenuItem(R.id.action_account_details, getString(R.string.account_details)),
                UiMenuItem(R.id.action_manage_accounts, getString(R.string.action_accounts)),
                UiMenuItem(R.id.action_show_qr_code, getString(R.string.show_qr_code)),
            ),
        ) { menuId ->
            val xmppActivity: XmppActivity = this.hostActivity
            if (xmppActivity == null) {
                Log.e(Config.LOGTAG, "Unable to perform action. no context provided")
                return@showRowMenu
            }
            if (menuId == R.id.action_show_qr_code) {
                xmppActivity.showQrCode(
                    (currentConversation.getAccount() ?: throw NullPointerException())
                        .getShareableUri(),
                )
            } else if (menuId == R.id.action_account_details) {
                xmppActivity.switchToAccount(
                    (message.getConversation() ?: throw NullPointerException()).getAccount(),
                    fingerprint,
                )
            } else if (menuId == R.id.action_manage_accounts) {
                AccountUtils.launchManageAccounts(xmppActivity)
            }
        }
    }
}


/**
 * The English row's strip was tapped: a reveal, and nothing is bought. The buy is the live facts
 * seam's (`EnglishLookup` over the bought English), and the row is not drawn at all until that
 * seam answers, so this slice cannot reach the purchase half; the reveal is recorded so the row
 * the seam later puts there is already open.
 */
override fun onEnglishTap(messageUuid: String) {
    if (tulkkiMessagesSession != null) {
        tulkkiMessagesSession.revealEnglish(messageUuid)
    }
}


/**
 * Item 17's decision five: the concealed strip the failure gate offered was tapped, so this row's
 * original is shown. It is the strip's own tap, never the bubble's, and the reveal lives in the
 * session with the English row's - two exceptions, two sets, neither reading the file again.
 */
override fun onRevealOriginal(messageUuid: String) {
    if (tulkkiMessagesSession != null) {
        tulkkiMessagesSession.revealOriginal(messageUuid)
    }
}


/**
 * A body was long-pressed: §4.5's selection, which is a set of ids rather than a mode. The menu
 * the Java long press opened is `ui-10` and is not built, so this toggles the outline and
 * nothing else - a second long press clears the row.
 */
override fun onLongPress(messageUuid: String) {
    if (tulkkiMessagesSession != null) {
        tulkkiMessagesSession.toggled(messageUuid)
    }
}


/** A body was swiped right far enough: §4.4's reply, opened on that row. */
override fun onReply(messageUuid: String) {
    val message = findMessage(messageUuid)
    if (message != null) {
        quoteMessage(message)
    }
}


/**
 * The reply preview's dismiss: the Java `context_preview_cancel` listener, moved onto the
 * Compose preview that is now the one drawn. It drops the thread selection first, exactly as the
 * Java button did, so clearing a reply does not leave a half-chosen thread behind.
 */
override fun onReplyCancel() {
    setThread(null)
    currentConversation.setUserSelectedThread(false)
    setupReply(null)
}


/**
 * The Compose composer's draft. It is remembered and drawn back: the Compose field is a controlled
 * field, so its own state is what the session holds, and handing the same words back is what lets
 * the owner type at all. It is also the draft the send path reads - [writeComposerDraft] is the one
 * write and this is the one place the owner's own typing reaches it - so there is no second draft to
 * disagree with.
 */
override fun onDraftChanged(draft: TextFieldValue) {
    writeComposerDraft(draft)
}


/**
 * The Compose composer's send affordance. Nothing here decides anything: the one send path runs -
 * the gate, the hold, the prompt and the translation are [sendMessage()]'s. The Compose side has no
 * send of its own, so the outgoing rule cannot be gone around.
 *
 * The draft is not re-read afterwards, because the send path writes every change back into the same
 * value: a sent message clears it, the gate's prompt clears it, a correction appends the corrected
 * draft, and the Compose field draws that value rather than the words that were handed over.
 */
override fun onSend() {
    sendMessage()
}


/**
 * The Compose composer's attach affordance: the owner asked to stage a file, an image, a recording
 * or a place. The menu is Java's and stays so - whether a conversation can stage a file depends on
 * its encryption, its permissions and what the account supports - so this opens the very menu the
 * Java send button's long press opens, and every pick is answered by the same
 * [onOptionsItemSelected(MenuItem)] rows it always was. Nothing is re-implemented here.
 */
override fun onAttach() {
    popAttachMenu()
}


/**
 * The Compose encryption selector's pick: the deleted `handleEncryptionSelection`'s write,
 * minus the Java menu's own check mark - the selector draws that from the state re-read below.
 * The service is told only when the attribute actually moved, and the hint and input method are
 * re-read either way, as the Java did.
 */
override fun onEncryptionSelect(choice: EncryptionChoice) {
    if (currentConversation == null || hostActivity == null || hostActivity.xmppConnectionService == null) {
        return
    }
    if (choice == EncryptionChoice.OMEMO) {
        Log.d(
            Config.LOGTAG,
            AxolotlService.getLogprefix(currentConversation.getAccount() ?: throw NullPointerException()) +
                "Enabled axolotl for Contact " +
                currentConversation.getContact().getJid(),
        )
    }
    if (currentConversation.setNextEncryption(choice.nextEncryption)) {
        hostActivity.xmppConnectionService.updateConversation(currentConversation)
    }
    updateChatMsgHint()
    toggleInputMethod()
    hostActivity.refreshUi()
}


/**
 * The selector's blocked pick - only OpenPGP can be that: the Java's two flows, entered instead
 * of a write. The selector's own row already drew the explanation.
 */
override fun onEncryptionBlocked(choice: EncryptionChoice, block: EncryptionBlock) {
    if (currentConversation == null || hostActivity == null) {
        return
    }
    if (block == EncryptionBlock.OPENPGP_PROVIDER_MISSING) {
        hostActivity.showInstallPgpDialog()
    } else if (block == EncryptionBlock.OPENPGP_KEY_UNPUBLISHED) {
        hostActivity.announcePgp(
            currentConversation.getAccount() ?: throw NullPointerException(),
            currentConversation,
            null,
            hostActivity.onOpenPGPKeyPublished,
        )
    }
}


/**
 * Tulkki: pops the attach menu anchored at the composer.
 *
 * **It used to anchor at the `GONE` Java send button** - a view that draws nothing - so the
 * popup landed at that view's origin rather than where the owner tapped. The Compose attach
 * affordance lives in the composer, and the composer is the enclosing surface of every
 * affordance that can open this menu, so the composer is the anchor: it is drawn, it is where the
 * tap happened, and it keeps the menu over the input row on both the API 24 path and the
 * fallback. Both callers - the Compose `onAttach` verb and `sendMessage`'s NOTHING branch - go
 * through here so there is one anchor and not two.
 */
    /**
     * Tulkki: the attach menu's rows, as the Compose row menu.
     *
     * It is the deleted `fragment_conversation.xml`'s attach submenu, whose rows
     * `onCreateContextMenu` copied into the composer's context menu - the only half of that menu the
     * owner could reach - and it is anchored where it was: the composer the attach affordance sits in.
     * The rows, their order and the three the deleted
     * `ConversationMenuConfigurator.configureAttachmentMenu` hid are that menu's own; the submenu
     * parent's own visibility was never consulted by the context path, so the rows are drawn whenever
     * this is asked for.
     */
    private fun popAttachMenu() {
        if (hostActivity == null || currentConversation == null || tulkkiView == null || !tulkkiPageHosted) {
            return
        }
        val encryptionNone = currentConversation.getNextEncryption() == Message.ENCRYPTION_NONE
        val items = ArrayList<UiMenuItem>()
        items.add(UiMenuItem(R.id.attach_choose_file, getString(R.string.choose_file)))
        items.add(UiMenuItem(R.id.attach_choose_picture, getString(R.string.attach_choose_picture)))
        items.add(UiMenuItem(R.id.attach_record_video, getString(R.string.attach_record_video)))
        items.add(UiMenuItem(R.id.attach_take_picture, getString(R.string.attach_take_picture)))
        items.add(UiMenuItem(R.id.attach_record_voice, getString(R.string.attach_record_voice)))
        items.add(UiMenuItem(R.id.attach_location, getString(R.string.send_location)))
        if (encryptionNone) {
            items.add(UiMenuItem(R.id.attach_live_location, getString(R.string.share_live_location)))
            items.add(UiMenuItem(R.id.attach_subject, getString(R.string.add_subject)))
        }
        if (!(Build.VERSION.SDK_INT < Build.VERSION_CODES.N || TextUtils.isEmpty(tulkkiComposerText()))) {
            items.add(UiMenuItem(R.id.attach_schedule, getString(R.string.schedule_message)))
        }
        showRowMenuAt(tulkkiComposerAnchor(), items) { id -> handleAttachmentSelection(id) }
    }

    /**
     * The Compose composer's request-to-speak affordance: the whole of the deleted Java button's tap
     * - ask the room for a voice and say so. It is drawn exactly when `canWrite()` is false, so the
     * request is offered when it is possible and never otherwise.
     */
    override fun onRequestVoice() {
        if (hostActivity == null ||
            hostActivity.xmppConnectionService == null ||
            currentConversation == null
        ) {
            return
        }
        hostActivity.xmppConnectionService.requestVoice(
            currentConversation.getAccount() ?: throw NullPointerException(),
            currentConversation.getJid() ?: throw NullPointerException(),
        )
        Toast.makeText(hostActivity, R.string.request_to_speak_send, Toast.LENGTH_SHORT).show()
    }




    /** The Compose thread marker's tap: the deleted `threadIdenticonLayout` click, verbatim. */
    override fun onThreadTap() {
        if (hostActivity == null || currentConversation == null) {
            return
        }
        val wasLocked = currentConversation.getLockThread()
        currentConversation.setLockThread(false)
        backPressedLeaveSingleThread.isEnabled = false
        if (wasLocked) {
            setThread(null)
            currentConversation.setUserSelectedThread(false)
            refresh()
            updateThreadFromLastMessage()
        } else {
            newThread()
            currentConversation.setUserSelectedThread(true)
            newThreadTutorialToast(hostActivity.getString(R.string.switch_to_new_thread))
        }
    }




    /** The Compose thread marker's long press: the deleted `threadIdenticonLayout` long-click. */
    override fun onThreadLongPress() {
        if (hostActivity == null || currentConversation == null) {
            return
        }
        val wasLocked = currentConversation.getLockThread()
        currentConversation.setLockThread(false)
        backPressedLeaveSingleThread.isEnabled = false
        setThread(null)
        currentConversation.setUserSelectedThread(true)
        if (wasLocked) refresh()
        newThreadTutorialToast(hostActivity.getString(R.string.cleared_thread))
    }




    /** Item 16's switch, from the Compose surface: the stored answer, or cleared. */
    override fun onDoubtHoldChanged(hold: Boolean) {
        storeDoubtHold(hold)
    }




    /**
     * The Compose formatting bar's close control, after its own confirmation: the Java
     * `closeFormatting`'s write, which the bar's dialog has already asked about. The two halves
     * of the old method are split deliberately - the confirmation is the drawn surface's, and the
     * preference write is the host's, so there is one dialog and not two.
     */
    override fun onFormattingClose() {
        if (hostActivity == null) {
            return
        }
        val preferences = PreferenceManager.getDefaultSharedPreferences(hostActivity)
        preferences.edit().putBoolean("showtextformatting", false).apply()
        hideTextFormat()
        updateSendButton()
    }




    /**
     * Tulkki: a reaction chip under a row was tapped - the owner's own answer for this row and this
     * emoji.
     *
     * The set is rebuilt from the message's aggregated reactions rather than from the chip: the
     * chip is the projector's view of the document and this is the document. A reaction the owner
     * already carries is dropped and one they do not is added, which is
     * `ReactionPublisher.sendReactions`'s contract for a removal as well as an addition - an
     * empty collection means removal, never a no-op.
     *
     * The unverified-key guard is the deleted quick-reaction verb's own
     * (`setupDialogReactionRow`'s `sendMessageReaction`): on a chat whose keys are not
     * trusted this raises the trust prompt instead of sending.
     */
    override fun onReaction(messageUuid: String, emoji: String) {
        if (hostActivity == null ||
            hostActivity.xmppConnectionService == null ||
            emoji == null
        ) {
            return
        }
        val message = findMessage(messageUuid)
        if (message == null) {
            return
        }
        if (requireTrustKeys()) {
            return
        }
        val aggregated = message.getAggregatedReactions()
        val builder = ImmutableSet.Builder<String>()
        if (aggregated.ourReactions.contains(emoji)) {
            for (reaction in aggregated.ourReactions) {
                if (!reaction.equals(emoji)) {
                    builder.add(reaction)
                }
            }
        } else {
            builder.addAll(aggregated.ourReactions)
            builder.add(emoji)
        }
        hostActivity.xmppConnectionService.sendReactions(message, builder.build())
    }




    /**
     * Tulkki: a reaction chip was long-pressed - the full picker on this row, which is the detail
     * screen the deleted pills opened and the only reaction surface left.
     *
     * The row travels as its uuid like every other verb here, and the picker resolves it again
     * when it is answered; nothing is handed an entity across the boundary.
     */
    override fun onReactionPicker(messageUuid: String) {
        if (hostActivity == null) {
            return
        }
        val message = findMessage(messageUuid)
        if (message == null) {
            return
        }
        val conversation = message.getConversation()
        if (conversation !is Conversation) {
            return
        }
        val intent = Intent(hostActivity, AddReactionActivity::class.java)
        intent.putExtra("conversation", conversation.getUuid())
        intent.putExtra("message", message.getUuid())
        hostActivity.startActivity(intent)
    }




    /** Tulkki's settings screen, where the key is set and the language pair is chosen. */
    private fun openTulkkiSettings() {
        if (hostActivity == null) {
            return
        }
        val intent = Intent(hostActivity, SettingsActivity::class.java)
        intent.putExtra(
            SettingsActivity.EXTRA_SETTINGS_FRAGMENT,
            TulkkiSettingsFragment::class.java.name,
        )
        startActivity(intent)
    }




    /**
     * Item 17's decision four: the failures screen, filtered to this conversation.
     *
     * It is the settings row's own [TranslationFailuresFragment] with the conversation's uuid
     * as its argument, hosted by [SettingsActivity]'s container - not a parallel list. The
     * conversation's own activity has no fragment container to replace, so the screen is an activity
     * of its own; the toolbar's arrow finishes it and returns here.
     */
    private fun openTranslationFailures() {
        if (hostActivity == null || currentConversation == null) {
            return
        }
        val intent = Intent(hostActivity, SettingsActivity::class.java)
        intent.putExtra(SettingsActivity.EXTRA_FAILURES_CONVERSATION, currentConversation.getUuid())
        startActivity(intent)
    }




    /** Whether the Compose tree is the dark one, read off the Activity's own configuration. */
    private fun darkTheme(): Boolean {
        val mode =
            getResources().getConfiguration().uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }




    /**
     * The Compose chip's tap: the language picker, which is the whole of the Java chip's tap. The
     * picker's row writes the conversation's override itself (`applyLanguage`), so nothing is
     * decided twice here and nothing is re-implemented: this is the gesture's name and no more.
     *
     * The Java chip's long press - Tulkki's settings - is not carried: it is one gesture of a
     * `GONE` view, and `docs/MIGRATION.md` §2.12 keeps that path reachable from the
     * settings entry only.
     */
    override fun onLanguageChipTap() {
        showLanguagePicker()
    }




    /**
     * The Compose emoji affordance's tap: the panel `EmojiPanelHost` already installed, opened
     * or put away.
     *
     * It replaces the Java row's two triggers. `emojiButton` opened the panel and
     * `keyboardButton` closed it and brought the IME back, and both sat in the Java `input` row the
     * Compose composer hides - so the panel was unreachable from
     * any view the owner could see. One verb now, toggled against the controller's own state, and the
     * two buttons and their listeners are gone with the row that held them.
     *
     * Opening hides the soft keyboard, which is the one half of the old `emojiButtonListener`
     * worth keeping: the panel takes a fixed share of the screen, and the two cannot want the same
     * pixels. Closing does not force the IME back on - the Compose field is the one the owner types
     * in, so the keyboard returns with the next tap on it.
     */
    override fun onEmojiTap() {
        if (emojiPanelController == null) {
            return
        }
        if (emojiPanelController.isOpen()) {
            closeEmojiPanel()
            return
        }
        emojiPanelController.open()
        hideSoftKeyboard(hostActivity)
    }




    /**
     * The picker: the languages the detector actually has profiles for, plus an explicit way back to
     * automatic detection. Clearing the override has to be a choice rather than the absence of one,
     * which is why "Detect automatically" is the first row.
     *
     * The header names both ends of the pair, because a language on its own says nothing about
     * what it is relative to: the app language is the fixed end (a setting, shown with its own tag
     * and deliberately not a row), and both the "Detect automatically" row and the language rows
     * choose the other end, the conversation's language. Neither name is hardcoded - both come from
     * [ConversationLanguage.languageName], so a non-Finnish app language and an override
     * rather than a detection both read correctly.
     *
     * It is a Compose dialog now (`conversation/ConversationDialogs.kt`), launched through
     * [showTulkkiDialog]. The old builder's custom-title workaround is gone with it: androidx kept
     * the `ListView` out of the content panel whenever a message was set, which is why the
     * description had to ride in the title panel; Compose draws the title, the description and the
     * list in one dialog without that constraint. The list, its preselection, the two labels and
     * [applyLanguage] are unchanged.
     */
    private fun showLanguagePicker() {
        if (hostActivity == null || currentConversation == null) {
            return
        }
        val current = tulkkiLanguage()
        val codes = TranslationLanguages.codes()
        val items = ArrayList<String>(codes.size + 1)
        items.add(automaticLabel(current))
        for (i in 0 until codes.size) {
            items.add(ConversationLanguage.languageName(codes.get(i)))
        }
        var preselected = 0
        val selected = if (current.isSet) current.code() else null
        if (selected != null) {
            val index = codes.indexOf(ComposerGate.normalize(selected))
            // A code set outside this list is still shown as selected rather than silently moved.
            preselected = if (index < 0) 0 else index + 1
        }
        val appLanguage =
            ConversationLanguage.languageName(TranslationSettings.get(hostActivity).appLanguage())
        val theyWrite = theyWriteLabel(current)
        hostActivity.showTulkkiDialog { dismiss ->
            LanguagePickerDialog(
                appLanguage = appLanguage,
                theyWrite = theyWrite,
                items = items,
                selectedIndex = preselected,
                onDismiss = dismiss,
                onChoose = { which ->
                    applyLanguage(if (which == 0) null else codes.get(which - 1))
                    dismiss()
                },
            )
        }
    }




    /**
     * How the conversation's end of the pair is known: the owner's answer, the app's detection, or
     * nothing yet. The language name is the same namer the chip and the settings screen use.
     */
    private fun theyWriteLabel(current: ConversationLanguage.Resolved): String {
        if (current.isUnknown) {
            return getString(R.string.tulkki_pair_they_unknown)
        }
        val name = ConversationLanguage.languageName(current.code())
        return getString(
            if (current.isSet) R.string.tulkki_pair_they_set else R.string.tulkki_pair_they_detected,
            name,
        )
    }




    /** "Detect automatically", saying what detection currently believes when it knows anything. */
    private fun automaticLabel(current: ConversationLanguage.Resolved): String {
        val detected = current.detected()
        if (ComposerGate.isUnknownLanguage(detected)) {
            return getString(R.string.tulkki_language_auto)
        }
        return getString(
            R.string.tulkki_language_auto_detected,
            ConversationLanguage.languageName(detected),
        )
    }




    /**
     * Stores the owner's answer, or `null` to go back to detection. Written through the
     * conversation entity and persisted the way every other conversation field is, so it survives the
     * process being killed. What happens to a held message afterwards is the resume path's business,
     * exactly as the existing "set it and the message will go out" promises - the picker does not
     * retry anything itself.
     */
    private fun applyLanguage(code: String?) {
        if (currentConversation == null) {
            return
        }
        val normalized = ComposerGate.normalize(code)
        currentConversation.setLanguageOverride(if (normalized.isEmpty()) null else normalized)
        if (hostActivity != null && hostActivity.xmppConnectionService != null) {
            hostActivity.xmppConnectionService.updateConversation(currentConversation)
        }
        // On the main thread, after the dialog: the composer stops claiming the message is stuck, and
        // the menu entry follows.
        if (tulkkiView != null) {
            tulkkiView.post { refreshTulkkiLanguageBar() }
        }
    }




    /** The real thing: the override if there is one, else what was detected, else nothing. */
    private fun tulkkiLanguage(): ConversationLanguage.Resolved {
        if (currentConversation == null) {
            return ConversationLanguage.resolve(null, null)
        }
        return ConversationLanguage.resolve(
            currentConversation.getDetectedLanguage(),
            currentConversation.getLanguageOverride(),
        )
    }




    /**
     * Tulkki: whether the interpreter is on, asked from the fragment rather than threaded in: every
     * surface that must not exist off - the chip, the language bar's line - asks this one question,
     * so they cannot disagree about the mode. And it is [LanguageChip.isDrawn] rather than a
     * second copy of the same predicate, so "is there a chip" and "is the interpreter on" cannot
     * drift either.
     */
    private fun interpreting(): Boolean =
        hostActivity != null &&
            LanguageChip.isDrawn(TranslationSettings.get(hostActivity).interpreter())




    private fun apiKeyConfigured(): Boolean =
        hostActivity != null && TranslationSettings.get(hostActivity).hasApiKey()




    private fun activityInvalidateOptionsMenu() {
        if (hostActivity != null) {
            hostActivity.invalidateOptionsMenu()
        }
    }




    /**
     * Tulkki: marks the conversation read up to the bottom-most row the owner can see.
     *
     * The row is the Compose list's own reading, kept in [tulkkiLastVisibleUuid] by
     * [onViewport]; before the first viewport fact (resume, refresh, a thread switch) the
     * model's newest message answers, which is what the deleted `ListView` arithmetic meant to say
     * and could not: that list is `GONE`, so `getLastVisiblePosition()` was always `-1` and the read
     * receipt had silently stopped firing.
     */
    private fun fireReadEvent() {
        if (hostActivity != null && this.currentConversation != null) {
            val uuid =
                if (tulkkiLastVisibleUuid != null) tulkkiLastVisibleUuid else getLastVisibleMessageUuid()
            if (uuid != null) {
                hostActivity.onConversationRead(this.currentConversation, uuid)
            }
        }
    }




    private fun newThread() {
        val thread = Element("thread", "jabber:client")
        thread.setContent(UUID.randomUUID().toString())
        if (hostActivity.xmppConnectionService != null &&
            hostActivity.xmppConnectionService.getBooleanPreference(
                "show_thread_feature",
                uk.xa0.tulkki.xmpp.R.bool.show_thread_feature,
            )
        ) {
            setThread(thread)
        }
    }




    private fun updateThreadFromLastMessage() {
        if (this.currentConversation != null &&
            !this.currentConversation.getUserSelectedThread() &&
            TextUtils.isEmpty(tulkkiComposerText())
        ) {
            val message = getLastVisibleMessage()
            if (message == null) {
                if (hostActivity != null && hostActivity.xmppConnectionService != null &&
                    hostActivity.xmppConnectionService.getBooleanPreference(
                        "show_thread_feature",
                        uk.xa0.tulkki.xmpp.R.bool.show_thread_feature,
                    )
                ) {
                    newThread()
                }
            } else {
                if (currentConversation.getMode() == Conversation.MODE_MULTI) {
                    if (hostActivity == null || hostActivity.xmppConnectionService == null) return
                    if (message.getStatus() < Message.STATUS_SEND) {
                        if (hostActivity != null && hostActivity.xmppConnectionService != null &&
                            !hostActivity.xmppConnectionService.getBooleanPreference(
                                "follow_thread_in_channel",
                                R.bool.follow_thread_in_channel,
                            )
                        ) {
                            return
                        }
                    }
                }
                if (hostActivity != null && hostActivity.xmppConnectionService != null &&
                    hostActivity.xmppConnectionService.getBooleanPreference(
                        "show_thread_feature",
                        uk.xa0.tulkki.xmpp.R.bool.show_thread_feature,
                    )
                ) {
                    setThread(message.getThread())
                }
            }
        }
    }




    private fun getLastVisibleMessageUuid(): String? {
        val message = getLastVisibleMessage()
        return if (message == null) null else message.getUuid()
    }




    /**
     * Tulkki: the bottom-most real message, read from the model rather than from a `ListView`.
     *
     * The deleted version asked the Java list for its last visible position and walked back from
     * it; that list is `GONE` and draws nothing, so it always answered `null` - which is how the
     * read receipt died when the Compose list became the visible one. The row the owner actually
     * sees arrives through [onViewport]; this is the fallback for the paths that run before
     * the first viewport fact, and it is the newest non-status message the conversation holds.
     */
    private fun getLastVisibleMessage(): Message? {
        synchronized(this.messageList) {
            for (i in this.messageList.size - 1 downTo 0) {
                val message = this.messageList.get(i)
                if (message.getType() != Message.TYPE_STATUS) {
                    return message
                }
            }
        }
        return null
    }




    // Tulkki: the message menu's nine action bodies are gone with the Java menu. Nothing in the
    // tree could reach them (the menu dialog that dispatched them was deleted with the adapter's
    // long press, and no other caller existed), and re-homing them is the Compose menu `ui-10`
    // is to build, where each verb gets its own affordance. The two the design supersedes rather
    // than moves are `resendMessage` (the held send's retry is the bubble tap on
    // `OutgoingTranslation.sendHeldNow`; the swap's own note forbids exposing "send again" as a
    // retry for a held row) and `showErrorMessage` (a failed translation says why in the
    // composer's bar and on the failures screen; no dialog is drawn over a row). The rest -
    // `pinMessage`, `deleteFile`, `saveToDownloads`, `openWith`, `reportMessage`,
    // `retryDecryption`, `cancelTransmission` - are ui-10's to re-own.

    fun privateMessageWith(counterpart: Jid?) {
        if (currentConversation.setOutgoingChatState(Config.DEFAULT_CHAT_STATE)) {
            hostActivity.xmppConnectionService.sendChatState(currentConversation)
        }
        writeComposerDraft("", 0)
        this.currentConversation.setNextCounterpart(counterpart)
        updateChatMsgHint()
        updateSendButton()
        updateEditablity()
    }




    private fun correctMessage(message: Message) {
        if (hostActivity.xmppConnectionService != null &&
            hostActivity.xmppConnectionService.getBooleanPreference(
                "show_thread_feature",
                uk.xa0.tulkki.xmpp.R.bool.show_thread_feature,
            )
        ) {
            setThread(message.getThread())
        }
        currentConversation.setUserSelectedThread(true)
        this.currentConversation.setCorrectingMessage(message)
        // The draft the correction replaces is set aside, and the editor is filled with the owner's
        // own words - never the wire text. After a translation those are two different columns -
        // translated_body holds what they wrote and body holds what went out in the conversation's
        // language - and the send path translates *from* the app language, so prefilling the wire text
        // would push the peer's language through the app language and translate a translation. A row
        // with nothing translated falls back to its body, which is the same rule the send path's own
        // draftOf applies.
        this.currentConversation.setDraftMessage(tulkkiComposerText())
        val ownText =
            HeldSend.draftOf(
                message.getBody(true),
                message.getTranslationState(),
                message.getTranslatedBody(),
            ).orEmpty()
        // The deleted `setText("")` + `append(ownText)` left the field holding the owner's words with
        // the caret after them.
        writeComposerDraft(ownText, ownText.length)
        val subject = message.getSubject()
        if (subject != null && subject.length > 0) {
            this.tulkkiSubject = subject
        }
        setupReply(message.getInReplyTo())
        correctionBarController.show(ownText)
    }




    /**
     * Tulkki: the `@nick` insert, on the one draft.
     *
     * <p>The deleted body held the `EditText`'s `Editable` and let `insert` move the caret, which is
     * `Spannable`'s own pointer arithmetic. The one draft is a value, so the same `Editable` is built
     * from its text with the same selection set on it, the same inserts run, and the text and caret it
     * leaves are written back - which is what the deleted field resync read off the view.
     */
    private fun highlightInConference(nick: String?) {
        val current = tulkkiComposerDraft()
        val editable = SpannableStringBuilder(current.text)
        Selection.setSelection(
            editable,
            ConversationHost.selectionStart(current),
            ConversationHost.selectionEnd(current),
        )
        val oldString = editable.toString().trim()
        val pos = Selection.getSelectionStart(editable)
        if (oldString.isEmpty() || pos == 0) {
            editable.insert(0, nick + ": ")
        } else {
            val before = editable[pos - 1]
            val after = if (editable.length > pos) editable[pos] else '\u0000'
            if (before == '\n') {
                editable.insert(pos, nick + ": ")
            } else {
                if (pos > 2 && editable.subSequence(pos - 2, pos).toString().equals(": ")) {
                    if (NickValidityChecker.check(
                            currentConversation,
                            editable.subSequence(0, pos - 2).toString().split(", "),
                        )
                    ) {
                        editable.insert(pos - 2, ", " + nick)
                        writeComposerDraft(
                            editable.toString(),
                            Selection.getSelectionStart(editable),
                        )
                        return
                    }
                }
                editable.insert(
                    pos,
                    (if (Character.isWhitespace(before)) "" else " ") +
                        nick +
                        (if (Character.isWhitespace(after)) "" else " "),
                )
                if (Character.isWhitespace(after)) {
                    Selection.setSelection(
                        editable,
                        Selection.getSelectionStart(editable) + 1,
                    )
                }
            }
        }
        writeComposerDraft(editable.toString(), Selection.getSelectionStart(editable))
    }




    override fun startActivityForResult(intent: Intent, requestCode: Int) {
        val activity = getActivity()
        if (activity is ConversationListActivity) {
            activity.clearPendingViewIntent()
        }
        super.startActivityForResult(intent, requestCode)
    }




    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (currentConversation != null) {
            outState.putString(STATE_CONVERSATION_UUID, currentConversation.getUuid())
            outState.putString(STATE_LAST_MESSAGE_UUID, lastMessageUuid)
            val uri = pendingTakePhotoUri.peek()
            if (uri != null) {
                outState.putString(STATE_PHOTO_URI, uri.toString())
            }
            val attachments: ArrayList<Attachment> =
                if (stagedAttachments == null) {
                    ArrayList()
                } else {
                    stagedAttachments.attachments()
                }
            if (attachments.size > 0) {
                outState.putParcelableArrayList(STATE_MEDIA_PREVIEWS, attachments)
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        if (savedInstanceState == null) {
            return
        }
        val uuid = savedInstanceState.getString(STATE_CONVERSATION_UUID)
        val attachments =
            savedInstanceState.getParcelableArrayList<Attachment>(STATE_MEDIA_PREVIEWS)
        pendingLastMessageUuid.push(savedInstanceState.getString(STATE_LAST_MESSAGE_UUID, null))
        if (uuid != null) {
            QuickLoader.set(uuid)
            pendingConversationUuid.push(uuid)
            if (attachments != null && attachments.size > 0) {
                pendingMediaPreviews.push(attachments)
            }
            val takePhotoUri = savedInstanceState.getString(STATE_PHOTO_URI)
            if (takePhotoUri != null) {
                pendingTakePhotoUri.push(Uri.parse(takePhotoUri))
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (reInitRequiredOnStart && currentConversation != null) {
            reInitRequiredOnStart = false
            val extras = pendingExtras.pop()
            reInit(
                currentConversation,
                extras != null,
                extras != null && extras.getString(ConversationListActivity.EXTRA_MESSAGE_UUID) != null,
            )
            if (extras != null) {
                processExtras(extras)
            }
        } else if (currentConversation == null &&
            hostActivity != null &&
            hostActivity.xmppConnectionService != null
        ) {
            val uuid = pendingConversationUuid.pop()
            Log.d(
                Config.LOGTAG,
                "ConversationFragment.onStart() - activity was bound but no conversation" +
                    " loaded. uuid=" +
                    uuid,
            )
            if (uuid != null) {
                findAndReInitByUuidOrArchive(uuid)
            }
        }
        updateChatBG()
    }

    override fun onStop() {
        super.onStop()
        val activity = getActivity()
        // Tulkki: stop listening for conversation updates with the view that would answer them; the
        // chip is redrawn on the next resume, when there is one.
        if (hostActivity != null && hostActivity.xmppConnectionService != null) {
            hostActivity.xmppConnectionService.removeOnConversationListChangedListener(this)
        }
        ConversationMedia.unregisterListenerInAudioPlayer()
        if (activity == null || !activity.isChangingConfigurations()) {
            hideSoftKeyboard(activity ?: throw NullPointerException())
            ConversationMedia.stopAudioPlayer()
        }
        if (currentConversation != null) {
            val msg = tulkkiComposerText()
            storeNextMessage(msg)
            updateChatState(currentConversation, msg)
            hostActivity.xmppConnectionService.getNotificationService().setOpenConversation(null)
        }
        reInitRequiredOnStart = true
    }

    private fun updateChatState(conversation: Conversation, msg: String) {
        val state = if (msg.length == 0) Config.DEFAULT_CHAT_STATE else ChatState.PAUSED
        val status = (conversation.getAccount() ?: throw NullPointerException()).getStatus()
        if (status == Account.State.ONLINE && conversation.setOutgoingChatState(state)) {
            hostActivity.xmppConnectionService.sendChatState(conversation)
        }
    }

    private fun saveMessageDraftStopAudioPlayer() {
        val previousConversation = currentConversation
        if (hostActivity == null || tulkkiView == null || previousConversation == null) {
            return
        }
        Log.d(Config.LOGTAG, "ConversationFragment.saveMessageDraftStopAudioPlayer()")
        val msg = tulkkiComposerText()
        storeNextMessage(msg)
        updateChatState(currentConversation, msg)
        ConversationMedia.stopAudioPlayer()
        if (stagedAttachments != null) {
            stagedAttachments.clear()
        }
        toggleInputMethod()
    }

    fun reInit(conversation: Conversation, extras: Bundle?) {
        QuickLoader.set(conversation.getUuid())
        val changedConversation = currentConversation !== conversation
        if (changedConversation) {
            saveMessageDraftStopAudioPlayer()
        }
        clearPending()
        if (reInit(
                conversation,
                extras != null,
                extras != null && extras.getString(ConversationListActivity.EXTRA_MESSAGE_UUID) != null,
            )
        ) {
            if (extras != null) {
                processExtras(extras)
            }
            reInitRequiredOnStart = false
        } else {
            reInitRequiredOnStart = true
            pendingExtras.push(extras)
        }
        // Tulkki: the deleted `resetUnreadMessagesCount` was this field's own clear plus the badge
        // write, and the badge is the Compose session's now (`unread`, with `hideUnreadMessagesCount`'s
        // null guard), so the call site is its two statements rather than a restored function.
        lastMessageUuid = unsafeNull()
        hideUnreadMessagesCount()
    }

    private fun reInit(conversation: Conversation) {
        reInit(conversation, false, false)
    }

    private fun reInit(
        conversation: Conversation?,
        hasExtras: Boolean,
        hasMessageUUID: Boolean,
    ): Boolean {
        if (conversation == null) {
            return false
        }
        val originalConversation = currentConversation
        currentConversation = conversation
        // once we set the conversation all is good and it will automatically do the right thing in
        // onStart()
        if (hostActivity == null || tulkkiView == null) {
            return false
        }

        if (!hostActivity.xmppConnectionService.isConversationStillOpen(currentConversation)) {
            hostActivity.onConversationArchived(currentConversation)
            return false
        }
        updateinputfield()
        // Tulkki: the composer bar belongs to the conversation it was written in, and the language
        // line has to follow the conversation the same way. The refresh is posted so it happens once
        // this re-initialisation, which rebuilds the view, has finished.
        hideTulkkiBar()
        if (tulkkiView != null) {
            tulkkiView.post { refreshTulkkiLanguageBar() }
        }
        setThread(conversation.getThread())
        setupReply(conversation.getReplyTo())

        Log.d(Config.LOGTAG, "reInit(hasExtras=" + hasExtras + ")")

        if (currentConversation.isRead(if (hostActivity == null) null else hostActivity.xmppConnectionService) &&
            hasExtras
        ) {
            Log.d(Config.LOGTAG, "trimming conversation")
            currentConversation.trim()
        }

        setupIme()

        // Tulkki: the Compose list's own anchor is the pending-scroll state now - it is `rememberSaveable`
        // and restores the reader's place across a view recreation - so the Java `ScrollState` pended in
        // the bundle is gone and this is just "was the list at its latest message".
        val scrolledToBottomAndNoPending = scrolledToBottom()

        // Tulkki: the Java field's own keyboard listener was set (and cleared) around this fill, and
        // the field is deleted: the draft verbs it forwarded are the Compose field's own now, and its
        // typing machine is `triggerKeyboardEvents`. The detach is kept as the flag, because it is what
        // kept this fill silent - a thread switch must not tell the peer the owner is composing, and it
        // must not store the draft it is filling from.
        val participating =
            conversation.getMode() == Conversational.MODE_SINGLE ||
                conversation.getMucOptions().participating()
        tulkkiTypingDetached = true
        if (participating) {
            val next = currentConversation.getNextMessage().orEmpty()
            writeComposerDraft(next, next.length)
        } else {
            writeComposerDraft(MessageUtils.EMPTY_STRING, 0)
        }
        tulkkiTypingDetached = false
        messageListAdapter.updatePreferences()
        refresh(false)
        // ui-9's swap: the Compose list is bound to the conversation `reInit` just set. It is a
        // re-show on a thread switch, which cancels the previous watcher and starts the new one.
        refreshTulkkiMessages()
        hostActivity.invalidateOptionsMenu()
        currentConversation.messagesLoaded.set(true)
        Log.d(Config.LOGTAG, "scrolledToBottomAndNoPending=" + scrolledToBottomAndNoPending)

        if (!hasMessageUUID && (hasExtras || scrolledToBottomAndNoPending)) {
            lastMessageUuid = unsafeNull()
            hideUnreadMessagesCount()
            synchronized(messageList) {
                Log.d(Config.LOGTAG, "jump to first unread message")
                val first = conversation.getFirstUnreadMessage()
                val bottom = Math.max(0, messageList.size - 1)
                val pos: Int
                val jumpToBottom: Boolean
                if (first == null) {
                    pos = bottom
                    jumpToBottom = true
                } else {
                    val i = getIndexOf(first.getUuid(), messageList)
                    pos = if (i < 0) bottom else i
                    jumpToBottom = false
                }
                setSelection(pos, jumpToBottom)
            }
        }

        tulkkiView.post { fireReadEvent() }
        // TODO if we only do this when this fragment is running on main it won't *bing* in tablet
        // layout which might be unnecessary since we can *see* it
        hostActivity.xmppConnectionService
            .getNotificationService()
            .setOpenConversation(currentConversation)

        if (tulkkiCommandsInitialised && conversation !== originalConversation) {
            tulkkiCommandsSession.applyCommands(emptyList())
            conversation.setupViewPager(
                conversationViewPager,
                tabLayout,
                hostActivity.xmppConnectionService.isOnboarding(),
                originalConversation,
                R.id.commands_view,
            )
            refreshCommands(false)
        }
        if (!tulkkiCommandsInitialised && conversation != null) {
            conversation.setupViewPager(
                conversationViewPager,
                tabLayout,
                hostActivity.xmppConnectionService.isOnboarding(),
                null,
                R.id.commands_view,
            )
            tulkkiCommandsInitialised = true
            refreshCommands(false)
        }
        tulkkiCommandsSession.showNote(hostActivity.xmppConnectionService.isOnboarding())
        return true
    }

    override fun refreshForNewCaps(newCapsJids: MutableSet<Jid>) {
        if (newCapsJids.isEmpty() ||
            (currentConversation != null &&
                newCapsJids.contains(
                    (currentConversation.getJid() ?: throw NullPointerException()).asBareJid(),
                ))
        ) {
            refreshCommands(true)
        }
    }

    /**
     * Tulkki: the command page's rows, from the facts the deleted adapter was filled with.
     *
     * It is the old body's own four sources - the room's configuration row when the owner is an
     * owner, the occupant's own command service, the room's, the domain's - and the same two answers
     * per fetch (`showViewPager` while the list has rows, `hideViewPager` when it has none). What
     * moved is where the rows go: `tulkkiCommandsSession`, drawn by the Compose page, instead of a
     * `CommandAdapter`'s `clear`/`add` calls and the three views beside it.
     */
    protected fun refreshCommands(delayShow: Boolean) {
        if (!tulkkiCommandsInitialised) return

        val mucConfig: UiCommand? =
            if (currentConversation.getMucOptions().getSelf().getAffiliation()
                    .ranks(MucOptions.Affiliation.OWNER)
            ) {
                UiCommand(
                    label = MUC_CONFIG_LABEL,
                    element = null,
                    start = { activity, conversation ->
                        conversation.startMucConfig(activity.xmppConnectionService)
                    },
                )
            } else {
                null
            }

        var commandJid: Jid? =
            currentConversation.getContact().resourceWhichSupport(Namespace.COMMANDS)
        if (commandJid == null &&
            currentConversation.getMode() == Conversation.MODE_MULTI &&
            currentConversation.getMucOptions().hasFeature(Namespace.COMMANDS)
        ) {
            commandJid = (currentConversation.getJid() ?: throw NullPointerException()).asBareJid()
        }
        if (commandJid == null &&
            (currentConversation.getJid() ?: throw NullPointerException()).isDomainJid()
        ) {
            commandJid = currentConversation.getJid()
        }
        if (commandJid == null) {
            tulkkiCommandsSession.applyLoading(false)
            if (mucConfig == null) {
                currentConversation.hideViewPager()
            } else {
                tulkkiCommandsSession.applyCommands(listOf(mucConfig))
                currentConversation.showViewPager()
            }
        } else {
            if (!delayShow) currentConversation.showViewPager()
            tulkkiCommandsSession.applyLoading(true)
            hostActivity.xmppConnectionService.fetchCommands(
                currentConversation.getAccount() ?: throw NullPointerException(),
                commandJid,
            ) { iq ->
                if (hostActivity == null) return@fetchCommands

                hostActivity.runOnUiThread {
                    tulkkiCommandsSession.applyLoading(false)
                    val commands = ArrayList<UiCommand>()
                    if (iq.getType() == Iq.Type.RESULT) {
                        for (child in iq.query().getChildren()) {
                            if (!"item".equals(child.getName()) ||
                                !Namespace.DISCO_ITEMS.equals(child.getNamespace())
                            ) continue
                            commands.add(tulkkiCommand0050(child))
                        }
                    }

                    if (mucConfig != null) commands.add(mucConfig)

                    tulkkiCommandsSession.applyCommands(commands)
                    if (commands.size < 1) {
                        currentConversation.hideViewPager()
                    } else if (delayShow) {
                        currentConversation.showViewPager()
                    }
                }
            }
        }
    }


    /**
     * One XEP-0050 item as a row: the service's own name and the deleted `CommandAdapter.Command0050`
     * `start`'s call, on the conversation that is on screen when the row is tapped.
     */
    private fun tulkkiCommand0050(child: Element): UiCommand =
        UiCommand(
            label = child.getAttribute("name") ?: throw NullPointerException(),
            element = child,
            start = { activity, conversation ->
                activity.startCommand(
                    conversation.getAccount() ?: throw NullPointerException(),
                    child.getAttributeAsJid("jid"),
                    child.getAttribute("node"),
                )
            },
        )


    private fun hideUnreadMessagesCount() {
        // The Java views are deleted with the dead row - the control is the Compose list's
        // (`ScrollToBottom`) - and the badge's own number is zeroed on the session that draws it.
        if (tulkkiMessagesSession != null) {
            tulkkiMessagesSession.unread(0)
        }
    }

    /**
     * Tulkki: brings the row at `pos` - or the newest one - on screen, through the Compose session's
     * scroll request. The deleted mechanism moved the Java `ListView` twice, before and after its
     * next layout pass (`ListViewUtils.setSelection`); a `LazyListState` is moved by the screen, so
     * there is one request and the read event that followed it.
     */
    private fun setSelection(pos: Int, jumpToBottom: Boolean) {
        val uuid: String?
        if (jumpToBottom) {
            val last = getLastVisibleMessage()
            uuid = if (last == null) null else last.getUuid()
        } else {
            synchronized(messageList) {
                uuid =
                    if (pos >= 0 && pos < messageList.size) messageList.get(pos).getUuid() else null
            }
        }
        if (uuid != null && tulkkiMessagesSession != null) {
            tulkkiMessagesSession.requestScroll(uuid)
        }
        tulkkiView.post { fireReadEvent() }
    }

    private fun scrolledToBottom(): Boolean {
        return !currentConversation.isInHistoryPart() && tulkkiView != null && tulkkiAtBottom
    }

    private fun processExtras(extras: Bundle) {
        val downloadUuid = extras.getString(ConversationListActivity.EXTRA_DOWNLOAD_UUID)
        val text = extras.getString(Intent.EXTRA_TEXT)
        val nick = extras.getString(ConversationListActivity.EXTRA_NICK)
        val node = extras.getString(ConversationListActivity.EXTRA_NODE)
        val postInitAction =
            extras.getString(ConversationListActivity.EXTRA_POST_INIT_ACTION)
        val asQuote = extras.getBoolean(ConversationListActivity.EXTRA_AS_QUOTE)
        val pm = extras.getBoolean(ConversationListActivity.EXTRA_IS_PRIVATE_MESSAGE, false)
        val doNotAppend =
            extras.getBoolean(ConversationListActivity.EXTRA_DO_NOT_APPEND, false)
        val type = extras.getString(ConversationListActivity.EXTRA_TYPE)

        val thread = extras.getString(ConversationListActivity.EXTRA_THREAD)
        if (thread != null) {
            currentConversation.setLockThread(true)
            backPressedLeaveSingleThread.isEnabled = true
            setThread(Element("thread").setContent(thread))
            refresh()
        }

        val uris = extractUris(extras)
        if (uris != null && uris.size > 0) {
            if (uris.size == 1 && "geo".equals(uris.get(0).getScheme())) {
                addMediaPreviews(
                    Attachment.of(hostActivity, uris.get(0), Attachment.Type.LOCATION)
                        .toMutableList(),
                )
            } else {
                val cleanedUris = cleanUris(ArrayList(uris))
                addMediaPreviews(
                    Attachment.of(hostActivity, cleanedUris, type).toMutableList(),
                )
            }
            toggleInputMethod()
            return
        }
        if (nick != null) {
            if (pm) {
                val jid = currentConversation.getJid() ?: throw NullPointerException()
                try {
                    val next = Jid.of(jid.getLocal(), jid.getDomain(), nick)
                    privateMessageWith(next)
                } catch (ignored: IllegalArgumentException) {
                    // do nothing
                }
            } else {
                val mucOptions = currentConversation.getMucOptions()
                if (mucOptions.participating() || currentConversation.getNextCounterpart() != null) {
                    highlightInConference(nick)
                }
            }
        } else {
            if (text != null && GeoHelper.GEO_URI.matcher(text).matches()) {
                addMediaPreviews(
                    Attachment.of(hostActivity, Uri.parse(text), Attachment.Type.LOCATION)
                        .toMutableList(),
                )
                toggleInputMethod()
                return
            } else if (text != null && asQuote) {
                writeComposerDraft(DraftMarkup.quote(tulkkiComposerDraft(), text))
            } else {
                appendText(text, doNotAppend)
            }
        }
        if (ConversationListActivity.POST_ACTION_RECORD_VOICE.equals(postInitAction)) {
            attachFile(ATTACHMENT_CHOICE_RECORD_VOICE, false)
            return
        }
        if ("call".equals(postInitAction)) {
            checkPermissionAndTriggerAudioCall()
        }
        if ("message".equals(postInitAction)) {
            conversationViewPager.post {
                conversationViewPager.setCurrentItem(0)
            }
        }
        if ("command".equals(postInitAction)) {
            conversationViewPager.post {
                val adapter = conversationViewPager.getAdapter()
                if (adapter != null && adapter.getCount() > 1) {
                    conversationViewPager.setCurrentItem(1)
                }
                val jid = extras.getString(ConversationListActivity.EXTRA_JID)
                var commandJid: Jid? = null
                if (jid != null) {
                    try {
                        commandJid = Jid.of(jid)
                    } catch (e: IllegalArgumentException) {
                    }
                }
                if (commandJid == null || !commandJid.isFullJid()) {
                    val discoJid =
                        currentConversation.getContact().resourceWhichSupport(Namespace.COMMANDS)
                    if (discoJid != null) commandJid = discoJid
                }
                if (node != null && commandJid != null && hostActivity != null) {
                    currentConversation.startCommand(
                        commandFor(commandJid, node),
                        hostActivity.xmppConnectionService,
                    )
                }
            }
            return
        }
        val message =
            if (downloadUuid == null) {
                null
            } else {
                currentConversation.findMessageWithFileAndUuid(downloadUuid)
            }
        if (message != null) {
            startDownloadable(message)
        }
        if (hostActivity.xmppConnectionService.isOnboarding() &&
            (currentConversation.getJid() ?: throw NullPointerException())
                .equals(Jid.of("cheogram.com"))
        ) {
            if (!currentConversation.switchToSession("jabber:iq:register")) {
                currentConversation.startCommand(
                    commandFor(
                        Jid.of("cheogram.com/CHEOGRAM%jabber:iq:register"),
                        "jabber:iq:register",
                    ),
                    hostActivity.xmppConnectionService,
                )
            }
        }
        val messageUuid = extras.getString(ConversationListActivity.EXTRA_MESSAGE_UUID)
        if (messageUuid != null) {
            updateSelection(messageUuid, false, false)
        }
    }

    /**
     * Tulkki: the XEP-0050 item for a deep link's node, or a freshly built one when the page has no
     * row for it. The rows are the session's now (`UiCommand.element`), which is the deleted
     * `CommandAdapter.Command0050.el` scan; the fallback element is the old body's own.
     */
    private fun commandFor(jid: Jid, node: String?): Element {
        for (row in tulkkiCommandsSession.commands) {
            val command = row.element ?: continue
            val commandNode = command.getAttribute("node")
            if (commandNode == null || !commandNode.equals(node)) continue

            val commandJid = command.getAttributeAsJid("jid")
            if (commandJid != null && !commandJid.asBareJid().equals(jid.asBareJid())) continue

            return command
        }

        return Element("command", Namespace.COMMANDS)
            .setAttribute("name", node)
            .setAttribute("node", node)
            .setAttribute("jid", jid)
    }

    private fun extractUris(extras: Bundle): MutableList<Uri>? {
        val uris: MutableList<Uri>? = extras.getParcelableArrayList<Uri>(Intent.EXTRA_STREAM)
        if (uris != null) {
            return uris
        }
        val uri: Uri? = extras.getParcelable<Uri>(Intent.EXTRA_STREAM)
        if (uri != null) {
            return Collections.singletonList(uri)
        } else {
            return null
        }
    }

    private fun cleanUris(uris: MutableList<Uri>): MutableList<Uri> {
        val iterator = uris.iterator()
        while (iterator.hasNext()) {
            val uri = iterator.next()
            if (FileBackend.dangerousFile(uri)) {
                iterator.remove()
                Toast.makeText(
                    requireActivity(),
                    R.string.security_violation_not_attaching_file,
                    Toast.LENGTH_SHORT,
                )
                    .show()
            }
        }
        return uris
    }

    private fun showBlockSubmenu(view: View): Boolean {
        val jid = currentConversation.getJid() ?: throw NullPointerException()
        val mode = currentConversation.getMode()
        val contact =
            if (mode == Conversation.MODE_SINGLE) currentConversation.getContact() else null
        val showReject =
            (contact ?: throw NullPointerException())
                .getOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST)
        // Tulkki: the deleted `block.xml`'s four rows and its three visibility writes, in its own
        // order. The rows are assembled here and drawn by the Compose popup, so the menu file is gone
        // and the conditions that hid its rows are the same three comparisons.
        val items = ArrayList<UiMenuItem>()
        if (showReject) {
            items.add(UiMenuItem(R.id.reject, getString(R.string.reject_request)))
        }
        items.add(UiMenuItem(R.id.block_domain, getString(R.string.block_entire_domain)))
        if (jid.getLocal() != null) {
            items.add(UiMenuItem(R.id.block_contact, getString(R.string.block_stranger)))
        }
        if (!(contact ?: throw NullPointerException()).showInRoster()) {
            items.add(UiMenuItem(R.id.add_contact, getString(R.string.add_contact)))
        }
        showRowMenu(view, items) { menuId ->
            if (menuId == R.id.reject) {
                hostActivity.xmppConnectionService.stopPresenceUpdatesTo(
                    currentConversation.getContact(),
                )
                updateSnackBar(currentConversation)
            } else if (menuId == R.id.add_contact) {
                mAddBackClickListener.onClick(view)
            } else {
                val blockable: Blockable =
                    if (menuId == R.id.block_domain) {
                        (currentConversation.getAccount() ?: throw NullPointerException())
                            .getRoster()
                            .getContact(jid.getDomain())
                    } else {
                        currentConversation
                    }
                BlockContactDialog.show(hostActivity, blockable)
            }
        }
        return true
    }

// Translation notes: the places where the Java and today's Kotlin signatures disagree, so the Java
// is followed at the call site rather than the rule's literal shape.
//
// * `refresh(boolean)`'s Java argument `activity == null ? null : activity.xmppConnectionService`
//   feeds a declaration that is not this file's and is non-null -
//   `Conversation.populateWithMessages(..., xmppConnectionService: XmppConnectionService)` - so the
//   Java's null branch is kept at the call site as the house guard `?: throw NullPointerException()`,
//   the same NPE the Kotlin declaration's own `checkNotNullParameter` raised for that null (the call
//   site is the only end that can change).
// * The same non-null Kotlin parameters guard the two other Java arguments the Java compiled against
//   platform types: `Jid.of(conversation.getAttribute("inviter"))` and
//   `tulkkiMessagesSession.requestScroll(last.getUuid())`.
// * `head.kt` imported `uk.xa0.tulkki.xmpp.Jid`; that class is not in this tree - it moved to
//   `uk.xa0.tulkki.libs.Jid` (the class's own KDoc records the move), which is the package the Java
//   source of this slice imports and the import this file carries. `Jid.of(...)` below is spelled
//   against that class.

private fun showBlockMucSubmenu(view: View): Boolean {
    val jid = currentConversation.getJid() ?: throw NullPointerException()
    // Tulkki: the deleted `block_muc.xml`'s three rows and its one visibility write, in its own order.
    val items = ArrayList<UiMenuItem>()
    items.add(UiMenuItem(R.id.reject, getString(R.string.delete_and_close)))
    if (jid.getLocal() != null) {
        items.add(UiMenuItem(R.id.block_contact, getString(R.string.block_inviter)))
    }
    items.add(UiMenuItem(R.id.add_bookmark, getString(R.string.add_bookmark)))
    showRowMenu(view, items) { menuId ->
        if (menuId == R.id.reject) {
            hostActivity.xmppConnectionService.clearConversationHistory(currentConversation)
            hostActivity.xmppConnectionService.archiveConversation(currentConversation)
        } else if (menuId == R.id.add_bookmark) {
            hostActivity.xmppConnectionService.saveConversationAsBookmark(currentConversation, "")
            updateSnackBar(currentConversation)
        } else {
            val blockable: Blockable =
                if (menuId == R.id.block_contact) {
                    (currentConversation.getAccount() ?: throw NullPointerException())
                        .getRoster()
                        .getContact(
                            Jid.of(currentConversation.getAttribute("inviter") ?: throw NullPointerException()),
                        )
                } else {
                    currentConversation
                }
            BlockContactDialog.show(hostActivity, blockable)
            hostActivity.xmppConnectionService.archiveConversation(currentConversation)
        }
    }
    return true
}

private fun updateSnackBar(conversation: Conversation) {
    val account = conversation.getAccount() ?: throw NullPointerException()
    val connection = account.getXmppConnection()
    val mode = conversation.getMode()
    val contact = if (mode == Conversation.MODE_SINGLE) conversation.getContact() else null
    if (conversation.getStatus() == Conversation.STATUS_ARCHIVED) {
        return
    }
    if (account.getStatus() == Account.State.DISABLED) {
        showSnackbar(
            R.string.this_account_is_disabled,
            R.string.enable,
            this.mEnableAccountListener,
        )
    } else if (account.getStatus() == Account.State.LOGGED_OUT) {
        showSnackbar(
            R.string.this_account_is_logged_out,
            R.string.log_in,
            this.mEnableAccountListener,
        )
    } else if (conversation.isBlocked()) {
        showSnackbar(R.string.contact_blocked, R.string.unblock, this.mUnblockClickListener)
    } else if (account.getStatus() == Account.State.CONNECTING) {
        showSnackbar(R.string.this_account_is_connecting, 0, null)
    } else if (account.getStatus() != Account.State.ONLINE) {
        showSnackbar(R.string.this_account_is_offline, 0, null)
    } else if (contact != null &&
        !contact.showInRoster() &&
        contact.getOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST)
    ) {
        showSnackbar(
            R.string.contact_added_you,
            R.string.options,
            this.mBlockClickListener,
            this.mLongPressBlockListener,
        )
    } else if (contact != null &&
        contact.getOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST)
    ) {
        showSnackbar(
            R.string.contact_asks_for_presence_subscription,
            R.string.allow,
            this.mAllowPresenceSubscription,
            this.mLongPressBlockListener,
        )
    } else if (mode == Conversation.MODE_MULTI &&
        !conversation.getMucOptions().online() &&
        account.getStatus() == Account.State.ONLINE
    ) {
        // Tulkki: the room's answers are [ConversationBar]'s, not this switch's - every arm
        // has a cell there, including the dead one the old switch fell through.
        val bar = ConversationBar.mucError(
            conversation.getMucOptions().getError(),
            conversation.receivedMessagesCount() > 0,
        )
        if (bar == null) {
            hideSnackbar()
        } else {
            showSnackbar(bar.words, bar.verb.label, barListener(bar.verb))
        }
    } else if (account.hasPendingPgpIntent(conversation)) {
        showSnackbar(R.string.openpgp_messages_found, R.string.decrypt, clickToDecryptListener)
    } else if (mode == Conversation.MODE_SINGLE &&
        conversation.smpRequested()
    ) {
        showSnackbar(R.string.smp_requested, R.string.verify, this.mAnswerSmpClickListener)
    } else if (mode == Conversation.MODE_SINGLE &&
        conversation.hasValidOtrSession() &&
        ((conversation.getOtrSession() ?: throw NullPointerException()).getSessionStatus() == SessionStatus.ENCRYPTED) &&
        (!conversation.isOtrFingerprintVerified())
    ) {
        showSnackbar(R.string.unknown_otr_fingerprint, R.string.verify, clickToVerify)
    } else if (connection != null &&
        connection.getFeatures().blocking() &&
        conversation.strangerInvited()
    ) {
        showSnackbar(
            R.string.received_invite_from_stranger,
            R.string.options,
            OnClickListener { v -> showBlockMucSubmenu(v) },
            View.OnLongClickListener { v -> showBlockMucSubmenu(v) },
        )
    } else if (connection != null &&
        connection.getFeatures().blocking() &&
        conversation.countMessages() != 0 &&
        !conversation.isBlocked() &&
        conversation.isWithStranger()
    ) {
        showSnackbar(
            R.string.received_message_from_stranger,
            R.string.options,
            this.mBlockClickListener,
            this.mLongPressBlockListener,
        )
    } else {
        hideSnackbar()
    }
}

/**
 * Tulkki: the resting bar's action as a listener. [ConversationBar] names the verb because
 * the decision is pure; what each verb *does* opens a dialog, sends a presence or re-joins a room,
 * so it stays here. [BarVerb.NONE] answers no listener, which is the bar's own "the sentence
 * is the whole answer".
 */
private fun barListener(verb: BarVerb): OnClickListener? =
    when (verb) {
        BarVerb.EDIT_NICK -> clickToMuc
        BarVerb.JOIN, BarVerb.TRY_AGAIN -> joinMuc
        BarVerb.ACCEPT_JOIN -> acceptJoin
        BarVerb.LEAVE -> leaveMuc
        BarVerb.ENTER_PASSWORD -> enterPassword
        else -> null
    }

override fun refresh() {
    if (this.tulkkiView == null) {
        Log.d(
            Config.LOGTAG,
            "ConversationFragment.refresh() skipped updated because view binding was null",
        )
        return
    }
    updateChatBG()
    if (this.currentConversation != null &&
        this.hostActivity != null &&
        this.hostActivity.xmppConnectionService != null
    ) {
        if (!hostActivity.xmppConnectionService.isConversationStillOpen(this.currentConversation)) {
            hostActivity.onConversationArchived(this.currentConversation)
            return
        }
    }
    this.refresh(true)
}

private fun refresh(notifyConversationRead: Boolean) {
    synchronized(this.messageList) {
        if (this.currentConversation != null) {
            currentConversation.populateWithMessages(
                this.messageList,
                // The Java passed `null` here (`activity == null ? null : …`); the Kotlin
                // declaration takes a non-null service, so the call site keeps that null as the same
                // NPE the declaration's own `checkNotNullParameter` raised for it.
                (hostActivity ?: throw NullPointerException()).xmppConnectionService,
            )
            try {
                updateStatusMessages()
            } catch (e: IllegalStateException) {
                Log.e(Config.LOGTAG, "Problem updating status messages on refresh: " + e)
            }
            this.messageListAdapter.notifyDataSetChanged()
            // Tulkki: the drawn list's own update, written beside the notification the GONE
            // adapter just got. The Compose rows are the session's (`ConversationRead.read`), and
            // this is the app's one message-update notification, so a send or an arrival must move
            // them here rather than leaving it to the file's watch to say so.
            refreshTulkkiMessageRows()
            // Tulkki: the badge's number is the session's now. The deleted pair of Java writes moved a
            // view that no longer exists, and the Compose control draws the badge from the count it is
            // given - so the count is what is written, and only while it is not zero, which is the
            // deleted condition.
            val unreadSince = currentConversation.getReceivedMessagesCountSinceUuid(lastMessageUuid)
            if (unreadSince != 0 && tulkkiMessagesSession != null) {
                tulkkiMessagesSession.unread(unreadSince)
            }
            updateSnackBar(currentConversation)
            if (hostActivity != null) updateChatMsgHint()
            if (notifyConversationRead && hostActivity != null) {
                tulkkiView.post { fireReadEvent() }
            }
            updateSendButton()
            updateEditablity()
            currentConversation.refreshSessions()

            if (hostActivity != null &&
                (tabLayout.getVisibility() == GONE || conversationViewPager.getCurrentItem() == 0)
            ) {
                hostActivity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }

            if (hostActivity != null) {
                hostActivity.runOnUiThread {
                    refreshPinnedBar()

                    // The three at-a-glance lines are the Compose header's now: the same three
                    // decisions, re-read into `UiConversationHeader` instead of written into eleven
                    // views. Each `View.GONE` of the deleted block is a `null` line here.
                    refreshTulkkiHeader()
                }
            }
        }
    }
}

protected fun messageSent() {
    this.tulkkiSubject = ""
    setThread(null)
    setupReply(null)
    currentConversation.setUserSelectedThread(false)
    mSendingPgpMessage.set(false)
    writeComposerDraft("", 0)
    if (currentConversation.setCorrectingMessage(null)) {
        // A corrected draft may have come back with the message: the same `append` the deleted field
        // did, on the one draft.
        val draft = currentConversation.getDraftMessage().orEmpty()
        writeComposerDraft(draft, draft.length)
        currentConversation.setDraftMessage(null)
    }
    storeNextMessage()
    updateChatMsgHint()
    if (hostActivity == null) return
    val p = PreferenceManager.getDefaultSharedPreferences(hostActivity)
    val prefScrollToBottom =
        p.getBoolean(
            "scroll_to_bottom",
            hostActivity.getResources().getBoolean(R.bool.scroll_to_bottom),
        )
    if (prefScrollToBottom || scrolledToBottom()) {
        Handler()
            .post {
                val last = getLastVisibleMessage()
                if (last != null && tulkkiMessagesSession != null) {
                    tulkkiMessagesSession.requestScroll(last.getUuid() ?: throw NullPointerException())
                }
            }
    }
}

private fun storeNextMessage(): Boolean = storeNextMessage(tulkkiComposerText())

private fun storeNextMessage(msg: String): Boolean {
    val participating =
        currentConversation.getMode() == Conversational.MODE_SINGLE ||
            currentConversation.getMucOptions().participating()
    if (this.currentConversation.getStatus() != Conversation.STATUS_ARCHIVED &&
        participating &&
        this.currentConversation.setNextMessage(msg) && hostActivity != null
    ) {
        hostActivity.xmppConnectionService.updateConversation(this.currentConversation)
        return true
    }
    return false
}

fun doneSendingPgpMessage() {
    mSendingPgpMessage.set(false)
}

fun getMaxHttpUploadSize(conversation: Conversation): Long {
    val connection = (conversation.getAccount() ?: throw NullPointerException()).getXmppConnection()
    return if (connection == null) -1L else connection.getFeatures().getMaxHttpUploadSize()
}

private fun canWrite(): Boolean =
    this.currentConversation.getMode() == Conversation.MODE_SINGLE ||
        this.currentConversation.getMucOptions().participating() ||
        this.currentConversation.getNextCounterpart() != null

private fun updateEditablity() {
    // Tulkki: the four writes that stood the Java field up and down - `setFocusable`,
    // `setFocusableInTouchMode`, `setCursorVisible` and `setEnabled` - are deleted with the field, and
    // the two Java buttons that went with them were already gone. `canWrite` rides
    // `UiComposer.canWrite`, which is the whole of a writer's editability now: the Compose composer
    // draws the request-to-speak affordance in the send icon's place exactly when it is false, so the
    // request is offered when it is possible and never otherwise.
    refreshTulkkiComposer()
}

fun updateSendButton() {
    val c = this.currentConversation
    val status: Presence.Status
    if ((c.getAccount() ?: throw NullPointerException()).getStatus() == Account.State.ONLINE) {
        if (hostActivity != null &&
            hostActivity.xmppConnectionService != null &&
            hostActivity.xmppConnectionService.getMessageArchiveService().isCatchingUp(c)
        ) {
            status = Presence.Status.OFFLINE
        } else if (c.getMode() == Conversation.MODE_SINGLE) {
            status = c.getContact().shownStatus
        } else {
            status =
                if (c.getMucOptions().online()) {
                    Presence.Status.ONLINE
                } else {
                    Presence.Status.OFFLINE
                }
        }
    } else {
        status = Presence.Status.OFFLINE
    }
    // Tulkki: the Java send button's own drawing (its tag, tint and icon) is gone with the button;
    // the Compose send affordance draws itself from `UiComposer.canSend`. What is left here is the
    // subject icons' presence tint, and the composer re-read: it now resolves the thread marker
    // and the `canWrite` gate that the deleted Java row used to stand up and down.
    //
    // The tint is the Compose header's now, and it is the same reading: `getSendButtonColor` off the
    // first `View` the fragment still owns, which the three deleted `setIconTint` calls each read off
    // their own view. The colour then travels to the header as an ARGB int, so the three icons are
    // shaded once rather than three times.
    this.tulkkiHeaderTint =
        SendButtonTool.getSendButtonColor(this.tulkkiView, status)
    refreshTulkkiHeader()
    refreshTulkkiComposer()
}

protected fun updateStatusMessages() {
    DateSeparator.addAll(this.messageList)
    if (showLoadMoreMessages(currentConversation)) {
        this.messageList.add(0, Message.createLoadMoreMessage(currentConversation))
    }
    if (currentConversation.getMode() == Conversation.MODE_SINGLE) {
        val state = currentConversation.getIncomingChatState()
        if (state == ChatState.COMPOSING) {
            this.messageList.add(
                Message.createStatusMessage(
                    currentConversation,
                    getString(R.string.contact_is_typing, currentConversation.getName()),
                ),
            )
        } else if (state == ChatState.PAUSED) {
            this.messageList.add(
                Message.createStatusMessage(
                    currentConversation,
                    getString(
                        R.string.contact_has_stopped_typing,
                        currentConversation.getName(),
                    ),
                ),
            )
        } else {
            for (i in this.messageList.size - 1 downTo 0) {
                val message = this.messageList.get(i)
                if (message.getType() != Message.TYPE_STATUS) {
                    if (message.getStatus() == Message.STATUS_RECEIVED) {
                        return
                    } else {
                        if (message.getStatus() == Message.STATUS_SEND_DISPLAYED) {
                            this.messageList.add(
                                i + 1,
                                Message.createStatusMessage(
                                    currentConversation,
                                    getString(
                                        R.string.contact_has_read_up_to_this_point,
                                        currentConversation.getName(),
                                    ),
                                ),
                            )
                            return
                        }
                    }
                }
            }
        }
    } else {
        val mucOptions = currentConversation.getMucOptions()
        val allUsers: MutableList<MucOptions.User> = mucOptions.getUsers()
        val addedMarkers: MutableSet<ReadByMarker> = HashSet()
        var state = ChatState.COMPOSING
        var users: MutableList<MucOptions.User> =
            currentConversation.getMucOptions().getUsersWithChatState(state, 5)
        if (users.size == 0) {
            state = ChatState.PAUSED
            users = currentConversation.getMucOptions().getUsersWithChatState(state, 5)
        }
        if (mucOptions.isPrivateAndNonAnonymous()) {
            for (i in this.messageList.size - 1 downTo 0) {
                val markersForMessage = messageList.get(i).getReadByMarkers()
                val shownMarkers: MutableList<MucOptions.User> = ArrayList()
                for (marker in markersForMessage) {
                    if (!ReadByMarker.contains(marker, addedMarkers)) {
                        addedMarkers.add(
                            marker,
                        ) // may be put outside this condition. set should do
                        // dedup anyway
                        val user = mucOptions.findUser(marker)
                        if (user != null && !users.contains(user)) {
                            shownMarkers.add(user)
                        }
                    }
                }
                val markerForSender = ReadByMarker.from(messageList.get(i))
                val statusMessage: Message?
                val size = shownMarkers.size
                if (size > 1) {
                    val body: String
                    if (size <= 4) {
                        body =
                            getString(
                                R.string.contacts_have_read_up_to_this_point,
                                UIHelper.concatNames(shownMarkers),
                            )
                    } else if (ReadByMarker.allUsersRepresented(
                            allUsers, markersForMessage, markerForSender)
                    ) {
                        body = getString(R.string.everyone_has_read_up_to_this_point)
                    } else {
                        body =
                            getString(
                                R.string.contacts_and_n_more_have_read_up_to_this_point,
                                UIHelper.concatNames(shownMarkers, 3),
                                size - 3,
                            )
                    }
                    statusMessage = Message.createStatusMessage(currentConversation, body)
                    statusMessage.setCounterparts(shownMarkers)
                } else if (size == 1) {
                    statusMessage =
                        Message.createStatusMessage(
                            currentConversation,
                            getString(
                                R.string.contact_has_read_up_to_this_point,
                                UIHelper.getDisplayName(shownMarkers.get(0)),
                            ),
                        )
                    statusMessage.setCounterpart(shownMarkers.get(0).getFullJid())
                    statusMessage.setTrueCounterpart(shownMarkers.get(0).getRealJid())
                } else {
                    statusMessage = null
                }
                if (statusMessage != null) {
                    this.messageList.add(i + 1, statusMessage)
                }
                addedMarkers.add(markerForSender)
                if (ReadByMarker.allUsersRepresented(allUsers, addedMarkers)) {
                    break
                }
            }
        }
        if (users.size > 0) {
            val statusMessage: Message
            if (users.size == 1) {
                val user = users.get(0)
                val id =
                    if (state == ChatState.COMPOSING) {
                        R.string.contact_is_typing
                    } else {
                        R.string.contact_has_stopped_typing
                    }
                statusMessage =
                    Message.createStatusMessage(
                        currentConversation, getString(id, UIHelper.getDisplayName(user)),
                    )
                statusMessage.setTrueCounterpart(user.getRealJid())
                statusMessage.setCounterpart(user.getFullJid())
            } else {
                val id =
                    if (state == ChatState.COMPOSING) {
                        R.string.contacts_are_typing
                    } else {
                        R.string.contacts_have_stopped_typing
                    }
                statusMessage =
                    Message.createStatusMessage(
                        currentConversation, getString(id, UIHelper.concatNames(users)),
                    )
                statusMessage.setCounterparts(users)
            }
            this.messageList.add(statusMessage)
        }
    }
}

private fun showLoadMoreMessages(c: Conversation): Boolean {
    if (hostActivity == null || hostActivity.xmppConnectionService == null) {
        return false
    }
    val mam = hasMamSupport(c) && !c.getContact().isBlocked()
    val service = hostActivity.xmppConnectionService.getMessageArchiveService()
    return mam &&
        (c.getLastClearHistory().getTimestamp() != 0L ||
            (c.countMessages() == 0 &&
                c.messagesLoaded.get() &&
                c.hasMessagesLeftOnServer() &&
                !service.queryInProgress(c)))
}

private fun hasMamSupport(c: Conversation): Boolean {
    if (c.getMode() == Conversation.MODE_SINGLE) {
        val connection = (c.getAccount() ?: throw NullPointerException()).getXmppConnection()
        return connection != null && connection.getFeatures().mam()
    } else {
        return c.getMucOptions().mamSupport()
    }
}

protected fun showSnackbar(
    message: Int,
    action: Int,
    clickListener: OnClickListener?,
) {
    showSnackbar(message, action, clickListener, null)
}

// Three shapes differ from the Java's own text; none of them changes what the code does.
//  * `startPendingIntent`'s parameter is `PendingIntent?`, though its Java body dereferences it
//    unchecked: Java's unannotated parameter was a platform type, so it tolerated null and NPE'd at
//    `getIntentSender()`. The guard sits at that same dereference, and a sibling chunk can hand it the
//    `PendingIntent?` a `UiCallback` override owns without adding one of its own.
//  * Kotlin parameters are immutable, so `appendText`'s reassignment of `text` becomes a local; and
//    the Java's `tulkkiRecordingFailure = null` is `unsafeNull()`, the spelling by which a field
//    head.kt parks on `unsafeNull()` takes back the null the Java wrote (RULES 2).
// No member carries `open`: head.kt declares the class final, so Kotlin's default narrows nothing
// anything can observe - the same choice ConversationListActivity's conversion made.

/**
 * Tulkki: the resting bar's sentence and its action, resolved here and drawn by the Compose
 * `ConversationSnackbar`.
 *
 * The deleted body moved four views: it showed the bar, cleared the bar's own click, set the
 * sentence, moved the action's `visibility` with the presence of a listener, set the action's words
 * only when a word was named (`if (action != 0)`), and set both of the action's listeners. All of
 * that is `UiSnackbar` now - the action is drawn exactly when [clickListener] is not null, and a
 * zero [action] leaves the button's words as the previous reading left them - so a caller cannot
 * reach a different bar by a different route.
 *
 * The listeners are carried across unchanged; the surface hands them its own host view, which is
 * what the two block submenus anchor their `PopupMenu` at and what the unblock action hides
 * (`ConversationHost.SnackbarSession.markActionUsed`).
 */
protected fun showSnackbar(
    message: Int,
    action: Int,
    clickListener: OnClickListener?,
    longClickListener: View.OnLongClickListener?,
) {
    tulkkiSnackbarSession.show(message, action, clickListener, longClickListener)
}

protected fun hideSnackbar() {
    tulkkiSnackbarSession.hide()
}

protected fun sendMessage(message: Message) {
    Thread { hostActivity.xmppConnectionService.sendMessage(message) }.start()
    messageSent()
}

protected fun sendOtrMessage(message: Message) {
    val activity = getActivity() as ConversationListActivity
    val xmppService = activity.xmppConnectionService
    activity.selectPresence(currentConversation) {
        message.setCounterpart(currentConversation.getNextCounterpart())
        xmppService.sendMessage(message)
        messageSent()
    }
}

protected fun sendPgpMessage(message: Message) {
    val xmppService = hostActivity.xmppConnectionService
    val contact = (message.getConversation() ?: throw NullPointerException()).getContact()
    if (!hostActivity.hasPgp()) {
        hostActivity.showInstallPgpDialog()
        return
    }
    if ((currentConversation.getAccount() ?: throw NullPointerException()).getPgpSignature() == null) {
        hostActivity.announcePgp(
            currentConversation.getAccount() ?: throw NullPointerException(),
            currentConversation,
            null,
            hostActivity.onOpenPGPKeyPublished,
        )
        return
    }
    if (!mSendingPgpMessage.compareAndSet(false, true)) {
        Log.d(Config.LOGTAG, "sending pgp message already in progress")
    }
    if (currentConversation.getMode() == Conversation.MODE_SINGLE) {
        if (contact.getPgpKeyId() != 0L) {
            val pgpEngine = xmppService.getPgpEngine()
                ?: throw NullPointerException("the PGP engine is not installed")
            pgpEngine.hasKey(
                    contact,
                    object : UiCallback<Contact> {

                        override fun userInputRequired(pi: PendingIntent?, obj: Contact) {
                            startPendingIntent(pi, REQUEST_ENCRYPT_MESSAGE)
                        }

                        override fun success(obj: Contact) {
                            encryptTextMessage(message)
                        }

                        override fun error(errorCode: Int, obj: Contact?) {
                            hostActivity.runOnUiThread {
                                Toast.makeText(
                                    hostActivity,
                                    uk.xa0.tulkki.xmpp.R.string
                                        .unable_to_connect_to_keychain,
                                    Toast.LENGTH_SHORT,
                                )
                                    .show()
                            }
                            mSendingPgpMessage.set(false)
                        }
                    },
                )
        } else {
            showNoPGPKeyDialog(
                false,
            ) { dialog, which ->
                currentConversation.setNextEncryption(Message.ENCRYPTION_NONE)
                xmppService.updateConversation(currentConversation)
                message.setEncryption(Message.ENCRYPTION_NONE)
                xmppService.sendMessage(message)
                messageSent()
            }
        }
    } else {
        if (currentConversation.getMucOptions().pgpKeysInUse()) {
            if (!currentConversation.getMucOptions().everybodyHasKeys()) {
                val warning =
                    Toast.makeText(
                        hostActivity,
                        R.string.missing_public_keys,
                        Toast.LENGTH_LONG,
                    )
                warning.setGravity(Gravity.CENTER_VERTICAL, 0, 0)
                warning.show()
            }
            encryptTextMessage(message)
        } else {
            showNoPGPKeyDialog(
                true,
            ) { dialog, which ->
                currentConversation.setNextEncryption(Message.ENCRYPTION_NONE)
                message.setEncryption(Message.ENCRYPTION_NONE)
                xmppService.updateConversation(currentConversation)
                xmppService.sendMessage(message)
                messageSent()
            }
        }
    }
}

fun encryptTextMessage(message: Message) {
    val pgpEngine = hostActivity.xmppConnectionService.getPgpEngine()
        ?: throw NullPointerException("the PGP engine is not installed")
    pgpEngine.encrypt(
            message,
            object : UiCallback<Message> {

                override fun userInputRequired(pi: PendingIntent?, obj: Message) {
                    startPendingIntent(pi, REQUEST_SEND_MESSAGE)
                }

                override fun success(obj: Message) {
                    // TODO the following two call can be made before the callback
                    hostActivity.runOnUiThread { messageSent() }
                }

                override fun error(errorCode: Int, obj: Message?) {
                    hostActivity
                        .runOnUiThread {
                            doneSendingPgpMessage()
                            Toast.makeText(
                                hostActivity,
                                if (errorCode == 0) {
                                    uk.xa0.tulkki.xmpp.R.string
                                        .unable_to_connect_to_keychain
                                } else {
                                    errorCode
                                },
                                Toast.LENGTH_SHORT,
                            )
                                .show()
                        }
                }
            },
        )
}

fun showNoPGPKeyDialog(plural: Boolean, listener: DialogInterface.OnClickListener) {
    val builder = MaterialAlertDialogBuilder(requireActivity())
    if (plural) {
        builder.setTitle(getString(R.string.no_pgp_keys))
        builder.setMessage(getText(R.string.contacts_have_no_pgp_keys))
    } else {
        builder.setTitle(getString(R.string.no_pgp_key))
        builder.setMessage(getText(R.string.contact_has_no_pgp_key))
    }
    builder.setNegativeButton(getString(uk.xa0.tulkki.data.R.string.cancel), null)
    builder.setPositiveButton(getString(R.string.send_unencrypted), listener)
    builder.create().show()
}

fun appendText(text: String?, doNotAppend: Boolean) {
    if (text == null) {
        return
    }
    val previous = tulkkiComposerText()
    if (doNotAppend && !TextUtils.isEmpty(previous)) {
        Toast.makeText(hostActivity, R.string.already_drafting_message, Toast.LENGTH_LONG)
            .show()
        return
    }
    var appended = text
    if (UIHelper.isLastLineQuote(previous)) {
        appended = "\n" + text
    } else if (previous.length != 0 &&
        !Character.isWhitespace(previous[previous.length - 1])
    ) {
        appended = " " + text
    }
    // The deleted `append` left the field holding the previous words plus the new ones, with the
    // caret at the end; the one draft takes the same value.
    val next = previous + appended
    writeComposerDraft(next, next.length)
}

override fun onEnterPressed(isCtrlPressed: Boolean): Boolean {
    if (isCtrlPressed || enterIsSend()) {
        sendMessage()
        return true
    }
    return false
}

private fun enterIsSend(): Boolean {
    val p = PreferenceManager.getDefaultSharedPreferences(hostActivity)
    return p.getBoolean("enter_is_send", getResources().getBoolean(R.bool.enter_is_send))
}

private fun skipImageEditor(): Boolean {
    val p = PreferenceManager.getDefaultSharedPreferences(getActivity())
    return p.getBoolean(
        "skip_image_editor_screen",
        getResources().getBoolean(R.bool.skip_image_editor_screen),
    )
}

fun onArrowUpCtrlPressed(): Boolean {
    val lastEditableMessage =
        if (currentConversation == null) {
            null
        } else {
            currentConversation.getLastEditableMessage()
        }
    if (lastEditableMessage != null) {
        correctMessage(lastEditableMessage)
        return true
    } else {
        Toast.makeText(hostActivity, R.string.could_not_correct_message, Toast.LENGTH_LONG)
            .show()
        return false
    }
}

override fun onTypingStarted() {
    val service =
        if (hostActivity == null) {
            null
        } else {
            hostActivity.xmppConnectionService
        }
    if (service == null) {
        return
    }
    val status = (currentConversation.getAccount() ?: throw NullPointerException()).getStatus()
    if (status == Account.State.ONLINE &&
        currentConversation.setOutgoingChatState(ChatState.COMPOSING)
    ) {
        service.sendChatState(currentConversation)
    }
    runOnUiThread { updateSendButton() }
}

override fun onTypingStopped() {
    val service =
        if (hostActivity == null) {
            null
        } else {
            hostActivity.xmppConnectionService
        }
    if (service == null) {
        return
    }
    val status = (currentConversation.getAccount() ?: throw NullPointerException()).getStatus()
    if (status == Account.State.ONLINE &&
        currentConversation.setOutgoingChatState(ChatState.PAUSED)
    ) {
        service.sendChatState(currentConversation)
    }
}

override fun onTextDeleted() {
    val service =
        if (hostActivity == null) {
            null
        } else {
            hostActivity.xmppConnectionService
        }
    if (service == null) {
        return
    }
    val status = (currentConversation.getAccount() ?: throw NullPointerException()).getStatus()
    if (status == Account.State.ONLINE &&
        currentConversation.setOutgoingChatState(Config.DEFAULT_CHAT_STATE)
    ) {
        service.sendChatState(currentConversation)
    }
    if (storeNextMessage()) {
        runOnUiThread {
            if (hostActivity == null) {
                return@runOnUiThread
            }
            hostActivity.onConversationListItemUpdated()
        }
    }
    runOnUiThread { updateSendButton() }
}

override fun onTextChanged() {
    if (currentConversation != null && currentConversation.getCorrectingMessage() != null) {
        runOnUiThread { updateSendButton() }
    }
}

/**
 * Tulkki: the room's nick completion, on the one draft.
 *
 * <p>The deleted body held the `EditText`'s `Editable` and let its `delete`/`insert` move the caret;
 * the one draft is a value, so the same splice is built from its text and written back with the caret
 * where the two edits left it - after the inserted completion. The three fields the repeats use
 * (`lastCompletionCursor`, `lastCompletionLength`, `completionIndex`) are unchanged, and the Java
 * `IndexOutOfBoundsException` a stale cursor could raise is clamped to the draft's length: a Compose
 * field can be emptied between two Tab presses where the `EditText` could not.
 */
override fun onTabPressed(repeated: Boolean): Boolean {
    if (currentConversation == null || currentConversation.getMode() == Conversation.MODE_SINGLE) {
        return false
    }
    val draft = tulkkiComposerDraft()
    val content = draft.text
    if (repeated) {
        completionIndex++
    } else {
        lastCompletionLength = 0
        completionIndex = 0
        lastCompletionCursor = ConversationHost.selectionEnd(draft)
        val start =
            if (lastCompletionCursor > 0) {
                content.lastIndexOf(" ", lastCompletionCursor - 1) + 1
            } else {
                0
            }
        firstWord = start == 0
        incomplete = content.substring(start, lastCompletionCursor)
    }
    val completions: MutableList<String> = ArrayList()
    for (user in currentConversation.getMucOptions().getUsers()) {
        val name = user.getNick()
        if (name != null && name.startsWith(incomplete)) {
            completions.add(name + (if (firstWord) ": " else " "))
        }
    }
    Collections.sort(completions)
    val from = lastCompletionCursor.coerceIn(0, content.length)
    val to = (from + lastCompletionLength).coerceAtMost(content.length)
    if (completions.size > completionIndex) {
        val completion = completions.get(completionIndex).substring(incomplete.length)
        val next = content.substring(0, from) + completion + content.substring(to)
        lastCompletionLength = completion.length
        writeComposerDraft(next, from + completion.length)
    } else {
        completionIndex = -1
        writeComposerDraft(content.substring(0, from) + content.substring(to), from)
        lastCompletionLength = 0
    }
    return true
}

private fun startPendingIntent(pendingIntent: PendingIntent?, requestCode: Int) {
    try {
        hostActivity
            .startIntentSenderForResult(
                (pendingIntent ?: throw NullPointerException()).getIntentSender(),
                requestCode,
                null,
                0,
                0,
                0,
                UiHost.installed().pgpStartIntentSenderOptions(),
            )
    } catch (ignored: SendIntentException) {
    }
}

override fun onBackendConnected() {
    Log.d(Config.LOGTAG, "ConversationFragment.onBackendConnected()")
    val uuid = pendingConversationUuid.pop()
    if (uuid != null) {
        if (!findAndReInitByUuidOrArchive(uuid)) {
            return
        }
    } else {
        if (!hostActivity.xmppConnectionService.isConversationStillOpen(currentConversation)) {
            clearPending()
            hostActivity.onConversationArchived(currentConversation)
            return
        }
    }
    val activityResult = postponedActivityResult.pop()
    if (activityResult != null) {
        handleActivityResult(activityResult)
    }
    clearPending()
}

private fun findAndReInitByUuidOrArchive(uuid: String): Boolean {
    val conversation =
        hostActivity.xmppConnectionService.findConversationByUuid(uuid) as Conversation?
    if (conversation == null) {
        clearPending()
        hostActivity.onConversationArchived(null)
        return false
    }
    reInit(conversation)
    val lastMessageUuid = pendingLastMessageUuid.pop()
    val attachments = pendingMediaPreviews.pop()
    if (lastMessageUuid != null) {
        // Tulkki: the reader's place is the Compose list's own `rememberSaveable` state, so the
        // Java `ScrollState` that used to be restored here is gone. Its other half is kept: the
        // unread count the jump control showed, read from the message the reader was parked at.
        this.lastMessageUuid = lastMessageUuid
        // Tulkki: the count the jump control showed, written to the session that draws it rather than
        // into the deleted Java badge.
        if (tulkkiMessagesSession != null) {
            tulkkiMessagesSession.unread(
                conversation.getReceivedMessagesCountSinceUuid(lastMessageUuid),
            )
        }
    }
    if (attachments != null && attachments.size > 0) {
        Log.d(Config.LOGTAG, "had attachments on restore")
        addMediaPreviews(attachments)
        toggleInputMethod()
    }
    return true
}

private fun clearPending() {
    if (postponedActivityResult.clear()) {
        Log.e(Config.LOGTAG, "cleared pending intent with unhandled result left")
        if (pendingTakePhotoUri.clear()) {
            Log.e(Config.LOGTAG, "cleared pending photo uri")
        }
    }
    if (pendingConversationUuid.clear()) {
        Log.e(Config.LOGTAG, "cleared pending conversation uuid")
    }
    if (pendingMediaPreviews.clear()) {
        Log.e(Config.LOGTAG, "cleared pending media previews")
    }
}

fun getConversation(): Conversation? {
    return currentConversation
}

private fun requireActivity(): Activity {
    var activity: Activity? = getActivity()
    if (activity == null) activity = hostActivity
    if (activity == null) {
        throw IllegalStateException("Activity not attached")
    }
    return activity
}

// Voice recorder

/**
 * The Compose recording bar's cancel: the deleted `mCancelVoiceRecord`'s job - stop and
 * delete the file, tell the activity the pick was cancelled, and bring the bar down.
 */
override fun onRecordingCancel() {
    mHandler.removeCallbacks(mTickExecutor)
    stopRecording(false)
    hostActivity.setResult(RESULT_CANCELED)
    clearRecording()
}

/**
 * The Compose recording bar's share: the deleted `mShareVoiceRecord`'s job - stop and keep
 * the file, which the [Finisher] hands to the attachment strip. The Java disabled the
 * button for half a second first; here that is `canShare` cleared and the same delay.
 */
override fun onRecordingShare() {
    this.tulkkiRecordingCanShare = false
    refreshTulkkiComposer()
    mHandler.removeCallbacks(mTickExecutor)
    mHandler.postDelayed({ stopRecording(true) }, 500)
}

/**
 * The Compose recording bar's timer tap: the deleted `mTimerClickListener`'s two branches -
 * pause a live recording, resume a paused one, and nothing while no bar is up.
 */
override fun onRecordingTogglePause() {
    if (recording) {
        pauseRecording()
    } else if (tulkkiRecordingActive) {
        resumeRecording()
    }
    refreshTulkkiComposer()
}

/**
 * Tulkki: the recording session the Compose bar draws, one Java field per state field - the
 * mapping `VoiceRecording.kt` documents. The blink, the label and the failure sentence are the
 * composable's; only the session is here.
 */
private fun tulkkiRecording(): VoiceRecordingState {
    return VoiceRecordingState(
        tulkkiRecordingActive,
        recording,
        mStartTime,
        tulkkiRecordingCanShare,
        tulkkiRecordingFailure,
    )
}

/**
 * Tulkki: the bar and its session come down - the Java's three
 * `recordingVoiceActivity.setVisibility(GONE)` writes, gathered into one place.
 */
private fun clearRecording() {
    this.tulkkiRecordingActive = false
    this.tulkkiRecordingCanShare = false
    this.tulkkiRecordingFailure = unsafeNull()
    refreshTulkkiComposer()
}

fun recordVoice() {
    if (this.tulkkiRecordingActive) {
        // A live recording owns the bar. The deleted mic button was disabled here for the same
        // reason; the menu row that opens a recording is the same one while the bar is up, so the
        // guard moves to the verb.
        return
    }
    this.tulkkiRecordingActive = true
    this.tulkkiRecordingFailure = unsafeNull()
    if (!startRecording()) {
        this.tulkkiRecordingCanShare = false
        this.tulkkiRecordingFailure = VoiceRecordingFailure.START
    }
    refreshTulkkiComposer()
}

private fun startRecording(): Boolean {
    hostActivity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED)
    hostActivity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    mRecorder = MediaRecorder()
    mRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
    val userChosenCodec =
        hostActivity.xmppConnectionService.getPreferences().getString("voice_message_codec", "")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        mRecorder.setPrivacySensitive(true)
    }
    val outputFormat: Int
    if (("opus" == userChosenCodec ||
            ("" == userChosenCodec && Config.USE_OPUS_VOICE_MESSAGES)) &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    ) {
        outputFormat = MediaRecorder.OutputFormat.WEBM
        mRecorder.setOutputFormat(outputFormat)
        mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
        mRecorder.setAudioEncodingBitRate(64000)
        mRecorder.setAudioSamplingRate(48000)
    } else if ("aac" == userChosenCodec || !Config.USE_OPUS_VOICE_MESSAGES) {
        outputFormat = MediaRecorder.OutputFormat.MPEG_4
        mRecorder.setOutputFormat(outputFormat)
        // Default for AAC sensitive devices
        mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.HE_AAC)
        mRecorder.setAudioSamplingRate(24_000)
        mRecorder.setAudioEncodingBitRate(28_000)
    } else {
        outputFormat = MediaRecorder.OutputFormat.THREE_GPP
        mRecorder.setOutputFormat(outputFormat)
        mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_WB)
        mRecorder.setAudioEncodingBitRate(23850)
        mRecorder.setAudioSamplingRate(16000)
    }
    setupOutputFile(outputFormat)
    mRecorder.setOutputFile(mOutputFile.getAbsolutePath())

    try {
        mRecorder.prepare()
        mRecorder.start()
        recording = true
        mHandler.postDelayed(mTickExecutor, 0)
        Log.d(Config.LOGTAG, "started recording to " + mOutputFile.getAbsolutePath())
        this.tulkkiRecordingCanShare = true
        return true
    } catch (e: Exception) {
        Log.e(Config.LOGTAG, "prepare() failed ", e)
        return false
    }
}

// NOTE (chunk11): the Java reassigns `outputFileWrittenLatch` in `setupFileObserver`
// (`outputFileWrittenLatch = new CountDownLatch(1);`; its Java field is `private CountDownLatch
// outputFileWrittenLatch = new CountDownLatch(1);`) and that reset is load-bearing - a second
// recording must begin on a fresh latch - but head.kt declares that field `val`. This slice follows
// the Java and writes the assignment; head.kt must declare it `var` for it to compile.
//
// Two mechanical spellings forced by Kotlin, both behaviour-identical: `mRecorder = null` becomes
// `mRecorder = unsafeNull()` (the field's Kotlin type is non-null and parked there), and
// `Objects.requireNonNull(parentDirectory)` becomes `parentDirectory ?: throw NullPointerException()`
// (the house spelling, as in FileBackend.kt).

    protected fun stopRecording() {
        try {
            mRecorder.stop()
            mRecorder.release()
            recording = false
            this.tulkkiRecordingCanShare = false
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    protected fun pauseRecording() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mRecorder.pause()
                mHandler.removeCallbacks(mTickExecutor)
                recording = false
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    protected fun resumeRecording() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mRecorder.resume()
                mHandler.postDelayed(mTickExecutor, 0)
            }
            recording = true
            Log.e("Voice Recorder", "resume recording")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    protected fun stopRecording(saveFile: Boolean) {
        resumeRecording()
        try {
            if (recording) {
                stopRecording()
            }
        } catch (e: Exception) {
            if (saveFile) {
                // The Java raised a Toast here; the bar draws the same sentence in the timer's own
                // slot (`VoiceRecordingFailure.SAVE`), which is where the owner is already looking.
                this.tulkkiRecordingFailure = VoiceRecordingFailure.SAVE
                refreshTulkkiComposer()
                return
            }
        } finally {
            // The Java wrote `mRecorder = null`; the field's Kotlin type is non-null and parked on
            // unsafeNull(), so the same null is spelled this way.
            mRecorder = unsafeNull()
            mStartTime = 0
            mHandler.removeCallbacks(mTickExecutor)
        }
        if (!saveFile && mOutputFile != null) {
            if (mOutputFile.delete()) {
                Log.d(Config.LOGTAG, "deleted canceled recording")
            }
        }
        if (saveFile) {
            Thread(Finisher(outputFileWrittenLatch, mOutputFile, hostActivity)).start()
        }
        hostActivity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hostActivity.setRequestedOrientation(oldOrientation ?: throw NullPointerException())
        this.tulkkiRecordingCanShare = false
    }

    /**
     * The Java's inner `Finisher`: the recorder's output file is closed on another thread, so this
     * waits for the file observer's latch and posts the finished recording to the UI thread. It is
     * an `inner` class because the three verbs it calls - `addMediaPreviews`, `toggleInputMethod`
     * and `clearRecording` - are the fragment's, exactly as the Java's non-static inner class read
     * the enclosing instance.
     */
    private inner class Finisher(
        private val latch: CountDownLatch,
        private val outputFile: File,
        activity: Activity,
    ) : Runnable {

        private val activityReference = WeakReference(activity)

        override fun run() {
            val userChosenCodec =
                hostActivity.xmppConnectionService
                    .getPreferences()
                    .getString("voice_message_codec", "")
            if (("opus" == userChosenCodec ||
                    ("" == userChosenCodec && Config.USE_OPUS_VOICE_MESSAGES)) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            ) {
                try {
                    if (!latch.await(8, TimeUnit.SECONDS)) {
                        Log.d(Config.LOGTAG, "time out waiting for output file to be written")
                    }
                } catch (e: InterruptedException) {
                    Log.d(Config.LOGTAG, "interrupted while waiting for output file to be written", e)
                }
                val activity = activityReference.get() ?: return
                activity.runOnUiThread {
                    activity.setResult(
                        Activity.RESULT_OK,
                        Intent().setData(Uri.fromFile(outputFile)),
                    )
                    addMediaPreviews(
                        Attachment.of(activity, Uri.fromFile(outputFile), Attachment.Type.RECORDING),
                    )
                    toggleInputMethod()
                    //attachFileToConversation(conversation, Uri.fromFile(outputFile), "audio/oga;codecs=opus");
                    clearRecording()
                }
            } else if ("aac" == userChosenCodec || !Config.USE_OPUS_VOICE_MESSAGES) {
                try {
                    if (!latch.await(8, TimeUnit.SECONDS)) {
                        Log.d(Config.LOGTAG, "time out waiting for output file to be written")
                    }
                } catch (e: InterruptedException) {
                    Log.d(Config.LOGTAG, "interrupted while waiting for output file to be written", e)
                }
                val activity = activityReference.get() ?: return
                activity.runOnUiThread {
                    activity.setResult(
                        Activity.RESULT_OK,
                        Intent().setData(Uri.fromFile(outputFile)),
                    )
                    addMediaPreviews(
                        Attachment.of(activity, Uri.fromFile(outputFile), Attachment.Type.RECORDING),
                    )
                    toggleInputMethod()
                    //attachFileToConversation(conversation, Uri.fromFile(outputFile), "audio/mp4");
                    clearRecording()
                }
            }
        }
    }

    private fun generateOutputFilename(outputFormat: Int): File {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US)
        val extension: String
        if (outputFormat == MediaRecorder.OutputFormat.MPEG_4) {
            extension = "m4a"
        } else if (outputFormat == MediaRecorder.OutputFormat.WEBM) {
            extension = "opus"
        } else if (outputFormat == MediaRecorder.OutputFormat.THREE_GPP) {
            extension = "awb"
        } else {
            throw IllegalStateException("Unrecognized output format")
        }
        val filename =
            String.format("RECORDING_%s.%s", dateFormat.format(Date()), extension)
        val parentDirectory: File
        if (currentConversation.storeSecurely(hostActivity.xmppConnectionService)) {
            parentDirectory = File(hostActivity.xmppConnectionService.getFilesDir(), "/media")
        } else {
            // The same public directory the rest of the media uses, so a rename of it (the one-time
            // move off upstream's directory name) covers recordings too.
            parentDirectory = File(FileBackend.getStorageDirectory(), "recordings")
        }
        return File(parentDirectory, filename)
    }

    private fun setupOutputFile(outputFormat: Int) {
        mOutputFile = generateOutputFilename(outputFormat)
        // The Java guarded this local with `Objects.requireNonNull(parentDirectory)`; the Kotlin
        // local is `File?` and is read twice below, so the same guard binds it once.
        val parentDirectory = mOutputFile.getParentFile() ?: throw NullPointerException()
        if (parentDirectory.mkdirs()) {
            Log.d(Config.LOGTAG, "created " + parentDirectory.getAbsolutePath())
        }
        setupFileObserver(parentDirectory)
    }

    private fun setupFileObserver(directory: File) {
        outputFileWrittenLatch = CountDownLatch(1)
        mFileObserver =
            object : FileObserver(directory.getAbsolutePath()) {
                override fun onEvent(event: Int, s: String?) {
                    if (s != null &&
                        s == mOutputFile.getName() &&
                        event == FileObserver.CLOSE_WRITE
                    ) {
                        outputFileWrittenLatch.countDown()
                    }
                }
            }
        mFileObserver.startWatching()
    }

    /**
     * Tulkki: the one-second beat of a live recording, re-read by the Compose bar. The Java formatted
     * `MM:SS` into the `timer` view here and wrapped the minutes at the hour; the label is
     * `VoiceRecordingTime`'s now, the bar is the drawn surface, and this only advances the session.
     * The read comes before the increment, as the Java's text write did.
     */
    private fun tick() {
        refreshTulkkiComposer()
        if (recording) {
            mStartTime++
        }
    }

    /**
     * Tulkki: closes the Compose emoji panel.
     *
     * It is the whole of the state left on this side. The old collapse moved a
     * `LinearLayout`'s `LayoutParams`, cleared three flags that existed to keep the panel
     * and the keyboard from fighting over the same pixels, and swapped the composer's two buttons; the
     * panel is `EmojiPanel.kt` now, the two buttons are gone, and open or closed is one call.
     */
    private fun closeEmojiPanel() {
        if (emojiPanelController != null) {
            emojiPanelController.close()
        }
    }

    public fun updateinputfield() {
        if (Build.VERSION.SDK_INT > 29) {
            ViewCompat.setOnApplyWindowInsetsListener(
                hostActivity.getWindow().getDecorView(),
            ) { v, insets ->
                val isKeyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
                if (hostActivity != null && hostActivity.xmppConnectionService != null &&
                    isKeyboardVisible && hostActivity.xmppConnectionService.showTextFormatting()
                ) {
                    showTextFormat()
                } else {
                    hideTextFormat()
                }
                ViewCompat.onApplyWindowInsets(v, insets)
            }
        } else {
            if (keyboardHeightProvider != null) return
            val llRoot = tulkkiView
            keyboardHeightProvider = KeyboardHeightProvider(
                hostActivity,
                hostActivity.getWindowManager(),
                llRoot,
                object : KeyboardHeightProvider.KeyboardHeightListener {
                    override fun onKeyboardHeightChanged(
                        keyboardHeight: Int,
                        keyboardOpen: Boolean,
                        isLandscape: Boolean,
                    ) {
                        if (hostActivity != null && hostActivity.xmppConnectionService != null &&
                            keyboardOpen && hostActivity.xmppConnectionService.showTextFormatting()
                        ) {
                            showTextFormat()
                        } else {
                            hideTextFormat()
                        }
                    }
                },
            )
        }
    }

    /**
     * Tulkki: show the Compose formatting bar. The Java row this replaced is gone, so the bar is the
     * only one and this is the whole of the verb: `updateinputfield` resolved the IME half of
     * the condition and `showTextFormatting()` the preference half, and the flag carries the
     * answer to [refreshTulkkiComposer].
     */
    private fun showTextFormat() {
        if (!tulkkiPageHosted) {
            return
        }
        if (!this.tulkkiFormatting) {
            this.tulkkiFormatting = true
            refreshTulkkiComposer()
        }
    }

    private fun hideTextFormat() {
        if (!tulkkiPageHosted) {
            return
        }
        if (this.tulkkiFormatting) {
            this.tulkkiFormatting = false
            refreshTulkkiComposer()
        }
    }

    /**
     * Tulkki: names the conversation the Compose pinned bar draws and asks it for a fresh read.
     *
     * It is the whole of this fragment's pinned-bar life: the bar answers "no pins" for a null
     * uuid, so this needs no branch, and a refresh that lands in the same conversation only re-reads
     * the store - the pins may have changed under it.
     */
    private fun refreshPinnedBar() {
        if (pinnedBarController == null) {
            return
        }
        val current = ConversationLookup.conversationReliable(hostActivity)
        pinnedBarController.setConversation(if (current == null) null else current.getUuid())
        pinnedBarController.reload()
    }

}
