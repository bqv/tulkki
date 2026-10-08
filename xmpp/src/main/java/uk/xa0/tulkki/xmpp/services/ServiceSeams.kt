package uk.xa0.tulkki.xmpp.services

import io.ipfs.cid.Cid
import org.openintents.openpgp.util.OpenPgpApi
import org.openintents.openpgp.util.OpenPgpServiceConnection
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.pep.Avatar
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.libs.AppSettingsRef
import uk.xa0.tulkki.xmpp.refs.DatabaseBackendRef
import uk.xa0.tulkki.libs.DownloadableFileRef
import uk.xa0.tulkki.xmpp.refs.FileBackendRef
import uk.xa0.tulkki.xmpp.refs.MessageRef
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tulkki: the small seam getters and the attachment copy, lifted out of `XmppConnectionService`
 *.
 *
 * Two of the chunk's five fields move whole — `COPY_TO_DOWNLOAD_EXECUTOR` and the cached
 * `mPgpEngine`, which nothing outside these methods read. `appSettings` and `fileBackend` stay on
 * the service because other chunks read them directly (`onStartCommand` and `MessageExpiry`, among
 * others), so they arrive by value and the service's own getters pass their field in; C04's low-ping
 * set, C23's transcode flag, C02's database-ready latch, C18's PGP engine factory and the public
 * `databaseBackend` slot likewise travel in, so no visibility was widened.
 *
 * The null contracts are the Java's: `getPgpEngine`/`getOpenPgpApi` answer **null** when OpenPGP is
 * unsupported or the connection is unbound, `copyAttachmentToDownloadsFolder`'s callback may be
 * null, and `saveCid` throws `BlockedMediaException` rather than answering. The two copies still run
 * on the single-thread executor and answer the callback from there.
 */
object ServiceSeams {

    private val COPY_TO_DOWNLOAD_EXECUTOR: Executor = Executors.newSingleThreadExecutor()
    private var mPgpEngine: PgpEnginePort? = null

    @JvmStatic
    fun generateFetchKey(account: AccountRef, avatar: Avatar): String =
        account.getJid().asBareJid().toString() + "_" + avatar.owner + "_" + avatar.sha1sum

    @JvmStatic
    fun isInLowPingTimeoutMode(account: AccountRef, lowPingTimeoutMode: MutableSet<Jid>): Boolean =
        synchronized(lowPingTimeoutMode) {
            lowPingTimeoutMode.contains(account.getJid().asBareJid())
        }

    @JvmStatic
    fun startOngoingVideoTranscodingForegroundNotification(
        ongoingVideoTranscoding: AtomicBoolean,
        toggleForegroundService: Runnable,
    ) {
        ongoingVideoTranscoding.set(true)
        toggleForegroundService.run()
    }

    @JvmStatic
    fun stopOngoingVideoTranscodingForegroundNotification(
        ongoingVideoTranscoding: AtomicBoolean,
        toggleForegroundService: Runnable,
    ) {
        ongoingVideoTranscoding.set(false)
        toggleForegroundService.run()
    }

    @JvmStatic
    fun areMessagesInitialized(restoredFromDatabaseLatch: CountDownLatch): Boolean =
        restoredFromDatabaseLatch.count == 0L

    @JvmStatic
    fun copyAttachmentToDownloadsFolder(
        message: MessageRef,
        callback: UiCallbackPort<Int>?,
        fileBackend: FileBackendRef,
    ) {
        COPY_TO_DOWNLOAD_EXECUTOR.execute {
            try {
                fileBackend.copyAttachmentToDownloadsFolder(message)
                callback?.success(-1)
            } catch (e: FileBackendRef.FileCopyException) {
                callback?.error(-1, e.resId)
            }
        }
    }

    @JvmStatic
    fun copyAttachmentToDownloadsFolder(
        file: File,
        callback: UiCallbackPort<Int>?,
        fileBackend: FileBackendRef,
    ) {
        COPY_TO_DOWNLOAD_EXECUTOR.execute {
            try {
                fileBackend.copyAttachmentToDownloadsFolder(file)
                callback?.success(-1)
            } catch (e: FileBackendRef.FileCopyException) {
                callback?.error(-1, e.resId)
            }
        }
    }

    @JvmStatic
    fun getPgpEngine(
        service: XmppConnectionService,
        pgpServiceConnection: OpenPgpServiceConnection?,
        pgpEngineFactory: PgpEngineFactory?,
    ): PgpEnginePort? {
        if (!Config.supportOpenPgp()) {
            return null
        }
        if (pgpServiceConnection != null && pgpServiceConnection.isBound) {
            if (mPgpEngine == null) {
                mPgpEngine =
                    pgpEngineFactory!!.create(
                        OpenPgpApi(service.applicationContext, pgpServiceConnection.service),
                        service,
                    )
            }
            return mPgpEngine
        }
        return null
    }

    @JvmStatic
    fun getOpenPgpApi(
        service: XmppConnectionService,
        pgpServiceConnection: OpenPgpServiceConnection?,
    ): OpenPgpApi? {
        if (!Config.supportOpenPgp()) {
            return null
        }
        if (pgpServiceConnection != null && pgpServiceConnection.isBound) {
            return OpenPgpApi(service, pgpServiceConnection.service)
        }
        return null
    }

    @JvmStatic fun getAppSettings(appSettings: AppSettingsRef): AppSettingsRef = appSettings

    @JvmStatic fun getFileBackend(fileBackend: FileBackendRef): FileBackendRef = fileBackend

    @JvmStatic
    fun getFileForCid(cid: Cid, databaseBackend: DatabaseBackendRef): DownloadableFileRef? =
        databaseBackend.getFileForCid(cid)

    @JvmStatic
    fun getUrlForCid(cid: Cid, databaseBackend: DatabaseBackendRef): String? =
        databaseBackend.getUrlForCid(cid)

    @JvmStatic
    fun saveCid(cid: Cid, file: File, databaseBackend: DatabaseBackendRef) {
        saveCid(cid, file, null, databaseBackend)
    }

    @JvmStatic
    fun saveCid(cid: Cid, file: File, url: String?, databaseBackend: DatabaseBackendRef) {
        if (databaseBackend.isBlockedMedia(cid)) {
            throw BlockedMediaException()
        }
        databaseBackend.saveCid(cid, file, url)
    }
}
