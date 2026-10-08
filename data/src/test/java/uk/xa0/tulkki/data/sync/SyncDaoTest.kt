package uk.xa0.tulkki.data.sync

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.SQLException
import java.util.regex.Pattern
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.JdbcSchemaExec
import uk.xa0.tulkki.data.KspSchema
import uk.xa0.tulkki.data.RepoFiles
import uk.xa0.tulkki.data.Schema75Fixture
import uk.xa0.tulkki.data.schema.Schema76
import uk.xa0.tulkki.data.schema.Schema77

/**
 * S5-3's `sync/` package test: three tables, their DAOs, and the facts the cursor model has to
 * get right.
 *
 * <p><strong>What this test executes, and what it does not.</strong> It executes the package's own
 * SQL - the three `CREATE`s and every statement the DAOs publish - over a schema-77 fixture
 * reached the way a device reaches it (74 fixture, the legacy 75 step, 76, 77), because Room's
 * runtime cannot open the <em>encrypted</em> file on the host (`docs/MIGRATION.md`, "Design:
 * the data layer" §4.4) and a test that re-spelled the queries would be testing the test. It also
 * executes, over a bare in-memory connection, the `CREATE` statements Room's own generated
 * `createAllTables` emits, and compares the result with the migrated file's
 * `PRAGMA table_info` - the same text `RoomConnectionManager.onMigrate` hands to
 * `onValidateSchema` after the owner's 75 → 77 upgrade.
 *
 * <p>What it does <strong>not</strong> execute is a call through `SyncCursorDao_Impl`: that
 * needs Room's connection machinery, and the host harness for it is the gap
 * `BlockingDaoExecutionTest` records (transaction bookkeeping inside Room's
 * `ConnectionPoolImpl.transaction`). The generated implementations' <em>existence and
 * content</em> are asserted separately, and the raw SQL this test runs is byte for byte the text
 * Room generates for each method -
 * `theDaosAnnotationsAndThePackagesConstantsAreTheSameStatements()` is what ties the two
 * together.
 *
 * <p><strong>The test binds by parameter name, not by position.</strong> SQLite numbers a statement's
 * `?`s by first appearance, and a `SET` clause appears before its `WHERE` - so `GAP_CLOSE`'s
 * first parameter is `state`, not `account` - while `GAP_SWEEP_CANDIDATES` names
 * `:account` twice for one bind. `execNamed` reads the statement's own names in order
 * and looks the values up, which is what Room's generated code does too.
 */
class SyncDaoTest {

    /**
     * The DAOs' annotations and `SyncQueries`' constants, read as the same list of statements.
     * A drift between the two is the defect this exists for, and the generated `_Impl` is then
     * checked against the very same list.
     */
    @Test
    fun theDaosAnnotationsAndThePackagesConstantsAreTheSameStatements() {
        val annotations = mutableListOf<String>()
        for (dao in DAO_SOURCES) {
            annotations.addAll(queriesIn(RepoFiles.read(dao)))
        }
        Assert.assertEquals(
                "every statement the three DAOs publish, in the order they declare them",
                listOf(
                        SyncQueries.CURSOR_BY_ACCOUNT,
                        SyncQueries.CURSOR_UPSERT,
                        SyncQueries.CURSOR_REMOVE,
                        SyncQueries.CONVERSATION_BY_CONVERSATION,
                        SyncQueries.CONVERSATION_CURSORS_BY_ACCOUNT,
                        SyncQueries.CONVERSATION_UPSERT,
                        SyncQueries.CONVERSATION_ADVANCE_SWEPT_THROUGH,
                        SyncQueries.GAP_BY_KEY,
                        SyncQueries.GAPS_OPEN_FOR_ACCOUNT,
                        SyncQueries.GAPS_FOR_ACCOUNT,
                        SyncQueries.GAP_OPEN,
                        SyncQueries.GAP_CLOSE,
                        SyncQueries.GAP_SWEEP_CANDIDATES,
                        SyncQueries.LIVE_MISS_CANDIDATES),
                annotations)
    }

