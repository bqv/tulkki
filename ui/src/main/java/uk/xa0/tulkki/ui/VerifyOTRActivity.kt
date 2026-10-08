package uk.xa0.tulkki.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.appcompat.app.AlertDialog
import me.drakeet.support.toast.ToastCompat
import net.java.otr4j.OtrException
import net.java.otr4j.session.Session
import uk.xa0.tulkki.data.R as DataR
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.ui.chrome.ChromeMenuItem
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.utils.CryptoHelper
import uk.xa0.tulkki.xmpp.utils.XmppUri

/**
 * The OTR verification screen: the two fingerprints to compare by hand, or the shared-secret flow
 * that verifies them for the owner.
 *
 * <p>**The layout is gone.** `activity_verify_otr.xml` held the explanation, the manual-verification
 * area with the two fingerprints and their captions, the SMP area with the status line, the hint, the
 * two fields and the two-button bar; the file and the `R.menu.verify_otr` inflation are deleted. The
 * bar is the shared chrome ([TulkkiChrome]) now - the layout had no toolbar at all, so
 * `updateView`'s `actionBar?.setTitle(...)` wrote to a null bar and the chrome is where those titles
 * ([titleRes]) are finally drawn - and the body is [VerifyOtrScreen]. Its two menu items, the QR code
 * and the settings gear, are the chrome's overflow, wired to the same `showQrCode()` and
 * `SettingsActivity` the base class's `onOptionsItemSelected` ran.
 *
 * <p>**The view tree is the state.** Every `findViewById` field is a field of [VerifyOtrScreenState]
 * and every `activateButton`/`deactivateButton` call is a label, an enabled flag and a
 * [VerifyOtrAction] the screen hands back; the two fields' contents are the state's `hintInput` and
 * `secretInput`, which is where the old `mSharedSecretHintEditable.setText(...)`/`getText()` pair
 * went. The branches preserve what the old code left alone as well as what it set - the read-only
 * hint, for one, is only touched by the answer-question branch, exactly as the view was.
 */
