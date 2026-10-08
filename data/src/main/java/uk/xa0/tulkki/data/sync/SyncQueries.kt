package uk.xa0.tulkki.data.sync

import uk.xa0.tulkki.data.model.Conversation

/**
 * `sync/`'s schema and every statement the package publishes (S5-3, the `sync/` capability).
 *
 * <p>Three tables, and each one is owned here rather than by the migration that first created it:
 * `docs/MIGRATION.md`, "Order, each step green" `S5-3` — "each owning its DDL, its queries and its
 * DAO test". The DDL was written in `Schema76` (S5-2) because the package did not exist yet; this
 * commit is where it moves, and `Schema76.STATEMENTS` now delegates to [STATEMENTS] so there is
 * still exactly one copy of the `CREATE`s and the two callers (the Room migration and the
 * fresh-install callback) still cannot disagree. A third, the legacy `onUpgrade` hook, went in
 * S5-5.
 *
 * <p>**Two primary keys gained `NOT NULL` while moving, and that is not tidiness.** Room validates
 * a pre-existing file by comparing each declared column's `notNull` flag (`TableInfo.Column`'s
 * equality, unconditional), and `RoomConnectionManager.onMigrate` runs that comparison *after* the
 * 75 -> 77 migrations, so a mismatch is a refused open on the owner's first launch rather than a
 * warning. SQLite reports `notnull = 0` for a bare `TEXT PRIMARY KEY` — measured, not assumed:
 * `CREATE TABLE t (a TEXT PRIMARY KEY)` answers `(0, 'a', 'TEXT', 0, None, 1)` to
 * `PRAGMA table_info` — so `sync_cursor.account_uuid` and `sync_conversation.conversation_uuid`
 * would have been nullable to Room while their entities declare non-null `String`s. The three
 * rebuilt tables in `Schema76` already spell `TEXT NOT NULL PRIMARY KEY` for exactly this reason;
 * these two now do too. No device has either table (the owner's file is at 75 and upgrades on first
 * launch), and both the fresh-install callback and the migration execute the same string, so the
 * correction cannot make the two paths diverge.
 *
 * <p>**The SQL of the queries is spelled in the DAOs, not named from here.** The constants below
 * are what `SyncDaoTest` compares the DAOs' own annotations against, so a drift between the three
 * is a red test; naming the constants from the annotation would compile to the same SQL but would
 * hide the query Room runs from a later reader (`BlockingDao`'s comment carries the same rule).
 */
internal object SyncQueries {

    // -- sync_cursor: one row per account, the paging anchor --------------------------------------

    const val CURSOR_TABLE = "sync_cursor"
    const val CURSOR_ACCOUNT = "account_uuid"
    const val CURSOR_ANCHOR_STANZA = "anchor_stanza_id"
    const val CURSOR_ANCHOR_TIME = "anchor_time"
    const val CURSOR_ANCHOR_SOURCE = "anchor_source"
    const val CURSOR_GAP_END = "gap_end"
    const val CURSOR_UPDATED_AT = "updated_at"

    // -- sync_conversation: per conversation, the anchor and the sweep floor ----------------------

    const val CONVERSATION_TABLE = "sync_conversation"
    const val CONVERSATION_UUID = "conversation_uuid"
    const val CONVERSATION_ACCOUNT = "account_uuid"
    const val CONVERSATION_ANCHOR_STANZA = "anchor_stanza_id"
    const val CONVERSATION_ANCHOR_TIME = "anchor_time"
    const val CONVERSATION_ARCHIVE_FIRST = "archive_first_id"
    const val CONVERSATION_SWEPT_THROUGH = "swept_through"
    const val CONVERSATION_UPDATED_AT = "updated_at"

    /**
     * The account sweep is one indexed query only because `account_uuid` is denormalised onto
     * `sync_conversation`; "Design: synchronisation" §1.1 names the index as the reason and never
     * spells it as DDL. Room compares index **names**, so the name is part of the entity too.
     */
    const val CONVERSATION_ACCOUNT_INDEX = "sync_conversation_account_index"

