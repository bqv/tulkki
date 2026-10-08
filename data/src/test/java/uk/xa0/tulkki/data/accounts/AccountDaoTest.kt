package uk.xa0.tulkki.data.accounts

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.DaoSql
import uk.xa0.tulkki.data.JdbcSchemaExec
import uk.xa0.tulkki.data.KspSchema
import uk.xa0.tulkki.data.RepoFiles
import uk.xa0.tulkki.data.Schema75Fixture
import uk.xa0.tulkki.data.schema.Schema76
import uk.xa0.tulkki.data.schema.Schema77

/**
 * S5-3's {@code accounts/} package test: the account row, its order, and the DDL the package now
 * owns.
 *
 * <p><strong>What this test executes, and what it does not.</strong> It executes the package's own
 * SQL - every {@code @Query} the DAO publishes, plus the {@code CREATE} the package owns - over a
 * schema-77 fixture reached the way a device reaches it, and it executes Room's own generated
 * {@code CREATE} for {@code accounts} and compares the {@code PRAGMA table_info} with the migrated
 * file's, which is what Room's {@code onValidateSchema} compares after the migration. A call through
 * {@code AccountDao_Impl} needs Room's connection machinery and is not executed here - the gap every
 * package test inherits.
 *
 * <p>The table's *shape* is not this package's to change: `Schema76` rebuilt `accounts` (its
 * `options` and `port` were `NUMBER`, which normalises to `UNDEFINED`) in S5-2b, so the entity
 * already validates against the file and this commit moves only the DDL's home.
 */
class AccountDaoTest {

    private companion object {

        private const val DAO_SOURCE =
            "data/src/main/java/uk/xa0/tulkki/data/accounts/AccountDao.kt"

        /** A schema-77 file, reached the way a device reaches it. */
        private fun upgraded(): Connection {
            val connection = Schema75Fixture.openWithRows()
            Schema75Fixture.takeTheSchema75Step(connection)
            Schema76.applyUpgrade(JdbcSchemaExec(connection), 4242L)
            Schema77.applySchema(JdbcSchemaExec(connection))
            return connection
        }

        private fun exec(connection: Connection, sql: String) {
            DaoSql.exec(connection, sql)
        }
    }

    /** The DAO's annotations and the package's constants, read as the same list of statements. */
    @Test
    fun theDaosAnnotationsAndThePackagesConstantsAreTheSameStatements() {
        Assert.assertEquals(
            "every statement AccountDao publishes, in the order it declares them",
            listOf(
                AccountQueries.BY_UUID,
                AccountQueries.ALL_ACCOUNTS,
                AccountQueries.DELETE_BY_UUID),
            DaoSql.queriesIn(RepoFiles.read(DAO_SOURCE)))
    }

    /** One row by key, and the ordered list the owner arranged. */
    @Test
    fun theAccountReadsAnswerByKeyAndInTheOwnersOrder() {
        val connection = upgraded()
        exec(connection, "INSERT INTO accounts (uuid, username, ordering) VALUES ('acct-late', 'b', 5)")
        exec(connection, "INSERT INTO accounts (uuid, username, ordering) VALUES ('acct-early', 'c', 1)")

        Assert.assertEquals(
            "the fixture's own account, by its key",
            Schema75Fixture.ACCOUNT,
            DaoSql.scalarNamed(connection, AccountQueries.BY_UUID, "uuid", Schema75Fixture.ACCOUNT))
        Assert.assertEquals(
            "and an unknown key answers no row",
            emptyList<String>(),
            DaoSql.queryNamed(connection, AccountQueries.BY_UUID, "uuid", "acct-nope"))

        Assert.assertEquals(
            "the list is ordered by `ordering`, and a null ordering sorts first",
            listOf("acct-1", "acct-early", "acct-late"),
            DaoSql.queryNamed(connection, AccountQueries.ALL_ACCOUNTS))
    }

    /** One account gone, and the delete answers the rows it moved. */
    @Test
    fun theDeleteMovesExactlyOneAccount() {
        val connection = upgraded()
        exec(connection, "INSERT INTO accounts (uuid, username) VALUES ('acct-2', 'other')")

        Assert.assertEquals(
            "two accounts to begin with",
            2L,
            DaoSql.scalarLong(connection, "SELECT COUNT(*) FROM accounts"))
        Assert.assertEquals(
            "the delete answers the rows it moved",
            1,
            DaoSql.execNamed(connection, AccountQueries.DELETE_BY_UUID, "uuid", "acct-2"))
        Assert.assertEquals(
            "and a second delete of the same key answers none",
            0,
            DaoSql.execNamed(connection, AccountQueries.DELETE_BY_UUID, "uuid", "acct-2"))
        Assert.assertEquals(
            "one account is left", 1L, DaoSql.scalarLong(connection, "SELECT COUNT(*) FROM accounts"))
    }

    /**
     * Room's own declaration of the table is the file the migration leaves. Room's
     * {@code onValidateSchema} compares each column's {@code notNull} flag and its normalised
     * affinity, so a mismatch here is a refused open on the owner's first launch.
     */
    @Test
    fun roomsOwnDeclarationOfTheTableIsTheFileTheOwnerHas() {
        val tables = mutableListOf<String>()
        for (statement in KspSchema.ddlFor("accounts")) {
            if (statement.startsWith("CREATE TABLE IF NOT EXISTS `accounts`")) {
                tables.add(statement)
            }
        }
        Assert.assertEquals(
            "Room must emit its own CREATE for accounts; anything else means the entity is not " +
                "in the @Database list",
            1,
            tables.size)

        val roomFresh = DriverManager.getConnection("jdbc:sqlite::memory:")
        DaoSql.exec(roomFresh, tables[0])
        val owner = upgraded()
        Assert.assertEquals(
            "Room's accounts and the migrated file's must be the same table",
            Schema75Fixture.tableInfo(owner, "accounts"),
            Schema75Fixture.tableInfo(roomFresh, "accounts"))
    }

    /**
     * The generated implementation, asserted to exist and to carry every statement. An accessor that
     * was dropped would leave a green compile and no {@code _Impl} at all - silently.
     */
    @Test
    fun roomGeneratedTheImplementationForEveryStatement() {
        val generated: Path =
            RepoFiles.root()
                .resolve(
                    "data/build/generated/ksp/debug/kotlin/uk/xa0/tulkki/data/accounts/" +
                        "AccountDao_Impl.kt")
        Assert.assertTrue(
            "Room must have generated AccountDao_Impl, which only the accessor makes happen",
            Files.isRegularFile(generated))
        val text = DaoSql.squeezed(RepoFiles.read(generated))
        Assert.assertTrue(
            "and it must be the generated class, not a hand-written copy",
            text.contains(DaoSql.squeezed("class AccountDao_Impl")))
        for (statement in arrayOf(
            AccountQueries.BY_UUID,
            AccountQueries.ALL_ACCOUNTS,
            AccountQueries.DELETE_BY_UUID)) {
            Assert.assertTrue(
                "the generated implementation must carry the DAO's statement: " + statement,
                text.contains(DaoSql.squeezed(DaoSql.bound(statement))))
        }
        Assert.assertTrue(
            "and the whole-row write the generator emitted from the entity",
            text.contains(DaoSql.squeezed("INSERT OR REPLACE INTO `accounts`")))
    }
}
