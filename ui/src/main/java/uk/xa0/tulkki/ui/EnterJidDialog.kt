package uk.xa0.tulkki.ui

import android.app.Activity
import android.content.Intent

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

import io.michaelrocks.libphonenumber.android.NumberParseException

import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.model.Contact
import uk.xa0.tulkki.data.model.Presence
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.ui.dialogs.AccountDropdown
import uk.xa0.tulkki.ui.dialogs.JidField
import uk.xa0.tulkki.ui.dialogs.showTulkkiDialog
import uk.xa0.tulkki.ui.host.UiHost
import uk.xa0.tulkki.xmpp.OnGatewayResult

import java.util.ArrayList

/** The sub-domains the sanity check warns about, exactly the fragment's list. */
private val SUSPICIOUS_DOMAINS: List<String> =
    listOf("conference", "muc", "room", "rooms")

/**
 * The JID dialog: the account to use, the gateways of that account, the address itself and whether
 * to save it.
 *
 * <p>**The `DialogFragment` and the two layouts are gone.** `dialog_enter_jid.xml` and
 * `enter_jid_dialog_gateway_list_item.xml` are deleted, and so is the fragment's
 * `MaterialAlertDialogBuilder` and its `RecyclerView` of `ToggleButton`s; the dialog is a Compose
 * `AlertDialog` over the host activity's content ([showTulkkiDialog]) and the gateway row is a row
 * of [FilterChip]s. All three hosts - `StartConversationActivity`, `ChooseContactActivity` and
 * `BlocklistActivity` - call [show] and get a [Session] back instead of a fragment, and a backend
 * connect refreshes the known hosts through that session rather than through
 * `FragmentManager.findFragmentByTag`.
 *
 * <p>**What did not change.** The address parsing and the gateway protocol are the fragment's, byte
 * for byte: the same `Jid.of`, the same `jid\20escaping` branch, the same `@`-to-`%` fallback, the
 * same phone normalisation for a `pstn`/`sms` gateway, and the same `JidError` contract with the
 * listener. The suggestions are the deleted `KnownHostsAdapter`'s
 * ([uk.xa0.tulkki.ui.dialogs.hostSuggestions]), the account dropdown is [AccountDropdown] - the
 * deleted `StartConversationActivity.populateAccountSpinner` in Compose - and the dynamic button
 * labels survive: a domain address or a suspicious sub-domain relabels the confirm button to
 * `add_anway` and the other one to `enter_jid_browse`, and the next keystroke puts both back.
 *
 * <p>**One thing is named that was a literal.** The warning's second button said `"Browse"`, an
 * inline English string in the fragment; it is `R.string.enter_jid_browse` now, in
 * `values/strings_startchat.xml`.
 *
 * <p>**One thing the fragment had is gone with it.** `setRetainInstance(true)` kept a rotated
 * dialog, and the fragment's back-stack entry kept it across a host's `onBackStackChanged`; a
 * session is a plain field of the host activity now, so a rotation closes the dialog - the trade
 * `showTulkkiDialog` already names for every Compose dialog in this tree.
 */
class EnterJidDialog {

    enum class SanityCheck {
        NO,
        YES,
        ALLOW_MUC,
    }

    class JidError(private val msg: String) : Exception() {

        override fun toString(): String {
            return msg
        }
    }

    fun interface OnEnterJidDialogPositiveListener {
        @Throws(JidError::class)
        fun onEnterJidDialogPositive(
            account: Jid,
            contact: Jid,
            secondary: Boolean,
            save: Boolean,
        ): Boolean
    }