    // -- sync_gap: one row per catch-up region the engine opened; this is the ledger --------------

    const val GAP_TABLE = "sync_gap"
    const val GAP_ACCOUNT = "account_uuid"
    const val GAP_CONVERSATION = "conversation_uuid"
    const val GAP_START = "gap_start"
    const val GAP_END = "gap_end"
    const val GAP_REGION = "region"
    const val GAP_STATE = "state"
    const val GAP_REASON = "reason"
    const val GAP_OPENED_AT = "opened_at"
    const val GAP_CLOSED_AT = "closed_at"

    /** `state`'s values: 0 OPEN, 1 COMPLETE, 2 DEGRADED. */
    const val GAP_STATE_OPEN = 0
    const val GAP_STATE_COMPLETE = 1
    const val GAP_STATE_DEGRADED = 2

    /**
     * `messages.delivery`'s two written values, next to the third that is never written:
     * `Schema76.DELIVERY_UNKNOWN = 2`, which is every row that predates the column and is exactly the
     * set both sweeps must leave alone. A live stanza belongs to the live-miss sweep, an archive
     * delivery to the gap sweep.
     */
    const val DELIVERY_LIVE = 0L
    const val DELIVERY_ARCHIVE = 1L

    /** `anchor_source`'s values. Only the seeded one is written by the 75 -> 76 migration. */
    const val ANCHOR_SOURCE_INITIAL = 0
    const val ANCHOR_SOURCE_SEEDED_FROM_STORE = 1
    const val ANCHOR_SOURCE_MAM_FIN = 2
    const val ANCHOR_SOURCE_CLEAR_HISTORY = 3
    const val ANCHOR_SOURCE_LIVE_STANZA = 4

    // -- the DDL, spelled once, executed by every caller ------------------------------------------

    const val CREATE_CURSOR_TABLE =
        "CREATE TABLE IF NOT EXISTS sync_cursor (" +
            "account_uuid TEXT NOT NULL PRIMARY KEY, " +
            "anchor_stanza_id TEXT, " +
            "anchor_time INTEGER NOT NULL DEFAULT 0, " +
            "anchor_source INTEGER NOT NULL DEFAULT 0, " +
            "gap_end INTEGER NOT NULL DEFAULT 0, " +
            "updated_at INTEGER NOT NULL, " +
            "FOREIGN KEY(account_uuid) REFERENCES accounts(uuid) ON DELETE CASCADE)"

    /**
     * A plain `val`, not a `const`: the foreign key names the persisted `conversations` table through
     * the model's own constant rather than spelling the word here. That is `Schema76`'s original
     * shape, restored - the literal I introduced while moving the DDL was the one new spelling of
     * that name in the tree, and the naming sweep is what found it.
     */
    val CREATE_CONVERSATION_TABLE =
        "CREATE TABLE IF NOT EXISTS sync_conversation (" +
            "conversation_uuid TEXT NOT NULL PRIMARY KEY, " +
            "account_uuid TEXT NOT NULL, " +
            "anchor_stanza_id TEXT, " +
            "anchor_time INTEGER NOT NULL DEFAULT 0, " +
            "archive_first_id TEXT, " +
            "swept_through INTEGER NOT NULL DEFAULT 0, " +
            "updated_at INTEGER NOT NULL, " +
            "FOREIGN KEY(conversation_uuid) REFERENCES " +
            Conversation.TABLENAME +
            "(uuid) ON DELETE CASCADE)"

    const val CREATE_CONVERSATION_ACCOUNT_INDEX =
        "CREATE INDEX IF NOT EXISTS " +
            CONVERSATION_ACCOUNT_INDEX +
            " ON " +
            CONVERSATION_TABLE +
            " (" +
            CONVERSATION_ACCOUNT +
            ")"