    /** The cursor is one row per account, and every writer holds the whole row. */
    @Test
    fun theAccountCursorIsOneRowAndItsUpsertReplacesIt() {
        val connection = upgraded()

        Assert.assertEquals(
                "the first write inserts a row",
                1,
                execNamed(
                        connection,
                        SyncQueries.CURSOR_UPSERT,
                        "account", Schema75Fixture.ACCOUNT,
                        "stanzaId", "srv-1",
                        "time", 1000L,
                        "source", 4L,
                        "gapEnd", 0L,
                        "updatedAt", 11L))
        Assert.assertEquals(
                "and the second write replaces it rather than adding a second",
                1,
                execNamed(
                        connection,
                        SyncQueries.CURSOR_UPSERT,
                        "account", Schema75Fixture.ACCOUNT,
                        "stanzaId", "srv-2",
                        "time", 2000L,
                        "source", 1L,
                        "gapEnd", 0L,
                        "updatedAt", 12L))
        Assert.assertEquals("one row per account", 1L, scalarLong(connection, "SELECT COUNT(*) FROM sync_cursor"))
        Assert.assertEquals(
                "and its time travelled with it",
                2000L,
                scalarLong(
                        connection,
                        "SELECT anchor_time FROM sync_cursor WHERE account_uuid = ?",
                        Schema75Fixture.ACCOUNT))
        Assert.assertEquals(
                "with the stanza the second writer stated",
                "srv-2",
                scalar(
                        connection,
                        "SELECT anchor_stanza_id FROM sync_cursor WHERE account_uuid = ?",
                        Schema75Fixture.ACCOUNT))

        // The read the DAO publishes, over the same fixture.
        Assert.assertEquals(
                "the package's own SELECT finds the row",
                listOf(Schema75Fixture.ACCOUNT),
                queryNamed(connection, SyncQueries.CURSOR_BY_ACCOUNT, "account", Schema75Fixture.ACCOUNT))

        Assert.assertEquals(
                "removing the row answers one, because there was one",
                1,
                execNamed(connection, SyncQueries.CURSOR_REMOVE, "account", Schema75Fixture.ACCOUNT))
        Assert.assertEquals(
                "and removing it again answers zero, not an error",
                0,
                execNamed(connection, SyncQueries.CURSOR_REMOVE, "account", Schema75Fixture.ACCOUNT))
    }

