package uk.xa0.tulkki.xmpp.services

import android.app.KeyguardManager
import android.app.NotificationManager
import android.media.AudioManager
import android.os.PowerManager
import android.util.Log
import java.util.concurrent.Executor
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.R
import uk.xa0.tulkki.libs.PresenceRef

/**
 * Tulkki: the presence preferences and the derived presence, lifted out of `XmppConnectionService`
 *.
 *
 * The preference reads go through the service's own public getters (chunk `C47`) and the platform
 * services are read through the service as a `Context`, so nothing is injected for any of them. Two
 * things belong to chunks that do not move and so travel in:
 *
 *  * the **database-writer executor** (chunk `C02`), which carries the account write of the two
 *    mutators; and
 *  * the **notification port** (chunk `C70`), which is a private field of the service and is passed
 *    by hand rather than read through `getNotificationService()`, whose `require` would have turned
 *    the Java's null-dereference into an `IllegalStateException`.
 *
 * Nothing here takes a lock, all of it runs on the caller's thread as before, and the two
 * fail-toward-false platform paths — a `RuntimeException` from `PowerManager.isInteractive()`, a
 * `Throwable` from the ringer read — answer exactly what the Java answered.
 */
object PresencePreferences {

    @JvmStatic
    fun dndOnSilentMode(service: XmppConnectionService): Boolean =
        service.getBooleanPreference(
            XmppConnectionService.DataStatics.DND_ON_SILENT_MODE,
            R.bool.dnd_on_silent_mode,
        )

    @JvmStatic
    fun manuallyChangePresence(service: XmppConnectionService): Boolean =
        service.getBooleanPreference(
            XmppConnectionService.DataStatics.MANUALLY_CHANGE_PRESENCE,
            R.bool.manually_change_presence,
        )

    @JvmStatic
    fun treatVibrateAsSilent(service: XmppConnectionService): Boolean =
        service.getBooleanPreference(
            XmppConnectionService.DataStatics.TREAT_VIBRATE_AS_SILENT,
            R.bool.treat_vibrate_as_silent,
        )

    @JvmStatic
    fun awayWhenScreenLocked(service: XmppConnectionService): Boolean =
        service.getBooleanPreference(
            XmppConnectionService.DataStatics.AWAY_WHEN_SCREEN_IS_OFF,
            R.bool.away_when_screen_off,
        )

    /**
     * A nullable return, because `SharedPreferences.getString` is declared `@Nullable` and the Java
     * body handed its answer straight back; the default is the resource, which is why it is
     * non-null in practice.
     */
    @JvmStatic
    fun getCompressPicturesPreference(service: XmppConnectionService): String? =
        service.getPreferences()
            .getString(
                "picture_compression",
                service.getResources().getString(R.string.picture_compression),
            )

    /** DND beats AWAY beats ONLINE, and every predicate is read left to right as before. */
    @JvmStatic
    fun getTargetPresence(service: XmppConnectionService): PresenceRef.StatusRef {
        if (dndOnSilentMode(service) && isPhoneSilenced(service)) {
            return PresenceRef.StatusRef.DND
        } else if (awayWhenScreenLocked(service) && isScreenLocked(service)) {
            return PresenceRef.StatusRef.AWAY
        } else {
            return PresenceRef.StatusRef.ONLINE
        }
    }

    @JvmStatic
    fun isScreenLocked(service: XmppConnectionService): Boolean {
        val keyguardManager = service.getSystemService(KeyguardManager::class.java)
        val powerManager = service.getSystemService(PowerManager::class.java)
        val locked = keyguardManager != null && keyguardManager.isKeyguardLocked()
        val interactive: Boolean
        try {
            interactive = powerManager != null && powerManager.isInteractive()
        } catch (e: Exception) {
            return false
        }
        return locked || !interactive
    }

    @JvmStatic
    fun isPhoneSilenced(service: XmppConnectionService): Boolean {
        val notificationManager = service.getSystemService(NotificationManager::class.java)
        val filter = notificationManager?.getCurrentInterruptionFilter()
            ?: NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        val notificationDnd = filter >= NotificationManager.INTERRUPTION_FILTER_PRIORITY
        val audioManager = service.getSystemService(AudioManager::class.java)
        val ringerMode = audioManager?.getRingerMode() ?: AudioManager.RINGER_MODE_NORMAL
        try {
            return if (treatVibrateAsSilent(service)) {
                notificationDnd || ringerMode != AudioManager.RINGER_MODE_NORMAL
            } else {
                notificationDnd || ringerMode == AudioManager.RINGER_MODE_SILENT
            }
        } catch (throwable: Throwable) {
            Log.d(
                Config.LOGTAG,
                "platform bug in isPhoneSilenced (" + throwable.message + ")",
            )
            return notificationDnd
        }
    }

    /**
     * Resets the connection attempt count of every account that has an error (or all of them) and
     * asks the database writer to clear the error flag. The null backend check stays inside the
     * queued task, as the Java had it.
     */
    @JvmStatic
    fun resetAllAttemptCounts(
        service: XmppConnectionService,
        reallyAll: Boolean,
        retryImmediately: Boolean,
        writer: Executor,
        notification: NotificationPort,
    ) {
        Log.d(Config.LOGTAG, "resetting all attempt counts")
        for (account in XmppConnectionService.dataStatics().accounts().getAccounts()) {
            if (account.hasErrorStatus() || reallyAll) {
                val connection = account.getXmppConnection()
                if (connection != null) {
                    connection.resetAttemptCount(retryImmediately)
                }
            }
            if (account.setShowErrorNotification(true)) {
                writer.execute {
                    if (service.hasDatabaseBackend()) {
                        (service.databaseBackend ?: throw NullPointerException("database backend is not open")).updateAccount(account)
                    }
                }
            }
        }
        notification.updateErrorNotification()
    }

    /** Clears the error flag of every account that has one; the null backend check stays inside. */
    @JvmStatic
    fun dismissErrorNotifications(service: XmppConnectionService, writer: Executor) {
        for (account in XmppConnectionService.dataStatics().accounts().getAccounts()) {
            if (account.hasErrorStatus()) {
                Log.d(
                    Config.LOGTAG,
                    "" + account.getJid().asBareJid() + ": dismissing error notification",
                )
                if (account.setShowErrorNotification(false)) {
                    writer.execute {
                        val backend = service.databaseBackend
                        if (backend != null) backend.updateAccount(account)
                    }
                }
            }
        }
    }
}
