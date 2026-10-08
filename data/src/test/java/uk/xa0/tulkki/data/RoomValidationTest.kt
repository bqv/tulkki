package uk.xa0.tulkki.data

import androidx.room.RoomOpenDelegate
import androidx.room.driver.SupportSQLiteConnection
import androidx.sqlite.SQLiteConnection
import java.sql.Connection
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.schema.Schema76
import uk.xa0.tulkki.data.schema.Schema77
import uk.xa0.tulkki.data.schema.Schema78
import uk.xa0.tulkki.data.schema.Schema79
import uk.xa0.tulkki.data.schema.Schema80

/**
 * The entities, validated by **Room itself**, on the host.
 *
 * <p>This is the test that makes declaring `AccountEntity`, `ConversationEntity` and
 * `MessageEntity` safe rather than a hope. Room validates a pre-existing file after the migration
 * by comparing each declared entity against the file's `PRAGMA`s, and a mismatch is a *refused
 * open* - the failure the whole adoption is shaped around. Running that comparison on a device is
 * not verification, it is discovery, so it runs here instead.
 *
 * <p>It is the generated validator that runs: `HistoryDatabase_Impl.createOpenDelegate()` is KSP's
 * own `onValidateSchema`, the same code Room calls at open, handed the same
 * `SupportSQLiteConnection` adapter Room builds from the `SupportSQLiteDatabase` at
 * `RoomOpenHelper.onUpgrade` bytecode 97-108. What is neither here nor possible here is SQLCipher:
 * the file under it is the host's plain SQLite. The comparison Room makes is the same one.
 *
 * <p>Two states, because both are real: the migrated file (a 74 fixture walked through the legacy
 * 75 step and then 76 and 77) and a fresh install. If they ever disagreed, one of the owner's two lives -
 * new phone, upgraded phone - would open and the other would not.
 */
class RoomValidationTest {

    private companion object {

        /**
         * Every entity the database declares, against the file as it now stands. The message is Room's
         * own expected-versus-found text, so a failure names the column or index that disagrees.
         */
        private fun assertRoomAccepts(connection: Connection, what: String) {
            val room: SQLiteConnection = SupportSQLiteConnection(JdbcSupportDatabase.of(connection))
            val result: RoomOpenDelegate.ValidationResult =
                RoomOnTheHost.openDelegate().onValidateSchema(room)
            Assert.assertTrue(
                "Room must accept " + what + ", or the app refuses to open it on the owner's " +
                    "phone:\n" + result.expectedFoundMsg,
                result.isValid)
        }
    }

    @Test
    fun roomAcceptsTheMigratedFileAndTheFreshInstall() {
        val migrated = Schema75Fixture.openFrom74WithRows()
        Schema75Fixture.takeTheSchema75Step(migrated)
        Schema76.applyUpgrade(JdbcSchemaExec(migrated), 4242L)
        Schema77.applySchema(JdbcSchemaExec(migrated))
        Schema78.applyUpgrade(JdbcSchemaExec(migrated))
        Schema79.applyUpgrade(JdbcSchemaExec(migrated))
        Schema80.applyUpgrade(JdbcSchemaExec(migrated))
        assertRoomAccepts(migrated, "the migrated file")

        val fresh = Schema75Fixture.open()
        Schema76.applySchema(JdbcSchemaExec(fresh))
        Schema80.applySchema(JdbcSchemaExec(fresh))
        assertRoomAccepts(fresh, "a fresh install at 78")
    }
}
