package uk.xa0.tulkki.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Spannable
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.HashMap
import java.util.concurrent.atomic.AtomicBoolean
import uk.xa0.tulkki.crypto.OmemoSetting
import uk.xa0.tulkki.crypto.axolotl.FingerprintStatus
import uk.xa0.tulkki.data.R as DataR
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.OnKeyStatusUpdated
import uk.xa0.tulkki.xmpp.utils.CryptoHelper
import uk.xa0.tulkki.xmpp.utils.IrregularUnicodeDetector
import uk.xa0.tulkki.xmpp.utils.XmppUri

/**
 * The island's `FetchStatus` is an inherited nested classifier, which Java reaches
 * through the implementing class but Kotlin does not; naming the port's own type by fully qualified
 * name in a file-private alias keeps it off the island-import ratchet.
 */
private typealias FetchStatus = uk.xa0.tulkki.xmpp.crypto.OmemoSessionPort.FetchStatus

/**
 * The trust screen: the owner's own undecided fingerprints, one card per contact whose keys still
 * need a decision, and the error card for a fetch that found nothing.
 *
 * <p>**The layouts are gone.** `activity_trust_keys.xml` held the app bar, the scroll body, the error
 * card, the own-keys card and the `foreign_keys` container, and `keys_card.xml` held one contact's
 * card; both files and the `R.menu.trust_keys` inflation are deleted. The bar is the shared chrome
 * ([TulkkiChrome]) now - its one live item, the QR scan the menu drew `app:showAsAction="always"`, is
 * the chrome's always-visible action, with the same icon, tap and visibility condition - and the body
 * is [TrustKeysScreen]. `setSupportActionBar`, `configureActionBar`, `setTitle`,
 * `Activities.setStatusAndNavigationBarColors` and the [androidx.databinding.DataBindingUtil] binding
 * went with the bar and the layouts.
 *
 * <p>**The fingerprint rows were `contact_key.xml`, which is deleted.** The shared row it held is
 * `uk.xa0.tulkki.ui.omemo.ContactKeyRow` now, drawn by the screens that inflated it; this screen had
 * already drawn its own shape for it ([FingerprintRow]), because `OmemoActivity` and
 * `ContactDetailsActivity` were still inflating the layout when this screen was converted. That row
 * carries the same four facts the shared view carried: the prettified key,
 * the theme's on-surface or on-surface-variant colour by activity, the switch against the pending
 * decision, and the green verified mark for a verified key. The rendered shape here is the one this
 * screen ever asked for - `showTag`, `highlight` and `undecidedNeedEnablement` were all `false` - so
 * the key-type line is not drawn.
 *
 * <p>**The decisions live in the two maps and the screen is a projection of them.** The maps are the
 * old `ownKeysToTrust`/`foreignKeysToTrust`, unchanged; [populateView] rebuilds [screen] from them
 * exactly as it used to rebuild the views, and a switch writes the map and its one row. The fetch
 * report, the pending-fetch lock, the camera hint and the finish/`commitTrusts` flow are the old
 * ones.
 */
