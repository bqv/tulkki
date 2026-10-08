package uk.xa0.tulkki.data.roster

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.DaoSql
import uk.xa0.tulkki.data.JdbcSchemaExec
import uk.xa0.tulkki.data.KspSchema
import uk.xa0.tulkki.data.RepoFiles
import uk.xa0.tulkki.data.Schema75Fixture
import uk.xa0.tulkki.data.model.ServiceDiscoveryResult
import uk.xa0.tulkki.data.schema.Schema76
import uk.xa0.tulkki.data.schema.Schema77

/**
 * S5-3's `roster/` package test: the account's contacts, the discovery cache, the roster
 * version on `accounts`, and the schema-77 rebuild the two tables needed.
 *
 * <strong>What this test executes, and what it does not.</strong> It executes the package's own
 * SQL - every `@Query` the DAO publishes, plus the package's two `CREATE`s - over a
 * schema-77 fixture reached the way a device reaches it, and it executes the rebuild itself over a
 * fixture with rows in it, because "the copy kept every row and every rowid" is the claim the
 * rebuild's whole risk sits on. It also executes Room's own generated `CREATE`s for
 * `contacts` and `discovery_results` and compares their `PRAGMA table_info` with
 * the rebuilt file's - which is what Room's `onValidateSchema` compares after the migration,
 * and a mismatch there is a refused open on the owner's phone rather than a warning.
 *
 * <strong>Two things it cannot execute, and says so.</strong> A call through
 * `RosterDao_Impl` needs Room's connection machinery - the gap every package test inherits -
 * and the rows the rebuild carries across are the ones this test puts there, not the owner's. The
 * reads are asserted on a named column rather than the first one, because the DAO's roster read is
 * `SELECT *` and its first column is the surrogate key.
 */
class RosterDaoTest {

    /** The DAO's annotations and the package's constants, read as the same list of statements. */
    @Test
    fun theDaosAnnotationsAndThePackagesConstantsAreTheSameStatements() {
        Assert.assertEquals(
                "every statement RosterDao publishes, in the order it declares them",
                listOf(
                        RosterQueries.CONTACTS_FOR_ACCOUNT,
                        RosterQueries.CONTACT_BY_JID,
                        RosterQueries.REMOVE_CONTACT,
                        RosterQueries.REMOVE_CONTACTS_FOR_ACCOUNT,
                        RosterQueries.DISCOVERY_BY_KEY,
                        RosterQueries.SAVE_DISCOVERY_RESULT,
                        RosterQueries.ROSTER_VERSION_OF,
                        RosterQueries.SET_ROSTER_VERSION),
                DaoSql.queriesIn(RepoFiles.read(DAO_SOURCE)))
    }

    /** The roster read is one account's, and only that account's. */
    @Test
    fun theRosterReadIsScopedToTheAccountAndInAStableOrder() {
        val connection = upgraded()
        addContact(connection, Schema75Fixture.ACCOUNT, "zed@example.org")
        addContact(connection, Schema75Fixture.ACCOUNT, "anna@example.org")
        addContact(connection, "acct-2", "other@example.org")

        Assert.assertEquals(
                "one account's contacts, ordered by jid",
                listOf("anna@example.org", "zed@example.org"),
                columnNamed(
                        connection,
                        RosterQueries.CONTACTS_FOR_ACCOUNT,
                        "jid",
                        "account",
                        Schema75Fixture.ACCOUNT))
        Assert.assertEquals(
                "and the other account's read is its own",
                listOf("other@example.org"),
                columnNamed(
                        connection, RosterQueries.CONTACTS_FOR_ACCOUNT, "jid", "account", "acct-2"))
        Assert.assertEquals(
                "one contact by the pair its key names",
                listOf("zed@example.org"),
                columnNamed(
                        connection,
                        RosterQueries.CONTACT_BY_JID,
                        "jid",
                        "account",
                        Schema75Fixture.ACCOUNT,
                        "jid",
                        "zed@example.org"))
        Assert.assertEquals(
                "and nothing for a jid the account has no row for",
                emptyList<String>(),
                columnNamed(
                        connection,
                        RosterQueries.CONTACT_BY_JID,
                        "jid",
                        "account",
                        Schema75Fixture.ACCOUNT,
                        "jid",
                        "nobody@example.org"))
    }

