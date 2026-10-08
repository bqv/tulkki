package uk.xa0.tulkki.data

import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.data.schema.Schema76
import uk.xa0.tulkki.xmpp.utils.FtsUtils

/**
 * S4-14: the search surface, over the schema S5-2 rebuilt.
 *
 * `docs/MIGRATION.md` §2.13 is deliberately near-empty of work, and the work is that the
 * emptiness holds: `SearchActivity` draws results on its own Compose screen, through its own
 * `searchItems` (there is no second renderer), `MessageSearchTask` fetches
 * through `MessageSnapshots.search`
 * (S5-6 ported it off `DatabaseBackend.getMessageSearchCursor` and its cursor; it runs this
 * same query), and nothing in that path may consult the interpreter. The one thing
 * that must not happen is a **covered row in a result** - pending, failed, capped, no key or refused
 * history - because such a row has no displayed text, and drawing one would be the interface
 * surfacing a body it refuses to show.
 *
 * <strong>The query is the rule, so the test runs the query.</strong>
 * `DatabaseBackend.buildMessageSearchQuery` returns the production SQL and its arguments rather than
 * running them, and this test executes exactly that over a schema-76 fixture: the FTS index is over
 * `translated_body`, so a covered row has nothing to match. A test that re-spelled the SQL
 * would prove nothing about the query the app runs. What it cannot cover is the drawing:
 * `SearchActivity` needs an Activity, and this module has no
 * Robolectric - the bubble's cover is `DisplayedBodyTest`'s and the device's.
 *
 * <strong>The invariant beside the column:</strong> nothing may read `translated_body` to
 * mean "a translation exists" - the state field decides that, and always did. The search reads the
 * column only as the FTS index's content, and every result it can return therefore has a displayed
 * text by construction; {@link #noResultIsARowTheInterfaceWouldCover()} asserts that for every row
 * the query returns, not for one term.
 */
class SearchInvariantTest {

    private companion object {
        /** The covered row's original, which must not be findable: it has no displayed text to index. */
        private const val COVERED = "salainen"

        /** A schema-76 file, reached the way the owner's will be: 74 fixture, legacy 75 step, 76. */
        private fun upgraded(): Connection {
            val connection = Schema75Fixture.openFrom74WithRows()
            Schema75Fixture.takeTheSchema75Step(connection)
            Schema76.applyUpgrade(JdbcSchemaExec(connection), 4242L)
            return connection
        }

        /** The message uuids the production search query returns for `term`. */
        private fun search(connection: Connection, term: String): List<String> {
            val uuids = mutableListOf<String>()
            eachResult(connection, term) { results -> uuids.add(results.getString(Message.UUID)) }
            return uuids
        }

        /**
         * Every row the query returns must have a displayed text and be in a state that draws one. This
         * is the invariant rather than one term's outcome: if a covered row could ever be returned, its
         * `translated_body` would be the thing that matched - and it is not, by construction.
         */
        private fun noResultIsARowTheInterfaceWouldCover(connection: Connection, term: String) {
            eachResult(connection, term) { results ->
                val uuid = results.getString(Message.UUID)
                Assert.assertNotNull(
                    "a result with no displayed text is a covered row the search surfaced: " + uuid,
                    results.getString(Message.TRANSLATED_BODY))
                val state = results.getInt(Message.TRANSLATION_STATE)
                Assert.assertTrue(
                    "a result in state " + state + " (" + uuid + ") is a row the interface would cover",
                    state == Message.TRANSLATION_DONE || state == Message.TRANSLATION_SAME_LANGUAGE)
            }
        }

        private fun eachResult(connection: Connection, term: String, check: RowCheck) {
            val query = DatabaseBackend.buildMessageSearchQuery(FtsUtils.parse(term), null)
            try {
                connection.prepareStatement(query.sql).use { statement ->
                    for (i in 0 until query.args.size) {
                        statement.setObject(i + 1, query.args[i])
                    }
                    statement.executeQuery().use { results ->
                        while (results.next()) {
                            check.check(results)
                        }
                    }
                }
            } catch (e: SQLException) {
                throw IllegalStateException("could not run the search query: " + query.sql, e)
            }
        }
    }

    private fun interface RowCheck {
        fun check(results: ResultSet)
    }

    /**
     * The text of the function whose declaration begins with [signature], from that signature to its
     * own closing brace. Search's functions are indented four spaces, so the first `\n    }` after
     * the signature is the function's close and not a nested block's.
     */
    private fun function(text: String, signature: String): String {
        val start = text.indexOf(signature)
        Assert.assertTrue(signature + " is gone; move this pin with it", start >= 0)
        val end = text.indexOf("\n    }", start)
        Assert.assertTrue(signature + " has no end; move this pin with it", end > start)
        return text.substring(start, end)
    }

    /** How many times [needle] occurs in [text], for the body-read ratchet below. */
    private fun count(text: String, needle: String): Int {
        var found = 0
        var index = text.indexOf(needle)
        while (index >= 0) {
            found++
            index = text.indexOf(needle, index + needle.length)
        }
        return found
    }