    /**
     * The write the handset died on, and the reconciliation that stands in its place.
     *
     * <p>The registration flow's abort deletes the account on the database-writer executor while the
     * socket's teardown still reports `onSessionEnded` to the engine on its own thread, so the
     * cursor's parent can be gone by the time `SyncCursorDao.upsert` runs:
     * `FOREIGN KEY constraint failed (code 787)` on `tulkki-sync`, and the process with it. What the
     * two account-keyed inserts must do then is **write nothing** - the account is gone and its uuid
     * is never reused, so there is nothing to record, nothing to create and nothing to retry.
     *
     * <p>Foreign keys are off by default on this host connection (`PRAGMA foreign_keys` is off for a
     * fresh SQLite), so the control runs first: the table's own foreign key has to refuse a bare
     * insert for an account the file does not have, or the assertions below would be asserting
     * against a statement nothing checks.
     */
    @Test
    fun anAccountKeyedWriteIsSkippedWhenItsAccountIsGone() {
        val connection = upgraded()
        exec(connection, "PRAGMA foreign_keys=ON")

        Assert.assertTrue(
                "the fixture must enforce the cursor's own foreign key, or this cell proves nothing",
                fails(
                        connection,
                        "INSERT INTO sync_cursor (account_uuid, anchor_stanza_id, anchor_time, " +
                                "anchor_source, gap_end, updated_at) VALUES (?, NULL, 0, 0, 0, 1)",
                        MISSING_ACCOUNT))

        // The fixture reaches schema 77 through `Schema76`'s own upgrade, whose `seed` has already
        // written one cursor row for the account the file still has - `SyncMigrationTest` measures
        // that row's anchor, so it is the migration's behaviour and not this cell's. The two counts
        // below therefore measure what the gone account's writes *add*, which is the claim the test
        // name makes, rather than demanding a table the migration has already populated.
        val cursorRowsBefore = scalarLong(connection, "SELECT COUNT(*) FROM sync_cursor")
        val regionRowsBefore = scalarLong(connection, "SELECT COUNT(*) FROM sync_gap")

        Assert.assertEquals(
                "the statement the DAO publishes writes nothing for that account, and does not throw",
                0,
                execNamed(
                        connection,
                        SyncQueries.CURSOR_UPSERT,
                        "account", MISSING_ACCOUNT,
                        "stanzaId", null,
                        "time", 0L,
                        "source", 0L,
                        "gapEnd", 0L,
                        "updatedAt", 1L))
        Assert.assertEquals(
                "the region is the engine's other account-keyed insert, guarded the same way",
                0,
                execNamed(
                        connection,
                        SyncQueries.GAP_OPEN,
                        "account", MISSING_ACCOUNT,
                        "conversation", "",
                        "gapStart", 0L,
                        "gapEnd", 0L,
                        "region", 0L,
                        "state", 0L,
                        "reason", null,
                        "openedAt", 1L,
                        "closedAt", null))
        Assert.assertEquals(
                "so the missing account left no cursor row",
                cursorRowsBefore,
                scalarLong(connection, "SELECT COUNT(*) FROM sync_cursor"))
        Assert.assertEquals(
                "and no region",
                regionRowsBefore,
                scalarLong(connection, "SELECT COUNT(*) FROM sync_gap"))

        // And it is a skip rather than a refusal: the account the fixture has takes both rows.
        Assert.assertEquals(
                "the account the file still has takes its cursor",
                1,
                execNamed(
                        connection,
                        SyncQueries.CURSOR_UPSERT,
                        "account", Schema75Fixture.ACCOUNT,
                        "stanzaId", null,
                        "time", 0L,
                        "source", 0L,
                        "gapEnd", 0L,
                        "updatedAt", 1L))
        Assert.assertEquals(
                "and its region",
                1,
                execNamed(
                        connection,
                        SyncQueries.GAP_OPEN,
                        "account", Schema75Fixture.ACCOUNT,
                        "conversation", "",
                        "gapStart", 0L,
                        "gapEnd", 0L,
                        "region", 0L,
                        "state", 0L,
                        "reason", null,
                        "openedAt", 1L,
                        "closedAt", null))
    }