class VerifyOTRActivity :
    XmppActivity(),
    uk.xa0.tulkki.xmpp.services.OnConversationUpdate {

    private var mAccount: Account? = null
    private var mConversation: Conversation? = null
    private var mode = MODE_MANUAL_VERIFICATION
    private var mPendingUri: XmppUri? = null

    /** Everything the screen draws, written by the `updateView*` branches. */
    private var screen by mutableStateOf(VerifyOtrScreenState())

    /** The title the chrome draws: the three `actionBar?.setTitle(...)` strings of `updateView`. */
    private var titleRes by mutableStateOf(R.string.manually_verify)

    protected fun initSmp(question: String, secret: String): Boolean {
        val conversation = mConversation ?: throw NullPointerException()
        val session: Session? = conversation.getOtrSession()
        if (session != null) {
            return try {
                session.initSmp(question, secret)
                conversation.smp().status = Conversation.Smp.STATUS_WE_REQUESTED
                conversation.smp().secret = secret
                conversation.smp().hint = question
                true
            } catch (e: OtrException) {
                false
            }
        } else {
            return false
        }
    }

    protected fun abortSmp(): Boolean {
        val conversation = mConversation ?: throw NullPointerException()
        val session: Session? = conversation.getOtrSession()
        if (session != null) {
            return try {
                session.abortSmp()
                conversation.smp().status = Conversation.Smp.STATUS_NONE
                conversation.smp().hint = null
                conversation.smp().secret = null
                true
            } catch (e: OtrException) {
                false
            }
        } else {
            return false
        }
    }

    protected fun respondSmp(question: String, secret: String): Boolean {
        val conversation = mConversation ?: throw NullPointerException()
        val session: Session? = conversation.getOtrSession()
        if (session != null) {
            return try {
                session.respondSmp(question, secret)
                true
            } catch (e: OtrException) {
                false
            }
        } else {
            return false
        }
    }

    /** The deleted `mVerifyFingerprintListener`, the positive button of the verify dialog. */
    private fun verifyFingerprint() {
        (mConversation ?: throw NullPointerException()).verifyOtrFingerprint()
        xmppConnectionService.syncRosterToDisk(
            (mConversation ?: throw NullPointerException()).getAccount() ?: throw NullPointerException(),
        )
        ToastCompat.makeText(this, R.string.verified, Toast.LENGTH_SHORT).show()
        finish()
    }

    /** The deleted `mCreateSharedSecretListener`, reading the two fields out of the state. */
    private fun createSharedSecret() {
        if (!isAccountOnline()) {
            return
        }
        if (screen.hintInput.trim { it <= ' ' }.isEmpty()) {
            screen =
                screen.copy(
                    hintError = R.string.shared_secret_hint_should_not_be_empty,
                    focusHint = screen.focusHint + 1,
                )
        } else if (screen.secretInput.trim { it <= ' ' }.isEmpty()) {
            screen =
                screen.copy(
                    secretError = R.string.shared_secret_can_not_be_empty,
                    focusSecret = screen.focusSecret + 1,
                )
        } else {
            screen = screen.copy(secretError = null, hintError = null)
            initSmp(screen.hintInput, screen.secretInput)
            updateView()
        }
    }

    /** The deleted `mCancelSharedSecretListener`. */
    private fun cancelSharedSecret() {
        if (isAccountOnline()) {
            abortSmp()
            updateView()
        }
    }

    /** The deleted `mRespondSharedSecretListener`. */
    private fun respondSharedSecret() {
        if (isAccountOnline()) {
            respondSmp(screen.hintInput, screen.secretInput)
            updateView()
        }
    }

    /** The deleted `mRetrySharedSecretListener`. */
    private fun retrySharedSecret() {
        val conversation = mConversation ?: throw NullPointerException()
        conversation.smp().status = Conversation.Smp.STATUS_NONE
        conversation.smp().hint = null
        conversation.smp().secret = null
        updateView()
    }

    /** The deleted `mFinishListener`. */
    private fun finishSmp() {
        val conversation = mConversation ?: throw NullPointerException()
        conversation.smp().status = Conversation.Smp.STATUS_NONE
        finish()
    }

    /** One bar button, which the screen names rather than holding the listener. */
    private fun dispatch(action: VerifyOtrAction) {
        when (action) {
            VerifyOtrAction.CreateSecret -> createSharedSecret()
            VerifyOtrAction.CancelSecret -> cancelSharedSecret()
            VerifyOtrAction.RespondSecret -> respondSharedSecret()
            VerifyOtrAction.RetrySecret -> retrySharedSecret()
            VerifyOtrAction.Finish -> finishSmp()
            VerifyOtrAction.Verify -> showManuallyVerifyDialog()
            VerifyOtrAction.None -> Unit
        }
    }

    protected fun verifyWithUri(uri: XmppUri): Boolean {
        val conversation = mConversation ?: throw NullPointerException()
        val contact = conversation.getContact()
        if (conversation.getContact().getJid().equals(uri.getJid()) && uri.hasFingerprints()) {
            xmppConnectionService.verifyFingerprints(contact, uri.getFingerprints())
            ToastCompat.makeText(this, R.string.verified, Toast.LENGTH_SHORT).show()
            updateView()
            return true
        } else {
            ToastCompat.makeText(this, R.string.could_not_verify_fingerprint, Toast.LENGTH_SHORT)
                .show()
            return false
        }
    }

    protected fun isAccountOnline(): Boolean {
        return if ((mAccount ?: throw NullPointerException()).getStatus() != Account.State.ONLINE) {
            ToastCompat.makeText(
                this,
                uk.xa0.tulkki.xmpp.R.string.not_connected_try_again,
                Toast.LENGTH_SHORT,
            ).show()
            false
        } else {
            true
        }
    }

    protected fun handleIntent(intent: Intent?): Boolean {
        if (intent != null && intent.action.equals(ACTION_VERIFY_CONTACT)) {
            this.mAccount = extractAccount(intent)
            if (this.mAccount == null) {
                return false
            }
            try {
                this.mConversation =
                    this.xmppConnectionService.find(
                        this.mAccount ?: throw NullPointerException(),
                        Jid.of(
                            (intent.extras ?: throw NullPointerException())
                                .getString("contact") ?: throw NullPointerException(),
                        ),
                        Jid.of(
                            (intent.extras ?: throw NullPointerException())
                                .getString("counterpart") ?: throw NullPointerException(),
                        ),
                    ) as Conversation?
                if (this.mConversation == null) {
                    return false
                }
            } catch (ignored: IllegalArgumentException) {
                ignored.printStackTrace()
                return false
            } catch (e: Exception) {
                e.printStackTrace()
                return false
            }
            this.mode = intent.getIntExtra("mode", MODE_MANUAL_VERIFICATION)
            // todo scan OTR fingerprint
            if (this.mode == MODE_SCAN_FINGERPRINT) {
                Log.d(Config.LOGTAG, "Scan OTR fingerprint is not implemented in this version")
                // new IntentIntegrator(this).initiateScan();
                return false
            }
            return true
        } else {
            return false
        }
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        // todo onActivityResult for OTR scan
        Log.d(Config.LOGTAG, "Scan OTR fingerprint result is not implemented in this version")
        super.onActivityResult(requestCode, resultCode, intent)
    }

    protected override fun onBackendConnected() {
        val pendingUri = mPendingUri
        if (handleIntent(intent)) {
            updateView()
        } else if (pendingUri != null) {
            verifyWithUri(pendingUri)
            finish()
            mPendingUri = null
        }
        setIntent(null)
    }

    protected fun updateView() {
        val conversation = mConversation
        if (conversation != null && conversation.hasValidOtrSession()) {
            screen = screen.copy(explanation = R.string.no_otr_session_found)
            when (this.mode) {
                MODE_ASK_QUESTION -> {
                    titleRes = R.string.ask_question
                    this.updateViewAskQuestion()
                }
                MODE_ANSWER_QUESTION -> {
                    titleRes = R.string.smp_requested
                    this.updateViewAnswerQuestion()
                }
                else -> {
                    titleRes = R.string.manually_verify
                    this.updateViewManualVerification()
                }
            }
        } else {
            screen = screen.copy(showManual = false, showSmp = false)
        }
    }

    protected fun updateViewManualVerification() {
        val account = mAccount ?: throw NullPointerException()
        val conversation = mConversation ?: throw NullPointerException()
        screen =
            screen.copy(
                explanation = R.string.manual_verification_explanation,
                showManual = true,
                showSmp = false,
                yourFingerprint = CryptoHelper.prettifyFingerprint(account.getOtrFingerprint()),
                remoteFingerprint =
                    CryptoHelper.prettifyFingerprint(conversation.getOtrFingerprint()),
            )
        if (conversation.isOtrFingerprintVerified()) {
            screen =
                screen.copy(
                    rightLabel = R.string.verified,
                    rightEnabled = false,
                    rightAction = VerifyOtrAction.None,
                    leftLabel = DataR.string.cancel,
                    leftEnabled = true,
                    leftAction = VerifyOtrAction.Finish,
                )
        } else {
            screen =
                screen.copy(
                    leftLabel = DataR.string.cancel,
                    leftEnabled = true,
                    leftAction = VerifyOtrAction.Finish,
                    rightLabel = R.string.verify,
                    rightEnabled = true,
                    rightAction = VerifyOtrAction.Verify,
                )
        }
    }

    protected fun updateViewAskQuestion() {
        val conversation = mConversation ?: throw NullPointerException()
        screen =
            screen.copy(
                showManual = false,
                showSmp = true,
                explanation = R.string.smp_explain_question,
            )
        when (conversation.smp().status) {
            Conversation.Smp.STATUS_WE_REQUESTED -> {
                screen =
                    screen.copy(
                        statusVisible = false,
                        hintEditableVisible = true,
                        hintInput = conversation.smp().hint ?: "",
                        secretVisible = true,
                        secretInput = conversation.smp().secret ?: "",
                        leftLabel = DataR.string.cancel,
                        leftEnabled = true,
                        leftAction = VerifyOtrAction.CancelSecret,
                        rightLabel = R.string.in_progress,
                        rightEnabled = false,
                        rightAction = VerifyOtrAction.None,
                    )
            }
            Conversation.Smp.STATUS_FAILED -> {
                screen =
                    screen.copy(
                        statusVisible = false,
                        hintEditableVisible = true,
                        secretVisible = true,
                        focusSecret = screen.focusSecret + 1,
                        secretError = R.string.secrets_do_not_match,
                        leftLabel = DataR.string.cancel,
                        leftEnabled = false,
                        leftAction = VerifyOtrAction.None,
                        rightLabel = R.string.try_again,
                        rightEnabled = true,
                        rightAction = VerifyOtrAction.RetrySecret,
                    )
            }
            Conversation.Smp.STATUS_VERIFIED -> {
                screen =
                    screen.copy(
                        hintInput = "",
                        hintEditableVisible = false,
                        secretInput = "",
                        secretVisible = false,
                        statusVisible = true,
                        leftLabel = DataR.string.cancel,
                        leftEnabled = false,
                        leftAction = VerifyOtrAction.None,
                        rightLabel = R.string.finish,
                        rightEnabled = true,
                        rightAction = VerifyOtrAction.Finish,
                    )
            }
            else -> {
                screen =
                    screen.copy(
                        statusVisible = false,
                        hintEditableVisible = true,
                        secretVisible = true,
                        leftLabel = DataR.string.cancel,
                        leftEnabled = true,
                        leftAction = VerifyOtrAction.Finish,
                        rightLabel = R.string.ask_question,
                        rightEnabled = true,
                        rightAction = VerifyOtrAction.CreateSecret,
                    )
            }
        }
    }

    protected fun updateViewAnswerQuestion() {
        val conversation = mConversation ?: throw NullPointerException()
        screen =
            screen.copy(
                showManual = false,
                showSmp = true,
                explanation = R.string.smp_explain_answer,
                hintEditableVisible = false,
                hintVisible = true,
                leftLabel = DataR.string.cancel,
                leftEnabled = false,
                leftAction = VerifyOtrAction.None,
            )
        when (conversation.smp().status) {
            Conversation.Smp.STATUS_CONTACT_REQUESTED -> {
                screen =
                    screen.copy(
                        statusVisible = false,
                        hintText = conversation.smp().hint ?: "",
                        rightLabel = R.string.respond,
                        rightEnabled = true,
                        rightAction = VerifyOtrAction.RespondSecret,
                    )
            }
            Conversation.Smp.STATUS_VERIFIED -> {
                screen =
                    screen.copy(
                        hintInput = "",
                        hintEditableVisible = false,
                        hintVisible = false,
                        secretInput = "",
                        secretVisible = false,
                        statusVisible = true,
                        rightLabel = R.string.finish,
                        rightEnabled = true,
                        rightAction = VerifyOtrAction.Finish,
                    )
            }
            else -> {
                screen =
                    screen.copy(
                        focusSecret = screen.focusSecret + 1,
                        secretError = R.string.secrets_do_not_match,
                        rightLabel = R.string.finish,
                        rightEnabled = true,
                        rightAction = VerifyOtrAction.Finish,
                    )
            }
        }
    }

    protected override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The chrome draws its bar behind the system bars and hands the content the space they
        // leave, so the window must not inset itself for them first - on every API level, which is
        // what enableEdgeToEdge settles. See TulkkiChrome for the whole pattern.
        enableEdgeToEdge()

        setTulkkiContent(darkTheme = isDark()) {
            TulkkiChrome(
                title = stringResource(titleRes),
                // The deleted layout had no toolbar, so no arrow existed to copy; the chrome's own
                // affordance is the system back button's own `finish()`.
                onUp = { finish() },
                // The two items of the deleted `R.menu.verify_otr`, both `app:showAsAction="never"`.
                menu =
                    listOf(
                        ChromeMenuItem(stringResource(R.string.show_qr_code)) { showQrCode() },
                        ChromeMenuItem(stringResource(R.string.action_settings)) {
                            startActivity(
                                Intent(this, uk.xa0.tulkki.ui.activity.SettingsActivity::class.java)
                            )
                        },
                    ),
            ) {
                VerifyOtrScreen(
                    state = screen,
                    onHintChange = { value -> screen = screen.copy(hintInput = value) },
                    onSecretChange = { value -> screen = screen.copy(secretInput = value) },
                    onAction = { action -> dispatch(action) },
                )
            }
        }
    }

    private fun showManuallyVerifyDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(R.string.manually_verify)
        builder.setMessage(R.string.are_you_sure_verify_fingerprint)
        builder.setNegativeButton(DataR.string.cancel, null)
        builder.setPositiveButton(R.string.verify) { _, _ -> verifyFingerprint() }
        builder.create().show()
    }

    protected override fun getShareableUri(): String {
        val account = mAccount
        return if (account != null) {
            account.getShareableUri()
        } else {
            ""
        }
    }

    public override fun onConversationUpdate() {
        refreshUi()
    }

    protected override fun refreshUiReal() {
        updateView()
    }

    companion object {
        const val ACTION_VERIFY_CONTACT = "verify_contact"
        const val MODE_SCAN_FINGERPRINT = -0x0502
        const val MODE_ASK_QUESTION = 0x0503
        const val MODE_ANSWER_QUESTION = 0x0504
        const val MODE_MANUAL_VERIFICATION = 0x0505
    }
}

