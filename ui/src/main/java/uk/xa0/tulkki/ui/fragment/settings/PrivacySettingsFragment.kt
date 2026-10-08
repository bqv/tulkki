package uk.xa0.tulkki.ui.fragment.settings

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

import uk.xa0.tulkki.data.AppSettings
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.preferences.PreferenceItem
import uk.xa0.tulkki.ui.preferences.PreferenceScreenFragment

/**
 * The privacy switches `preferences_privacy.xml` held, over the same six categories, with the file
 * deleted with this class.
 *
 * <p>Three things are not a plain row. `update_track_longer_than` draws its entries from
 * `R.array.track_duration_values`, an int array, named by the seconds plural the old `setValues` used.
 * `load_now_playing_from_system` opens the platform's notification-listener screen when it is switched
 * on without access, and the old `onResume` switched it back off when access had been revoked while
 * the app was away. And the `send_crash_reports` row and its category are omitted entirely when Sentry
 * is not on the classpath, exactly as the old `Class.forName` check did.
 *
 * <p>The two `android:dependency` chains are androidx's own rule: a dependent row is enabled iff its
 * master's `disableDependentsState` says so, and the master's own greyed state does not travel down the
 * chain (`manually_change_presence` disables its dependents while it is checked).
 */
class PrivacySettingsFragment : PreferenceScreenFragment() {

