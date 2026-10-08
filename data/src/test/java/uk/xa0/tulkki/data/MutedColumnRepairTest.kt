package uk.xa0.tulkki.data

import androidx.room.RoomOpenDelegate
import androidx.room.driver.SupportSQLiteConnection
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.schema.RawTables
import uk.xa0.tulkki.data.schema.Schema76
import uk.xa0.tulkki.data.schema.Schema77
import uk.xa0.tulkki.data.schema.Schema78
import uk.xa0.tulkki.data.schema.Schema79
import uk.xa0.tulkki.data.schema.Schema80
import uk.xa0.tulkki.data.schema.TulkkiMigrations

/**
 * The phone's own launch crash, and the repair that is now a migration (S5-12 / S5-6 / schema 78).
 *
 * <p><strong>What the owner saw.</strong> A debug build on `master` (S5-12 in, installed over an
 * existing database) died at launch:
 *
 * <pre>
 * android.database.sqlite.SQLiteException: no such column: account_uuid (code 1)
 *   while compiling: SELECT muc_jid, occupant_id FROM muted_participants WHERE account_uuid = ?
 *     at DatabaseBackend.loadMutedMucUsers(DatabaseBackend.java:648)
 *     &lt;- XmppConnectionService.restoreFromDatabase
 * </pre>
 *
 * <p><strong>The cause, as measured here rather than assumed.</strong> Two candidates, and this test
 * decides between them:
 *
 * <ol>
 *   <li><em>"a pre-77 file is upgraded through the wrong path."</em>
 *       {@link #aPreSeventySevenFileWalksTheMigrationsAndTheReadRuns} answers it: a file whose
 *       `muted_participants` really is the legacy shape is walked through every registered migration
 *       as a device walks it, and the startup read then runs. So this is not the mechanism -
 *       `DatabaseBackend` no longer opens anything (it is handed Room's handle at `getInstance`), and
 *       the migration does add the column.
 *   <li><em>"the file was already at 77 with the shapes S5-12 later changed."</em>
 *       {@link #aFileAlreadyAtSeventySevenKeepsTheStaleTablesAndTheReadDies} answers this one, and it
 *       is the mechanism. S5-12 (`a8676ea412`) added `translation_queue`'s, `muted_participants`' and
 *       `webxdc_updates`' account-scoped keys *inside* version 77, whose migration the earlier S5-3
 *       batches had already registered; a file stamped 77 in between runs no migration on that build.
 *       None of the three was an entity then, so `onValidateSchema` never looked at them and the file
 *       opened - and the startup read then threw the owner's own message. Since schema 78 declares
 *       `translation_queue`, that same file is a refused open instead, which is the loud half of the
 *       same repair.
 * </ol>
 *
 * <p><strong>Where the repair lives now, and why it moved.</strong> Schema 78 is what makes a 77
 * file a version difference, so `MIGRATION_77_78` runs `Schema78.applyUpgrade`, which is
 * `Schema77.repairLateColumns`' own guarded bodies plus 78's new table. That is the position the
 * repair always needed: **Room validates the declared entities after the migrations and before any
 * callback**, so a repair in `onOpen` could never help a table an entity declares - and once
 * `translation_queue` is declared, a repair left in the callback would be believed to do something
 * it cannot. {@link #theMigrationMakesTheStaleTablesTheMigratedOnes} drives that function over the
 * stale file and then hands the result to Room's own validator,
 * {@link #theRepairIsIdempotent} is its second half, and
 * {@link #theRepairIsTheMigrationsAndTheCallbackNoLongerDoes} pins both sides of the move.
 *
 * <p><strong>What a device still has to confirm.</strong> Everything here runs against the host's
 * plain SQLite through `JdbcSupportDatabase`; the owner's file is SQLCipher's, and the real open path
 * is `SupportOpenHelperFactory`'s. What the host proves is the shape arithmetic and that Room
 * validates the repaired result, not SQLCipher.
 */
class MutedColumnRepairTest {

