package uk.xa0.tulkki.data.updb

import androidx.room.RoomOpenDelegate
import androidx.room.driver.SupportSQLiteConnection
import androidx.sqlite.SQLiteConnection
import java.io.File
import java.sql.Connection
import java.sql.SQLException
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.DaoSql
import uk.xa0.tulkki.data.JdbcSchemaExec
import uk.xa0.tulkki.data.JdbcSupportDatabase
import uk.xa0.tulkki.data.Schema75Fixture

/**
 * S5-3's adoption of the UnifiedPush file: the preflight, and what it promises.
 *
 * The file the owner's phone holds is at `user_version = 1` with a `push` table whose
 * `expiration` is spelled `NUMBER`. Room's `@Database` is 1 as well, so nothing
 * upgrades it: Room opens the file, finds no `room_master_table` and runs
 * `onValidateSchema`, which compares affinity unconditionally and refuses. That refusal is
 * measured here rather than described, and so is the cure.
 *
 * <strong>The guarantee, in one sentence.</strong> Before Room opens the file the preflight
 * rebuilds `push` create-copy-drop-rename - the surrogate `_id` carrying the legacy
 * `rowid`, every column in its true affinity, the inline `UNIQUE(instance)` kept and the
 * same guarantee created as the named index the entity declares - and **no row is added, removed or
 * made NOT NULL**; the whole rebuild is one transaction on one connection, so a killed process
 * leaves either the old file or the new one.
 */
class LegacyPreflightTest {

    @Test
    fun theLegacyFileIsRebuiltAndEveryRowSurvives() {
        val connection = UpdbFixture.legacyWithRows()
        val before = updbRows(connection)
        Assert.assertFalse("the fixture must start with the legacy spelling", before.isEmpty())

        Assert.assertTrue(
                "a file with the legacy push table must be rebuilt",
                LegacyPreflight.rebuildIfLegacy(JdbcSchemaExec(connection)))

        Assert.assertEquals(
                "the rebuild must not add, remove or change a row", before, updbRows(connection))
        Assert.assertEquals(
                "and the surrogate key is the legacy rowid, in order",
                "1|2|3",
                joined(connection, "SELECT _id FROM push ORDER BY _id"))
        val info = Schema75Fixture.tableInfo(connection, UpdbQueries.TABLE)
        Assert.assertTrue(
                "the surrogate key must be the file's primary key: " + info,
                info.contains("_id|INTEGER|1|null|1"))
        Assert.assertTrue(
                "the row whose account, transport, endpoint and expiration were all NULL must not " +
                        "have been made NOT NULL: " +
                        info,
                info.contains("account|TEXT|0|null|0") &&
                        info.contains("transport|TEXT|0|null|0") &&
                        info.contains("endpoint|TEXT|0|null|0") &&
                        info.contains("expiration|INTEGER|0|0|0"))
        Assert.assertFalse(
                "the legacy NUMBER spelling must be gone, or Room reads UNDEFINED and refuses: " +
                        info,
                info.contains("NUMBER"))
        Assert.assertTrue(
                "and UNIQUE(instance) must still be a table constraint: " +
                        joined(connection, "SELECT sql FROM sqlite_master WHERE name = 'push'"),
                joined(connection, "SELECT sql FROM sqlite_master WHERE name = 'push'")
                        .uppercase()
                        .contains("UNIQUE(INSTANCE)"))
    }

    @Test
    fun theUniqueInstanceConstraintSurvivesTheRebuild() {
        val connection = UpdbFixture.adopted()

        Assert.assertEquals(
                "the named unique index the entity declares must exist",
                1L,
                DaoSql.scalarLong(
                        connection,
                        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = ?",
                        "index_push_instance"))
        Assert.assertTrue(
                "and a duplicate instance must still be refused",
                DaoSql.fails(
                        connection,
                        "INSERT INTO push (application, instance) VALUES (?, ?)",
                        "app-x",
                        UpdbFixture.INSTANCE_ONE))
        Assert.assertEquals(
                "so the three legacy rows are still three",
                3L,
                DaoSql.scalarLong(connection, "SELECT COUNT(*) FROM push"))
    }

    @Test
    fun thePreflightDoesNothingWithoutAFile() {
        val absent =
                File(
                        System.getProperty("java.io.tmpdir"),
                        "updb-absent-" + System.nanoTime())
        Assert.assertFalse("a fresh install has no file", LegacyPreflight.hasLegacyFile(absent))

        LegacyPreflight.adopt(absent, ByteArray(32))

        Assert.assertFalse(
                "the preflight must not create the file Room has not opened yet", absent.exists())
    }

