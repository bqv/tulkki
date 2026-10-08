package uk.xa0.tulkki.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import java.util.Arrays
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import uk.xa0.tulkki.data.DatabaseBackend
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.data.model.RtpSessionStatus
import uk.xa0.tulkki.ui.calls.CallRow
import uk.xa0.tulkki.ui.calls.CallsScreen
import uk.xa0.tulkki.ui.calls.CallsScreenState
import uk.xa0.tulkki.ui.chrome.ChromeMenuItem
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import uk.xa0.tulkki.ui.host.UiHost
import uk.xa0.tulkki.ui.stories.NavBadges
import uk.xa0.tulkki.ui.stories.NavIcons
import uk.xa0.tulkki.ui.stories.NavTab
import uk.xa0.tulkki.ui.utils.UIHelper

/**
 * The calls log: the shared chrome around [CallsScreen], and the host half the deleted
 * `CallsFragment` and `adapter/CallsAdapter.kt` carried between them.
 *
 * <p>**The layouts are gone.** `activity_calls.xml` held the toolbar, the fragment container and the
 * `BottomNavigationView` bound to `bottom_navigation_menu_calls.xml`; `fragment_calls.xml` held the
 * `RecyclerView` and the empty view; `item_call.xml` was the row `CallsAdapter` inflated; and all
 * three layouts, both menus, the adapter and `CallsFragment` are deleted. The bar is the shared
 * chrome ([TulkkiChrome]) now and the body is [CallsScreen], so `setSupportActionBar`,
 * `configureActionBar`, `setTitle`, `Activities.setStatusAndNavigationBarColors`, the fragment
 * transaction, the `BottomNavigationView` lookups and its listener all went with the views they
 * belonged to.
 *
 * <p>**The settings item.** The gear this screen shares with the stories screen used to come from
 * `res/menu/activity_stories.xml`; when that menu was deleted with the stories screen the item was
 * built in `onCreateOptionsMenu` here. The chrome carries it now, in the same overflow position and
 * with the same title resource and action, so `onCreateOptionsMenu`, `onOptionsItemSelected` and the
 * item id constant are gone.
 *
 * <p>**What the fragment did that this class does now.** `CallsFragment` bound the service on its own
 * and registered an `OnCallLogUpdated` listener, loaded the log on a single-thread executor, and ran
 * the permission-then-place-call flow. `XmppActivity` already owns the service binding, so this class
 * only registers the listener ([onStart], [onBackendConnected]), reads the log off the main thread
 * ([loadCalls], [getCalls]), turns each [Message] into a [CallRow] ([publishRows]) and keeps the
 * fragment's permission callbacks.
 *
 * <p>**What the screens still do between them.** Every string is the tree's own: the title, the
 * "No calls yet" state, the four tab labels, the settings item and the two "call again" items. The
 * four tab badges are read exactly as the deleted `refreshUiReal` read them, and a tap on another tab
 * makes the same intent, with the same extra and the same transition, that the deleted
 * `OnItemSelectedListener` made.
 */
