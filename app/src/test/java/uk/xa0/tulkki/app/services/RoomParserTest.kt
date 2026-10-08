package uk.xa0.tulkki.app.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uk.xa0.tulkki.data.model.Room
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.forms.Field
import uk.xa0.tulkki.xmpp.models.stanza.Iq

/**
 * The room parser port-13's `RoomRef` deletion moved out of the island, pinned before it moved.
 *
 * <p>The island's `IqParser.parseRoom` had no cell of any kind - it named a ref and called
 * `android.text.TextUtils.isEmpty`, so a JVM test could not even call it - and the move retired both
 * the ref and `ChannelDiscoveryPort`'s `discover` with it. The cell came first: these cases are the
 * behaviour the discovery service relies on, taken from the island's own code, and the service calls
 * this parser now with them green.
 *
 * <p>What each case pins: the name comes from the room form and falls back to the identity's own
 * name; the address is the sender's JID (the caller keys the room by it); a description, a language
 * and an occupant count ride along; a malformed or absent occupant count is zero; and a response with
 * no query or no form is not a room.
 */
class RoomParserTest {

    /** The `from` the response carries: the room's own JID, which becomes the room's address. */
    private companion object {

        const val ADDRESS = "tulkki@conference.example.org"

        /** One form field: `Field.setValue` is void, so it cannot be chained into `addChild`. */
        fun field(name: String, value: String): Element {
            val field = Field(name)
            field.setValue(value)
            return field
        }

        /**
         * An `x` form with only the fields asked for: a null room name or occupant count omits the
         * field, which is how the absence cases are built (removing a child after the fact does not
         * reach the bound form).
         */
        fun form(roomName: String?, occupants: String?): Element {
            val x = Element("x", "jabber:x:data")
            if (roomName != null) {
                x.addChild(field("muc#roomconfig_roomname", roomName))
            }
            x.addChild(field("muc#roominfo_description", "A room about migrating"))
            x.addChild(field("muc#roominfo_lang", "fi"))
            if (occupants != null) {
                x.addChild(field("muc#roominfo_occupants", occupants))
            }
            return x
        }

        fun response(identityName: String?, roomName: String?, occupants: String?): Iq {
            val packet = Iq(Iq.Type.RESULT)
            packet.setFrom(Jid.of(ADDRESS))
            val query = Element("query", Namespace.DISCO_INFO)
            if (identityName != null) {
                query.addChild(Element("identity").setAttribute("name", identityName))
            }
            query.addChild(form(roomName, occupants))
            packet.addChild(query)
            return packet
        }
    }

    @Test
    fun aNamedRoomIsBuiltFromTheFormAndTheSendersJid() {
        val room: Room = RoomParser.parse(response("identity name", "Tulkki", "42"))!!

        assertEquals("the address is the sender's own JID", ADDRESS, room.address)
        assertEquals("the form's room name wins over the identity's", "Tulkki", room.name)
        assertEquals("A room about migrating", room.description)
        assertEquals("fi", room.language)
        assertEquals(42, room.nusers)
    }

    @Test
    fun aRoomWithNoNameInTheFormFallsBackToTheIdentityName() {
        val room: Room = RoomParser.parse(response("The identity name", null, "7"))!!

        assertEquals("The identity name", room.name)
        assertEquals(7, room.nusers)
    }

    @Test
    fun aRoomWithNoNameAnywhereHasANullNameAndNoOccupants() {
        val room: Room = RoomParser.parse(response(null, null, null))!!

        assertNull("nothing named it", room.name)
        assertEquals("and an absent occupant count is zero, not a parse failure", 0, room.nusers)
        assertEquals(ADDRESS, room.address)
    }

    @Test
    fun aMalformedOccupantCountIsZero() {
        val room: Room = RoomParser.parse(response("identity name", "Tulkki", "not a number"))!!

        assertEquals("the Java's catch folded this to zero too", 0, room.nusers)
    }

    @Test
    fun aResponseWithNoQueryOrNoFormIsNotARoom() {
        val noQuery = Iq(Iq.Type.RESULT)
        noQuery.setFrom(Jid.of(ADDRESS))
        assertNull("no query child", RoomParser.parse(noQuery))

        val noForm = Iq(Iq.Type.RESULT)
        noForm.setFrom(Jid.of(ADDRESS))
        noForm.addChild(Element("query", Namespace.DISCO_INFO))
        assertNull("no x form", RoomParser.parse(noForm))
    }
}
