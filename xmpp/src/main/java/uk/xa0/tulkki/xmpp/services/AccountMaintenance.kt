package uk.xa0.tulkki.xmpp.services

import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.Log
import android.util.LruCache
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.R
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ContactRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.libs.PresenceTemplateRef
import uk.xa0.tulkki.xmpp.utils.XmppUri
import uk.xa0.tulkki.xml.Element

/**
 * Tulkki: presence templates, fingerprints and the account order, lifted out of
 * `XmppConnectionService`.
 *
 * Everything the Java body reached outside the chunk goes in by hand: the drawable cache is private
 * state of chunk `C53`, the shortcut port of chunk `C71` — its `require` guard stays in chunk
 * `C76`'s shared helper and is resolved on the Java side — and the account-update listener set of
 * chunk `C34`, which the Java null-checked even though it is final, so it arrives nullable and the
 * check is kept where it was. The database backend is the service's **public** field, read directly
 * as the Kotlin homes before this one do.
 *
 * `saveConversationAsBookmark` keeps the Java's nick fallback and its comparison against the
 * chunk-`C73` static; `updateAccountOrder` keeps the lock on the shared live accounts list.
 */
object AccountMaintenance {

    /**
     * `signature` is nullable, because the Java it replaces was: `EditAccountActivity` clears an
     * account's PGP signature with a literal `null`, the pre-port `XmppConnectionService` forwarded
     * it to `Account.setPgpSignature`, and the model stores (and answers) a null signature. Typing it
     * non-null here added a `checkNotNullParameter` the null call site never had to satisfy.
     */
    @JvmStatic
    fun changeStatus(
        service: XmppConnectionService,
        account: AccountRef,
        template: PresenceTemplateRef,
        signature: String?,
    ) {
        // `getStatusMessage()` is nullable; the Java dereferenced it here, so the same
        // NPE is spelled out rather than left to a platform type.
        if (!(template.getStatusMessage() ?: throw NullPointerException()).isEmpty()) {
            (service.databaseBackend ?: throw NullPointerException("database backend is not open")).insertPresenceTemplate(template)
        }
        account.setPgpSignature(signature)
        account.setPresenceStatusRef(template.getStatusRef())
        account.setPresenceStatusMessage(template.getStatusMessage())
        (service.databaseBackend ?: throw NullPointerException("database backend is not open")).updateAccount(account)
        service.sendPresence(account)
    }

    /**
     * Tulkki: 3.7 C5-E2 - the return is a **concrete** `List<PresenceTemplateRef>`, not a wildcard,
     * and the local is the ref list the adapter needs. The one model list the database answers is
     * copied into a ref list by the `ArrayList` constructor, and the self-contact's own templates are
     * already model objects that satisfy the ref.
     */
    @JvmStatic
    fun presenceTemplates(
        service: XmppConnectionService,
        account: AccountRef,
    ): List<PresenceTemplateRef> {
        val templates = ArrayList<PresenceTemplateRef>((service.databaseBackend ?: throw NullPointerException("database backend is not open")).getPresenceTemplates())
        for (template in account.getSelfContact().getPresences().asTemplates()) {
            if (!templates.contains(template)) {
                templates.add(0, template)
            }
        }
        return templates
    }

    /**
     * `name` is nullable, because the Java it replaces was: the hub itself calls
     * `saveConversationAsBookmark(conversation, null)` when a followed invite has no bookmark yet,
     * and the pre-port body guarded it with `TextUtils.isEmpty(name)` — which null satisfies.
     */
    @JvmStatic
    fun saveConversationAsBookmark(
        service: XmppConnectionService,
        conversation: ConversationRef,
        name: String?,
    ) {
        val account = conversation.getAccount() ?: throw NullPointerException("conversation has no account")
        val bookmark =
            XmppConnectionService.dataStatics().newBookmark(account, (conversation.getJid() ?: throw NullPointerException("conversation has no jid")).asBareJid())
        var nick = conversation.getMucOptions().getActualNick()
        if (nick == null) nick = (conversation.getJid() ?: throw NullPointerException("conversation has no jid")).getResource()
        if (nick != null
            && !nick.isEmpty()
            && nick != XmppConnectionService.dataStatics().defaultNick(account)
        ) {
            bookmark.setNick(nick)
        }
        if (!TextUtils.isEmpty(name)) {
            bookmark.setBookmarkName(name)
        }
        bookmark.setAutojoin(true)
        service.createBookmark(account, bookmark)
        bookmark.setConversation(conversation)
    }

