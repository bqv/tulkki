package uk.xa0.tulkki.data.sync

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.regex.Pattern
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.JdbcSchemaExec
import uk.xa0.tulkki.data.Schema75Fixture
import uk.xa0.tulkki.data.schema.Schema76
import uk.xa0.tulkki.data.schema.Schema77

/**
 * S5-8's gap-sweep test: the two statements that read the ledger, executed over a schema-77 file
 * reached the way a device reaches it (74 fixture, the legacy 75 step, 76, 77) - the same fixture
 * `SyncDaoTest` uses, and the same reason for it (`docs/MIGRATION.md`, "Design: the data
 * layer" §4.4: Room's runtime cannot open the encrypted file on the host).
 *
 * <p><strong>What the five cells pin.</strong> The gap sweep is the archive half of the engine's
 * read: a row is a candidate only when it is received, still unanswered
 * (`translation_state = 0`), recorded as an archive delivery (`delivery = 1`) and inside
 * a region the ledger still holds open for that account. The live-miss sweep is the floor half:
 * `delivery = 0` only, above that conversation's own `swept_through`, so "a row left
 * alone is left alone" survives per conversation rather than as one global long.
 *
 * <p><strong>The first cell is why the account-wide scope exists.</strong> A `sync_gap` row
 * whose conversation is the empty string is the account-wide catch-up, and for an absence shorter
 * than `Config.MAM_MAX_CATCHUP` that is the only region opened at all - so a sweep that read
 * `conversation_uuid IN (…)` literally matched nothing for the ordinary reconnect and the gap
 * would never have been translated. `SyncQueries.GAP_SWEEP_CANDIDATES` now carries the
 * `EXISTS` that spells the empty string as "every conversation of this account"; this test is
 * the instrument that would have caught it, and the cell opens exactly that region.
 */
class GapSweepQueryTest {

    /**
     * The account-wide region covers every conversation of the account, and only the rows that are
     * received, archive-delivered and unanswered.
     */
    @Test
    fun theGapSweepReturnsOnlyArchiveDeliveredUntranslatedRowsOfThisAccount() {
        val connection = upgraded()
        openAccountWideGap(connection, Schema75Fixture.ACCOUNT)
        markArchived(connection, "m-ordinary", "m-plain", "m-muc", "m-finnish", "m-translated")

        Assert.assertEquals(
                "an answered row is never a candidate - m-finnish is SAME_LANGUAGE and m-translated is"
                        + " TRANSLATION_DONE - while m-covered and m-private keep the UNKNOWN marker"
                        + " schema 76 gave every pre-existing row, which is not an archive delivery",
                setOf("m-ordinary", "m-plain", "m-muc"),
                gapSweep(connection, Schema75Fixture.ACCOUNT))
    }

    /** The archive half is scoped by the account, so one account's pass cannot enqueue another's. */
    @Test
    fun theGapSweepNeverReturnsARowAnotherAccountsPassEnqueued() {
        val connection = upgraded()
        seedSecondAccount(connection)
        openAccountWideGap(connection, Schema75Fixture.ACCOUNT)
        openAccountWideGap(connection, OTHER_ACCOUNT)
        markArchived(connection, "m-ordinary", OTHER_MESSAGE)

        Assert.assertEquals(
                "this account's region returns this account's row",
                setOf("m-ordinary"),
                gapSweep(connection, Schema75Fixture.ACCOUNT))
        Assert.assertEquals(
                "and the other account's region returns its own - the two never cross",
                setOf(OTHER_MESSAGE),
                gapSweep(connection, OTHER_ACCOUNT))
    }

