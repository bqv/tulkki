package uk.xa0.tulkki.xmpp.mam

import uk.xa0.tulkki.libs.Jid

/**
 * Tulkki: port-13 - the archive catch-up queue's element type, moved down into the island.
 *
 * This is upstream's own `eu.siacs.conversations.entities.ReceiptRequest`, body unchanged; only the
 * package and the `Jid` import are ours. It sat in `:data` for one reason and no other - the port
 * mapped upstream's `entities` package there - which is why the island could not hold one directly
 * and reached it through `xmpp.refs.ReceiptRequestRef`. The ref's whole surface was this value's two
 * getters, and the island was its only reader, so the value now lives with its queue:
 * `MessageArchiveService.Query`'s two `HashSet`s are the model type again, exactly as upstream
 * declares them, and `MessageParser` constructs it in place.
 *
 * What must not move: the constructor checks in `id`-then-`jid` order, the bare-JID field, and the
 * `(jid, id)` equality - the two `HashSet`s compare these objects, so `hashCode` is load-bearing,
 * not decorative. Nothing in the tree extends the class.
 *
 * Ported from `ReceiptRequest.java`. The island's value
 * types are reproduced faithfully, surface-surviving: the constructor parameters stay nullable so
 * the two `IllegalArgumentException`s and their `id`-first order are the Java's own (a Kotlin
 * non-null parameter would throw `NullPointerException` in declaration order instead), and the two
 * getters stay *functions* because every caller - `MamPostponedReceipts.kt:32-33` and
 * `ReceiptRequestTest` - calls `getJid()`/`getId()` by name. The Kotlin backing properties are
 * therefore named `bareJid`/`receiptId`: a property called `jid` would generate the same
 * `getJid()` JVM signature as the explicit getter.
 */
class ReceiptRequest(jid: Jid?, id: String?) {

    private val bareJid: Jid
    private val receiptId: String

    init {
        if (id == null) {
            throw IllegalArgumentException("id must not be null")
        }
        if (jid == null) {
            throw IllegalArgumentException("jid must not be null")
        }
        this.bareJid = jid.asBareJid()
        this.receiptId = id
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false

        val that = other as ReceiptRequest

        if (bareJid != that.bareJid) return false
        return receiptId == that.receiptId
    }

    override fun hashCode(): Int {
        var result = bareJid.hashCode()
        result = 31 * result + receiptId.hashCode()
        return result
    }

    fun getId(): String = receiptId

    fun getJid(): Jid = bareJid
}
