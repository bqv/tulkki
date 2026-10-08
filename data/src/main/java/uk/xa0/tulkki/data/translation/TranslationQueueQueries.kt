package uk.xa0.tulkki.data.translation

import uk.xa0.tulkki.data.TranslationTables

/**
 * `translation/`'s queue statements (S5-6): the queue's own rows, spelled once.
 *
 * <p>The state is a **parameter** and never a literal here. Which number means "pending" is
 * `TranslationQueue.Item`'s fact and `:data` may not import `:translation`, so the caller passes it -
 * which is better than a second spelling of the three numbers, and the reason the earlier
 * `TranslationTables.QUEUE_STATE_*` constants were removed rather than kept in step by a test.
 *
 * <p>`TranslationStore` used to compose every one of these itself, out of `TranslationTables`'
 * column names, holding the writable handle to do it. The statements move here with the store that
 * reads them (`docs/MIGRATION.md`, "Design: the data layer" §6 step 6, the four direct
 * `getWritableDatabase()` consumers), and `TranslationQueueStore` is the only caller.
 */
internal object TranslationQueueQueries {

    const val TABLE = TranslationTables.QUEUE_TABLE

    const val MESSAGE_UUID = TranslationTables.QUEUE_MESSAGE_UUID
    const val CONVERSATION_UUID = TranslationTables.QUEUE_CONVERSATION_UUID
    const val BODY = TranslationTables.QUEUE_BODY
    const val TARGET_LANGUAGE = TranslationTables.QUEUE_TARGET_LANGUAGE
    const val CACHE_KEY = TranslationTables.QUEUE_CACHE_KEY
    const val STATE = TranslationTables.QUEUE_STATE
    const val ATTEMPTS = TranslationTables.QUEUE_ATTEMPTS
    const val NEXT_ATTEMPT_AT = TranslationTables.QUEUE_NEXT_ATTEMPT_AT
    const val LAST_ERROR = TranslationTables.QUEUE_LAST_ERROR
    const val CREATED_AT = TranslationTables.QUEUE_CREATED_AT
    const val FAILED_AT = TranslationTables.QUEUE_FAILED_AT
    const val FAILURE_CAUSE = TranslationTables.QUEUE_FAILURE_CAUSE

    /** The order the due reads share, so `nextDue` and `due` cannot drift apart. */
    private const val DUE_ORDER = " ORDER BY " + NEXT_ATTEMPT_AT + " ASC, " + CREATED_AT + " ASC"

    /** The one row the pump would take next, or none. */
    @JvmField
    val NEXT_DUE: String =
        "SELECT * FROM " +
            TABLE +
            " WHERE " +
            STATE +
            " = ? AND " +
            NEXT_ATTEMPT_AT +
            " <= ?" +
            DUE_ORDER +
            " LIMIT 1"

    /** The due snapshot a batched pass works from: at most `limit` rows, the same order. */
    @JvmStatic
    fun due(limit: Int): String =
        "SELECT * FROM " +
            TABLE +
            " WHERE " +
            STATE +
            " = ? AND " +
            NEXT_ATTEMPT_AT +
            " <= ?" +
            DUE_ORDER +
            " LIMIT " +
            limit

    /** How many rows are in one state. */
    @JvmField
    val COUNT_IN_STATE: String = "SELECT COUNT(*) FROM " + TABLE + " WHERE " + STATE + " = ?"

    /** The earliest instant one state is due, or `NULL` when it holds no row. */
    @JvmField
    val EARLIEST_IN_STATE: String =
        "SELECT MIN(" + NEXT_ATTEMPT_AT + ") FROM " + TABLE + " WHERE " + STATE + " = ?"

    /**
     * Every row of one state goes: the work the queue owed when the interpreter was switched off.
     * The pending state is the only one that carries a target language the pump still classifies
     * against, so it is exactly the state whose lifetime ended with the interpreter.
     */
    @JvmField
    val DELETE_IN_STATE: String = "DELETE FROM " + TABLE + " WHERE " + STATE + " = ?"

    /**
     * One statement, and only for a row that is not already done. A retryable failure waiting out
     * its backoff is included on purpose: the owner asked for this one now.
     *
     * <p>The target language and the cache key are rewritten with the revival, which is what makes an
     * explicit re-request use the language in force now rather than the one the row captured when it
     * was first queued. The insert's `IGNORE` is deliberately untouched: this is the owner's tap, and
     * the automatic pass must still leave an existing row alone.
     */
    @JvmField
    val MAKE_DUE: String =
        "UPDATE " +
            TABLE +
            " SET " +
            STATE +
            " = ?, " +
            ATTEMPTS +
            " = 0, " +
            NEXT_ATTEMPT_AT +
            " = ?, " +
            LAST_ERROR +
            " = NULL, " +
            FAILURE_CAUSE +
            " = NULL, " +
            TARGET_LANGUAGE +
            " = ?, " +
            CACHE_KEY +
            " = ? WHERE " +
            MESSAGE_UUID +
            " = ? AND " +
            STATE +
            " <> ?"

    /** One message's row goes, whatever state it is in: the message's text is no longer that text. */
    @JvmField
    val DELETE_BY_MESSAGE: String =
        "DELETE FROM " + TABLE + " WHERE " + MESSAGE_UUID + " = ?"

    /**
     * Whether a translation is queued for this message, whatever state it is in.
     */
    @JvmField
    val HAS_QUEUED: String =
        "SELECT 1 FROM " + TABLE + " WHERE " + MESSAGE_UUID + " = ? LIMIT 1"

    /**
     * The read model's three numbers (schema 78): how many rows are in each of the two states the
     * caller names, and when the earliest of the first is due.
     *
     * <p>The aliases are the POJO's field names, so Room's own mapping needs no second spelling. This
     * is the one queue statement that is not executed by [TranslationQueueStore]: its caller is
     * [TranslationQueueDao], which spells the same text in its `@Query` annotation so a reader can see
     * what Room runs - `TranslationQueueSummaryTest` asserts the two are equal and executes this one.
     *
     * <p>The named parameters are Room's (`:pendingState`, `:failedState`); the JVM test rewrites
     * them to positional binds, exactly as it does for the queue's other statements.
     */
    const val SUMMARY =
        "SELECT (SELECT COUNT(*) FROM translation_queue WHERE state = :pendingState) AS pending, " +
            "(SELECT COUNT(*) FROM translation_queue WHERE state = :failedState) AS failed, " +
            "(SELECT MIN(next_attempt_at) FROM translation_queue WHERE state = :pendingState) " +
            "AS nextAttemptAt"

    /**
     * The queue's columns, in the file's own order. `failed_at` is the schema-73 column a file may
     * not have; `TranslationQueueStore` writes it only when the probe says it is really there.
     */
    @JvmField
    val COLUMNS: List<String> =
        listOf(
            MESSAGE_UUID,
            CONVERSATION_UUID,
            BODY,
            TARGET_LANGUAGE,
            CACHE_KEY,
            STATE,
            ATTEMPTS,
            NEXT_ATTEMPT_AT,
            LAST_ERROR,
            CREATED_AT,
            FAILED_AT,
            FAILURE_CAUSE,
        )
}
