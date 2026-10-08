package uk.xa0.tulkki.data.reactions

import java.nio.file.Files
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
 * S5-3's `reactions/` package test: the reaction document of one message.
 *
 * <p><strong>What this test executes, and what it does not.</strong> It executes both statements the
 * DAO publishes over a schema-77 fixture reached the way a device reaches it, and asserts the write
 * replaces the document rather than appending to it and touches no other column. It does not execute
 * a call through `ReactionDao_Impl`: that needs Room's connection machinery and is the gap
 * every package test inherits.
 *
 * <p>There is no DDL here and no entity: the package owns one column of `messages`, whose
 * `CREATE` `messages/` owns. So there is nothing to compare against
 * `PRAGMA table_info`; what it does instead is execute the statements against the table the
 * entity declares.
 */
class ReactionDaoTest {

    /** The DAO's annotations and the package's constants, read as the same list of statements. */
    @Test
    fun theDaosAnnotationsAndThePackagesConstantsAreTheSameStatements() {
        Assert.assertEquals(
            "every statement ReactionDao publishes, in the order it declares them",
            listOf(ReactionQueries.REACTIONS_OF, ReactionQueries.SET_REACTIONS),
            DaoSql.queriesIn(RepoFiles.read(DAO_SOURCE)))
    }

    /** The document is absent until it is written, and a write replaces it whole. */
    @Test
    fun theDocumentIsWrittenWholeAndReplacedRatherThanAppended() {
        val connection = upgraded()
        Assert.assertNull(
            "the fixture row has no reactions yet",
            DaoSql.scalarNamed(connection, ReactionQueries.REACTIONS_OF, "uuid", MESSAGE))

        Assert.assertEquals(
            "the first write moves one row",
            1,
            DaoSql.execNamed(
                connection,
                ReactionQueries.SET_REACTIONS,
                "uuid",
                MESSAGE,
                "reactions",
                FIRST))
        Assert.assertEquals(
            "and the read answers the whole document",
            FIRST,
            DaoSql.scalarNamed(connection, ReactionQueries.REACTIONS_OF, "uuid", MESSAGE))

        Assert.assertEquals(
            "the second write replaces it",
            1,
            DaoSql.execNamed(
                connection,
                ReactionQueries.SET_REACTIONS,
                "uuid",
                MESSAGE,
                "reactions",
                "[]"))
        Assert.assertEquals(
            "and the document is the second one, not the two appended",
            "[]",
            DaoSql.scalar(
                connection, "SELECT reactions FROM messages WHERE uuid = ?", MESSAGE))
        Assert.assertEquals(
            "an unknown uuid moves nothing",
            0,
            DaoSql.execNamed(
                connection, ReactionQueries.SET_REACTIONS, "uuid", "m-nope", "reactions", "[]"))
    }

    /**
     * The generated implementation, asserted to exist and to carry both statements. An accessor that
     * was dropped would leave a green compile and no `_Impl` at all - silently.
     */
    @Test
    fun roomGeneratedTheImplementationForEveryStatement() {
        val generated =
            RepoFiles.root()
                .resolve(
                    "data/build/generated/ksp/debug/kotlin/uk/xa0/tulkki/data/reactions/" +
                        "ReactionDao_Impl.kt")
        Assert.assertTrue(
            "Room must have generated ReactionDao_Impl, which only the accessor makes happen",
            Files.isRegularFile(generated))
        val text = DaoSql.squeezed(RepoFiles.read(generated))
        Assert.assertTrue(
            "and it must be the generated class, not a hand-written copy",
            text.contains(DaoSql.squeezed("class ReactionDao_Impl")))
        for (statement in arrayOf(ReactionQueries.REACTIONS_OF, ReactionQueries.SET_REACTIONS)) {
            Assert.assertTrue(
                "the generated implementation must carry the DAO's statement: " + statement,
                text.contains(DaoSql.squeezed(DaoSql.bound(statement))))
        }
        Assert.assertFalse(
            "the document is the unit: no insert and no per-reactor statement may appear",
            text.contains(DaoSql.squeezed("INSERT INTO")))
    }

    private companion object {

        const val DAO_SOURCE =
            "data/src/main/java/uk/xa0/tulkki/data/reactions/ReactionDao.kt"

        const val MESSAGE = "m-plain"

        const val FIRST = "[{\"reactions\":[\"\\u2764\"],\"reactor\":\"a@example.org\"}]"

        /** A schema-77 file, reached the way a device reaches it. */
        fun upgraded(): Connection {
            val connection = Schema75Fixture.openWithRows()
            Schema75Fixture.takeTheSchema75Step(connection)
            Schema76.applyUpgrade(JdbcSchemaExec(connection), 4242L)
            Schema77.applySchema(JdbcSchemaExec(connection))
            return connection
        }
    }
}
