package uk.xa0.tulkki.data.messages

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSetMetaData
import java.sql.SQLException
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.DaoSql
import uk.xa0.tulkki.data.DatabaseBackend
import uk.xa0.tulkki.data.JdbcSchemaExec
import uk.xa0.tulkki.data.KspSchema
import uk.xa0.tulkki.data.RepoFiles
import uk.xa0.tulkki.data.Schema75Fixture
import uk.xa0.tulkki.data.schema.Schema76
import uk.xa0.tulkki.data.schema.Schema77
import uk.xa0.tulkki.data.schema.Schema78
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.schema.Schema79
import uk.xa0.tulkki.data.schema.Schema80
import uk.xa0.tulkki.xmpp.utils.FtsUtils

/**
 * S5-3's `messages/` package test: the two tables, their two DAOs and every statement the
 * package publishes.
 *
 * <strong>What this test executes, and what it does not.</strong> It executes the package's own
 * SQL - every `@Query` the two DAOs publish, plus the index statements the package owns - over
 * a schema-77 fixture reached the way a device reaches it (a 75 snapshot, the schema-75 step, then
 * `Schema76` and `Schema77`), because Room's runtime cannot open the <em>encrypted</em>
 * file on the host and a test that re-spelled the queries would be testing the test. It also
 * executes Room's own generated `CREATE`s, read from `HistoryDatabase_Impl.kt`, and
 * compares their `PRAGMA table_info` with the migrated file's - which is what Room's
 * `onValidateSchema` compares after the migration, and a mismatch there is a refused open on
 * the owner's phone rather than a warning.
 *
 * <strong>Three things it cannot execute, and says so.</strong> (1) A call through
 * `MessagesDao_Impl` needs Room's connection machinery - the gap the `sync/` test names
 * once and every package test inherits. (2) The search read is a `@RawQuery`, so no annotation
 * holds its SQL: the statement it is handed is `DatabaseBackend.buildMessageSearchQuery`'s,
 * and what this test runs is that statement over the fixture - the same one
 * `SearchInvariantTest` owns, run once here to prove it works against <em>this</em> fixture.
 * (3) `messages_index` cannot be created by the legacy chain on the host at all, so the FTS table
 * comes from {@link Schema75Fixture}.
 *
 * The statements bind by parameter name, because SQLite numbers a statement's `?`s by
 * first appearance and Room's generator binds them in that same order.
 */
class MessagesDaoTest {

    /** The DAOs' annotations and the package's constants, read as the same list of statements. */
    @Test
    fun theDaosAnnotationsAndThePackagesConstantsAreTheSameStatements() {
        Assert.assertEquals(
            "every statement MessagesDao publishes, in the order it declares them",
            listOf(
                MessagesQueries.BY_CONVERSATION,
                MessagesQueries.BY_UUID,
                MessagesQueries.DELETE_BY_UUID,
                MessagesQueries.WATCH_BY_CONVERSATION,
                MessagesQueries.BY_UUIDS),
            DaoSql.queriesIn(RepoFiles.read(MESSAGES_DAO)))
        Assert.assertEquals(
            "every statement the conversation DAO publishes, in the order it declares them",
            listOf(
                ConversationQueries.BY_UUID,
                ConversationQueries.ROWS_FOR_ACCOUNT,
                ConversationQueries.LIST_FOR_ACCOUNT,
                ConversationQueries.DELETE_BY_UUID,
                ConversationQueries.WATCH_BY_UUID,
                ConversationQueries.WATCH_ACCOUNT_LIST),
            DaoSql.queriesIn(RepoFiles.read(CONVERSATION_DAO)))
        Assert.assertTrue(
            "the search read must be the raw read: a @Query naming messages_index is refused by " +
                "Room's own verifier (MessagesQueries records its words)",
            RepoFiles.read(MESSAGES_DAO).contains("@RawQuery"))
    }

