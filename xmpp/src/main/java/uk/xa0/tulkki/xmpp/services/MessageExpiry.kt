package uk.xa0.tulkki.xmpp.services

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import uk.xa0.tulkki.xmpp.refs.DatabaseBackendRef
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import java.util.function.BiConsumer

/**
 * Tulkki: message expiry, lifted out of `XmppConnectionService`.
 *
 * The service kept the timestamp of its last expiry pass in an `AtomicLong` and ran the pass behind
 * its database-writer executor; the deletion of the files that pass orphaned went to the
 * `FILE_ATTACHMENT_EXECUTOR`, and the next run was rescheduled as another writer task. All of that is
 * here, with the state the Java held as an instance field now on this object — the service is a
 * singleton, so the two are the same lifetime — and the executor, the file backend and the system
 * event receiver's class passed in, because those belong to other chunks (`C02`, `C21`, `C76`) that
 * do not move with this one.
 *
 * The two public methods keep the Java's guard order exactly: the backend is captured and
 * null-checked first (answering silently, never throwing), and in [scheduleNextExpiry] the capture
 * happens before the task is queued while the null check stays inside the task.
 */
object MessageExpiry {

    private val lastRun = AtomicLong(0)

    /** The elapsed-realtime reading [noteExpiryRun] last stored; what `onStartCommand` compares. */
    @JvmStatic
    fun lastExpiryRun(): Long = lastRun.get()

    /** Stores a fresh `SystemClock.elapsedRealtime()` reading, as the Java body's `set` did. */
    @JvmStatic
    fun noteExpiryRun() {
        lastRun.set(SystemClock.elapsedRealtime())
    }

    /**
     * Deletes the messages and exclusive files past their deletion date and reschedules the next
     * pass. Runs the body on [writer]; the file deletion is dispatched to the static
     * `XmppConnectionService.FILE_ATTACHMENT_EXECUTOR` through [deleteFiles], which is the service's
     * own `deleteFilesAsync` (chunk `C21`) and so stays on the service's side of the seam.
     */
    @JvmStatic
    fun expireOldMessages(
        service: XmppConnectionService,
        resetHasMessagesLeftOnServer: Boolean,
        writer: Executor,
        deleteFiles: BiConsumer<List<String>, String>,
    ) {
        // The Java's own two lines: `final DatabaseBackend backend = databaseBackend;
        // if (backend == null) return;`. The accessor is nullable (DatabaseReadiness), so this is a
        // real early return and not a dead elvis on a throwing non-null property.
        val backend = service.databaseBackend ?: return
        lastRun.set(SystemClock.elapsedRealtime())
        writer.execute {
            val timestamp = service.getAutomaticMessageDeletionDate()
            if (service.getAppSettings().isDeleteUnusedFiles()) {
                val exclusivePaths = backend.getExclusiveFilePathsExpiring(timestamp)
                if (exclusivePaths.isNotEmpty()) {
                    XmppConnectionService.FILE_ATTACHMENT_EXECUTOR.execute {
                        deleteFiles.accept(exclusivePaths, "background expiry")
                    }
                }
            }
            backend.expireOldMessages(timestamp)
            synchronized(service.getConversationList()) {
                for (conversation in service.getConversationList()) {
                    conversation.expireOldMessages(timestamp)
                    if (resetHasMessagesLeftOnServer) {
                        conversation.messagesLoaded().set(true)
                        conversation.setHasMessagesLeftOnServer(true)
                    }
                }
            }
            service.updateConversationUi()
            service.scheduleNextExpiry()
        }
    }

    /**
     * Arms the alarm for the backend's next expiration, on [writer]. [receiverClass] is the service's
     * `SystemEventPort.receiverClass()`, read by the caller because that port's accessor is chunk
     * `C76`'s; the action is the service's own public constant.
     */
    @JvmStatic
    fun scheduleNextExpiry(
        service: XmppConnectionService,
        writer: Executor,
        receiverClass: Class<*>,
    ) {
        // The Java captured the field before queueing and checked it inside the task; the check
        // stays inside here for the same reason - a slot that is un-installed between the capture
        // and the task's turn must still make the task a no-op.
        val backend: DatabaseBackendRef? = service.databaseBackend
        writer.execute {
            if (backend == null) return@execute
            val next = backend.getNextExpiration()
            if (next > 0) {
                val alarmManager = service.getSystemService(AlarmManager::class.java)
                    ?: return@execute
                val intent = Intent(service, receiverClass)
                intent.action = XmppConnectionService.ACTION_EXPIRE_MESSAGES
                val pendingIntent = PendingIntent.getBroadcast(
                    service,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        next,
                        pendingIntent
                    )
                } else {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, next, pendingIntent)
                }
            }
        }
    }
}