    /** Whether Sentry is on the classpath at all; the old check ran once, at screen creation. */
    private val sentryPresent: Boolean by lazy {
        try {
            Class.forName("io.sentry.Sentry")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }

    override fun buildItems(): List<PreferenceItem> {
        val items = ArrayList<PreferenceItem>()
        val manualPresence =
            preferences.getBoolean(
                AppSettings.MANUALLY_CHANGE_PRESENCE,
                resources.getBoolean(uk.xa0.tulkki.xmpp.R.bool.manually_change_presence),
            )
        val dndOnSilentMode =
            preferences.getBoolean(
                AppSettings.DND_ON_SILENT_MODE,
                resources.getBoolean(uk.xa0.tulkki.xmpp.R.bool.dnd_on_silent_mode),
            )

        items.add(header(R.string.pref_category_engagement_notifications))
        items.add(
            switch(
                AppSettings.CONFIRM_MESSAGES,
                R.string.pref_confirm_messages,
                R.string.pref_confirm_messages_summary,
                R.drawable.ic_done_all_24dp,
                uk.xa0.tulkki.xmpp.R.bool.confirm_messages,
            )
        )
        items.add(
            switch(
                CHAT_STATES,
                R.string.pref_chat_states,
                R.string.pref_chat_states_summary,
                R.drawable.ic_keyboard_24dp,
                uk.xa0.tulkki.xmpp.R.bool.chat_states,
            )
        )
        items.add(
            switch(
                AppSettings.BROADCAST_LAST_ACTIVITY,
                R.string.pref_broadcast_last_activity,
                R.string.pref_broadcast_last_activity_summary,
                R.drawable.ic_visibility_24dp,
                uk.xa0.tulkki.xmpp.R.bool.last_activity,
            )
        )

        items.add(header(R.string.pref_category_interaction))
        items.add(
            switch(
                AppSettings.ALLOW_MESSAGE_CORRECTION,
                R.string.pref_allow_message_correction,
                R.string.pref_allow_message_correction_summary,
                R.drawable.ic_edit_24dp,
                uk.xa0.tulkki.xmpp.R.bool.allow_message_correction,
            )
        )
        items.add(
            switch(
                ALLOW_UNENCRYPTED_REACTIONS,
                R.string.pref_allow_unencrypted_reactions,
                R.string.pref_allow_unencrypted_reactions_summary,
                R.drawable.outline_emoji_emotions_24,
                R.bool.allow_unencrypted_reactions,
            )
        )
        items.add(
            switch(
                DISABLE_REACTIONS_FALLBACK,
                R.string.pref_disable_reactions_fallback_title,
                R.string.pref_disable_reactions_fallback_summary,
                R.drawable.ic_add_reaction_24dp,
                uk.xa0.tulkki.xmpp.R.bool.disable_reactions_fallback,
            )
        )

        items.add(header(R.string.pref_ui_options))
        // The privacy row's key is `send_link_previews`, *not* the interface screen's
        // `show_link_previews`: two rows, two keys, and this one is the one the send path reads.
        items.add(
            switch(
                SEND_LINK_PREVIEWS,
                R.string.pref_send_link_previews,
                R.string.pref_send_link_previews_summary,
                R.drawable.rounded_link_24,
                R.bool.send_link_previews,
            )
        )
        items.add(
            switch(
                LOAD_IMAGE_FROM_ANY_LINK,
                R.string.pref_load_image_from_any_link_title,
                R.string.pref_load_image_from_any_link_summary,
                R.drawable.ic_image_24dp,
                R.bool.load_image_from_any_link,
            )
        )

        items.add(header(R.string.pref_presence_settings))
        items.add(
            switch(
                AppSettings.MANUALLY_CHANGE_PRESENCE,
                R.string.pref_manually_change_presence,
                R.string.pref_manually_change_presence_summary,
                R.drawable.rounded_info_24,
                uk.xa0.tulkki.xmpp.R.bool.manually_change_presence,
            )
        )
        // `manually_change_presence` carries `disableDependentsState="true"`: its dependents are
        // enabled while it is unchecked.
        items.add(
            switch(
                AppSettings.AWAY_WHEN_SCREEN_IS_OFF,
                R.string.pref_away_when_screen_off,
                R.string.pref_away_when_screen_off_summary,
                R.drawable.ic_screen_lock_portrait_24dp,
                uk.xa0.tulkki.xmpp.R.bool.away_when_screen_off,
                enabled = !manualPresence,
            )
        )
        items.add(
            switch(
                AppSettings.DND_ON_SILENT_MODE,
                R.string.pref_dnd_on_silent_mode,
                R.string.pref_dnd_on_silent_mode_summary,
                R.drawable.ic_do_not_disturb_on_24dp,
                uk.xa0.tulkki.xmpp.R.bool.dnd_on_silent_mode,
                enabled = !manualPresence,
            )
        )
        // The chain stops at the master's value: androidx's `TwoStatePreference` answers the dependents
        // from its checked state alone, so this row is enabled exactly while silent mode means DND.
        items.add(
            switch(
                AppSettings.TREAT_VIBRATE_AS_SILENT,
                R.string.pref_treat_vibrate_as_silent,
                R.string.pref_treat_vibrate_as_dnd_summary,
                R.drawable.ic_vibration_24dp,
                uk.xa0.tulkki.xmpp.R.bool.treat_vibrate_as_silent,
                enabled = dndOnSilentMode,
            )
        )

        items.add(header(R.string.pref_now_playing))
        items.add(
            switchItem(
                LOAD_NOW_PLAYING_FROM_SYSTEM,
                R.string.pref_send_now_playing,
                R.string.pref_auto_update_now_playing,
                R.drawable.rounded_play_arrow_36,
                false,
            )
        )
        items.add(updateTrackLongerThan())

        if (!sentryPresent) {
            items.add(header(R.string.pref_category_application))
            items.add(
                switch(
                    AppSettings.SEND_CRASH_REPORTS,
                    R.string.pref_send_crash_reports,
                    R.string.pref_never_send_crash_summary,
                    R.drawable.ic_report_24dp,
                    uk.xa0.tulkki.data.R.bool.send_crash_reports,
                )
            )
            items.add(
                switch(
                    AppSettings.LOAD_PROVIDERS_EXTERNAL,
                    R.string.pref_load_providers_list_external,
                    R.string.pref_load_providers_list_external_summary,
                    R.drawable.xmpp_providers,
                    R.bool.load_providers_list_external,
                )
            )
        }
        return items
    }

    /** The one list row, named by the seconds plural `setValues` used. */
    private fun updateTrackLongerThan(): PreferenceItem {
        val (labels, values) =
            listFromIntArray(R.array.track_duration_values) { value ->
                if (value <= 0) {
                    getString(R.string.ignore)
                } else {
                    resources.getQuantityString(R.plurals.seconds, value, value)
                }
            }
        val default = resources.getInteger(R.integer.update_track_longer_than_secs).toString()
        val value = preferences.getString(UPDATE_TRACK_LONGER_THAN, default)
        return PreferenceItem(
            key = UPDATE_TRACK_LONGER_THAN,
            title = getString(R.string.pref_update_track_longer_than),
            summary = listSummary(labels, values, value),
            kind = PreferenceItem.Kind.LIST,
            labels = labels,
            values = values,
            value = value,
            defaultValue = default,
        )
    }

    override fun onPreferenceChange(item: PreferenceItem, newValue: Any?): Boolean {
        if (item.key == LOAD_NOW_PLAYING_FROM_SYSTEM &&
            java.lang.Boolean.TRUE == newValue &&
            !hasNotificationAccess()
        ) {
            // The switch still takes the value: the screen this opens is where access is granted, and
            // the old listener returned true exactly as this does.
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        if (preferences.getBoolean(LOAD_NOW_PLAYING_FROM_SYSTEM, false) && !hasNotificationAccess()) {
            putBoolean(LOAD_NOW_PLAYING_FROM_SYSTEM, false)
        }
    }

    override fun onSharedPreferenceChanged(key: String) {
        super.onSharedPreferenceChanged(key)
        when (key) {
            AppSettings.AWAY_WHEN_SCREEN_IS_OFF,
            AppSettings.MANUALLY_CHANGE_PRESENCE -> {
                requireService().toggleScreenEventReceiver()
                requireService().refreshAllPresences()
            }
            AppSettings.CONFIRM_MESSAGES,
            AppSettings.BROADCAST_LAST_ACTIVITY,
            AppSettings.ALLOW_MESSAGE_CORRECTION,
            AppSettings.DND_ON_SILENT_MODE,
            AppSettings.TREAT_VIBRATE_AS_SILENT -> {
                requireService().refreshAllPresences()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        requireActivity().setTitle(R.string.pref_privacy)
    }

    // Original implementation by Upendra Shah: https://stackoverflow.com/questions/47673127
    //
    // Tulkki: the grant is a *component*, not a package. The platform stores a flattened
    // ComponentName in Settings.Secure and binds exactly that component, so a rename leaves the
    // stored entry naming a class that no longer exists while the package name is still inside the
    // string - which is why `contains(packageName)` used to answer "access is present" and the
    // switch below then refused to open the screen that is the app's only remedy. The decision is in
    // NotificationListenerGrant, where a JVM test pins it; what cannot move to the JVM is the lookup
    // itself.
    private fun hasNotificationAccess(): Boolean {
        val context = requireContext()
        val contentResolver = context.contentResolver
        val enabledNotificationListeners =
            Settings.Secure.getString(contentResolver, "enabled_notification_listeners")

        return NotificationListenerGrant.granted(
            context.packageName,
            enabledNotificationListeners,
            this::componentExists,
        )
    }

    /** Whether the platform could still bind a listener with this class name. */
    private fun componentExists(className: String): Boolean {
        return try {
            requireContext()
                .packageManager
                .getServiceInfo(ComponentName(requireContext().packageName, className), 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** A `SwitchPreferenceCompat` over one of the module `@bool` defaults. */
    private fun switch(
        key: String,
        title: Int,
        summary: Int,
        icon: Int,
        defaultRes: Int,
        enabled: Boolean = true,
    ): PreferenceItem =
        switchItem(key, title, summary, icon, resources.getBoolean(defaultRes), enabled)

    private fun header(title: Int): PreferenceItem =
        PreferenceItem(title = getString(title), kind = PreferenceItem.Kind.HEADER)

    companion object {
        private const val CHAT_STATES = "chat_states"
        private const val SEND_LINK_PREVIEWS = "send_link_previews"
        private const val ALLOW_UNENCRYPTED_REACTIONS = "allow_unencrypted_reactions"
        private const val DISABLE_REACTIONS_FALLBACK = "disable_reactions_fallback"
        private const val LOAD_IMAGE_FROM_ANY_LINK = "load_image_from_any_link"
        private const val LOAD_NOW_PLAYING_FROM_SYSTEM = "load_now_playing_from_system"
        private const val UPDATE_TRACK_LONGER_THAN = "update_track_longer_than"
    }
}
