package uk.xa0.tulkki.data

import java.sql.Connection
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.schema.Schema77
import uk.xa0.tulkki.data.schema.Schema78
import uk.xa0.tulkki.data.schema.Schema79
import uk.xa0.tulkki.data.schema.Schema80
import uk.xa0.tulkki.data.schema.TulkkiMigrations
import uk.xa0.tulkki.data.schema.Schema76

/**
 * The "one definition, two callers" half of S5-2.
 *
 * `docs/MIGRATION.md`, "Design: the data layer" §4.2: the Room migration (75 → 76) and the
 * legacy `onUpgrade` hook were both live for exactly one commit, and the design's whole job in
 * that commit was to make them unable to disagree. The legacy hook is gone (S5-5), the fresh-install
 * callback is the second caller now, and the shape of the proof is unchanged. There are two halves
 * to proving it on a host with no SQLCipher (`§4.4`):
 *
 * <ul>
 *   <li>the statements and the seed are one object both callers invoke, and neither is allowed to
 *       spell a sync table's name for itself — {@link #bothCallersRouteThroughTheOneDefinition()};
 *   <li>the callers, executed over their own fixtures, land on the same `sqlite_master` and
 *       the same `PRAGMA table_info` text, as does the fresh-install definition —
 *       {@link #theTwoCallersProduceTheSameSchemaOverASchema75Fixture()};
 *   <li>that object's own statements are safe to run twice, which is what the migration needs and
 *       the guarded `delivery` helper exists for — {@link #everySharedStatementIsReRunnable()}.
 * </ul>
 *
 * `SyncMigrationTest` is the other half: what the one definition actually produces, and
 * that a fresh install and an upgrade end at the same schema. The *real* fresh install - Room's
 * generated `createAllTables` and then the definition, with no schema-75 file under it - is
 * `FreshInstallSchemaTest`'s, because its `sqlite_master` text is Room's and cannot be
 * compared with a fixture's.
 */
class Schema76SharedTest {

    private companion object {
        const val MIGRATIONS =
            "data/src/main/java/uk/xa0/tulkki/data/schema/Migrations.kt"

        const val SCHEMA = "data/src/main/java/uk/xa0/tulkki/data/schema/Schema76.kt"

        const val OPENER = "data/src/main/java/uk/xa0/tulkki/data/HistoryDatabase.kt"
    }

    /**
     * The probe, first, and it fails rather than skips. The fixture contains `messages_index
     * USING fts4`, so a host SQLite without FTS4 cannot run any assertion in this class in a way
     * that means anything.
     */
    @Test
    fun theJvmSqliteHasFts4OrEveryAssertionBelowIsMeaningless() {
        Schema75Fixture.requireFts4(Schema75Fixture.open())
    }

    /**
     * The two callers, plus the fresh install: the Room migration, the fresh-install callback, and
     * the one object both of them execute. Since S5-5 there is no third caller - the legacy
     * `DatabaseBackend.onUpgrade` hook is gone with the rest of the legacy chain, and
     * `LegacyChainTest` is what keeps it gone - so what is pinned here is that the two
     * remaining callers still name the shared definition instead of a copy of it.
     */
    @Test
    fun bothCallersRouteThroughTheOneDefinition() {
        val migrations = RepoFiles.read(MIGRATIONS)
        Assert.assertTrue(
            "the Room migration must execute Schema76.applyUpgrade, not its own statements: " +
                migrations,
            migrations.contains("Schema76.applyUpgrade("))

        val opener = RepoFiles.read(OPENER)
        Assert.assertTrue(
            "the fresh-install callback must execute the one definition, or a new install is " +
                "missing the sync tables and the delivery column",
            opener.contains("installFreshSchema("))
        Assert.assertTrue(
            "and that definition must execute Schema76.applySchema, the fresh half",
            opener.contains("Schema76.applySchema("))
        Assert.assertTrue(
            "and Schema79.applySchema, so the capability tables, the day-split table and the " +
                "doubt-hold column reach a new install too",
            opener.contains("Schema80.applySchema("))
    }

