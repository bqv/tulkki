package uk.xa0.tulkki.ui.fragment.settings

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast

import androidx.activity.result.ActivityResultLauncher

import com.google.common.base.Optional

import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.AppSettings
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.activity.result.PickRingtone
import uk.xa0.tulkki.ui.host.UiHost
import uk.xa0.tulkki.ui.preferences.PreferenceItem
import uk.xa0.tulkki.ui.preferences.PreferenceScreenFragment
import uk.xa0.tulkki.xmpp.Config

/**
 * The notification rows `preferences_notifications.xml` held, with the file deleted with this class.
 *
 * <p>Three of the rows are the same row in different clothes according to the platform: on API 26 and
 * up the channel settings screen is the way in and the ringtone, heads-up, vibrate, LED and
 * foreground-service rows are gone; below it the reverse. The fullscreen-notification row is the third
 * case - it exists only where the platform can still ask for that permission and the app has not been
 * granted it. In the Compose tree each is simply absent when it does not apply, which is what
 * `isVisible = false` drew.
 *
 * <p>The ringtone rows keep their `PickRingtone` launchers verbatim, and the "no account yet" default
 * for `chat_requests` keeps the old one-off write through the row's own default: a switch that is off
 * with no value stored gets "strangers" written on bind, exactly where `requests.value = "strangers"`
 * wrote it.
 */
class NotificationsSettingsFragment : PreferenceScreenFragment() {

    private val pickNotificationToneLauncher: ActivityResultLauncher<Uri?> =
        registerForActivityResult(
            PickRingtone(RingtoneManager.TYPE_NOTIFICATION)
        ) { result ->
            if (result == null) {
                // do nothing. user aborted
                return@registerForActivityResult
            }
            val uri = PickRingtone.noneToNull(result)
            appSettings().setNotificationTone(uri)
            Log.i(Config.LOGTAG, "User set notification tone to " + uri)
        }
    private val pickRingtoneLauncher: ActivityResultLauncher<Uri?> =
        registerForActivityResult(
            PickRingtone(RingtoneManager.TYPE_RINGTONE)
        ) { result ->
            if (result == null) {
                // do nothing. user aborted
                return@registerForActivityResult
            }
            val uri = PickRingtone.noneToNull(result)
            appSettings().setRingtone(uri)
            Log.i(Config.LOGTAG, "User set ringtone to " + uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                UiHost.installed().recreateIncomingCallChannel(requireContext(), uri)
            }
        }

