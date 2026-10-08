package uk.xa0.tulkki.data.messages

import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Message

/**
 * `messages/`'s schema and every statement the package publishes (S5-3, the `messages/` capability).
 *
 * <p>**What this package owns.** The `messages` table - its column vocabulary, its eight indexes,
 * the FTS4 search table and its three triggers, and the eight statements the DAOs publish. The DDL
 * was in `DatabaseBackend.onCreate` (the legacy chain, since S5-1) and, in its rebuilt form, in
 * `Schema76`; both reference this object, so there is exactly one copy of each `CREATE` and no
 * caller can spell a `messages` table of its own. (The legacy caller went in S5-5; the entity is
 * what a fresh install gets, and `Schema76`'s rebuild is what an upgrade gets.) `docs/MIGRATION.md`, "Design: the data layer"
 * §6 step 3: "the capabilities take their tables ... each owning its DDL, its queries and its DAO
 * test".
 *
 * <p>**The one spelling of the 75 table, and it is the migration's.** `Schema76.MESSAGES_COLUMNS` is
 * the *rebuilt* table - `NUMBER` normalised to `INTEGER`, because Room compares a normalised
 * affinity and no `ALTER` can change a declared type - and it stays with the migration that runs the
 * create-copy-drop-rename. This object used to carry the legacy 75 `CREATE` as well, as a second
 * spelling of the file's shape; it is gone, because nothing executed it - Room's own
 * `createAllTables` makes the table on a fresh install, an upgraded file already has it, and the
 * host fixture reads the 75 shape out of `src/test/resources/schema-75.sql`. The *indexes* are the
 * opposite case and are one list, [INDEX_STATEMENTS], executed by both homes.
 *
 * <p>**The search statements are the one place the package does not restate production.** The live
 * search query is built at runtime by `DatabaseBackend.buildMessageSearchQuery`, which
 * `SearchInvariantTest` pins as source text; it is not a compile-time constant and cannot be a Room
 * `@Query`. So [SEARCH_ALL] and [SEARCH_IN_CONVERSATION] are the same statement with the two
 * variants the builder produces unrolled, and `MessagesDaoTest` proves they agree with the builder
 * *behaviourally* - the same uuids, over the same fixture - rather than by comparing text.
 */
internal object MessagesQueries {

    // -- the column vocabulary, read through the model so a persisted name is spelled once ---------

    const val TABLE = Message.TABLENAME
    const val UUID = Message.UUID
    const val CONVERSATION = Message.CONVERSATION
    const val TIME_SENT = Message.TIME_SENT
    const val TRANSLATED_BODY = Message.TRANSLATED_BODY
    const val TRANSLATION_STATE = Message.TRANSLATION_STATE
    const val DELIVERY = "delivery"

    // -- the eight indexes, by the names the file has had since schema 71 and earlier ---------------

    const val CONVERSATION_INDEX = "message_conversation_index"
    const val DELETED_INDEX = "message_deleted_index"
    const val EXPIRE_AT_INDEX = "message_expire_at_index"
    const val FILE_DELETED_INDEX = "message_file_deleted_index"
    const val FILE_PATH_INDEX = "message_file_path_index"
    const val TIME_INDEX = "message_time_index"
    const val TIME_RECEIVED_INDEX = "message_time_received_index"
    const val TYPE_INDEX = "message_type_index"

    // -- the legacy `CREATE`, verbatim from DatabaseBackend ----------------------------------------

    // -- the search index and its triggers ---------------------------------------------------------

    /**
     * The FTS4 table, external-content over [TRANSLATED_BODY] - the *displayed* text, so a row the
     * interface would cover has nothing to match. `Message.TRANSLATED_BODY` is the same string
     * `MessageIndexStore`'s search query matches, and both must stay that string.
     */
    @JvmField
    val CREATE_INDEX_TABLE =
        "CREATE VIRTUAL TABLE IF NOT EXISTS messages_index USING fts4" +
            " (uuid," +
            TRANSLATED_BODY +
            ",notindexed=\"uuid\",content=\"" +
            TABLE +
            "\",tokenize='unicode61')"

    @JvmField
    val CREATE_INSERT_TRIGGER =
        "CREATE TRIGGER IF NOT EXISTS after_message_insert AFTER INSERT ON " +
            TABLE +
            " BEGIN INSERT INTO messages_index(rowid,uuid," +
            TRANSLATED_BODY +
            ") VALUES(NEW.rowid,NEW.uuid,NEW." +
            TRANSLATED_BODY +
            "); END;"