    /**
     * **Candidate 1, answered: the migrations add the column.** A file whose mute table really is the
     * legacy shape is walked through every registered migration as a device walks it, and the app's
     * own startup read then runs rather than throwing. The legacy row survives with no owner -
     * S5-12's own documented outcome, "the one row that cannot be attributed at all" - and a row
     * written afterwards, with an owner, is returned for it.
     */
    @Test
    fun aPreSeventySevenFileWalksTheMigrationsAndTheReadRuns() {
        val connection: Connection = Schema75Fixture.open()
        DaoSql.exec(connection, "DROP TABLE " + RawTables.MUTED_TABLE)
        DaoSql.exec(connection, RawTables.CREATE_MUTED_LEGACY)
        DaoSql.exec(
                connection,
                "INSERT INTO " +
                        RawTables.MUTED_TABLE +
                        " (" +
                        RawTables.MUTED_MUC +
                        ", " +
                        RawTables.MUTED_OCCUPANT +
                        ") VALUES ('" +
                        MUC +
                        "', 'legacy-occupant')")
        JdbcSchemaExec(connection).exec("PRAGMA foreign_keys=OFF")
        walkTheRegisteredMigrations(connection)

        Assert.assertTrue(
                "the migrations must add the owner column to a file that never had it",
                Schema75Fixture.columnNames(connection, RawTables.MUTED_TABLE)
                        .contains(RawTables.MUTED_ACCOUNT))
        Assert.assertEquals(
                "and the startup read runs; the legacy row has no owner, so it is nobody's",
                emptyList<String>(),
                mutes(connection, ACCOUNT))

        DaoSql.exec(
                connection,
                "INSERT INTO " +
                        RawTables.MUTED_TABLE +
                        " (" +
                        RawTables.MUTED_ACCOUNT +
                        ", " +
                        RawTables.MUTED_MUC +
                        ", " +
                        RawTables.MUTED_OCCUPANT +
                        ") VALUES (?,?,?)",
                ACCOUNT,
                MUC,
                OCCUPANT)
        Assert.assertEquals(
                "a row with an owner is the account's own",
                listOf(OCCUPANT),
                mutes(connection, ACCOUNT))
    }

    /**
     * **Candidate 2, and it is the phone: a file already at 77 keeps the stale tables and the startup
     * read dies - and, since the queue is an entity, Room itself now refuses it.**
     *
     * <p>Both halves matter and they changed places with schema 78. While none of the three tables was
     * declared, `onValidateSchema` never looked at them and the stale file opened: the read then threw
     * the owner's own message, which is exactly why the version short-circuit was invisible. Declaring
     * `translation_queue` is what makes Room able to see it - so a file that reached validation
     * unrepaired is now a *refused open* rather than a launch crash somewhere later. In production the
     * repair has already run by then, because `MIGRATION_77_78` is the step Room takes before it
     * validates; this cell is the state *without* that step, which is what makes the migration
     * load-bearing rather than a tidy-up.
     */
    @Test
    fun aFileAlreadyAtSeventySevenKeepsTheStaleTablesAndTheReadDies() {
        val stale: Connection = aFileStampedSeventySevenBeforeS5_12()

        val room: SQLiteConnection = SupportSQLiteConnection(JdbcSupportDatabase.of(stale))
        val result: RoomOpenDelegate.ValidationResult =
                RoomOnTheHost.openDelegate().onValidateSchema(room)
        Assert.assertFalse(
                "Room must now refuse the unrepaired file, and name the entity that found it - until " +
                        "the queue was declared, this file opened and the damage surfaced later:\n" +
                        result.expectedFoundMsg,
                result.isValid)
        Assert.assertTrue(
                "and an entity's declaration must catch it - with schema 79's nullable `doubt_hold` " +
                        "the conversation table is named first on a pre-79 file, and the queue's own " +
                        "missing shape is still a refusal: " + result.expectedFoundMsg,
                result.expectedFoundMsg!!.contains("conversations") ||
                        result.expectedFoundMsg!!.contains("translation_queue"))

        Assert.assertTrue(
                "and the app's startup read still throws the owner's own message",
                DaoSql.fails(stale, RawTables.MUTED_FOR_ACCOUNT, ACCOUNT))
    }