    /**
     * S5-6's read model reads: the `Flow` watches are the statements the plain reads already
     * publish - the list watch is the list read, statement for statement - and each one runs over the
     * fixture with the same answer. The cell above proves the text agrees with the constants; this
     * proves the text is a statement SQLite executes.
     */
    @Test
    fun theWatchStatementsRunAndAnswerWhatTheReadsTheyMirrorAnswer() {
        val connection = upgraded()
        Assert.assertEquals(
            "the message watch answers the reading order",
            DaoSql.queryNamed(
                connection,
                MessagesQueries.BY_CONVERSATION,
                "conversation",
                Schema75Fixture.CONVERSATION_CLEARED),
            DaoSql.queryNamed(
                connection,
                MessagesQueries.WATCH_BY_CONVERSATION,
                "conversation",
                Schema75Fixture.CONVERSATION_CLEARED))
        Assert.assertEquals(
            "the single-row watch answers the row by its own key",
            DaoSql.queryNamed(
                connection, ConversationQueries.BY_UUID, "uuid", Schema75Fixture.CONVERSATION_MUC),
            DaoSql.queryNamed(
                connection,
                ConversationQueries.WATCH_BY_UUID,
                "uuid",
                Schema75Fixture.CONVERSATION_MUC))
        Assert.assertEquals(
            "and the list watch answers the list read, pointer included",
            DaoSql.queryNamed(
                connection, ConversationQueries.LIST_FOR_ACCOUNT, "account", Schema75Fixture.ACCOUNT),
            DaoSql.queryNamed(
                connection,
                ConversationQueries.WATCH_ACCOUNT_LIST,
                "account",
                Schema75Fixture.ACCOUNT))
    }