    @JvmField
    val CREATE_UPDATE_TRIGGER =
        "CREATE TRIGGER IF NOT EXISTS after_message_update UPDATE OF uuid," +
            TRANSLATED_BODY +
            " ON " +
            TABLE +
            " BEGIN UPDATE messages_index SET " +
            TRANSLATED_BODY +
            "=NEW." +
            TRANSLATED_BODY +
            ",uuid=NEW.uuid WHERE rowid=OLD.rowid; END;"

    /** The trigger's name, so the migration that replaces it drops the one it is replacing (S5-12). */
    const val DELETE_TRIGGER = "after_message_delete"

    /**
     * **`BEFORE`, and that is a correctness fix rather than a spelling choice (S5-12).**
     *
     * <p>`messages_index` is external-content FTS4 (`content="messages"`), so the index holds no copy
     * of the text: the `DELETE` below reads the row being removed out of `messages` to work out which
     * terms to drop. An `AFTER DELETE` trigger runs when that row is already gone, so the delete
     * resolves to no terms and every message deletion leaks the deleted message's terms into the
     * index for ever - measured on the host over this exact DDL: with `AFTER` the term is still
     * found after the delete, with `BEFORE` it is gone. `BEFORE` is the only position where the row
     * still exists, which is what an external-content FTS delete needs.
     *
     * <p>The app's own search cannot surface the residue (it joins `messages`, whose row has gone),
     * so this is growth rather than readable text - but it is unbounded growth until the next full
     * rebuild. Schema 77 drops and recreates this trigger, because `CREATE TRIGGER IF NOT EXISTS`
     * cannot replace an existing one and the old spelling is on every file.
     */
    @JvmField
    val CREATE_DELETE_TRIGGER =
        "CREATE TRIGGER IF NOT EXISTS " +
            DELETE_TRIGGER +
            " BEFORE DELETE ON " +
            TABLE +
            " BEGIN DELETE FROM messages_index WHERE rowid=OLD.rowid; END;"

    @JvmField
    val CREATE_CONVERSATION_INDEX =
        "CREATE INDEX IF NOT EXISTS " + CONVERSATION_INDEX + " ON " + TABLE + "(" + CONVERSATION + ")"

    @JvmField
    val CREATE_DELETED_INDEX =
        "CREATE INDEX IF NOT EXISTS " + DELETED_INDEX + " ON " + TABLE + "(deleted)"

    @JvmField
    val CREATE_EXPIRE_AT_INDEX =
        "CREATE INDEX IF NOT EXISTS " + EXPIRE_AT_INDEX + " ON " + TABLE + "(" + Message.EXPIRE_AT + ")"

    @JvmField
    val CREATE_FILE_DELETED_INDEX =
        "CREATE INDEX IF NOT EXISTS " + FILE_DELETED_INDEX + " ON " + TABLE + "(file_deleted)"

    @JvmField
    val CREATE_FILE_PATH_INDEX =
        "CREATE INDEX IF NOT EXISTS " +
            FILE_PATH_INDEX +
            " ON " +
            TABLE +
            "(" +
            Message.RELATIVE_FILE_PATH +
            ")"

    @JvmField
    val CREATE_TIME_INDEX =
        "CREATE INDEX IF NOT EXISTS " + TIME_INDEX + " ON " + TABLE + "(" + TIME_SENT + ")"

    @JvmField
    val CREATE_TIME_RECEIVED_INDEX =
        "CREATE INDEX IF NOT EXISTS " +
            TIME_RECEIVED_INDEX +
            " ON " +
            TABLE +
            "(timeReceived)"

    @JvmField
    val CREATE_TYPE_INDEX =
        "CREATE INDEX IF NOT EXISTS " + TYPE_INDEX + " ON " + TABLE + "(" + Message.TYPE + ")"

    /**
     * Every `CREATE` `messages` needs after its table: the eight indexes and the search index's own
     * machinery. An index and a trigger belong to their table, so `DROP TABLE messages` takes them
     * down and this list puts them back. It is `IF NOT EXISTS` throughout and therefore re-runnable,
     * which is what the rebuild's own second run needs.
     */
    @JvmField
    val INDEX_STATEMENTS: List<String> =
        listOf(
            CREATE_CONVERSATION_INDEX,
            CREATE_DELETED_INDEX,
            CREATE_EXPIRE_AT_INDEX,
            CREATE_FILE_DELETED_INDEX,
            CREATE_FILE_PATH_INDEX,
            CREATE_TIME_INDEX,
            CREATE_TIME_RECEIVED_INDEX,
            CREATE_TYPE_INDEX,
            CREATE_INDEX_TABLE,
            CREATE_INSERT_TRIGGER,
            CREATE_UPDATE_TRIGGER,
            CREATE_DELETE_TRIGGER,
        )

