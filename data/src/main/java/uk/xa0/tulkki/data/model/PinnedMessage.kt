package uk.xa0.tulkki.data.model

import io.ipfs.cid.Cid

/**
 * One pinned message's stored row: the message it pins, the conversation and account it belongs to,
 * the locally encrypted body with its IV, when it was pinned, and the optional attachment CID.
 *
 * <p>Ported from Java by the `port` stage (port-2). The decisions, recorded rather than inherited:
 *
 * 1. **The seven `public static final String`s become a companion of `const val`s.** Their readers are
 *    both languages - Kotlin (`schema/RawTables.kt`, `backup/BackupExport.kt`, `backup/BackupQueries
 *    .kt`) and Java (`DatabaseBackend`, and the three backup tests) - and every one of them writes
 *    `PinnedMessage.TABLENAME` and its siblings. A `const val` in a companion compiles to a static
 *    field **on `PinnedMessage` itself**, so all of those reads are unchanged and need no
 *    `@JvmField`; `const val` is also the closer translation of Java's `final static String`.
 * 2. **A plain `class`, not a `data class`.** Java's `equals` compares [messageUuid] alone, so two
 *    rows for the same message with different timestamps and CIDs are equal today; a `data class`
 *    would compare all six components and silently change that. Measured: nothing in the tree
 *    constructs one (`grep` finds no `new PinnedMessage(`), so nothing depends on either answer yet -
 *    which is exactly why the translation preserves the written one instead of choosing an
 *    improvement with no test behind it.
 * 3. **`equals` keeps Java's `getClass() != o.getClass()`** as `javaClass != other.javaClass` rather
 *    than Kotlin's `other !is PinnedMessage`, which would accept a subclass instance Java rejects.
 * 4. **[cid] is the one nullable property.** Java's own comments mark it as the per-file field ("New
 *    field for Content Identifier", "For files") and its `cid` column is declared without
 *    `NOT NULL`; [messageUuid] is dereferenced by `equals`/`hashCode`, and the other four are the
 *    row's required values, so they stay non-null.
 * 5. **The unused `java.util.Arrays` import is dropped.** Java imported it under a `// if needed`
 *    comment and never referred to it; Kotlin would warn, and there is no reference to carry.
 *
 * Nothing here is a field a Java caller reads as a field, and the statics are Java-visible by
 * construction, so this file adds **zero** interop debt.
 */
class PinnedMessage(
    val messageUuid: String,
    val conversationUuid: String,
    val encryptedContent: ByteArray,
    val iv: ByteArray,
    val timestamp: Long,
    val cid: Cid?,
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false

        val that = other as PinnedMessage

        return messageUuid == that.messageUuid
    }

    override fun hashCode(): Int = messageUuid.hashCode()

    companion object {

        const val TABLENAME = "pinned_messages"
        const val MESSAGE_UUID = "message_uuid"
        const val CONVERSATION_UUID = "conversation_uuid"
        const val ACCOUNT_UUID = "account_uuid"
        const val BODY = "body"
        const val TIMESTAMP = "timestamp"
        const val CID = "cid"
    }
}
