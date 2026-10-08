package uk.xa0.tulkki.data.references

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.DaoSql
import uk.xa0.tulkki.data.JdbcSchemaExec
import uk.xa0.tulkki.data.RepoFiles
import uk.xa0.tulkki.data.Schema75Fixture
import uk.xa0.tulkki.data.schema.Schema76
import uk.xa0.tulkki.data.schema.Schema77

/**
 * S5-3's `references/` package test: the payload document and the reply's id fallback.
 *
 * <p><strong>What this test executes, and what it does not.</strong> It executes all three
 * statements the DAO publishes over a schema-77 fixture reached the way a device reaches it, and it
 * exercises the fallback against all three of the ids it accepts - the row's `uuid`, its
 * `serverMsgId` and its `remoteMsgId` - because a fallback that answered only one of them is the
 * defect the statement exists to prevent. It does not execute a call through
 * `ReferenceDao_Impl`: that needs Room's connection machinery and is the gap every package
 * test inherits.
 *
 * <p>There is no DDL here and no entity: the package owns two columns of `messages`, whose
 * `CREATE` `messages/` owns. So there is nothing to compare against
 * `PRAGMA table_info`.
 */
class ReferenceDaoTest {

    private companion object {

        private const val DAO_SOURCE =
            "data/src/main/java/uk/xa0/tulkki/data/references/ReferenceDao.kt"

        private const val MESSAGE = "m-plain"

        private const val PAYLOAD = "[{\"name\":\"live-location\"}]"

        /** A schema-77 file, reached the way a device reaches it. */
        private fun upgraded(): Connection {
            val connection = Schema75Fixture.openWithRows()
            Schema75Fixture.takeTheSchema75Step(connection)
            Schema76.applyUpgrade(JdbcSchemaExec(connection), 4242L)
            Schema77.applySchema(JdbcSchemaExec(connection))
            return connection
        }

        private fun exec(connection: Connection, sql: String, vararg args: Any?) {
            DaoSql.exec(connection, sql, *args)
        }
    }

    /** The DAO's annotations and the package's constants, read as the same list of statements. */
    @Test
    fun theDaosAnnotationsAndThePackagesConstantsAreTheSameStatements() {
        Assert.assertEquals(
            "every statement ReferenceDao publishes, in the order it declares them",
            listOf(
                ReferenceQueries.PAYLOADS_OF,
                ReferenceQueries.SET_PAYLOADS,
                ReferenceQueries.QUOTE_FALLBACK),
            DaoSql.queriesIn(RepoFiles.read(DAO_SOURCE)))
    }

    /** The payload document is absent until written, and a write replaces it whole. */
    @Test
    fun thePayloadDocumentIsWrittenWholeAndReplacedRatherThanAppended() {
        val connection = upgraded()
        Assert.assertNull(
            "the fixture row carries no payloads yet",
            DaoSql.scalarNamed(connection, ReferenceQueries.PAYLOADS_OF, "uuid", MESSAGE))

        Assert.assertEquals(
            "the first write moves one row",
            1,
            DaoSql.execNamed(
                connection,
                ReferenceQueries.SET_PAYLOADS,
                "uuid",
                MESSAGE,
                "payloads",
                PAYLOAD))
        Assert.assertEquals(
            "and the read answers the whole document",
            PAYLOAD,
            DaoSql.scalarNamed(connection, ReferenceQueries.PAYLOADS_OF, "uuid", MESSAGE))

        Assert.assertEquals(
            "the second write replaces it",
            1,
            DaoSql.execNamed(
                connection, ReferenceQueries.SET_PAYLOADS, "uuid", MESSAGE, "payloads", "[]"))
        Assert.assertEquals(
            "and the document is the second one",
            "[]",
            DaoSql.scalar(connection, "SELECT payloads FROM messages WHERE uuid = ?", MESSAGE))
    }

    /** The fallback answers whichever of the three ids the caller was handed. */
    @Test
    fun theQuoteFallbackAnswersAllThreeIdsAndOnlyInsideTheConversation() {
        val connection = upgraded()
        exec(
            connection,
            "UPDATE messages SET serverMsgId = ?, remoteMsgId = ? WHERE uuid = ?",
            "srv-plain",
            "rem-plain",
            MESSAGE)

        Assert.assertEquals(
            "the row's own uuid answers it",
            MESSAGE,
            DaoSql.scalarNamed(
                connection,
                ReferenceQueries.QUOTE_FALLBACK,
                "conversation",
                Schema75Fixture.CONVERSATION_PLAIN,
                "messageId",
                MESSAGE))
        Assert.assertEquals(
            "its server id answers it",
            MESSAGE,
            DaoSql.scalarNamed(
                connection,
                ReferenceQueries.QUOTE_FALLBACK,
                "conversation",
                Schema75Fixture.CONVERSATION_PLAIN,
                "messageId",
                "srv-plain"))
        Assert.assertEquals(
            "and its remote id answers it",
            MESSAGE,
            DaoSql.scalarNamed(
                connection,
                ReferenceQueries.QUOTE_FALLBACK,
                "conversation",
                Schema75Fixture.CONVERSATION_PLAIN,
                "messageId",
                "rem-plain"))
        Assert.assertEquals(
            "an id no row carries answers nothing rather than failing",
            emptyList<String>(),
            DaoSql.queryNamed(
                connection,
                ReferenceQueries.QUOTE_FALLBACK,
                "conversation",
                Schema75Fixture.CONVERSATION_PLAIN,
                "messageId",
                "srv-nope"))
        Assert.assertEquals(
            "and another conversation's row is not the answer",
            emptyList<String>(),
            DaoSql.queryNamed(
                connection,
                ReferenceQueries.QUOTE_FALLBACK,
                "conversation",
                Schema75Fixture.CONVERSATION_CLEARED,
                "messageId",
                "srv-plain"))
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
                    "data/build/generated/ksp/debug/kotlin/uk/xa0/tulkki/data/references/" +
                        "ReferenceDao_Impl.kt")
        Assert.assertTrue(
            "Room must have generated ReferenceDao_Impl, which only the accessor makes happen",
            Files.isRegularFile(generated))
        val text = DaoSql.squeezed(RepoFiles.read(generated))
        Assert.assertTrue(
            "and it must be the generated class, not a hand-written copy",
            text.contains(DaoSql.squeezed("class ReferenceDao_Impl")))
        for (statement in
            arrayOf(
                ReferenceQueries.PAYLOADS_OF,
                ReferenceQueries.SET_PAYLOADS,
                ReferenceQueries.QUOTE_FALLBACK,
            )) {
            Assert.assertTrue(
                "the generated implementation must carry the DAO's statement: " + statement,
                text.contains(DaoSql.squeezed(DaoSql.bound(statement))))
        }
    }
}
