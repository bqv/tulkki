package uk.xa0.tulkki.data.omemo

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
import uk.xa0.tulkki.data.schema.Schema76
import uk.xa0.tulkki.data.schema.Schema77

/**
 * S5-3's `omemo/` package test: the OMEMO session store's four tables, the statements the DAO
 * publishes, and the schema-77 rebuild they needed.
 *
 * <p><strong>What this test executes, and what it does not.</strong> It executes every statement the
 * DAO publishes over a schema-77 fixture reached the way a device reaches it; it executes each
 * table's live write path - the `REPLACE` upsert on the file's own `UNIQUE`, the deletes, the
 * identity status/trust/certificate writes; it executes the rebuild over a fixture <em>with rows in
 * all four tables</em>, including a row whose columns are null, and compares every legacy column,
 * every surrogate `_id` against the rowid the row already had, and every row's presence; and it
 * executes Room's own generated `CREATE`s and compares `PRAGMA table_info` with the
 * rebuilt file's - which is what Room's `onValidateSchema` compares after the migration. It
 * does not execute a call through `OmemoStoreDao_Impl`: that needs Room's connection machinery
 * and is the gap every package test inherits. `RoomValidationTest` is the second instrument and
 * covers these four entities automatically, because they are declared in the one `@Database`.
 */
class OmemoStoreDaoTest {

    /** The DAO's annotations and the package's constants, read as the same list of statements. */
    @Test
    fun theDaosAnnotationsAndThePackagesConstantsAreTheSameStatements() {
        Assert.assertEquals(
                "every statement OmemoStoreDao publishes, in the order it declares them",
                listOf(
                        OmemoQueries.SESSION_BY_ADDRESS,
                        OmemoQueries.SESSION_COUNT_BY_ADDRESS,
                        OmemoQueries.SESSION_DEVICE_IDS,
                        OmemoQueries.SESSION_NAMES,
                        OmemoQueries.UPSERT_SESSION,
                        OmemoQueries.DELETE_SESSION,
                        OmemoQueries.DELETE_SESSIONS_BY_NAME,
                        OmemoQueries.DELETE_SESSIONS_BY_ACCOUNT,
                        OmemoQueries.PREKEY_KEY,
                        OmemoQueries.UPSERT_PREKEY,
                        OmemoQueries.DELETE_PREKEY,
                        OmemoQueries.DELETE_PREKEYS_BY_ACCOUNT,
                        OmemoQueries.SIGNED_PREKEY_KEY,
                        OmemoQueries.SIGNED_PREKEY_KEYS,
                        OmemoQueries.SIGNED_PREKEY_COUNT,
                        OmemoQueries.UPSERT_SIGNED_PREKEY,
                        OmemoQueries.DELETE_SIGNED_PREKEY,
                        OmemoQueries.DELETE_SIGNED_PREKEYS_BY_ACCOUNT,
                        OmemoQueries.IDENTITY_STATUS,
                        OmemoQueries.IDENTITY_STATUS_BY_FINGERPRINT,
                        OmemoQueries.IDENTITY_TRUSTED_COUNT,
                        OmemoQueries.INSERT_IDENTITY,
                        OmemoQueries.UPDATE_IDENTITY,
                        OmemoQueries.UPDATE_IDENTITY_STATUS,
                        OmemoQueries.IDENTITY_CERTIFICATE,
                        OmemoQueries.UPDATE_IDENTITY_CERTIFICATE,
                        OmemoQueries.DELETE_IDENTITIES_BY_ACCOUNT),
                DaoSql.queriesIn(RepoFiles.read(DAO_SOURCE)))
    }

