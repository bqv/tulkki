package uk.xa0.tulkki.data.discovery

import android.util.Log
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONException
import uk.xa0.tulkki.data.model.ServiceDiscoveryResult
import uk.xa0.tulkki.data.roster.RosterQueries
import uk.xa0.tulkki.data.schema.RawTables
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.utils.Resolver

/**
 * `discovery/`'s capability: the service-discovery cache and the resolver's SRV results.
 *
 * <p>**The next capability out of `DatabaseBackend`'s grab-bag.** Five methods over two tables: the
 * disco cache's write and its `(hash, ver)` read, and the resolver's domain write and read. Both
 * tables are schema 77's (`RawTables` creates them), no entity owns either, and the only callers
 * are `:xmpp`'s `XmppConnection` and `XmppConnectionService`. `docs/MIGRATION.md`'s `port-45` row is
 * the inventory this comes out of.
 *
 * <p>**The home is an `object` that takes the `SQLiteDatabase` rather than a store that owns it.**
 * `DatabaseBackend` is the one owner of the file (`HistoryDatabase`'s Room connection, handed in by
 * `getInstance`), so a package that opened its own would be the second owner the design forbids;
 * the seam is the same one `schema/RawTables.applySchema(exec)` uses. There is no `Context` here and
 * no `get()` to add.
 *
 * <p>**The names are the owners', not a second spelling.** The disco read executes
 * `RosterQueries.DISCOVERY_BY_KEY`, the statement `roster/` already publishes beside
 * `discovery_results`; the table name the resolver's two statements need is
 * `RawTables.RESOLVER_RESULTS_TABLENAME`, widened from `private` to `internal` in this commit
 * because the store is now its second reader inside the same module - the schema object is where
 * the `CREATE` lives, so the name stays there.
 *
 * <p>**The Java-visible surface is unchanged: `@JvmStatic` on all four.** `DatabaseBackend`'s five
 * methods are now one-line delegations (the "one delegating step"), so `XmppConnection`'s eight call
 * sites and `XmppConnectionService`'s two compile against byte for byte what they did before. A
 * `@JvmStatic` member of an `object` is the only shape whose static bridge carries the un-mangled
 * name - a Kotlin `internal` member would be emitted as `save$data` and Java could not see it (the
 * `ScriptReading`/`TranslationLanguages` precedent).
 *
 * <p>**The null contracts are read off the callers and off the Java's own guards.** `save` takes the
 * caller's own result, never null; `find` answers `null` for a miss and for a cached row whose JSON
 * will not parse, exactly as the Java's `JSONException` arm did. The resolver's two take the
 * caller's own non-null domain and result, and `findResolver` answers `null` for a miss and for a
 * row `fromCursor` refuses, exactly as the Java's catch did.
 *
 * <p>**No `@Throws`: nothing on this path throws a checked exception the Java catches.** The four
 * original declarations carried none, and the JSON failure is caught where the Java caught it.
 */
object DiscoveryStore {

    /**
     * The cached capabilities of one server, saved. The model writes its own row, as the Java's body
     * did - there is no inline SQL here to fold into a published statement.
     */
    @JvmStatic
    fun save(db: SQLiteDatabase, result: ServiceDiscoveryResult) {
        db.insert(ServiceDiscoveryResult.TABLENAME, null, result.getContentValues())
    }

    /**
     * One cached capabilities result by the pair the cache is keyed on, or `null` when the table has
     * none or the stored JSON will not parse.
     *
     * @param hash the capabilities hash `presence.getHash()` supplies
     * @param ver the capabilities version `presence.getVer()` supplies
     */
    @JvmStatic
    fun find(db: SQLiteDatabase, hash: String, ver: String): ServiceDiscoveryResult? {
        db.rawQuery(RosterQueries.DISCOVERY_BY_KEY, arrayOf(hash, ver)).use { cursor ->
            if (cursor.count == 0) {
                return null
            }
            cursor.moveToFirst()
            return try {
                ServiceDiscoveryResult(cursor)
            } catch (e: JSONException) {
                /* result is still null */
                null
            }
        }
    }

    /**
     * One resolver result, keyed by the domain it was looked up for: the DNS cache write.
     *
     * @param domain the domain the caller resolved
     * @param result the `Resolver.Result` the caller's lookup produced
     */
    @JvmStatic
    fun saveResolver(db: SQLiteDatabase, domain: String, result: Resolver.Result) {
        val contentValues = result.toContentValues()
        contentValues.put(Resolver.Result.DOMAIN, domain)
        db.insert(RawTables.RESOLVER_RESULTS_TABLENAME, null, contentValues)
    }

    /**
     * The cached resolver result for one domain, or `null` when the table has none or the row will
     * not build - the Java logged that failure and answered `null`, and both are kept.
     *
     * <p>The Java's `synchronized` was on the whole method; the delegation in `DatabaseBackend`
     * keeps the modifier, so the lock still covers the read as it did.
     */
    @JvmStatic
    fun findResolver(db: SQLiteDatabase, domain: String): Resolver.Result? {
        val where = Resolver.Result.DOMAIN + "=?"
        val whereArgs = arrayOf(domain)
        db.query(RawTables.RESOLVER_RESULTS_TABLENAME, null, where, whereArgs, null, null, null)
            .use { cursor ->
                try {
                    if (cursor.moveToFirst()) {
                        return Resolver.Result.fromCursor(cursor)
                    }
                } catch (e: Exception) {
                    Log.d(
                        Config.LOGTAG,
                        "unable to find cached resolver result in database " + e.message,
                    )
                    return null
                }
            }
        return null
    }
}
