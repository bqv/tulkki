package uk.xa0.tulkki.data.filepaths

import uk.xa0.tulkki.data.model.Message

/**
 * `filepaths/`'s statements, spelled once.
 *
 * <p>They are the ones the file sweep and the media queries ran inline inside
 * `DatabaseBackend`. The column and table names are `Message`'s companion `const val`s, not a second
 * spelling. The two that a bound (`:keep`-style) or an offset makes dynamic stay functions or are
 * built by the store; the rest are `const val`s so a test can read the statement the app runs.
 */
internal object FilePathQueries {

    /** The `.pgp` half of `markFileAsDeleted`'s internal-file selection, encryption numbers and all. */
    const val SELECT_PGP =
        "(" + Message.RELATIVE_FILE_PATH + " IN(?,?) OR (" + Message.RELATIVE_FILE_PATH +
            "=? and encryption in(1,4))) and type in (1,2,5)"

    const val SELECT_INTERNAL = Message.RELATIVE_FILE_PATH + " IN(?,?) and type in (1,2,5)"

    const val SELECT_EXTERNAL = Message.RELATIVE_FILE_PATH + "=? and type in (1,2,5)"

    /** `getFilePathInfo`'s selection: every file-bearing row that still names a relative path. */
    const val SELECT_FILE_PATH_INFO =
        "type in (1,2,5) and " + Message.RELATIVE_FILE_PATH + " is not null"

    /** `getRelativeFilePaths`' prefix; the account, date and query clauses are appended. */
    const val SELECT_RELATIVE_FILE_PATHS =
        "select uuid,relativeFilePath,timeSent,conversationUuid from messages where type in (1,2,5)" +
            " and deleted=0 and " + Message.RELATIVE_FILE_PATH + " is not null"

    const val AND_ACCOUNT_JID =
        " and conversationUuid=(select uuid from conversations where accountUuid=? and" +
            " (contactJid=? or contactJid like ?))"

    const val AND_DATE =
        " and date(" + Message.TIME_SENT + " / 1000, 'unixepoch', 'localtime') = ?"

    const val AND_QUERY = " and (body like ? or " + Message.RELATIVE_FILE_PATH + " like ?)"

    const val ORDER_BY_TIME_SENT_DESC = " order by " + Message.TIME_SENT + " desc"

    /** The month's per-day file count; `offset` is the local time zone's offset in seconds. */
    fun relativeFilePathsForMonth(offset: Long): String =
        "SELECT " +
            Message.UUID +
            ", " +
            Message.RELATIVE_FILE_PATH +
            ", " +
            "CAST(strftime('%d', " +
            Message.TIME_SENT +
            " / 1000 + " +
            offset +
            ", 'unixepoch') AS INTEGER) AS day_of_month, " +
            "COUNT(" +
            Message.UUID +
            ") AS message_count " +
            "FROM " +
            Message.TABLENAME +
            " " +
            "WHERE " +
            Message.CONVERSATION +
            " = ? " +
            "AND " +
            Message.TIME_SENT +
            " >= ? " +
            "AND " +
            Message.TIME_SENT +
            " <= ? " +
            "AND " +
            Message.DELETED +
            " = 0 " +
            "AND " +
            Message.RELATIVE_FILE_PATH +
            " IS NOT NULL " +
            "GROUP BY day_of_month " +
            "ORDER BY day_of_month ASC;"

    /** `getExclusiveFilePaths`: paths this conversation has and no other conversation shares. */
    const val EXCLUSIVE_FILE_PATHS =
        "SELECT DISTINCT m." + Message.RELATIVE_FILE_PATH +
            " FROM " + Message.TABLENAME + " m" +
            " WHERE m." + Message.CONVERSATION + "=?" +
            " AND m." + Message.RELATIVE_FILE_PATH + " IS NOT NULL" +
            " AND NOT EXISTS (" +
            "SELECT 1 FROM " + Message.TABLENAME + " m2" +
            " WHERE m2." + Message.RELATIVE_FILE_PATH + "=m." + Message.RELATIVE_FILE_PATH +
            " AND m2." + Message.CONVERSATION + "!=?" +
            ")"

    /** `getExclusiveFilePath`: whether any other row still names this path. */
    const val EXCLUSIVE_FILE_PATH =
        "SELECT 1 FROM " + Message.TABLENAME +
            " WHERE " + Message.RELATIVE_FILE_PATH + "=?" +
            " AND " + Message.UUID + "!=?" +
            " LIMIT 1"

    /** `getExclusiveFilePathsExpiring`: paths whose only rows are all past the expiry rule. */
    const val EXCLUSIVE_FILE_PATHS_EXPIRING =
        "SELECT DISTINCT m." + Message.RELATIVE_FILE_PATH +
            " FROM " + Message.TABLENAME + " m" +
            " WHERE ((" + Message.EXPIRE_AT + " > 0 AND " + Message.EXPIRE_AT + " < ?)" +
            " OR (" + Message.TIME_SENT + " < ? AND ? > 0))" +
            " AND m." + Message.RELATIVE_FILE_PATH + " IS NOT NULL" +
            " AND NOT EXISTS (" +
            "SELECT 1 FROM " + Message.TABLENAME + " m2" +
            " WHERE m2." + Message.RELATIVE_FILE_PATH + "=m." + Message.RELATIVE_FILE_PATH +
            " AND NOT ((" + Message.EXPIRE_AT + " > 0 AND " + Message.EXPIRE_AT + " < ?)" +
            " OR (" + Message.TIME_SENT + " < ? AND ? > 0))" +
            ")"
}
