package uk.xa0.tulkki.data.upload

/**
 * `upload/`'s DDL and every statement the package publishes (S5-3, the `upload/` capability).
 *
 * <p>Two tables and the file-state columns of `messages`. The DDL was `DatabaseBackend`'s own string
 * literals until this commit; `docs/MIGRATION.md`, "Order, each step green" `S5-3` gives each
 * capability package its DDL, and `DatabaseBackend.ensureMessageFileDeletedColumn` now executes
 * [CREATE_CIDS], [CREATE_BLOCKED_MEDIA] and [ADD_CIDS_URL] from here rather than spelling them
 * itself. There is no schema version or migration in this commit and there should not be one: the
 * three statements are byte for byte the ones the owner's file was already built from, so moving the
 * text changes nothing on disk.
 *
 * <p>**The `url` column is an `ALTER`, so it is not in [STATEMENTS].** SQLite has no
 * `ADD COLUMN IF NOT EXISTS`; the legacy chain guards it with a `PRAGMA` read, and the constant is
 * separate so a re-runnable list cannot accidentally include it. That is `Schema76`'s shape for
 * `messages.delivery`, applied here.
 *
 * <p>**`file_deleted` has no reader today, and that is measured rather than assumed.** A search of
 * `:data`'s and `:app`'s sources finds one writer (`DatabaseBackend.expireOldMessages`, which sets
 * the marker while it clears the path) and no query that reads the column, so
 * `message_file_deleted_index` covers a column nothing selects by. [EXPIRED_FILES] is the read that
 * marker exists for, published here so the sweep has a home when S5-6 wires it up; it is not a
 * retrieval of an existing consumer, and it is not offered as one.
 *
 * <p>The DAOs spell their SQL themselves; these constants are what `UploadDaoTest` compares the
 * annotations against, so a drift is a red test (`BlockingDao`'s comment carries the rule).
 */
internal object UploadQueries {

    // -- cids: the local-file map, content hash to path ------------------------------------------

    const val CIDS_TABLE = "cids"
    const val CID = "cid"
    const val PATH = "path"
    const val URL = "url"

    // -- blocked_media: the content hashes that must never be rendered ---------------------------

    const val BLOCKED_MEDIA_TABLE = "blocked_media"

    // -- the `messages` columns this package's sweep reads and writes ----------------------------

    const val MESSAGES_TABLE = "messages"
    const val MESSAGE_UUID = "uuid"
    const val MESSAGE_RELATIVE_FILE_PATH = "relativeFilePath"
    const val MESSAGE_FILE_DELETED = "file_deleted"
    const val MESSAGE_DELETED = "deleted"
    const val MESSAGE_TYPE = "type"

    /** The file-bearing message types: 1 image, 2 file, 5 video (`Message.FILE_TYPE_*`). */
    const val FILE_TYPES = "1, 2, 5"

    // -- the DDL, spelled once, executed by the legacy chain and by the test ---------------------

    const val CREATE_CIDS =
        "CREATE TABLE IF NOT EXISTS cids (" +
            "cid TEXT NOT NULL PRIMARY KEY," +
            "path TEXT NOT NULL" +
            ")"

    const val CREATE_BLOCKED_MEDIA =
        "CREATE TABLE IF NOT EXISTS blocked_media (" +
            "cid TEXT NOT NULL PRIMARY KEY" +
            ")"

    /** The one statement that cannot be `IF NOT EXISTS`; the legacy chain guards it with a `PRAGMA`. */
    const val ADD_CIDS_URL = "ALTER TABLE cids ADD COLUMN url TEXT"

    /** Every re-runnable statement the two tables need, in the order the owner's file was built in. */
    @JvmField
    val STATEMENTS: List<String> = listOf(CREATE_CIDS, CREATE_BLOCKED_MEDIA)

    // -- the statements the DAO publishes ---------------------------------------------------------

    const val PATH_FOR_CID = "SELECT path FROM cids WHERE cid = :cid"

    const val URL_FOR_CID = "SELECT url FROM cids WHERE cid = :cid"

    const val CID_FOR_PATH = "SELECT cid FROM cids WHERE path = :path"

    const val SAVE_CID = "INSERT OR REPLACE INTO cids (cid, path, url) VALUES (:cid, :path, :url)"

    const val BLOCKED_COUNT = "SELECT COUNT(*) FROM blocked_media WHERE cid = :cid"

    const val BLOCK_MEDIA = "INSERT OR REPLACE INTO blocked_media (cid) VALUES (:cid)"

    const val CLEAR_BLOCKED_MEDIA = "DELETE FROM blocked_media"

    /**
     * `DatabaseBackend.getFilePathInfo`'s selection, reduced to the one thing a sweep needs: which
     * rows still carry a path. The type list is the file-bearing three, the same restriction the
     * method has always applied.
     */
    const val FILE_PATHS_TO_CHECK =
        "SELECT uuid FROM messages WHERE type IN (" +
            FILE_TYPES +
            ") AND relativeFilePath IS NOT NULL"

    /** `DatabaseBackend.setFilePathDeleted`'s write, one row. */
    const val SET_FILE_PATH_DELETED = "UPDATE messages SET deleted = :deleted WHERE uuid = :uuid"

    /** The read the `file_deleted` marker exists for; see the class comment. */
    const val EXPIRED_FILES = "SELECT uuid FROM messages WHERE file_deleted = 1"
}