    @Test
    fun thePreflightDoesNothingOnAnAlreadyRoomFile() {
        val connection = UpdbFixture.legacyWithRows()
        UpdbFixture.exec(
                connection,
                "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
        val before = updbRows(connection)

        Assert.assertFalse(
                "a file Room has already written its identity into must not be touched, even when " +
                        "its push table still carries the legacy spelling",
                LegacyPreflight.rebuildIfLegacy(JdbcSchemaExec(connection)))

        Assert.assertEquals("and no row moved", before, updbRows(connection))
        Assert.assertTrue(
                "with the legacy NUMBER left exactly where it was",
                Schema75Fixture.tableInfo(connection, UpdbQueries.TABLE).contains("NUMBER"))
    }

    @Test
    fun thePreflightDoesNothingWithoutAPushTable() {
        val connection = UpdbFixture.empty()

        Assert.assertFalse(
                "there is nothing to adopt",
                LegacyPreflight.rebuildIfLegacy(JdbcSchemaExec(connection)))
        Assert.assertEquals(
                "and nothing was created",
                0L,
                DaoSql.scalarLong(
                        connection,
                        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
                        UpdbQueries.TABLE))
    }

    @Test
    fun thePreflightDoesNothingOnAFileItAlreadyRebuilt() {
        val connection = UpdbFixture.legacyWithRows()
        Assert.assertTrue(LegacyPreflight.rebuildIfLegacy(JdbcSchemaExec(connection)))
        val after = updbRows(connection)

        Assert.assertFalse(
                "a run that rebuilt the file and was killed before Room wrote its identity must be " +
                        "a no-op on the second attempt",
                LegacyPreflight.rebuildIfLegacy(JdbcSchemaExec(connection)))

        Assert.assertEquals("and it must leave the rebuilt file alone", after, updbRows(connection))
        Assert.assertEquals(
                "including the surrogate keys", "1|2|3", joined(connection, "SELECT _id FROM push ORDER BY _id"))
    }

    /**
     * The cell this whole commit is shaped around: Room's own `onValidateSchema` - the
     * generated `UnifiedPushDatabase_Impl.createOpenDelegate()`, the same code the app runs at
     * every `XmppConnectionService` start - refuses the file as the owner holds it, and
     * accepts it once the preflight has run.
     */
    @Test
    fun roomRefusesTheLegacyFileAndAcceptsTheAdoptedOne() {
        val connection = UpdbFixture.legacyWithRows()

        val before = validate(connection)
        Assert.assertFalse(
                "Room must refuse the legacy file: this is the refused open, not a warning, that the " +
                        "preflight exists to prevent. Room said: " +
                        before.expectedFoundMsg,
                before.isValid)

        Assert.assertTrue(
                "the preflight must rebuild it",
                LegacyPreflight.rebuildIfLegacy(JdbcSchemaExec(connection)))

        val after = validate(connection)
        Assert.assertTrue(
                "Room must accept the adopted file, or the app refuses to open the push database on " +
                        "the owner's phone: " +
                        after.expectedFoundMsg,
                after.isValid)
        Assert.assertEquals(
                "and the three registrations are still there",
                3L,
                DaoSql.scalarLong(connection, "SELECT COUNT(*) FROM push"))
    }

    private companion object {
        private fun validate(connection: Connection): RoomOpenDelegate.ValidationResult {
            val room: SQLiteConnection = SupportSQLiteConnection(JdbcSupportDatabase.of(connection))
            return UpdbOnTheHost.openDelegate().onValidateSchema(room)
        }

        private fun updbRows(connection: Connection): String {
            return Schema75Fixture.dumpRows(connection, UpdbQueries.TABLE, listOf(*UpdbFixture.ROW_COLUMNS))
        }

        /** The first column of every row, joined: the shape a small ordered assertion reads best in. */
        private fun joined(connection: Connection, sql: String): String {
            val values = mutableListOf<String>()
            try {
                connection.createStatement().use { statement ->
                    statement.executeQuery(sql).use { results ->
                        while (results.next()) {
                            values.add(results.getString(1))
                        }
                    }
                }
            } catch (e: SQLException) {
                throw IllegalStateException("could not query: " + sql, e)
            }
            return values.joinToString("|")
        }
    }
}
