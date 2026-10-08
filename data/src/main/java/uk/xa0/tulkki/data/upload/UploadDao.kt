package uk.xa0.tulkki.data.upload

import androidx.room.Dao
import androidx.room.Query

/**
 * The local-file map, the blocked-media set, and the file-state columns of `messages`
 * (S5-3, the `upload/` capability).
 *
 * <p>**The SQL is spelled here and nowhere else, and the test reads it back off these annotations.**
 * `UploadDaoTest` asserts by reflection that each annotation equals its [UploadQueries] constant and
 * then executes the annotation's own SQL over the JDBC fixture, so a drift between the two is a red
 * test rather than a surprise (`BlockingDao`'s comment carries the rule in full).
 *
 * <p>Every write is `INSERT OR REPLACE` rather than `@Insert`, for the same reason
 * `DatabaseBackend.saveCid` always was one: a CID's path or URL is replaced when the file moves, and
 * a re-block of the same CID is a no-op that the owner may well cause.
 *
 * <p>`internal`, like every DAO: the module's public surface is the read models
 * (`docs/MIGRATION.md`, "Design: the data layer" §2.5).
 */
@Dao
internal interface UploadDao {

    /** The file this content hash names, or `null` when the map has no entry for it. */
    @Query("SELECT path FROM cids WHERE cid = :cid")
    fun pathFor(cid: String): String?

    /** The remote address the file came from, which is `NULL` for anything not downloaded. */
    @Query("SELECT url FROM cids WHERE cid = :cid")
    fun urlFor(cid: String): String?

    /** The reverse lookup: which content hash a stored path holds, or `null` if none does. */
    @Query("SELECT cid FROM cids WHERE path = :path")
    fun cidFor(path: String): String?

    /** The rowid of the written row (`-1` never occurs here; `REPLACE` always writes one). */
    @Query("INSERT OR REPLACE INTO cids (cid, path, url) VALUES (:cid, :path, :url)")
    fun saveCid(cid: String, path: String, url: String?): Long

    /** 1 when the content hash is blocked, 0 otherwise - the existence question `isBlockedMedia` asks. */
    @Query("SELECT COUNT(*) FROM blocked_media WHERE cid = :cid")
    fun blockedCount(cid: String): Int

    /** The rowid of the written row; blocking twice is a no-op, exactly as `blockMedia` was. */
    @Query("INSERT OR REPLACE INTO blocked_media (cid) VALUES (:cid)")
    fun blockMedia(cid: String): Long

    /** `clearBlockedMedia`: the whole set goes, so the count returned is how many were blocked. */
    @Query("DELETE FROM blocked_media")
    fun clearBlockedMedia(): Int

    /**
     * The rows whose file state the sweep decides: everything still carrying a path, restricted to
     * the file-bearing types. It is `DatabaseBackend.getFilePathInfo`'s selection, reduced to the
     * key.
     */
    @Query("SELECT uuid FROM messages WHERE type IN (1, 2, 5) AND relativeFilePath IS NOT NULL")
    fun filePathsToCheck(): List<String>

    /** `DatabaseBackend.setFilePathDeleted`'s write: one row's `deleted` flag. */
    @Query("UPDATE messages SET deleted = :deleted WHERE uuid = :uuid")
    fun setFilePathDeleted(uuid: String, deleted: Long): Int

    /**
     * The rows `expireOldMessages` has marked whose file it took away. Nothing read the marker
     * before this; [UploadQueries]' comment says so in as many words rather than implying a caller.
     */
    @Query("SELECT uuid FROM messages WHERE file_deleted = 1")
    fun expiredFiles(): List<String>
}