/** One bar button's job, which the old `activateButton` carried as a listener. */
enum class VerifyOtrAction {
    None,
    Finish,
    CancelSecret,
    CreateSecret,
    RespondSecret,
    RetrySecret,
    Verify,
}

/** Everything [VerifyOTRActivity]'s `updateView*` branches draw, and nothing the views held. */
data class VerifyOtrScreenState(
    @StringRes val explanation: Int? = null,
    val showManual: Boolean = true,
    val showSmp: Boolean = true,
    val yourFingerprint: String = "",
    val remoteFingerprint: String = "",
    val statusVisible: Boolean = false,
    val hintVisible: Boolean = false,
    val hintText: String = "",
    val hintEditableVisible: Boolean = true,
    val hintInput: String = "",
    val hintError: Int? = null,
    val secretVisible: Boolean = true,
    val secretInput: String = "",
    val secretError: Int? = null,
    @StringRes val leftLabel: Int? = null,
    val leftEnabled: Boolean = true,
    val leftAction: VerifyOtrAction = VerifyOtrAction.None,
    @StringRes val rightLabel: Int? = null,
    val rightEnabled: Boolean = true,
    val rightAction: VerifyOtrAction = VerifyOtrAction.None,
    val focusHint: Int = 0,
    val focusSecret: Int = 0,
)