    override fun buildItems(): List<PreferenceItem> {
        val items = ArrayList<PreferenceItem>()
        val runsTwentySix = UiHost.installed().runsTwentySix()
        if (runsTwentySix) {
            items.add(
                PreferenceItem(
                    key = MESSAGE_NOTIFICATION_SETTINGS,
                    title = getString(R.string.pref_message_notification_settings),
                    summary = getString(R.string.pref_more_notification_settings_summary),
                    icon = R.drawable.ic_chat_24dp,
                )
            )
        } else {
            items.add(
                PreferenceItem(
                    key = AppSettings.NOTIFICATION_RINGTONE,
                    title = getString(R.string.pref_notification_sound),
                    summary = getString(R.string.pref_notification_sound_summary),
                )
            )
            items.add(
                switchItem(
                    AppSettings.NOTIFICATION_HEADS_UP,
                    R.string.pref_headsup_notifications,
                    R.string.pref_headsup_notifications_summary,
                    null,
                    resources.getBoolean(R.bool.headsup_notifications),
                )
            )
            items.add(
                switchItem(
                    AppSettings.NOTIFICATION_VIBRATE,
                    R.string.pref_vibrate,
                    R.string.pref_vibrate_summary,
                    null,
                    resources.getBoolean(R.bool.vibrate_on_notification),
                )
            )
            items.add(
                switchItem(
                    AppSettings.NOTIFICATION_LED,
                    R.string.pref_led,
                    R.string.pref_led_summary,
                    null,
                    resources.getBoolean(R.bool.led),
                )
            )
        }

        items.add(
            PreferenceItem(
                key = AppSettings.RINGTONE,
                title = getString(R.string.pref_ringtone),
                summary = getString(R.string.pref_call_ringtone_summary),
                icon = R.drawable.ic_phone_24dp,
            )
        )
        if (diallerIntegrationPossible()) {
            items.add(
                switchItem(
                    DIALLER_INTEGRATION_INCOMING,
                    R.string.pref_dialler_integration_incoming,
                    R.string.pref_dialler_integration_incoming_summary,
                    R.drawable.ic_phone_in_talk_24dp,
                    resources.getBoolean(R.bool.dialler_integration_incoming),
                )
            )
        }
        if (fullscreenNotificationOffered()) {
            items.add(
                PreferenceItem(
                    key = FULLSCREEN_NOTIFICATION,
                    title = getString(R.string.pref_fullscreen_notification),
                    summary = getString(R.string.pref_fullscreen_notification_summary),
                    icon = R.drawable.ic_smartphone_24dp,
                )
            )
        }
        if (UiHost.installed().selfManagedCallAvailable(requireContext())) {
            items.add(
                switchItem(
                    AppSettings.CALL_INTEGRATION,
                    R.string.pref_call_integration,
                    R.string.pref_call_integration_summary,
                    R.drawable.ic_mobile_friendly_24dp,
                    resources.getBoolean(uk.xa0.tulkki.data.R.bool.call_integration),
                )
            )
        }
        items.add(
            list(
                GRACE_PERIOD_LENGTH,
                R.string.pref_notification_grace_period,
                R.string.pref_notification_grace_period_summary,
                R.drawable.ic_notifications_paused_24dp,
                R.array.grace_periods,
                R.array.grace_periods_values,
                resources.getInteger(uk.xa0.tulkki.xmpp.R.integer.grace_period).toString(),
            )
        )
        items.add(chatRequests())
        items.add(
            switchItem(
                RING_FROM_STRANGERS,
                R.string.pref_ring_from_strangers,
                R.string.pref_ring_from_strangers_summary,
                R.drawable.ring_volume_24dp,
                resources.getBoolean(uk.xa0.tulkki.xmpp.R.bool.notifications_from_strangers),
            )
        )
        if (!runsTwentySix) {
            items.add(
                switchItem(
                    AppSettings.KEEP_FOREGROUND_SERVICE,
                    R.string.pref_keep_foreground_service,
                    R.string.pref_keep_foreground_service_summary,
                    R.drawable.ic_link_24dp,
                    resources.getBoolean(R.bool.enable_foreground_service),
                )
            )
        }
        return items
    }

    /**
     * `chat_requests` keeps the old one-off correction: while the strangers switch is off and the key
     * had no value, `requests.value = "strangers"` persisted it on bind. As a row's own default it is
     * written by the base's bind-time default pass and drawn the same way.
     */
    private fun chatRequests(): PreferenceItem {
        val strangers =
            preferences.getBoolean(STRANGERS_KEY, resources.getBoolean(uk.xa0.tulkki.xmpp.R.bool.notifications_from_strangers))
        val stored = preferences.getString(CHAT_REQUESTS, null)
        val default =
            if (!strangers && stored == null) {
                STRANGERS
            } else {
                getString(R.string.default_chat_requests)
            }
        val (labels, values) = listFromArrays(R.array.chat_requests_entries, R.array.chat_requests_values)
        val value = preferences.getString(CHAT_REQUESTS, default)
        return PreferenceItem(
            key = CHAT_REQUESTS,
            title = getString(R.string.pref_chat_requests),
            summary = listSummary(labels, values, value),
            icon = R.drawable.ic_domino_mask_24dp,
            kind = PreferenceItem.Kind.LIST,
            labels = labels,
            values = values,
            value = value,
            defaultValue = default,
        )
    }

    /** A `ListPreference` from two string arrays with the XML's static summary. */
    private fun list(
        key: String,
        title: Int,
        summary: Int,
        icon: Int,
        entriesRes: Int,
        valuesRes: Int,
        default: String,
    ): PreferenceItem {
        val (labels, values) = listFromArrays(entriesRes, valuesRes)
        return PreferenceItem(
            key = key,
            title = getString(title),
            summary = getString(summary),
            icon = icon,
            kind = PreferenceItem.Kind.LIST,
            labels = labels,
            values = values,
            value = preferences.getString(key, default),
            defaultValue = default,
        )
    }