class TrustKeysActivity :
    OmemoActivity(),
    OnKeyStatusUpdated {

    private val ownKeysToTrust: MutableMap<String, Boolean> = HashMap()
    private val foreignKeysToTrust: MutableMap<Jid, MutableMap<String, Boolean>> = HashMap()
    private lateinit var contactJids: MutableList<Jid>
    private var mAccount: Account? = null
    private var mConversation: Conversation? = null
    private val mUseCameraHintShown = AtomicBoolean(false)
    private var lastFetchReport = FetchStatus.SUCCESS
    private var mUseCameraHintToast: Toast? = null

    /** Everything the screen draws, rebuilt by [populateView] from the two maps and the service. */
    private var screen by mutableStateOf(TrustKeysScreenState())

    /**
     * The deleted menu's one item, which `app:showAsAction="always"` drew as a QR icon in the bar.
     * The chrome's `actions` slot is the always-visible one, so it carries the same icon and tap
     * conditionally rather than hiding a camera behind the overflow.
     */
    private var scanActionVisible by mutableStateOf(false)

    protected override fun refreshUiReal() {
        populateView()
    }

    protected override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        this.contactJids = ArrayList()
        val intent = this.intent
        val contacts = intent?.getStringArrayExtra("contacts")
        for (jid in contacts ?: emptyArray<String>()) {
            try {
                this.contactJids.add(Jid.of(jid))
            } catch (ignored: IllegalArgumentException) {
            }
        }

        if (savedInstanceState != null) {
            mUseCameraHintShown.set(savedInstanceState.getBoolean("camera_hint_shown", false))
        }

        // The chrome draws its bar behind the system bars and hands the content the space they
        // leave, so the window must not inset itself for them first - on every API level, which is
        // what enableEdgeToEdge settles. See TulkkiChrome for the whole pattern.
        enableEdgeToEdge()

        setTulkkiContent(darkTheme = isDark()) {
            TulkkiChrome(
                // `setTitle` went with the action bar; the chrome draws this instead.
                title = stringResource(R.string.trust_omemo_fingerprints),
                // The arrow `configureActionBar(supportActionBar)` switched on, which ran `finish()`;
                // the cancel button below is the one that also answers `RESULT_CANCELED`.
                onUp = { finish() },
                actions = {
                    if (scanActionVisible) {
                        IconButton(onClick = { scanQrCode() }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_qr_code_scanner_24dp),
                                contentDescription = stringResource(R.string.scan_qr_code),
                            )
                        }
                    }
                },
            ) {
                TrustKeysScreen(
                    state = screen,
                    onOwnKeyChanged = { fingerprint, checked ->
                        applyOwnKeyChanged(fingerprint, checked)
                    },
                    onForeignKeyChanged = { jid, fingerprint, checked ->
                        applyForeignKeyChanged(jid, fingerprint, checked)
                    },
                    onForeignTitleClick = { jid ->
                        val account = mAccount
                        if (account != null) {
                            switchToContactDetails(account.getRoster().getContact(jid))
                        }
                    },
                    onFingerprintClick = { row -> onFingerprintClicked(row.status) },
                    onDisableEncryption = { disableEncryptionDialog() },
                    onCancel = { cancelAndFinish() },
                    onSave = {
                        commitTrusts()
                        finishOk(false)
                    },
                )
            }
        }
    }

    public override fun onSaveInstanceState(savedInstanceState: Bundle) {
        savedInstanceState.putBoolean("camera_hint_shown", mUseCameraHintShown.get())
        super.onSaveInstanceState(savedInstanceState)
    }

    /**
     * The deleted `onCreateOptionsMenu`: the QR icon is visible only while there is something to
     * scan and the device has a camera.
     */
    private fun applyScanActionVisibility() {
        scanActionVisible =
            (!ownKeysToTrust.isEmpty() || foreignActuallyHasKeys()) && isCameraFeatureAvailable()
    }

    /** The deleted `R.id.action_scan_qr_code` branch of `onOptionsItemSelected`. */
    private fun scanQrCode() {
        if (hasPendingKeyFetches()) {
            Toast.makeText(
                this,
                R.string.please_wait_for_keys_to_be_fetched,
                Toast.LENGTH_SHORT,
            ).show()
        } else {
            ScanActivity.scan(this)
        }
    }

    /** The deleted `showCameraToast`, which these two lines are. */
    private fun showCameraToast() {
        mUseCameraHintToast =
            Toast.makeText(this, R.string.use_camera_icon_to_scan_barcode, Toast.LENGTH_LONG)
        mUseCameraHintToast?.setGravity(Gravity.TOP or Gravity.END, 0, actionBarHeight())
        mUseCameraHintToast?.show()
    }

    /**
     * The height the XML action bar carried, which is where the old hint toast was positioned: the
     * `?attr/actionBarSize` the deleted toolbar's `android:minHeight` named.
     */
    private fun actionBarHeight(): Int {
        val value = TypedValue()
        return if (theme.resolveAttribute(android.R.attr.actionBarSize, value, true)) {
            TypedValue.complexToDimensionPixelSize(value.data, resources.displayMetrics)
        } else {
            0
        }
    }

    protected override fun onStop() {
        super.onStop()
        mUseCameraHintToast?.cancel()
    }

    /** The row tap the deleted `contact_key.xml` carried as its three click listeners. */
    private fun onFingerprintClicked(status: FingerprintStatus) {
        when {
            !status.isActive() ->
                replaceToast(getString(R.string.this_device_is_no_longer_in_use), false)
            status.isVerified() ->
                replaceToast(getString(R.string.this_device_has_been_verified), false)
            else -> hideToast()
        }
    }

    /** One own-key switch, which the old `OnCheckedChangeListener` wrote straight into the map. */
    private fun applyOwnKeyChanged(fingerprint: String, checked: Boolean) {
        ownKeysToTrust[fingerprint] = checked
        screen =
            screen.copy(
                ownKeys =
                    screen.ownKeys.map { row ->
                        if (row.fingerprint == fingerprint) row.copy(trusted = checked) else row
                    }
            )
    }

    /** One foreign-key switch, which the old listener wrote into the map and then relocked on. */
    private fun applyForeignKeyChanged(jid: Jid, fingerprint: String, checked: Boolean) {
        synchronized(foreignKeysToTrust) {
            foreignKeysToTrust[jid]?.set(fingerprint, checked)
        }
        screen =
            screen.copy(
                foreignKeys =
                    screen.foreignKeys.map { card ->
                        if (card.jid == jid) {
                            card.copy(
                                rows =
                                    card.rows.map { row ->
                                        if (row.fingerprint == fingerprint) {
                                            row.copy(trusted = checked)
                                        } else {
                                            row
                                        }
                                    }
                            )
                        } else {
                            card
                        }
                    }
            )
        lockOrUnlockAsNeeded()
    }

    protected override fun processFingerprintVerification(uri: XmppUri) {
        val conversation = mConversation
        val account = mAccount
        if (conversation != null &&
            account != null &&
            uri.hasFingerprints() &&
            (account.getAxolotlService() ?: throw NullPointerException())
                .getCryptoTargets(conversation)
                .contains(uri.getJid())
        ) {
            val performedVerification =
                xmppConnectionService.verifyFingerprints(
                    account.getRoster().getContact(uri.getJid() ?: throw NullPointerException()),
                    uri.getFingerprints(),
                )
            val keys = reloadFingerprints()
            if (performedVerification &&
                !keys &&
                !hasNoOtherTrustedKeys() &&
                !hasPendingKeyFetches()
            ) {
                Toast.makeText(
                    this,
                    R.string.all_omemo_keys_have_been_verified,
                    Toast.LENGTH_SHORT,
                ).show()
                finishOk(false)
                return
            } else if (performedVerification) {
                Toast.makeText(this, R.string.verified_fingerprints, Toast.LENGTH_SHORT).show()
            }
        } else {
            reloadFingerprints()
            Log.d(
                Config.LOGTAG,
                "xmpp uri was: " + uri.getJid() + " has Fingerprints: " + uri.hasFingerprints(),
            )
            Toast.makeText(
                this,
                R.string.barcode_does_not_contain_fingerprints_for_this_chat,
                Toast.LENGTH_SHORT,
            ).show()
        }
        populateView()
    }

    private fun populateView() {
        val account = this.mAccount ?: return

        var hasOwnKeys = false
        val ownRows = ArrayList<FingerprintRowState>()
        for (fingerprint in ownKeysToTrust.keys) {
            hasOwnKeys = true
            ownRows.add(
                FingerprintRowState(
                    fingerprint = fingerprint,
                    status = FingerprintStatus.createActive(ownKeysToTrust[fingerprint]),
                    trusted = ownKeysToTrust[fingerprint] ?: false,
                )
            )
        }

        var hasForeignKeys = false
        val foreignCards = ArrayList<ForeignKeysCardState>()
        synchronized(foreignKeysToTrust) {
            for (entry in foreignKeysToTrust.entries) {
                hasForeignKeys = true
                val jid = entry.key
                val fingerprints = entry.value
                val rows = ArrayList<FingerprintRowState>()
                for (fingerprint in fingerprints.keys) {
                    rows.add(
                        FingerprintRowState(
                            fingerprint = fingerprint,
                            status = FingerprintStatus.createActive(fingerprints[fingerprint]),
                            trusted = fingerprints[fingerprint] ?: false,
                        )
                    )
                }
                val noKeysText =
                    if (fingerprints.isEmpty()) {
                        if (hasNoOtherTrustedKeys(jid)) {
                            if (!account.getRoster().getContact(jid).mutualPresenceSubscription()) {
                                getString(R.string.error_no_keys_to_trust_presence)
                            } else {
                                getString(R.string.error_no_keys_to_trust_server_error)
                            }
                        } else {
                            getString(
                                R.string.no_keys_just_confirm,
                                account.getRoster().getContact(jid).getDisplayName(),
                            )
                        }
                    } else {
                        null
                    }
                foreignCards.add(
                    ForeignKeysCardState(
                        jid = jid,
                        title = styledJid(this, jid),
                        rows = rows,
                        noKeysText = noKeysText,
                    )
                )
            }
        }

        if ((hasOwnKeys || foreignActuallyHasKeys()) &&
            isCameraFeatureAvailable() &&
            mUseCameraHintShown.compareAndSet(false, true)
        ) {
            showCameraToast()
        }

        val pending = hasPendingKeyFetches()
        var showOwnKeys = hasOwnKeys
        var showForeignKeys = hasForeignKeys
        var error: TrustKeysErrorState? = null
        if (!pending && !hasForeignKeys && hasNoOtherTrustedKeys()) {
            showOwnKeys = false
            showForeignKeys = false
            val lastReportWasError = lastFetchReport == FetchStatus.ERROR
            val axolotl = account.getAxolotlService() ?: throw NullPointerException()
            val errorFetchingBundle = axolotl.fetchMapHasErrors(contactJids)
            val errorFetchingDeviceList = axolotl.hasErrorFetchingDeviceList(contactJids)
            val anyWithoutMutualPresenceSubscription =
                anyWithoutMutualPresenceSubscription(contactJids)
            val message: String? =
                when {
                    errorFetchingDeviceList -> getString(R.string.error_trustkey_device_list)
                    errorFetchingBundle || lastReportWasError ->
                        getString(R.string.error_trustkey_bundle)
                    else -> null
                }
            val contact = account.getRoster().getContact(contactJids[0])
            error =
                TrustKeysErrorState(
                    message = message,
                    general =
                        getString(
                            R.string.error_trustkey_general,
                            BuildConfig.APP_NAME,
                            contact.getDisplayName(),
                        ),
                    showMutualHint = anyWithoutMutualPresenceSubscription,
                    showDisable = !OmemoSetting.isAlways(),
                )
        }

        screen =
            TrustKeysScreenState(
                ownKeysTitle = account.getJid().asBareJid().toString(),
                ownKeys = ownRows,
                showOwnKeys = showOwnKeys,
                foreignKeys = foreignCards,
                showForeignKeys = showForeignKeys,
                error = error,
                saveLabel = if (pending) R.string.fetching_keys else R.string.done,
                saveEnabled = screen.saveEnabled,
            )

        if (pending) {
            lock()
        } else {
            lockOrUnlockAsNeeded()
        }
        applyScanActionVisibility()
    }

    private fun disableEncryptionDialog() {
        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle(R.string.disable_encryption)
        builder.setMessage(R.string.disable_encryption_message)
        builder.setPositiveButton(R.string.disable_now) { _, _ ->
            (mConversation ?: throw NullPointerException()).setNextEncryption(
                Message.ENCRYPTION_NONE,
            )
            xmppConnectionService.updateConversation(
                mConversation ?: throw NullPointerException(),
            )
            finishOk(true)
        }
        builder.setNegativeButton(DataR.string.cancel, null)
        builder.create().show()
    }

    private fun anyWithoutMutualPresenceSubscription(contactJids: List<Jid>): Boolean {
        val account = mAccount ?: throw NullPointerException()
        for (jid in contactJids) {
            if (!account.getRoster().getContact(jid).mutualPresenceSubscription()) {
                return true
            }
        }
        return false
    }

    private fun foreignActuallyHasKeys(): Boolean {
        synchronized(this.foreignKeysToTrust) {
            for (entry in foreignKeysToTrust.entries) {
                if (entry.value.isNotEmpty()) {
                    return true
                }
            }
        }
        return false
    }

    private fun reloadFingerprints(): Boolean {
        val conversation = mConversation
        val acceptedTargets: MutableList<Jid> =
            if (conversation == null) {
                ArrayList()
            } else {
                ArrayList(conversation.getAcceptedCryptoTargets())
            }
        ownKeysToTrust.clear()
        val account = this.mAccount ?: return false
        val service = (account.getAxolotlService() ?: throw NullPointerException())
        val ownKeysSet = service.getKeysWithTrust(FingerprintStatus.createActiveUndecided())
        for (identityKey in ownKeysSet) {
            val fingerprint = CryptoHelper.bytesToHex(identityKey.getPublicKey().serialize())
            if (!ownKeysToTrust.containsKey(fingerprint)) {
                ownKeysToTrust[fingerprint] = false
            }
        }
        synchronized(this.foreignKeysToTrust) {
            foreignKeysToTrust.clear()
            for (jid in contactJids) {
                val foreignKeysSet =
                    service
                        .getKeysWithTrust(FingerprintStatus.createActiveUndecided(), jid)
                        .toMutableSet()
                if (hasNoOtherTrustedKeys(jid) && ownKeysSet.isEmpty()) {
                    foreignKeysSet.addAll(
                        service.getKeysWithTrust(FingerprintStatus.createActive(false), jid),
                    )
                }
                val foreignFingerprints = HashMap<String, Boolean>()
                for (identityKey in foreignKeysSet) {
                    val fingerprint =
                        CryptoHelper.bytesToHex(identityKey.getPublicKey().serialize())
                    if (!foreignFingerprints.containsKey(fingerprint)) {
                        foreignFingerprints[fingerprint] = false
                    }
                }
                if (foreignFingerprints.isNotEmpty() || !acceptedTargets.contains(jid)) {
                    foreignKeysToTrust[jid] = foreignFingerprints
                }
            }
        }
        return ownKeysSet.size + foreignKeysToTrust.size > 0
    }

    public override fun onBackendConnected() {
        val intent = this.intent
        this.mAccount = extractAccount(intent)
        val account = this.mAccount
        if (account != null && intent != null) {
            val uuid = intent.getStringExtra("conversation")
            this.mConversation =
                xmppConnectionService.findConversationByUuid(uuid) as Conversation?
            if (mPendingFingerprintVerificationUri != null) {
                processFingerprintVerification(
                    mPendingFingerprintVerificationUri ?: throw NullPointerException(),
                )
                this.mPendingFingerprintVerificationUri = null
            } else {
                val keysToTrust = reloadFingerprints()
                if (keysToTrust || hasPendingKeyFetches() || hasNoOtherTrustedKeys()) {
                    populateView()
                } else {
                    finishOk(false)
                }
            }
        }
    }

    private fun hasNoOtherTrustedKeys(): Boolean {
        val account = mAccount ?: return true
        return (account.getAxolotlService() ?: throw NullPointerException()).anyTargetHasNoTrustedKeys(contactJids)
    }

    private fun hasNoOtherTrustedKeys(contact: Jid): Boolean {
        val account = mAccount ?: return true
        return (account.getAxolotlService() ?: throw NullPointerException()).getNumTrustedKeys(contact) == 0L
    }

    private fun hasPendingKeyFetches(): Boolean {
        val account = mAccount ?: return false
        return (account.getAxolotlService() ?: throw NullPointerException()).hasPendingKeyFetches(contactJids)
    }

    public override fun onKeyStatusUpdated(report: FetchStatus?) {
        val keysToTrust = reloadFingerprints()
        if (report != null) {
            lastFetchReport = report
            runOnUiThread {
                if (mUseCameraHintToast != null && !keysToTrust) {
                    mUseCameraHintToast?.cancel()
                }
                when (report) {
                    FetchStatus.ERROR ->
                        Toast.makeText(
                            this,
                            R.string.error_fetching_omemo_key,
                            Toast.LENGTH_SHORT,
                        ).show()
                    FetchStatus.SUCCESS_TRUSTED ->
                        Toast.makeText(
                            this,
                            R.string.blindly_trusted_omemo_keys,
                            Toast.LENGTH_LONG,
                        ).show()
                    FetchStatus.SUCCESS_VERIFIED ->
                        Toast.makeText(
                            this,
                            if (Config.X509_VERIFICATION) {
                                R.string.verified_omemo_key_with_certificate
                            } else {
                                R.string.all_omemo_keys_have_been_verified
                            },
                            Toast.LENGTH_LONG,
                        ).show()
                    else -> Unit
                }
            }
        }
        if (keysToTrust || hasPendingKeyFetches() || hasNoOtherTrustedKeys()) {
            refreshUi()
        } else {
            runOnUiThread { finishOk(false) }
        }
    }

    private fun finishOk(disabled: Boolean) {
        val data = Intent()
        data.putExtra(
            "choice",
            intent.getIntExtra("choice", ConversationRequests.ATTACHMENT_CHOICE_INVALID),
        )
        data.putExtra("disabled", disabled)
        setResult(RESULT_OK, data)
        finish()
    }

    /** The deleted cancel button, which also answered `RESULT_CANCELED`. */
    private fun cancelAndFinish() {
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun commitTrusts() {
        val account = mAccount ?: throw NullPointerException()
        for (fingerprint in ownKeysToTrust.keys) {
            (account.getAxolotlService() ?: throw NullPointerException()).setFingerprintTrust(
                fingerprint,
                FingerprintStatus.createActive(ownKeysToTrust[fingerprint]),
            )
        }
        val conversation = mConversation
        val acceptedTargets: MutableList<Jid> =
            if (conversation == null) {
                ArrayList()
            } else {
                ArrayList(conversation.getAcceptedCryptoTargets())
            }
        synchronized(this.foreignKeysToTrust) {
            for (entry in foreignKeysToTrust.entries) {
                val jid = entry.key
                val value = entry.value
                if (!acceptedTargets.contains(jid)) {
                    acceptedTargets.add(jid)
                }
                for (fingerprint in value.keys) {
                    (account.getAxolotlService() ?: throw NullPointerException()).setFingerprintTrust(
                        fingerprint,
                        FingerprintStatus.createActive(value[fingerprint]),
                    )
                }
            }
        }
        if (conversation != null && conversation.getMode() == Conversation.MODE_MULTI) {
            conversation.setAcceptedCryptoTargets(acceptedTargets)
            xmppConnectionService.updateConversation(conversation)
        }
    }

    /** The deleted `saveButton.setEnabled(true)`. */
    private fun unlock() {
        screen = screen.copy(saveEnabled = true)
    }

    /** The deleted `saveButton.setEnabled(false)`. */
    private fun lock() {
        screen = screen.copy(saveEnabled = false)
    }

    private fun lockOrUnlockAsNeeded() {
        synchronized(this.foreignKeysToTrust) {
            for (jid in contactJids) {
                val fingerprints = foreignKeysToTrust[jid]
                if (hasNoOtherTrustedKeys(jid) &&
                    (fingerprints == null || !fingerprints.containsValue(true))
                ) {
                    lock()
                    return
                }
            }
        }
        unlock()
    }
}

