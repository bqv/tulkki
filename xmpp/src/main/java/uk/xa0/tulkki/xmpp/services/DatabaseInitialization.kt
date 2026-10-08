package uk.xa0.tulkki.xmpp.services

import android.os.Handler
import android.util.Log
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.refs.DatabaseBackendRef
import uk.xa0.tulkki.xmpp.utils.EncryptionException
import java.util.function.Consumer

/**
 * Tulkki: the background database open, lifted out of `XmppConnectionService`
 *.
 *
 * The `db-init` thread's whole body. The Java's order is kept exactly: open, then the UnifiedPush
 * pre-warm (which must happen on this thread so its Argon2id derivation never lands on the main
 * thread), then the account reload; both failure paths post the same three steps back to the main
 * handler — clear the accounts, record the failure, release the callbacks — and return.
 *
 * The chunk owns no field. Everything it touches outside itself travels in as a value: the
 * `DataStatics` collection root, the main-looper `Handler`, and four small callbacks the service
 * binds to chunk C02's state (`DatabaseReadiness`'s needs-password flag, critical error and ready
 * callbacks, and `continueAfterDbInit`). Nothing is narrowed and no guard moves: the Java's
 * `EncryptionException` catch, the bare `Exception` catch around the pre-warm and the
 * `SQLiteNotADatabaseException` catch are the Java's, and the exception path still carries the
 * failure back as C02's critical error rather than throwing.
 */
object DatabaseInitialization {

    @JvmStatic
    fun initialize(
        service: XmppConnectionService,
        dataStatics: XmppConnectionService.DataStatics,
        mainHandler: Handler,
        onNeedsPassword: Runnable,
        onCriticalError: Consumer<EncryptionException>,
        onSettled: Runnable,
        onOpened: Consumer<DatabaseBackendRef>,
    ) {
        val backend: DatabaseBackendRef =
            try {
                dataStatics.openDatabase(service.applicationContext)
            } catch (e: EncryptionException) {
                mainHandler.post {
                    dataStatics.accounts().clear()
                    if (e.reason == EncryptionException.Reason.NEEDS_SESSION_PASSWORD) {
                        Log.i(
                            Config.LOGTAG,
                            "Database requires startup password — waiting for user",
                        )
                        onNeedsPassword.run()
                    } else {
                        Log.e(
                            Config.LOGTAG,
                            "Critical keystore failure during service startup",
                            e,
                        )
                        onCriticalError.accept(e)
                    }
                    onSettled.run()
                }
                return
            }
        // Pre-warm UnifiedPushDatabase on this background thread so its Argon2id key derivation
        // never runs on the main thread. UnifiedPushDatabase.getInstance() is called from
        // BroadcastReceiver.onReceive() (main thread) and the XMPP connection thread; without
        // pre-warming, the first call from either thread triggers 2-4s of Argon2id computation
        // and can cause an ANR if it races with a main-thread caller holding the class lock.
        try {
            dataStatics.warmUpUnifiedPushDatabase(service.applicationContext)
        } catch (e: Exception) {
            Log.w(Config.LOGTAG, "Failed to pre-initialize UnifiedPushDatabase (non-fatal)", e)
        }

        Log.d(Config.LOGTAG, "restoring accounts...")
        try {
            dataStatics.accounts().reload(service.applicationContext)
        } catch (e: net.zetetic.database.sqlcipher.SQLiteNotADatabaseException) {
            mainHandler.post {
                Log.e(Config.LOGTAG, "Wrong database key on getAccounts", e)
                dataStatics.closeDatabase()
                dataStatics.clearSessionPassword()
                onCriticalError.accept(
                    EncryptionException(
                        "Wrong database key",
                        e,
                        EncryptionException.Reason.DB_WRONG_KEY,
                    ),
                )
                dataStatics.accounts().clear()
                onSettled.run()
            }
            return
        }
        mainHandler.post { onOpened.accept(backend) }
    }
}