    /**
     * The conversation cursor is scoped to its account: that scope is why the denormalised
     * `account_uuid` and its index exist.
     *
     * <p>The three fixture conversations already carry seeded rows - the 75 → 76 upgrade seeds a
     * `sync_conversation` row for every conversation with history - so this asserts the whole account
     * sweep rather than a hand-made subset.
     */
    @Test
    fun theConversationCursorIsScopedToItsAccount() {
        val connection = upgraded()
        seedSecondAccount(connection)
        exec(
                connection,
                "INSERT INTO conversations (uuid, accountUuid, mode) VALUES (?, ?, 0)",
                SECOND_CONVERSATION,
                SECOND_ACCOUNT)
        execNamed(
                connection,
                SyncQueries.CONVERSATION_UPSERT,
                "conversation", SECOND_CONVERSATION,
                "account", SECOND_ACCOUNT,
                "stanzaId", "ref",
                "time", 300L,
                "archiveFirst", "first",
                "sweptThrough", 300L,
                "updatedAt", 1L)

        Assert.assertEquals(
                "the account sweep is one indexed query over this account's cursor rows, in "
                        + "conversation-uuid order",
                listOf(
                        Schema75Fixture.CONVERSATION_CLEARED,
                        Schema75Fixture.CONVERSATION_MUC,
                        Schema75Fixture.CONVERSATION_PLAIN),
                queryNamed(
                        connection,
                        SyncQueries.CONVERSATION_CURSORS_BY_ACCOUNT,
                        "account",
                        Schema75Fixture.ACCOUNT))
        Assert.assertEquals(
                "and the other account sees only its own",
                listOf(SECOND_CONVERSATION),
                queryNamed(
                        connection,
                        SyncQueries.CONVERSATION_CURSORS_BY_ACCOUNT,
                        "account",
                        SECOND_ACCOUNT))
        Assert.assertEquals(
                "and the by-conversation read finds it",
                listOf(SECOND_CONVERSATION),
                queryNamed(
                        connection,
                        SyncQueries.CONVERSATION_BY_CONVERSATION,
                        "conversation",
                        SECOND_CONVERSATION))
        Assert.assertEquals(
                "a conversation with no row answers nothing, which is `anchor_time = 0`, not an error",
                0L,
                scalarLong(
                        connection,
                        "SELECT COUNT(*) FROM sync_conversation WHERE conversation_uuid = ?",
                        "conv-none"))

        Assert.assertEquals(
                "the sweep floor moves in its own statement",
                1,
                execNamed(
                        connection,
                        SyncQueries.CONVERSATION_ADVANCE_SWEPT_THROUGH,
                        "sweptThrough", 900L,
                        "updatedAt", 2L,
                        "conversation", Schema75Fixture.CONVERSATION_PLAIN))
        Assert.assertEquals(
                "the floor moved",
                900L,
                scalarLong(
                        connection,
                        "SELECT swept_through FROM sync_conversation WHERE conversation_uuid = ?",
                        Schema75Fixture.CONVERSATION_PLAIN))
        Assert.assertEquals(
                "and the anchor it was not touching did not",
                Schema75Fixture.PLAIN_ANCHOR_TIME,
                scalarLong(
                        connection,
                        "SELECT anchor_time FROM sync_conversation WHERE conversation_uuid = ?",
                        Schema75Fixture.CONVERSATION_PLAIN))
    }