    /** The session is keyed by the address, and the upsert `REPLACE`s on the triple. */
    @Test
    fun theSessionsAreKeyedByTheAddressAndTheUpsertReplaces() {
        val connection = upgraded()
        upsertSession(connection, ACCOUNT, "bob@example.org", 1L, "session-one")
        Assert.assertEquals(
                "the address is the identity",
                listOf("session-one"),
                keyColumn(connection, ACCOUNT, "bob@example.org", 1L))

        upsertSession(connection, ACCOUNT, "bob@example.org", 1L, "session-two")
        Assert.assertEquals(
                "the same address twice is one row, because the rebuild kept the UNIQUE",
                1L,
                count(connection, "sessions"))
        Assert.assertEquals(
                "and the row is the new value",
                listOf("session-two"),
                keyColumn(connection, ACCOUNT, "bob@example.org", 1L))

        upsertSession(connection, ACCOUNT, "bob@example.org", 2L, "session-device-2")
        upsertSession(connection, ACCOUNT, "carol@example.org", 1L, "session-carol")
        Assert.assertEquals(
                "containsSession asks the same row for its count",
                "1",
                DaoSql.scalarNamed(
                        connection,
                        OmemoQueries.SESSION_COUNT_BY_ADDRESS,
                        "account",
                        ACCOUNT,
                        "name",
                        "bob@example.org",
                        "deviceId",
                        2L))
        Assert.assertEquals(
                "getSubDeviceSessions lists one address's devices",
                listOf("1", "2"),
                column(
                        connection,
                        OmemoQueries.SESSION_DEVICE_IDS,
                        "device_id",
                        "account",
                        ACCOUNT,
                        "name",
                        "bob@example.org"))
        Assert.assertEquals(
                "getKnownSignalAddresses lists the account's names, once each",
                listOf("bob@example.org", "carol@example.org"),
                column(connection, OmemoQueries.SESSION_NAMES, "name", "account", ACCOUNT))

        Assert.assertEquals(
                "deleteSession is one address's row",
                1,
                DaoSql.execNamed(
                        connection,
                        OmemoQueries.DELETE_SESSION,
                        "account",
                        ACCOUNT,
                        "name",
                        "bob@example.org",
                        "deviceId",
                        2L))
        Assert.assertEquals("two rows left", 2L, count(connection, "sessions"))
        Assert.assertEquals(
                "deleteAllSessions is every device of one name",
                1,
                DaoSql.execNamed(
                        connection,
                        OmemoQueries.DELETE_SESSIONS_BY_NAME,
                        "account",
                        ACCOUNT,
                        "name",
                        "bob@example.org"))
        Assert.assertEquals("one row left", 1L, count(connection, "sessions"))
        Assert.assertEquals(
                "wipeAxolotlDb is the account's own rows",
                1,
                DaoSql.execNamed(
                        connection, OmemoQueries.DELETE_SESSIONS_BY_ACCOUNT, "account", ACCOUNT))
        Assert.assertEquals("and none for that account", 0L, count(connection, "sessions"))
    }

    /** The two pre-key tables: the pair is the identity and the count ignores a `NULL` key. */
    @Test
    fun theKeysAreKeyedByThePairAndTheCountIgnoresANullKey() {
        val connection = upgraded()
        upsertPreKey(connection, ACCOUNT, 7L, "prekey-seven")
        upsertPreKey(connection, ACCOUNT, 7L, "prekey-seven-again")
        upsertPreKey(connection, OTHER_ACCOUNT, 7L, "other-account")
        Assert.assertEquals(
                "REPLACE on UNIQUE(account, id): two rows, one per account",
                2L,
                count(connection, "prekeys"))
        Assert.assertEquals(
                "and the key is read by the pair the table's UNIQUE names",
                "prekey-seven-again",
                DaoSql.scalarNamed(
                        connection, OmemoQueries.PREKEY_KEY, "account", ACCOUNT, "id", 7L))
        Assert.assertEquals(
                "deletePreKey is the pair",
                1,
                DaoSql.execNamed(
                        connection, OmemoQueries.DELETE_PREKEY, "account", ACCOUNT, "id", 7L))
        Assert.assertEquals(
                "wipeAxolotlDb is the account",
                1,
                DaoSql.execNamed(
                        connection,
                        OmemoQueries.DELETE_PREKEYS_BY_ACCOUNT,
                        "account",
                        OTHER_ACCOUNT))

        upsertSignedPreKey(connection, ACCOUNT, 11L, "signed-eleven")
        upsertSignedPreKey(connection, ACCOUNT, 12L, null)
        Assert.assertEquals(
                "getSignedPreKeysCount counts `key`, so a NULL key is not one of them",
                "1",
                DaoSql.scalarNamed(
                        connection, OmemoQueries.SIGNED_PREKEY_COUNT, "account", ACCOUNT))
        Assert.assertEquals(
                "loadSignedPreKeys reads every non-null key the account holds",
                listOf("signed-eleven"),
                nonNull(
                        column(
                                connection,
                                OmemoQueries.SIGNED_PREKEY_KEYS,
                                "key",
                                "account",
                                ACCOUNT)))
        Assert.assertEquals(
                "loadSignedPreKey is the pair",
                "signed-eleven",
                DaoSql.scalarNamed(
                        connection,
                        OmemoQueries.SIGNED_PREKEY_KEY,
                        "account",
                        ACCOUNT,
                        "id",
                        11L))
        Assert.assertEquals(
                "deleteSignedPreKey is the pair",
                1,
                DaoSql.execNamed(
                        connection,
                        OmemoQueries.DELETE_SIGNED_PREKEY,
                        "account",
                        ACCOUNT,
                        "id",
                        11L))
        Assert.assertEquals(
                "wipeAxolotlDb is the account",
                1,
                DaoSql.execNamed(
                        connection,
                        OmemoQueries.DELETE_SIGNED_PREKEYS_BY_ACCOUNT,
                        "account",
                        ACCOUNT))
    }

