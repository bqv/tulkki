package uk.xa0.tulkki.data

import androidx.room.RoomOpenDelegate
import androidx.room.driver.SupportSQLiteConnection
import androidx.sqlite.SQLiteConnection
import java.sql.Connection
import java.sql.SQLException
import java.util.TreeSet
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.schema.TulkkiMigrations

/**
 * The fresh install, actually assembled, against a file the migrations walked from 75.
 *
 * This is what `docs/MIGRATION.md` §6 step 5 asks for once Room generates `onCreate`:
 * the fresh path is **Room's own generated `createAllTables`** - the same method Room runs on a
 * first launch, reached here through the generated `HistoryDatabase_Impl` and a host JDBC
 * connection, exactly as `RoomValidationTest` reaches the validator - and then
 * `HistoryDatabase.installFreshSchema`, which is the one function `HistoryCallbacks.onCreate`
 * calls. Nothing here is a restatement of the definition; the test drives it.
 *
 * <p><strong>What is compared, and why not the `sqlite_master` text.</strong> The two paths
 * cannot be equal as text and are not meant to be: Room's generated `CREATE TABLE` quotes its
 * identifiers and writes `ON UPDATE NO ACTION`, while the migration's DDL for the same table
 * is the spelling the file has had since before the adoption. Room does not compare text either -
 * `TableInfo.equals` compares the columns' names, affinities, not-null flags and primary-key
 * positions, the foreign keys and the indices - so the comparison here is the same one: the set of
 * tables, `PRAGMA table_info` for each, and the set of index names. A fresh install and an
 * owner's upgraded file are the same schema in the only sense the opener checks.
 *
 * <p><strong>The version.</strong> Neither path writes `PRAGMA user_version`: the definition
 * is not allowed to (that is Room's, and `SyncMigrationTest.theUpgradeLeavesUserVersionToRoom`
 * pins the migration half of it). This test pins the fresh half - the value is still 0 after the
 * definition has run - so the number the owner's file ends at is
 * `@Database(version = DatabaseBackend.DATABASE_VERSION)`, which is 80 and is pinned as source
 * text by `OpenHelperFactoryTest`. The first launch itself - SQLCipher, the keystore, a real
 * file - remains the device check the S5-1 row owns.
 */
class FreshInstallSchemaTest {

    private companion object {

        /** Room's own bookkeeping table: created by `createAllTables`, never by a migration. */
        private const val ROOM_MASTER_TABLE = "room_master_table"

        /**
         * SQLite's FTS4 shadow tables. They are in the table-name comparison (both paths have them, and
         * a missing one is a real defect) but not in the per-table column comparison: their shape is
         * SQLite's, and both sides create the virtual table from the same statement.
         */
        private val FTS_SHADOW_TABLES =
            setOf(
                "messages_index_docsize",
                "messages_index_segdir",
                "messages_index_segments",
                "messages_index_stat")

        private const val FRESH = "a fresh install"
        private const val MIGRATED = "a file the migrations walked from 75"

        /**
         * Room's generated `createAllTables`, then the one function the callback calls.
         *
         * <p>Room's KMP driver is what makes this reachable on the host (`RoomOnTheHost` uses it for the
         * same reason): `createAllTables` takes an `androidx.sqlite.SQLiteConnection`, and
         * {@link JdbcSQLiteDriver} is one over the host's JDBC SQLite. The `SupportSQLiteDatabase`
         * harness cannot stand in here - {@link JdbcSupportDatabase} refuses `compileStatement`
         * rather than answering a stub, and Room's `SupportSQLiteConnection.prepare` needs it.
         */
        private fun freshInstall(name: String): Connection {
            val driver = JdbcSQLiteDriver(name)
            val connection = driver.connection()
            // The fresh install creates `messages_index`, so a host SQLite without FTS4 must fail here
            // rather than assert something weaker further down.
            Schema75Fixture.requireFts4(connection)
            val room: SQLiteConnection = driver.open(name)
            try {
                RoomOnTheHost.openDelegate().createAllTables(room)
            } finally {
                room.close()
            }
            HistoryDatabase.installFreshSchema(JdbcSchemaExec(connection))
            return connection
        }

        /** The owner's path: a schema-75 file walked by both registered migrations. */
        private fun migratedFrom75(): Connection {
            val connection = Schema75Fixture.openWithRows()
            for (migration in TulkkiMigrations.ALL) {
                migration.migrate(JdbcSupportDatabase.of(connection))
            }
            return connection
        }

        /** Every table the file holds, SQLite's own and Room's bookkeeping table aside. */
        private fun tableNames(connection: Connection): Set<String> {
            return names(connection, "table")
        }

        private fun indexNames(connection: Connection): Set<String> {
            return names(connection, "index")
        }

        private fun names(connection: Connection, type: String): Set<String> {
            val out: MutableSet<String> = TreeSet()
            try {
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery(
                            "SELECT name FROM sqlite_master WHERE type = '" +
                                type +
                                "' AND name NOT LIKE 'sqlite_%' AND name <> '" +
                                ROOM_MASTER_TABLE +
                                "'")
                        .use { results ->
                            while (results.next()) {
                                out.add(results.getString(1))
                            }
                        }
                }
            } catch (e: SQLException) {
                throw IllegalStateException("could not read sqlite_master", e)
            }
            return out
        }

        private fun longAt(connection: Connection, sql: String): Long {
            try {
                connection.createStatement().use { statement ->
                    statement.executeQuery(sql).use { results ->
                        Assert.assertTrue("no row for " + sql, results.next())
                        return results.getLong(1)
                    }
                }
            } catch (e: SQLException) {
                throw IllegalStateException("could not run: " + sql, e)
            }
        }
    }

    @Test
    fun roomGeneratingOnCreateLandsOnTheMigratedSchema() {
        val fresh = freshInstall("fresh-schema")
        val migrated = migratedFrom75()

        Assert.assertEquals(
            "a fresh install and an upgraded file must hold the same tables",
            tableNames(migrated),
            tableNames(fresh))
        Assert.assertEquals(
            "and the same indices",
            indexNames(migrated),
            indexNames(fresh))

        for (table in tableNames(fresh)) {
            if (FTS_SHADOW_TABLES.contains(table)) {
                continue
            }
            Assert.assertEquals(
                "PRAGMA table_info differs for " +
                    table +
                    " between " +
                    FRESH +
                    " and " +
                    MIGRATED,
                Schema75Fixture.tableInfo(migrated, table),
                Schema75Fixture.tableInfo(fresh, table))
        }

        // Room's own comparison, over the connection this test built rather than a fixture: if the
        // entities and the assembled schema ever disagree, the app refuses to open the file.
        val room: SQLiteConnection = SupportSQLiteConnection(JdbcSupportDatabase.of(fresh))
        val result: RoomOpenDelegate.ValidationResult =
            RoomOnTheHost.openDelegate().onValidateSchema(room)
        Assert.assertTrue(
            "Room must accept the fresh install, or a new phone refuses to open its own " +
                "database:\n" +
                result.expectedFoundMsg,
            result.isValid)
    }

    @Test
    fun nothingInTheFreshInstallWritesTheVersion() {
        val fresh = freshInstall("fresh-version")
        Assert.assertEquals(
            "the fresh-install definition must leave user_version alone: Room owns it, and the" +
                " number it writes is @Database(version = DatabaseBackend.DATABASE_VERSION)",
            0L,
            longAt(fresh, "PRAGMA user_version"))
        Assert.assertEquals("the version in force", 80, DatabaseBackend.DATABASE_VERSION)
    }
}
