package uk.xa0.tulkki.xmpp.mam

/**
 * Tulkki: a MAM region's `(time, reference)` pair, the archive's own ordering key.
 *
 * Upstream's own `eu.siacs.conversations.xmpp.mam.MamReference`, body unchanged; only the package
 * and the two null-answer decisions below are ours. It is the island's value: the anchor a query is
 * paged from, the anchor a cursor is seeded with and the value `Schema76` reads back out of a
 * conversation's `last_clear_history`.
 *
 * Ported from `MamReference.java`. Decisions taken
 * rather than inherited:
 *
 * 1. **The two fields stay private and the two getters stay *functions*** (`getTimestamp()`,
 *    `getReference()`), not Kotlin properties. Kotlin hides a property's accessor from Kotlin
 *    source, so properties named `timestamp`/`reference` would have forced edits at the *majority*
 *    of call sites - every island caller (`MessageArchiveService.kt:206-209`, `MamPaging.kt:39-71`,
 *    `MamCatchup.kt:71-86`, `ConversationPaging.kt:94`), `Conversation.kt:424,1240,1340-1341`,
 *    `SyncEngine.kt:253-428` and the one Java caller (`XmppConnectionService.java:3807`) all name
 *    the getters. Only `XmppTulkkiHost.kt:254,264` and `Schema76.kt:588-656` read them as
 *    properties, and those are the sites normalised in this commit. The backing properties are
 *    renamed `timestampValue`/`referenceValue` so no synthetic `getTimestamp()` collides with the
 *    explicit one. This is the same answer `ReceiptRequest` and `BackupFileHeader` record.
 * 2. **`max(MamReference, MamReference)` answers `MamReference?`**, as the Java did: it answers null
 *    only when *both* sides are null. `XmppTulkkiHost.kt:260` already declared its result nullable
 *    and `Schema76.kt:634` already guards with `?:`; the three sites that leaned on the Java
 *    *platform* type now say what the Java did (`Conversation.kt:564`, `Schema76.kt:587`, `:689`).
 * 3. **`max(MamReference, long)` answers a non-null `MamReference`**, which the Java's own body
 *    proves: it forwards a freshly built, non-null second argument, so its two-argument null
 *    branches cannot be reached. `MamPaging.kt:66-71` reads it as non-null.
 * 4. **The `time:reference` parse keeps Java's `split` semantics**, trailing empty tokens dropped:
 *    `"123:"` answers the reference-less `MamReference(123)`, not `MamReference(123, "")`, exactly
 *    as `String.split` did. Every malformed input still lands on `MamReference(0)` via the same
 *    catch-all.
 */
class MamReference(private val timestampValue: Long, private val referenceValue: String?) {

    constructor(timestamp: Long) : this(timestamp, null)

    fun getTimestamp(): Long = timestampValue

    fun getReference(): String? = referenceValue

    fun greaterThan(b: MamReference): Boolean = timestampValue > b.timestampValue

    fun greaterThan(b: Long): Boolean = timestampValue > b

    fun timeOnly(): MamReference =
        if (referenceValue == null) this else MamReference(timestampValue)

    companion object {

        @JvmStatic
        fun max(a: MamReference?, b: MamReference?): MamReference? =
            when {
                a != null && b != null -> if (a.timestampValue > b.timestampValue) a else b
                a != null -> a
                else -> b
            }

        @JvmStatic
        fun max(a: MamReference?, b: Long): MamReference =
            if (a != null && a.timestampValue > b) a else MamReference(b)

        @JvmStatic
        fun fromAttribute(attr: String?): MamReference {
            if (attr == null) {
                return MamReference(0)
            }
            val attrs = attr.split(":").dropLastWhile { it.isEmpty() }
            return try {
                val timestamp = attrs[0].toLong()
                if (attrs.size >= 2) MamReference(timestamp, attrs[1]) else MamReference(timestamp)
            } catch (e: Exception) {
                MamReference(0)
            }
        }
    }
}
