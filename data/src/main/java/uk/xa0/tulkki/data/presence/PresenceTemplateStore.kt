package uk.xa0.tulkki.data.presence

import net.zetetic.database.sqlcipher.SQLiteDatabase
import uk.xa0.tulkki.data.model.PresenceTemplate

/**
 * `presence/`'s capability: the owner's saved status lines, at most nine of them, in their own table.
 *
 * <p>**The second capability out of `DatabaseBackend`'s grab-bag, and the next smallest that is
 * genuinely coherent once `pinned/` has gone.** Two methods: the writer - delete the line with the
 * same message, trim the table to its nine newest rows, insert - and the picker's read. One table
 * (`presence_templates`), one caller class (`:xmpp`'s `XmppConnectionService`), and `presence/`
 * already publishes the three statements as `PresenceQueries`. `docs/MIGRATION.md`'s `port-32` row is
 * the inventory this comes out of. It names `message expiry` (2 methods / 30 lines) as the next
 * smallest group, and that one is refused: its writer calls the connection group's private
 * `columnExists`, so it does not need "nothing but the opener" and is not a capability that can move
 * alone.
 *
 * <p>**The home is an `object` that takes the `SQLiteDatabase` rather than a store that owns it.**
 * `DatabaseBackend` is the one owner of the file (`HistoryDatabase`'s Room connection, handed in by
 * `getInstance`), so a package that opened its own would be the second owner the design forbids; the
 * seam is the same one `schema/RawTables.applySchema(exec)` uses. There is no `Context` here and no
 * `get()` to add.
 *
 * <p>**The statements are `PresenceQueries`'s, not a second spelling.** The live Java spelled all
 * three inline beside the published copies; the writer now executes `REMOVE_BY_MESSAGE` and
 * `TRIM_TO_NEWEST`, and the read executes `ALL`. The one number left is the trim's bound - the
 * picker's nine, the seat `TRIM_TO_NEWEST`'s own `:keep` was published for.
 *
 * <p>**The Java-visible surface is unchanged: `@JvmStatic` on both.** `DatabaseBackend`'s two methods
 * are now one-line delegations (the "one delegating step"), so `XmppConnectionService`'s two call
 * sites and the `DatabaseBackendRef` signatures compile against byte for byte what they did before. A
 * `@JvmStatic` member of an `object` is the only shape whose static bridge carries the un-mangled name
 * - a Kotlin `internal` member would be emitted as `insert$data` and Java could not see it (the
 * `ScriptReading`/`TranslationLanguages` precedent).
 *
 * <p>**The null contracts are read off the caller.** `getStatusMessage()` is the row's nullable
 * `message` column (`PresenceTemplate`'s own doc records why it is nullable), and it is both the
 * delete's argument and a `ContentValues` member, so it stays nullable here. The writer's template is
 * the caller's own `new PresenceTemplate(...)` or a cursor row, never null. The read answers a real
 * `ArrayList`, as the Java's did, not an immutable copy.
 *
 * <p>**No `@Throws`: nothing on this path throws a checked exception the Java catches.** The original
 * two declared none and `XmppConnectionService` catches nothing around them.
 */
object PresenceTemplateStore {

    /**
     * The picker's bound: nine lines, the same number the Java's own `limit 9` held, passed as
     * `TRIM_TO_NEWEST`'s `:keep`.
     */
    private const val KEEP_NEWEST = 9

    /**
     * The line, saved: any row with the same message goes first, then everything past the nine newest
     * rows, then the upsert. The pair the table's own `UNIQUE` names is `(message, status)`, but the
     * live writer has always keyed the removal on the message alone, and that is carried faithfully.
     *
     * @param template the line to save, the caller's own object
     */
    @JvmStatic
    fun insert(db: SQLiteDatabase, template: PresenceTemplate) {
        db.execSQL(PresenceQueries.REMOVE_BY_MESSAGE, arrayOf<Any?>(template.getStatusMessage()))
        db.execSQL(PresenceQueries.TRIM_TO_NEWEST, arrayOf<Any>(KEEP_NEWEST))
        db.insert(PresenceQueries.TABLE, null, template.getContentValues())
    }

    /**
     * Every saved line, most recently used first: the picker's own read, as the mutable list the Java
     * handed back (its one caller copies it into a ref list).
     */
    @JvmStatic
    fun all(db: SQLiteDatabase): List<PresenceTemplate> {
        val templates = ArrayList<PresenceTemplate>()
        db.rawQuery(PresenceQueries.ALL, null).use { cursor ->
            while (cursor.moveToNext()) {
                templates.add(PresenceTemplate.fromCursor(cursor))
            }
        }
        return templates
    }
}
