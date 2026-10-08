package uk.xa0.tulkki.xmpp.services

import android.util.Log
import java.util.concurrent.Executor
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.OnUpdateBlocklist
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.ConversationalRef

/**
 * Tulkki: history clearing, blocking and the unblock, lifted out of `XmppConnectionService`
 *.
 *
 * Two **private** things arrive by hand: the database-writer executor (C02) and the shared
 * `conversationList` (C29), on which `removeBlockedConversationEntries` still locks with
 * `synchronized (list)`. The attachment executor is passed as a value too rather than read from
 * C09's public static, so nothing here reaches a field another chunk still owns. The rest
 * (`getAppSettings`, `getFileBackend`, `sendIqPacket`, `getIqGenerator`, `updateBlocklistUi`,
 * `updateConversationUi`, `markRead`, `updateConversation`) is a public service member.
 *
 * The Java's null guards are the Java's: `sendBlockRequest` and `sendUnblockRequest` each answer
 * `false`/do nothing for a null blockable before touching the JID, the full-JID case answers `false`
 * rather than archiving, and `deleteFilesAsync` swallows every per-path failure exactly as before.
 */
object ConversationHistory {

    @JvmStatic
    fun clearConversationHistory(
        service: XmppConnectionService,
        conversation: ConversationRef,
        writerExecutor: Executor,
        fileAttachmentExecutor: Executor,
    ) {
        val clearDate: Long
        val reference: String?
        if (conversation.countMessages() > 0) {
            val latestMessage = conversation.getLatestMessage()
            clearDate = latestMessage.getTimeSent() + 1000
            reference = latestMessage.getServerMsgId()
        } else {
            clearDate = System.currentTimeMillis()
            reference = null
        }
        conversation.clearMessages()
        conversation.setHasMessagesLeftOnServer(false) // avoid messages getting loaded through mam
        conversation.setLastClearHistory(clearDate, reference)
        val deleteFiles = service.getAppSettings().isDeleteUnusedFiles()
        val runnable =
            Runnable {
                val exclusiveFilePaths: List<String>?
                if (deleteFiles) {
                    exclusiveFilePaths = (service.databaseBackend ?: throw NullPointerException("database backend is not open")).getExclusiveFilePaths(conversation)
                } else {
                    exclusiveFilePaths = null
                }
                (service.databaseBackend ?: throw NullPointerException("database backend is not open")).deleteMessagesInConversation(conversation)
                (service.databaseBackend ?: throw NullPointerException("database backend is not open")).updateConversation(conversation)
                if (exclusiveFilePaths != null && !exclusiveFilePaths.isEmpty()) {
                    val jid = (conversation.getJid() ?: throw NullPointerException("conversation has no jid")).asBareJid().toString()
                    fileAttachmentExecutor.execute {
                        deleteFilesAsync(service, exclusiveFilePaths, jid)
                    }
                }
            }
        writerExecutor.execute(runnable)
    }

    @JvmStatic
    fun deleteFilesAsync(
        service: XmppConnectionService,
        exclusiveFilePaths: List<String>,
        jid: String,
    ) {
        var deletedCount = 0
        for (relativePath in exclusiveFilePaths) {
            try {
                val file = service.getFileBackend().getFileForPath(relativePath).asFile()
                if (file.exists() && file.delete()) {
                    deletedCount++
                    service.getFileBackend().updateMediaScanner(file)
                    Log.d(Config.LOGTAG, "deleted file: " + file.getAbsolutePath())
                }
            } catch (e: Exception) {
                Log.w(Config.LOGTAG, "error deleting file for path " + relativePath, e)
            }
        }
        Log.d(
            Config.LOGTAG,
            "deleted " + deletedCount + "/" + exclusiveFilePaths.size + " exclusive files for " + jid,
        )
    }

    // Tulkki (2026-10-08, lane `G`): `BlockableRef` is gone and its three reads arrive as three
    // values. The Java's own order is kept exactly: a null thing, or a null `getBlockedJid()`,
    // answered `false`, and a null `getAccount()` met this service's non-null `sendIqPacket`
    // parameter and threw - so the guard is on `blockedJid` first and the account is checked after.
    @JvmStatic
    fun sendBlockRequest(
        service: XmppConnectionService,
        account: AccountRef?,
        blockedJid: Jid?,
        reportSpam: Boolean,
        serverMsgId: String?,
        conversationList: MutableList<ConversationRef>,
    ): Boolean {
        if (blockedJid == null) {
            return false
        }
        val blockAccount = account ?: throw NullPointerException()
        service.sendIqPacket(
            blockAccount,
            service.getIqGenerator().generateSetBlockRequest(blockedJid, reportSpam, serverMsgId),
        ) { response ->
            if (response.getType() == Iq.Type.RESULT) {
                blockAccount.getBlocklist().add(blockedJid)
                service.updateBlocklistUi(OnUpdateBlocklist.Status.BLOCKED)
            }
        }
        if (blockedJid.isFullJid()) {
            return false
        } else if (removeBlockedConversationEntries(service, blockAccount, blockedJid, conversationList)) {
            service.updateConversationUi()
            return true
        } else {
            return false
        }
    }

    @JvmStatic
    fun removeBlockedConversationEntries(
        service: XmppConnectionService,
        accountRef: AccountRef,
        blockedJid: Jid,
        conversationList: MutableList<ConversationRef>,
    ): Boolean {
        var removed = false
        synchronized(conversationList) {
            val domainJid = blockedJid.getLocal() == null
            for (conversation in conversationList) {
                val jidMatches =
                    (domainJid
                        && blockedJid.getDomain().equals((conversation.getJid() ?: throw NullPointerException("conversation has no jid")).getDomain()))
                        || blockedJid.equals((conversation.getJid() ?: throw NullPointerException("conversation has no jid")).asBareJid())
                if (conversation.getAccount() === accountRef
                    && conversation.getMode() == ConversationalRef.MODE_SINGLE
                    && jidMatches
                ) {
                    conversationList.remove(conversation)
                    service.markRead(conversation)
                    conversation.setStatus(ConversationRef.STATUS_ARCHIVED)
                    Log.d(
                        Config.LOGTAG,
                        accountRef.getJid().asBareJid().toString()
                            + ": archiving conversation " + (conversation.getJid() ?: throw NullPointerException("conversation has no jid")).asBareJid()
                            + " because jid was blocked",
                    )
                    service.updateConversation(conversation)
                    removed = true
                }
            }
        }
        return removed
    }

    // Same peel as `sendBlockRequest` above. The Java guarded on `getJid()` and then unblocked
    // `getBlockedJid()`, which is preserved: the guard is `jid`, the value sent is `blockedJid`.
    @JvmStatic
    fun sendUnblockRequest(
        service: XmppConnectionService,
        account: AccountRef?,
        jid: Jid?,
        blockedJid: Jid?,
    ) {
        if (jid == null) {
            return
        }
        val unblockAccount = account ?: throw NullPointerException()
        val blocked = blockedJid ?: throw NullPointerException()
        service.sendIqPacket(
            unblockAccount,
            service.getIqGenerator().generateSetUnblockRequest(blocked),
        ) { response ->
            if (response.getType() == Iq.Type.RESULT) {
                unblockAccount.getBlocklist().remove(blocked)
                service.updateBlocklistUi(OnUpdateBlocklist.Status.UNBLOCKED)
            }
        }
    }
}