    @Test
    fun aCoveredRowIsNeverASearchResult() {
        val connection = upgraded()

        Assert.assertEquals(
            "a covered row's original must not be findable: it has no displayed text, so the " +
                "index that search matches has nothing of it",
            emptyList<String>(),
            search(connection, COVERED))

        noResultIsARowTheInterfaceWouldCover(connection, COVERED)
    }

    @Test
    fun aRowThatNeededNoTranslationIsFoundByTheTextTheInterfaceDisplays() {
        val connection = upgraded()

        Assert.assertEquals(
            "a row that needed no translation carries its body in `translated_body` (S5-2's " +
                "repair), so the owner's own messages are findable again",
            listOf("m-finnish"),
            search(connection, Schema75Fixture.FINNISH_BODY))

        noResultIsARowTheInterfaceWouldCover(connection, Schema75Fixture.FINNISH_BODY)
    }

    @Test
    fun aTranslatedRowIsFoundByItsTranslationAndNeverByItsConcealedOriginal() {
        val connection = upgraded()

        Assert.assertEquals(
            "the displayed text of a translated row is its translation",
            listOf("m-translated"),
            search(connection, Schema75Fixture.TRANSLATED_BODY))
        Assert.assertEquals(
            "and the original it concealed must not be findable through the index",
            emptyList<String>(),
            search(connection, Schema75Fixture.TRANSLATED_ORIGINAL))

        noResultIsARowTheInterfaceWouldCover(connection, Schema75Fixture.TRANSLATED_BODY)
        noResultIsARowTheInterfaceWouldCover(connection, Schema75Fixture.TRANSLATED_ORIGINAL)
    }

    /**
     * §2.13's claim, pinned rather than repeated: the fetch path is not mode-aware, and the query
     * matches the column the triggers write. `SearchActivity` is deliberately not asserted to
     * be free of the interpreter - it is not: its quote path (`SearchActivity.quote`) asks
     * `DisplayedBody` and `Interpreter` whether a result may be quoted, which is S4-3b's
     * landed sweep. What must stay true here is that the *result set* - what can be found - is
     * decided by the data, not by the switch; the display is `SearchActivity.searchItems`'s.
     */
    @Test
    fun theFetchPathStaysOutOfTheInterpreter() {
        val task = RepoFiles.read("app/src/main/java/uk/xa0/tulkki/app/services/MessageSearchTask.kt")
        for (token in arrayOf("Interpreter", "DisplayedBody", "TranslationSettings", "translation_state")) {
            Assert.assertFalse(
                "the search fetch must not consult the interpreter, or 'what is findable' becomes " +
                    "a mode: MessageSearchTask names " + token,
                task.contains(token))
        }
        val activity = RepoFiles.read("ui/src/main/java/uk/xa0/tulkki/ui/SearchActivity.kt")
        // The rows are search's own screen now: 41bb1db164 made it Compose and deleted
        // SearchResultAdapter, so the display rule the adapter carried (f9c3b428fa) lives in the
        // screen's own `searchItems`/`displayedBody` instead. The pin reads that path - the row
        // builder and the decision it draws from - not a class name.
        val rows = function(activity, "private fun searchItems(")
        Assert.assertTrue(
            "the result rows must be built by search's own `searchItems`, one item per row, " +
                "with no second renderer: f9c3b428fa gave search its own row building and " +
                "41bb1db164 moved it into the Compose screen",
            rows.contains("displayedBody(message)") && rows.contains("isBlurred()"))
        Assert.assertTrue(
            "the only body the row builder may read is the date-separator sentinel, and only as a " +
                "comparison - a boolean, nothing drawn from it:\n" + rows,
            rows.contains("Message.DATE_SEPARATOR_BODY == message.getBody()") &&
                count(rows, "getBody(") == 1)
        Assert.assertTrue(
            "search draws text, so its rows must take the body through the same DisplayedBody " +
                "decision the bubble made - a blurred body shows its cover caption and " +
                "never the original",
            rows.contains("coverCaption") && rows.contains("displayed.text()"))
        val displayed = function(activity, "private fun displayedBody(")
        Assert.assertTrue(
            "the row's display decision must be DisplayedBody's, asked the same questions the " +
                "bubble asks - what the body holds and whether it needed translating:\n" + displayed,
            displayed.contains("DisplayedBody.of(") &&
                displayed.contains("DisplayedBody.needsTranslation("))
        val backend =
            RepoFiles.read(
                "data/src/main/java/uk/xa0/tulkki/data/messages/MessageIndexStore.kt")
        Assert.assertTrue(
            "the query must match the column the FTS triggers write",
            backend.contains("MESSAGE_INDEX_COLUMN") && backend.contains(" MATCH ?)"))
        Assert.assertFalse(
            "the search must not be made to match the raw body instead",
            backend.contains(" body MATCH") || backend.contains("body MATCH"))
    }
}