class CallsActivity :
    XmppActivity(),
    // Tulkki: top-level in the island now (chunk C48); fully qualified so no `:ui -> :xmpp` key is
    // added.
    uk.xa0.tulkki.xmpp.services.OnCallLogUpdated {

    /** The log the last read found, newest first. */
    private val calls: MutableList<Message> = ArrayList()

    private var mPendingCall: Message? = null
    private var callsLoaded = false
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    /** The whole screen, read by the chrome's content and written here after a read. */
    private var screen by mutableStateOf(CallsScreenState())

    protected override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The chrome draws its bar behind the system bars and hands the content the space they
        // leave, so the window must not inset itself for them first - on every API level, which is
        // what enableEdgeToEdge settles. See TulkkiChrome for the whole pattern.
        enableEdgeToEdge()

        setTulkkiContent(darkTheme = isDark()) {
            val state = screen
            TulkkiChrome(
                // `setTitle` went with the action bar; the label is the layout's `@string/calls`.
                title = stringResource(R.string.calls),
                // `refreshUiReal` switched the arrow on only while the tab bar was hidden, and
                // `finish()` is what the arrow ran.
                onUp = if (state.showNavBar) null else ({ finish() }),
                menu =
                    listOf(
                        ChromeMenuItem(stringResource(R.string.action_settings)) {
                            startActivity(
                                Intent(this, uk.xa0.tulkki.ui.activity.SettingsActivity::class.java)
                            )
                        }
                    ),
            ) {
                CallsScreen(
                    state = state,
                    icons = navIcons(),
                    onTab = { navigate(it) },
                    onContact = { row -> switchToContactDetails(row.contact) },
                    onCallAgain = { row, isVideoCall -> onCallAgainClick(row, isVideoCall) },
                )
            }
        }
    }

    public override fun onStart() {
        super.onStart()

        screen =
            screen.copy(
                showNavBar =
                    getBooleanPreference("show_nav_bar", R.bool.show_nav_bar) &&
                        intent.getBooleanExtra("show_nav_bar", false)
            )

        val service = xmppConnectionService
        if (service != null) {
            service.setOnCallLogUpdatedListener(this)
        }
    }

    public override fun onStop() {
        super.onStop()
        xmppConnectionService?.removeOnCallLogUpdatedListener(this)
    }

    override fun onBackendConnected() {
        // Clear missed call notifications and badge when the activity is displayed.
        if (xmppConnectionService != null) {
            xmppConnectionService.getNotificationService().clearMissedCalls()
            xmppConnectionService.setOnCallLogUpdatedListener(this)
        }
        refreshUiReal()
        if (!callsLoaded) {
            loadCalls()
        }
    }

    public override fun onBackPressed() {
        if (screen.showNavBar) {
            val intent = Intent(this, ConversationListActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            startActivity(intent)
            overridePendingTransition(R.animator.fade_in, R.animator.fade_out)
        }

        super.onBackPressed()
    }

    /** The four tab destinations, exactly the deleted `OnItemSelectedListener`'s. */
    private fun navigate(tab: NavTab) {
        when (tab) {
            NavTab.CHATS -> {
                startActivity(Intent(applicationContext, ConversationListActivity::class.java))
                overridePendingTransition(R.animator.fade_in, R.animator.fade_out)
            }
            NavTab.FEEDS -> {
                val i = Intent(applicationContext, PostsActivity::class.java)
                i.putExtra("show_nav_bar", true)
                startActivity(i)
                overridePendingTransition(R.animator.fade_in, R.animator.fade_out)
            }
            NavTab.STORIES -> {
                val i = Intent(applicationContext, StoriesActivity::class.java)
                i.putExtra("show_nav_bar", true)
                startActivity(i)
                overridePendingTransition(R.animator.fade_in, R.animator.fade_out)
            }
            NavTab.CALLS -> Unit
        }
    }

    protected override fun refreshUiReal() {
        if (xmppConnectionService == null) {
            return
        }
        val service = xmppConnectionService

        // The deleted `refreshUiReal`'s four badges, read exactly as it read them.
        val unreadCount = service.unreadCount()
        val lastRead = getPreferences().getLong("last_read_story_timestamp", 0)
        val hasNewStories = service.getStories().stream().anyMatch { s -> s.getPublished() > lastRead }
        val lastReadPosts = getPreferences().getLong("last_read_post_timestamp", 0)
        val hasNewPosts =
            DatabaseBackend.get().getPosts().stream().anyMatch { p ->
                val published = p.published
                published != null && published.time > lastReadPosts
            }
        val hasNewMissedCalls = service.getNotificationService().hasNewMissedCalls()

        screen =
            screen.copy(
                badges =
                    NavBadges(
                        chats = unreadCount,
                        stories = hasNewStories,
                        feeds = hasNewPosts,
                        calls = hasNewMissedCalls,
                    )
            )
    }

    /**
     * The tab bar's icons, resolved from the eight theme attributes the deleted
     * `bottom_navigation_menu_calls.xml` named, whose `values-night/themes.xml` pair the white set.
     * `CallsScreen` takes them already resolved because a theme attribute belongs to this window's
     * theme, which is where `Theme.Tulkki` is applied.
     */
    private fun navIcons(): NavIcons =
        NavIcons(
            chatsUnselected =
                themeDrawable(R.attr.ic_chat_unselected, R.drawable.outline_chat_black_24),
            chatsSelected = themeDrawable(R.attr.ic_chat_selected, R.drawable.chat_selected_black_24),
            callsUnselected =
                themeDrawable(R.attr.ic_calls_unselected, R.drawable.calls_unselected_black_24dp),
            callsSelected =
                themeDrawable(R.attr.ic_calls_selected, R.drawable.calls_selected_black_24dp),
            storiesUnselected =
                themeDrawable(R.attr.ic_stories_unselected, R.drawable.stories_unselected_black_24),
            storiesSelected =
                themeDrawable(R.attr.ic_stories_selected, R.drawable.stories_selected_black_24),
            feedsUnselected = themeDrawable(R.attr.feed_unselected, R.drawable.feed_unselected_black_24dp),
            feedsSelected = themeDrawable(R.attr.feed_selected, R.drawable.feed_selected_black_24dp),
        )

    /** One theme attribute, as the drawable the deleted menu named, with the light value as the floor. */
    private fun themeDrawable(@AttrRes attr: Int, @DrawableRes fallback: Int): Int {
        val value = TypedValue()
        return if (theme.resolveAttribute(attr, value, true)) value.resourceId else fallback
    }

    /** The deleted `CallsFragment`'s own read, off the main thread. */
    private fun loadCalls() {
        xmppConnectionService ?: return
        executor.execute {
            val loadedCalls = getCalls()
            runOnUiThread {
                calls.clear()
                calls.addAll(loadedCalls)
                publishRows()
                callsLoaded = true
            }
        }
    }

    private fun getCalls(): List<Message> {
        val calls = ArrayList<Message>()
        val service = xmppConnectionService ?: return calls
        // The list is island-typed as `ConversationRef`; the objects in it are the model's, which is
        // what `DatabaseBackend.getMessages(Conversation, ...)` needs. The cast is per element and
        // checked, so no island type has to be named here.
        for (ref in service.getConversationList()) {
            calls.addAll(
                DatabaseBackend.get()
                    .getMessages(ref as Conversation, Message.TYPE_RTP_SESSION, 100)
            )
        }
        calls.sortWith(Comparator { o1, o2 -> java.lang.Long.compare(o2.getTimeSent(), o1.getTimeSent()) })
        return calls
    }

    /**
     * The deleted `CallsAdapter.CallViewHolder.bind`, for every row at once: the contact, its avatar
     * and name, the status icon and its colour, the preview, the relative date and the optional
     * `show_own_accounts` line.
     */
    private fun publishRows() {
        val service = xmppConnectionService
        val rows = ArrayList<CallRow>(calls.size)
        for (call in calls) {
            // Java dereferenced `call.getConversation()` straight through; a null there was the
            // NPE it would have thrown, so name it rather than assert it.
            val conversation = call.getConversation() ?: throw NullPointerException()
            val contact = conversation.getContact()
            val rtpSessionStatus = RtpSessionStatus.of(call.getBody())
            val received = call.getStatus() == Message.STATUS_RECEIVED
            val missed = received && !rtpSessionStatus.successful
            val showOwnAccounts =
                service != null &&
                    service.getBooleanPreference("show_own_accounts", R.bool.show_own_accounts)
            rows.add(
                CallRow(
                    message = call,
                    contact = contact,
                    name = contact.getDisplayName(),
                    info =
                        if (service != null) {
                            UIHelper.getMessagePreview(service, call).first.toString()
                        } else {
                            ""
                        },
                    date = UIHelper.readableTimeDifference(this, call.getTimeSent(), false),
                    missed = missed,
                    icon = UIHelper.rtpSessionStatusIcon(received, rtpSessionStatus.successful),
                    account =
                        if (showOwnAccounts) {
                            conversation.getAccount()?.getJid()?.asBareJid()?.toString()
                        } else {
                            null
                        },
                    contactClickable = !contact.isSelf(),
                )
            )
        }
        screen = screen.copy(rows = rows)
    }

    private fun onCallAgainClick(row: CallRow, isVideoCall: Boolean) {
        mPendingCall = row.message ?: return
        if (isVideoCall) {
            checkPermissionAndTriggerVideoCall()
        } else {
            checkPermissionAndTriggerAudioCall()
        }
    }

    private fun checkPermissionAndTriggerAudioCall() {
        val service = xmppConnectionService ?: return
        val pending = mPendingCall ?: return
        val conversation = pending.getConversation() ?: throw NullPointerException()
        val account = conversation.getAccount() ?: throw NullPointerException()
        if (service.useTorToConnect() || account.isOnion()) {
            Toast.makeText(this, R.string.disable_tor_to_make_call, Toast.LENGTH_SHORT).show()
            return
        }
        if (service.useI2PToConnect() || account.isI2P()) {
            Toast.makeText(this, R.string.no_i2p_calls, Toast.LENGTH_SHORT).show()
            return
        }

        val permissions: List<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Arrays.asList(Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                Collections.singletonList(Manifest.permission.RECORD_AUDIO)
            }
        if (hasPermissions(permissions, REQUEST_START_AUDIO_CALL)) {
            triggerRtpSession(RtpSessionActivity.ACTION_MAKE_VOICE_CALL)
        }
    }

    private fun checkPermissionAndTriggerVideoCall() {
        val service = xmppConnectionService ?: return
        val pending = mPendingCall ?: return
        val conversation = pending.getConversation() ?: throw NullPointerException()
        val account = conversation.getAccount() ?: throw NullPointerException()
        if (service.useTorToConnect() || account.isOnion()) {
            Toast.makeText(this, R.string.disable_tor_to_make_call, Toast.LENGTH_SHORT).show()
            return
        }
        if (service.useI2PToConnect() || account.isI2P()) {
            Toast.makeText(this, R.string.no_i2p_calls, Toast.LENGTH_SHORT).show()
            return
        }
        val permissions: List<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Arrays.asList(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.CAMERA,
                    Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                Arrays.asList(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            }
        if (hasPermissions(permissions, REQUEST_START_VIDEO_CALL)) {
            triggerRtpSession(RtpSessionActivity.ACTION_MAKE_VIDEO_CALL)
        }
    }

    private fun hasPermissions(permissions: List<String>, requestCode: Int): Boolean {
        val missingPermissions = ArrayList<String>()
        for (permission in permissions) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(permission)
            }
        }
        if (missingPermissions.isEmpty()) {
            return true
        }
        requestPermissions(missingPermissions.toTypedArray(), requestCode)
        return false
    }

    public override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.size > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (mPendingCall != null) {
                if (requestCode == REQUEST_START_AUDIO_CALL) {
                    triggerRtpSession(RtpSessionActivity.ACTION_MAKE_VOICE_CALL)
                } else if (requestCode == REQUEST_START_VIDEO_CALL) {
                    triggerRtpSession(RtpSessionActivity.ACTION_MAKE_VIDEO_CALL)
                }
            }
        }
    }

    private fun triggerRtpSession(action: String) {
        val service = xmppConnectionService ?: return
        if (service.getJingleConnectionManager().isBusy()) {
            Toast.makeText(this, R.string.only_one_call_at_a_time, Toast.LENGTH_LONG).show()
            return
        }
        val conversation =
            (mPendingCall?.getConversation() ?: throw NullPointerException()) as Conversation
        val account = conversation.getAccount() ?: throw NullPointerException()
        if (account.setOption(Account.OPTION_SOFT_DISABLED, false)) {
            service.updateAccount(account)
        }
        UiHost.installed()
            .placeCall(
                service,
                account,
                conversation.getJid() ?: throw NullPointerException(),
                action)
        mPendingCall = null
    }

    override fun onCallLogUpdated() {
        loadCalls()
    }

    private companion object {

        private const val REQUEST_START_AUDIO_CALL = 0x213
        private const val REQUEST_START_VIDEO_CALL = 0x214
    }
}
