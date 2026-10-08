package uk.xa0.tulkki.data

import uk.xa0.tulkki.data.model.Message

/**
 * Tulkki's own tables, and the names Tulkki's store uses to read them.
 *
 * Four tables, deliberately separate from upstream's `messages`:
 *
 * - `translation_queue` is the work that is owed. It is a table rather than a list because a message
 *   awaiting translation has to survive the process being killed.
 * - `translation_cache` is what was already paid for, keyed by `uk.xa0.tulkki.translation.CacheKey`, so a
 *   retry, a crash or a repeated message is never a second purchase.
 * - `translation_usage` is what the app has spent, as tokens per local day and per tariff, so the usage
 *   screen can show a per-day yuan figure that an edited price re-prices.
 * - `translation_usage_origin` is that same spend split by the conversation it belonged to (schema 78), for
 *   the day's drill-down; the empty origin is the calls that belong to none.
 *
 * The statements are `IF NOT EXISTS` and the migration adds the columns one at a time behind a check, the
 * same way the schema-71 translation columns do, so this is safe to run both from `onCreate` on a fresh
 * install and from `onUpgrade` on an existing one. The queue's rekey (S5-12) is a rebuild rather than an
 * `ALTER` - SQLite cannot add a foreign key - and it is a second spelling of the create for the same reason
 * every other rebuild is.
 */
object TranslationTables {

    const val QUEUE_TABLE = "translation_queue"
    const val QUEUE_MESSAGE_UUID = "message_uuid"
    const val QUEUE_CONVERSATION_UUID = "conversation_uuid"
    const val QUEUE_BODY = "body"
    const val QUEUE_TARGET_LANGUAGE = "target_language"
    const val QUEUE_CACHE_KEY = "cache_key"
    const val QUEUE_STATE = "state"
    const val QUEUE_ATTEMPTS = "attempts"
    const val QUEUE_NEXT_ATTEMPT_AT = "next_attempt_at"
    const val QUEUE_LAST_ERROR = "last_error"
    const val QUEUE_CREATED_AT = "created_at"

    /**
     * Tulkki: when the most recent attempt failed, so the failures screen can time a failure by its own
     * moment rather than by when the message arrived. Schema 73, additive; a row written before that
     * migration has none, which the screen says rather than passing the arrival off as the failure.
     */
    const val QUEUE_FAILED_AT = "failed_at"

    /**
     * Tulkki: item 17's per-row failure cause - the reason a queued row is stopped, for the reasons the
     * queue's retry axis cannot express. `TEXT` and nullable: `NULL` is "no cause recorded", which is both
     * a row that was never blocked and an ordinary retryable API failure (that axis is
     * `attempts`/`next_attempt_at`), so it is not the same as a cause the app could not clear. The
     * vocabulary is `:translation`'s (its enum spells the strings); `:data` stores the string. Written when
     * a received row is stopped, cleared on a re-enqueue (`MAKE_DUE`) or on `DONE`.
     */
    const val QUEUE_FAILURE_CAUSE = "failure_cause"

    const val CACHE_TABLE = "translation_cache"
    const val CACHE_KEY = "cache_key"
    const val CACHE_DETECTED_LANGUAGE = "detected_language"
    const val CACHE_TRANSLATED_BODY = "translated_body"
    const val CACHE_TOTAL_TOKENS = "total_tokens"
    const val CACHE_CREATED_AT = "created_at"

    /**
     * Tulkki: the app's own DeepSeek tokens, one row per local calendar day, split by the tariff the call
     * was billed under. Schema 74, additive like everything before it.
     *
     * Tokens and not money: the prices are editable settings (`uk.xa0.tulkki.translation.TokenPrices`), so a
     * stored figure would freeze every past day at the price in force when it was made, while a stored token
     * count can be re-priced by an edit. The six columns are exactly
     * `uk.xa0.tulkki.translation.TokenUsage`'s six counts, and the day is spelled the way
     * `uk.xa0.tulkki.translation.DailyTokenCounter#dayOf` spells it, so the ledger and the daily cap agree
     * about when a day ended.
     */
    const val USAGE_TABLE = "translation_usage"

    /** The local calendar day, ISO, and the primary key: one row per day. */
    const val USAGE_DAY = "day"