    /**
     * The two callers, executed, each over its own schema-75 fixture, compared as sorted
     * `sqlite_master` and `PRAGMA table_info` text - which is what "cannot disagree"
     * means here (`docs/MIGRATION.md`, "Design: the data layer" §4.2).
     *
     * The first caller is the real Room object,
     * `MIGRATION_75_76.migrate(SupportSQLiteDatabase)`. Handing it a host-backed
     * {@link JdbcSupportDatabase} is also §4.4's first measurement: the JVM `Migration` type
     * takes the same `SupportSQLiteDatabase` the module compiles against, and the compiler
     * settles that here rather than a reading of Room's release notes.
     *
     * The second is the definition the legacy hook used to call, driven directly: the hook's own
     * body could never run on the host - its parameter is
     * `net.zetetic.database.sqlcipher.SQLiteDatabase`, which needs the Android-only native
     * library - so this is the function that was behind it, and since S5-5 it is the one the
     * fresh-install callback runs too.
     *
     * The third is the fresh-install definition over the same 75 file: a new install and an
     * owner's upgraded file must be the same schema or the two paths have diverged. (The real fresh
     * install, over Room's generated `createAllTables`, is `FreshInstallSchemaTest`'s.)
     */
    @Test
    fun theTwoCallersProduceTheSameSchemaOverASchema75Fixture() {
        val throughRoom = Schema75Fixture.openWithRows()
        for (migration in TulkkiMigrations.ALL) {
            migration.migrate(JdbcSupportDatabase.of(throughRoom))
        }

        val throughTheSharedDefinition = Schema75Fixture.openWithRows()
        Schema76.applyUpgrade(JdbcSchemaExec(throughTheSharedDefinition), 4242L)
        Schema77.applySchema(JdbcSchemaExec(throughTheSharedDefinition))
        Schema78.applyUpgrade(JdbcSchemaExec(throughTheSharedDefinition))
        Schema79.applyUpgrade(JdbcSchemaExec(throughTheSharedDefinition))
        Schema80.applyUpgrade(JdbcSchemaExec(throughTheSharedDefinition))

        val freshInstall = Schema75Fixture.open()
        Schema76.applySchema(JdbcSchemaExec(freshInstall))
        Schema80.applySchema(JdbcSchemaExec(freshInstall))

        Assert.assertEquals(
            "the Room migration and the shared definition the fresh install also runs must land on the same " +
                "schema; a drift between them is the defect this commit exists to prevent",
            Schema75Fixture.master(throughRoom),
            Schema75Fixture.master(throughTheSharedDefinition))
        Assert.assertEquals(
            "and a fresh install must land there too, or the two paths have diverged at 76",
            Schema75Fixture.master(throughRoom),
            Schema75Fixture.master(freshInstall))
        Assert.assertEquals(
            "the seeded rows must agree as well as the DDL: a caller that stopped running the " +
                "seed, or seeded a different anchor, is a drift no text comparison sees",
            Schema75Fixture.seededRows(throughRoom),
            Schema75Fixture.seededRows(throughTheSharedDefinition))
        for (table in arrayOf(
            "accounts",
            "conversations",
            "messages",
            "blocked_jids",
            "sync_cursor",
            "sync_conversation",
            "sync_gap",
        )) {
            Assert.assertEquals(
                "PRAGMA table_info differs for " +
                    table +
                    " between the Room migration and the shared definition the fresh install also runs",
                Schema75Fixture.tableInfo(throughRoom, table),
                Schema75Fixture.tableInfo(throughTheSharedDefinition, table))
            Assert.assertEquals(
                "PRAGMA table_info differs for " +
                    table +
                    " between the Room migration and a fresh install",
                Schema75Fixture.tableInfo(throughRoom, table),
                Schema75Fixture.tableInfo(freshInstall, table))
        }
    }

    /**
     * Re-runnability is not a style point: the legacy hook may run the list on a file that has
     * already seen it, and the owner's file must not be the thing that discovers otherwise.
     */
    @Test
    fun everySharedStatementIsReRunnable() {
        Assert.assertEquals("four statements: three tables and the account index", 4, Schema76.STATEMENTS.size)
        for (statement in Schema76.STATEMENTS) {
            Assert.assertTrue(
                "a shared statement without IF NOT EXISTS is not re-runnable: " + statement,
                statement.startsWith("CREATE TABLE IF NOT EXISTS ") ||
                    statement.startsWith("CREATE INDEX IF NOT EXISTS "))
        }
        Assert.assertFalse(
            "the delivery ALTER cannot be `IF NOT EXISTS` (SQLite has no such form), so it is " +
                "deliberately not in the shared list - a guarded helper runs it",
            Schema76.ADD_DELIVERY_COLUMN.contains("IF NOT EXISTS"))
        Assert.assertTrue(
            "the delivery ALTER must carry the UNKNOWN default, so every pre-existing row reads " +
                "as never-a-gap-candidate rather than as live",
            Schema76.ADD_DELIVERY_COLUMN.contains("DEFAULT " + Schema76.DELIVERY_UNKNOWN))
        Assert.assertTrue(
            "the delivery helper must read the column before adding it, or the second run " +
                "throws",
            RepoFiles.read(SCHEMA).contains("hasColumn("))
    }

    /** The guarded helper is what makes a second run safe, so run one. */
    @Test
    fun theUpgradeSurvivesBeingAppliedTwice() {
        val connection = Schema75Fixture.openWithRows()
        val exec = JdbcSchemaExec(connection)
        Schema76.applyUpgrade(exec, 4242L)
        val once = Schema75Fixture.master(connection) + Schema75Fixture.tableInfo(connection, "messages")
        Schema76.applyUpgrade(exec, 4242L)
        Assert.assertEquals(
            "applying the shared upgrade twice must change nothing; the guarded delivery " +
                "column and the IF NOT EXISTS statements are what buy that",
            once,
            Schema75Fixture.master(connection) + Schema75Fixture.tableInfo(connection, "messages"))
    }
}
