package uk.xa0.tulkki.ui.settings

import android.content.Context
import android.os.Build

import com.google.common.base.Strings

import uk.xa0.tulkki.ui.BuildConfig
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.preferences.PreferenceItem

/**
 * The main settings list as a value: the nine doors and the about row `preferences_main.xml`
 * described, as [PreferenceItem]s, with nothing but a `Context` between them and a screen.
 *
 * <p>It is separated from [uk.xa0.tulkki.ui.fragment.settings.MainSettingsFragment] for the reason
 * [SettingsPage] is separated from the Tulkki screen's host: a screenshot cell must be able to render
 * **the real list**, and a fixture that built its own would be a picture of a second list that could
 * drift from the one the owner sees. So the three readings the list depends on are parameters - the
 * interpreter's switch, whether channel discovery is hidden, and whether this build has a push server -
 * and the host passes what it reads.
 *
 * <p>Five of the ten rows are doors. The XML gave those five no `android:key` because a preference
 * that stores nothing needs none; a Compose row routes on something, so each gets one of the constants
 * below. They are written nowhere and read nowhere but here, so they cannot collide with a stored key.
 * The five keys the XML did give - `connection`, `backup`, `up`, `about`, `tulkki` - are kept exactly.
 */
object MainSettingsPage {

    /** The five door keys the XML never had, and the five stored ones it did. */
    const val KEY_INTERFACE = "interface"

    const val KEY_SECURITY = "security"
    const val KEY_PRIVACY = "privacy"
    const val KEY_NOTIFICATIONS = "notifications"
    const val KEY_ATTACHMENTS = "attachments"
    const val KEY_CONNECTION = "connection"
    const val KEY_BACKUP = "backup"
    const val KEY_UP = "up"
    const val KEY_ABOUT = "about"
    const val KEY_TULKKI = "tulkki"

    /**
     * The list, in the XML's order.
     *
     * @param interpreterEnabled whether Tulkki's interpreter is on, which is what the Tulkki row's
     *     summary says - read live by the host, because this screen is resumed after the trip that
     *     changed it.
     * @param channelDiscoveryHidden whether [uk.xa0.tulkki.ui.fragment.settings.ConnectionSettingsFragment.hideChannelDiscovery]
     *     answers true, which shortens the connection row's summary.
     * @param upVisible whether this build has a push server at all; the XML row was `isVisible` false
     *     without one, which here is simply absence.
     */
    fun items(
        context: Context,
        interpreterEnabled: Boolean,
        channelDiscoveryHidden: Boolean,
        upVisible: Boolean,
    ): List<PreferenceItem> {
        val items = ArrayList<PreferenceItem>()
        items.add(door(context, KEY_INTERFACE, R.drawable.ic_touch_app_24dp, R.string.pref_title_interface, R.string.pref_summary_appearance))
        items.add(door(context, KEY_SECURITY, R.drawable.ic_security_24dp, R.string.pref_title_security, R.string.pref_summary_security))
        items.add(door(context, KEY_PRIVACY, R.drawable.ic_privacy_tip_24dp, R.string.pref_privacy, R.string.pref_privacy_summary))
        items.add(door(context, KEY_NOTIFICATIONS, R.drawable.ic_notifications_24dp, R.string.notifications, R.string.pref_notifications_summary))
        items.add(door(context, KEY_ATTACHMENTS, R.drawable.ic_attachment_24dp, R.string.pref_attachments, R.string.pref_attachments_summary))
        items.add(
            door(
                context,
                KEY_CONNECTION,
                R.drawable.ic_settings_ethernet_24dp,
                R.string.pref_connection_options,
                if (channelDiscoveryHidden) R.string.pref_connection_summary else R.string.pref_connection_summary_w_cd,
            )
        )
        items.add(door(context, KEY_BACKUP, R.drawable.ic_archive_24dp, R.string.backup, R.string.pref_backup_summary))
        if (upVisible) {
            items.add(door(context, KEY_UP, R.drawable.ic_cloud_sync_24dp, R.string.unified_push_distributor, R.string.unified_push_summary))
        }
        items.add(
            PreferenceItem(
                key = KEY_ABOUT,
                title = context.getString(R.string.title_activity_about_x, BuildConfig.APP_NAME),
                summary = aboutSummary(),
                icon = R.drawable.tulkki_logo_signet,
                copyable = true,
            )
        )
        items.add(
            door(
                context,
                KEY_TULKKI,
                R.drawable.ic_tulkki_translate_24dp,
                R.string.tulkki_settings_title,
                if (interpreterEnabled) R.string.tulkki_settings_summary else R.string.tulkki_settings_summary_off,
            )
        )
        return items
    }

    /** The build line the about row has always shown, argument for argument. */
    fun aboutSummary(): String =
        String.format(
            "%s %s %s @ %s · %s · %s",
            BuildConfig.APP_NAME,
            BuildConfig.VERSION_NAME,
            im.conversations.webrtc.BuildConfig.WEBRTC_VERSION,
            Strings.nullToEmpty(Build.MANUFACTURER),
            Strings.nullToEmpty(Build.DEVICE),
            Strings.nullToEmpty(Build.VERSION.RELEASE),
        )

    private fun door(context: Context, key: String, icon: Int, title: Int, summary: Int): PreferenceItem =
        PreferenceItem(
            key = key,
            title = context.getString(title),
            summary = context.getString(summary),
            icon = icon,
        )
}
