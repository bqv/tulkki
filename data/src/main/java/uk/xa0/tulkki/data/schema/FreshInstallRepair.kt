package uk.xa0.tulkki.data.schema

import uk.xa0.tulkki.data.messages.MessageIndexStore

/**
 * `schema/`'s capability: the repair a file Room created without the fresh-install schema needs.
 *
 * <p>**The bug this exists for is recorded in `HistoryDatabase.HistoryCallbacks`:** Room's callback
 * had no `onCreate`, so `installFreshSchema` never ran on a brand-new file, and a file that exists is
 * never created again - `adb install -r` kept the hole. The probe is one of [RawTables]' own tables,
 * `muted_participants`, because no entity declares it and the same call creates every table in the
 * list, so a file that has this one has all of them.
 *
 * <p>**The repair is [RawTables.applySchema] plus the index rebuild, and nothing else.** The
 * `IF NOT EXISTS` schema is the same statement list a fresh install runs, so it is a no-op on a
 * healthy file; the rebuild follows because `RawTables` creates `messages_index` empty, and an empty
 * index is not a fragmented one. `Schema76`/`Schema80` are deliberately not run - they rebuild entity
 * tables and refuse while foreign keys are on, which is the state of an open file, and every table
 * and column they touch is a Room entity `createAllTables` already made.
 *
 * <p>**The home is `schema/`, over the [SchemaExec] seam the JVM harness implements.** The two
 * methods already took that seam rather than the `SQLiteDatabase`, which is what makes this a plain
 * move: no `Context`, no opener, no second owner of the file. `docs/MIGRATION.md`'s `port-45` row is
 * the inventory this comes out of.
 *
 * <p>**The Java-visible surface is `DatabaseBackend`'s two package-private statics, unchanged:
 * `@JvmStatic` on both.** `DatabaseBackend.needsFreshInstallRepair`/`repairMissingFreshInstallSchema`
 * are one-line delegations (the "one delegating step"), so `getInstance`'s own body and
 * `FreshInstallRepairTest`'s four cells compile against byte for byte what they did before. A
 * `@JvmStatic` member of an `object` is the only shape whose static bridge carries the un-mangled
 * name - a Kotlin `internal` member would be emitted as `needsRepair$data` and Java could not see it
 * (the `ScriptReading`/`TranslationLanguages` precedent).
 *
 * <p>**No `@Throws`: nothing on this path throws a checked exception the Java catches.** Neither
 * original declared one.
 */
object FreshInstallRepair {

    /** Whether the file still needs the fresh-install schema: the one `sqlite_master` probe. */
    @JvmStatic
    fun needsRepair(exec: SchemaExec): Boolean = !exec.hasTable(RawTables.MUTED_TABLE)

    /**
     * Run the fresh-install schema and rebuild the index, on a file the probe says predates the fix.
     *
     * <p>The probe is re-read first, exactly as the Java did, so a healthy file pays one lookup and
     * nothing else.
     */
    @JvmStatic
    fun repair(exec: SchemaExec) {
        if (!needsRepair(exec)) {
            return
        }
        RawTables.applySchema(exec)
        MessageIndexStore.rebuildMessagesIndex(exec)
    }
}
