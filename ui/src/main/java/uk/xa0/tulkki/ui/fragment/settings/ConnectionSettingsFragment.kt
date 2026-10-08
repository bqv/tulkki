package uk.xa0.tulkki.ui.fragment.settings

import android.util.Log
import android.widget.Toast

import com.google.common.base.Strings

import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.AppSettings
import uk.xa0.tulkki.data.DatabaseBackend
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.host.UiHost
import uk.xa0.tulkki.ui.preferences.PreferenceItem
import uk.xa0.tulkki.ui.preferences.PreferenceScreenFragment
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.utils.Resolver

/**
 * The connection switches and fields `preferences_connection.xml` held, with the file deleted with
 * this class.
 *
 * <p>Two rows are conditional and the conditions are the old ones: a Quicky build hides the extended
 * connection options, and [hideChannelDiscovery] hides the whole groups-and-channels category. In the
 * Compose tree a hidden row is simply absent, which is what `isVisible = false` drew.
 *
 * <p>The DNS fields keep their empty-string defaults, their static summaries and the reset row's own
 * behaviour: clearing both fields **removes** the keys, which is what `setText(null)` persisted, and
 * then reconnects every account. The reset also reaches `onSharedPreferenceChanged` for the two keys
 * through the listener, exactly as the old `setText` did, so the reconnects are as many as they were.
 */
class ConnectionSettingsFragment : PreferenceScreenFragment() {

    override fun buildItems(): List<PreferenceItem> {
        val items = ArrayList<PreferenceItem>()
        items.add(header(R.string.pref_category_server_connection))
        items.add(
            PreferenceItem(
                key = AppSettings.CUSTOM_RESOURCE_NAME,
                title = getString(R.string.pref_custom_resource_name),
                summary = getString(R.string.pref_custom_resource_name_summary),
                icon = R.drawable.ic_label_24dp,
                kind = PreferenceItem.Kind.TEXT,
                value = preferences.getString(AppSettings.CUSTOM_RESOURCE_NAME, null),
                dialogTitle = getString(R.string.pref_custom_resource_name_dialog_title),
                textHint =
                    getString(
                        R.string.pref_custom_resource_name_hint,
                        AppSettings.CUSTOM_RESOURCE_NAME_MAX_LENGTH,
                    ),
            )
        )
        if (!UiHost.installed().quicksy()) {
            items.add(
                switch(
                    AppSettings.SHOW_CONNECTION_OPTIONS,
                    R.string.pref_show_connection_options,
                    R.string.pref_show_connection_options_summary,
                    R.drawable.ic_settings_24dp,
                    uk.xa0.tulkki.data.R.bool.show_connection_options,
                )
            )
        }
        items.add(
            switch(
                AppSettings.USE_TOR,
                R.string.pref_use_tor,
                R.string.pref_use_tor_summary,
                R.drawable.ic_network_node_24dp,
                uk.xa0.tulkki.xmpp.R.bool.use_tor,
            )
        )
        items.add(
            switch(
                AppSettings.USE_I2P,
                R.string.pref_use_i2p,
                R.string.pref_use_i2p_summary,
                R.drawable.i2p_connection,
                uk.xa0.tulkki.xmpp.R.bool.use_i2p,
            )
        )
        items.add(
            switch(
                AppSettings.PREFER_IPV6,
                R.string.pref_prefer_ipv6,
                R.string.pref_prefer_ipv6_summary,
                R.drawable.rounded_commit_24,
                uk.xa0.tulkki.data.R.bool.prefer_ipv6,
            )
        )
        items.add(
            text(
                DNS_SERVER_IPV4,
                R.string.pref_dns_server_ipv4_title,
                R.string.pref_dns_server_ipv4_summary,
                R.drawable.outline_dns_24,
                getString(R.string.default_dns_server_ipv4),
            )
        )
        items.add(
            text(
                DNS_SERVER_IPV6,
                R.string.pref_dns_server_ipv6_title,
                R.string.pref_dns_server_ipv6_summary,
                R.drawable.outline_dns_24,
                getString(R.string.default_dns_server_ipv6),
            )
        )
        items.add(
            PreferenceItem(
                key = RESET_DNS_SERVER,
                title = getString(R.string.pref_reset_dns_server_title),
                summary = getString(R.string.pref_reset_dns_server_summary),
                icon = R.drawable.outline_reset_settings_24,
            )
        )

        items.add(header(R.string.peer_to_peer))
        items.add(
            switch(
                AppSettings.USE_RELAYS,
                R.string.pref_use_relays,
                R.string.pref_use_relays_summary,
                R.drawable.ic_location_disabled_24dp,
                uk.xa0.tulkki.data.R.bool.use_relays,
            )
        )

        if (!hideChannelDiscovery()) {
            items.add(header(R.string.group_chats_and_channels))
            val (labels, values) =
                listFromArrays(R.array.channel_discovery_entries, R.array.channel_discover_values)
            val default = getString(R.string.default_channel_discovery)
            val value = preferences.getString(AppSettings.CHANNEL_DISCOVERY_METHOD, default)
            items.add(
                PreferenceItem(
                    key = AppSettings.CHANNEL_DISCOVERY_METHOD,
                    title = getString(R.string.pref_channel_discovery),
                    summary = getString(R.string.pref_channel_discovery_summary),
                    icon = R.drawable.ic_travel_explore_24dp,
                    kind = PreferenceItem.Kind.LIST,
                    labels = labels,
                    values = values,
                    value = value,
                    defaultValue = default,
                )
            )
        }
        return items
    }