    /**
     * **The repair, driven through the migration's own function and then handed to Room.** The
     * startup read runs afterwards, the room's owner is resolved out of the file, each repaired table
     * is - `PRAGMA table_info` for `PRAGMA table_info` - the table a correctly migrated file has, and
     * Room's own validator accepts the result. The keys come with it, which is what makes this the
     * migration's own shape rather than a column bolted on by an `ALTER`.
     */
    @Test
    fun theMigrationMakesTheStaleTablesTheMigratedOnes() {
        val stale: Connection = aFileStampedSeventySevenBeforeS5_12()
        val migrated: Connection = atSeventyEight()

        Schema78.applyUpgrade(JdbcSchemaExec(stale))
        Schema79.applyUpgrade(JdbcSchemaExec(stale))
        Schema80.applyUpgrade(JdbcSchemaExec(stale))

        Assert.assertEquals(
                "the startup read runs, and the room's owner is resolved out of the file",
                listOf(OCCUPANT),
                mutes(stale, ACCOUNT))
        for (table in arrayOf(
                RawTables.MUTED_TABLE,
                uk.xa0.tulkki.data.TranslationTables.QUEUE_TABLE,
                RawTables.WEBXDC_TABLE,
        )) {
            Assert.assertEquals(
                    "the repaired " + table + " must be the migrated file's table, key and all",
                    Schema75Fixture.tableInfo(migrated, table),
                    Schema75Fixture.tableInfo(stale, table))
        }

        // Room's own comparison, after the migration, over the repaired file.
        val room: SQLiteConnection = SupportSQLiteConnection(JdbcSupportDatabase.of(stale))
        val result: RoomOpenDelegate.ValidationResult =
                RoomOnTheHost.openDelegate().onValidateSchema(room)
        Assert.assertTrue(
                "Room must accept the repaired file, or the migration is not enough and the owner's " +
                        "phone refuses to open:\n" +
                        result.expectedFoundMsg,
                result.isValid)
    }

    /** A second run changes nothing: the guards are the column and the two keys. */
    @Test
    fun theRepairIsIdempotent() {
        val stale: Connection = aFileStampedSeventySevenBeforeS5_12()
        Schema78.applyUpgrade(JdbcSchemaExec(stale))
        Schema79.applyUpgrade(JdbcSchemaExec(stale))
        Schema80.applyUpgrade(JdbcSchemaExec(stale))
        val once = Schema75Fixture.master(stale)

        Schema78.applyUpgrade(JdbcSchemaExec(stale))
        Schema79.applyUpgrade(JdbcSchemaExec(stale))
        Schema80.applyUpgrade(JdbcSchemaExec(stale))

        Assert.assertEquals(
                "a second run must change nothing at all", once, Schema75Fixture.master(stale))
        Assert.assertEquals(
                "and the rows are still the rows", listOf(OCCUPANT), mutes(stale, ACCOUNT))
    }

    /**
     * The move itself, pinned from both sides. The repair is `Schema78.applyUpgrade`'s (and it is
     * `Schema77`'s guarded bodies, not a second spelling), the migration runs it, and the `onOpen`
     * callback no longer does - so nothing invites the belief that a callback can repair a table an
     * entity declares.
     */
    @Test
    fun theRepairIsTheMigrationsAndTheCallbackNoLongerDoes() {
        val schema = RepoFiles.read(SCHEMA_78)
        Assert.assertTrue(
                "Schema78.applyUpgrade must run the late-shape repair",
                schema.contains("Schema77.repairLateColumns(exec)"))

        val migrations = RepoFiles.read(MIGRATIONS)
        Assert.assertTrue(
                "and MIGRATION_77_78 must be the migration that runs it",
                migrations.contains("Schema78.applyUpgrade(db)"))

        val opener = RepoFiles.read(HISTORY_DATABASE)
        val onOpen = opener.indexOf("override fun onOpen(")
        val enable = opener.indexOf("setForeignKeyConstraintsEnabled(true)")
        Assert.assertTrue(
                "the opener must still enable foreign keys in onOpen, which is the legal position",
                onOpen > 0 && enable > onOpen)
        Assert.assertFalse(
                "and the callback must not repair any more: Room validates the declared entities " +
                        "before it runs a callback, so an onOpen repair cannot help an entity table",
                opener.contains("repairLateColumns("))
    }

