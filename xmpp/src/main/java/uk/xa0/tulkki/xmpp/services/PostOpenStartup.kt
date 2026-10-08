package uk.xa0.tulkki.xmpp.services

import android.util.Log
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.refs.DatabaseBackendRef
import uk.xa0.tulkki.xmpp.utils.EncryptionException
import java.util.function.BooleanSupplier
import java.util.function.Consumer

/**
 * Tulkki: the post-open head of `continueAfterDbInit`, lifted out of `XmppConnectionService`
 *.
 *
 * Slices 1 and 2 took the scheduling tail and the post-restore wiring; this is what runs first, once
 * the background open has handed the service its backend and the accounts are loaded: install the
 * backend, apply the stored account colours, record whether any account is enabled and toggle the
 * profile-picture activity from that one read, reconfigure the push distributor, hand the phone
 * accounts to call integration, then restore the conversations from the database. It mirrors what was
 * previously the second half of `onCreate()`.
 *
 * The reaches travel in by value, as the first two slices' did. `restoreFromDatabase` (C27) and
 * `hasEnabledAccounts` (C38) are **private** members of other chunks and C76's `systemEvent()` is a
 * **private** accessor; none is widened - the service binds them as a [Runnable], a [BooleanSupplier]
 * and the resolved port. `toggleSetProfilePictureActivity` is C33's private method and arrives as a
 * [Consumer] the service binds, exactly as C33's own `syncEnabledAccountSetting` call site does.
 * `getPreferences()`, `reconfigurePushDistributor()` and `callIntegration()` are already public on the
 * service and are called directly, as slice 2 called `startContactObserver`.
 *
 * The Java's guard order is the Java's: the backend is installed before anything reads it, the
 * enabled-accounts value is computed once and written and toggled from that one read, call
 * integration runs only when the device has the feature, and the wrong-key catch is terminal - it
 * closes the database, un-installs the backend ([DatabaseReadiness.invalidate], the Java's
 * `databaseBackend = null`), clears the session password, records the critical error and releases the
 * ready callbacks, then answers **false**. The caller returns on false, so the post-restore wiring and
 * the scheduling tail still never run after a wrong key, and the ready flag is still never set on that
 * path: that ordering is behaviour, not structure.
 */
object PostOpenStartup {

    @JvmStatic
    fun run(
        service: XmppConnectionService,
        backend: DatabaseBackendRef,
        systemEvent: SystemEventPort,
        hasEnabledAccounts: BooleanSupplier,
        toggleSetProfilePictureActivity: Consumer<Boolean>,
        restoreFromDatabase: Runnable,
    ): Boolean {
        DatabaseReadiness.install(backend)

        for (account in XmppConnectionService.dataStatics().accounts().getAccounts()) {
            val color = service.getPreferences().getInt("account_color:" + account.getUuid(), 0)
            if (color != 0) account.setColor(color)
        }
        val editor = service.getPreferences().edit()
        val enabledAccounts = hasEnabledAccounts.asBoolean
        editor.putBoolean(systemEvent.settingEnabledAccounts(), enabledAccounts).apply()
        editor.apply()
        toggleSetProfilePictureActivity.accept(enabledAccounts)
        service.reconfigurePushDistributor()

        if (service.callIntegration().hasSystemFeature(service)) {
            service.callIntegration().togglePhoneAccountsAsync(
                service,
                XmppConnectionService.dataStatics().accounts().getAccounts(),
            )
        }

        try {
            restoreFromDatabase.run()
        } catch (e: net.zetetic.database.sqlcipher.SQLiteNotADatabaseException) {
            Log.e(Config.LOGTAG, "Wrong database key on restoreFromDatabase", e)
            XmppConnectionService.dataStatics().closeDatabase()
            DatabaseReadiness.invalidate()
            XmppConnectionService.dataStatics().clearSessionPassword()
            DatabaseReadiness.setCriticalError(
                EncryptionException(
                    "Wrong database key",
                    e,
                    EncryptionException.Reason.DB_WRONG_KEY,
                ),
            )
            DatabaseReadiness.notifyReadyCallbacks()
            return false
        }

        return true
    }
}