    /**
     * The ledger's key is the four-tuple, and the account-wide scope is the empty string rather than
     * a null: SQLite treats `NULL`s as distinct in a unique index, so a null conversation could be
     * inserted twice and the composite primary key would not stop it.
     */
    @Test
    fun theGapLedgerIsKeyedByTheFourTupleAndTheEmptyStringIsTheAccountScope() {
        val connection = upgraded()

        Assert.assertEquals(
                1,
                execNamed(
                        connection,
                        SyncQueries.GAP_OPEN,
                        "account", Schema75Fixture.ACCOUNT,
                        "conversation", "",
                        "gapStart", 100L,
                        "gapEnd", 200L,
                        "region", 0L,
                        "state", 0L,
                        "reason", null,
                        "openedAt", 1L,
                        "closedAt", null))
        Assert.assertEquals(
                1,
                execNamed(
                        connection,
                        SyncQueries.GAP_OPEN,
                        "account", Schema75Fixture.ACCOUNT,
                        "conversation", Schema75Fixture.CONVERSATION_PLAIN,
                        "gapStart", 100L,
                        "gapEnd", 200L,
                        "region", 1L,
                        "state", 0L,
                        "reason", null,
                        "openedAt", 1L,
                        "closedAt", null))
        Assert.assertEquals(
                "the same account's two regions are two rows",
                2,
                queryNamed(
                                connection,
                                SyncQueries.GAPS_OPEN_FOR_ACCOUNT,
                                "account",
                                Schema75Fixture.ACCOUNT)
                        .size)

        Assert.assertEquals(
                "closing one region moves only that row",
                1,
                execNamed(
                        connection,
                        SyncQueries.GAP_CLOSE,
                        "account", Schema75Fixture.ACCOUNT,
                        "conversation", "",
                        "gapStart", 100L,
                        "region", 0L,
                        "state", 1L,
                        "reason", null,
                        "closedAt", 250L))
        Assert.assertEquals(
                "the closed region is COMPLETE and carries its close instant",
                "1|250",
                scalar(
                        connection,
                        "SELECT state || '|' || closed_at FROM sync_gap WHERE account_uuid = ? "
                                + "AND conversation_uuid = '' AND region = 0",
                        Schema75Fixture.ACCOUNT))
        Assert.assertEquals(
                "and the other region is still open",
                1,
                queryNamed(
                                connection,
                                SyncQueries.GAPS_OPEN_FOR_ACCOUNT,
                                "account",
                                Schema75Fixture.ACCOUNT)
                        .size)
        Assert.assertEquals(
                "and one region's key is exactly its own row",
                1,
                queryNamed(
                                connection,
                                SyncQueries.GAP_BY_KEY,
                                "account", Schema75Fixture.ACCOUNT,
                                "conversation", Schema75Fixture.CONVERSATION_PLAIN,
                                "gapStart", 100L,
                                "region", 1L)
                        .size)
        Assert.assertEquals(
                "the row the key found is the conversation's, not the account-wide one",
                Schema75Fixture.CONVERSATION_PLAIN,
                scalar(
                        connection,
                        "SELECT conversation_uuid FROM sync_gap WHERE account_uuid = ? "
                                + "AND gap_start = 100 AND region = 1",
                        Schema75Fixture.ACCOUNT))

        Assert.assertEquals(
                "re-opening a region rewrites the same row rather than adding one",
                1,
                execNamed(
                        connection,
                        SyncQueries.GAP_OPEN,
                        "account", Schema75Fixture.ACCOUNT,
                        "conversation", "",
                        "gapStart", 100L,
                        "gapEnd", 200L,
                        "region", 0L,
                        "state", 0L,
                        "reason", null,
                        "openedAt", 3L,
                        "closedAt", null))
        Assert.assertEquals(
                "so the ledger is two rows again, both open",
                2,
                queryNamed(
                                connection,
                                SyncQueries.GAPS_OPEN_FOR_ACCOUNT,
                                "account",
                                Schema75Fixture.ACCOUNT)
                        .size)

        Assert.assertTrue(
                "a null account-wide scope must be refused, or the composite key would not hold",
                fails(
                        connection,
                        "INSERT INTO sync_gap (account_uuid, conversation_uuid, gap_start, gap_end, "
                                + "region, state, opened_at) VALUES (?, NULL, 100, 200, 2, 0, 1)",
                        Schema75Fixture.ACCOUNT))
    }

    /**
     * The two sweeps of "Design: synchronisation" §1.2: the archive half is bounded by the account's
     * open regions, the live half by one conversation's own floor.
     */
    @Test
    fun theSweepsSeeArchiveRowsInsideTheGapAndLiveRowsAboveTheFloor() {
        val connection = upgraded()
        execNamed(
                connection,
                SyncQueries.GAP_OPEN,
                "account", Schema75Fixture.ACCOUNT,
                "conversation", Schema75Fixture.CONVERSATION_PLAIN,
                "gapStart", 0L,
                "gapEnd", 0L,
                "region", 0L,
                "state", 0L,
                "reason", null,
                "openedAt", 1L,
                "closedAt", null)

        // `m-plain` becomes an archive delivery; every other row keeps the UNKNOWN marker schema 76
        // gave it, which is read as "never a gap candidate".
        exec(connection, "UPDATE messages SET delivery = 1 WHERE uuid = ?", "m-plain")
        Assert.assertEquals(
                "the gap sweep sees the archive row inside the open region, and nothing else",
                listOf("m-plain"),
                queryNamed(
                        connection,
                        SyncQueries.GAP_SWEEP_CANDIDATES,
                        "account",
                        Schema75Fixture.ACCOUNT))

        // `m-muc` becomes a live row, with the conversation's floor below it.
        exec(connection, "UPDATE messages SET delivery = 0 WHERE uuid = ?", "m-muc")
        Assert.assertEquals(
                "the live sweep sees the row above the floor",
                listOf("m-muc"),
                queryNamed(
                        connection,
                        SyncQueries.LIVE_MISS_CANDIDATES,
                        "conversation", Schema75Fixture.CONVERSATION_MUC,
                        "sweptThrough", Schema75Fixture.MUC_ANCHOR_TIME - 100L,
                        "limit", 5))
        Assert.assertEquals(
                "and none once the floor has caught up with it: a row left alone is left alone",
                emptyList<String>(),
                queryNamed(
                        connection,
                        SyncQueries.LIVE_MISS_CANDIDATES,
                        "conversation", Schema75Fixture.CONVERSATION_MUC,
                        "sweptThrough", Schema75Fixture.MUC_ANCHOR_TIME,
                        "limit", 5))
    }

