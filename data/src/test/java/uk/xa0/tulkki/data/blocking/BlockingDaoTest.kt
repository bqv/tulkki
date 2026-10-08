package uk.xa0.tulkki.data.blocking

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.regex.Pattern
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.JdbcSchemaExec
import uk.xa0.tulkki.data.RepoFiles
import uk.xa0.tulkki.data.Schema75Fixture
import uk.xa0.tulkki.data.schema.Schema76
import uk.xa0.tulkki.data.schema.Schema77

/**
 * S5-3's `blocking/` DAO test: the table, the four statements, and the two facts the blocklist has
 * to get right.
 *
 * <p><strong>What this test does and does not execute.</strong> It runs the package's own SQL over a
 * schema-77 fixture reached the way a device reaches it - 74 fixture, the legacy 75 step, 76, 77 -
 * because Room's runtime cannot open the *encrypted* file on the host (`docs/MIGRATION.md`, "Design:
 * the data layer" §4.4), and a test that re-spelled the queries would be testing the test. What it
 * does **not** execute is a call through `BlockingDao_Impl`: that needs Room's connection machinery,
 * and the host harness for it is `BlockingDaoExecutionTest`'s (which proves Room's generated
 * `createAllTables` runs through a JDBC driver, and which names the generated-DAO-call gap). The
 * generated implementation's *existence and content* are a separate instrument, and this test carries
 * it: {@link #roomGeneratedTheImplementationForEveryStatement()} reads the KSP output and asserts the
 * four statements are the ones generated.
 *
 * <p>The DAO's annotations are also read as source text and asserted against
 * {@link BlockingQueries}, so a drift between the DAO, the constants and Room's generated code is a
 * red test in either direction.
 */
class BlockingDaoTest {

    @Test
    fun theDaosAnnotationsAndThePackagesConstantsAreTheSameStatements() {
        val matcher =
                Pattern.compile("@Query\\(\"([^\"]*)\"\\)").matcher(RepoFiles.read(DAO_SOURCE))
        val inTheDao = mutableListOf<String>()
        while (matcher.find()) {
            inTheDao.add(matcher.group(1))
        }
        Assert.assertEquals(
                "every statement the DAO publishes, in the order it declares them", 4, inTheDao.size)
        Assert.assertEquals(
                "the DAO's annotations and BlockingQueries must be the same four statements, or the "
                        + "one the test runs is not the one Room would run",
                listOf(
                        BlockingQueries.BLOCKED_BY_ACCOUNT,
                        BlockingQueries.COUNT_FOR,
                        BlockingQueries.BLOCK,
                        BlockingQueries.UNBLOCK),
                inTheDao)
    }

    @Test
    fun blockingIsIdempotentAndScopedToTheAccount() {
        val connection = upgraded()
        seedSecondAccount(connection)

        Assert.assertEquals("the first block inserts", 1, exec(connection, BlockingQueries.BLOCK, Schema75Fixture.ACCOUNT, BLOCKED_JID))
        Assert.assertEquals(
                "blocking the same JID twice is a no-op, not a second row",
                0,
                exec(connection, BlockingQueries.BLOCK, Schema75Fixture.ACCOUNT, BLOCKED_JID))
        Assert.assertEquals(1, count(connection, Schema75Fixture.ACCOUNT, BLOCKED_JID))
        Assert.assertEquals(
                "the blocklist is JID-ordered and holds the one JID",
                listOf(BLOCKED_JID),
                query(connection, BlockingQueries.BLOCKED_BY_ACCOUNT, Schema75Fixture.ACCOUNT))
        Assert.assertEquals(
                "another account is not blocked by this account's row",
                0,
                count(connection, SECOND_ACCOUNT, BLOCKED_JID))
    }