/**
 * One fingerprint row: the shared `contact_key.xml`'s contents, redrawn for this screen, which
 * cannot hang a view on a deleted container.
 *
 * <p>The key is the monospaced [CryptoHelper.prettifyFingerprint] of the fingerprint the old view was
 * handed, coloured `onSurface` while the key is active and `onSurfaceVariant` while it is not; the
 * action slot is the layout's `key_action_width` by 48 dp box, holding the green
 * `ic_verified_user_24dp` mark for a verified key (alpha 1.0 active, `0.4368` inactive, exactly the
 * two alphas the shared view set) and the trust switch otherwise. The switch is disabled while the
 * key is inactive, which is the layout's `tglTrust.isEnabled = false`.
 *
 * <p>The key-type line under the key is not drawn: this screen asked the shared view for
 * `showTag = false` and `highlight = false`, so the view left that line `GONE` too.
 */
@Composable
fun FingerprintRow(
    row: FingerprintRowState,
    onTrustChanged: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = CryptoHelper.prettifyFingerprint(row.fingerprint.substring(2)),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color =
                if (row.status.isActive()) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
        Box(
            modifier = Modifier.width(54.dp).height(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (row.status.isVerified()) {
                Icon(
                    painter = painterResource(R.drawable.ic_verified_user_24dp),
                    contentDescription = null,
                    tint = colorResource(R.color.light_green_600),
                    modifier =
                        Modifier.size(40.dp)
                            .alpha(if (row.status.isActive()) 1.0f else 0.4368f),
                )
            } else {
                Switch(
                    checked = row.trusted,
                    onCheckedChange = onTrustChanged,
                    enabled = row.status.isActive(),
                )
            }
        }
    }
}

