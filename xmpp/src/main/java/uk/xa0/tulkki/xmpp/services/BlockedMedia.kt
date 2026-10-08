package uk.xa0.tulkki.xmpp.services

import io.ipfs.cid.Cid
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.MessageRef
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Tulkki: the cid and blocked-media seam, lifted out of `XmppConnectionService`
 *.
 *
 * Five thin refusals onto the file and database backends, plus the avatar getter the same region
 * carried. Every one takes the service, because both backends are the service's own slots, which is
 * how the whole partition passes a context in. The avatar slot is `C70`'s private field and its
 * guard is `C76`'s private `require`, so the service resolves both on its side of the seam and hands
 * the non-null port in; neither visibility is widened.
 */
object BlockedMedia {

    /** Hashes [f] into cids and blocks them; a broken file is swallowed, as the Java body did. */
    @JvmStatic
    fun blockMedia(service: XmppConnectionService, f: File) {
        try {
            val cids = service.getFileBackend().calculateCids(FileInputStream(f))
            for (cid in cids) {
                blockMedia(service, cid)
            }
        } catch (e: IOException) {
            // as in the Java: a file that cannot be read blocks nothing
        }
    }

    @JvmStatic
    fun blockMedia(service: XmppConnectionService, cid: Cid) {
        (service.databaseBackend ?: throw NullPointerException("database backend is not open")).blockMedia(cid)
    }

    @JvmStatic
    fun clearBlockedMedia(service: XmppConnectionService) {
        (service.databaseBackend ?: throw NullPointerException("database backend is not open")).clearBlockedMedia()
    }

    /**
     * `null` for an unknown uuid, which is what the Java answered and what every caller must
     * expect. [uuid] stays nullable because the Java body never evaluated it, only handed it to the
     * backend; a Kotlin non-null parameter would add a throw the Java did not have.
     */
    @JvmStatic
    fun getMessage(
        service: XmppConnectionService,
        conversation: ConversationRef,
        uuid: String?,
    ): MessageRef? = (service.databaseBackend ?: throw NullPointerException("database backend is not open")).getMessage(conversation, uuid)

    /**
     * The backend's map in a **fresh mutable** `HashMap`, never an unmodifiable view: the Java
     * returned a copy the caller may hold, and `DataStatics`/`MessageParser` rely on the mutability.
     */
    @JvmStatic
    @JvmSuppressWildcards
    fun getMessageFuzzyIds(
        service: XmppConnectionService,
        conversation: ConversationRef,
        ids: Collection<String>,
    ): Map<String, MessageRef> = HashMap((service.databaseBackend ?: throw NullPointerException("database backend is not open")).getMessageFuzzyIds(conversation, ids))

    /**
     * The avatar port, already resolved by the caller. `XmppConnectionService.getAvatarService()`
     * evaluates its private `require` on its private `mAvatarService` and passes the non-null result,
     * so this home names neither.
     */
    @JvmStatic
    fun avatarService(port: AvatarPort): AvatarPort = port
}
