package uk.xa0.tulkki.ui.fragment.settings

import android.widget.Toast

import com.google.common.base.Strings

import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.host.UiHost
import uk.xa0.tulkki.ui.preferences.PreferenceItem
import uk.xa0.tulkki.ui.preferences.PreferenceScreenFragment

import java.net.URI
import java.net.URISyntaxException

/**
 * The UnifiedPush distributor screen `preferences_up.xml` held, with the file deleted with this class.
 *
 * <p>The two keys are the composition root's, not the XML's: `UiHost.installed()` names the account
 * preference and the server preference, and the screen reads both through it exactly as the old
 * fragment did, so a flavour with different keys keeps working.
 *
 * <p>The account list is not a resource: it is the accounts this phone has, rebuilt on every render,
 * with the "no account" entry first and "none" as its value. The old `onBackendConnected` also forced
 * the stored value to "none" when it no longer named an account - a write that is kept here, in
 * [onBackendConnected], because it is a write and not a drawing.
 *
 * <p>The server field keeps its veto: a blank, non-JID or http(s) value is refused with the invalid-JID
 * toast and nothing is persisted, exactly as the old `setOnPreferenceChangeListener` that returned
 * false.
 */
class UpSettingsFragment : PreferenceScreenFragment() {

    override fun buildItems(): List<PreferenceItem> {
        val items = ArrayList<PreferenceItem>()
        items.add(accountPreference())
        items.add(serverPreference())
        items.add(
            PreferenceItem(summary = getString(R.string.pref_up_long_summary), kind = PreferenceItem.Kind.LINE)
        )
        return items
    }

    override fun onPreferenceChange(item: PreferenceItem, newValue: Any?): Boolean {
        if (item.key != UiHost.installed().unifiedPushServerPreference()) {
            return true
        }
        if (newValue is String) {
            if (Strings.isNullOrEmpty(newValue) || isJidInvalid(newValue) || isHttpUri(newValue)) {
                Toast.makeText(requireActivity(), R.string.invalid_jid, Toast.LENGTH_LONG).show()
                return false
            }
            return true
        }
        Toast.makeText(requireActivity(), R.string.invalid_jid, Toast.LENGTH_LONG).show()
        return false
    }

    override fun onBackendConnected() {
        val key = UiHost.installed().unifiedPushAccountPreference()
        if (!accountValues().contains(preferences.getString(key, null))) {
            // `listPreference.value = "none"` persisted the same write, and its listener ran the same
            // way - this is that line, not a drawing of it.
            putString(key, NO_ACCOUNT)
        }
        // The old `reconfigureUpAccountPreference` also re-set the entries, which is what this is:
        // the account list is this phone's and it may have changed while the screen was away.
        render()
    }

    override fun onSharedPreferenceChanged(key: String) {
        super.onSharedPreferenceChanged(key)
        if (UiHost.installed().unifiedPushPreferences().contains(key)) {
            val service = requireService()
            if (service.reconfigurePushDistributor()) {
                service.renewUnifiedPushEndpoints()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        requireActivity().setTitle(R.string.unified_push_distributor)
    }

    /** The account list preference: its entries are this phone's accounts, and "none" is the first. */
    private fun accountPreference(): PreferenceItem {
        val key = UiHost.installed().unifiedPushAccountPreference()
        val accounts = accountValues()
        val labels = ArrayList<CharSequence>(accounts.size + 1)
        val values = ArrayList<String>(accounts.size + 1)
        labels.add(getString(R.string.no_account_deactivated))
        values.add(NO_ACCOUNT)
        for (account in accounts) {
            labels.add(account)
            values.add(account)
        }
        val default = getString(R.string.default_push_account)
        return PreferenceItem(
            key = key,
            title = getString(R.string.pref_up_push_account_title),
            summary = getString(R.string.pref_up_push_account_summary),
            icon = R.drawable.ic_person_24dp,
            kind = PreferenceItem.Kind.LIST,
            labels = labels,
            values = values,
            value = preferences.getString(key, default),
            defaultValue = default,
        )
    }

    /** The server field: a JID, summarised by its own value because the XML said so. */
    private fun serverPreference(): PreferenceItem {
        val key = UiHost.installed().unifiedPushServerPreference()
        val default = getString(R.string.default_push_server)
        val value = preferences.getString(key, default)
        return PreferenceItem(
            key = key,
            title = getString(R.string.pref_up_push_server_title),
            summary = value,
            icon = R.drawable.ic_mediation_24dp,
            kind = PreferenceItem.Kind.TEXT,
            value = value,
            defaultValue = default,
        )
    }

    private fun accountValues(): List<String> =
        AccountRegistry.get().getAccounts().map { it.getJid().asBareJid().toString() }

    private fun isJidInvalid(input: String): Boolean {
        return try {
            val jid = Jid.of(input)
            !jid.isBareJid()
        } catch (e: IllegalArgumentException) {
            true
        }
    }

    private fun isHttpUri(input: String): Boolean {
        val uri: URI =
            try {
                URI(input)
            } catch (e: URISyntaxException) {
                return false
            }
        return listOf("http", "https").contains(uri.scheme)
    }

    companion object {
        private const val NO_ACCOUNT = "none"
    }
}