    private companion object {

        const val ACCOUNT = "acct-1"

        const val MUC = "room@conference.example.org"

        const val OCCUPANT = "occupant-1"

        const val HISTORY_DATABASE =
                "data/src/main/java/uk/xa0/tulkki/data/HistoryDatabase.kt"

        const val SCHEMA_78 = "data/src/main/java/uk/xa0/tulkki/data/schema/Schema78.kt"

        const val MIGRATIONS =
                "data/src/main/java/uk/xa0/tulkki/data/schema/Migrations.kt"

        /** The 77 file the owner's phone has: the migrations walked to 77 and stopped there. */
        fun atSeventySeven(): Connection {
            val connection = Schema75Fixture.openWithRows()
            Schema75Fixture.takeTheSchema75Step(connection)
            Schema76.applyUpgrade(JdbcSchemaExec(connection), 4242L)
            Schema77.applySchema(JdbcSchemaExec(connection))
            return connection
        }

        /** The same file walked on to 78: what a correct 77 file becomes. */
        fun atSeventyEight(): Connection {
            val connection = atSeventySeven()
            Schema78.applyUpgrade(JdbcSchemaExec(connection))
            Schema79.applyUpgrade(JdbcSchemaExec(connection))
            Schema80.applyUpgrade(JdbcSchemaExec(connection))
            return connection
        }

        /** Every registered migration, in order: the device's own walk, not a second spelling of it. */
        fun walkTheRegisteredMigrations(connection: Connection) {
            for (migration in TulkkiMigrations.ALL) {
                migration.migrate(JdbcSupportDatabase.of(connection))
            }
        }

        /**
         * The owner's file as S5-12 found it: a file the migrations have taken to 77 - which is what a
         * correct migration does, and what makes the rest of the file Room-valid - with S5-12's three
         * account-scoped shapes put back to the legacy ones, because that is what a file stamped 77 by a
         * build <em>before</em> S5-12 carries. The room's own conversation is present, so the repair's
         * copy has an owner to resolve.
         */
        fun aFileStampedSeventySevenBeforeS5_12(): Connection {
            val connection = atSeventySeven()

            DaoSql.exec(connection, "DROP TABLE " + uk.xa0.tulkki.data.TranslationTables.QUEUE_TABLE)
            DaoSql.exec(
                    connection,
                    uk.xa0.tulkki.data.TranslationTables.CREATE_QUEUE_TABLE.replace(
                            uk.xa0.tulkki.data.TranslationTables.QUEUE_FOREIGN_KEY_TAIL, ""))
            DaoSql.exec(connection, "DROP TABLE " + RawTables.MUTED_TABLE)
            DaoSql.exec(connection, RawTables.CREATE_MUTED_LEGACY)
            DaoSql.exec(connection, "DROP TABLE " + RawTables.WEBXDC_TABLE)
            DaoSql.exec(connection, RawTables.CREATE_WEBXDC_LEGACY)

            // The room the mute belongs to, so the repair's owner lookup has an answer - the fixture's
            // own conversations carry no contactJid.
            DaoSql.exec(
                    connection,
                    "INSERT INTO conversations (uuid, accountUuid, contactJid, mode) VALUES (?,?,?,1)",
                    "conv-muted",
                    ACCOUNT,
                    MUC)
            DaoSql.exec(
                    connection,
                    "INSERT INTO " +
                            RawTables.MUTED_TABLE +
                            " (" +
                            RawTables.MUTED_MUC +
                            ", " +
                            RawTables.MUTED_OCCUPANT +
                            ") VALUES ('" +
                            MUC +
                            "', '" +
                            OCCUPANT +
                            "')")
            return connection
        }

        /** The app's own startup read, run the way `DatabaseBackend.loadMutedMucUsers` runs it. */
        fun mutes(connection: Connection, account: String): List<String> {
            val out = mutableListOf<String>()
            try {
                connection.prepareStatement(RawTables.MUTED_FOR_ACCOUNT).use { statement ->
                    statement.setString(1, account)
                    statement.executeQuery().use { results ->
                        while (results.next()) {
                            out.add(results.getString(RawTables.MUTED_OCCUPANT))
                        }
                    }
                }
            } catch (e: SQLException) {
                throw IllegalStateException("the startup read failed: " + e.message, e)
            }
            return out
        }
    }
}