    /** The identity's status, its trust change and its certificate all move. */
    @Test
    fun theIdentitiesHoldStatusTrustAndCertificate() {
        val connection = upgraded()
        insertIdentity(
                connection, ACCOUNT, "bob@example.org", 0L, "AA:BB", "key-bytes", "UNDECIDED", 1L)
        Assert.assertEquals(
                "the status read is the name-and-own pair, and it projects four columns",
                listOf("UNDECIDED|1|null|key-bytes"),
                projection(
                        connection,
                        OmemoQueries.IDENTITY_STATUS,
                        arrayOf("trust", "active", "last_activation", "key"),
                        "account",
                        ACCOUNT,
                        "name",
                        "bob@example.org",
                        "ownKey",
                        0L))
        Assert.assertEquals(
                "and there is no other-key row to confuse it with",
                emptyList<String>(),
                projection(
                        connection,
                        OmemoQueries.IDENTITY_STATUS,
                        arrayOf("trust"),
                        "account",
                        ACCOUNT,
                        "name",
                        "bob@example.org",
                        "ownKey",
                        1L))

        Assert.assertEquals(
                "setIdentityKeyTrust moves the two columns a trust change carries",
                1,
                DaoSql.execNamed(
                        connection,
                        OmemoQueries.UPDATE_IDENTITY_STATUS,
                        "account",
                        ACCOUNT,
                        "fingerprint",
                        "AA:BB",
                        "trust",
                        "VERIFIED",
                        "active",
                        1L))
        Assert.assertEquals(
                "numTrustedKeys counts trusted or verified and active",
                "1",
                DaoSql.scalarNamed(
                        connection,
                        OmemoQueries.IDENTITY_TRUSTED_COUNT,
                        "account",
                        ACCOUNT,
                        "name",
                        "bob@example.org",
                        "trusted",
                        "TRUSTED",
                        "verified",
                        "VERIFIED",
                        "verifiedX509",
                        "VERIFIED_X509"))
        Assert.assertEquals(
                "and getFingerprintStatus reads the same row by fingerprint",
                listOf("VERIFIED|1"),
                projection(
                        connection,
                        OmemoQueries.IDENTITY_STATUS_BY_FINGERPRINT,
                        arrayOf("trust", "active"),
                        "account",
                        ACCOUNT,
                        "fingerprint",
                        "AA:BB"))

        val certificate = byteArrayOf(1, 2, 3, 4)
        Assert.assertEquals(
                "setIdentityKeyCertificate writes the DER bytes",
                1,
                DaoSql.execNamed(
                        connection,
                        OmemoQueries.UPDATE_IDENTITY_CERTIFICATE,
                        "account",
                        ACCOUNT,
                        "fingerprint",
                        "AA:BB",
                        "certificate",
                        certificate))
        Assert.assertArrayEquals(
                "and getIdentityKeyCertifcate reads them back",
                certificate,
                DaoSql.scalarObject(
                                connection,
                                DaoSql.bound(OmemoQueries.IDENTITY_CERTIFICATE),
                                ACCOUNT,
                                "AA:BB")
                        as ByteArray)

        Assert.assertEquals(
                "storeIdentityKey's update branch is the (account, name, fingerprint) triple",
                1,
                DaoSql.execNamed(
                        connection,
                        OmemoQueries.UPDATE_IDENTITY,
                        "account",
                        ACCOUNT,
                        "name",
                        "bob@example.org",
                        "ownKey",
                        0L,
                        "fingerprint",
                        "AA:BB",
                        "key",
                        "new-key-bytes",
                        "trust",
                        "VERIFIED",
                        "active",
                        1L))
        Assert.assertEquals(
                "and it left the certificate alone",
                4,
                (DaoSql.scalarObject(
                                        connection,
                                        DaoSql.bound(OmemoQueries.IDENTITY_CERTIFICATE),
                                        ACCOUNT,
                                        "AA:BB")
                                as ByteArray)
                        .size)

        Assert.assertEquals(
                "wipeAxolotlDb is the account's own rows",
                1,
                DaoSql.execNamed(
                        connection, OmemoQueries.DELETE_IDENTITIES_BY_ACCOUNT, "account", ACCOUNT))
        Assert.assertEquals("and none for that account", 0L, count(connection, "identities"))
    }

