package uk.xa0.tulkki.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentSender
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.Uri
import android.os.AsyncTask
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.PersistableBundle
import android.os.PowerManager
import android.os.SystemClock
import android.preference.PreferenceManager
import android.provider.Settings
import android.text.Html
import android.text.InputType
import android.util.DisplayMetrics
import android.util.Log
import android.util.Pair
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.ValueCallback
import android.widget.ImageView
import android.widget.Toast
import androidx.annotation.BoolRes
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.base.Strings
import net.java.otr4j.session.SessionID
import uk.xa0.tulkki.libs.Avatarable
import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.AppSettings
import uk.xa0.tulkki.data.DatabaseBackend
import uk.xa0.tulkki.data.FileBackends
import uk.xa0.tulkki.data.messages.ConversationSnapshot
import uk.xa0.tulkki.data.messages.MessageSnapshot
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Contact
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.data.model.Presences
import uk.xa0.tulkki.libs.Transferable
import uk.xa0.tulkki.translation.EngineHost
import uk.xa0.tulkki.translation.HeldSend
import uk.xa0.tulkki.translation.OutgoingTranslation
import uk.xa0.tulkki.translation.ReplyFallback
import uk.xa0.tulkki.translation.ReviewMarks
import uk.xa0.tulkki.translation.ReviewStore
import uk.xa0.tulkki.translation.TranslationSettings
import uk.xa0.tulkki.ui.conversation.EnglishHold
import uk.xa0.tulkki.ui.conversationlist.AvatarShape
import uk.xa0.tulkki.ui.conversationlist.ConversationAvatar
import uk.xa0.tulkki.ui.dialogs.showTulkkiDialog
import uk.xa0.tulkki.ui.host.UiHost
import uk.xa0.tulkki.ui.projection.AttachmentInHand
import uk.xa0.tulkki.ui.projection.CryptoPhase
import uk.xa0.tulkki.ui.projection.EnglishInHand
import uk.xa0.tulkki.ui.projection.MessageFacts
import uk.xa0.tulkki.ui.projection.PerProcess
import uk.xa0.tulkki.ui.projection.RoomName
import uk.xa0.tulkki.ui.projection.UiAccountLine
import uk.xa0.tulkki.ui.projection.UiNotification
import uk.xa0.tulkki.ui.projection.UiPresence
import uk.xa0.tulkki.ui.projection.UiReview
import uk.xa0.tulkki.ui.projection.UiTransferState
import uk.xa0.tulkki.ui.util.MenuDoubleTabUtil
import uk.xa0.tulkki.ui.util.PresenceSelector
import uk.xa0.tulkki.ui.util.SettingsUtils
import uk.xa0.tulkki.ui.utils.ThemeHelper
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.OnKeyStatusUpdated
import uk.xa0.tulkki.xmpp.OnUpdateBlocklist
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.xmpp.utils.AccountUtils
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.ArrayList
import java.util.HashMap
import java.util.PriorityQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference

/**
 * The Tulkki port of `XmppActivity.java`, the base of most of `:ui`'s remaining screens.
 *
 * **The Java-visible surface is preserved member for member** (proved with `javap` against the
 * compiled Java class). Two interop decisions are worth naming: the five `protected static final
 * int` request codes are companion `const val`s, which widen them to `public static final` (Kotlin
 * cannot put `protected` on a companion member and a Kotlin subclass cannot read one through the
 * base-class qualifier), and `xmppConnectionService`/`staticXmppConnectionService` keep the Java
 * field shape through a non-null field that is null until the binder hands the service over - Kotlin
 * cannot put `@JvmField` on a `lateinit` property, and the 250-odd Kotlin call sites read the field
 * as non-null.
 */
abstract class XmppActivity : ActionBarActivity() {

    @JvmField var xmppConnectionService: XmppConnectionService = unsafeNull()
    @JvmField var xmppConnectionServiceBound = false

    @JvmField var AvatarPopup: AlertDialog? = null

    @JvmField protected var mTheme = 0
    @JvmField protected var mCustomColors: HashMap<Int, Int> = unsafeNull()
    @JvmField protected var mUsingEnterKey = false

    // Java's `protected` reaches the whole package; Kotlin's does not, and same-package access has
    // no Kotlin spelling. `internal` is the narrowest one that reaches `ConversationFragment`, which
    // reads these three through the activity it is attached to; every reader is in `:ui`.
    @JvmField internal var mUseTor = false
    @JvmField internal var mUseI2P = false
    @JvmField internal var mToast: Toast? = null
    @JvmField
    var onOpenPGPKeyPublished: Runnable =
        Runnable {
            Toast.makeText(
                this@XmppActivity,
                R.string.openpgp_has_been_published,
                Toast.LENGTH_SHORT,
            ).show()
        }

    @JvmField protected var mPendingConferenceInvite: ConferenceInvite? = null

    @JvmField
    protected var activityCallbacks: PriorityQueue<Pair<Int, ValueCallback<Array<Uri>>>> =
        if (Build.VERSION.SDK_INT >= 24) {
            PriorityQueue(
                Comparator<Pair<Int, ValueCallback<Array<Uri>>>> { x, y ->
                    y.first.compareTo(x.first)
                },
            )
        } else {
            PriorityQueue()
        }

    @JvmField
    protected var mConnection: ServiceConnection =
        object : ServiceConnection {

            override fun onServiceConnected(className: ComponentName, service: IBinder) {
                val binder = service as uk.xa0.tulkki.xmpp.services.XmppConnectionBinder
                xmppConnectionService = binder.getService()
                staticXmppConnectionService = binder.getService()
                xmppConnectionServiceBound = true
                handleServiceConnected()
            }

            override fun onServiceDisconnected(arg0: ComponentName) {
                xmppConnectionServiceBound = false
            }
        }

    private var metrics: DisplayMetrics = unsafeNull()
    private var mLastUiRefresh = 0L
    private val mRefreshUiHandler = Handler()
    private val mRefreshUiRunnable =
        Runnable {
            mLastUiRefresh = SystemClock.elapsedRealtime()
            refreshUiReal()
        }

    private val adhocCallback:
        uk.xa0.tulkki.xmpp.services.UiCallbackPort<uk.xa0.tulkki.xmpp.refs.ConversationRef> =
        object : uk.xa0.tulkki.xmpp.services.UiCallbackPort<uk.xa0.tulkki.xmpp.refs.ConversationRef> {

            override fun success(conversation: uk.xa0.tulkki.xmpp.refs.ConversationRef) {
                runOnUiThread {
                    switchToConversation(conversation as Conversation)
                    hideToast()
                }
            }

            override fun error(
                errorCode: Int,
                obj: uk.xa0.tulkki.xmpp.refs.ConversationRef?,
            ) {
                runOnUiThread { replaceToast(getString(errorCode)) }
            }

            override fun userInputRequired(
                pi: PendingIntent?,
                obj: uk.xa0.tulkki.xmpp.refs.ConversationRef,
            ) {}
        }

    // Same widening as the fields above, and the same reason: `ConversationFragment` calls these on
    // the activity it is attached to, which Java's package-scoped `protected` allowed and Kotlin's
    // class-scoped one does not. `internal` is the narrowest spelling that reaches `:ui`, the whole
    // reader set.
    internal open fun hideToast() {
        val toast = this.mToast
        if (toast == null) {
            return
        }
        toast.cancel()
    }

    internal open fun replaceToast(msg: String) {
        replaceToast(msg, true)
    }

    protected open fun replaceToast(msg: String, showlong: Boolean) {
        hideToast()
        val toast =
            Toast.makeText(this, msg, if (showlong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT)
        mToast = toast
        toast.show()
    }

    public final fun refreshUi() {
        val diff = SystemClock.elapsedRealtime() - mLastUiRefresh
        if (diff > Config.REFRESH_UI_INTERVAL) {
            mRefreshUiHandler.removeCallbacks(mRefreshUiRunnable)
            mRefreshUiHandler.postDelayed(mRefreshUiRunnable, 1)
        } else {
            val next = Config.REFRESH_UI_INTERVAL - diff
            mRefreshUiHandler.removeCallbacks(mRefreshUiRunnable)
            mRefreshUiHandler.postDelayed(mRefreshUiRunnable, next)
        }
    }

    private var isCameraFeatureAvailable = false

    private var mStartupPasswordDialog: AlertDialog? = null
    private var mStartupPasswordError: String? = null

    override fun onStart() {
        super.onStart()
        if (mCustomColors != ThemeHelper.applyCustomColors(this)) {
            recreate()
        }
        if (!xmppConnectionServiceBound) {
            if (AppSettings(this).isPasswordOnStartupRequired() &&
                !AppSettings.isSessionUnlocked()
            ) {
                showStartupPasswordPrompt()
            } else {
                connectToBackend()
            }
        } else {
            registerListeners()
            onBackendConnected()
        }
        mUsingEnterKey = usingEnterKey()
        mUseTor = useTor()
        mUseI2P = useI2P()
    }

    private fun showStartupPasswordPrompt() {
        val openDialog = mStartupPasswordDialog
        if (openDialog != null && openDialog.isShowing()) {
            return
        }
        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle(R.string.dialog_startup_password_title)
        builder.setCancelable(false)

        val layout = android.widget.LinearLayout(this)
        layout.orientation = android.widget.LinearLayout.VERTICAL
        val p = (16 * resources.displayMetrics.density).toInt()
        layout.setPadding(p, p, p, p)

        // Show error from a previous failed attempt (wrong password)
        val error = mStartupPasswordError
        if (error != null) {
            val errorView = android.widget.TextView(this)
            errorView.setText(error)
            errorView.setTextColor(0xFFB00020.toInt()) // Material error red
            errorView.setPadding(0, 0, 0, p / 2)
            layout.addView(errorView)
            mStartupPasswordError = null
        }

        val input = com.google.android.material.textfield.TextInputEditText(this)
        input.setHint(R.string.dialog_db_password_hint)
        input.setSingleLine(true)
        input.setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        layout.addView(input)
        builder.setView(layout)

        builder.setPositiveButton(R.string.action_unlock_database, null)
        builder.setNegativeButton(R.string.action_exit) { _, _ ->
            AppSettings.clearSessionPassword()
            finishAffinity()
        }

        val dialog = builder.create()
        mStartupPasswordDialog = dialog
        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val text = input.getText()
            if (text == null || text.length == 0) return@setOnClickListener
            // Copy to char[] so we can zero it; set as session password for DB open
            val entered = CharArray(text.length)
            text.getChars(0, text.length, entered, 0)
            text.clear()
            AppSettings.setSessionPassword(entered)
            java.util.Arrays.fill(entered, '\u0000')
            dialog.dismiss()
            mStartupPasswordDialog = null
            connectToBackend()
        }

        input.requestFocus()
    }