    /**
     * §6 step 3's rule: a table is created by the shared definition, so the fresh-install path and
     * the migration land on the same DDL. The other schema tests compare whole `sqlite_master` texts;
     * this one says the three 76 tables are in that comparison.
     */
    @Test
    fun aFreshInstallAndTheUpgradeAgreeOnTheThreeTables() {
        val fresh = Schema75Fixture.open()
        Schema76.applySchema(JdbcSchemaExec(fresh))
        Schema77.applySchema(JdbcSchemaExec(fresh))

        val migrated = upgraded()

        for (table in TABLES) {
            Assert.assertEquals(
                    "the fresh install's and the migrated file's " + table + " must be the same DDL",
                    Schema75Fixture.tableInfo(fresh, table),
                    Schema75Fixture.tableInfo(migrated, table))
        }
        Assert.assertTrue(
                "sync_cursor's key must be NOT NULL: Room compares the flag, and SQLite calls a bare "
                        + "TEXT PRIMARY KEY nullable - "
                        + Schema75Fixture.tableInfo(migrated, "sync_cursor"),
                Schema75Fixture.tableInfo(migrated, "sync_cursor").contains("account_uuid|TEXT|1|null|1"))
        Assert.assertTrue(
                "and so must sync_conversation's",
                Schema75Fixture.tableInfo(migrated, "sync_conversation")
                        .contains("conversation_uuid|TEXT|1|null|1"))
        Assert.assertTrue(
                "the account-wide gap scope is the empty string, not a null",
                Schema75Fixture.tableInfo(migrated, "sync_gap")
                        .contains("conversation_uuid|TEXT|1|''|2"))
    }

    /**
     * The generated implementations, asserted to exist and to carry exactly the package's statements.
     * This is the instrument that closes the same gap batch 1 named: a DAO is real only because the
     * database exposes it, and an accessor that was dropped would leave a green compile and no
     * `_Impl` at all - silently.
     *
     * <p>It is a build-output check, not an execution; the execution is the fixture work above.
     */
    @Test
    fun roomGeneratedTheImplementationForEveryStatement() {
        for (dao in DAO_CLASSES) {
            val generated = generatedPath(dao)
            Assert.assertTrue(
                    "Room must have generated "
                            + dao
                            + "_Impl: a DAO without an accessor on the database is inert and silent, "
                            + "and this is the file that proves the accessor worked",
                    Files.isRegularFile(generated))
            // `Files.readString` is not on the unit-test classpath (the mockable android jar's
            // `java.nio.file` shadows the JDK's), so text is read the way RepoFiles reads it.
            Assert.assertTrue(
                    "and it must be the generated class, not a hand-written copy",
                    squeezed(RepoFiles.read(generated)).contains(squeezed("class " + dao + "_Impl")))
        }

        assertCarries(
                generatedPath("SyncCursorDao"),
                SyncQueries.CURSOR_BY_ACCOUNT,
                SyncQueries.CURSOR_UPSERT,
                SyncQueries.CURSOR_REMOVE)
        assertCarries(
                generatedPath("SyncConversationDao"),
                SyncQueries.CONVERSATION_BY_CONVERSATION,
                SyncQueries.CONVERSATION_CURSORS_BY_ACCOUNT,
                SyncQueries.CONVERSATION_UPSERT,
                SyncQueries.CONVERSATION_ADVANCE_SWEPT_THROUGH)
        assertCarries(
                generatedPath("SyncGapDao"),
                SyncQueries.GAP_BY_KEY,
                SyncQueries.GAPS_OPEN_FOR_ACCOUNT,
                SyncQueries.GAP_OPEN,
                SyncQueries.GAP_CLOSE,
                SyncQueries.GAP_SWEEP_CANDIDATES,
                SyncQueries.LIVE_MISS_CANDIDATES)
    }