    /** The pair is the identity: a second write replaces the row rather than adding a second one. */
    @Test
    fun thePairIsTheIdentityAndARemovalIsOneRowOfOneAccount() {
        val connection = upgraded()
        addContact(connection, Schema75Fixture.ACCOUNT, "anna@example.org")
        addContact(connection, Schema75Fixture.ACCOUNT, "anna@example.org")
        Assert.assertEquals(
                "the same pair twice is one row, because the rebuild kept the UNIQUE",
                1L,
                DaoSql.scalarLong(connection, "SELECT COUNT(*) FROM contacts"))

        addContact(connection, "acct-2", "anna@example.org")
        Assert.assertEquals(
                "and the same jid under another account is its own row",
                2L,
                DaoSql.scalarLong(connection, "SELECT COUNT(*) FROM contacts"))

        Assert.assertEquals(
                "removing one pair moves one row",
                1,
                DaoSql.execNamed(
                        connection,
                        RosterQueries.REMOVE_CONTACT,
                        "account",
                        Schema75Fixture.ACCOUNT,
                        "jid",
                        "anna@example.org"))
        Assert.assertEquals(
                "and the other account's row is untouched",
                listOf("anna@example.org"),
                columnNamed(
                        connection, RosterQueries.CONTACTS_FOR_ACCOUNT, "jid", "account", "acct-2"))

        addContact(connection, "acct-2", "second@example.org")
        Assert.assertEquals(
                "the account-wide removal answers the rows it moved",
                2,
                DaoSql.execNamed(
                        connection,
                        RosterQueries.REMOVE_CONTACTS_FOR_ACCOUNT,
                        "account",
                        "acct-2"))
        Assert.assertEquals(
                "and leaves nothing behind for that account",
                0L,
                DaoSql.scalarLong(
                        connection, "SELECT COUNT(*) FROM contacts WHERE accountUuid = ?", "acct-2"))
    }

    /** The discovery cache is keyed by the pair, replaced rather than duplicated. */
    @Test
    fun theDiscoveryCacheIsKeyedByHashAndVersion() {
        val connection = upgraded()
        Assert.assertEquals(
                "nothing is cached to begin with",
                0L,
                DaoSql.scalarLong(connection, "SELECT COUNT(*) FROM discovery_results"))

        Assert.assertEquals(
                "the first save writes a row",
                1,
                DaoSql.execNamed(
                        connection,
                        RosterQueries.SAVE_DISCOVERY_RESULT,
                        "hash",
                        "h1",
                        "ver",
                        "v1",
                        "result",
                        "<query/>"))
        Assert.assertEquals(
                "the same pair replaces it",
                1,
                DaoSql.execNamed(
                        connection,
                        RosterQueries.SAVE_DISCOVERY_RESULT,
                        "hash",
                        "h1",
                        "ver",
                        "v1",
                        "result",
                        "<query2/>"))
        exec(connection, RosterQueries.SAVE_DISCOVERY_RESULT, "h1", "v2", "<second-version/>")
        Assert.assertEquals(
                "one row per pair",
                2L,
                DaoSql.scalarLong(connection, "SELECT COUNT(*) FROM discovery_results"))

        Assert.assertEquals(
                "the read answers the row's own result",
                listOf("<query2/>"),
                columnNamed(
                        connection,
                        RosterQueries.DISCOVERY_BY_KEY,
                        "result",
                        "hash",
                        "h1",
                        "ver",
                        "v1"))
        Assert.assertEquals(
                "and a pair that was never cached answers no row",
                emptyList<String>(),
                columnNamed(
                        connection, RosterQueries.DISCOVERY_BY_KEY, "result", "hash", "h9", "ver", "v9"))
    }

    /** The roster version is a column of `accounts`, read and moved by this package. */
    @Test
    fun theRosterVersionIsReadAndMovedOnTheAccount() {
        val connection = upgraded()
        Assert.assertNull(
                "a fixture account has no roster version yet",
                DaoSql.scalarNamed(
                        connection, RosterQueries.ROSTER_VERSION_OF, "account", Schema75Fixture.ACCOUNT))
        Assert.assertEquals(
                "writing it answers one row",
                1,
                DaoSql.execNamed(
                        connection,
                        RosterQueries.SET_ROSTER_VERSION,
                        "account",
                        Schema75Fixture.ACCOUNT,
                        "version",
                        "ver-42"))
        Assert.assertEquals(
                "and the read answers it",
                "ver-42",
                DaoSql.scalarNamed(
                        connection, RosterQueries.ROSTER_VERSION_OF, "account", Schema75Fixture.ACCOUNT))
    }