    const val USAGE_PEAK_CACHE_HIT = "peak_cache_hit"
    const val USAGE_PEAK_CACHE_MISS = "peak_cache_miss"
    const val USAGE_PEAK_OUTPUT = "peak_output"
    const val USAGE_OFF_PEAK_CACHE_HIT = "off_peak_cache_hit"
    const val USAGE_OFF_PEAK_CACHE_MISS = "off_peak_cache_miss"
    const val USAGE_OFF_PEAK_OUTPUT = "off_peak_output"

    /**
     * S5-12: the queue's row is the message's row, so the message's own `ON DELETE CASCADE` takes it. The
     * legacy declaration was keyed by [QUEUE_MESSAGE_UUID] with no foreign key at all, which is what let a
     * deleted message's queue row - and with it the raw `body`, the phantom failures row and the paid retry
     * on a dead uuid - outlive its message.
     *
     * It is not an `ALTER`: SQLite cannot add a foreign key to an existing table, so the table is rebuilt by
     * schema 77 in the same create-copy-drop-rename shape the three entity tables and the seven declared
     * tables already use. The columns in [CREATE_QUEUE_TABLE] are the legacy declaration's, in the same
     * order, with the key and the `NOT NULL`s it always had; this is the tail it gains. A row whose message
     * is already gone is dropped by the migration before the copy, because a cascade cannot be added over a
     * row that already violates it - and that row is exactly the orphan the finding measured.
     */
    const val QUEUE_FOREIGN_KEY_TAIL =
        ", FOREIGN KEY(" +
            QUEUE_MESSAGE_UUID +
            ") REFERENCES " +
            Message.TABLENAME +
            "(" +
            Message.UUID +
            ") ON DELETE CASCADE"

    /**
     * The one declaration of the queue table, executed by a fresh install (`DatabaseBackend.onCreate` through
     * `addTranslationQueueTables`) and by the schema-77 rebuild alike (S5-12). A fresh install and an upgraded
     * file must be the same table, so there is one spelling of it and the rebuild copies into this shape
     * rather than restating it.
     */
    const val CREATE_QUEUE_TABLE =
        "CREATE TABLE IF NOT EXISTS " +
            QUEUE_TABLE +
            " (" +
            QUEUE_MESSAGE_UUID +
            " TEXT NOT NULL PRIMARY KEY," +
            QUEUE_CONVERSATION_UUID +
            " TEXT," +
            QUEUE_BODY +
            " TEXT NOT NULL," +
            QUEUE_TARGET_LANGUAGE +
            " TEXT," +
            QUEUE_CACHE_KEY +
            " TEXT NOT NULL," +
            QUEUE_STATE +
            " INTEGER NOT NULL DEFAULT 0," +
            QUEUE_ATTEMPTS +
            " INTEGER NOT NULL DEFAULT 0," +
            QUEUE_NEXT_ATTEMPT_AT +
            " INTEGER NOT NULL DEFAULT 0," +
            QUEUE_LAST_ERROR +
            " TEXT," +
            QUEUE_CREATED_AT +
            " INTEGER NOT NULL," +
            QUEUE_FAILED_AT +
            " INTEGER," +
            QUEUE_FAILURE_CAUSE +
            " TEXT" +
            QUEUE_FOREIGN_KEY_TAIL +
            ")"

    /** The queue is always read as "pending and due", so index exactly that. */
    const val CREATE_QUEUE_INDEX =
        "CREATE INDEX IF NOT EXISTS " +
            QUEUE_TABLE +
            "_due ON " +
            QUEUE_TABLE +
            " (" +
            QUEUE_STATE +
            ", " +
            QUEUE_NEXT_ATTEMPT_AT +
            ")"

    /**
     * The orphan prune the rebuild needs first: a queue row whose message is gone. Only the migration runs
     * it, and only so the copy into the new table can succeed - the FK is the live mechanism from then on.
     */
    const val DELETE_ORPHANED_QUEUE_ROWS =
        "DELETE FROM " +
            QUEUE_TABLE +
            " WHERE " +
            QUEUE_MESSAGE_UUID +
            " NOT IN (SELECT " +
            Message.UUID +
            " FROM " +
            Message.TABLENAME +
            ")"

    const val CREATE_CACHE_TABLE =
        "CREATE TABLE IF NOT EXISTS " +
            CACHE_TABLE +
            " (" +
            CACHE_KEY +
            " TEXT PRIMARY KEY," +
            CACHE_DETECTED_LANGUAGE +
            " TEXT," +
            CACHE_TRANSLATED_BODY +
            " TEXT NOT NULL," +
            CACHE_TOTAL_TOKENS +
            " INTEGER NOT NULL DEFAULT 0," +
            CACHE_CREATED_AT +
            " INTEGER NOT NULL" +
            ")"