    @Test
    fun unblockingRemovesOnlyThatPair() {
        val connection = upgraded()
        seedSecondAccount(connection)
        exec(connection, BlockingQueries.BLOCK, Schema75Fixture.ACCOUNT, BLOCKED_JID)
        exec(connection, BlockingQueries.BLOCK, Schema75Fixture.ACCOUNT, "someone-else@example.org")
        exec(connection, BlockingQueries.BLOCK, SECOND_ACCOUNT, BLOCKED_JID)

        Assert.assertEquals("one row, and only that one", 1, exec(connection, BlockingQueries.UNBLOCK, Schema75Fixture.ACCOUNT, BLOCKED_JID))
        Assert.assertEquals(0, count(connection, Schema75Fixture.ACCOUNT, BLOCKED_JID))
        Assert.assertEquals(
                "the account's other block survives",
                1,
                count(connection, Schema75Fixture.ACCOUNT, "someone-else@example.org"))
        Assert.assertEquals(
                "and so does the other account's block of the same JID",
                1,
                count(connection, SECOND_ACCOUNT, BLOCKED_JID))
        Assert.assertEquals(
                "unblocking what is not blocked answers zero, not an error",
                0,
                exec(connection, BlockingQueries.UNBLOCK, Schema75Fixture.ACCOUNT, BLOCKED_JID))
    }

    /**
     * The foreign key is the reason an account can be deleted without leaving a blocklist behind,
     * and it is the same `ON DELETE CASCADE` the 76 rebuild's `requireForeignKeysOff` exists for.
     */
    @Test
    fun removingTheAccountRemovesItsBlocklist() {
        val connection = upgraded()
        seedSecondAccount(connection)
        exec(connection, BlockingQueries.BLOCK, Schema75Fixture.ACCOUNT, BLOCKED_JID)
        exec(connection, BlockingQueries.BLOCK, SECOND_ACCOUNT, BLOCKED_JID)
        exec(connection, "PRAGMA foreign_keys = ON", *arrayOfNulls<Any?>(0))

        exec(connection, "DELETE FROM accounts WHERE uuid = ?", Schema75Fixture.ACCOUNT)

        Assert.assertEquals(
                "the deleted account's blocklist must be gone", 0, count(connection, Schema75Fixture.ACCOUNT, BLOCKED_JID))
        Assert.assertEquals(
                "and the other account's must not be", 1, count(connection, SECOND_ACCOUNT, BLOCKED_JID))
    }

    /**
     * §6 step 3's rule for this table: it is created by the shared definition, so the fresh-install
     * path and the migration land on the same DDL. The other tests in this module compare whole
     * `sqlite_master` texts; this one says the 77 table is in that comparison.
     */
    @Test
    fun aFreshInstallAndTheUpgradeAgreeOnTheTable() {
        val fresh = Schema75Fixture.open()
        Schema76.applySchema(JdbcSchemaExec(fresh))
        Schema77.applySchema(JdbcSchemaExec(fresh))

        val migrated = upgraded()

        Assert.assertEquals(
                "the fresh install's and the migrated file's blocked_jids must be the same DDL",
                Schema75Fixture.tableInfo(fresh, "blocked_jids"),
                Schema75Fixture.tableInfo(migrated, "blocked_jids"))
        Assert.assertTrue(
                "the table must be the one the package owns: "
                        + Schema75Fixture.tableInfo(migrated, "blocked_jids"),
                Schema75Fixture.tableInfo(migrated, "blocked_jids")
                        .contains("account_uuid|TEXT|1|null|1"))
        Assert.assertTrue(
                Schema75Fixture.tableInfo(migrated, "blocked_jids").contains("jid|TEXT|1|null|2"))
    }