    // -- the statements the DAO publishes ----------------------------------------------------------

    /** The conversation's rows in reading order. `timeSent` ascending, as the design spells it. */
    const val BY_CONVERSATION =
        "SELECT * FROM " +
            TABLE +
            " WHERE " +
            CONVERSATION +
            " = :conversation ORDER BY " +
            TIME_SENT +
            " ASC"

    /** One row by its own key. */
    const val BY_UUID = "SELECT * FROM " + TABLE + " WHERE " + UUID + " = :uuid"

    /** One row gone. The conversation's foreign key is `ON DELETE CASCADE`, not this statement. */
    const val DELETE_BY_UUID = "DELETE FROM " + TABLE + " WHERE " + UUID + " = :uuid"

    /** [BY_CONVERSATION] again as the read model's watch: the same statement, a `Flow` return (S5-6). */
    const val WATCH_BY_CONVERSATION = BY_CONVERSATION

    /** The search read's row fetch: one statement for the whole result set (S5-6). */
    const val BY_UUIDS = "SELECT * FROM " + TABLE + " WHERE " + UUID + " IN (:uuids)"

    /**
     * **The search read is not here, and that is a measurement rather than a gap.** The live search
     * query is built at runtime by `DatabaseBackend.buildMessageSearchQuery` from the parsed term;
     * it reads `messages_index`, an FTS4 table Room does not declare (`docs/MIGRATION.md`, "Design:
     * the data layer" §2.4 - a raw table is invisible to Room), and Room's compile-time verifier
     * refuses a `@Query` that names a table in no entity list. Measured, not assumed:
     *
     * <pre>
     * e: [ksp] MessagesDao.kt:53: There is a problem with the query:
     *     [SQLITE_ERROR] SQL error or missing database (no such table: messages_index)
     * </pre>
     *
     * So `MessagesDao.search` is a `@RawQuery` and the statement it is handed is the builder's. The
     * package test executes that statement over its own fixture and says in as many words that it
     * cannot call the generated DAO method.
     */

    // -- the row readers and writers `MessageStore` executes (port-45's `messages: rows`) ----------
    //
    // These are `?`-bound statements rather than the DAO's `:name` ones: the live callers hand them
    // to `rawQuery`, and a Room named bind is not executable there. Every table and column name is
    // still this object's, and the statement text is the Java body's own, line for line.

    /** `MessageStore.getMessage`: one row by its own key. */
    const val ROW_BY_UUID = "SELECT * FROM " + TABLE + " WHERE " + UUID + "=?"

    /** `getMessageWithServerMsgId`: the server's own id, within the conversation. */
    const val ROW_BY_SERVER_MSG_ID =
        "select * from " +
            TABLE +
            " where " +
            CONVERSATION +
            "=? and " +
            Message.SERVER_MSG_ID +
            "=? LIMIT 1"

    /** `getMessageWithUuidOrRemoteId`: the local uuid or the peer's id, whichever names it. */
    const val ROW_BY_UUID_OR_REMOTE_ID =
        "select * from " +
            TABLE +
            " where " +
            CONVERSATION +
            "=? and (" +
            UUID +
            "=? OR " +
            Message.REMOTE_MSG_ID +
            "=?) LIMIT 1"

