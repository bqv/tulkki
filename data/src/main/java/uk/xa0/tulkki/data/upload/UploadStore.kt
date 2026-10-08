package uk.xa0.tulkki.data.upload

import android.content.ContentValues
import io.ipfs.cid.Cid
import java.io.File
import net.zetetic.database.sqlcipher.SQLiteDatabase
import uk.xa0.tulkki.data.model.DownloadableFile

/**
 * `upload/`'s capability: the local-file map (`cids`, content hash to path and remote url) and the
 * blocked-media set (`blocked_media`).
 *
 * <p>**The third capability out of `DatabaseBackend`'s grab-bag, and the smallest one whose package
 * already publishes its statements.** Seven methods and two tables: the file behind a content hash and
 * its remote address, the write that maps one, and the three blocked-media statements. One caller
 * class (`:xmpp`'s `XmppConnectionService`), and `UploadQueries` already publishes `PATH_FOR_CID`,
 * `URL_FOR_CID`, `SAVE_CID`, `BLOCKED_COUNT`, `BLOCK_MEDIA` and `CLEAR_BLOCKED_MEDIA`.
 * `docs/MIGRATION.md`'s `port-32` row is the inventory this comes out of.
 *
 * <p>**The home is an `object` that takes the `SQLiteDatabase` rather than a store that owns it.**
 * `DatabaseBackend` is the one owner of the file (`HistoryDatabase`'s Room connection, handed in by
 * `getInstance`), so a package that opened its own would be the second owner the design forbids; the
 * seam is the same one `schema/RawTables.applySchema(exec)` uses. There is no `Context` here and no
 * `get()` to add.
 *
 * <p>**The statements are `UploadQueries`'s, with one deliberate exception.** The two reads, the two
 * blocked-media writes and the clear execute the published statements; the column and table names are
 * the published constants. The exception is [saveCid]: the published `SAVE_CID` is a whole-row
 * `INSERT OR REPLACE`, while the live writer updates the row first and inserts only when it matched
 * nothing, because a `null` `url` must not wipe an address already stored. `SAVE_CID` is left for the
 * typed DAO path, which owns the whole row; here the update-else-insert is carried faithfully.
 *
 * <p>**The Java-visible surface is unchanged: `@JvmStatic` on all seven, two of them overloads.**
 * `DatabaseBackend`'s methods are now one-line delegations (the "one delegating step"), so
 * `XmppConnectionService`'s eight call sites and the `DatabaseBackendRef` signatures compile against
 * byte for byte what they did before. A `@JvmStatic` member of an `object` is the only shape whose
 * static bridge carries the un-mangled name (the `ScriptReading`/`TranslationLanguages` precedent).
 *
 * <p>**The null contracts are read off the caller.** `getFileForCid` guards `cid == null` in the Java
 * and answers `null`, so its content hash stays nullable. The other three reads and writes dereference
 * the hash (`cid.toString()`), and every caller in the tree - `XmppConnectionService`'s own wrappers,
 * `FileBackend.kt`'s two- and three-argument `saveCid` calls - passes a real `Cid`, so theirs is
 * non-null. `saveCid`'s `file` is guarded (`if (file != null)`) and its `url` is guarded, so both stay
 * nullable. The reads answer the Java's own `null` when the map has no row.
 *
 * <p>**No `@Throws`: nothing on this path throws a checked exception the Java catches.** The one
 * checker the callers care about, `saveCid`'s blocked-media refusal, is `XmppConnectionService`'s own
 * and stays there.
 */
object UploadStore {

    /**
     * The local file a content hash maps to, or `null` when the map has no entry for it.
     *
     * @param cid the content hash, or `null` - the Java's own early answer is a `null` file
     */
    @JvmStatic
    fun fileForCid(db: SQLiteDatabase, cid: Cid?): DownloadableFile? {
        if (cid == null) return null
        db.rawQuery(UploadQueries.PATH_FOR_CID, arrayOf(cid.toString())).use { cursor ->
            return if (cursor.moveToNext()) DownloadableFile(cursor.getString(0)) else null
        }
    }

    /** The remote address the file came from, which is `null` for anything not downloaded. */
    @JvmStatic
    fun urlForCid(db: SQLiteDatabase, cid: Cid): String? {
        db.rawQuery(UploadQueries.URL_FOR_CID, arrayOf(cid.toString())).use { cursor ->
            return if (cursor.moveToNext()) cursor.getString(0) else null
        }
    }

    /**
     * The content hash mapped to its file, and to the address it came from when there is one: the row
     * is updated in place, and only written fresh when the update matched nothing. The `null` guards
     * are the Java's: a `null` file or url leaves the column alone rather than clearing it.
     */
    @JvmStatic
    fun saveCid(db: SQLiteDatabase, cid: Cid, file: File?, url: String?) {
        val values = ContentValues()
        values.put(UploadQueries.CID, cid.toString())
        if (file != null) values.put(UploadQueries.PATH, file.absolutePath)
        if (url != null) values.put(UploadQueries.URL, url)
        if (db.update(
                UploadQueries.CIDS_TABLE,
                values,
                UploadQueries.CID + "=?",
                arrayOf(cid.toString()),
            ) < 1
        ) {
            db.insertWithOnConflict(
                UploadQueries.CIDS_TABLE,
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }

    /** One content hash blocked; blocking twice replaces its own row and is a no-op. */
    @JvmStatic
    fun blockMedia(db: SQLiteDatabase, cid: Cid) {
        db.execSQL(UploadQueries.BLOCK_MEDIA, arrayOf(cid.toString()))
    }

    /** Whether the content hash is blocked: the existence question the count answers. */
    @JvmStatic
    fun isBlockedMedia(db: SQLiteDatabase, cid: Cid): Boolean {
        db.rawQuery(UploadQueries.BLOCKED_COUNT, arrayOf(cid.toString())).use { cursor ->
            return if (cursor.moveToNext()) cursor.getInt(0) > 0 else false
        }
    }

    /** The whole blocked set goes. */
    @JvmStatic
    fun clearBlockedMedia(db: SQLiteDatabase) {
        db.execSQL(UploadQueries.CLEAR_BLOCKED_MEDIA)
    }
}
