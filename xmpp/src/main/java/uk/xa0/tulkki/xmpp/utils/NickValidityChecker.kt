package uk.xa0.tulkki.xmpp.utils

import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.refs.ConversationRef

/**
 * Is a nick still valid in a room: has anyone written under that full JID, or is the room currently
 * showing a user with it?
 *
 * Ported from Java by the `px` lane. Decisions taken rather than inherited:
 *
 * 1. **The two-argument `check` is `@JvmStatic`**: its one caller is `:ui`'s
 *    `ConversationFragment.java:5450`, and it is the overload the list-of-nicks path uses.
 * 2. **The single-nick helper stays `private`** and the list overload calls it unqualified, exactly
 *    as the Java private static did.
 * 3. **`nick` and the list are non-null** (both are dereferenced immediately), and the
 *    `IllegalArgumentException` catch is unchanged - it is what turns an unparsable full JID into
 *    "not valid" rather than a crash.
 * 4. **The room lookup is a direct `Jid.of(room.getLocal(), room.getDomain(), nick)`**: the local
 *    part travels as the platform value it already was (`ConversationRef.getJid()`'s own type), so
 *    the null-local case throws where Java threw.
 */
class NickValidityChecker {

    companion object {

        private fun check(conversation: ConversationRef, nick: String): Boolean {
            val room =
                conversation.getJid() ?: throw NullPointerException("conversation has no jid")
            return try {
                val full = Jid.of(room.getLocal(), room.getDomain(), nick)
                conversation.hasMessageWithCounterpart(full) ||
                    conversation.getMucOptions().findUserByFullJid(full) != null
            } catch (e: IllegalArgumentException) {
                false
            }
        }

        @JvmStatic
        fun check(conversation: ConversationRef, nicks: List<String>): Boolean {
            val previousNicks = HashSet(nicks)
            for (previousNick in previousNicks) {
                if (!check(conversation, previousNick)) {
                    return false
                }
            }
            return true
        }
    }
}
