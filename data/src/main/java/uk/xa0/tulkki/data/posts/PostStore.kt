package uk.xa0.tulkki.data.posts

import net.zetetic.database.sqlcipher.SQLiteDatabase
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Post

/**
 * `posts/`'s capability, the posts half: the owner's Atom feed entries and their per-conversation
 * bookkeeping.
 *
 * <p>**One of the next capabilities out of `DatabaseBackend`'s grab-bag, and the posts half of the
 * `stories and posts` group.** Four methods: the upsert, the feed read, the single delete and the
 * clear. One table (`posts`, no Room entity), `schema/RawTables` owns its `CREATE`, and its callers
 * are `:xmpp`'s `XmppConnectionService` and `:ui`'s `PostsActivity`, `CallsActivity`,
 * `StoriesActivity` and `PostsAdapter`. `docs/MIGRATION.md`'s `port-45` row is the inventory this
 * comes out of.
 *
 * <p>**The home is an `object` that takes the `SQLiteDatabase` rather than a store that owns it.**
 * `DatabaseBackend` is the one owner of the file (`HistoryDatabase`'s Room connection, handed in by
 * `getInstance`), so a package that opened its own would be the second owner the design forbids;
 * the seam is the same one `schema/RawTables.applySchema(exec)` uses. There is no `Context` here and
 * no `get()` to add.
 *
 * <p>**The table and column names are `Post`'s companion `const val`s, not a second spelling**, and
 * the rows are the model's own `getContentValues(account)` / `fromCursor(cursor)`, called exactly as
 * the Java called them.
 *
 * <p>**The Java-visible surface is unchanged: `@JvmStatic` on all four.** `DatabaseBackend`'s four
 * methods are now one-line delegations (the "one delegating step"), so the `:ui` and `:xmpp` call
 * sites and the `PostRef`/`AccountRef` signatures they compile against do not move. A `@JvmStatic`
 * member of an `object` is the only shape whose static bridge carries the un-mangled name - a Kotlin
 * `internal` member would be emitted as `create$data` and Java could not see it (the
 * `ScriptReading`/`TranslationLanguages` precedent).
 *
 * <p>**The null contracts are read off the callers and off the Java's own body.** The post and its
 * account are the caller's non-null locals (the Java's one cast takes a `PostRef`/`AccountRef` the
 * caller has already checked); `uuid` is the caller's non-null id; the return is the real, mutable
 * `ArrayList` the Java handed back. `getContentValues` and `fromCursor` are the model's, whose own
 * docs record the nullable columns; nothing here narrows them.
 *
 * <p>**No `@Throws`: nothing on this path throws a checked exception the Java catches.** The four
 * original declarations carried none, and `PostsActivity` catches around its own calls.
 */
object PostStore {

    /**
     * One feed entry, saved: `posts`'s own `UNIQUE(id)` resolves the conflict, so a re-delivered or
     * edited entry replaces its row rather than duplicating it.
     */
    @JvmStatic
    fun create(db: SQLiteDatabase, post: Post, account: Account) {
        db.insertWithOnConflict(
            Post.TABLENAME,
            null,
            post.getContentValues(account),
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** Every feed entry, most recently published first: the posts screen's own read. */
    @JvmStatic
    fun all(db: SQLiteDatabase): List<Post> {
        val list = ArrayList<Post>()
        db.query(Post.TABLENAME, null, null, null, null, null, Post.PUBLISHED + " DESC")
            .use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(Post.fromCursor(cursor))
                }
            }
        return list
    }

    /** One entry, gone: the retraction's write. */
    @JvmStatic
    fun delete(db: SQLiteDatabase, uuid: String) {
        db.delete(Post.TABLENAME, Post.UUID + "=?", arrayOf(uuid))
    }

    /** The whole feed, gone: the owner's "clear posts". */
    @JvmStatic
    fun clear(db: SQLiteDatabase) {
        db.delete(Post.TABLENAME, null, null)
    }
}