    /**
     * Room's own declaration of the three tables, executed, is the file the migration leaves.
     *
     * <p>This is the test that would catch a `TEXT PRIMARY KEY`: Room's `onValidateSchema` compares
     * each column's `notNull` flag, so a table whose owner's upgrade produced `notnull = 0` for a key
     * the entity declares non-null is a refused open rather than a warning - and the text compared
     * here is the same `PRAGMA table_info` on both sides.
     */
    @Test
    fun roomsOwnDeclarationsOfTheThreeTablesAreTheFileTheMigrationLeaves() {
        val ddl =
                KspSchema.ddlFor(
                        "sync_cursor", "sync_conversation", "sync_gap", "sync_conversation_account_index")
        Assert.assertEquals(
                "Room must emit its own CREATE for each of the three tables and the account index; "
                        + "anything else means an entity is not in the @Database list",
                4,
                ddl.size)

        val roomFresh = DriverManager.getConnection("jdbc:sqlite::memory:")
        for (statement in ddl) {
            exec(roomFresh, statement)
        }
        val migrated = upgraded()
        for (table in TABLES) {
            Assert.assertEquals(
                    "Room's own DDL and the migration's must describe the same "
                            + table
                            + "; a difference here is what onValidateSchema refuses after the upgrade",
                    Schema75Fixture.tableInfo(roomFresh, table),
                    Schema75Fixture.tableInfo(migrated, table))
        }
        Assert.assertEquals(
                "and the account index must exist under the name the entity declares",
                1L,
                scalarLong(
                        roomFresh,
                        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = ?",
                        SyncQueries.CONVERSATION_ACCOUNT_INDEX))
    }

