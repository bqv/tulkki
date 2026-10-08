package uk.xa0.tulkki.app.services

import uk.xa0.tulkki.data.model.Room
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.forms.Data
import uk.xa0.tulkki.xmpp.models.stanza.Iq

/**
 * Tulkki: the room a `DISCO#info` response describes, parsed where the model is reachable.
 *
 * <p>This is `IqParser.parseRoom` moved, in two commits on purpose. The island's copy could not be
 * tested at all: it named `RoomRef` and it called `android.text.TextUtils.isEmpty`, an Android stub
 * off-device, so no JVM cell could drive it - and it was 32 island lines no test had ever run. So the
 * port came first, with its cell, and port-13's `RoomRef` deletion then deleted the island's copy and
 * pointed `ChannelDiscoveryService` here: the code that runs is the code the cell drives.
 *
 * <p>The behaviour is the island's, byte for byte, with the two Android couplings replaced:
 * `TextUtils.isEmpty(roomName)` becomes `roomName.isNullOrEmpty()` (the same answer for null and for
 * empty), and `Integer.parseInt` in a try/catch becomes `toIntOrNull()`, which answers zero for the
 * same strings - including the whitespace and overflow cases Java's catch folded to zero.
 *
 * <p>A response with no `query` or no `x` is not a room and answers null; the address is the
 * sender's own JID, which is what the caller keys the result by.
 */
object RoomParser {

    /**
     * @param packet the `DISCO#info` response
     * @return the room it describes, or `null` when the response carries no room form
     */
    @JvmStatic
    fun parse(packet: Iq): Room? {
        val query = packet.findChild("query", Namespace.DISCO_INFO) ?: return null
        val x = query.findChild("x") ?: return null
        val identity = query.findChild("identity")
        val data = Data.parse(x) ?: throw NullPointerException()
        val address = (packet.getFrom() ?: throw NullPointerException()).toString()
        val name = identity?.getAttribute("name")
        val roomName = data.getValue("muc#roomconfig_roomname")
        val description = data.getValue("muc#roominfo_description")
        val language = data.getValue("muc#roominfo_lang")
        val occupants = data.getValue("muc#roominfo_occupants")
        val nusers = occupants?.toIntOrNull() ?: 0
        return Room(
                address,
                if (roomName.isNullOrEmpty()) name else roomName,
                description,
                language,
                nusers)
    }
}
