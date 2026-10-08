package uk.xa0.tulkki.data.translation

import uk.xa0.tulkki.data.model.Message

/**
 * `translation/`'s by-uuid message read (S5-6): the rows a gap named, as the messages they are.
 *
 * <p>**One statement, built for a chunk.** SQLite bounds a statement's parameters, and a gap can name
 * hundreds of messages, so the read goes in chunks and the `IN (...)` list has to be built for the
 * chunk's size - which is why this is a function and not a constant. Everything else about it is
 * fixed: the table, the key, and `SELECT *` because `Message.fromCursor` reads the whole row.
 *
 * <p>`TranslationStore` composed this itself, holding the writable handle, and walked the cursor;
 * [MessageLookupStore] is its only caller from here on.
 */
internal object MessageLookupQueries {

    const val TABLE = Message.TABLENAME
    const val UUID = Message.UUID

    /**
     * How many uuids one `IN (...)` may name. Four hundred keeps the statement's parameter count well
     * inside SQLite's default limit (999 before 3.32) with room for the other binds a caller may add.
     */
    const val CHUNK = 400

    /** The statement for one chunk of exactly [count] uuids, each a positional bind. */
    @JvmStatic
    fun byUuid(count: Int): String {
        require(count > 0) { "a by-uuid read needs at least one uuid" }
        return "SELECT * FROM " + TABLE + " WHERE " + UUID + " IN (" + "?,".repeat(count - 1) + "?)"
    }
}