    /**
     * The live-miss half is bounded by one conversation's own floor, which is what keeps a row left
     * alone left alone without suppressing another conversation's rows.
     */
    @Test
    fun theLiveMissSweepIsBoundedByThePerConversationFloor() {
        val connection = upgraded()
        exec(connection, "UPDATE messages SET delivery = 0 WHERE uuid IN ('m-muc','m-ordinary')")

        Assert.assertEquals(
                "above this conversation's floor its row is a candidate, and m-ordinary - a live row"
                        + " of another conversation, above the same value - is not",
                listOf("m-muc"),
                liveSweep(
                        connection,
                        Schema75Fixture.CONVERSATION_MUC,
                        Schema75Fixture.MUC_ANCHOR_TIME - 100L))
        Assert.assertEquals(
                "at the floor nothing is owed: a row that was already decided is not re-read",
                emptyList<String>(),
                liveSweep(
                        connection,
                        Schema75Fixture.CONVERSATION_MUC,
                        Schema75Fixture.MUC_ANCHOR_TIME))
    }

    /** A row the database cannot classify is never a sweep candidate, in either half. */
    @Test
    fun theLiveMissSweepNeverReturnsAnUnknownDeliveryRow() {
        val connection = upgraded()

        Assert.assertEquals(
                "every pre-existing row carries DELIVERY_UNKNOWN, and UNKNOWN is not LIVE",
                emptyList<String>(),
                liveSweep(connection, Schema75Fixture.CONVERSATION_CLEARED, 0L))

        exec(connection, "UPDATE messages SET delivery = 0 WHERE uuid = ?", "m-ordinary")
        Assert.assertEquals(
                "and the same row is a candidate the moment its insert path records a live delivery",
                listOf("m-ordinary"),
                liveSweep(connection, Schema75Fixture.CONVERSATION_CLEARED, 0L))
    }

    /**
     * The never-twice property is read from the row, in both halves: an answered row is not a
     * candidate however it was delivered.
     */
    @Test
    fun aRowAlreadyAnsweredIsNeverASweepCandidate() {
        val connection = upgraded()
        openAccountWideGap(connection, Schema75Fixture.ACCOUNT)
        markArchived(connection, "m-ordinary", "m-finnish", "m-translated")

        Assert.assertEquals(
                "the answered archive rows stay out of the gap sweep, and the unanswered one is in it",
                setOf("m-ordinary"),
                gapSweep(connection, Schema75Fixture.ACCOUNT))

        exec(connection, "UPDATE messages SET delivery = 0 WHERE uuid IN ('m-finnish','m-translated')")
        Assert.assertEquals(
                "and answered live rows stay out of the live-miss sweep for the same reason",
                emptyList<String>(),
                liveSweep(connection, Schema75Fixture.CONVERSATION_CLEARED, 0L))
    }

    // -- the fixture and the machine, the same shape SyncDaoTest uses ------------------------------

