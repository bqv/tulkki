package uk.xa0.tulkki.xmpp.services

import com.google.common.collect.HashMultimap
import com.google.common.collect.Multimap
import uk.xa0.tulkki.xmpp.refs.MucOptionsRef

/**
 * Tulkki: the per-account muc mute cache and the roster-form predicates, lifted out of
 * `XmppConnectionService`.
 *
 * The Java kept the cache in a private `HashMultimap` and wrote it through to the database; the
 * cache lives on this object now and the service is passed in for its public `databaseBackend`
 * slot. The one write the Java made from outside the chunk — `restoreFromDatabase`'s reload loop —
 * calls [reloadMutedMucUsers] rather than touching the map, so the move does not need a visibility
 * widened. Nothing here takes a lock, exactly as before, and the two nullable cases the row names
 * are kept: [mucAccount] catches the `RuntimeException` a JID-only participant throws and answers
 * null, and the account-less [isMucUserMuted] answers false for a null account uuid.
 */
object MucMuting {

    private val mutedMucUsers: Multimap<String, String> = HashMultimap.create()

    @JvmStatic
    fun muteMucUser(service: XmppConnectionService, user: MucOptionsRef.UserRef): Boolean {
        val accountUuid = mucAccount(service, user) ?: return false
        val muted = (service.databaseBackend ?: throw NullPointerException("database backend is not open")).muteMucUser(user, accountUuid)
        if (!muted) return false
        mutedMucUsers.put(
            muteKey(accountUuid, user.getMuc().toString()),
            user.getOccupantId() ?: throw NullPointerException("user has no occupant id"),
        )
        return true
    }

    @JvmStatic
    fun unmuteMucUser(service: XmppConnectionService, user: MucOptionsRef.UserRef): Boolean {
        val accountUuid = mucAccount(service, user) ?: return false
        val unmuted = (service.databaseBackend ?: throw NullPointerException("database backend is not open")).unmuteMucUser(user, accountUuid)
        if (!unmuted) return false
        mutedMucUsers.remove(muteKey(accountUuid, user.getMuc().toString()), user.getOccupantId())
        return true
    }

    /** The roster form: the participant carries the conversation it was read from. */
    @JvmStatic
    fun isMucUserMuted(user: MucOptionsRef.UserRef): Boolean =
        isMucUserMutedForAccount(
            if (user.getConversation() == null) null else user.getConversation().getAccountUuid(),
            "" + user.getMuc(),
            user.getOccupantId(),
        )

    /**
     * The form a caller with only a message in hand uses, and the one the mute cache is keyed by.
     * [accountUuid] stays nullable because the roster form above hands this a null when the
     * participant has no conversation, and the Java answered false rather than throwing.
     */
    @JvmStatic
    fun isMucUserMutedForAccount(
        accountUuid: String?,
        mucJid: String,
        occupantId: String?,
    ): Boolean {
        if (accountUuid == null) return false
        return mutedMucUsers.containsEntry(muteKey(accountUuid, mucJid), occupantId)
    }

    /**
     * The cache's one reload, moved out of `restoreFromDatabase` so the map itself stays private
     * here. The account list is read through the service's own public static, and the database
     * through its public slot, so nothing outside this object touches the map.
     */
    @JvmStatic
    fun reloadMutedMucUsers(service: XmppConnectionService) {
        mutedMucUsers.clear()
        for (account in XmppConnectionService.dataStatics().accounts().getAccounts()) {
            val accountUuid = account.getUuid()
            for (entry in (service.databaseBackend ?: throw NullPointerException("database backend is not open")).loadMutedMucUsers(accountUuid).entries()) {
                mutedMucUsers.put(muteKey(accountUuid, entry.key), entry.value)
            }
        }
    }

    /**
     * The account a mute belongs to. The roster's participants carry the conversation they were
     * read from, so it is read off them; `:ui`'s participants are built from a JID and an occupant
     * alone and cannot, so the room's own conversation is looked up in the file for those.
     */
    private fun mucAccount(
        service: XmppConnectionService,
        user: MucOptionsRef.UserRef,
    ): String? {
        try {
            val conversation = user.getConversation()
            if (conversation != null) {
                return conversation.getAccountUuid()
            }
        } catch (ignored: RuntimeException) {
            // A JID-only `MucOptions.User`, whose conversation field is null. Looked up below.
        }
        return if (user.getMuc() == null) {
            null
        } else {
            (service.databaseBackend ?: throw NullPointerException("database backend is not open")).accountOfMuc(user.getMuc().toString())
        }
    }

    /**
     * The mute cache's key is the account and the room, because the pair is what a mute is. The NUL
     * separator cannot occur in either a JID or an account uuid, so no pair can collide with another.
     */
    private fun muteKey(accountUuid: String, mucJid: String): String =
        accountUuid + "\u0000" + mucJid
}