    /**
     * The rebuild carries every row and every rowid across, which is the whole risk of a
     * create-copy-drop-rename. The surrogate `_id` is the copy of the old `rowid`, so a row's
     * identity survives the rebuild as well as its columns.
     */
    @Test
    fun theRebuildCarriesEveryRowAndEveryRowidAcross() {
        val connection = Schema75Fixture.openWithRows()
        exec(
                connection,
                "INSERT INTO contacts (accountUuid, jid, options, callsDisabled, groups) " +
                        "VALUES (?, ?, ?, ?, ?)",
                Schema75Fixture.ACCOUNT,
                "first@example.org",
                3L,
                1L,
                "Friends")
        exec(
                connection,
                "INSERT INTO contacts (accountUuid, jid, options) VALUES (?, ?, ?)",
                Schema75Fixture.ACCOUNT,
                "second@example.org",
                0L)
        exec(
                connection,
                "INSERT INTO discovery_results (hash, ver, result) VALUES (?, ?, ?)",
                "h",
                "v",
                "<query/>")

        val contactsBefore = Schema75Fixture.dumpRows(connection, "contacts", CONTACT_COLUMNS)
        val discoveryBefore = Schema75Fixture.dumpRows(connection, "discovery_results", DISCOVERY_COLUMNS)
        val identities = columnNamed(connection, "SELECT rowid, jid FROM contacts ORDER BY rowid", "jid")

        Schema77.applySchema(JdbcSchemaExec(connection))

        Assert.assertEquals(
                "no contact row's columns may change across the rebuild",
                contactsBefore,
                Schema75Fixture.dumpRows(connection, "contacts", CONTACT_COLUMNS))
        Assert.assertEquals(
                "and no cached discovery result may be lost",
                discoveryBefore,
                Schema75Fixture.dumpRows(connection, "discovery_results", DISCOVERY_COLUMNS))
        Assert.assertEquals(
                "the surrogate key is the rowid the row already had",
                identities,
                columnNamed(connection, "SELECT _id, jid FROM contacts ORDER BY _id", "jid"))
        Assert.assertEquals(
                "and the key equals that rowid numerically",
                listOf("1", "2"),
                columnNamed(connection, "SELECT _id, jid FROM contacts ORDER BY _id", "_id"))
        Assert.assertEquals(
                "and the cached discovery row's key is its rowid too",
                listOf("1"),
                columnNamed(
                        connection, "SELECT _id, hash FROM discovery_results ORDER BY _id", "_id"))
    }

    /**
     * Room's own declaration of the two tables is the file the migration leaves. Room's
     * `onValidateSchema` compares each column's `notNull` flag, its normalised affinity
     * and its primary key, so a mismatch here is a refused open on the owner's first launch.
     */
    @Test
    fun roomsOwnDeclarationsOfTheTwoTablesAreTheFilesTheOwnerHas() {
        val tables = mutableListOf<String>()
        for (statement in KspSchema.ddlFor("contacts", "discovery_results")) {
            if (statement.startsWith("CREATE TABLE IF NOT EXISTS `contacts`") ||
                    statement.startsWith("CREATE TABLE IF NOT EXISTS `discovery_results`")) {
                tables.add(statement)
            }
        }
        Assert.assertEquals(
                "Room must emit its own CREATE for both tables; anything else means an entity is not " +
                        "in the @Database list",
                2,
                tables.size)

        val roomFresh = DriverManager.getConnection("jdbc:sqlite::memory:")
        for (statement in tables) {
            DaoSql.exec(roomFresh, statement)
        }
        val owner = upgraded()
        Assert.assertEquals(
                "Room's contacts and the rebuilt file's must be the same table",
                Schema75Fixture.tableInfo(owner, "contacts"),
                Schema75Fixture.tableInfo(roomFresh, "contacts"))
        Assert.assertEquals(
                "Room's discovery_results and the rebuilt file's must be the same table",
                Schema75Fixture.tableInfo(owner, "discovery_results"),
                Schema75Fixture.tableInfo(roomFresh, "discovery_results"))
    }

