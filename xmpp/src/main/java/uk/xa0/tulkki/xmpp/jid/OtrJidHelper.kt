package uk.xa0.tulkki.xmpp.jid

import net.java.otr4j.session.SessionID
import uk.xa0.tulkki.libs.Jid

object OtrJidHelper {

    @JvmStatic
    @Throws(IllegalArgumentException::class)
    fun fromSessionID(id: SessionID): Jid =
        if (id.userID.isEmpty()) {
            Jid.of(id.accountID)
        } else {
            Jid.of(id.accountID + "/" + id.userID)
        }
}