/**
 * The verify screen's body: the explanation, the manual area or the SMP area, and the two-button bar
 * the deleted `activity_verify_otr.xml` held.
 *
 * <p>Every size is the deleted layout's own: the scroll body takes the `activity_*_margin` 8 dp
 * margins, the two areas `android:layout_marginTop="16dp"`, the remote fingerprint
 * `android:layout_marginTop="20dp"` and its caption `android:layout_marginBottom="20dp"`, the hint
 * `8dp` under the status line, the editable hint `8dp` above the secret, and the two buttons one
 * `weight` each around a `1dp` `colorSecondaryContainer` rule with the layout's `7dp` top and bottom
 * insets. The fields are [OutlinedTextField]s for the layout's bare `TextInputEditText`+`hint` pair,
 * with the secret taking the password transformation its `inputType="textPassword"` asked for, and
 * the buttons are [FilledTonalButton]s for the `Widget.Material3.Button.TonalButton` style. The
 * `?attr/colorControlNormal` text colour is `onSurfaceVariant`, the role it resolves to in
 * `Theme.Tulkki`.
 */
@Composable
fun VerifyOtrScreen(
    state: VerifyOtrScreenState,
    onHintChange: (String) -> Unit,
    onSecretChange: (String) -> Unit,
    onAction: (VerifyOtrAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hintFocus = remember { FocusRequester() }
    val secretFocus = remember { FocusRequester() }
    LaunchedEffect(state.focusHint) {
        if (state.focusHint > 0) {
            hintFocus.requestFocus()
        }
    }
    LaunchedEffect(state.focusSecret) {
        if (state.focusSecret > 0) {
            secretFocus.requestFocus()
        }
    }

    val hintError = state.hintError
    val hintSupporting: (@Composable () -> Unit)? =
        if (hintError != null) {
            { Text(stringResource(hintError)) }
        } else {
            null
        }
    val secretError = state.secretError
    val secretSupporting: (@Composable () -> Unit)? =
        if (secretError != null) {
            { Text(stringResource(secretError)) }
        } else {
            null
        }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier.weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            val explanation = state.explanation
            if (explanation != null) {
                Text(
                    text = stringResource(explanation),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.showManual) {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    Text(
                        text = state.yourFingerprint,
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.your_fingerprint),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = state.remoteFingerprint,
                        modifier = Modifier.padding(top = 20.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.remote_fingerprint),
                        modifier = Modifier.padding(bottom = 20.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.showSmp) {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    if (state.statusVisible) {
                        Text(
                            text = stringResource(R.string.verified),
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.hintVisible) {
                        Text(
                            text = state.hintText,
                            modifier = Modifier.padding(bottom = 8.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.hintEditableVisible) {
                        OutlinedTextField(
                            value = state.hintInput,
                            onValueChange = onHintChange,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .padding(bottom = 8.dp)
                                    .focusRequester(hintFocus),
                            placeholder = { Text(stringResource(R.string.shared_secret_hint)) },
                            isError = hintError != null,
                            supportingText = hintSupporting,
                            singleLine = true,
                        )
                    }
                    if (state.secretVisible) {
                        OutlinedTextField(
                            value = state.secretInput,
                            onValueChange = onSecretChange,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .padding(top = 8.dp)
                                    .focusRequester(secretFocus),
                            placeholder = { Text(stringResource(R.string.shared_secret_secret)) },
                            isError = secretError != null,
                            supportingText = secretSupporting,
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                        )
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            FilledTonalButton(
                onClick = { onAction(state.leftAction) },
                enabled = state.leftEnabled,
                modifier = Modifier.weight(1f),
            ) {
                Text(text = state.leftLabel?.let { stringResource(it) } ?: "")
            }
            Box(
                modifier =
                    Modifier.width(1.dp)
                        .fillMaxHeight()
                        .padding(vertical = 7.dp)
                        .background(MaterialTheme.colorScheme.secondaryContainer)
            )
            FilledTonalButton(
                onClick = { onAction(state.rightAction) },
                enabled = state.rightEnabled,
                modifier = Modifier.weight(1f),
            ) {
                Text(text = state.rightLabel?.let { stringResource(it) } ?: "")
            }
        }
    }
}