    /**
     * The generated implementation, asserted to exist and to carry every statement. An accessor that
     * was dropped would leave a green compile and no `_Impl` at all - silently.
     */
    @Test
    fun roomGeneratedTheImplementationForEveryStatement() {
        val generated: Path =
                RepoFiles.root()
                        .resolve(
                                "data/build/generated/ksp/debug/kotlin/uk/xa0/tulkki/data/roster/" +
                                        "RosterDao_Impl.kt")
        Assert.assertTrue(
                "Room must have generated RosterDao_Impl, which only the accessor makes happen",
                Files.isRegularFile(generated))
        val text = DaoSql.squeezed(RepoFiles.read(generated))
        Assert.assertTrue(
                "and it must be the generated class, not a hand-written copy",
                text.contains(DaoSql.squeezed("class RosterDao_Impl")))
        for (statement in arrayOf(
                RosterQueries.CONTACTS_FOR_ACCOUNT,
                RosterQueries.CONTACT_BY_JID,
                RosterQueries.REMOVE_CONTACT,
                RosterQueries.REMOVE_CONTACTS_FOR_ACCOUNT,
                RosterQueries.DISCOVERY_BY_KEY,
                RosterQueries.SAVE_DISCOVERY_RESULT,
                RosterQueries.ROSTER_VERSION_OF,
                RosterQueries.SET_ROSTER_VERSION,
        )) {
            Assert.assertTrue(
                    "the generated implementation must carry the DAO's statement: " + statement,
                    text.contains(DaoSql.squeezed(DaoSql.bound(statement))))
        }
        Assert.assertTrue(
                "and the whole-row contact write the generator emitted from the entity",
                text.contains(DaoSql.squeezed("INSERT OR REPLACE INTO `contacts`")))
    }

    private companion object {
        private const val DAO_SOURCE =
                "data/src/main/java/uk/xa0/tulkki/data/roster/RosterDao.kt"

        private val CONTACT_COLUMNS =
                listOf(
                        "accountUuid",
                        "servername",
                        "systemname",
                        "presence_name",
                        "jid",
                        "pgpkey",
                        "photouri",
                        "options",
                        "systemaccount",
                        "avatar",
                        "last_presence",
                        "callsDisabled",
                        "last_time",
                        "rtpCapability",
                        "groups")

        private val DISCOVERY_COLUMNS =
                listOf(
                        ServiceDiscoveryResult.HASH,
                        ServiceDiscoveryResult.VER,
                        ServiceDiscoveryResult.RESULT)

        /** A schema-77 file, reached the way a device reaches it. */
        private fun upgraded(): Connection {
            val connection = Schema75Fixture.openWithRows()
            Schema75Fixture.takeTheSchema75Step(connection)
            Schema76.applyUpgrade(JdbcSchemaExec(connection), 4242L)
            Schema77.applySchema(JdbcSchemaExec(connection))
            return connection
        }

        private fun addContact(connection: Connection, account: String, jid: String) {
            exec(
                    connection,
                    "INSERT OR REPLACE INTO contacts (accountUuid, jid) VALUES (?, ?)",
                    account,
                    jid)
        }

        /** One named column of every row a statement selected, bound by the statement's own names. */
        private fun columnNamed(
                connection: Connection, sql: String, column: String, vararg nameValuePairs: Any?): List<String> {
            val out = mutableListOf<String>()
            try {
                connection.prepareStatement(DaoSql.bound(sql)).use { statement ->
                    DaoSql.bind(statement, DaoSql.parametersOf(sql), DaoSql.values(*nameValuePairs))
                    statement.executeQuery().use { results ->
                        while (results.next()) {
                            out.add(results.getString(column))
                        }
                    }
                }
            } catch (e: SQLException) {
                throw IllegalStateException("could not query: " + sql, e)
            }
            return out
        }

        private fun exec(connection: Connection, sql: String, vararg args: Any?) {
            DaoSql.exec(connection, sql, *args)
        }
    }
}
