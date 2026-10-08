package uk.xa0.tulkki.data.updb

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.DaoSql
import uk.xa0.tulkki.data.KspSchema
import uk.xa0.tulkki.data.RepoFiles
import uk.xa0.tulkki.data.Schema75Fixture

/**
 * S5-3's `updb/` package test: the `push` table, its DAO, and the two facts that make the
 * Room adoption safe.
 *
 * <p><strong>What this test executes, and what it does not.</strong> It executes the package's own
 * SQL - the rebuilt table's DDL through {@link LegacyPreflight}, every statement
 * {@link PushDao} publishes, and the `CREATE`s Room's own generated `createAllTables`
 * emits for the single declared entity - over plain JDBC, because Room's runtime cannot open the
 * *encrypted* file on the host (`docs/MIGRATION.md`, "Design: the data layer" §4.4) and a test that
 * re-spelled the queries would be testing the test. What it does <strong>not</strong> execute is a
 * call through `PushDao_Impl`: that needs Room's connection machinery, and the host harness
 * for it is the gap `BlockingDaoExecutionTest` records. The generated implementation's
 * existence and content are asserted separately, and the raw SQL this test runs is byte for byte
 * the text Room generates for each method -
 * {@link #theDaosAnnotationsAndThePackagesConstantsAreTheSameStatements()} ties the two together.
 *
 * <p><strong>The comparison that matters.</strong> Room validates the adopted file by reading its
 * `PRAGMA table_info` and comparing it with the entity it generated - and it does that on
 * every open, at every `XmppConnectionService` start. {@link
 * #roomsOwnDeclarationAndTheAdoptedTableAreTheSameFile()} therefore executes Room's own DDL and
 * compares the result with the table the preflight leaves, column for column, including the
 * `NUMBER`-to-`INTEGER` change the whole preflight exists for.
 */
class UpdbDaoTest {

    private companion object {

        private const val DAO_SOURCE =
            "data/src/main/java/uk/xa0/tulkki/data/updb/PushDao.kt"

        /** Every statement `PushDao` publishes, in the order it declares them. */
        private val STATEMENTS =
            listOf(
                UpdbQueries.APPLICATION_BY_INSTANCE,
                UpdbQueries.INSERT,
                UpdbQueries.RENEWALS,
                UpdbQueries.ENDPOINT_FOR,
                UpdbQueries.ALL_TARGETS,
                UpdbQueries.DELETE_ALL,
                UpdbQueries.HAS_ENDPOINTS,
                UpdbQueries.ENDPOINT_BY_INSTANCE,
                UpdbQueries.UPDATE_ENDPOINT,
                UpdbQueries.TARGETS_BY_ACCOUNT,
                UpdbQueries.DELETE_INSTANCE,
                UpdbQueries.DELETE_BY_ACCOUNT,
                UpdbQueries.DELETE_APPLICATION)

        /** Register's insert, through the package's own statement. */
        private fun insert(connection: Connection, application: String, instance: String): Int {
            return DaoSql.execNamed(
                connection, UpdbQueries.INSERT, "application", application, "instance", instance)
        }

        /** The second column of every row a statement selected, bound by the statement's own names. */
        private fun secondColumn(
            connection: Connection, sql: String, vararg nameValuePairs: Any?
        ): List<String> {
            val out = mutableListOf<String>()
            try {
                connection.prepareStatement(DaoSql.bound(sql)).use { statement ->
                    DaoSql.bind(statement, DaoSql.parametersOf(sql), DaoSql.values(*nameValuePairs))
                    statement.executeQuery().use { results ->
                        while (results.next()) {
                            out.add(results.getString(2))
                        }
                    }
                }
            } catch (e: SQLException) {
                throw IllegalStateException("could not query: " + sql, e)
            }
            return out
        }
    }

    /**
     * The DAO's annotations and {@link UpdbQueries}' constants, read as the same list of statements.
     * A drift between the two is the defect this exists for, and the generated `_Impl` is then
     * checked against the very same list.
     */
    @Test
    fun theDaosAnnotationsAndThePackagesConstantsAreTheSameStatements() {
        val annotations = DaoSql.queriesIn(RepoFiles.read(DAO_SOURCE))
        Assert.assertEquals(
            "every statement PushDao publishes, in the order it declares them, must be the " +
                "package's own constant - the annotations are what Room compiles, the " +
                "constants are what this test executes",
            STATEMENTS,
            annotations)
    }

