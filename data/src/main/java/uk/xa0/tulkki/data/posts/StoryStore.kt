package uk.xa0.tulkki.data.posts

import android.util.Log
import net.zetetic.database.sqlcipher.SQLiteDatabase
import uk.xa0.tulkki.data.model.Story
import uk.xa0.tulkki.xmpp.Config

/**
 * `posts/`'s capability, the stories half: the published stories that are younger than a day.
 *
 * <p>**The stories half of the `stories and posts` group, taken in the same commit as
 * [PostStore].** Four methods: the upsert, the last-24-hours read, the single delete and the sweep
 * of everything older than a day. One table (`stories`, no Room entity), `schema/RawTables` owns its
 * `CREATE`, and its only caller is `:xmpp`'s `XmppConnectionService`. `docs/MIGRATION.md`'s
 * `port-45` row is the inventory this comes out of.
 *
 * <p>**The home is an `object` that takes the `SQLiteDatabase` rather than a store that owns it.**
 * `DatabaseBackend` is the one owner of the file (`HistoryDatabase`'s Room connection, handed in by
 * `getInstance`), so a package that opened its own would be the second owner the design forbids;
 * the seam is the same one `schema/RawTables.applySchema(exec)` uses. There is no `Context` here and
 * no `get()` to add.
 *
 * <p>**The table and column names are `Story`'s companion `const val`s, not a second spelling**, and
 * its `UUID` is the one `AbstractEntity` carries, as the model's own doc records. The rows are the
 * model's own `getContentValues()` / `fromCursor(cursor)`, called exactly as the Java called them.
 *
 * <p>**The Java-visible surface is unchanged: `@JvmStatic` on all four.** `DatabaseBackend`'s four
 * methods are now one-line delegations (the "one delegating step"), so `XmppConnectionService`'s
 * call sites and the `StoryRef` signatures they compile against do not move. A `@JvmStatic` member
 * of an `object` is the only shape whose static bridge carries the un-mangled name - a Kotlin
 * `internal` member would be emitted as `upsert$data` and Java could not see it (the
 * `ScriptReading`/`TranslationLanguages` precedent).
 *
 * <p>**The null contracts are read off the caller and off the Java's own guards.** The story is the
 * caller's already-checked ref, cast once in the delegation as the Java cast it; `uuid` is the
 * caller's non-null id; the return is the real, mutable `ArrayList` the Java handed back. A row
 * whose cursor will not build is skipped and logged, exactly as the Java's per-row `try` skipped and
 * logged it, so a bad row still costs only itself.
 *
 * <p>**No `@Throws`: nothing on this path throws a checked exception the Java catches.** The four
 * original declarations carried none, and `XmppConnectionService` catches nothing around them.
 */
object StoryStore {

    /** The window every read and every sweep uses: one day, in milliseconds, as the Java spelled it. */
    private const val ONE_DAY_MILLIS = 86400000L

    /**
     * One story, saved: `stories`'s own `UNIQUE(uuid)` resolves the conflict, so a re-delivered
     * story replaces its row rather than duplicating it.
     */
    @JvmStatic
    fun upsert(db: SQLiteDatabase, story: Story) {
        db.insertWithOnConflict(
            Story.TABLENAME,
            null,
            story.getContentValues(),
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /**
     * Every story published in the last day, most recently published first. A row the model refuses
     * is logged and skipped, as the Java's per-row guard did.
     */
    @JvmStatic
    fun all(db: SQLiteDatabase): List<Story> {
        val list = ArrayList<Story>()
        val twentyFourHoursAgo = System.currentTimeMillis() - ONE_DAY_MILLIS
        db.query(
            Story.TABLENAME,
            null,
            Story.PUBLISHED + " >= ?",
            arrayOf(twentyFourHoursAgo.toString()),
            null,
            null,
            Story.PUBLISHED + " DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                try {
                    list.add(Story.fromCursor(cursor))
                } catch (e: Exception) {
                    Log.w(Config.LOGTAG, "Failed to restore story from database", e)
                }
            }
        }
        return list
    }

    /** One story, gone: the owner's own retraction. */
    @JvmStatic
    fun delete(db: SQLiteDatabase, uuid: String) {
        db.delete(Story.TABLENAME, Story.UUID + "=?", arrayOf(uuid))
    }

    /** Every story older than a day, gone: the sweep the service runs on its own. */
    @JvmStatic
    fun deleteExpired(db: SQLiteDatabase) {
        val twentyFourHoursAgo = System.currentTimeMillis() - ONE_DAY_MILLIS
        db.delete(
            Story.TABLENAME,
            Story.PUBLISHED + " < ?",
            arrayOf(twentyFourHoursAgo.toString()),
        )
    }
}
