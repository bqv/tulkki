package uk.xa0.tulkki.data.references

import uk.xa0.tulkki.data.model.Message

/**
 * `references/`'s statements (S5-3, the `references/` capability).
 *
 * <p>Two columns of `messages` and no table: `payloads`, the raw XEP-0334-ish extension payloads
 * stored with a row, and the lookup a reply falls back to when the id it was handed is not the row's
 * `uuid`. `messages/` owns the `CREATE`; this package owns the column's meaning.
 *
 * <p>**The fallback lookup is the reason this package exists at all.** A quoted message arrives
 * naming a stanza id, and the row that answers it may be keyed by that id, by its own `uuid`, or by
 * the remote id its sender put on the wire - the same three-way predicate `DatabaseBackend`'s
 * `getMessagesNearUuid` anchors on. It is one statement here rather than three call sites, and it
 * answers the row's `uuid` because the caller has the conversation and wants the row.
 *
 * <p>`payloads` is written whole, like every other document column in this schema: the model builds
 * the list and the column holds it, so there is nothing here to append to.
 */
internal object ReferenceQueries {

    const val TABLE = Message.TABLENAME
    const val UUID = Message.UUID
    const val CONVERSATION = Message.CONVERSATION
    const val SERVER_MSG_ID = Message.SERVER_MSG_ID
    const val REMOTE_MSG_ID = Message.REMOTE_MSG_ID
    const val PAYLOADS = Message.PAYLOADS
    const val TIME_SENT = Message.TIME_SENT

    /** The row's payload document, or `null` when the row carries none. */
    const val PAYLOADS_OF =
        "SELECT " + PAYLOADS + " FROM " + TABLE + " WHERE " + UUID + " = :uuid"

    /** The document again, written whole. */
    const val SET_PAYLOADS =
        "UPDATE " + TABLE + " SET " + PAYLOADS + " = :payloads WHERE " + UUID + " = :uuid"

    /**
     * The quote fallback: the newest row of one conversation that any of the three ids names, or no
     * row at all. `ORDER BY timeSent DESC LIMIT 1` because two rows can legitimately carry the same
     * remote id and the caller is asking about the one it just saw.
     */
    const val QUOTE_FALLBACK =
        "SELECT " +
            UUID +
            " FROM " +
            TABLE +
            " WHERE " +
            CONVERSATION +
            " = :conversation AND (" +
            SERVER_MSG_ID +
            " = :messageId OR " +
            REMOTE_MSG_ID +
            " = :messageId OR " +
            UUID +
            " = :messageId) ORDER BY " +
            TIME_SENT +
            " DESC LIMIT 1"
}