    /**
     * The rebuild carries every row, every column and every rowid across, and Room's own declaration
     * of each table is what it leaves. Room's `onValidateSchema` compares each column's
     * `notNull` flag, its normalised affinity and its key, so a mismatch here is a refused open
     * on the owner's first launch.
     */
    @Test
    fun theRebuildCarriesEveryRowAndRoomAcceptsWhatItLeaves() {
        val connection = Schema75Fixture.open()
        legacyIdentity(
                connection, ACCOUNT, "bob@example.org", 0L, "AA:BB", "UNDECIDED", 1L, 3L, "key-bytes")
        // A row with the nulls the file allows, because a `NOT NULL` surrogate key must not make the
        // copy throw on one.
        legacyIdentity(connection, ACCOUNT, null, null, null, null, null, null, null)
        legacySession(connection, ACCOUNT, "bob@example.org", 1L, "session-one")
        legacyPreKey(connection, ACCOUNT, 7L, "prekey-seven")
        legacySignedPreKey(connection, ACCOUNT, 11L, "signed-eleven")

        val identitiesBefore = Schema75Fixture.dumpRows(connection, "identities", IDENTITY_COLUMNS)
        val sessionsBefore = Schema75Fixture.dumpRows(connection, "sessions", SESSION_COLUMNS)
        val prekeysBefore = Schema75Fixture.dumpRows(connection, "prekeys", KEY_COLUMNS)
        val signedBefore = Schema75Fixture.dumpRows(connection, "signed_prekeys", KEY_COLUMNS)

        Schema77.applySchema(JdbcSchemaExec(connection))

        Assert.assertEquals(
                "no identities row's columns may change across the rebuild",
                identitiesBefore,
                Schema75Fixture.dumpRows(connection, "identities", IDENTITY_COLUMNS))
        Assert.assertEquals(
                "no sessions row's columns may change",
                sessionsBefore,
                Schema75Fixture.dumpRows(connection, "sessions", SESSION_COLUMNS))
        Assert.assertEquals(
                "no prekeys row's columns may change",
                prekeysBefore,
                Schema75Fixture.dumpRows(connection, "prekeys", KEY_COLUMNS))
        Assert.assertEquals(
                "no signed_prekeys row's columns may change",
                signedBefore,
                Schema75Fixture.dumpRows(connection, "signed_prekeys", KEY_COLUMNS))

        Assert.assertEquals(
                "every identity and session keeps the rowid it had",
                listOf("1", "2"),
                column(connection, "SELECT _id FROM identities ORDER BY _id", "_id"))
        for (table in arrayOf("sessions", "prekeys", "signed_prekeys")) {
            Assert.assertEquals(
                    "one row, and the surrogate key is the rowid it already had, for " + table,
                    listOf("1"),
                    column(connection, "SELECT _id FROM " + table + " ORDER BY _id", "_id"))
        }

        for (table in OMEMO_TABLES) {
            val creates = mutableListOf<String>()
            for (statement in KspSchema.ddlFor(table)) {
                if (statement.startsWith("CREATE TABLE IF NOT EXISTS `" + table + "`")) {
                    creates.add(statement)
                }
            }
            Assert.assertEquals(
                    "Room must emit its own CREATE for "
                            + table
                            + "; anything else means the entity is not in the @Database list",
                    1,
                    creates.size)
            val roomFresh = DriverManager.getConnection("jdbc:sqlite::memory:")
            DaoSql.exec(roomFresh, creates[0])
            Assert.assertEquals(
                    "Room's " + table + " and the rebuilt file's must be the same table",
                    Schema75Fixture.tableInfo(connection, table),
                    Schema75Fixture.tableInfo(roomFresh, table))
        }
    }

