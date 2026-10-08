package uk.xa0.tulkki.xmpp.services

import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.libs.AttachmentRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import java.io.File

/**
 * Tulkki: the attachment queries and the media deletion behind them, lifted out of
 * `XmppConnectionService`.
 *
 * The seven `getAttachments` overloads nest exactly as the Java nested them, each narrow one a call
 * to the next; the widest hands `DataStaticsHost.loadAttachments` the service's **public**
 * `databaseBackend` slot, so nothing here needed a visibility widened. The Kotlin statics carry
 * distinct simple names; the service keeps every Java name, visibility and signature as a one-line
 * delegation, so no call site moves.
 *
 * The nulls the row names are kept: [hasEnabledAccounts] answers false for a null account list, and
 * the query-only overload still passes `null` for both the account uuid and the jid — the Java
 * forwarded both to the port without evaluating either, so they stay nullable rather than gaining a
 * check the Java did not have. `deleteMedia`'s parameter keeps the Java's wildcard spelling:
 * `kotlin.collections.List` is covariant, so the emitted signature is
 * `java.util.List<? extends AttachmentRef>`, the Java's own.
 */
object Attachments {

    @JvmStatic
    fun hasEnabledAccounts(): Boolean {
        val accounts = XmppConnectionService.dataStatics().accounts().getAccounts()
        if (accounts == null) {
            return false
        }
        for (account in accounts) {
            if (account.isConnectionEnabled()) {
                return true
            }
        }
        return false
    }

    @JvmStatic
    fun fromConversation(
        service: XmppConnectionService,
        conversation: ConversationRef,
        limit: Int,
        onMediaLoaded: MediaLoadedHook,
    ) {
        fromConversationWithQuery(service, conversation, null, limit, onMediaLoaded)
    }

    @JvmStatic
    fun fromConversationWithQuery(
        service: XmppConnectionService,
        conversation: ConversationRef,
        query: String?,
        limit: Int,
        onMediaLoaded: MediaLoadedHook,
    ) {
        fromAccountWithQuery(
            service,
            conversation.getAccount() ?: throw NullPointerException("conversation has no account"),
            (conversation.getJid() ?: throw NullPointerException("conversation has no jid"))
                .asBareJid(),
            query,
            limit,
            onMediaLoaded,
        )
    }

    /** The account and jid are dereferenced below, exactly as the Java dereferenced them. */
    @JvmStatic
    fun fromAccount(
        service: XmppConnectionService,
        account: AccountRef,
        jid: Jid,
        limit: Int,
        onMediaLoaded: MediaLoadedHook,
    ) {
        fromAccountWithQuery(service, account, jid, null, limit, onMediaLoaded)
    }

    @JvmStatic
    fun fromAccountWithQuery(
        service: XmppConnectionService,
        account: AccountRef,
        jid: Jid,
        query: String?,
        limit: Int,
        onMediaLoaded: MediaLoadedHook,
    ) {
        load(service, account.getUuid(), jid.asBareJid(), query, limit, onMediaLoaded)
    }

    /** The keyed form: the caller holds a uuid, and the jid is forwarded unevaluated. */
    @JvmStatic
    fun fromAccountUuid(
        service: XmppConnectionService,
        accountUuid: String?,
        jid: Jid?,
        limit: Int,
        onMediaLoaded: MediaLoadedHook,
    ) {
        load(service, accountUuid, jid, null, limit, onMediaLoaded)
    }

    /**
     * The one overload that does the work: the island's entry point, the port's call. The
     * `(String) null, (Jid) null` the query-only overload passes is deliberate — the port resolves
     * the account itself — so neither parameter is checked here.
     */
    @JvmStatic
    fun load(
        service: XmppConnectionService,
        accountUuid: String?,
        jid: Jid?,
        query: String?,
        limit: Int,
        onMediaLoaded: MediaLoadedHook,
    ) {
        XmppConnectionService.dataStatics()
            .loadAttachments(
                service.databaseBackend ?: throw NullPointerException("database backend is not open"),
                accountUuid,
                jid,
                query,
                limit,
                onMediaLoaded,
            )
    }

    @JvmStatic
    fun fromQueryOnly(
        service: XmppConnectionService,
        query: String?,
        limit: Int,
        onMediaLoaded: MediaLoadedHook,
    ) {
        load(service, null, null, query, limit, onMediaLoaded)
    }

    @JvmStatic
    fun deleteMedia(service: XmppConnectionService, attachments: List<AttachmentRef>) {
        val uuids = ArrayList<String>()
        for (attachment in attachments) {
            val path = service.getFileBackend().getOriginalPath(attachment.getUri())
            if (path != null) {
                val file = File(path)
                if (file.delete()) {
                    service.evictPreview(file)
                }
            }
            uuids.add(attachment.getUuid().toString())
        }
        (service.databaseBackend ?: throw NullPointerException("database backend is not open")).markFileAsDeleted(uuids)
    }
}
