package uk.xa0.tulkki.data.filepaths

import android.content.ContentValues
import java.io.File
import java.util.ArrayList
import java.util.Calendar
import java.util.HashMap
import java.util.TimeZone
import net.zetetic.database.sqlcipher.SQLiteDatabase
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.libs.FilePathInfoRef

/**
 * `filepaths/`'s capability: the file sweep's reads and writes over `messages.relativeFilePath`.
 *
 * <p>**The next capability out of `DatabaseBackend`'s grab-bag**, and the group whose nested
 * `FilePath`/`FilePathInfo` pair had to be un-nested first - the pair moved to this package in the
 * same commit, because a type written `Outer.Inner` cannot leave its outer class while an outside
 * file spells it that way and `:data`'s own `FileBackend.convertToAttachments` did
 * (`docs/MIGRATION.md`'s `port-45` row is the inventory this comes out of). The store, the two value
 * types and the queries all live here; `DatabaseBackend` is reduced to one-line delegations and the
 * two ref-typed adapters that only cast.
 *
 * <p>**The home is an `object` that takes the `SQLiteDatabase`** rather than a store that owns it.
 * `DatabaseBackend` is the one owner of the file (`HistoryDatabase`'s Room connection, handed in by
 * `getInstance`); a package that opened its own would be the second owner the design forbids. The
 * seam is the same one `schema/RawTables.applySchema(exec)` uses.
 *
 * <p>**The Java-visible surface is unchanged: `@JvmStatic` on every method.** `DatabaseBackend`'s
 * live methods are one-line delegations (the "one delegating step"), so `XmppConnectionService`,
 * `MessageExpiry`, `ConversationCalendarActivity` and `DataStaticsHost` compile against what they
 * did before. A `@JvmStatic` member of an `object` is the only shape whose static bridge carries
 * the un-mangled name - a Kotlin `internal` member would be emitted as `mark$data` and Java could
 * not see it (the `ScriptReading`/`TranslationLanguages` precedent).
 *
 * <p>**The null contracts are read off the callers and off the Java's own body.** `getFilePathInfo`,
 * `getRelativeFilePaths`, `getRelativeFilePathsForConversationForMonth` and the three exclusive-path
 * reads all answer a real collection; `markFileAsDeleted` answers the uuids it wrote; only
 * `getExclusiveFilePath` is nullable, because `XmppConnectionService:5960` tests it for null. The
 * three arguments `getRelativeFilePaths` branches on - `account`, `jid`, `query` - are the nullable
 * `DataStaticsHost.loadAttachments` parameters.
 *
 * <p>**No `@Throws`: nothing on this path throws a checked exception the Java catches.**
 */
object FilePathStore {

    /**
     * The uuids of every file-bearing message that names `file`, marked deleted. The `.pgp` case
     * also matches its base name and the `encryption in(1,4)` pair, exactly as the Java spelled it.
     */
    @JvmStatic
    fun markFileAsDeleted(
        db: SQLiteDatabase,
        file: File,
        internalFile: Boolean,
    ): List<String> {
        val selection: String
        val selectionArgs: Array<String>
        if (internalFile) {
            val name = file.name
            if (name.endsWith(".pgp")) {
                selection = FilePathQueries.SELECT_PGP
                selectionArgs =
                    arrayOf(file.absolutePath, name, name.substring(0, name.length - 4))
            } else {
                selection = FilePathQueries.SELECT_INTERNAL
                selectionArgs = arrayOf(file.absolutePath, name)
            }
        } else {
            selection = FilePathQueries.SELECT_EXTERNAL
            selectionArgs = arrayOf(file.absolutePath)
        }
        val uuids = ArrayList<String>()
        val cursor =
            db.query(
                Message.TABLENAME,
                arrayOf(Message.UUID),
                selection,
                selectionArgs,
                null,
                null,
                null,
            )
        while (cursor != null && cursor.moveToNext()) {
            uuids.add(cursor.getString(0))
        }
        if (cursor != null) {
            cursor.close()
        }
        markFileAsDeleted(db, uuids)
        return uuids
    }

    /** The uuids already chosen, marked deleted in one transaction. */
    @JvmStatic
    fun markFileAsDeleted(db: SQLiteDatabase, uuids: List<String>) {
        val contentValues = ContentValues()
        val where = Message.UUID + "=?"
        contentValues.put(Message.DELETED, 1)
        db.beginTransaction()
        for (uuid in uuids) {
            db.update(Message.TABLENAME, contentValues, where, arrayOf(uuid))
        }
        db.setTransactionSuccessful()
        db.endTransaction()
    }

    /** The sweep's write-back: the deleted flag the probe found, in one transaction. */
    @JvmStatic
    fun markFilesAsChanged(db: SQLiteDatabase, files: List<FilePathInfoRef>) {
        val where = Message.UUID + "=?"
        db.beginTransaction()
        for (info in files) {
            val contentValues = ContentValues()
            contentValues.put(Message.DELETED, if (info.deleted()) 1 else 0)
            db.update(Message.TABLENAME, contentValues, where, arrayOf(info.getUuid()))
        }
        db.setTransactionSuccessful()
        db.endTransaction()
    }

    /** Every file-bearing row that still names a relative path. */
    @JvmStatic
    fun getFilePathInfo(db: SQLiteDatabase): List<FilePathInfo> {
        val cursor =
            db.query(
                Message.TABLENAME,
                arrayOf(Message.UUID, Message.RELATIVE_FILE_PATH, Message.DELETED),
                FilePathQueries.SELECT_FILE_PATH_INFO,
                null,
                null,
                null,
                null,
            )
        val list = ArrayList<FilePathInfo>()
        while (cursor != null && cursor.moveToNext()) {
            list.add(
                FilePathInfo(cursor.getString(0), cursor.getString(1), cursor.getInt(2) > 0),
            )
        }
        if (cursor != null) {
            cursor.close()
        }
        return list
    }