    /**
     * Room's declaration of `push`, executed, is the table the preflight leaves.
     *
     * <p>This is also the cell that would catch the defect the adoption is shaped around: a
     * `NUMBER`-declared `expiration` normalises to `UNDEFINED`, the entity can only emit `INTEGER`,
     * and `TableInfo.Column.equalsCommon` compares affinity unconditionally last. The text
     * compared here is the same `PRAGMA table_info` Room's `onValidateSchema` reads.
     */
    @Test
    fun roomsOwnDeclarationAndTheAdoptedTableAreTheSameFile() {
        val ddl = KspSchema.updbDdlFor(UpdbQueries.TABLE, "index_push_instance")
        Assert.assertEquals(
            "Room must emit the table and its unique index for `push`; anything else means the " +
                "entity is not in the @Database list",
            2,
            ddl.size)

        val fresh = UpdbFixture.memory()
        for (statement in ddl) {
            UpdbFixture.exec(fresh, statement)
        }
        val adopted = UpdbFixture.adopted()

        Assert.assertEquals(
            "Room's own DDL and the preflight's rebuild must describe the same push table; a " +
                "difference here is what onValidateSchema refuses on the owner's phone",
            Schema75Fixture.tableInfo(fresh, UpdbQueries.TABLE),
            Schema75Fixture.tableInfo(adopted, UpdbQueries.TABLE))
        Assert.assertTrue(
            "and the affinity must be INTEGER, not the legacy NUMBER that reads UNDEFINED: " +
                Schema75Fixture.tableInfo(adopted, UpdbQueries.TABLE),
            Schema75Fixture.tableInfo(adopted, UpdbQueries.TABLE)
                .contains("expiration|INTEGER|0|0|0"))
        Assert.assertEquals(
            "the entity's unique index must exist in the adopted file under its own name",
            1L,
            DaoSql.scalarLong(
                adopted,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = ?",
                "index_push_instance"))
    }

    /** The reads the distributor makes, over an adopted fixture. */
    @Test
    fun theReadStatementsAnswerWhatTheDistributorReads() {
        val connection = UpdbFixture.adopted()

        Assert.assertEquals(
            "register's existence check finds the instance's application",
            UpdbFixture.APPLICATION_ONE,
            DaoSql.scalarNamed(
                connection,
                UpdbQueries.APPLICATION_BY_INSTANCE,
                "instance",
                UpdbFixture.INSTANCE_ONE))
        Assert.assertEquals(
            "and answers nothing for an instance that was never registered",
            emptyList<String>(),
            DaoSql.queryNamed(
                connection,
                UpdbQueries.APPLICATION_BY_INSTANCE,
                "instance",
                "inst-none"))

        // The renewal sweep: `acct-1`/`tr-1` is current, `inst-2` is another account's, and
        // `inst-3`'s account and transport are NULL - which SQLite evaluates as "not different",
        // exactly as the legacy concatenated WHERE did.
        Assert.assertEquals(
            "the renewal list is every registration that is not this account's current one",
            listOf(UpdbFixture.INSTANCE_TWO),
            secondColumn(
                connection,
                UpdbQueries.RENEWALS,
                "account",
                UpdbFixture.ACCOUNT_ONE,
                "transport",
                UpdbFixture.TRANSPORT_ONE,
                "expiration",
                500L))

        Assert.assertEquals(
            "the endpoint is answered while it is inside the renewal window",
            listOf(UpdbFixture.APPLICATION_ONE),
            DaoSql.queryNamed(
                connection,
                UpdbQueries.ENDPOINT_FOR,
                "account",
                UpdbFixture.ACCOUNT_ONE,
                "transport",
                UpdbFixture.TRANSPORT_ONE,
                "instance",
                UpdbFixture.INSTANCE_ONE,
                "expiration",
                500L))
        Assert.assertEquals(
            "and nothing once the window has passed it",
            emptyList<String>(),
            DaoSql.queryNamed(
                connection,
                UpdbQueries.ENDPOINT_FOR,
                "account",
                UpdbFixture.ACCOUNT_ONE,
                "transport",
                UpdbFixture.TRANSPORT_ONE,
                "instance",
                UpdbFixture.INSTANCE_ONE,
                "expiration",
                5000L))

        Assert.assertEquals(
            "hasEndpoints is 1 for the account that has a row",
            "1",
            DaoSql.scalarNamed(
                connection,
                UpdbQueries.HAS_ENDPOINTS,
                "account",
                UpdbFixture.ACCOUNT_ONE,
                "transport",
                UpdbFixture.TRANSPORT_ONE))
        Assert.assertEquals(
            "1 as well for a row whose endpoint is NULL, because the legacy statement is an " +
                "EXISTS over the row and not over the endpoint - kept, not tidied",
            "1",
            DaoSql.scalarNamed(
                connection,
                UpdbQueries.HAS_ENDPOINTS,
                "account",
                UpdbFixture.ACCOUNT_TWO,
                "transport",
                UpdbFixture.TRANSPORT_TWO))
        Assert.assertEquals(
            "and 0 for an account with no registration at all",
            "0",
            DaoSql.scalarNamed(
                connection,
                UpdbQueries.HAS_ENDPOINTS,
                "account",
                "acct-none",
                "transport",
                "tr-none"))

        Assert.assertEquals(
            "getPushTargets answers this account's registrations",
            listOf(UpdbFixture.INSTANCE_ONE),
            secondColumn(
                connection,
                UpdbQueries.TARGETS_BY_ACCOUNT,
                "account",
                UpdbFixture.ACCOUNT_ONE))
        Assert.assertEquals(
            "the bulk read answers every row, which is what deletePushTargets then removes",
            listOf(
                UpdbFixture.INSTANCE_ONE,
                UpdbFixture.INSTANCE_TWO,
                UpdbFixture.INSTANCE_THREE),
            secondColumn(connection, UpdbQueries.ALL_TARGETS))
    }

