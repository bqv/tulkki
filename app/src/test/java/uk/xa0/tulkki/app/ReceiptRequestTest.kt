package uk.xa0.tulkki.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.mam.ReceiptRequest

/**
 * Tulkki: port-13's feature move of the receipt request, pinned as it moves.
 *
 * The archive catch-up queue keys two `HashSet`s on this value, so its validation and its equality
 * are the behaviour, not decoration: `MessageArchiveService.processPostponed` compares the pending
 * set against the arrived set, and `MessageParser` builds each value from the stanza's `to`/`from`
 * and its message id. The value lived in `:data` behind `ReceiptRequestRef` until port-13 moved it
 * into the island (`uk.xa0.tulkki.xmpp.mam.ReceiptRequest`), so this cell pins the three facts the
 * move must not lose - the constructor's checks and their order, the bare-JID field, and the
 * `(jid, id)` equality the sets run - against the class in its new home.
 */
class ReceiptRequestTest {

    private val sender = Jid.of("tulkki@example.org")

    /** `id` is checked before `jid`, so a request with neither names `id` first. */
    @Test
    fun aMissingIdIsReportedBeforeAMissingJid() {
        val missingId =
            assertThrows(IllegalArgumentException::class.java) { ReceiptRequest(sender, null) }
        assertEquals("id must not be null", missingId.message)

        val missingJid =
            assertThrows(IllegalArgumentException::class.java) {
                ReceiptRequest(null, "stanza-1")
            }
        assertEquals("jid must not be null", missingJid.message)
    }

    /** Whatever resource the sender carried, the value holds the bare address. */
    @Test
    fun theAddressIsStoredBare() {
        val request = ReceiptRequest(Jid.of("tulkki@example.org/phone"), "stanza-1")
        assertEquals(sender, request.getJid())
        assertEquals(sender, request.getJid().asBareJid())
        assertEquals("stanza-1", request.getId())
    }

    /** The two sets collapse equal requests, so equality and hashCode must agree on the pair. */
    @Test
    fun equalPairsCollapseInASet() {
        val first = ReceiptRequest(sender, "stanza-1")
        val same = ReceiptRequest(sender, "stanza-1")
        val otherId = ReceiptRequest(sender, "stanza-2")
        val otherJid = ReceiptRequest(Jid.of("someone@example.org"), "stanza-1")

        assertEquals(first, same)
        assertEquals(first.hashCode(), same.hashCode())
        assertNotEquals(first, otherId)
        assertNotEquals(first, otherJid)

        val queue = HashSet<ReceiptRequest>()
        queue.add(first)
        queue.add(same)
        assertEquals(1, queue.size)
        queue.add(otherId)
        assertTrue(queue.contains(first))
        assertEquals(2, queue.size)
    }
}