    private companion object {

        val DAO_SOURCES = arrayOf(
            "data/src/main/java/uk/xa0/tulkki/data/sync/SyncCursorDao.kt",
            "data/src/main/java/uk/xa0/tulkki/data/sync/SyncConversationDao.kt",
            "data/src/main/java/uk/xa0/tulkki/data/sync/SyncGapDao.kt"
        )

        val DAO_CLASSES = arrayOf(
            "SyncCursorDao", "SyncConversationDao", "SyncGapDao"
        )

        const val SECOND_ACCOUNT = "acct-2"
        const val SECOND_CONVERSATION = "conv-2"

        /** An account uuid the fixture's `accounts` table does not have: the deleted parent. */
        const val MISSING_ACCOUNT = "acct-gone"

        /** The three tables, in the order the entities declare them. */
        val TABLES = arrayOf("sync_cursor", "sync_conversation", "sync_gap")

        val PARAMETER = Pattern.compile(":([A-Za-z][A-Za-z0-9_]*)")

        fun assertCarries(generated: Path, vararg statements: String) {
            val text = squeezed(RepoFiles.read(generated))
            for (statement in statements) {
                Assert.assertTrue(
                        "the generated implementation must carry the DAO's statement: " + statement,
                        text.contains(squeezed(bound(statement))))
            }
        }

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

        fun generatedPath(dao: String): Path {
            return RepoFiles.root()
                    .resolve("data/build/generated/ksp/debug/kotlin/uk/xa0/tulkki/data/sync/" + dao + "_Impl.kt")
        }

        /** Names become `?`; Room's generated SQL binds positionally in first-appearance order. */
        fun bound(sql: String): String {
            return sql.replace(Regex(":[A-Za-z][A-Za-z0-9_]*"), "?")
        }

        /**
         * Whitespace, quotes and concatenation signs removed, so the assertion is about the SQL rather
         * than about how Kotlin wrapped the literal the generator wrote. `val _sql` is one line for a
         * short query and several `+`-joined fragments for a long one.
         */
        fun squeezed(text: String): String {
            return text.replace(Regex("[\\s\"+]"), "")
        }

        /** The names a statement binds, in the order SQLite numbers its `?`s. */
        fun parametersOf(sql: String): List<String> {
            val names = mutableListOf<String>()
            val matcher = PARAMETER.matcher(sql)
            while (matcher.find()) {
                names.add(matcher.group(1))
            }
            return names
        }

        fun execNamed(connection: Connection, sql: String, vararg nameValuePairs: Any?): Int {
            try {
                return connection.prepareStatement(bound(sql)).use { statement ->
                    bind(statement, parametersOf(sql), values(*nameValuePairs))
                    statement.execute()
                    Math.max(statement.updateCount, 0)
                }
            } catch (e: SQLException) {
                throw IllegalStateException("could not execute: " + sql, e)
            }
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
            var i = 0
            while (i < nameValuePairs.size) {
                Assert.assertTrue("a parameter name must be a String", nameValuePairs[i] is String)
                out[nameValuePairs[i] as String] = nameValuePairs[i + 1]
                i += 2
            }
            return out
        }

        fun exec(connection: Connection, sql: String, vararg args: Any?): Int {
            try {
                return connection.prepareStatement(sql).use { statement ->
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

        fun fails(connection: Connection, sql: String, vararg args: Any?): Boolean {
            try {
                exec(connection, sql, *args)
                return false
            } catch (e: IllegalStateException) {
                return true
            }
        }

        fun scalar(connection: Connection, sql: String, vararg args: Any?): String? {
            val value = scalarObject(connection, sql, *args)
            return if (value == null) null else value.toString()
        }

        fun scalarLong(connection: Connection, sql: String, vararg args: Any?): Long {
            val value = scalarObject(connection, sql, *args)
            Assert.assertNotNull("the query must answer a value: " + sql, value)
            return (value as Number).toLong()
        }

        fun scalarObject(connection: Connection, sql: String, vararg args: Any?): Any? {
            try {
                return connection.prepareStatement(sql).use { statement ->
                    for (i in args.indices) {
                        statement.setObject(i + 1, args[i])
                    }
                    statement.executeQuery().use { results ->
                        Assert.assertTrue("the query must answer one row: " + sql, results.next())
                        results.getObject(1)
                    }
                }
            } catch (e: SQLException) {
                throw IllegalStateException("could not query: " + sql, e)
            }
        }

        /** The first column of every row a statement selected, bound by the statement's own names. */
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

        /**
         * Every `@Query` in a Kotlin DAO, with the `+`-joined literals of a wrapped annotation read as
         * one string. It reads the annotation, not the file: a statement that only appears in a comment
         * is not a statement Room will run.
         */
        fun queriesIn(source: String): List<String> {
            val out = mutableListOf<String>()
            var at = source.indexOf("@Query(")
            while (at >= 0) {
                var index = at + "@Query(".length
                var depth = 1
                var inString = false
                val sql = StringBuilder()
                while (index < source.length) {
                    val c = source[index]
                    if (inString) {
                        if (c == '\\') {
                            sql.append(source[index + 1])
                            index += 2
                            continue
                        }
                        if (c == '"') {
                            inString = false
                            index++
                            continue
                        }
                        sql.append(c)
                    } else if (c == '"') {
                        inString = true
                    } else if (c == '(') {
                        depth++
                    } else if (c == ')') {
                        depth--
                        if (depth == 0) {
                            break
                        }
                    }
                    index++
                }
                out.add(sql.toString())
                at = source.indexOf("@Query(", index)
            }
            return out
        }
    }
}