    /** The writes: the insert, the endpoint update, and the three deletes. */
    @Test
    fun theWriteStatementsChangeExactlyWhatTheySay() {
        val connection = UpdbFixture.adopted()

        Assert.assertEquals(
            "register inserts a new registration", 1, insert(connection, "app-9", "inst-9"))
        Assert.assertEquals(
            "and a second insert of the same instance is refused by the unique guarantee",
            true,
            DaoSql.fails(
                connection,
                "INSERT INTO push (application, instance) VALUES (?, ?)",
                "app-x",
                UpdbFixture.INSTANCE_ONE))
        Assert.assertEquals(
            "so the table has four rows, not five",
            4L,
            DaoSql.scalarLong(connection, "SELECT COUNT(*) FROM push"))

        Assert.assertNull(
            "the endpoint read answers NULL for a registration whose endpoint was never set",
            DaoSql.scalarNamed(
                connection,
                UpdbQueries.ENDPOINT_BY_INSTANCE,
                "instance",
                UpdbFixture.INSTANCE_THREE))
        Assert.assertEquals(
            "the endpoint write moves one row",
            1,
            DaoSql.execNamed(
                connection,
                UpdbQueries.UPDATE_ENDPOINT,
                "instance",
                UpdbFixture.INSTANCE_TWO,
                "account",
                UpdbFixture.ACCOUNT_TWO,
                "transport",
                UpdbFixture.TRANSPORT_TWO,
                "endpoint",
                "https://push.example/2",
                "expiration",
                9000L))
        Assert.assertEquals(
            "and the row now carries it, under the same instance",
            "https://push.example/2",
            DaoSql.scalarNamed(
                connection,
                UpdbQueries.ENDPOINT_BY_INSTANCE,
                "instance",
                UpdbFixture.INSTANCE_TWO))

        Assert.assertEquals(
            "unregistering an instance removes its row, once",
            1,
            DaoSql.execNamed(
                connection, UpdbQueries.DELETE_INSTANCE, "instance", UpdbFixture.INSTANCE_THREE))
        Assert.assertEquals(
            "and answers 0 the second time, because there was nothing left to remove",
            0,
            DaoSql.execNamed(
                connection, UpdbQueries.DELETE_INSTANCE, "instance", UpdbFixture.INSTANCE_THREE))

        Assert.assertEquals(
            "unregistering an application removes every registration it owns",
            2,
            DaoSql.execNamed(
                connection,
                UpdbQueries.DELETE_APPLICATION,
                "application",
                UpdbFixture.APPLICATION_ONE))
        Assert.assertEquals(
            "and the bulk unregister takes the rest",
            1,
            DaoSql.execNamed(connection, UpdbQueries.DELETE_ALL))
        Assert.assertEquals(
            "leaving the table empty",
            0L,
            DaoSql.scalarLong(connection, "SELECT COUNT(*) FROM push"))
    }

    /**
     * The generated implementation, asserted to exist and to carry exactly the package's statements.
     * This is the instrument batch 1 taught: a DAO is real only because the database exposes it, and
     * an accessor that was dropped would leave a green compile and no `_Impl` at all -
     * silently.
     */
    @Test
    fun roomGeneratedTheImplementationForEveryStatement() {
        val generated: Path =
            RepoFiles.root()
                .resolve(
                    "data/build/generated/ksp/debug/kotlin/uk/xa0/tulkki/data/updb/" +
                        "PushDao_Impl.kt")
        Assert.assertTrue(
            "Room must have generated PushDao_Impl: a DAO without an accessor on the database is " +
                "inert and silent, and this is the file that proves the accessor worked",
            Files.isRegularFile(generated))
        val text = DaoSql.squeezed(RepoFiles.read(generated))
        Assert.assertTrue(
            "and it must be the generated class, not a hand-written copy",
            text.contains(DaoSql.squeezed("class PushDao_Impl")))
        for (statement in STATEMENTS) {
            Assert.assertTrue(
                "the generated implementation must carry the DAO's statement: " + statement,
                text.contains(DaoSql.squeezed(DaoSql.bound(statement))))
        }
    }
}