    /** The media browser's paged read, narrowed by account, date or free text when given. */
    @JvmStatic
    fun getRelativeFilePaths(
        db: SQLiteDatabase,
        account: String?,
        jid: Jid?,
        query: String?,
        limit: Int,
    ): List<FilePath> {
        val sqlBuilder = StringBuilder(FilePathQueries.SELECT_RELATIVE_FILE_PATHS)
        val args = ArrayList<String>()

        if (account != null && jid != null) {
            sqlBuilder.append(FilePathQueries.AND_ACCOUNT_JID)
            args.add(account)
            args.add(jid.toString())
            args.add(jid.toString() + "/%")
        }

        if (query != null && query.isNotEmpty()) {
            if (query.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                sqlBuilder.append(FilePathQueries.AND_DATE)
                args.add(query)
            } else {
                sqlBuilder.append(FilePathQueries.AND_QUERY)
                args.add("%$query%")
                args.add("%$query%")
            }
        }

        sqlBuilder.append(FilePathQueries.ORDER_BY_TIME_SENT_DESC)
        if (limit > 0) {
            sqlBuilder.append(" limit ").append(limit)
        }

        val cursor = db.rawQuery(sqlBuilder.toString(), args.toTypedArray())
        val filesPaths = ArrayList<FilePath>()
        while (cursor.moveToNext()) {
            filesPaths.add(
                FilePath(
                    cursor.getString(0),
                    cursor.getString(1),
                    cursor.getLong(2),
                    cursor.getString(3),
                ),
            )
        }
        cursor.close()
        return filesPaths
    }

    /** The calendar's per-day file count for one month, keyed by day of month. */
    @JvmStatic
    fun getRelativeFilePathsForConversationForMonth(
        db: SQLiteDatabase,
        conversationUuid: String,
        year: Int,
        month: Int,
    ): Map<Int, FilePath> {
        val dayToFilePath = HashMap<Int, FilePath>()

        // Calculate the start and end timestamps for the given month
        val calendar = Calendar.getInstance()
        calendar.set(year, month - 1, 1, 0, 0, 0) // Month is 0-indexed in Calendar
        calendar.set(Calendar.MILLISECOND, 0)
        val startTimeMillis = calendar.timeInMillis

        calendar.add(Calendar.MONTH, 1)
        calendar.add(Calendar.MILLISECOND, -1)
        val endTimeMillis = calendar.timeInMillis

        val offset = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000L

        val selectionArgs =
            arrayOf(conversationUuid, startTimeMillis.toString(), endTimeMillis.toString())

        val cursor = db.rawQuery(FilePathQueries.relativeFilePathsForMonth(offset), selectionArgs)

        if (cursor != null) {
            try {
                val dayOfMonthIndex = cursor.getColumnIndex("day_of_month")
                val messageCountIndex = cursor.getColumnIndex("message_count")
                val uuidIndex = cursor.getColumnIndex(Message.UUID)
                val relativePathIndex = cursor.getColumnIndex(Message.RELATIVE_FILE_PATH)

                if (dayOfMonthIndex != -1 && messageCountIndex != -1) {
                    while (cursor.moveToNext()) {
                        val day = cursor.getInt(dayOfMonthIndex)
                        val uuid = cursor.getString(uuidIndex)
                        val relativePath = cursor.getString(relativePathIndex)
                        dayToFilePath[day] = FilePath(uuid, relativePath, 0L, conversationUuid)
                    }
                }
            } finally {
                cursor.close()
            }
        }
        return dayToFilePath
    }

    /** Paths this conversation has and no other conversation's rows share. */
    @JvmStatic
    fun getExclusiveFilePaths(db: SQLiteDatabase, conversation: Conversation): List<String> {
        val paths = ArrayList<String>()
        db.rawQuery(
                FilePathQueries.EXCLUSIVE_FILE_PATHS,
                arrayOf(conversation.getUuid(), conversation.getUuid()),
            )
            .use { cursor ->
                while (cursor.moveToNext()) {
                    val relativePath = cursor.getString(0)
                    if (relativePath != null) {
                        paths.add(relativePath)
                    }
                }
            }
        return paths
    }

    /** The path this message alone names, or `null` when another row still names it. */
    @JvmStatic
    fun getExclusiveFilePath(db: SQLiteDatabase, message: Message): String? {
        val relativePath = message.getRelativeFilePath() ?: return null
        db.rawQuery(
                FilePathQueries.EXCLUSIVE_FILE_PATH,
                arrayOf(relativePath, message.getUuid()),
            )
            .use { cursor ->
                if (cursor.count == 0) {
                    return relativePath
                }
            }
        return null
    }

    /** The paths whose every row is past the expiry rule, for the background sweep to delete. */
    @JvmStatic
    fun getExclusiveFilePathsExpiring(db: SQLiteDatabase, historyTimestamp: Long): List<String> {
        val paths = ArrayList<String>()
        val now = System.currentTimeMillis()
        db.rawQuery(
                FilePathQueries.EXCLUSIVE_FILE_PATHS_EXPIRING,
                arrayOf(
                    now.toString(),
                    historyTimestamp.toString(),
                    historyTimestamp.toString(),
                    now.toString(),
                    historyTimestamp.toString(),
                    historyTimestamp.toString(),
                ),
            )
            .use { cursor ->
                while (cursor.moveToNext()) {
                    paths.add(cursor.getString(0))
                }
            }
        return paths
    }
}