    private companion object {

        const val OTHER_ACCOUNT = "acct-2"
        const val OTHER_CONVERSATION = "conv-2"
        const val OTHER_MESSAGE = "m-other"

        val PARAMETER = Pattern.compile(":([A-Za-z][A-Za-z0-9_]*)")

        /** A schema-77 file, reached the way a device reaches it. */
        fun upgraded(): Connection {
            val connection = Schema75Fixture.openWithRows()
            Schema75Fixture.takeTheSchema75Step(connection)
            Schema76.applyUpgrade(JdbcSchemaExec(connection), 4242L)
            Schema77.applySchema(JdbcSchemaExec(connection))
            return connection
        }

        /**
         * The account-wide region: the empty conversation is the account scope (`SyncGapEntity`), and it
         * is the region a short absence opens.
         */
        fun openAccountWideGap(connection: Connection, account: String) {
            exec(
                    connection,
                    "INSERT INTO sync_gap (account_uuid, conversation_uuid, gap_start, gap_end, region,"
                            + " state, reason, opened_at, closed_at) VALUES (?,?,?,?,?,?,?,?,?)",
                    account,
                    "",
                    0L,
                    4242L,
                    0L,
                    0L,
                    null,
                    1L,
                    null)
        }

        /** The write the parser's archive branch makes: this row came from an archive query. */
        fun markArchived(connection: Connection, vararg uuids: String) {
            for (uuid in uuids) {
                exec(connection, "UPDATE messages SET delivery = 1 WHERE uuid = ?", uuid)
            }
        }

        fun seedSecondAccount(connection: Connection) {
            exec(connection, "INSERT INTO accounts (uuid, username) VALUES (?, ?)", OTHER_ACCOUNT, "other")
            exec(
                    connection,
                    "INSERT INTO conversations (uuid, accountUuid, mode) VALUES (?,?,0)",
                    OTHER_CONVERSATION,
                    OTHER_ACCOUNT)
            exec(
                    connection,
                    "INSERT INTO messages (uuid, conversationUuid, timeSent, status, type, serverMsgId,"
                            + " translation_state, translated_body, body, encryption, delivery)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,0,1)",
                    OTHER_MESSAGE,
                    OTHER_CONVERSATION,
                    100L,
                    0,
                    0,
                    null,
                    0,
                    null,
                    "hei")
        }

        /** The account's archive candidates, as a set so the assertion is about rows, not row order. */
        fun gapSweep(connection: Connection, account: String): Set<String> {
            return LinkedHashSet(
                    queryNamed(connection, SyncQueries.GAP_SWEEP_CANDIDATES, "account", account))
        }

        fun liveSweep(connection: Connection, conversation: String, floor: Long): List<String> {
            return queryNamed(
                    connection,
                    SyncQueries.LIVE_MISS_CANDIDATES,
                    "conversation",
                    conversation,
                    "sweptThrough",
                    floor,
                    "limit",
                    5)
        }

        fun exec(connection: Connection, sql: String, vararg args: Any?) {
            try {
                connection.prepareStatement(sql).use { statement ->
                    for (i in args.indices) {
                        statement.setObject(i + 1, args[i])
                    }
                    statement.execute()
                }
            } catch (e: SQLException) {
                throw IllegalStateException("could not execute: " + sql, e)
            }
        }

        /** The first column of every row, bound by the statement's own names in first-appearance order. */
        fun queryNamed(connection: Connection, sql: String, vararg nameValuePairs: Any?): List<String> {
            val out = mutableListOf<String>()
            try {
                connection.prepareStatement(bound(sql)).use { statement ->
                    bind(statement, parametersOf(sql), values(*nameValuePairs))
                    statement.executeQuery().use { results ->
                        while (results.next()) {
                            out.add(results.getString(1))
                        }
                    }
                }
            } catch (e: SQLException) {
                throw IllegalStateException("could not query: " + sql, e)
            }
            return out
        }

        /** Names become `?`; Room's generated SQL binds positionally in first-appearance order. */
        fun bound(sql: String): String {
            return sql.replace(Regex(":[A-Za-z][A-Za-z0-9_]*"), "?")
        }

        fun parametersOf(sql: String): List<String> {
            val names = mutableListOf<String>()
            val matcher = PARAMETER.matcher(sql)
            while (matcher.find()) {
                names.add(matcher.group(1))
            }
            return names
        }

        fun bind(statement: PreparedStatement, names: List<String>, values: Map<String, Any?>) {
            for (i in names.indices) {
                val name = names[i]
                Assert.assertTrue("no value given for :" + name, values.containsKey(name))
                statement.setObject(i + 1, values[name])
            }
        }

        fun values(vararg nameValuePairs: Any?): Map<String, Any?> {
            Assert.assertEquals("the name/value pairs must come in twos", 0, nameValuePairs.size % 2)
            val out = linkedMapOf<String, Any?>()
            for (i in nameValuePairs.indices step 2) {
                Assert.assertTrue("a parameter name must be a String", nameValuePairs[i] is String)
                out[nameValuePairs[i] as String] = nameValuePairs[i + 1]
            }
            return out
        }
    }
}