    override fun onPreferenceClick(item: PreferenceItem) {
        when (item.key) {
            AppSettings.RINGTONE -> pickRingtone()
            AppSettings.NOTIFICATION_RINGTONE -> pickNotificationTone()
            MESSAGE_NOTIFICATION_SETTINGS -> startActivity(channelSettingsIntent())
            FULLSCREEN_NOTIFICATION -> manageAppUseFullScreen()
        }
    }

    /** The XML `<intent>` of the message-notification row, extras and all. */
    private fun channelSettingsIntent(): Intent =
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, MESSAGES_CHANNEL)

    override fun onSharedPreferenceChanged(key: String) {
        super.onSharedPreferenceChanged(key)
        if (key == AppSettings.KEEP_FOREGROUND_SERVICE) {
            requireService().toggleForegroundService()
        }
    }

    override fun onStart() {
        super.onStart()
        requireActivity().setTitle(R.string.notifications)
    }

    /**
     * Whether the platform could still offer the fullscreen-intent row: API 34 and up, and only while
     * the app has not been granted the permission - the old `onResume` asked the same question again
     * after the platform's own screen closed, which a per-render reading does by construction.
     */
    private fun fullscreenNotificationOffered(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return false
        }
        return !notificationManager().canUseFullScreenIntent()
    }

    private fun diallerIntegrationPossible(): Boolean {
        if (Build.VERSION.SDK_INT < 23) {
            return false
        }
        return AccountRegistry.get().getAccounts().stream().anyMatch { a -> a.getGateways("pstn").size > 0 }
    }

    /**
     * The Java dereferenced `getSystemService`'s result, so a null manager was a nameless
     * NullPointerException; this is the same failure, named.
     */
    private fun notificationManager(): NotificationManager =
        requireContext().getSystemService(NotificationManager::class.java)
            ?: throw NullPointerException("no notification manager")

    private fun manageAppUseFullScreen() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return
        }
        val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
        intent.setData(Uri.parse(String.format("package:%s", requireContext().packageName)))
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(requireContext(), R.string.unsupported_operation, Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun pickNotificationTone() {
        val uri = appSettings().getNotificationTone()
        Log.i(Config.LOGTAG, "current notification tone: " + uri)
        pickNotificationToneLauncher.launch(uri)
    }

    private fun pickRingtone() {
        val channelRingtone: Optional<Uri>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            channelRingtone =
                UiHost.installed()
                    .currentIncomingCallChannel(requireContext())
                    .transform { channel -> PickRingtone.nullToNone(channel.sound) }
        } else {
            channelRingtone = Optional.absent<Uri>()
        }
        val uri: Uri?
        if (channelRingtone.isPresent) {
            uri = channelRingtone.get()
            Log.d(Config.LOGTAG, "ringtone came from channel")
        } else {
            uri = appSettings().getRingtone()
        }
        Log.i(Config.LOGTAG, "current ringtone: " + uri)
        try {
            pickRingtoneLauncher.launch(uri)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(requireActivity(), R.string.no_application_found, Toast.LENGTH_LONG)
                .show()
        }
    }

    private fun appSettings(): AppSettings {
        return AppSettings(requireContext())
    }

    companion object {
        private const val MESSAGE_NOTIFICATION_SETTINGS = "message_notification_settings"
        private const val DIALLER_INTEGRATION_INCOMING = "dialler_integration_incoming"
        private const val FULLSCREEN_NOTIFICATION = "fullscreen_notification"
        private const val GRACE_PERIOD_LENGTH = "grace_period_length"
        private const val CHAT_REQUESTS = "chat_requests"
        private const val RING_FROM_STRANGERS = "ring_from_strangers"

        /**
         * The key the *readers* use, which is not the switch's own key and never has been: the XML
         * switch is `ring_from_strangers` while this correction and the rest of the tree read
         * `notifications_from_strangers`. Kept as it is, because a key is load-bearing.
         */
        private const val STRANGERS_KEY = "notifications_from_strangers"
        private const val STRANGERS = "strangers"
        private const val MESSAGES_CHANNEL = "messages"
    }
}
