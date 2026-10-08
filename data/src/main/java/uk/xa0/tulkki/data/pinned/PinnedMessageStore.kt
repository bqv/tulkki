package uk.xa0.tulkki.data.pinned

import android.content.ContentValues
import android.database.Cursor
import net.zetetic.database.sqlcipher.SQLiteDatabase
import uk.xa0.tulkki.data.model.PinnedMessage

/**
 * `pinned/`'s capability: the owner's pinned messages, one row per pin, in their own table.
 *
 * <p>**The first capability out of `DatabaseBackend`'s grab-bag, and the smallest one that is
 * genuinely coherent.** Four statements and nothing else: the pin's upsert, its removal by message,
 * its removal by conversation and message, and the conversation's ordered read. One table
 * (`pinned_messages`), one caller class (`:ui`'s `PinnedMessageRepository`), and no other group in
 * the 3,371-line class needs any of it. `docs/MIGRATION.md`'s `port-32` row is the inventory this
 * comes out of.
 *
 * <p>**The home is an `object` that takes the `SQLiteDatabase` rather than a store that owns it.**
 * `DatabaseBackend` is the one owner of the file (`HistoryDatabase`'s Room connection, handed in by
 * `getInstance`), so a package that opened its own would be the second owner the design forbids; the
 * seam is the same one `schema/RawTables.applySchema(exec)` uses. There is no `Context` here and no
 * `get()` to add.
 *
 * <p>**The column and table names are the entity's**, not a second spelling: `PinnedMessage`'s
 * companion already carries them as `const val`s on the model itself, which the entity's own doc
 * records as its reason for being a companion. This file adds none.
 *
 * <p>**The Java-visible surface is `PinnedMessage`'s names, unchanged: `@JvmStatic` on all four.**
 * `DatabaseBackend`'s four methods are now one-line delegations to these (the "one delegating step"
 * the handover asks for), so the five `PinnedMessageRepository` call sites and the method
 * signatures they compile against do not move. A `@JvmStatic` member of an `object` is the only
 * shape whose static bridge carries the un-mangled name — a Kotlin `internal` member would be
 * emitted as `pin$data` and Java could not see it (the `ScriptReading`/`TranslationLanguages`
 * precedent).
 *
 * <p>**The null contracts are read off the caller, not off the columns.** `PinnedMessageRepository`
 * passes `getAccountUuidForConversation(...)`'s answer straight in, and that method answers `null`
 * when the conversation names no account; it also passes `decryptedText`, which is `null` when the
 * pinned JSON's body did not decrypt, and `cid?.toString()`. All three are nullable here. The other
 * three parameters are the caller's non-null `String`/`long` locals, and `forConversation`'s
 * `Cursor` is the one `db.query` always answers - the caller's `cursor != null` guard stays
 * harmless.
 *
 * <p>**No `@Throws`: nothing on this path throws a checked exception the Java catches.** The
 * original four declared none, the caller wraps its own `try`, and `PinnedMessageRepository`
 * catches `Exception`, not a named type.
 */
object PinnedMessageStore {

    /**
     * The pin, replaced rather than duplicated on the message uuid - `pinned_messages`'s own primary
     * key is what `CONFLICT_REPLACE` resolves against, so re-pinning one message overwrites its row.
     *
     * @param accountUuid the conversation's account, or `null` when
     *     `getAccountUuidForConversation` named none
     * @param body the decrypted body, or `null` when the stored body did not decrypt
     * @param cid the attachment's content identifier as stored, or `null` for a text pin
     */
    @JvmStatic
    fun pin(
        db: SQLiteDatabase,
        messageUuid: String,
        conversationUuid: String,
        accountUuid: String?,
        body: String?,
        cid: String?,
        timestamp: Long,
    ) {
        val values = ContentValues()
        values.put(PinnedMessage.MESSAGE_UUID, messageUuid)
        values.put(PinnedMessage.CONVERSATION_UUID, conversationUuid)
        values.put(PinnedMessage.ACCOUNT_UUID, accountUuid)
        values.put(PinnedMessage.BODY, body)
        values.put(PinnedMessage.TIMESTAMP, timestamp)
        values.put(PinnedMessage.CID, cid)
        db.insertWithOnConflict(
            PinnedMessage.TABLENAME,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** The pin on one message, gone. The message itself is untouched. */
    @JvmStatic
    fun unpin(db: SQLiteDatabase, messageUuid: String) {
        db.delete(
            PinnedMessage.TABLENAME,
            PinnedMessage.MESSAGE_UUID + "=?",
            arrayOf(messageUuid),
        )
    }

    /**
     * One conversation's pins, newest first, as the raw cursor the repository walks.
     *
     * <p>The cursor leaves this package because that is the shape the one caller reads: it needs the
     * four fields plus the body's own decryption, and `PinnedMessage`'s own row type carries
     * `encryptedContent`/`iv` that the repository never uses. Turning the cursor into a list here
     * would be a second projection of the same row and a behaviour change, so it is not made.
     */
    @JvmStatic
    fun forConversation(db: SQLiteDatabase, conversationUuid: String): Cursor =
        db.query(
            PinnedMessage.TABLENAME,
            null,
            PinnedMessage.CONVERSATION_UUID + "=?",
            arrayOf(conversationUuid),
            null,
            null,
            PinnedMessage.TIMESTAMP + " DESC",
        )

    /** One conversation's pin on one message, gone: the pair the delete button names. */
    @JvmStatic
    fun delete(db: SQLiteDatabase, conversationUuid: String, messageUuid: String) {
        db.delete(
            PinnedMessage.TABLENAME,
            PinnedMessage.CONVERSATION_UUID + "=? and " + PinnedMessage.MESSAGE_UUID + "=?",
            arrayOf(conversationUuid, messageUuid),
        )
    }
}