    /**
     * The search row fetch (S5-6): the statement the ported search consumer runs for the ids the FTS
     * read answered. Room expands `IN (:uuids)` to as many placeholders as the list holds, so
     * the execution below expands it the same way - two ids, two placeholders.
     */
    @Test
    fun theSearchRowFetchAnswersEveryNamedRow() {
        val connection = upgraded()
        val sql = MessagesQueries.BY_UUIDS.replace(":uuids", "?,?")
        val rows = mutableListOf<String>()
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, "m-plain")
            statement.setString(2, "m-muc")
            statement.executeQuery().use { results ->
                while (results.next()) {
                    rows.add(results.getString("uuid"))
                }
            }
        }
        Assert.assertEquals(
            "both named rows and only those; the statement promises no order, which is why the " +
                "read model restores the FTS read's own",
            listOf("m-muc", "m-plain"),
            rows.stream().sorted().toList())
        Assert.assertEquals(
            "and an id no row names answers nothing rather than failing",
            emptyList<String>(),
            DaoSql.queryNamed(
                connection, MessagesQueries.BY_UUIDS.replace(":uuids", "?"), "x", "m-nope"))
    }

    /** The conversation's own rows, oldest first, and nobody else's. */
    @Test
    fun theReadingOrderIsTheConversationRowsOldestFirst() {
        val connection = upgraded()
        Assert.assertEquals(
            "the cleared conversation's five rows, oldest first",
            listOf("m-translated", "m-covered", "m-finnish", "m-ordinary", "m-private"),
            DaoSql.queryNamed(
                connection,
                MessagesQueries.BY_CONVERSATION,
                "conversation",
                Schema75Fixture.CONVERSATION_CLEARED))
        Assert.assertEquals(
            "and another conversation's read is its own row only",
            listOf("m-plain"),
            DaoSql.queryNamed(
                connection,
                MessagesQueries.BY_CONVERSATION,
                "conversation",
                Schema75Fixture.CONVERSATION_PLAIN))
        Assert.assertEquals(
            "a conversation no row names answers nothing rather than failing",
            emptyList<String>(),
            DaoSql.queryNamed(
                connection, MessagesQueries.BY_CONVERSATION, "conversation", "conv-nope"))
    }

    /** One row by its own key, and nothing for a key no row has. */
    @Test
    fun theSingleRowReadsAnswerByKeyAndNothingForAnUnknownOne() {
        val connection = upgraded()
        Assert.assertEquals(
            "by uuid",
            "m-plain",
            DaoSql.scalarNamed(connection, MessagesQueries.BY_UUID, "uuid", "m-plain"))
        Assert.assertEquals(
            "an unknown uuid answers no row",
            emptyList<String>(),
            DaoSql.queryNamed(connection, MessagesQueries.BY_UUID, "uuid", "m-nope"))

        Assert.assertEquals(
            "the conversation's own read by key",
            "conv-muc",
            DaoSql.scalarNamed(
                connection, ConversationQueries.BY_UUID, "uuid", Schema75Fixture.CONVERSATION_MUC))
        Assert.assertEquals(
            "the account's rows, in the statement's own order",
            listOf(
                Schema75Fixture.CONVERSATION_CLEARED,
                Schema75Fixture.CONVERSATION_MUC,
                Schema75Fixture.CONVERSATION_PLAIN),
            DaoSql.queryNamed(
                connection,
                ConversationQueries.ROWS_FOR_ACCOUNT,
                "account",
                Schema75Fixture.ACCOUNT))
    }

    /** The delete is one row, and the cascade the package leans on is the foreign key's. */
    @Test
    fun theDeleteMovesExactlyOneRow() {
        val connection = upgraded()
        Assert.assertEquals(
            "seven rows to begin with", 7L, DaoSql.scalarLong(connection, "SELECT COUNT(*) FROM messages"))
        Assert.assertEquals(
            "the delete answers the rows it moved",
            1,
            DaoSql.execNamed(connection, MessagesQueries.DELETE_BY_UUID, "uuid", "m-plain"))
        Assert.assertEquals(
            "and a second delete of the same key answers none, because it is gone",
            0,
            DaoSql.execNamed(connection, MessagesQueries.DELETE_BY_UUID, "uuid", "m-plain"))
        Assert.assertEquals(
            "six rows are left", 6L, DaoSql.scalarLong(connection, "SELECT COUNT(*) FROM messages"))
    }

    /**
     * The list's read: one row per conversation of the account, the newest row's id and instant
     * already projected, and no body column - S5-6 widened S5-3's pointer-only projection to the
     * whole row, and the rule it was written for is the same either way.
     */
    @Test
    fun theListPointerNamesTheNewestRowOfEachConversationAndCarriesNoBody() {
        Assert.assertFalse(
            "the list read must not select a body; the projector owns the preview",
            ConversationQueries.LIST_FOR_ACCOUNT.contains("body"))

        val connection = upgraded()
        Assert.assertEquals(
            "one pointer row per conversation of the account, each with its newest row",
            listOf(
                Schema75Fixture.CONVERSATION_CLEARED + "|m-private|3000",
                Schema75Fixture.CONVERSATION_MUC + "|m-muc|700",
                Schema75Fixture.CONVERSATION_PLAIN + "|m-plain|500"),
            pointerRows(connection))
    }

    /**
     * The list projection must name **every** column `ConversationRow`'s embedded entity has, and
     * until schema 79 it did not: Room does not compare an `@Embedded` entity's columns with the
     * query's, so a column the statement leaves out arrives as the field's own default. `doubt_hold`
     * read that way is `null` on every list row, and `null` in that column means **"never chose"** -
     * an answer a row that chose `0` never gave. The comparison is against the table's own
     * `PRAGMA table_info`, because `RoomValidationTest` already pins the table's columns to the
     * entity's.
     */
    @Test
    fun theListProjectionNamesEveryColumnOfTheRowImage() {
        val connection = upgraded()
        val projected = projectedColumns(connection)
        val dropped = mutableListOf<String>()
        for (column in tableColumns(connection)) {
            if (!projected.contains(column)) {
                dropped.add(column)
            }
        }
        Assert.assertEquals(
            "the list statement must name every column ConversationRow embeds; one it drops " +
                "arrives as its Kotlin default, and for `doubt_hold` that default is the " +
                "tri-state's third answer",
            emptyList<String>(),
            dropped)
    }

    /**
     * The statement the `@RawQuery` is handed runs over this fixture, and it finds the
     * displayed text and not the covered one. The statement is the production builder's; this is a
     * second, narrower execution of it because `SearchInvariantTest` owns the invariant and
     * this package owns the read.
     */
    @Test
    fun theSearchStatementTheRawReadIsHandedRunsOverTheSchema77Fixture() {
        val connection = upgraded()
        Assert.assertEquals(
            "a same-language row is findable by the text the interface draws for it",
            listOf("m-finnish"),
            search(connection, Schema75Fixture.FINNISH_BODY))
        Assert.assertEquals(
            "a stored translation is findable",
            listOf("m-translated"),
            search(connection, Schema75Fixture.TRANSLATED_BODY))
        Assert.assertEquals(
            "and a covered row is not findable by its concealed original",
            emptyList<String>(),
            search(connection, Schema75Fixture.COVERED_BODY))
    }

    /** The package's DDL is `IF NOT EXISTS` throughout, and running it twice changes nothing. */
    @Test
    fun everyIndexStatementIsReRunnableAndChangesNothingOnASecondRun() {
        Assert.assertEquals("eight indexes, the search table and three triggers", 12, MessagesQueries.INDEX_STATEMENTS.size)
        for (statement in MessagesQueries.INDEX_STATEMENTS) {
            Assert.assertTrue(
                "a statement without IF NOT EXISTS is not re-runnable: " + statement,
                statement.startsWith("CREATE INDEX IF NOT EXISTS ") ||
                    statement.startsWith("CREATE VIRTUAL TABLE IF NOT EXISTS ") ||
                    statement.startsWith("CREATE TRIGGER IF NOT EXISTS "))
        }
        val connection = upgraded()
        val once = Schema75Fixture.master(connection)
        for (statement in MessagesQueries.INDEX_STATEMENTS) {
            DaoSql.exec(connection, statement)
        }
        Assert.assertEquals(
            "a second run of the package's DDL must change nothing", once, Schema75Fixture.master(connection))
    }

    /**
     * Room's own declaration of the two tables is the file the migration leaves. Room's
     * `onValidateSchema` compares each column's `notNull` flag and its normalised
     * affinity, so a mismatch here is a refused open on the owner's first launch rather than a
     * warning.
     */
    @Test
    fun roomsOwnDeclarationsOfTheTwoTablesAreTheFilesTheOwnerHas() {
        val tables = mutableListOf<String>()
        for (statement in KspSchema.ddlFor("messages", ConversationQueries.TABLE)) {
            if (statement.startsWith("CREATE TABLE IF NOT EXISTS `messages`") ||
                statement.startsWith("CREATE TABLE IF NOT EXISTS `" + ConversationQueries.TABLE + "`")) {
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
            "Room's messages and the migrated file's must be the same table",
            Schema75Fixture.tableInfo(owner, "messages"),
            Schema75Fixture.tableInfo(roomFresh, "messages"))
        Assert.assertEquals(
            "Room's conversation table and the migrated file's must be the same table",
            Schema75Fixture.tableInfo(owner, ConversationQueries.TABLE),
            Schema75Fixture.tableInfo(roomFresh, ConversationQueries.TABLE))
    }

    /**
     * The generated implementations, asserted to exist and to carry every statement. An accessor
     * that was dropped would leave a green compile and no `_Impl` at all - silently.
     */
    @Test
    fun roomGeneratedTheImplementationForEveryStatement() {
        val messages = squeezed(generated("MessagesDao_Impl.kt"))
        Assert.assertTrue(
            "Room must have generated MessagesDao_Impl, which only the accessor makes happen",
            messages.contains(squeezed("class MessagesDao_Impl")))
        for (statement in arrayOf(
            MessagesQueries.BY_CONVERSATION,
            MessagesQueries.BY_UUID,
            MessagesQueries.DELETE_BY_UUID,
        )) {
            Assert.assertTrue(
                "the generated implementation must carry the DAO's statement: " + statement,
                messages.contains(squeezed(DaoSql.bound(statement))))
        }
        Assert.assertTrue(
            "and the whole-row write the generator emitted from the entity",
            messages.contains(squeezed("INSERT OR IGNORE INTO `messages`")))
        Assert.assertTrue(
            "and the update, likewise from the entity",
            messages.contains(squeezed("UPDATE OR ABORT `messages`")))
        Assert.assertTrue(
            "and the raw read, which is the only shape Room accepts for `messages_index`",
            messages.contains("RoomRawQuery"))

        val conversation = squeezed(generated("ConversationDao_Impl.kt"))
        Assert.assertTrue(
            "Room must have generated ConversationDao_Impl",
            conversation.contains(squeezed("class ConversationDao_Impl")))
        for (statement in arrayOf(
            ConversationQueries.BY_UUID,
            ConversationQueries.ROWS_FOR_ACCOUNT,
            ConversationQueries.LIST_FOR_ACCOUNT,
            ConversationQueries.DELETE_BY_UUID,
        )) {
            Assert.assertTrue(
                "the generated implementation must carry the DAO's statement: " + statement,
                conversation.contains(squeezed(DaoSql.bound(statement))))
        }
    }

    private companion object {
        const val MESSAGES_DAO =
            "data/src/main/java/uk/xa0/tulkki/data/messages/MessagesDao.kt"

        const val CONVERSATION_DAO =
            "data/src/main/java/uk/xa0/tulkki/data/messages/ConversationDao.kt"

        /** A schema-77 file, reached the way a device reaches it. */
        private fun upgraded(): Connection {
            val connection = Schema75Fixture.openWithRows()
            Schema75Fixture.takeTheSchema75Step(connection)
            Schema76.applyUpgrade(JdbcSchemaExec(connection), 4242L)
            Schema77.applySchema(JdbcSchemaExec(connection))
            Schema78.applyUpgrade(JdbcSchemaExec(connection))
            Schema79.applyUpgrade(JdbcSchemaExec(connection))
            Schema80.applyUpgrade(JdbcSchemaExec(connection))
            return connection
        }

        /** The list read's pointer columns, one line per row, beside the row's own key. */
        private fun pointerRows(connection: Connection): List<String> {
            val out = mutableListOf<String>()
            val sql = ConversationQueries.LIST_FOR_ACCOUNT
            try {
                connection.prepareStatement(DaoSql.bound(sql)).use { statement ->
                    DaoSql.bind(
                        statement,
                        DaoSql.parametersOf(sql),
                        DaoSql.values("account", Schema75Fixture.ACCOUNT))
                    statement.executeQuery().use { results ->
                        while (results.next()) {
                            out.add(
                                results.getString("uuid") +
                                    "|" +
                                    results.getString("lastMessageId") +
                                    "|" +
                                    results.getString("lastMessageAt"))
                        }
                    }
                }
            } catch (e: SQLException) {
                throw IllegalStateException("could not query: " + sql, e)
            }
            return out
        }

        /** The list statement's own column labels, in the order it returns them. */
        private fun projectedColumns(connection: Connection): List<String> {
            val sql = ConversationQueries.LIST_FOR_ACCOUNT
            val labels = mutableListOf<String>()
            try {
                connection.prepareStatement(DaoSql.bound(sql)).use { statement ->
                    DaoSql.bind(
                        statement,
                        DaoSql.parametersOf(sql),
                        DaoSql.values("account", Schema75Fixture.ACCOUNT))
                    statement.executeQuery().use { results ->
                        val meta: ResultSetMetaData = results.getMetaData()
                        for (i in 1..meta.getColumnCount()) {
                            labels.add(meta.getColumnLabel(i))
                        }
                    }
                }
            } catch (e: SQLException) {
                throw IllegalStateException("could not query: " + sql, e)
            }
            return labels
        }

        /** The table's own columns, read from the file rather than from a second hand-written list. */
        private fun tableColumns(connection: Connection): List<String> {
            val columns = mutableListOf<String>()
            try {
                connection.prepareStatement("PRAGMA table_info(" + Conversation.TABLENAME + ")").use { statement ->
                    statement.executeQuery().use { results ->
                        while (results.next()) {
                            columns.add(results.getString("name"))
                        }
                    }
                }
            } catch (e: SQLException) {
                throw IllegalStateException("could not read the conversation table", e)
            }
            return columns
        }

        /** The production search statement, run over this fixture: the rows the raw read would answer. */
        private fun search(connection: Connection, term: String): List<String> {
            val query =
                DatabaseBackend.buildMessageSearchQuery(FtsUtils.parse(term), null)
            val out = mutableListOf<String>()
            try {
                connection.prepareStatement(query.sql).use { statement ->
                    for (i in query.args.indices) {
                        statement.setObject(i + 1, query.args[i])
                    }
                    statement.executeQuery().use { results ->
                        while (results.next()) {
                            out.add(results.getString("uuid"))
                        }
                    }
                }
            } catch (e: SQLException) {
                throw IllegalStateException("could not run the search query: " + query.sql, e)
            }
            return out
        }

        private fun generated(dao: String): String {
            val path: Path =
                RepoFiles.root()
                    .resolve(
                        "data/build/generated/ksp/debug/kotlin/uk/xa0/tulkki/data/messages/" +
                            dao)
            Assert.assertTrue("Room must have generated " + dao, Files.isRegularFile(path))
            return RepoFiles.read(path)
        }

        private fun squeezed(text: String): String {
            return DaoSql.squeezed(text)
        }
    }
}