    /**
     * `sync_gap.conversation_uuid` is `NOT NULL DEFAULT ''` and not nullable: SQLite treats `NULL`s
     * as distinct in a unique index, so an account-wide gap row with a null conversation could be
     * inserted twice and the composite primary key would not stop it. The empty string is the
     * account-wide scope ("Design: synchronisation" §1.1).
     */
    const val CREATE_GAP_TABLE =
        "CREATE TABLE IF NOT EXISTS sync_gap (" +
            "account_uuid TEXT NOT NULL, " +
            "conversation_uuid TEXT NOT NULL DEFAULT '', " +
            "gap_start INTEGER NOT NULL, " +
            "gap_end INTEGER NOT NULL, " +
            "region INTEGER NOT NULL DEFAULT 0, " +
            "state INTEGER NOT NULL DEFAULT 0, " +
            "reason TEXT, " +
            "opened_at INTEGER NOT NULL, " +
            "closed_at INTEGER, " +
            "PRIMARY KEY (account_uuid, conversation_uuid, gap_start, region), " +
            "FOREIGN KEY(account_uuid) REFERENCES accounts(uuid) ON DELETE CASCADE)"

    /**
     * Everything schema 76 adds at the DDL level. Every statement is `IF NOT EXISTS`, so the list
     * is re-runnable - which is what makes the legacy hook safe to run on a file that has already
     * seen it.
     */
    @JvmField
    val STATEMENTS: List<String> =
        listOf(
            CREATE_CURSOR_TABLE,
            CREATE_CONVERSATION_TABLE,
            CREATE_CONVERSATION_ACCOUNT_INDEX,
            CREATE_GAP_TABLE,
        )

    // -- the statements the DAOs publish ----------------------------------------------------------

    const val CURSOR_BY_ACCOUNT =
        "SELECT * FROM sync_cursor WHERE account_uuid = :account"

    /**
     * `INSERT OR REPLACE`, and written only for an account the file still has. **A cursor row
     * requires its parent**, and the parent can be gone before this statement runs: the registration
     * flow's abort deletes the account on the database-writer executor while the socket's teardown
     * still reaches the engine on its own thread (`AccountLifecycle.deleteAccount` queues the
     * `DELETE`, then `disconnect` reports `onSessionEnded` to `tulkki-sync`). The handset died of
     * exactly that with `FOREIGN KEY constraint failed (code 787)` from `SyncCursorDao_Impl.upsert`.
     *
     * <p>The guard is part of the statement, not a check in the engine, because the two writes race
     * across two threads and a check-then-write would be the same race one instruction narrower. The
     * foreign key stays: a cursor describing an account the file does not have is the orphan it
     * exists to refuse, and the delete's own `ON DELETE CASCADE` takes a cursor that was written
     * first.
     */
    const val CURSOR_UPSERT =
        "INSERT OR REPLACE INTO sync_cursor " +
            "(account_uuid, anchor_stanza_id, anchor_time, anchor_source, gap_end, updated_at) " +
            "SELECT :account, :stanzaId, :time, :source, :gapEnd, :updatedAt " +
            "WHERE EXISTS (SELECT 1 FROM accounts WHERE uuid = :account)"

    const val CURSOR_REMOVE =
        "DELETE FROM sync_cursor WHERE account_uuid = :account"

    const val CONVERSATION_BY_CONVERSATION =
        "SELECT * FROM sync_conversation WHERE conversation_uuid = :conversation"

    const val CONVERSATION_CURSORS_BY_ACCOUNT =
        "SELECT * FROM sync_conversation WHERE account_uuid = :account " +
            "ORDER BY conversation_uuid ASC"

    const val CONVERSATION_UPSERT =
        "INSERT OR REPLACE INTO sync_conversation " +
            "(conversation_uuid, account_uuid, anchor_stanza_id, anchor_time, archive_first_id, " +
            "swept_through, updated_at) " +
            "VALUES (:conversation, :account, :stanzaId, :time, :archiveFirst, :sweptThrough, " +
            ":updatedAt)"

    const val CONVERSATION_ADVANCE_SWEPT_THROUGH =
        "UPDATE sync_conversation SET swept_through = :sweptThrough, updated_at = :updatedAt " +
            "WHERE conversation_uuid = :conversation"