    @JvmStatic
    fun verifyContactFingerprints(
        service: XmppConnectionService,
        contact: ContactRef,
        fingerprints: List<XmppUri.Fingerprint>,
    ): Boolean {
        var needsRosterWrite = false
        var performedVerification = false
        val axolotlService = contact.getAccount().getOmemoSession() ?: throw NullPointerException("no omemo session")
        for (fp in fingerprints) {
            if (fp.type == XmppUri.FingerprintType.OTR) {
                performedVerification = performedVerification or contact.addOtrFingerprint(fp.fingerprint)
                needsRosterWrite = needsRosterWrite or performedVerification
            } else if (fp.type == XmppUri.FingerprintType.OMEMO) {
                val fingerprint = "05" + fp.fingerprint.replace("\\s".toRegex(), "")
                if (axolotlService.hasFingerprintTrust(fingerprint)) {
                    if (!axolotlService.isFingerprintVerified(fingerprint)) {
                        performedVerification = true
                        axolotlService.markFingerprintVerified(fingerprint)
                    }
                } else {
                    axolotlService.preVerifyContactFingerprint(contact, fingerprint)
                }
            }
        }

        if (needsRosterWrite) {
            service.syncRosterToDisk(contact.getAccount())
        }

        return performedVerification
    }

    @JvmStatic
    fun verifyAccountFingerprints(
        account: AccountRef,
        fingerprints: List<XmppUri.Fingerprint>,
    ): Boolean {
        val axolotlService = account.getOmemoSession() ?: throw NullPointerException("no omemo session")
        var verifiedSomething = false
        for (fp in fingerprints) {
            if (fp.type == XmppUri.FingerprintType.OMEMO) {
                val fingerprint = "05" + fp.fingerprint.replace("\\s".toRegex(), "")
                Log.d(Config.LOGTAG, "trying to verify own fp=" + fingerprint)
                if (axolotlService.hasFingerprintTrust(fingerprint)) {
                    if (!axolotlService.isFingerprintVerified(fingerprint)) {
                        axolotlService.markFingerprintVerified(fingerprint)
                        verifiedSomething = true
                    }
                } else {
                    axolotlService.preVerifyAccountFingerprint(account, fingerprint)
                    verifiedSomething = true
                }
            }
        }
        return verifiedSomething
    }

    @JvmStatic
    fun blindTrustBeforeVerification(service: XmppConnectionService): Boolean =
        service.getBooleanPreference(
            XmppConnectionService.DataStatics.BLIND_TRUST_BEFORE_VERIFICATION,
            R.bool.btbv,
        )

    @JvmStatic
    fun shortcutService(
        port: ShortcutPort,
    ): ShortcutPort = port

    @JvmStatic
    fun pushMamPreferences(service: XmppConnectionService, account: AccountRef, prefs: Element) {
        val set = Iq(Iq.Type.SET)
        set.addChild(prefs)
        account.setMamPrefs(prefs)
        service.sendIqPacket(account, set, null)
    }

    @JvmStatic
    fun evictPreview(cache: LruCache<String, Drawable>, f: java.io.File?) {
        if (f == null) return

        if (cache.remove(f.getAbsolutePath()) != null) {
            Log.d(Config.LOGTAG, "deleted cached preview")
        }
    }

    @JvmStatic
    fun evictPreview(cache: LruCache<String, Drawable>, uuid: String) {
        if (cache.remove(uuid) != null) {
            Log.d(Config.LOGTAG, "deleted cached preview")
        }
    }

    @JvmStatic
    fun updateAccountOrder(
        service: XmppConnectionService,
        listeners: Set<OnAccountUpdate>?,
    ) {
        synchronized(XmppConnectionService.dataStatics().accounts().getAccounts()) {
            for (i in 0 until XmppConnectionService.dataStatics().accounts().getAccounts().size) {
                val account = XmppConnectionService.dataStatics().accounts().getAccounts()[i]
                // Update the order field on the object
                account.setOrdering(i)
                // Persist to database
                (service.databaseBackend ?: throw NullPointerException("database backend is not open")).updateAccount(account)
            }
        }
        // Notify UI
        if (listeners != null) {
            for (listener in listeners) {
                listener.onAccountUpdate()
            }
        }
    }
}