    fun connectToBackend() {
        val intent = Intent(this, XmppConnectionService::class.java)
        intent.setAction("ui")
        try {
            startService(intent)
        } catch (e: IllegalStateException) {
            Log.w(Config.LOGTAG, "unable to start service from " + javaClass.simpleName)
        }
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE)
    }

    /**
     * Called once the service binder is available (and again via callback if the service is still
     * opening the database in its background init thread). Guards against activity lifecycle races.
     */
    private fun handleServiceConnected() {
        if (isFinishing || isDestroyed || !xmppConnectionServiceBound) return
        if (xmppConnectionService.isInitializing()) {
            // DB open is still running in the background - register a callback and wait.
            xmppConnectionService.runWhenDatabaseReady { handleServiceConnected() }
            return
        }
        val err = xmppConnectionService.getCriticalError()
        if (xmppConnectionService.needsPassword() && AppSettings.isSessionUnlocked()) {
            // Service was already running without DB access; session pw now available
            // - restart so onCreate() re-runs with the password.
            unregisterListeners()
            unbindService(mConnection)
            xmppConnectionServiceBound = false
            stopService(Intent(this@XmppActivity, XmppConnectionService::class.java))
            connectToBackend()
        } else if (xmppConnectionService.needsPassword()) {
            // Service locked and user hasn't entered password yet (shouldn't normally
            // reach here; onStart guards it, but handle defensively).
            showStartupPasswordPrompt()
        } else if (err != null &&
            err.reason == uk.xa0.tulkki.xmpp.utils.EncryptionException.Reason.DB_WRONG_KEY &&
            AppSettings(this@XmppActivity).isPasswordOnStartupRequired()
        ) {
            // Wrong startup password - clear and let the user retry
            AppSettings.clearSessionPassword()
            unregisterListeners()
            unbindService(mConnection)
            xmppConnectionServiceBound = false
            stopService(Intent(this@XmppActivity, XmppConnectionService::class.java))
            mStartupPasswordError = getString(R.string.toast_wrong_startup_password)
            showStartupPasswordPrompt()
        } else if (err != null) {
            showCriticalErrorDialog()
        } else {
            registerListeners()
            onBackendConnected()
        }
    }

    override fun onStop() {
        super.onStop()
        if (xmppConnectionServiceBound) {
            unregisterListeners()
            unbindService(mConnection)
            xmppConnectionServiceBound = false
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    protected fun configureCustomNotification(shortcut: ShortcutInfoCompat) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val channel =
            notificationManager.getNotificationChannel(
                UiHost.installed().messagesNotificationChannel(),
                shortcut.getId(),
            )
        if (channel != null && channel.getConversationId() != null) {
            ShortcutManagerCompat.pushDynamicShortcut(this, shortcut)
            openNotificationSettings(shortcut)
        } else {
            UiHost.installed().createConversationChannel(this, shortcut)
            ShortcutManagerCompat.pushDynamicShortcut(this, shortcut)
            openNotificationSettings(shortcut)
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    protected fun openNotificationSettings(shortcut: ShortcutInfoCompat) {
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        intent.putExtra(
            Settings.EXTRA_CHANNEL_ID,
            UiHost.installed().messagesNotificationChannel(),
        )
        intent.putExtra(Settings.EXTRA_CONVERSATION_ID, shortcut.getId())
        startActivity(intent)
    }

    fun hasPgp(): Boolean = xmppConnectionService.getPgpEngine() != null

    fun showInstallPgpDialog() {
        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle(getString(R.string.openkeychain_required))
        builder.setIconAttribute(android.R.attr.alertDialogIcon)
        builder.setMessage(
            Html.fromHtml(
                getString(
                    R.string.openkeychain_required_long,
                    BuildConfig.APP_NAME,
                ),
            ),
        )
        builder.setNegativeButton(getString(uk.xa0.tulkki.data.R.string.cancel), null)
        builder.setNeutralButton(getString(R.string.restart)) { _, _ ->
            if (xmppConnectionServiceBound) {
                unbindService(mConnection)
                xmppConnectionServiceBound = false
            }
            stopService(Intent(this@XmppActivity, XmppConnectionService::class.java))
            finish()
        }
        builder.setPositiveButton(getString(R.string.install)) { _, _ ->
            val uri = Uri.parse("market://details?id=org.sufficientlysecure.keychain")
            val marketIntent = Intent(Intent.ACTION_VIEW, uri)
            val manager = applicationContext.packageManager
            val infos = manager.queryIntentActivities(marketIntent, 0)
            if (infos.isEmpty()) {
                val website = Uri.parse("http://www.openkeychain.org/")
                val browserIntent = Intent(Intent.ACTION_VIEW, website)
                try {
                    startActivity(browserIntent)
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(
                        this,
                        R.string.application_found_to_open_website,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } else {
                startActivity(marketIntent)
            }
            finish()
        }
        builder.create().show()
    }

    protected fun deleteAccount(account: Account) {
        deleteAccount(account, null)
    }

    protected fun deleteAccount(account: Account, postDelete: Runnable?) {
        // `dialog_delete_account.xml` is gone; the checkbox and the two paragraphs are
        // `DeleteAccountDialog`, and the three side effects are the same code, unchanged. The two
        // facts the old code held in the view - that the button says "please wait" and is disabled
        // while an unregistration is in flight, and that the checkbox is disabled with it - live in
        // the state object the host hands the dialog and writes back to on a failed result.
        val state = DeleteAccountDialogState()
        showTulkkiDialog { dismiss ->
            DeleteAccountDialog(
                titleRes = R.string.mgmt_account_delete,
                messageRes = R.string.mgmt_account_delete_confirm_text,
                checkboxRes = R.string.delete_from_server,
                confirmRes = R.string.delete,
                waitingRes = R.string.please_wait,
                state = state,
                onDismiss = dismiss,
                onConfirm = { unregister ->
                    if (unregister) {
                        if (account.isOnlineAndConnected()) {
                            state.waiting = true
                            xmppConnectionService.unregisterAccount(account) { result ->
                                runOnUiThread {
                                    if (result) {
                                        dismiss()
                                        if (postDelete != null) {
                                            postDelete.run()
                                        }
                                        // Tulkki: the gate here read
                                        // `Config.MAGIC_CREATE_DOMAIN != null` (true), so the last
                                        // account being deleted still offers sign-up.
                                        if (AccountRegistry.get().getAccounts().size == 0) {
                                            val intent =
                                                UiHost.installed().signUpIntent(this, false)
                                            intent.setFlags(
                                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                                    Intent.FLAG_ACTIVITY_CLEAR_TASK,
                                            )
                                            startActivity(intent)
                                        }
                                    } else {
                                        state.waiting = false
                                        Toast.makeText(
                                            this,
                                            R.string.could_not_delete_account_from_server,
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                }
                            }
                        } else {
                            Toast.makeText(
                                this,
                                uk.xa0.tulkki.xmpp.R.string.not_connected_try_again,
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    } else {
                        xmppConnectionService.deleteAccount(account)
                        dismiss()
                        // Tulkki: same gate as above; `Config.MAGIC_CREATE_DOMAIN != null`
                        // was true, so deleting the last account still offers sign-up.
                        if (AccountRegistry.get().getAccounts().size == 0) {
                            val intent = UiHost.installed().signUpIntent(this, false)
                            intent.setFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
                            )
                            startActivity(intent)
                        } else if (postDelete != null) {
                            postDelete.run()
                        }
                    }
                },
            )
        }
    }

    protected abstract fun refreshUiReal()

    protected abstract fun onBackendConnected()

    protected fun registerListeners() {
        if (this is uk.xa0.tulkki.xmpp.services.OnConversationUpdate) {
            xmppConnectionService.setOnConversationListChangedListener(this)
        }
        if (this is uk.xa0.tulkki.xmpp.services.OnAccountUpdate) {
            xmppConnectionService.setOnAccountListChangedListener(this)
        }
        if (this is uk.xa0.tulkki.xmpp.services.OnCaptchaRequested) {
            xmppConnectionService.setOnCaptchaRequestedListener(this)
        }
        if (this is uk.xa0.tulkki.xmpp.services.OnRosterUpdate) {
            xmppConnectionService.setOnRosterUpdateListener(this)
        }
        if (this is uk.xa0.tulkki.xmpp.services.OnMucRosterUpdate) {
            xmppConnectionService.setOnMucRosterUpdateListener(this)
        }
        if (this is OnUpdateBlocklist) {
            xmppConnectionService.setOnUpdateBlocklistListener(this)
        }
        if (this is uk.xa0.tulkki.xmpp.services.OnShowErrorToast) {
            xmppConnectionService.setOnShowErrorToastListener(this)
        }
        if (this is OnKeyStatusUpdated) {
            xmppConnectionService.setOnKeyStatusUpdatedListener(this)
        }
        if (this is uk.xa0.tulkki.xmpp.services.OnJingleRtpConnectionUpdate) {
            xmppConnectionService.setOnRtpConnectionUpdateListener(this)
        }
    }

    protected fun unregisterListeners() {
        if (this is uk.xa0.tulkki.xmpp.services.OnConversationUpdate) {
            xmppConnectionService.removeOnConversationListChangedListener(this)
        }
        if (this is uk.xa0.tulkki.xmpp.services.OnAccountUpdate) {
            xmppConnectionService.removeOnAccountListChangedListener(this)
        }
        if (this is uk.xa0.tulkki.xmpp.services.OnCaptchaRequested) {
            xmppConnectionService.removeOnCaptchaRequestedListener(this)
        }
        if (this is uk.xa0.tulkki.xmpp.services.OnRosterUpdate) {
            xmppConnectionService.removeOnRosterUpdateListener(this)
        }
        if (this is uk.xa0.tulkki.xmpp.services.OnMucRosterUpdate) {
            xmppConnectionService.removeOnMucRosterUpdateListener(this)
        }
        if (this is OnUpdateBlocklist) {
            xmppConnectionService.removeOnUpdateBlocklistListener(this)
        }
        if (this is uk.xa0.tulkki.xmpp.services.OnShowErrorToast) {
            xmppConnectionService.removeOnShowErrorToastListener(this)
        }
        if (this is OnKeyStatusUpdated) {
            xmppConnectionService.removeOnNewKeysAvailableListener(this)
        }
        if (this is uk.xa0.tulkki.xmpp.services.OnJingleRtpConnectionUpdate) {
            xmppConnectionService.removeRtpConnectionUpdateListener(this)
        }
    }

    public override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.getItemId()
        if (id == R.id.action_settings) {
            startActivity(Intent(this, uk.xa0.tulkki.ui.activity.SettingsActivity::class.java))
        } else if (id == R.id.action_privacy_policy) {
            openPrivacyPolicy()
        } else if (id == R.id.action_accounts) {
            AccountUtils.launchManageAccounts(this)
        } else if (id == R.id.action_account) {
            // Pair 11 (D4): this was `AccountUtils.launchManageAccount(this)`, an island static
            // that existed only for this one `:ui` caller and took an `XmppActivity` to do two
            // things the activity can do itself. Bucket 2 of round 155's ruling: the caller does
            // the view work. C5-D: `getFirst` answers the island's AccountRef now and
            // `switchToAccount` reads a model.
            switchToAccount(
                AccountUtils.getFirst(AccountRegistry.get().getAccounts())
                    ?: throw NullPointerException(),
            )
        } else if (id == android.R.id.home) {
            finish()
        } else if (id == R.id.action_show_qr_code) {
            showQrCode()
        }
        return super.onOptionsItemSelected(item)
    }

    private fun openPrivacyPolicy() {
        if (BuildConfig.PRIVACY_POLICY == null) {
            return
        }
        val viewPolicyIntent = Intent(Intent.ACTION_VIEW)
        viewPolicyIntent.setData(Uri.parse(BuildConfig.PRIVACY_POLICY))
        try {
            startActivity(viewPolicyIntent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_application_found_to_open_link, Toast.LENGTH_SHORT)
                .show()
        }
    }

    fun selectPresence(
        conversation: Conversation,
        listener: PresenceSelector.OnPresenceSelected,
    ) {
        val contact = conversation.getContact()
        if (conversation.hasValidOtrSession()) {
            val id: SessionID =
                (conversation.getOtrSession() ?: throw NullPointerException()).getSessionID()
            var jid: Jid? = null
            try {
                jid = Jid.of(id.getAccountID() + "/" + id.getUserID())
            } catch (e: IllegalArgumentException) {
                jid = null
            }
            conversation.setNextCounterpart(jid)
            listener.onPresenceSelected()
        } else if (contact.showInRoster() || contact.isSelf()) {
            val presences: Presences = contact.getPresences()
            if (presences.size() == 0) {
                if (contact.isSelf()) {
                    conversation.setNextCounterpart(null)
                    listener.onPresenceSelected()
                } else if (!contact.getOption(Contact.Options.TO) &&
                    !contact.getOption(Contact.Options.ASKING) &&
                    contact.getAccount()?.getStatus() == Account.State.ONLINE
                ) {
                    showAskForPresenceDialog(contact)
                } else if (!contact.getOption(Contact.Options.TO) ||
                    !contact.getOption(Contact.Options.FROM)
                ) {
                    PresenceSelector.warnMutualPresenceSubscription(this, conversation, listener)
                } else {
                    conversation.setNextCounterpart(null)
                    listener.onPresenceSelected()
                }
            } else if (presences.size() == 1) {
                val presence = presences.toResourceArray()[0]
                conversation.setNextCounterpart(
                    PresenceSelector.getNextCounterpart(contact, presence),
                )
                listener.onPresenceSelected()
            } else {
                PresenceSelector.showPresenceSelectionDialog(this, conversation, listener)
            }
        } else {
            showAddToRosterDialog(conversation.getContact())
        }
    }

    @SuppressLint("UnsupportedChromeOsCameraSystemFeature")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        metrics = resources.displayMetrics
        isCameraFeatureAvailable =
            packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        mCustomColors = ThemeHelper.applyCustomColors(this)
    }

    // Widened from `protected` for `ConversationFragment`'s menu read, which Java's package-scoped
    // `protected` allowed; `internal` keeps it inside `:ui`, where every reader is.
    internal fun isCameraFeatureAvailable(): Boolean = isCameraFeatureAvailable

    protected fun isOptimizingBattery(): Boolean {
        val pm = getSystemService(PowerManager::class.java)
        return !pm.isIgnoringBatteryOptimizations(packageName)
    }

    protected fun isAffectedByDataSaver(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
            cm != null &&
                cm.isActiveNetworkMetered() &&
                UiHost.installed().restrictBackgroundStatus(cm) ==
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        } else {
            false
        }
    }

    private fun usingEnterKey(): Boolean =
        getBooleanPreference("display_enter_key", R.bool.display_enter_key)

    private fun useTor(): Boolean =
        getBooleanPreference("use_tor", uk.xa0.tulkki.xmpp.R.bool.use_tor)

    private fun useI2P(): Boolean =
        getBooleanPreference("use_i2p", uk.xa0.tulkki.xmpp.R.bool.use_i2p)

    // Widened from `protected`: `ConversationFragment.storeRecentlyUsedQuickAction` reads it through
    // the activity, which Java's package-scoped `protected` allowed. `internal` reaches `:ui` only.
    internal fun getPreferences(): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(applicationContext)

    fun getBooleanPreference(name: String, @BoolRes res: Int): Boolean =
        getPreferences().getBoolean(name, resources.getBoolean(res))

    fun startCommand(account: Account?, jid: Jid?, node: String?) {
        val intent = Intent(this, ConversationListActivity::class.java)
        intent.setAction(ConversationListActivity.ACTION_VIEW_CONVERSATION)
        intent.putExtra(
            ConversationListActivity.EXTRA_CONVERSATION,
            xmppConnectionService
                .findOrCreateConversation(
                    account ?: throw NullPointerException(),
                    jid ?: throw NullPointerException(),
                    false,
                    false,
                )
                .getUuid(),
        )
        intent.putExtra(ConversationListActivity.EXTRA_POST_INIT_ACTION, "command")
        intent.putExtra(ConversationListActivity.EXTRA_NODE, node)
        intent.putExtra(ConversationListActivity.EXTRA_JID, jid as CharSequence)
        intent.setFlags(intent.getFlags() or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
    }

    open fun colorCodeAccounts(): Boolean = AccountRegistry.get().getAccounts().size > 1

    @JvmSuppressWildcards
    open fun populateWithOrderedConversationList(list: MutableList<Conversation>) {
        xmppConnectionService.populateWithOrderedConversationList(
            list as MutableList<uk.xa0.tulkki.xmpp.refs.ConversationRef>,
        )
    }

    /**
     * The live conversation a snapshot names, or `null`. Both seams' first lookup, and the one
     * place this class narrows the service to a model type.
     */
    private fun conversationEntity(uuid: String?): Conversation? =
        if (uuid == null) {
            null
        } else {
            xmppConnectionService.findConversationByUuid(uuid) as Conversation?
        }

    /**
     * The facts a conversation-list row cannot read from a snapshot, answered from the service's
     * own live state.
     */
    fun conversationListFacts(): PerProcess = conversationListFacts

    private val conversationListFacts: PerProcess = LiveConversationFacts()

    /** One answer per fact, each from the object that owns it. */
    inner class LiveConversationFacts : PerProcess {

        private fun entity(uuid: String?): Conversation? = conversationEntity(uuid)

        override fun mutedOccupant(message: MessageSnapshot): Boolean {
            val status = message.status
            if (status == null ||
                status != Message.STATUS_RECEIVED.toLong() ||
                message.occupantId == null
            ) {
                return false
            }
            val conversation = entity(message.conversationId) ?: return false
            val account = conversation.getAccount() ?: return false
            if (conversation.getMode() != Conversation.MODE_MULTI) {
                return false
            }
            return xmppConnectionService.isMucUserMuted(
                account.getUuid(),
                "" + conversation.getJid(),
                message.occupantId,
            )
        }

        override fun moderated(message: MessageSnapshot): Boolean {
            val conversation = entity(message.conversationId)
            val row =
                if (conversation == null) {
                    null
                } else {
                    conversation.findMessageWithUuid(message.id)
                }
            return row != null && row.getModerated() != null
        }

        override fun transfer(message: MessageSnapshot): UiTransferState =
            UiTransferState.None

        override fun unreadCount(conversation: ConversationSnapshot): Int {
            val row = entity(conversation.id)
            return if (row == null) 0 else row.unreadCount(xmppConnectionService)
        }

        override fun withSelf(conversation: ConversationSnapshot): Boolean {
            val row = entity(conversation.id)
            return row != null && row.withSelf()
        }

        override fun ongoingCall(conversation: ConversationSnapshot): Boolean {
            val row = entity(conversation.id)
            if (row == null || row.getContact() == null || row.getMode() == Conversation.MODE_MULTI) {
                return false
            }
            return xmppConnectionService
                .getJingleConnectionManager()
                .getOngoingRtpConnection(row.getContact())
                .isPresent()
        }

        /**
         * The presence dot, mapped from the two facts `PresenceIndicator.setStatus` read.
         */
        override fun presence(conversation: ConversationSnapshot): UiPresence {
            val shown = getBooleanPreference("show_contact_status", R.bool.show_contact_status)
            val row = entity(conversation.id) ?: return UiPresence.UNKNOWN
            val contact = row.getContact() ?: return UiPresence.UNKNOWN
            val account = contact.getAccount()
            return UiPresence.of(
                contact.shownStatus,
                account != null && account.isOnlineAndConnected(),
                shown,
            )
        }

        /**
         * The notification mark: the call the row already asks about, then the raw `muted_till`
         * attribute and finally the conversation's own `alwaysNotify`.
         */
        override fun notification(conversation: ConversationSnapshot): UiNotification {
            val row = entity(conversation.id) ?: return UiNotification.NONE
            return UiNotification.of(
                ongoingCall(conversation),
                row.getLongAttribute(Conversation.ATTRIBUTE_MUTED_TILL, 0),
                System.currentTimeMillis(),
                row.alwaysNotify(),
            )
        }

        /**
         * A room's four name candidates, in `Conversation.getName`'s own order but not composed
         * here.
         */
        override fun roomName(conversation: ConversationSnapshot): RoomName? {
            val row = entity(conversation.id)
            if (row == null || row.getMode() != Conversation.MODE_MULTI) {
                return null
            }
            val bookmark = row.getBookmark()
            return RoomName(
                row.getMucOptions().getName(),
                row.getMucOptions().getSubject(),
                if (bookmark == null) null else bookmark.getBookmarkName(),
                row.getMucOptions().createNameFromParticipants(),
            )
        }

        /**
         * The account line. The tree's rule is the owner's `show_own_accounts` preference and
         * nothing else.
         */
        override fun accountLine(conversation: ConversationSnapshot): String? {
            val row = entity(conversation.id)
            val shown = getBooleanPreference("show_own_accounts", R.bool.show_own_accounts)
            val account = row?.getAccount()
            if (account == null) {
                return UiAccountLine.of(shown, null)
            }
            return UiAccountLine.of(shown, account.getJid().asBareJid().toString())
        }
    }

    /**
     * The facts a conversation row cannot read from a snapshot, answered from the live model and
     * the two stores that hold them.
     */
    fun messageFacts(): MessageFacts = messageFacts

    private val messageFacts: MessageFacts = LiveMessageFacts()

    /**
     * Tulkki: the snapshots of the conversations whose rows a Compose list is drawing, by
     * conversation and then by row id.
     *
     * <p>It exists for the one seam answer the projection cannot make for itself: a reply's quote
     * names a row (the payload-tree read, `LiveMessageFacts.quoted`) and needs that row's *snapshot*,
     * and Room's blocking `MessageSnapshots.readOne` refuses the composition's thread. So the rows the
     * host already reads off the main thread for the drawn list are cached here from the same write
     * the session takes (`ConversationHost.MessagesSession.onRows`, installed by
     * `ConversationFragment`), and the seam answers from them. Written in that write and read on the
     * composition's thread, so a projection never sees a cache a write behind the rows it draws. The
     * fragment drops a conversation's entry when it releases its list, so this holds the drawn
     * conversations rather than every conversation ever opened.
     */
    private var messageRows: Map<String, Map<String, MessageSnapshot>> = emptyMap()

    /** Replace one conversation's cached rows with the host's latest read. */
    fun cacheMessageRows(conversation: String, rows: List<MessageSnapshot>) {
        messageRows = messageRows + (conversation to rows.associateBy { it.id })
    }

    /** Drop one conversation's cached rows when the list that drew them is released. */
    fun clearMessageRows(conversation: String) {
        messageRows = messageRows - conversation
    }

    /** One answer per fact, each from the object that owns it. */
    inner class LiveMessageFacts : MessageFacts {

        /** The live row a snapshot names, inside the live conversation it belongs to, or `null`. */
        private fun row(message: MessageSnapshot): Message? {
            val conversation = conversationEntity(message.conversationId) ?: return null
            return conversation.findMessageWithUuid(message.id)
        }

        /**
         * The row the reply answers, as a snapshot: `MessageAdapter.replyReference`'s own
         * resolution, answered from the rows the host read off the main thread.
         *
         * <p>**The database read is not here any more, and it could not be.** The projection that
         * asks this runs on the composition's thread, and Room's blocking `MessageSnapshots.readOne`
         * refuses that thread (`performBlocking`'s own `assertNotMainThread`), so the old body threw
         * for every reply row. The row this answers is one of the conversation's own snapshots and
         * the host already reads them off the main thread for the drawn list (`ConversationFragment`
         * hands them to [cacheMessageRows]); this resolves the *reference* from the live payload tree
         * and takes the *snapshot* from that cache, so the composition never opens the database.
         * Before the first read lands the cache is empty and this answers `null` - the projection
         * draws [MessageFacts.replyFallback]'s unresolved quote, which is the safe direction, and the
         * next recomposition resolves it.
         */
        override fun quoted(message: MessageSnapshot): MessageSnapshot? {
            val conversation = conversationEntity(message.conversationId) ?: return null
            val row = conversation.findMessageWithUuid(message.id) ?: return null
            val resolved = row.getInReplyTo()
            val referenced =
                if (resolved != null) {
                    resolved
                } else {
                    // The payload is an island type, so it is held as `var` and only chained:
                    // naming it would move `ui-reaches-island` back up at this symbol.
                    val reply = row.getReply()
                    val id = if (reply == null) null else reply.getAttribute("id")
                    if (id == null) null else conversation.findMessageWithUuidOrRemoteId(id)
                }
            val uuid = referenced?.getUuid() ?: return null
            val conversationId = message.conversationId ?: return null
            return messageRows[conversationId]?.get(uuid)
        }

        /**
         * The live half of the row's encryption.
         */
        override fun crypto(message: MessageSnapshot): CryptoPhase {
            val encryption = message.encryption ?: return CryptoPhase.NONE
            if (encryption == Message.ENCRYPTION_PGP.toLong()) {
                val conversation = conversationEntity(message.conversationId)
                val account = conversation?.getAccount()
                if (account != null &&
                    account.isPgpDecryptionServiceConnected() &&
                    !account.hasPendingPgpIntent(conversation)
                ) {
                    return CryptoPhase.PENDING
                }
                return CryptoPhase.NONE
            }
            if (encryption == Message.ENCRYPTION_AXOLOTL.toLong() ||
                encryption == Message.ENCRYPTION_DECRYPTED.toLong() ||
                encryption == Message.ENCRYPTION_OTR.toLong()
            ) {
                return CryptoPhase.DECRYPTED
            }
            return CryptoPhase.NONE
        }

        /**
         * Why this row is not translated, in two steps.
         */
        override fun held(message: MessageSnapshot): HeldSend.HoldReason? {
            val settings = TranslationSettings.get(this@XmppActivity)
            val recorded = settings.activity().reasonFor(message.id)
            if (recorded != null) {
                return recorded
            }
            val status = message.status
            if (status == null || status != Message.STATUS_WAITING.toLong()) {
                return null
            }
            val conversation = conversationEntity(message.conversationId) ?: return null
            val row = conversation.findMessageWithUuid(message.id) ?: return null
            if (!OutgoingTranslation.isHeldForTranslation(
                    row,
                    settings.appLanguage(),
                    settings.interpreter(),
                )
            ) {
                return null
            }
            return HeldSend.localReason(
                settings.hasApiKey(),
                OutgoingTranslation.capReached(settings),
                OutgoingTranslation.languageOf(conversation),
            )
        }

        /**
         * The notes the owner bought for this row.
         */
        override fun review(message: MessageSnapshot): UiReview? {
            val row = row(message) ?: return null
            val review = ReviewStore.of(this@XmppActivity, row)
            if (review.items().isEmpty()) {
                return null
            }
            return UiReview(
                review.items(),
                ReviewMarks.`in`(review, row.getTranslatedBody() ?: throw NullPointerException()),
            )
        }

        /**
         * The row's live transfer, off the connection object the model holds - plus the two outcomes
         * that are not in-flight statuses.
         *
         * <p>A completed transfer is not a status: the transferable is gone once the file is on the
         * phone, so the durable answer is the row's own `relativeFilePath` and the cell draws the file
         * rather than an empty bubble. `STATUS_FAILED` is a status with no case in the vocabulary until
         * now, and it answers [UiTransferState.Failed] - reasonless, because §2.2.1 #5 keeps the typed
         * `TransferFailure` deferred.
         *
         * <p>**The stored reason tells the two terminal outcomes apart, exactly as the projection's own
         * fallback does**: `ERROR_MESSAGE_CANCELLED` is [UiTransferState.Cancelled] and any other reason
         * is [UiTransferState.Failed]. Answering `Failed` for a cancelled transfer would swallow the
         * cancelled case the cell draws as itself, because a host answer takes the earlier branch in
         * `MessageProjection`'s `when` and leaves the fallback unreachable.
         */
        override fun transfer(message: MessageSnapshot): UiTransferState {
            val row = row(message) ?: return UiTransferState.None
            val transferable = row.getTransferable()
            if (transferable == null) {
                localFile(message)?.let { return UiTransferState.Ready(it) }
                return when (message.errorMsg) {
                    null -> UiTransferState.None
                    Message.ERROR_MESSAGE_CANCELLED -> UiTransferState.Cancelled
                    else -> UiTransferState.Failed
                }
            }
            return when (transferable.getStatus()) {
                Transferable.STATUS_OFFER -> UiTransferState.Offered(transferable.getFileSize())
                Transferable.STATUS_DOWNLOADING ->
                    UiTransferState.Downloading(
                        transferable.getProgress(),
                        transferable.getFileSize(),
                    )
                Transferable.STATUS_UPLOADING ->
                    UiTransferState.Uploading(transferable.getProgress())
                Transferable.STATUS_CHECKING,
                Transferable.STATUS_OFFER_CHECK_FILESIZE ->
                    UiTransferState.Checking
                Transferable.STATUS_CANCELLED -> UiTransferState.Cancelled
                Transferable.STATUS_FAILED -> UiTransferState.Failed
                else -> UiTransferState.None
            }
        }

        /**
         * The attachment's own facts a snapshot cannot carry: the file's name out of the
         * `urn:xmpp:sims:1` payload tree - **never the stored body**, which for a file row is a
         * filename or an out-of-band address, and drawing that is the raw-text leak - and the pixels of
         * a ready image, which a pure projection cannot build.
         *
         * <p>[FileBackends.getThumbnailBitmap] is the same reader `loadBitmap` already uses, at the
         * same 288 dp size, and `null` is its honest answer: the cell then draws a named plate, and a
         * ready image is still never an empty bubble.
         */
        override fun attachment(message: MessageSnapshot): AttachmentInHand? {
            val row = row(message) ?: return null
            val name = row.getFileParams().getName()
            val bitmap =
                FileBackends.get().getThumbnailBitmap(row, resources, (metrics.density * 288).toInt())
            return AttachmentInHand(name, bitmap?.asImageBitmap())
        }

        /**
         * The row's local file as a `file://` URI, or `null` when it is not on the phone.
         *
         * <p>The durable source is the snapshot's own `relativeFilePath`, with `fileDeleted` clear;
         * [FileBackends.getFile] is the store's resolver for the absolute location, and a row the live
         * conversation no longer holds answers `null` rather than inventing a path.
         */
        private fun localFile(message: MessageSnapshot): String? {
            if (message.relativeFilePath == null || message.fileDeleted == 1L) {
                return null
            }
            val row = row(message) ?: return null
            return Uri.fromFile(FileBackends.get().getFile(row)).toString()
        }

        /**
         * The English this process holds, read out of the one home the buyers and the reader share.
         */
        override fun english(message: MessageSnapshot): EnglishInHand? =
            EnglishHold.of(message.id)

        /**
         * The row's conversation-language side with its reply fallback removed.
         */
        override fun strippedBody(message: MessageSnapshot): String? {
            val row = row(message)
            return if (row == null) message.bodies.original else row.getBody(true)
        }

        /**
         * The reply's own copy of the text it quotes, for a reference that cannot be resolved: the
         * prefix the sender declared inside the `urn:xmpp:reply:0` fallback, read through
         * `ReplyFallback` - the same read the send path splits a reply with - and never scanned out of
         * the body.
         *
         * The span is payload-tree work, so the composed body is read here for `ReplyFallback.of` to
         * place it: this is the second `getBody` `LeakGuardScanTest`'s allow-list names for this file,
         * `strippedBody` being the first. `null` means "not a reply at all" - a row with neither
         * `getInReplyTo()` nor `getReply()` carries no quote - so the projection draws no quote rather
         * than an empty one; a reply whose span cannot be placed answers the fallback's empty text,
         * which `ReplyQuote.unresolved` covers while the interpreter is on.
         */
        override fun replyFallback(message: MessageSnapshot): String? {
            val row = row(message)
            if (row == null || (row.getInReplyTo() == null && row.getReply() == null)) {
                return null
            }
            return ReplyFallback.of(row.getBody(), EngineHost.installed().replySpan(row)).carried()
        }

        /**
         * Whether a tap may buy this row's translation now.
         */
        override fun canTranslateNow(message: MessageSnapshot): Boolean {
            val settings = TranslationSettings.get(this@XmppActivity)
            return HeldSend.localReason(
                settings.hasApiKey(),
                OutgoingTranslation.capReached(settings),
                settings.appLanguage(),
            ) == null
        }

        /**
         * The pixels a concealed half is drawn as, which this class cannot build.
         */
        override fun smear(message: MessageSnapshot): ImageBitmap? = null
    }

    /**
     * The row avatars, resolved once for a whole read and handed to the screen through
     * `ConversationAvatar`.
     */
    @Volatile private var conversationAvatars: Map<String?, Drawable> = HashMap()

    private val avatarPort: ConversationAvatar =
        object : ConversationAvatar {
            override fun of(conversationUuid: String): Drawable? =
                conversationAvatars.get(conversationUuid)

            override fun shape(): AvatarShape {
                val shape =
                    PreferenceManager.getDefaultSharedPreferences(applicationContext)
                        .getString("avatar_shape", "oval")
                if ("rounded_square" == shape) {
                    return AvatarShape.ROUNDED_SQUARE
                }
                if ("square" == shape) {
                    return AvatarShape.SQUARE
                }
                return AvatarShape.OVAL
            }
        }

    /** The avatar capability the two conversation-list hosts hand their screen. */
    fun conversationAvatars(): ConversationAvatar = avatarPort

    /**
     * Resolve every row's avatar at the list's own size, on the caller's thread.
     */
    @JvmSuppressWildcards
    fun warmConversationAvatars(rows: List<Conversation>) {
        val size =
            resources.getDimension(R.dimen.avatar_on_conversation_overview).toInt()
        val resolved: MutableMap<String?, Drawable> = HashMap()
        for (conversation in rows) {
            try {
                val avatar = avatarService().get(conversation, size, false)
                if (avatar != null) {
                    resolved.put(conversation.getUuid(), avatar)
                }
            } catch (e: RuntimeException) {
                // An avatar is decoration: a row whose image cannot be resolved is drawn without
                // one rather than taking the list down with it.
            }
        }
        conversationAvatars = resolved
    }

    open fun launchStartConversation() {
        StartConversationActivity.launch(this)
    }

    open fun switchToConversation(conversation: Conversation?) {
        switchToConversation(conversation, null)
    }

    fun switchToConversationOnMessage(conversation: Conversation?, messageUuid: String?) {
        switchToConversationOnMessage(conversation, null, messageUuid)
    }

    fun switchToConversationOnMessage(
        conversation: Conversation?,
        thread: String?,
        messageUuid: String?,
    ) {
        switchToConversation(conversation, null, false, null, false, false, null, thread, messageUuid)
    }

    fun switchToConversationAndQuote(conversation: Conversation?, text: String?) {
        switchToConversation(conversation, text, true, null, false, false)
    }

    fun switchToConversation(conversation: Conversation?, text: String?) {
        switchToConversation(conversation, text, false, null, false, false)
    }

    fun switchToConversationDoNotAppend(conversation: Conversation?, text: String?) {
        switchToConversation(conversation, text, false, null, false, true)
    }

    fun highlightInMuc(conversation: Conversation?, nick: String?) {
        switchToConversation(conversation, null, false, nick, false, false)
    }

    fun privateMsgInMuc(conversation: Conversation?, nick: String?) {
        switchToConversation(conversation, null, false, nick, true, false)
    }

    fun switchToConversation(
        conversation: Conversation?,
        text: String?,
        asQuote: Boolean,
        nick: String?,
        pm: Boolean,
        doNotAppend: Boolean,
    ) {
        switchToConversation(conversation, text, asQuote, nick, pm, doNotAppend, null)
    }

    fun switchToConversation(
        conversation: Conversation?,
        text: String?,
        asQuote: Boolean,
        nick: String?,
        pm: Boolean,
        doNotAppend: Boolean,
        postInit: String?,
    ) {
        switchToConversation(
            conversation,
            text,
            asQuote,
            nick,
            pm,
            doNotAppend,
            postInit,
            null,
            null,
        )
    }

    fun switchToConversation(
        conversation: Conversation?,
        text: String?,
        asQuote: Boolean,
        nick: String?,
        pm: Boolean,
        doNotAppend: Boolean,
        postInit: String?,
        thread: String?,
        messageUuid: String?,
    ) {
        if (conversation == null) return
        val intent = Intent(this, ConversationListActivity::class.java)
        intent.setAction(ConversationListActivity.ACTION_VIEW_CONVERSATION)
        intent.putExtra(ConversationListActivity.EXTRA_CONVERSATION, conversation.getUuid())
        intent.putExtra(ConversationListActivity.EXTRA_THREAD, thread)
        if (text != null) {
            intent.putExtra(Intent.EXTRA_TEXT, text)
            if (asQuote) {
                intent.putExtra(ConversationListActivity.EXTRA_AS_QUOTE, true)
            }
        }
        if (nick != null) {
            intent.putExtra(ConversationListActivity.EXTRA_NICK, nick)
            intent.putExtra(ConversationListActivity.EXTRA_IS_PRIVATE_MESSAGE, pm)
        }
        if (doNotAppend) {
            intent.putExtra(ConversationListActivity.EXTRA_DO_NOT_APPEND, true)
        }
        if (messageUuid != null) {
            intent.putExtra(ConversationListActivity.EXTRA_MESSAGE_UUID, messageUuid)
        }
        intent.putExtra(ConversationListActivity.EXTRA_POST_INIT_ACTION, postInit)
        intent.setFlags(intent.getFlags() or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
        finish()
    }

    fun switchToContactDetails(contact: Contact?) {
        switchToContactDetails(contact, null)
    }

    fun switchToContactDetails(contact: Contact?, messageFingerprint: String?) {
        val intent = Intent(this, ContactDetailsActivity::class.java)
        intent.setAction(ContactDetailsActivity.ACTION_VIEW_CONTACT)
        val target = contact ?: throw NullPointerException()
        intent.putExtra(
            EXTRA_ACCOUNT,
            (target.getAccount() ?: throw NullPointerException()).getJid().asBareJid().toString(),
        )
        intent.putExtra("contact", target.getJid().toString())
        intent.putExtra("fingerprint", messageFingerprint)
        startActivity(intent)
    }

    fun switchToAccount(account: Account?, fingerprint: String?) {
        switchToAccount(account, false, fingerprint)
    }

    fun switchToAccount(account: Account?) {
        switchToAccount(account, false, null)
    }

    fun switchToAccount(account: Account?, init: Boolean, fingerprint: String?) {
        val intent = Intent(this, EditAccountActivity::class.java)
        intent.putExtra(
            "jid",
            (account ?: throw NullPointerException()).getJid().asBareJid().toString(),
        )
        intent.putExtra("init", init)
        if (init) {
            intent.setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION,
            )
        }
        if (fingerprint != null) {
            intent.putExtra("fingerprint", fingerprint)
        }
        startActivity(intent)
        if (init) {
            overridePendingTransition(0, 0)
        }
    }

    // Widened from `protected` for `ConversationFragment`'s attach paths, which call it through the
    // activity; `internal` keeps it in `:ui`, the only module that reads it.
    internal fun delegateUriPermissionsToService(uri: Uri) {
        val intent = Intent(this, XmppConnectionService::class.java)
        intent.setAction(Intent.ACTION_SEND)
        intent.setData(uri)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startService(intent)
        } catch (e: Exception) {
            Log.e(Config.LOGTAG, "unable to delegate uri permission", e)
        }
    }

    protected fun inviteToConversation(conversation: Conversation?) {
        startActivityForResult(
            ChooseContactActivity.create(this, conversation ?: throw NullPointerException()),
            REQUEST_INVITE_TO_CONVERSATION,
        )
    }

    // Widened from `protected` for `ConversationFragment`'s encryption-blocked paths, which call it
    // through the activity; `internal` keeps it in `:ui`, the only module that reads it.
    internal fun announcePgp(
        account: Account,
        conversation: Conversation?,
        intent: Intent?,
        onSuccess: Runnable?,
    ) {
        if (account.getPgpId() == 0L) {
            choosePgpSignId(account)
        } else {
            val status = Strings.nullToEmpty(account.getPresenceStatusMessage())
            (xmppConnectionService.getPgpEngine() ?: throw NullPointerException())
                .generateSignature(
                    intent,
                    account,
                    status,
                    object : UiCallback<String> {

                        override fun userInputRequired(
                            pi: PendingIntent?,
                            signature: String,
                        ) {
                            try {
                                startIntentSenderForResult(
                                    (pi ?: throw NullPointerException()).getIntentSender(),
                                    REQUEST_ANNOUNCE_PGP,
                                    null,
                                    0,
                                    0,
                                    0,
                                    UiHost.installed().pgpStartIntentSenderOptions(),
                                )
                            } catch (e: IntentSender.SendIntentException) {
                            }
                        }

                        override fun success(signature: String) {
                            account.setPgpSignature(signature)
                            DatabaseBackend.get().updateAccount(account)
                            xmppConnectionService.sendPresence(account)
                            if (conversation != null) {
                                conversation.setNextEncryption(Message.ENCRYPTION_PGP)
                                xmppConnectionService.updateConversation(conversation)
                                refreshUi()
                            }
                            if (onSuccess != null) {
                                runOnUiThread(onSuccess)
                            }
                        }

                        override fun error(errorCode: Int, signature: String?) {
                            if (errorCode == 0) {
                                account.setPgpSignId(0)
                                account.unsetPgpSignature()
                                DatabaseBackend.get().updateAccount(account)
                                choosePgpSignId(account)
                            } else {
                                displayErrorDialog(errorCode)
                            }
                        }
                    },
                )
        }
    }

    protected fun choosePgpSignId(account: Account) {
        (xmppConnectionService.getPgpEngine() ?: throw NullPointerException())
            .chooseKey(
                account,
                object : UiCallback<Account> {
                    override fun success(a: Account) {}

                    override fun error(errorCode: Int, obj: Account?) {}

                    override fun userInputRequired(pi: PendingIntent?, obj: Account) {
                        try {
                            startIntentSenderForResult(
                                (pi ?: throw NullPointerException()).getIntentSender(),
                                REQUEST_CHOOSE_PGP_ID,
                                null,
                                0,
                                0,
                                0,
                                UiHost.installed().pgpStartIntentSenderOptions(),
                            )
                        } catch (e: IntentSender.SendIntentException) {
                        }
                    }
                },
            )
    }

    fun showCriticalErrorDialog() {
        runOnUiThread {
            val builder = MaterialAlertDialogBuilder(this)
            builder.setTitle(R.string.title_critical_keystore_error)
            builder.setMessage(R.string.message_critical_keystore_error)
            builder.setCancelable(false)
            builder.setPositiveButton(R.string.action_clear_app_data) { _, _ ->
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager?
                if (am != null) {
                    am.clearApplicationUserData()
                }
            }
            builder.setNegativeButton(R.string.action_exit) { _, _ -> finishAffinity() }
            builder.show()
        }
    }

    protected fun displayErrorDialog(errorCode: Int) {
        runOnUiThread {
            val builder = MaterialAlertDialogBuilder(this@XmppActivity)
            builder.setTitle(getString(R.string.error))
            builder.setMessage(errorCode)
            builder.setNeutralButton(R.string.accept, null)
            builder.create().show()
        }
    }

    protected fun showAddToRosterDialog(contact: Contact?) {
        val builder = MaterialAlertDialogBuilder(this)
        val target = contact ?: throw NullPointerException()
        builder.setTitle(target.getJid().toString())
        builder.setMessage(getString(R.string.not_in_roster))
        builder.setNegativeButton(getString(uk.xa0.tulkki.data.R.string.cancel), null)
        builder.setPositiveButton(getString(R.string.add_contact)) { _, _ ->
            target.copySystemTagsToGroups()
            xmppConnectionService.createContact(target, true)
        }
        builder.create().show()
    }

    private fun showAskForPresenceDialog(contact: Contact) {
        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle(contact.getJid().toString())
        builder.setMessage(R.string.request_presence_updates)
        builder.setNegativeButton(uk.xa0.tulkki.data.R.string.cancel, null)
        builder.setPositiveButton(R.string.request_now) { _, _ ->
            if (xmppConnectionServiceBound) {
                xmppConnectionService.sendPresencePacket(
                    contact.getAccount(),
                    xmppConnectionService
                        .getPresenceGenerator()
                        .requestPresenceUpdatesFrom(contact),
                )
            }
        }
        builder.create().show()
    }

    // Widened from `protected` together with `OnValueEdited` below: one signature, one visibility.
    // `quickPasswordEdit` is already `internal` for ConversationFragment, and every caller of the
    // family - the five overloads and the interface - is inside `:ui`.
    internal fun quickEdit(previousValue: String?, @StringRes hint: Int, callback: OnValueEdited) {
        quickEdit(previousValue, callback, hint, false, false)
    }

    internal fun quickEdit(
        previousValue: String?,
        @StringRes hint: Int,
        callback: OnValueEdited,
        permitEmpty: Boolean,
    ) {
        quickEdit(previousValue, callback, hint, false, permitEmpty)
    }

    // Widened from `protected`: `ConversationFragment.enterPassword` calls it through the activity,
    // which Java's package-scoped `protected` allowed. `internal` reaches `:ui` only.
    internal fun quickPasswordEdit(previousValue: String?, callback: OnValueEdited) {
        quickEdit(previousValue, callback, R.string.password, true, false)
    }

    internal fun quickEdit(
        previousValue: String?,
        callback: OnValueEdited,
        @StringRes hint: Int,
        password: Boolean,
        permitEmpty: Boolean,
    ) {
        quickEdit(previousValue, callback, hint, password, permitEmpty, false)
    }

    internal fun quickEdit(
        previousValue: String?,
        callback: OnValueEdited,
        @StringRes hint: Int,
        password: Boolean,
        permitEmpty: Boolean,
        alwaysCallback: Boolean,
    ) {
        quickEdit(previousValue, callback, hint, password, permitEmpty, alwaysCallback, false)
    }

    // `:data`'s `dialog_quickedit.xml`, the `DialogQuickeditBinding` it generated and `:data`'s only
    // layout are gone: the field is `QuickEditDialog`, and the validation the positive button's own
    // listener ran - `alwaysCallback`, `permitEmpty`, and the error that keeps the dialog open - is
    // the composable's now, so the one caller that answers with an error still sees it in the field.
    // `startSelected` selects the prefilled text, and a password field is masked the same way the
    // deleted `setInputType(TYPE_TEXT_VARIATION_PASSWORD)` masked it.
    internal fun quickEdit(
        previousValue: String?,
        callback: OnValueEdited,
        @StringRes hint: Int,
        password: Boolean,
        permitEmpty: Boolean,
        alwaysCallback: Boolean,
        startSelected: Boolean,
    ) {
        showTulkkiDialog { dismiss ->
            QuickEditDialog(
                previousValue = previousValue,
                hintRes = hint,
                password = password,
                permitEmpty = permitEmpty,
                alwaysCallback = alwaysCallback,
                startSelected = startSelected,
                onDismiss = dismiss,
                onValueEdited = { callback.onValueEdited(it) },
            )
        }
    }

    fun hasStoragePermission(requestCode: Int): Boolean {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    requestCode,
                )
                false
            } else {
                true
            }
        } else {
            true
        }
    }

    @Synchronized
    fun startActivityWithCallback(intent: Intent, cb: ValueCallback<Array<Uri>>) {
        val peek = activityCallbacks.peek()
        val index = if (peek == null) 1 else peek.first + 1
        activityCallbacks.add(Pair(index, cb))
        startActivityForResult(intent, index)
    }

    protected override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_INVITE_TO_CONVERSATION && resultCode == RESULT_OK) {
            mPendingConferenceInvite = ConferenceInvite.parse(data)
            val invite = mPendingConferenceInvite
            if (xmppConnectionServiceBound && invite != null) {
                if (invite.execute(this)) {
                    val toast = Toast.makeText(this, R.string.creating_conference, Toast.LENGTH_LONG)
                    mToast = toast
                    toast.show()
                }
                mPendingConferenceInvite = null
            }
        } else if (resultCode == RESULT_OK) {
            for (cb in ArrayList(activityCallbacks)) {
                if (cb.first == requestCode) {
                    activityCallbacks.remove(cb)
                    val dataUris = ArrayList<Uri>()
                    val dataString = data?.getDataString()
                    if (dataString != null) {
                        dataUris.add(Uri.parse(dataString))
                    } else {
                        val clip = data?.getClipData()
                        if (clip != null) {
                            for (i in 0 until clip.getItemCount()) {
                                dataUris.add(clip.getItemAt(i).getUri())
                            }
                        }
                    }
                    cb.second.onReceiveValue(dataUris.toTypedArray())
                }
            }
        }
    }

    fun copyTextToClipboard(text: String, labelResId: Int): Boolean =
        copyTextToClipboard(text, labelResId, false)

    /**
     * Tulkki: `sensitive` marks the clip so that API 33 and later neither preview it in the paste
     * UI nor keep it in the clipboard history.
     */
    fun copyTextToClipboard(text: String, labelResId: Int, sensitive: Boolean): Boolean {
        val mClipBoardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager?
        val label = resources.getString(labelResId)
        if (mClipBoardManager != null) {
            val mClipData = ClipData.newPlainText(label, text)
            // ClipDescription carries a PersistableBundle, not a Bundle.
            if (sensitive && mClipData.getDescription() != null) {
                val description = mClipData.getDescription()
                var extras = description.getExtras()
                if (extras == null) {
                    extras = PersistableBundle()
                    description.setExtras(extras)
                }
                extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
            mClipBoardManager.setPrimaryClip(mClipData)
            return true
        }
        return false
    }

    protected fun manuallyChangePresence(): Boolean =
        getBooleanPreference(
            AppSettings.MANUALLY_CHANGE_PRESENCE,
            uk.xa0.tulkki.xmpp.R.bool.manually_change_presence,
        )

    fun unicoloredBG(): Boolean =
        getBooleanPreference("unicolored_chatbg", R.bool.use_unicolored_chatbg)

    /**
     * The address this screen can share. One URI now: the `xmpp:` form the tree builds, because
     * the web-invite page was a third party's host and there is no Tulkki page to put in its
     * place.
     */
    protected open fun getShareableUri(): String? = null

    protected fun shareLink() {
        val uri = getShareableUri()
        if (uri == null || uri.isEmpty()) {
            return
        }
        val intent = Intent(Intent.ACTION_SEND)
        intent.setType("text/plain")
        intent.putExtra(Intent.EXTRA_TEXT, uri)
        try {
            startActivity(Intent.createChooser(intent, getText(R.string.share_uri_with)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_application_to_share_uri, Toast.LENGTH_SHORT).show()
        }
    }

    protected fun launchOpenKeyChain(keyId: Long) {
        val pgp = xmppConnectionService.getPgpEngine()
        try {
            startIntentSenderForResult(
                ((pgp ?: throw NullPointerException()).getIntentForKey(keyId)
                        ?: throw NullPointerException("no OpenKeychain result intent"))
                    .getIntentSender(),
                0,
                null,
                0,
                0,
                0,
                UiHost.installed().pgpStartIntentSenderOptions(),
            )
        } catch (e: Throwable) {
            Log.d(Config.LOGTAG, "could not launch OpenKeyChain", e)
            Toast.makeText(
                this@XmppActivity,
                uk.xa0.tulkki.crypto.R.string.openpgp_error,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        SettingsUtils.applyScreenshotSetting(this)
    }

    public override fun onPause() {
        super.onPause()
    }

    public override fun onMenuOpened(id: Int, menu: Menu): Boolean {
        if (id == AppCompatDelegate.FEATURE_SUPPORT_ACTION_BAR && menu != null) {
            MenuDoubleTabUtil.recordMenuOpen()
        }
        return super.onMenuOpened(id, menu)
    }

    protected fun showQrCode() {
        val uri = getShareableUri()
        if (uri != null) {
            showQrCode(uri)
            return
        }

        val accounts = AccountRegistry.get().getAccounts()
        if (accounts.size < 1) return

        if (accounts.size == 1) {
            showQrCode(accounts.get(0).getShareableUri())
            return
        }

        val selectedAccount = AtomicReference(accounts.get(0))
        val alertDialogBuilder = MaterialAlertDialogBuilder(this)
        alertDialogBuilder.setTitle(R.string.choose_account)
        val asStrings = accounts.map { it.getJid().asBareJid().toString() }.toTypedArray()
        alertDialogBuilder.setSingleChoiceItems(asStrings, 0) { _, which ->
            selectedAccount.set(accounts.get(which))
        }
        alertDialogBuilder.setNegativeButton(uk.xa0.tulkki.data.R.string.cancel, null)
        alertDialogBuilder.setPositiveButton(R.string.ok) { _, _ ->
            showQrCode(selectedAccount.get().getShareableUri())
        }
        alertDialogBuilder.create().show()
    }

    // Widened from `protected`: `ConversationFragment`'s context menu shows a contact's or the
    // account's QR code through the activity, which Java's package-scoped `protected` allowed. The
    // no-argument overload stays `protected`; nothing outside this class calls it.
    internal fun showQrCode(uri: String?) {
        if (uri == null || uri.isEmpty()) {
            return
        }
        val size = Point()
        windowManager.getDefaultDisplay().getSize(size)
        val width = Math.min(size.x, size.y)
        val black: Int
        val white: Int
        if (Activities.isNightMode(this)) {
            black =
                MaterialColors.getColor(
                    this,
                    com.google.android.material.R.attr.colorSurfaceContainerHighest,
                    "No surface color configured",
                )
            white =
                MaterialColors.getColor(
                    this,
                    com.google.android.material.R.attr.colorSurfaceInverse,
                    "No inverse surface color configured",
                )
        } else {
            black =
                MaterialColors.getColor(
                    this,
                    com.google.android.material.R.attr.colorSurfaceInverse,
                    "No inverse surface color configured",
                )
            white =
                MaterialColors.getColor(
                    this,
                    com.google.android.material.R.attr.colorSurfaceContainerHighest,
                    "No surface color configured",
                )
        }
        val bitmap = UiHost.installed().barcode(uri, width, black, white)
        val view = ImageView(this)
        view.setBackgroundColor(white)
        view.setImageBitmap(bitmap)
        val builder = MaterialAlertDialogBuilder(this)
        builder.setView(view)
        builder.create().show()
    }

    protected fun extractAccount(intent: Intent?): Account? {
        val jid = if (intent != null) intent.getStringExtra(EXTRA_ACCOUNT) else null
        return try {
            if (jid != null) AccountRegistry.get().findAccountByJid(Jid.of(jid)) else null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun avatarService(): uk.xa0.tulkki.xmpp.services.AvatarPort =
        xmppConnectionService.getAvatarService()

    fun loadBitmap(message: Message, imageView: ImageView) {
        var bm: Drawable?
        try {
            bm =
                FileBackends.get()
                    .getThumbnail(message, resources, (metrics.density * 288).toInt(), true)
        } catch (e: IOException) {
            bm = null
        }
        if (bm != null) {
            cancelPotentialWork(message, imageView)
            imageView.setImageDrawable(bm)
            imageView.setBackgroundColor(0x00000000)
            if (Build.VERSION.SDK_INT >= 28 && bm is AnimatedImageDrawable) {
                bm.start()
            }
        } else {
            if (cancelPotentialWork(message, imageView)) {
                val task = BitmapWorkerTask(imageView)
                val fallbackThumb =
                    FileBackends.get()
                        .getFallbackThumbnail(message, (metrics.density * 288).toInt(), true)
                imageView.setBackgroundColor(
                    if (fallbackThumb == null) 0xff333333.toInt() else 0x00000000,
                )
                val asyncDrawable =
                    AsyncDrawable(
                        resources,
                        if (fallbackThumb != null) fallbackThumb.getBitmap() else null,
                        task,
                    )
                imageView.setImageDrawable(asyncDrawable)
                try {
                    task.execute(message)
                } catch (e: RejectedExecutionException) {
                    e.printStackTrace()
                }
            }
        }
    }

    internal fun interface OnValueEdited {
        fun onValueEdited(value: String): String?
    }

    class ConferenceInvite {
        private var uuid: String? = null
        private val jids: MutableList<Jid> = ArrayList()

        companion object {
            @JvmStatic
            fun parse(data: Intent?): ConferenceInvite? {
                val invite = ConferenceInvite()
                invite.uuid = data?.getStringExtra(ChooseContactActivity.EXTRA_CONVERSATION)
                if (invite.uuid == null) {
                    return null
                }
                invite.jids.addAll(
                    ChooseContactActivity.extractJabberIds(data ?: throw NullPointerException()),
                )
                return invite
            }
        }

        fun execute(activity: XmppActivity): Boolean {
            val service = activity.xmppConnectionService
            val conversation = service.findConversationByUuid(this.uuid) as Conversation?
            if (conversation == null) {
                return false
            }
            if (conversation.getMode() == Conversation.MODE_MULTI) {
                for (jid in jids) {
                    service.invite(conversation, jid)
                }
                return false
            } else {
                jids.add((conversation.getJid() ?: throw NullPointerException()).asBareJid())
                return service.createAdhocConference(
                    conversation.getAccount() ?: throw NullPointerException(),
                    null,
                    jids,
                    activity.adhocCallback,
                )
            }
        }
    }

    private class BitmapWorkerTask internal constructor(imageView: ImageView) :
        AsyncTask<Message, Void, Drawable>() {
        private val imageViewReference: WeakReference<ImageView> = WeakReference(imageView)
        internal var message: Message? = null

        override fun doInBackground(vararg params: Message): Drawable? {
            if (isCancelled) {
                return null
            }
            val activity = find(imageViewReference)
            var d: Drawable? = null
            message = params[0]
            try {
                val imageView = imageViewReference.get()
                if (activity != null && imageView != null) {
                    d =
                        FileBackends.get()
                            .getThumbnail(
                                message ?: throw NullPointerException(),
                                imageView.getContext().getResources(),
                                (activity.metrics.density * 288).toInt(),
                                false,
                            )
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
            val imageView = imageViewReference.get()
            if (d == null &&
                activity != null &&
                imageView != null &&
                imageView.getDrawable() is AsyncDrawable &&
                (imageView.getDrawable() as AsyncDrawable).getBitmap() == null
            ) {
                d =
                    FileBackends.get()
                        .getFallbackThumbnail(
                            message ?: throw NullPointerException(),
                            (activity.metrics.density * 288).toInt(),
                            false,
                        )
            }
            return d
        }

        override fun onPostExecute(drawable: Drawable?) {
            if (!isCancelled) {
                val imageView = imageViewReference.get()
                if (imageView != null) {
                    val old = imageView.getDrawable()
                    if (old is AsyncDrawable) {
                        old.clearTask()
                    }
                    if (drawable != null) {
                        imageView.setImageDrawable(drawable)
                    }
                    imageView.setBackgroundColor(
                        if (drawable == null) 0xff333333.toInt() else 0x00000000,
                    )
                    if (Build.VERSION.SDK_INT >= 28 && drawable is AnimatedImageDrawable) {
                        drawable.start()
                    }
                }
            }
        }
    }

    private class AsyncDrawable(
        res: Resources,
        bitmap: Bitmap?,
        bitmapWorkerTask: BitmapWorkerTask,
    ) : BitmapDrawable(res, bitmap) {
        private var bitmapWorkerTaskReference: WeakReference<BitmapWorkerTask>? =
            WeakReference(bitmapWorkerTask)

        @Synchronized
        internal fun getBitmapWorkerTask(): BitmapWorkerTask? {
            val reference = bitmapWorkerTaskReference ?: return null
            return reference.get()
        }

        @Synchronized
        fun clearTask() {
            bitmapWorkerTaskReference = null
        }
    }

    fun isDark(): Boolean {
        val nightModeFlags =
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES
    }

    fun ShowAvatarPopup(user: Avatarable?) {
        // `avatar_dialog.xml` and the `Dialog` it hung from are gone: the face is `AvatarPopupBody`,
        // and this is the launch path the details, channel and MUC-user screens still call. The Java
        // dereference the deleted body made is kept as the one named here.
        val target = user ?: throw NullPointerException()
        showTulkkiDialog { dismiss -> AvatarPopupBody(target, onDismiss = dismiss) }
    }

    private fun hideAvatarPopup() {
        val popup = AvatarPopup
        if (popup != null && popup.isShowing()) {
            popup.cancel()
        }
    }

    companion object {
        const val EXTRA_ACCOUNT = "account"
        const val FRAGMENT_TAG_DIALOG = "dialog"
        const val REQUEST_ANNOUNCE_PGP = 0x0101
        const val REQUEST_INVITE_TO_CONVERSATION = 0x0102
        const val REQUEST_CHOOSE_PGP_ID = 0x0103
        const val REQUEST_BATTERY_OP = 0x49ff
        const val REQUEST_POST_NOTIFICATION = 0x50ff

        @JvmField var staticXmppConnectionService: XmppConnectionService = unsafeNull()

        @JvmStatic
        fun cancelPotentialWork(message: Message, imageView: ImageView): Boolean {
            val bitmapWorkerTask = getBitmapWorkerTask(imageView)
            if (bitmapWorkerTask != null) {
                val oldMessage = bitmapWorkerTask.message
                if (oldMessage == null || message !== oldMessage) {
                    bitmapWorkerTask.cancel(true)
                } else {
                    return false
                }
            }
            return true
        }

        private fun getBitmapWorkerTask(imageView: ImageView?): BitmapWorkerTask? {
            if (imageView != null) {
                val drawable = imageView.getDrawable()
                if (drawable is AsyncDrawable) {
                    return drawable.getBitmapWorkerTask()
                }
            }
            return null
        }

        @JvmStatic
        fun find(viewWeakReference: WeakReference<ImageView>): XmppActivity? {
            val view = viewWeakReference.get()
            return if (view == null) null else find(view)
        }

        @JvmStatic
        fun find(view: View): XmppActivity? {
            var context: Context? = view.getContext()
            while (context is ContextWrapper) {
                if (context is XmppActivity) {
                    return context
                }
                context = context.getBaseContext()
            }
            return null
        }
    }
}

/**
 * A non-null Kotlin field that is `null` at the JVM level until the class assigns it. The Java
 * field shape is load-bearing (`@JvmField` cannot be put on a `lateinit` property), and the
 * compiler inserts no null check for a generic return.
 */
private fun <T> unsafeNull(): T = null as T