    /**
     * The per-day ledger. Every count defaults to zero and the day is the primary key, so the write path can
     * `INSERT OR IGNORE` the day and then add to it without ever reading first.
     *
     * Rows are kept for ever: this is a few bytes a day, and a window would be a decision about how far back
     * the owner's own history is worth keeping that nobody asked for and that cannot be undone once the rows
     * are gone.
     */
    const val CREATE_USAGE_TABLE =
        "CREATE TABLE IF NOT EXISTS " +
            USAGE_TABLE +
            " (" +
            USAGE_DAY +
            " TEXT PRIMARY KEY," +
            USAGE_PEAK_CACHE_HIT +
            " INTEGER NOT NULL DEFAULT 0," +
            USAGE_PEAK_CACHE_MISS +
            " INTEGER NOT NULL DEFAULT 0," +
            USAGE_PEAK_OUTPUT +
            " INTEGER NOT NULL DEFAULT 0," +
            USAGE_OFF_PEAK_CACHE_HIT +
            " INTEGER NOT NULL DEFAULT 0," +
            USAGE_OFF_PEAK_CACHE_MISS +
            " INTEGER NOT NULL DEFAULT 0," +
            USAGE_OFF_PEAK_OUTPUT +
            " INTEGER NOT NULL DEFAULT 0" +
            ")"

    /**
     * Tulkki: one local day's tokens **split by the conversation the call belonged to** (schema 78).
     *
     * The day table above answers "what did today cost"; this one answers "which rooms cost it", which is the
     * drill-down the ledger screen opens when the owner taps a day. It is a second table rather than a column
     * on the first because the first is one row per day and this is one row per day *and* origin, and because
     * the day's total has to stay a single row the daily cap and the ledger's own sum can both be read from.
     *
     * [USAGE_ORIGIN] is `TEXT NOT NULL DEFAULT ''` and **not** nullable, deliberately. SQLite treats every
     * `NULL` in a unique key as distinct, so a nullable origin could be inserted twice for the same day and
     * the bucket would stop summing to the day's total; the empty string is the one spelling of "this call
     * was not tied to a conversation" - gloss lookups, review and notes, the silent language sample and
     * pre-send suggestions - and it is the same convention `sync_gap` already uses for an account-wide row.
     *
     * The table is not an entity: it is written by the same insert-then-add pair the day table is, and
     * nothing declares it to Room. When the ledger's write path moves into `:data` (data-5) it will own this
     * DDL the way `translation_queue`'s is owned now.
     */
    const val USAGE_ORIGIN_TABLE = "translation_usage_origin"

    /**
     * The conversation the call belonged to, or `''` for a call that belongs to no conversation. The column
     * carries a key and no second snapshot of the conversation's address: the display name is resolved by
     * joining `conversations` at read time, so a renamed or removed room cannot leave a stale address behind
     * here.
     */
    const val USAGE_ORIGIN = "origin"

    /** The per-day, per-origin ledger (schema 78): one row per day and origin, keyed on the pair. */
    const val CREATE_USAGE_ORIGIN_TABLE =
        "CREATE TABLE IF NOT EXISTS " +
            USAGE_ORIGIN_TABLE +
            " (" +
            USAGE_DAY +
            " TEXT NOT NULL," +
            USAGE_ORIGIN +
            " TEXT NOT NULL DEFAULT ''," +
            USAGE_PEAK_CACHE_HIT +
            " INTEGER NOT NULL DEFAULT 0," +
            USAGE_PEAK_CACHE_MISS +
            " INTEGER NOT NULL DEFAULT 0," +
            USAGE_PEAK_OUTPUT +
            " INTEGER NOT NULL DEFAULT 0," +
            USAGE_OFF_PEAK_CACHE_HIT +
            " INTEGER NOT NULL DEFAULT 0," +
            USAGE_OFF_PEAK_CACHE_MISS +
            " INTEGER NOT NULL DEFAULT 0," +
            USAGE_OFF_PEAK_OUTPUT +
            " INTEGER NOT NULL DEFAULT 0," +
            "PRIMARY KEY(" +
            USAGE_DAY +
            ", " +
            USAGE_ORIGIN +
            ")" +
            ")"
}