    /**
     * The generated implementation, asserted to exist and to carry exactly the package's four
     * statements. This is the instrument that closes batch 1's gap: `BlockingDao` is registered by
     * the accessor `HistoryDatabase.blockingDao()` (without which Room generates nothing at all,
     * **silently** - measured), so the KSP output is where "this DAO is real" is checkable without
     * Room's runtime.
     *
     * <p>It is a build-output check, not an execution: the file exists only after KSP has run, which
     * it has before any test. Execution of a generated DAO *method* is `BlockingDaoExecutionTest`'s
     * subject, and it says how far it gets.
     */
    @Test
    fun roomGeneratedTheImplementationForEveryStatement() {
        val generated =
                RepoFiles.root()
                        .resolve(
                                "data/build/generated/ksp/debug/kotlin/uk/xa0/tulkki/data/blocking/"
                                        + "BlockingDao_Impl.kt")
        Assert.assertTrue(
                "Room must have generated BlockingDao_Impl: a DAO without an accessor on the database "
                        + "is inert and silent, and this is the file that proves the accessor worked",
                Files.isRegularFile(generated))
        // `Files.readString` is not on the unit-test classpath (the mockable android jar's
        // `java.nio.file` shadows the JDK's), so text is read the way RepoFiles reads it.
        val text = RepoFiles.read(generated)
        for (statement in arrayOf(
                BlockingQueries.BLOCKED_BY_ACCOUNT, BlockingQueries.COUNT_FOR, BlockingQueries.BLOCK, BlockingQueries.UNBLOCK)) {
            Assert.assertTrue(
                    "the generated implementation must carry the DAO's statement: " + statement,
                    text.contains(statement.replace(":account", "?").replace(":jid", "?")))
        }
        Assert.assertTrue(
                "and it must be the generated class, not a hand-written copy",
                text.contains("class BlockingDao_Impl"))
    }

    private companion object {

        const val DAO_SOURCE =
                "data/src/main/java/uk/xa0/tulkki/data/blocking/BlockingDao.kt"

        const val SECOND_ACCOUNT = "acct-2"

        const val BLOCKED_JID = "spammer@example.org"

        /** A schema-77 file, reached the way a device reaches it. */
        fun upgraded(): Connection {
            val connection = Schema75Fixture.openWithRows()
            Schema75Fixture.takeTheSchema75Step(connection)
            Schema76.applyUpgrade(JdbcSchemaExec(connection), 4242L)
            Schema77.applySchema(JdbcSchemaExec(connection))
            return connection
        }

        fun seedSecondAccount(connection: Connection) {
            exec(connection, "INSERT INTO accounts (uuid, username) VALUES (?, ?)", SECOND_ACCOUNT, "other")
        }

        /** `:name` becomes `?`; the caller binds in the same order the statement names them. */
        fun jdbc(sql: String): String {
            return sql.replace(Regex(":[A-Za-z][A-Za-z0-9_]*"), "?")
        }

        fun exec(connection: Connection, sql: String, vararg args: Any?): Int {
            try {
                return connection.prepareStatement(jdbc(sql)).use { statement ->
                    for (i in args.indices) {
                        statement.setObject(i + 1, args[i])
                    }
                    statement.execute()
                    Math.max(statement.updateCount, 0)
                }
            } catch (e: SQLException) {
                throw IllegalStateException("could not execute: " + sql, e)
            }
        }

        fun count(connection: Connection, account: String, jid: String): Int {
            try {
                return connection.prepareStatement(jdbc(BlockingQueries.COUNT_FOR)).use { statement ->
                    statement.setObject(1, account)
                    statement.setObject(2, jid)
                    statement.executeQuery().use { results ->
                        Assert.assertTrue("the count query must answer one row", results.next())
                        results.getInt(1)
                    }
                }
            } catch (e: SQLException) {
                throw IllegalStateException("could not count", e)
            }
        }

        fun query(connection: Connection, sql: String, vararg args: Any?): List<String> {
            val out = mutableListOf<String>()
            try {
                connection.prepareStatement(jdbc(sql)).use { statement ->
                    for (i in args.indices) {
                        statement.setObject(i + 1, args[i])
                    }
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
    }
}
