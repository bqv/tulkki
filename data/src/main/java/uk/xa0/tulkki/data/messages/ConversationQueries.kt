package uk.xa0.tulkki.data.messages

import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Message

/**
 * The conversation table's DDL and the statements `ConversationDao` publishes (S5-3, the `messages/`
 * capability).
 *
 * <p>**The name is singular on purpose.** The design's §2.5 table calls this DAO `ConversationsDao`,
 * and the naming rule bans that spelling outright: `docs/MIGRATION.md`, "The naming rule" bans the
 * plural in every identifier we write, and the sweep's own pattern matches `Conversation` + a
 * lowercase `s` inside a longer token. The persisted table's name is not ours to change, so it is
 * reached through `Conversation.TABLENAME` and is never spelled here - the licence for the raw SQL
 * in `tools/verify-allowlist` covers a statement that names the table, not a name of ours.
 *
 * <p>The 75 table's shape is `Schema76.CONVERSATION_COLUMNS`'s, and it stays with the migration,
 * for the reason `MessagesQueries`'s comment gives. This object used to carry the legacy 75 `CREATE`
 * as a second spelling; it is gone, because nothing executed it - Room's `createAllTables` makes the
 * table on a fresh install and an upgraded file already has it.
 */
internal object ConversationQueries {

    const val TABLE = Conversation.TABLENAME
    const val UUID = Conversation.UUID
    const val ACCOUNT = Conversation.ACCOUNT
    const val CONTACT = Conversation.CONTACT
    const val CONTACT_JID = Conversation.CONTACTJID
    const val NAME = Conversation.NAME
    const val CREATED = Conversation.CREATED
    const val STATUS = Conversation.STATUS
    const val MODE = Conversation.MODE
    const val ATTRIBUTES = Conversation.ATTRIBUTES
    const val DETECTED_LANGUAGE = Conversation.DETECTED_LANGUAGE
    const val LANGUAGE_OVERRIDE = Conversation.LANGUAGE_OVERRIDE
    const val DOUBT_HOLD = Conversation.DOUBT_HOLD

    /** One row by its own key. */
    const val BY_UUID = "SELECT * FROM " + TABLE + " WHERE " + UUID + " = :uuid"

    /** Every row of one account, in a stable order: the account sweep's own read. */
    const val ROWS_FOR_ACCOUNT =
        "SELECT * FROM " + TABLE + " WHERE " + ACCOUNT + " = :account ORDER BY " + UUID + " ASC"

    /** One row gone; the account's cascade is the foreign key's job, not this statement's. */
    const val DELETE_BY_UUID = "DELETE FROM " + TABLE + " WHERE " + UUID + " = :uuid"

    /**
     * One status's rows that name a contact, newest first: the legacy list read
     * `ConversationStore.list` executes, whose table and columns are this object's.
     */
    const val LIST_FOR_STATUS =
        "select * from " + TABLE + " where " + STATUS + " = ? and " + CONTACT_JID +
            " is not null order by " + CREATED + " desc"

    /**
     * One conversation's uuid found through its account's credentials rather than its own key -
     * the read `ConversationStore.findUuid` executes when the caller holds the pair, not the uuid.
     */
    const val UUID_FOR_ACCOUNT_JID =
        "SELECT " + TABLE + "." + UUID + " FROM " + TABLE + " JOIN " + Account.TABLENAME +
            " ON " + TABLE + "." + ACCOUNT + "=" + Account.TABLENAME + "." + Account.UUID +
            " WHERE " + Account.TABLENAME + "." + Account.USERNAME + "=? AND " + Account.TABLENAME +
            "." + Account.SERVER + "=? AND (" + CONTACT_JID + "=? OR " + CONTACT_JID + " LIKE ?)"

    /** [BY_UUID] again as the read model's watch: the same statement, a `Flow` return (S5-6). */
    const val WATCH_BY_UUID = BY_UUID

    /**
     * The list with its last-message pointer: every column of the row plus an id and an instant per
     * conversation, **no body column** (`docs/MIGRATION.md`, "Design: the Compose UI" §2.3 - the
     * preview is the projector's to build, and a body read here would put one on the boundary by
     * accident). Two correlated subqueries rather than a `GROUP BY` join: the pointer is the newest
     * row's own id *and* its instant, and `MAX(timeSent)` answers only the second.
     *
     * <p>**It is one statement on purpose** (§2.3 invariant 4: "The list is one joined query per
     * emission, not N+1"), and the same statement serves the synchronous read and the `Flow`, so the
     * two cannot drift. S5-3's narrower pointer projection was `SELECT c.uuid AS conversationUuid,
     * ...`; S5-6 widens it to the row [ConversationRow] embeds, which is why the interaction it
     * replaces (`ConversationPointer`) is gone rather than kept beside it.
     */
    const val LIST_FOR_ACCOUNT =
        "SELECT c." +
            UUID +
            ", c." +
            NAME +
            ", c." +
            CONTACT +
            ", c." +
            ACCOUNT +
            ", c." +
            CONTACT_JID +
            ", c." +
            CREATED +
            ", c." +
            STATUS +
            ", c." +
            MODE +
            ", c." +
            ATTRIBUTES +
            ", c." +
            DETECTED_LANGUAGE +
            ", c." +
            LANGUAGE_OVERRIDE +
            ", c." +
            DOUBT_HOLD +
            ", (SELECT m." +
            Message.UUID +
            " FROM " +
            Message.TABLENAME +
            " m WHERE m." +
            Message.CONVERSATION +
            " = c." +
            UUID +
            " ORDER BY m." +
            Message.TIME_SENT +
            " DESC LIMIT 1) AS lastMessageId, (SELECT m." +
            Message.TIME_SENT +
            " FROM " +
            Message.TABLENAME +
            " m WHERE m." +
            Message.CONVERSATION +
            " = c." +
            UUID +
            " ORDER BY m." +
            Message.TIME_SENT +
            " DESC LIMIT 1) AS lastMessageAt FROM " +
            TABLE +
            " c WHERE c." +
            ACCOUNT +
            " = :account ORDER BY c." +
            UUID +
            " ASC"

    /** [LIST_FOR_ACCOUNT] again as the read model's watch: the same statement, a `Flow` return (S5-6). */
    const val WATCH_ACCOUNT_LIST = LIST_FOR_ACCOUNT
}
