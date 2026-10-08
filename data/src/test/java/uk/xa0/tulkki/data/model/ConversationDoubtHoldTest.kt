package uk.xa0.tulkki.data.model

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.JdbcSchemaExec
import uk.xa0.tulkki.data.JdbcSupportDatabase
import uk.xa0.tulkki.data.Schema75Fixture
import uk.xa0.tulkki.data.schema.Schema76
import uk.xa0.tulkki.data.schema.Schema77
import uk.xa0.tulkki.data.schema.Schema78
import uk.xa0.tulkki.data.schema.Schema79
import uk.xa0.tulkki.data.schema.Schema80

/**
 * The per-conversation doubt-hold is a **tri-state**, and `NULL` is the third answer rather than a
 * spelling of "off": `NULL` is "this conversation never chose" and follows the shipped default,
 * which is *on*, `0` is off and `1` is explicitly on. The column and the model store exactly that -
 * the rule that consults the value is what resolves the default, and neither the column nor the
 * model may answer it.
 *
 * <p><strong>What this executes, and what it cannot.</strong> The column's own shape and the read
 * path are executed against a real 79 file: the fixture is reached the way a device reaches it (a
 * 75 snapshot, the 75 step, then 76, 77, 78 and 79), the three states are written with raw SQL
 * `1`/`0`/`NULL`, and {@link Conversation#fromCursor} is handed a cursor over real SQL `NULL`s
 * through {@link JdbcSupportDatabase} - which is where a "never chose" can silently become an off.
 * The *save* path cannot be called here: {@code getContentValues()} builds an
 * {@code android.content.ContentValues}, whose methods the host's {@code android.jar} does not
 * implement, so what a save writes is pinned through {@link Conversation#doubtHoldColumnValue} - the
 * one spelling {@code getContentValues()} uses - instead of by calling it.
 */
class ConversationDoubtHoldTest {

    private companion object {

        /** The account's own conversation rows at 79, reached the way a device reaches them. */
        private fun atSchema79(): Connection {
            val connection = Schema75Fixture.openWithRows()
            Schema75Fixture.takeTheSchema75Step(connection)
            Schema76.applyUpgrade(JdbcSchemaExec(connection), 4242L)
            Schema77.applySchema(JdbcSchemaExec(connection))
            Schema78.applyUpgrade(JdbcSchemaExec(connection))
            Schema79.applyUpgrade(JdbcSchemaExec(connection))
            Schema80.applyUpgrade(JdbcSchemaExec(connection))
            return connection
        }

        /**
         * One state written with raw SQL - the literal `1`, `0` or `NULL`, never the model's helper - and
         * the peer JID the model's reader resolves. The fixture's sample rows carry no `contactJid`, and
         * `Jid.ofOrInvalid(null)` throws rather than answering an invalid JID; that is upstream's own
         * shape, measured here rather than inherited.
         */
        private fun store(connection: Connection, value: Int?) {
            try {
                connection.prepareStatement(
                    "UPDATE " +
                        Conversation.TABLENAME +
                        " SET " +
                        Conversation.DOUBT_HOLD +
                        " = ?, " +
                        Conversation.CONTACTJID +
                        " = ? WHERE " +
                        Conversation.UUID +
                        " = ?").use { statement ->
                    if (value == null) {
                        statement.setNull(1, Types.INTEGER)
                    } else {
                        statement.setInt(1, value)
                    }
                    statement.setString(2, "peer@example.org")
                    statement.setString(3, Schema75Fixture.CONVERSATION_PLAIN)
                    statement.executeUpdate()
                }
            } catch (e: SQLException) {
                throw IllegalStateException("could not store the doubt-hold", e)
            }
        }

        /** One row of the fixture, mapped by the model's own reader. */
        private fun read(connection: Connection): Conversation {
            val database: SupportSQLiteDatabase = JdbcSupportDatabase.of(connection)
            return database.query(
                "SELECT * FROM " +
                    Conversation.TABLENAME +
                    " WHERE " +
                    Conversation.UUID +
                    " = ?",
                arrayOf<Any?>(Schema75Fixture.CONVERSATION_PLAIN)).use { cursor ->
                Assert.assertTrue("the fixture has that conversation", cursor.moveToNext())
                Conversation.fromCursor(cursor)
            }
        }
    }

    /**
     * `NULL` is its own answer and not a spelling of "off": a conversation that never chose follows
     * the shipped default, which is **on**, so reading it as `false` would turn the hold off for
     * every conversation that never touched it.
     */
    @Test
    fun nullIsNeverChoseAndNotOff() {
        val connection = atSchema79()
        store(connection, null)
        val doubtHold = read(connection).getDoubtHold()
        Assert.assertNull("NULL is \"never chose\", and the model does not resolve it", doubtHold)
        Assert.assertNotEquals(
            "and it is not `false` either: the default it follows is on",
            false,
            doubtHold)
    }

    /** All three states survive the file and the model, each as itself. */
    @Test
    fun theThreeStatesRoundTripThroughTheModel() {
        val connection = atSchema79()
        store(connection, Integer.valueOf(1))
        Assert.assertEquals("1 is on", true, read(connection).getDoubtHold())
        store(connection, Integer.valueOf(0))
        Assert.assertEquals("0 is off", false, read(connection).getDoubtHold())
        store(connection, null)
        Assert.assertNull("and NULL is neither of them", read(connection).getDoubtHold())
    }

    /** The column's own spelling, which is what a save writes: `1`, `0`, and absent. */
    @Test
    fun theColumnsOwnSpellingIsOneZeroAndAbsent() {
        Assert.assertEquals(Integer.valueOf(1), Conversation.doubtHoldColumnValue(true))
        Assert.assertEquals(Integer.valueOf(0), Conversation.doubtHoldColumnValue(false))
        Assert.assertNull(
            "a save of \"never chose\" writes NULL and not 0",
            Conversation.doubtHoldColumnValue(null))
    }

    /** `INTEGER`, nullable, no default: the ruling, read out of the file's own schema. */
    @Test
    fun theColumnIsANullableIntegerWithNoDefault() {
        val connection = atSchema79()
        var seen = false
        connection.prepareStatement(
            "PRAGMA table_info(" + Conversation.TABLENAME + ")").use { statement ->
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    if (!Conversation.DOUBT_HOLD.equals(rows.getString("name"))) {
                        continue
                    }
                    seen = true
                    Assert.assertEquals("an INTEGER", "INTEGER", rows.getString("type"))
                    Assert.assertEquals(
                        "nullable, so NULL can mean \"never chose\"", 0, rows.getInt("notnull"))
                    Assert.assertNull(
                        "and no default: the shipped default is not stored in the column",
                        rows.getString("dflt_value"))
                }
            }
        }
        Assert.assertTrue("the column is in the file: " + Conversation.DOUBT_HOLD, seen)
    }
}