    const val GAP_BY_KEY =
        "SELECT * FROM sync_gap WHERE account_uuid = :account " +
            "AND conversation_uuid = :conversation AND gap_start = :gapStart AND region = :region"

    const val GAPS_OPEN_FOR_ACCOUNT =
        "SELECT * FROM sync_gap WHERE account_uuid = :account AND state = 0 " +
            "ORDER BY gap_start ASC, region ASC"

    /**
     * Every region the account has ever opened, in one order. The engine rebuilds its in-memory
     * ledger from this after a process death, and it needs the `COMPLETE` and `DEGRADED` rows as well
     * as the `OPEN` ones: completeness is a statement about *all* of a gap's regions, and a degraded
     * region is re-opened from its row rather than from a memory the process no longer has.
     */
    const val GAPS_FOR_ACCOUNT =
        "SELECT * FROM sync_gap WHERE account_uuid = :account " +
            "ORDER BY gap_start ASC, region ASC"

    /** The engine's other account-keyed insert, guarded for the reason [CURSOR_UPSERT] states. */
    const val GAP_OPEN =
        "INSERT OR REPLACE INTO sync_gap " +
            "(account_uuid, conversation_uuid, gap_start, gap_end, region, state, reason, opened_at, " +
            "closed_at) SELECT :account, :conversation, :gapStart, :gapEnd, :region, :state, " +
            ":reason, :openedAt, :closedAt " +
            "WHERE EXISTS (SELECT 1 FROM accounts WHERE uuid = :account)"

    const val GAP_CLOSE =
        "UPDATE sync_gap SET state = :state, reason = :reason, closed_at = :closedAt " +
            "WHERE account_uuid = :account AND conversation_uuid = :conversation " +
            "AND gap_start = :gapStart AND region = :region"

    /**
     * The gap sweep ("Design: synchronisation" §1.2): bounded by construction, no floor. It selects
     * the row's `uuid` rather than `m.*` because `messages` and `conversations` both carry a `uuid`
     * and a `status` column, and a `@Query` maps its result columns to the return type by name -
     * a `SELECT *` over this join is a column collision, which is a reason rather than a
     * measurement, and the statement's own WHERE clause is the design's.
     *
     * <p><strong>The account-wide region is a wildcard, and S5-8 found that the plain subquery
     * missed it.</strong> A `sync_gap` row whose `conversation_uuid` is the empty string *is* the
     * account-wide catch-up (§1.1), and the ordinary case - an absence shorter than
     * `Config.MAM_MAX_CATCHUP` - opens that row and no other (§2.2). A sweep scoped by
     * `conversation_uuid IN (…)` alone therefore matched nothing for exactly that case, and since
     * §2.3 makes the sweep the only reader of the gap, a resume would have translated nothing: the
     * one decision the gap exists for. The `EXISTS` spells the region's own scope - the empty string
     * means every conversation of the account, any other value means that one - and it is an
     * `EXISTS` rather than a second join so that an open account-wide row *and* an open
     * per-conversation row cannot return the same message twice.
     */
    const val GAP_SWEEP_CANDIDATES =
        "SELECT m.uuid FROM messages m JOIN conversations c ON c.uuid = m.conversationUuid " +
            "WHERE c.accountUuid = :account AND m.status = 0 AND m.translation_state = 0 " +
            "AND m.delivery = 1 AND EXISTS (SELECT 1 FROM sync_gap g " +
            "WHERE g.account_uuid = :account AND g.state = 0 " +
            "AND (g.conversation_uuid = '' OR g.conversation_uuid = m.conversationUuid))"

    /** The live-miss sweep: bounded by the per-conversation floor, one conversation at a time. */
    const val LIVE_MISS_CANDIDATES =
        "SELECT uuid FROM messages WHERE conversationUuid = :conversation AND status = 0 " +
            "AND translation_state = 0 AND delivery = 0 AND timeSent > :sweptThrough " +
            "ORDER BY timeSent DESC LIMIT :limit"
}