    /**
     * One open dialog: the state the screen draws and the logic the fragment held.
     *
     * <p>Everything the screen reads is a Compose state or derived from one, so the composition
     * follows the account, the gateway selection, the warning and the async gateway answers. The
     * activity owns the session - it is what a backend connect refreshes, and what the listener's
     * own deferred paths dismiss.
     */
    class Session internal constructor(
        private val activity: XmppActivity,
        private val listener: OnEnterJidDialogPositiveListener,
        private val sanityCheck: SanityCheck,
        private val title: String,
        private val positiveButton: String,
        private val secondaryButton: String?,
        private val prefilledJid: String?,
        private val account: String?,
        private val activatedAccounts: List<String>,
        private val allowEditJid: Boolean,
        private val showBookmarkCheckbox: Boolean,
    ) {

        private var jidText by mutableStateOf(prefilledJid ?: "")

        private var selectedAccount by mutableStateOf(account ?: activatedAccounts.firstOrNull())

        /** The known hosts, which are also the domains a sub-domain warning excuses. */
        private var knownHosts by mutableStateOf<List<String>>(emptyList())

        /** `GatewayListAdapter.gateways`: the gateway contact and the prompt it answered. */
        private var gateways by mutableStateOf<List<Pair<Contact, String?>>>(emptyList())

        private var selectedGateway by mutableStateOf(0)

        private var errorText by mutableStateOf<String?>(null)

        private var helperText by mutableStateOf<String?>(null)

        private var positiveLabel by mutableStateOf(positiveButton)

        private var negativeLabel by mutableStateOf(
            secondaryButton ?: activity.getString(uk.xa0.tulkki.data.R.string.cancel))

        private var issuedWarning = false

        /** The screen's checkbox; the listener reads it as the old `binding.bookmark.isChecked`. */
        var bookmark by mutableStateOf(true)

        /** Set by [dismiss]; the screen closes the dialog window on the next frame. */
        var closeRequested by mutableStateOf(false)
            private set

        /** What the screen draws. Every read below is tracked by the composition. */
        val state: EnterJidState
            get() =
                EnterJidState(
                    title = title,
                    jid = jidText,
                    jidLabel = jidLabel(),
                    jidPlaceholder = jidPlaceholder(),
                    domains = if (selectedGateway == 0) knownHosts else emptyList(),
                    readOnly = !allowEditJid,
                    keyboardType = jidKeyboardType(),
                    accounts = if (account == null) activatedAccounts else emptyList(),
                    selectedAccount = selectedAccount,
                    gatewayChips = gatewayChips(),
                    selectedGateway = selectedGateway,
                    error = errorText,
                    helper = helperText,
                    primaryLabel = positiveLabel,
                    secondaryLabel = negativeLabel,
                    hasSecondary = secondaryButton != null,
                    neutralLabel =
                        if (secondaryButton == null) null
                        else activity.getString(uk.xa0.tulkki.data.R.string.cancel),
                    showBookmark = showBookmarkCheckbox,
                    bookmark = bookmark,
                    dismissRequested = closeRequested,
                )

        /** The old `afterTextChanged`: a keystroke takes the warning's labels back. */
        fun onJidChange(text: String) {
            jidText = text
            if (issuedWarning) {
                positiveLabel = activity.getString(R.string.add)
                errorText = null
                issuedWarning = false
            }
        }

        /** A chosen account: the gateways are that account's, and the first one is selected again. */
        fun onAccountSelected(name: String) {
            selectedAccount = name
            populateGateways()
        }

        /** A tapped gateway chip: `GatewayListAdapter.setSelected`, with its field changes derived. */
        fun selectGateway(index: Int) {
            selectedGateway = index
        }

        /** The known hosts, read where the fragment read them: on start and on a backend connect. */
        fun refreshKnownHosts() {
            knownHosts = activity.xmppConnectionService?.getKnownHosts()?.toList() ?: emptyList()
        }

        fun onBackendConnected() {
            refreshKnownHosts()
        }

        /** The old `dismiss()`, reachable from a listener that answers after its own callback. */
        fun dismiss() {
            closeRequested = true
        }

        /**
         * The deleted `populateGateways`: every roster contact that is a gateway is asked for its
         * prompt, and each answer is a chip.
         */
        fun populateGateways() {
            val service = activity.xmppConnectionService ?: return
            val accountJid = accountJid() ?: return
            selectedGateway = 0
            gateways = emptyList()
            val account =
                AccountRegistry.get().findAccountByJid(accountJid) ?: return

            for (contact in account.getRoster().getContacts()) {
                val presences = contact.getPresences()
                if (contact.showInRoster() &&
                    presences.size() > 0 &&
                    (presences.anyIdentity("gateway", null) ||
                        presences.anySupport("jabber:iq:gateway"))) {
                    service.fetchFromGateway(
                        account,
                        contact.getJid(),
                        null,
                        object : OnGatewayResult {
                            override fun onGatewayResult(prompt: String?, errorText: String?) {
                                if (prompt == null &&
                                    !contact.getPresences().anyIdentity("gateway", null)) {
                                    return
                                }

                                activity.runOnUiThread { addGateway(contact, prompt) }
                            }
                        },
                    )
                }
            }
        }

        /** The old `handleEnter`, on the state the screen holds. */
        fun submit(secondary: Boolean) {
            if (account == null && activatedAccounts.isEmpty()) {
                return
            }
            val accountJid = accountJid()
            val finish =
                object : OnGatewayResult {
                    override fun onGatewayResult(prompt: String?, errorText: String?) {
                        val jidString = prompt
                        val errorMessage = errorText
                        activity.runOnUiThread {
                            if (errorMessage != null) {
                                this@Session.errorText = errorMessage
                                return@runOnUiThread
                            }
                            if (jidString == null) {
                                this@Session.errorText = activity.getString(R.string.invalid_jid)
                                return@runOnUiThread
                            }

                            val contactJid =
                                try {
                                    Jid.of(jidString)
                                } catch (e: IllegalArgumentException) {
                                    this@Session.errorText =
                                        activity.getString(R.string.invalid_jid)
                                    return@runOnUiThread
                                }

                            if (!issuedWarning && sanityCheck != SanityCheck.NO) {
                                if (contactJid.isDomainJid()) {
                                    helperText =
                                        activity.getString(R.string.this_looks_like_a_domain)
                                    positiveLabel = activity.getString(R.string.add_anway)
                                    negativeLabel = activity.getString(R.string.enter_jid_browse)
                                    issuedWarning = true
                                    return@runOnUiThread
                                }
                                if (sanityCheck != SanityCheck.ALLOW_MUC &&
                                    suspiciousSubDomain(contactJid.getDomain().toString())) {
                                    this@Session.errorText =
                                        activity.getString(R.string.this_looks_like_channel)
                                    positiveLabel = activity.getString(R.string.add_anway)
                                    issuedWarning = true
                                    return@runOnUiThread
                                }
                            } else if (secondary) {
                                val intent =
                                    Intent(activity, ChannelDiscoveryActivity::class.java)
                                intent.putExtra(
                                    "services",
                                    arrayOf(
                                        jidString,
                                        (accountJid ?: throw NullPointerException()).toString()),
                                )
                                dismiss()
                                activity.startActivity(intent)
                                return@runOnUiThread
                            }

                            try {
                                if (listener.onEnterJidDialogPositive(
                                        accountJid ?: throw NullPointerException(),
                                        contactJid,
                                        secondary,
                                        bookmark,
                                    )
                                ) {
                                    activity.setResult(Activity.RESULT_OK)
                                    dismiss()
                                }
                            } catch (error: JidError) {
                                this@Session.errorText = error.toString()
                                positiveLabel = activity.getString(R.string.add)
                                issuedWarning = false
                            }
                        }
                    }
                }

            val p = selectedGatewayOption()
            val type = selectedType()

            // Resolve based on local settings before submission
            if (type != null && (type == "pstn" || type == "sms")) {
                try {
                    jidText =
                        UiHost.installed()
                            .normalizePhoneNumber(activity, jidText, true)
                } catch (e: NumberParseException) {
                } catch (e: IllegalArgumentException) {
                } catch (e: NullPointerException) {
                }
            }

            if (p == null) {
                finish.onGatewayResult(jidText.trim(), null)
            } else if (p.first != null) { // Gateway already responsed to jabber:iq:gateway once
                val acct =
                    AccountRegistry.get().findAccountByJid(accountJid ?: throw NullPointerException())
                (activity.xmppConnectionService ?: throw NullPointerException())
                    .fetchFromGateway(
                        acct ?: throw NullPointerException(),
                        p.second.first,
                        jidText.trim(),
                        finish,
                    )
            } else if (p.second.first.isDomainJid() &&
                (p.second.second.getServiceDiscoveryResult() ?: throw NullPointerException())
                    .getFeatures()
                    .contains("jid\\20escaping")
            ) {
                finish.onGatewayResult(
                    Jid.ofLocalAndDomain(
                            jidText.trim(),
                            p.second.first.getDomain().toString(),
                        )
                        .toString(),
                    null,
                )
            } else if (p.second.first.isDomainJid()) {
                finish.onGatewayResult(
                    Jid.ofLocalAndDomain(
                            jidText.trim().replace("@", "%"),
                            p.second.first.getDomain().toString(),
                        )
                        .toString(),
                    null,
                )
            } else {
                finish.onGatewayResult(null, null)
            }
        }

        /** `GatewayListAdapter.getSelected`, on the gateway the chip row has selected. */
        private fun selectedGatewayOption(): Pair<String?, Pair<Jid, Presence>>? {
            if (selectedGateway == 0) {
                return null // No gateway, just use direct JID entry
            }

            val gateway = gateways[selectedGateway - 1]

            var presence: Pair<Jid, Presence>? = null
            for (e in gateway.first.getPresences().getPresencesMap().entries) {
                val p = e.value
                val disco = p.getServiceDiscoveryResult()
                if (disco != null) {
                    if (disco.getFeatures().contains("jabber:iq:gateway")) {
                        presence =
                            if (e.key == "") {
                                Pair(gateway.first.getJid(), p)
                            } else {
                                Pair(gateway.first.getJid().withResource(e.key), p)
                            }
                        break
                    }
                    if (disco.hasIdentity("gateway", null)) {
                        presence =
                            if (e.key == "") {
                                Pair(gateway.first.getJid(), p)
                            } else {
                                Pair(gateway.first.getJid().withResource(e.key), p)
                            }
                    }
                }
            }

            return if (presence == null) null else Pair(gateway.second, presence)
        }

        private fun accountJid(): Jid? {
            return try {
                Jid.of(selectedAccount ?: "")
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        private fun addGateway(gateway: Contact, prompt: String?) {
            val next = gateways.toMutableList()
            next.add(Pair(gateway, prompt))
            next.sortWith { x, y -> labelOf(x.first).compareTo(labelOf(y.first)) }
            gateways = next
        }

        private fun gatewayChips(): List<String> {
            val labels = ArrayList<String>()
            labels.add(activity.getString(R.string.account_settings_jabber_id))
            for (gateway in gateways) {
                labels.add(labelOf(gateway.first))
            }
            return labels
        }

        /** The old `getLabel(Contact)`: the type, the phone glyph for a `pstn` one, or the name. */
        private fun labelOf(gateway: Contact): String {
            val type = typeOf(gateway)
            if ("pstn" == type) {
                return "📞"
            }
            if (type != null) {
                return type
            }

            return gateway.getDisplayName()
        }

        private fun selectedType(): String? {
            return typeOf(selectedGateway)
        }

        private fun typeOf(index: Int): String? {
            if (index == 0) {
                return null
            }

            return typeOf(gateways[index - 1].first)
        }

        /** The old `getType(Contact)`: `pstn` wins, otherwise the first identity type. */
        private fun typeOf(gateway: Contact): String? {
            val types = typesOf(gateway)
            if (types.contains("pstn")) {
                return "pstn"
            }
            return if (types.isEmpty()) null else types[0]
        }

        private fun typesOf(gateway: Contact): List<String?> {
            val types: MutableList<String?> = ArrayList()

            for (p in gateway.getPresences().getPresences()) {
                val disco = p.getServiceDiscoveryResult()
                if (disco != null) {
                    for (id in disco.getIdentities()) {
                        if ("gateway" == id.getCategory()) {
                            types.add(id.getType())
                        }
                    }
                }
            }

            return types
        }

        /** The selected gateway's own label: the prompt it answered, or the base field's. */
        private fun jidLabel(): String {
            if (selectedGateway == 0) {
                return activity.getString(R.string.account_settings_jabber_id)
            }
            return gateways[selectedGateway - 1].second ?: ""
        }

        private fun jidPlaceholder(): String? {
            if (selectedGateway == 0) {
                return activity.getString(R.string.account_settings_example_jabber_id)
            }
            return when (selectedType()) {
                "email", "sip" -> activity.getString(R.string.account_settings_example_jabber_id)
                else -> null
            }
        }

        private fun jidKeyboardType(): KeyboardType {
            if (selectedGateway == 0) {
                return KeyboardType.Email
            }
            return when (selectedType()) {
                "pstn", "sms" -> KeyboardType.Phone
                "email", "sip" -> KeyboardType.Email
                else -> KeyboardType.Text
            }
        }

        private fun suspiciousSubDomain(domain: String): Boolean {
            if (knownHosts.contains(domain)) {
                return false
            }
            val parts = domain.split("\\.")
            return parts.size >= 3 && SUSPICIOUS_DOMAINS.contains(parts[0])
        }
    }

    companion object {
        /**
         * Open the dialog over [activity]'s own content, exactly where the fragment's window was.
         *
         * @param activatedAccounts the accounts the dropdown offers; empty with [account] set, which
         *     is the disabled single-account field the fragment built.
         * @param listener the host's answer; a `true` closes the dialog, and a host that answers
         *     later calls [Session.dismiss] itself.
         */
        fun show(
            activity: XmppActivity,
            activatedAccounts: List<String>,
            title: String?,
            positiveButton: String?,
            secondaryButton: String?,
            prefilledJid: String?,
            account: String?,
            allowEditJid: Boolean,
            showBookmarkCheckbox: Boolean,
            sanityCheck: SanityCheck,
            listener: OnEnterJidDialogPositiveListener,
        ): Session {
            val session =
                Session(
                    activity = activity,
                    listener = listener,
                    sanityCheck = sanityCheck,
                    title = title ?: "",
                    positiveButton = positiveButton ?: "",
                    secondaryButton = secondaryButton,
                    prefilledJid = prefilledJid,
                    account = account,
                    activatedAccounts = activatedAccounts,
                    allowEditJid = allowEditJid,
                    showBookmarkCheckbox = showBookmarkCheckbox,
                )
            session.refreshKnownHosts()
            session.populateGateways()
            activity.showTulkkiDialog { dismiss ->
                EnterJidDialogScreen(
                    state = session.state,
                    onDismiss = dismiss,
                    onAccountSelected = { session.onAccountSelected(it) },
                    onJidChange = { session.onJidChange(it) },
                    onGatewaySelected = { session.selectGateway(it) },
                    onBookmarkChange = { session.bookmark = it },
                    onSubmitPrimary = { session.submit(false) },
                    onSubmitSecondary = { session.submit(true) },
                )
            }
            return session
        }
    }
}

/**
 * The dialog's face: the deleted layout's three fields and the builder's buttons.
 *
 * @param state what the session draws; the labels come from the session so this file names only the
 *     checkbox's string, the one string the layout itself carried.
 */
@Composable
fun EnterJidDialogScreen(
    state: EnterJidState,
    onDismiss: () -> Unit,
    onAccountSelected: (String) -> Unit,
    onJidChange: (String) -> Unit,
    onGatewaySelected: (Int) -> Unit,
    onBookmarkChange: (Boolean) -> Unit,
    onSubmitPrimary: () -> Unit,
    onSubmitSecondary: () -> Unit,
) {
    if (state.dismissRequested) {
        LaunchedEffect(Unit) { onDismiss() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(state.title) },
        text = {
            Column {
                AccountDropdown(
                    accounts = state.accounts,
                    selected = state.selectedAccount,
                    onSelect = onAccountSelected,
                )
                if (state.gatewayChips.size > 1) {
                    Row(
                        modifier =
                            Modifier.fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(vertical = 4.dp),
                    ) {
                        for (index in state.gatewayChips.indices) {
                            FilterChip(
                                selected = index == state.selectedGateway,
                                onClick = { onGatewaySelected(index) },
                                label = { Text(state.gatewayChips[index]) },
                                modifier = Modifier.padding(end = 4.dp),
                            )
                        }
                    }
                }
                JidField(
                    value = state.jid,
                    onValueChange = onJidChange,
                    label = state.jidLabel,
                    placeholder = state.jidPlaceholder,
                    domains = state.domains,
                    isError = state.error != null,
                    supportingText = state.error ?: state.helper,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    imeAction = ImeAction.Done,
                    onImeAction = onSubmitPrimary,
                    keyboardType = state.keyboardType,
                    readOnly = state.readOnly,
                )
                if (state.showBookmark) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = state.bookmark, onCheckedChange = onBookmarkChange)
                        Text(stringResource(R.string.save_as_contact_bookmark))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmitPrimary) { Text(state.primaryLabel) }
        },
        dismissButton = {
            TextButton(
                onClick = { if (state.hasSecondary) onSubmitSecondary() else onDismiss() },
            ) {
                Text(state.secondaryLabel)
            }
            val neutral = state.neutralLabel
            if (neutral != null) {
                TextButton(onClick = onDismiss) { Text(neutral) }
            }
        },
    )
}

/** Everything [EnterJidDialogScreen] draws, resolved by the session that owns it. */
data class EnterJidState(
    val title: String,
    val jid: String,
    val jidLabel: String,
    val jidPlaceholder: String?,
    val domains: List<String>,
    val readOnly: Boolean,
    val keyboardType: KeyboardType,
    val accounts: List<String>,
    val selectedAccount: String?,
    val gatewayChips: List<String>,
    val selectedGateway: Int,
    val error: String?,
    val helper: String?,
    val primaryLabel: String,
    val secondaryLabel: String,
    val hasSecondary: Boolean,
    val neutralLabel: String?,
    val showBookmark: Boolean,
    val bookmark: Boolean,
    val dismissRequested: Boolean,
)