/** Everything [TrustKeysActivity] hands [TrustKeysScreen], rebuilt whenever the maps change. */
data class TrustKeysScreenState(
    val ownKeysTitle: String = "",
    val ownKeys: List<FingerprintRowState> = emptyList(),
    val showOwnKeys: Boolean = false,
    val foreignKeys: List<ForeignKeysCardState> = emptyList(),
    val showForeignKeys: Boolean = false,
    val error: TrustKeysErrorState? = null,
    @StringRes val saveLabel: Int = R.string.save,
    val saveEnabled: Boolean = false,
)

/** One key and the decision pending on it: the old `tglTrust.isChecked` and its listeners. */
data class FingerprintRowState(
    val fingerprint: String,
    val status: FingerprintStatus,
    val trusted: Boolean,
)

/** One contact's card (`keys_card.xml`): the styled JID, its rows, and the no-keys line. */
data class ForeignKeysCardState(
    val jid: Jid,
    val title: AnnotatedString,
    val rows: List<FingerprintRowState>,
    val noKeysText: String?,
)

/** The error card (`key_error_message_card`) and the four lines it drew. */
data class TrustKeysErrorState(
    val message: String?,
    val general: String,
    val showMutualHint: Boolean,
    val showDisable: Boolean,
)

/**
 * The trust screen's body: the scrollable cards the deleted `activity_trust_keys.xml` held between
 * its bar and its button bar.
 *
 * <p>Every size is the deleted layout's own: each card takes the `activity_*_margin` 8 dp margins,
 * its content `card_padding_list` 8 dp, the title `list_padding` 8 dp, the error card's inner column
 * `card_padding_regular` 16 dp, and the two buttons the bar's 16 dp / 8 dp padding. The scroll body
 * takes the space the button bar leaves, exactly as the `ScrollView`'s `layout_above` did. The two
 * card types are Material 3 [Card]s for the layout's `MaterialCardView`s, the error card's button is
 * a [TextButton] for its `Widget.Material3.Button.TextButton` style and the save button a [Button]
 * for the default one.
 */