    override fun onPreferenceClick(item: PreferenceItem) {
        if (item.key != RESET_DNS_SERVER) {
            return
        }
        // `setText(null)` persisted a removal, and each write reached the listener before the next
        // line - which is where the two `dns_server_*` reconnects below come from.
        putString(DNS_SERVER_IPV4, null)
        putString(DNS_SERVER_IPV6, null)
        reconnectAccounts()
        Toast.makeText(requireSettingsActivity(), R.string.dns_server_reset, Toast.LENGTH_LONG)
            .show()
    }

    override fun onSharedPreferenceChanged(key: String) {
        super.onSharedPreferenceChanged(key)
        when (key) {
            AppSettings.USE_TOR -> {
                val appSettings = AppSettings(requireContext())
                if (appSettings.isUseTor()) {
                    runOnUiThread {
                        Toast.makeText(
                                requireActivity(),
                                R.string.audio_video_disabled_tor,
                                Toast.LENGTH_LONG,
                            )
                            .show()
                    }
                }
                reconnectAccounts()
                requireService().reinitializeMuclumbusService()
            }
            AppSettings.USE_I2P -> {
                val appSettings = AppSettings(requireContext())
                if (appSettings.isUseI2P()) {
                    runOnUiThread {
                        Toast.makeText(
                                requireActivity(),
                                R.string.audio_video_disabled_i2p,
                                Toast.LENGTH_LONG,
                            )
                            .show()
                    }
                }
                reconnectAccounts()
                requireService().reinitializeMuclumbusService()
            }
            AppSettings.SHOW_CONNECTION_OPTIONS,
            AppSettings.PREFER_IPV6,
            DNS_SERVER_IPV4,
            DNS_SERVER_IPV6,
            AppSettings.CUSTOM_RESOURCE_NAME -> {
                reconnectAccounts()
            }
        }
        if (listOf(AppSettings.USE_TOR, AppSettings.SHOW_CONNECTION_OPTIONS).contains(key)) {
            val appSettings = AppSettings(requireContext())
            if (appSettings.isUseTor() || appSettings.isExtendedConnectionOptions()) {
                return
            }
            resetUserDefinedHostname()
        }
    }

    private fun resetUserDefinedHostname() {
        // The Java called requireService() for its own sake and never read the value: the call is
        // where a missing service throws.
        requireService()
        for (account in AccountRegistry.get().getAccounts()) {
            Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid().toString() +
                    ": resetting hostname and port to defaults",
            )
            account.setHostname(null)
            account.setPort(Resolver.XMPP_PORT_STARTTLS)
            DatabaseBackend.get().updateAccount(account)
        }
    }

    override fun onStart() {
        super.onStart()
        requireActivity().setTitle(R.string.pref_connection_options)
    }

    /** A `SwitchPreferenceCompat` over one of the module `@bool` defaults. */
    private fun switch(
        key: String,
        title: Int,
        summary: Int,
        icon: Int,
        defaultRes: Int,
    ): PreferenceItem = switchItem(key, title, summary, icon, resources.getBoolean(defaultRes))

    /** An `EditTextPreference` with a static summary; the value lives in its dialog only. */
    private fun text(
        key: String,
        title: Int,
        summary: Int,
        icon: Int,
        default: String?,
    ): PreferenceItem =
        PreferenceItem(
            key = key,
            title = getString(title),
            summary = getString(summary),
            icon = icon,
            kind = PreferenceItem.Kind.TEXT,
            value = preferences.getString(key, default),
            defaultValue = default,
        )

    private fun header(title: Int): PreferenceItem =
        PreferenceItem(title = getString(title), kind = PreferenceItem.Kind.HEADER)

    companion object {
        private const val DNS_SERVER_IPV4 = "dns_server_ipv4"
        private const val DNS_SERVER_IPV6 = "dns_server_ipv6"
        private const val RESET_DNS_SERVER = "reset_dns_server"

        @JvmStatic
        fun hideChannelDiscovery(): Boolean {
            return UiHost.installed().quicksy() ||
                UiHost.installed().playStoreFlavor() ||
                Strings.isNullOrEmpty(Config.CHANNEL_DISCOVERY)
        }
    }
}