    /**
     * The generated implementation, asserted to exist and to carry every statement. An accessor that
     * was dropped would leave a green compile and no `_Impl` at all - silently.
     */
    @Test
    fun roomGeneratedTheImplementationForEveryStatement() {
        val generated =
                RepoFiles.root()
                        .resolve(
                                "data/build/generated/ksp/debug/kotlin/uk/xa0/tulkki/data/omemo/"
                                        + "OmemoStoreDao_Impl.kt")
        Assert.assertTrue(
                "Room must have generated OmemoStoreDao_Impl, which only the accessor makes happen",
                Files.isRegularFile(generated))
        val text = DaoSql.squeezed(RepoFiles.read(generated))
        Assert.assertTrue(
                "and it must be the generated class, not a hand-written copy",
                text.contains(DaoSql.squeezed("class OmemoStoreDao_Impl")))
        for (statement in DaoSql.queriesIn(RepoFiles.read(DAO_SOURCE))) {
            Assert.assertTrue(
                    "the generated implementation must carry the DAO's statement: " + statement,
                    text.contains(DaoSql.squeezed(DaoSql.bound(statement))))
        }
    }

    companion object {

        private const val DAO_SOURCE =
                "data/src/main/java/uk/xa0/tulkki/data/omemo/OmemoStoreDao.kt"

        private const val ACCOUNT = "acct-1"

        private const val OTHER_ACCOUNT = "acct-2"

        private val OMEMO_TABLES =
                arrayOf("identities", "sessions", "prekeys", "signed_prekeys")

        private val IDENTITY_COLUMNS =
                listOf(
                        "account",
                        "name",
                        "ownkey",
                        "fingerprint",
                        "certificate",
                        "trust",
                        "active",
                        "last_activation",
                        "key")

        private val SESSION_COLUMNS =
                listOf("account", "name", "device_id", "key")

        private val KEY_COLUMNS = listOf("account", "id", "key")

        /** A schema-77 file, reached the way a device reaches it. */
        private fun upgraded(): Connection {
            val connection = Schema75Fixture.openWithRows()
            Schema75Fixture.takeTheSchema75Step(connection)
            Schema76.applyUpgrade(JdbcSchemaExec(connection), 4242L)
            Schema77.applySchema(JdbcSchemaExec(connection))
            return connection
        }

        private fun upsertSession(
                connection: Connection,
                account: String?,
                name: String?,
                deviceId: Long,
                key: String?) {
            DaoSql.execNamed(
                    connection,
                    OmemoQueries.UPSERT_SESSION,
                    "account",
                    account,
                    "name",
                    name,
                    "deviceId",
                    deviceId,
                    "key",
                    key)
        }

        /** The `key` column of the one session the address names. */
        private fun keyColumn(
                connection: Connection,
                account: String?,
                name: String?,
                deviceId: Long): List<String?> {
            return column(
                    connection,
                    OmemoQueries.SESSION_BY_ADDRESS,
                    "key",
                    "account",
                    account,
                    "name",
                    name,
                    "deviceId",
                    deviceId)
        }

        private fun upsertPreKey(connection: Connection, account: String?, id: Long, key: String?) {
            DaoSql.execNamed(
                    connection, OmemoQueries.UPSERT_PREKEY, "account", account, "id", id, "key", key)
        }

        private fun upsertSignedPreKey(
                connection: Connection,
                account: String?,
                id: Long,
                key: String?) {
            DaoSql.execNamed(
                    connection,
                    OmemoQueries.UPSERT_SIGNED_PREKEY,
                    "account",
                    account,
                    "id",
                    id,
                    "key",
                    key)
        }

        private fun insertIdentity(
                connection: Connection,
                account: String?,
                name: String?,
                ownKey: Long,
                fingerprint: String?,
                key: String?,
                trust: String?,
                active: Long) {
            DaoSql.execNamed(
                    connection,
                    OmemoQueries.INSERT_IDENTITY,
                    "account",
                    account,
                    "name",
                    name,
                    "ownKey",
                    ownKey,
                    "fingerprint",
                    fingerprint,
                    "key",
                    key,
                    "trust",
                    trust,
                    "active",
                    active)
        }

        private fun legacyIdentity(
                connection: Connection,
                account: String?,
                name: String?,
                ownKey: Long?,
                fingerprint: String?,
                trust: String?,
                active: Long?,
                lastActivation: Long?,
                key: String?) {
            exec(
                    connection,
                    "INSERT INTO identities (account, name, ownkey, fingerprint, certificate, trust, "
                            + "active, last_activation, key) VALUES (?,?,?,?,?,?,?,?,?)",
                    account,
                    name,
                    ownKey,
                    fingerprint,
                    null,
                    trust,
                    active,
                    lastActivation,
                    key)
        }

        private fun legacySession(
                connection: Connection,
                account: String?,
                name: String?,
                deviceId: Long?,
                key: String?) {
            exec(
                    connection,
                    "INSERT INTO sessions (account, name, device_id, key) VALUES (?,?,?,?)",
                    account,
                    name,
                    deviceId,
                    key)
        }

        private fun legacyPreKey(connection: Connection, account: String?, id: Long?, key: String?) {
            exec(
                    connection,
                    "INSERT INTO prekeys (account, id, key) VALUES (?,?,?)",
                    account,
                    id,
                    key)
        }

        private fun legacySignedPreKey(
                connection: Connection,
                account: String?,
                id: Long?,
                key: String?) {
            exec(
                    connection,
                    "INSERT INTO signed_prekeys (account, id, key) VALUES (?,?,?)",
                    account,
                    id,
                    key)
        }

        private fun count(connection: Connection, table: String): Long {
            return DaoSql.scalarLong(connection, "SELECT COUNT(*) FROM " + table)
        }

        /** One named column of every row a statement selected, bound by the statement's own names. */
        private fun column(
                connection: Connection,
                sql: String,
                column: String,
                vararg nameValuePairs: Any?): List<String?> {
            val out = mutableListOf<String?>()
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

        /** The named columns of every row, joined with `|`, so a projection can be asserted whole. */
        private fun projection(
                connection: Connection,
                sql: String,
                columns: Array<String>,
                vararg nameValuePairs: Any?): List<String?> {
            val out = mutableListOf<String?>()
            try {
                connection.prepareStatement(DaoSql.bound(sql)).use { statement ->
                    DaoSql.bind(statement, DaoSql.parametersOf(sql), DaoSql.values(*nameValuePairs))
                    statement.executeQuery().use { results ->
                        while (results.next()) {
                            val row = StringBuilder()
                            for (column in columns) {
                                row.append(results.getString(column)).append('|')
                            }
                            out.add(row.substring(0, row.length - 1))
                        }
                    }
                }
            } catch (e: SQLException) {
                throw IllegalStateException("could not query: " + sql, e)
            }
            return out
        }

        private fun nonNull(values: List<String?>): List<String> {
            val out = mutableListOf<String>()
            for (value in values) {
                if (value != null) {
                    out.add(value)
                }
            }
            return out
        }

        private fun exec(connection: Connection, sql: String, vararg args: Any?) {
            DaoSql.exec(connection, sql, *args)
        }
    }
}