    /**
     * `getMessagesNearUuid`: the window around one `(timeSent, uuid)` anchor, half of it older and
     * half newer. The Java built it inline; the `limit / 2` bounds stay bound rather than
     * interpolated, exactly as it had them.
     */
    @JvmField
    val NEAR_UUID =
        "WITH anchor AS (SELECT " +
            TIME_SENT +
            ", " +
            UUID +
            " FROM " +
            TABLE +
            " WHERE " +
            CONVERSATION +
            "=? AND (" +
            Message.SERVER_MSG_ID +
            "=? OR " +
            Message.REMOTE_MSG_ID +
            "=? OR " +
            UUID +
            "=?)" +
            " ORDER BY " +
            TIME_SENT +
            " DESC LIMIT 1) " +
            "SELECT * FROM ( " +
            "  SELECT m.* FROM " +
            TABLE +
            " m, anchor a " +
            "  WHERE m." +
            CONVERSATION +
            "=? AND (m." +
            TIME_SENT +
            " < a." +
            TIME_SENT +
            " OR (m." +
            TIME_SENT +
            " = a." +
            TIME_SENT +
            " AND m." +
            UUID +
            " < a." +
            UUID +
            ")) " +
            "  ORDER BY m." +
            TIME_SENT +
            " DESC, m." +
            UUID +
            " DESC LIMIT ? " +
            ") " +
            "UNION ALL " +
            "SELECT m.* FROM " +
            TABLE +
            " m, anchor a WHERE m." +
            UUID +
            " = a." +
            UUID +
            " " +
            "UNION ALL " +
            "SELECT * FROM ( " +
            "  SELECT m.* FROM " +
            TABLE +
            " m, anchor a " +
            "  WHERE m." +
            CONVERSATION +
            "=? AND (m." +
            TIME_SENT +
            " > a." +
            TIME_SENT +
            " OR (m." +
            TIME_SENT +
            " = a." +
            TIME_SENT +
            " AND m." +
            UUID +
            " > a." +
            UUID +
            ")) " +
            "  ORDER BY m." +
            TIME_SENT +
            " ASC, m." +
            UUID +
            " ASC LIMIT ? " +
            ") " +
            "ORDER BY " +
            TIME_SENT +
            " ASC, " +
            UUID +
            " ASC"

    /** `getRecentOutgoingLiveLocationMessages`: the account's live-location rows of the last 8 hours. */
    @JvmField
    val RECENT_LIVE_LOCATION =
        "SELECT m." +
            Message.CONVERSATION +
            ", m." +
            Message.PAYLOADS +
            " FROM " +
            TABLE +
            " m" +
            " JOIN " +
            Conversation.TABLENAME +
            " c ON m." +
            Message.CONVERSATION +
            " = c." +
            Conversation.UUID +
            " WHERE c." +
            Conversation.ACCOUNT +
            " = ?" +
            " AND m." +
            Message.STATUS +
            " > 0" +
            " AND m." +
            Message.PAYLOADS +
            " LIKE '%live-location%'" +
            " AND m." +
            Message.TIME_SENT +
            " > ?"

    /**
     * `getMessageFuzzyIds`: the three-way `IN` set the caller's id count decides. The caller joins
     * one placeholder per id, three times over, and this is the statement around them.
     */
    fun fuzzyIds(placeholders: String): String =
        "SELECT * FROM " +
            TABLE +
            " WHERE " +
            UUID +
            " IN (" +
            placeholders +
            ") OR " +
            Message.SERVER_MSG_ID +
            " IN (" +
            placeholders +
            ") OR " +
            Message.REMOTE_MSG_ID +
            " IN (" +
            placeholders +
            ")"

    /** `getMessages` with no anchor: the conversation's `limit` rows in the caller's sort order. */
    fun rowsForConversation(sorting: String, limit: Int): String =
        "SELECT * FROM " +
            TABLE +
            " WHERE " +
            UUID +
            " IN (" +
            "SELECT " +
            UUID +
            " FROM " +
            TABLE +
            " WHERE " +
            CONVERSATION +
            "=? " +
            "ORDER BY " +
            TIME_SENT +
            sorting +
            "," +
            UUID +
            sorting +
            "LIMIT " +
            limit +
            ") " +
            "ORDER BY " +
            TIME_SENT +
            sorting +
            "," +
            UUID +
            sorting

    /** `getMessages` around a `(timeSent, uuid)` anchor: `comparison` is `isForward`'s own `>`/`<`. */
    fun rowsAround(sorting: String, comparison: String, limit: Int): String =
        "SELECT * FROM " +
            TABLE +
            " WHERE " +
            UUID +
            " IN (" +
            "SELECT " +
            UUID +
            " FROM " +
            TABLE +
            " WHERE " +
            CONVERSATION +
            "=? AND (" +
            TIME_SENT +
            comparison +
            " ? OR (" +
            TIME_SENT +
            " = ? AND " +
            UUID +
            comparison +
            " ?)) " +
            "ORDER BY " +
            TIME_SENT +
            sorting +
            "," +
            UUID +
            sorting +
            "LIMIT " +
            limit +
            ") " +
            "ORDER BY " +
            TIME_SENT +
            sorting +
            "," +
            UUID +
            sorting
}