@Composable
fun TrustKeysScreen(
    state: TrustKeysScreenState,
    onOwnKeyChanged: (String, Boolean) -> Unit,
    onForeignKeyChanged: (Jid, String, Boolean) -> Unit,
    onForeignTitleClick: (Jid) -> Unit,
    onFingerprintClick: (FingerprintRowState) -> Unit,
    onDisableEncryption: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        ) {
            val error = state.error
            if (error != null) {
                KeyErrorCard(error = error, onDisableEncryption = onDisableEncryption)
            }
            if (state.showOwnKeys) {
                Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = state.ownKeysTitle,
                            modifier = Modifier.padding(8.dp),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        for (row in state.ownKeys) {
                            FingerprintRow(
                                row = row,
                                onTrustChanged = { onOwnKeyChanged(row.fingerprint, it) },
                                onClick = { onFingerprintClick(row) },
                            )
                        }
                    }
                }
            }
            if (state.showForeignKeys) {
                for (card in state.foreignKeys) {
                    ForeignKeysCard(
                        card = card,
                        onTitleClick = { onForeignTitleClick(card.jid) },
                        onTrustChanged = { fingerprint, checked ->
                            onForeignKeyChanged(card.jid, fingerprint, checked)
                        },
                        onFingerprintClick = onFingerprintClick,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) { Text(stringResource(DataR.string.cancel)) }
            Spacer(Modifier.weight(1f))
            Button(onClick = onSave, enabled = state.saveEnabled) {
                Text(stringResource(state.saveLabel))
            }
        }
    }
}

/** One contact's card: the deleted `keys_card.xml`, with its title tap going to contact details. */
@Composable
private fun ForeignKeysCard(
    card: ForeignKeysCardState,
    onTitleClick: () -> Unit,
    onTrustChanged: (String, Boolean) -> Unit,
    onFingerprintClick: (FingerprintRowState) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = card.title,
                modifier = Modifier.clickable(onClick = onTitleClick).padding(8.dp),
                style = MaterialTheme.typography.titleLarge,
            )
            for (row in card.rows) {
                FingerprintRow(
                    row = row,
                    onTrustChanged = { onTrustChanged(row.fingerprint, it) },
                    onClick = { onFingerprintClick(row) },
                )
            }
            val noKeysText = card.noKeysText
            if (card.rows.isEmpty() && noKeysText != null) {
                Text(
                    text = noKeysText,
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** The deleted `key_error_message_card`: the title, the fetch message, the hint and the button. */
@Composable
private fun KeyErrorCard(error: TrustKeysErrorState, onDisableEncryption: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.error_trustkeys_title),
                    modifier = Modifier.padding(bottom = 8.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
                val message = error.message
                if (message != null) {
                    Text(text = message, style = MaterialTheme.typography.titleMedium)
                }
                Text(text = error.general, style = MaterialTheme.typography.bodyMedium)
                if (error.showMutualHint) {
                    Text(
                        text = stringResource(R.string.error_trustkey_hint_mutual),
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (error.showDisable) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDisableEncryption) {
                        Text(stringResource(R.string.disable_encryption))
                    }
                }
            }
        }
    }
}

/**
 * [IrregularUnicodeDetector.style] as Compose text: the same string with the same red on the same
 * characters, read out of the `ForegroundColorSpan`s the detector set instead of drawn by a view.
 */
private fun styledJid(context: Context, jid: Jid): AnnotatedString {
    val spannable: Spannable = IrregularUnicodeDetector.style(context, jid)
    val builder = AnnotatedString.Builder(spannable.toString())
    for (span in spannable.getSpans(0, spannable.length, ForegroundColorSpan::class.java)) {
        builder.addStyle(
            SpanStyle(color = Color(span.foregroundColor)),
            spannable.getSpanStart(span),
            spannable.getSpanEnd(span),
        )
    }
    return builder.toAnnotatedString()
}
