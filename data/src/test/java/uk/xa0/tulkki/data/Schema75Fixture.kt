package uk.xa0.tulkki.data

import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import org.junit.Assert
import uk.xa0.tulkki.data.messages.MessagesQueries
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.omemo.OmemoQueries
import uk.xa0.tulkki.data.roster.RosterQueries
import uk.xa0.tulkki.data.schema.RawTables
import uk.xa0.tulkki.data.upload.UploadQueries

/**
 * A schema-75 database, on the host, in memory — and a schema-74 one, because the upgrade chain has
 * two steps and only one of them is Room's.
 *
 * <p>The upgrade path can only be exercised on the JVM with a plain-JVM SQLite, because SQLCipher
 * ships Android ABIs only (`docs/MIGRATION.md`, "Design: the data layer" §4.4). This builds
 * the fixture from checked-in sources rather than from anybody's memory:
 *
 * <ol>
 *   <li>`src/test/resources/schema-75.sql` — the mechanically resolved snapshot of
 *       `messages`, the conversation table, `cids` and the eight message indexes, the
 *       same file `SchemaNameTest` pins;
 *   <li>the `accounts` statement and `messages_index` with its three triggers, the
 *       latter verbatim from `messages/`'s own constants. Without the triggers the
 *       search-index repair would look like it worked while nothing was indexed;
 *   <li>`RawTables`, the list S5-5 moved out of `DatabaseBackend`: the search index and
 *       its triggers, `resolver_results`, `posts`, `stories`, `webxdc_updates`,
 *       `muted_participants`, `pinned_messages` and Tulkki's three translation tables -
 *       every table the owner's file has that the snapshot above does not name;
 *   <li>for the from-74 tests, `DatabaseBackend.recreateMessageIndex`'s own statement list as
 *       the 74 → 75 step — that guard is the whole of schema 75 ("Design: the data layer" §1.5).
 * </ol>
 *
 * <p><strong>Why 74 is modelled rather than skipped.</strong> A device that never took the 75 step
 * is a real path in the history, and S5-5 chose what happens to it: the legacy chain that knew how
 * to walk it forward is gone, so a 74 file refuses to open rather than being recreated (see
 * `HistoryDatabase`). Before 75 the
 * index was built over `body`; 75 rebuilt it over `translated_body`. So a 74 fixture is
 * this fixture with the other index spelling, and the chain a 74 file walks is: the legacy 75 step,
 * then the schema-76 work. Driving it here is the most of that chain a host can drive, and it is
 * what `SyncMigrationTest`'s `upgradingFrom74*` names exercise.
 *
 * <p><strong>The FTS4 probe is first, and it fails rather than skips.</strong> The bundled SQLite
 * could have been built without FTS4, and the fixture cannot exist without it. A silently skipped
 * assertion is a passed test that proves nothing, so {@link #open()} throws with the reason before
 * it builds anything.
 */
object Schema75Fixture {

    /** The one account the seed tests use. */
    const val ACCOUNT = "acct-1"

    /** A conversation with a clear-history anchor, so the JSON parse is exercised. */
    const val CONVERSATION_CLEARED = "conv-cleared"

    /** A conversation with history but no clear-history attribute. */
    const val CONVERSATION_PLAIN = "conv-plain"

    /** A MUC — mode 1 — so the account anchor's mode clause is exercised. */
    const val CONVERSATION_MUC = "conv-muc"

    /** The MUC-join private message: skipped by the transmitted scan, counted by the account one. */
    const val PRIVATE_TIME = 3000L

    /** The newest ordinary message of [CONVERSATION_CLEARED]. */
    const val CLEARED_MESSAGE_TIME = 1000L

    /** The clear-history anchor, and its reference: `MamReference`'s `time:ref` attribute form. */
    const val CLEARED_ANCHOR_TIME = 2000L

    const val CLEARED_ANCHOR_REFERENCE = "refClear"

    /** The plain conversation's anchor. */
    const val PLAIN_ANCHOR_TIME = 500L

    /** The MUC's anchor. */
    const val MUC_ANCHOR_TIME = 700L

    /** The account anchor, which is the private message: `getLastMessageReceived` has no filter. */
    const val ACCOUNT_ANCHOR_REFERENCE = "srv-private"

    /** A body that is already in the app language and was recorded as same-language. */
    const val FINNISH_BODY = "Minä puhun suomea"

    /**
     * A row that was translated: its displayed text is the translation, its original is concealed.
     * The 75 step leaves the index empty (its rebuild is deferred), and the repair's belt only
     * rescues same-language rows - so this row is findable after the upgrade *only* if the rebuild
     * re-reads the index, which is what makes that statement load-bearing rather than tidy.
     */
    const val TRANSLATED_BODY = "käännös"

    /** The concealed original of [TRANSLATED_BODY]: it must never be findable. */
    const val TRANSLATED_ORIGINAL = "translation"

    /** A body whose row is pending, so it is covered and must stay out of the index. */
    const val COVERED_BODY = "salainen alkuperäinen"

    /** A connection to an empty schema-75 database. The FTS4 probe runs first. */
    fun open(): Connection {
        try {
            val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
            requireFts4(connection)
            applyLegacySchema(connection)
            return connection
        } catch (e: SQLException) {
            throw IllegalStateException("could not open the JVM SQLite fixture", e)
        }
    }

    /** An empty schema-75 database with the sample rows the seed and the repair are tested on. */
    fun openWithRows(): Connection {
        val connection = open()
        insertSampleRows(connection)
        return connection
    }

    /** A schema-74 database with the sample rows: the index is still the one built over `body`. */
    fun openFrom74WithRows(): Connection {
        val connection = open()
        asSchema74(connection)
        insertSampleRows(connection)
        return connection
    }

    /**
     * The probe, and it is deliberately the first thing that runs. Two lines, and the failure names
     * the reason instead of skipping: the snapshot contains `messages_index USING fts4` and a
     * run that could not create it must not report a green schema test.
     */
    fun requireFts4(connection: Connection) {
        try {
            connection.createStatement().use { statement ->
                statement.execute("CREATE VIRTUAL TABLE probe USING fts4(x)")
                statement.execute("DROP TABLE probe")
            }
        } catch (e: SQLException) {
            throw AssertionError(
                "this JVM SQLite has no FTS4 compiled in, so the schema-75 fixture (which "
                    + "contains `messages_index USING fts4`) cannot be built and no schema "
                    + "assertion below can mean anything: "
                    + e.message,
                e
            )
        }
    }

    /**
     * The 74 -> 75 step, verbatim from what `DatabaseBackend.recreateMessageIndex` did: drop
     * the index and its triggers, create it over `translated_body` instead of `body`,
     * recreate the triggers. The statements are now `messages/`'s own constants, which is the one
     * spelling the fresh install and the fixture share, so the two paths' `messages_index` is the
     * same object. The rebuild of its contents is deferred by the real method (it reads every row
     * inside a migration's transaction), which is why the index is empty afterwards and why the
     * schema-76 repair's "missing rows" belt matters.
     */
    fun takeTheSchema75Step(connection: Connection) {
        dropMessageIndex(connection)
        exec(connection, MessagesQueries.CREATE_INDEX_TABLE)
        exec(connection, MessagesQueries.CREATE_INSERT_TRIGGER)
        exec(connection, MessagesQueries.CREATE_UPDATE_TRIGGER)
        exec(connection, MessagesQueries.CREATE_DELETE_TRIGGER)
    }

    /**
     * The FTS machinery taken away entirely - the index, its shadow tables and its three triggers -
     * which is not the same state as a fragmented index: it is a file that never had one. It is the
     * owner's fresh install, whose Room callback never ran the fresh-install schema, and
     * `MessageIndexRebuildTest` is where that state is driven.
     */
    fun dropMessageIndex(connection: Connection) {
        for (statement in messageIndexDrops()) {
            exec(connection, statement)
        }
    }

    private fun asSchema74(connection: Connection) {
        for (statement in recreateMessageIndexStatements("body")) {
            exec(connection, statement)
        }
    }

    /** The statements `recreateMessageIndex` ran with the pre-75 spelling of the indexed column. */
    private fun recreateMessageIndexStatements(column: String): List<String> {
        val statements = messageIndexDrops().toMutableList()
        statements.add(messageIndexTable(column))
        statements.add(messageInsertTrigger(column))
        statements.add(messageUpdateTrigger(column))
        statements.add(messageDeleteTrigger())
        return statements
    }

    /** The drops both spellings share: the index, its shadow tables and its three triggers. */
    private fun messageIndexDrops(): List<String> {
        val statements = mutableListOf<String>()
        statements.add("DROP TRIGGER IF EXISTS after_message_insert")
        statements.add("DROP TRIGGER IF EXISTS after_message_update")
        statements.add("DROP TRIGGER IF EXISTS after_message_delete")
        statements.add("DROP TABLE IF EXISTS messages_index")
        statements.add("DROP TABLE IF EXISTS messages_index_docsize")
        statements.add("DROP TABLE IF EXISTS messages_index_segdir")
        statements.add("DROP TABLE IF EXISTS messages_index_segments")
        statements.add("DROP TABLE IF EXISTS messages_index_stat")
        return statements
    }

    private fun applyLegacySchema(connection: Connection) {
        exec(connection, ACCOUNTS)
        for (statement in statementsFromSnapshot()) {
            exec(connection, statement)
        }
        // `blocked_media` is not in the snapshot either - `SchemaNameTest` pins that file to
        // `messages`, `conversations`, `cids` and the indexes - but the owner's 75 file has it:
        // the legacy chain's guarded column helper created it (`DatabaseBackend` lost that helper
        // at S5-5; `upload/` owns the constant now).
        exec(connection, UploadQueries.CREATE_BLOCKED_MEDIA)
        // `roster/`'s two tables, likewise not in the snapshot: the owner's 75 file has both, and
        // the package's own `CREATE`s are the one spelling the legacy chain executed. The schema-77
        // rebuild runs over them, so the fixture starts from the legacy spelling (`NUMBER` columns
        // and an inline `UNIQUE`) exactly as the device's file does.
        exec(connection, RosterQueries.CREATE_CONTACTS)
        exec(connection, RosterQueries.CREATE_DISCOVERY_RESULTS)
        // `presence/`'s table: the same reason, and the schema-77 rebuild runs over it too.
        exec(connection, PRESENCE_TEMPLATES)
        // `omemo/`'s four: the owner's 75 file has all four - the OMEMO store has written them
        // since long before this migration - and the schema-77 rebuild runs over each. They are the
        // legacy spelling (`NUMBER` columns, no primary key, inline `UNIQUE`), exactly as the
        // device's file has them.
        for (statement in OmemoQueries.CREATE_STATEMENTS) {
            exec(connection, statement)
        }
        // Everything else the owner's 75 file has and the snapshot does not: the search index and
        // its triggers, `resolver_results`, `posts`, `stories`, `webxdc_updates`,
        // `muted_participants`, `pinned_messages` and Tulkki's three translation tables. S5-5 moved
        // those statements out of `DatabaseBackend` into `RawTables`, and this is the same list the
        // fresh install runs - which is what makes the two paths comparable table by table
        // (`FreshInstallSchemaTest`). The owner's file did not get them from `RawTables`: it got
        // them from the legacy chain these strings were moved verbatim from, and every one of them
        // predates schema 75.
        RawTables.applySchema(JdbcSchemaExec(connection))
    }

    /** The mechanically resolved snapshot, split on `;` with its `--` comments stripped. */
    private fun statementsFromSnapshot(): List<String> {
        val sql: String
        try {
            val input: InputStream? = Schema75Fixture::class.java.getResourceAsStream("/schema-75.sql")
            try {
                Assert.assertNotNull("the test classpath is missing /schema-75.sql", input)
                sql = String(input!!.readAllBytes(), StandardCharsets.UTF_8)
            } finally {
                input?.close()
            }
        } catch (e: IOException) {
            throw IllegalStateException("could not read /schema-75.sql", e)
        }
        val withoutComments = StringBuilder()
        for (line in sql.split("\n")) {
            if (!line.trim().startsWith("--")) {
                withoutComments.append(line).append('\n')
            }
        }
        val statements = mutableListOf<String>()
        for (part in withoutComments.toString().split(";")) {
            if (!part.trim().isEmpty()) {
                statements.add(part.trim())
            }
        }
        Assert.assertTrue("the snapshot produced no statements", statements.size > 10)
        return statements
    }

    private fun insertSampleRows(connection: Connection) {
        exec(connection, "INSERT INTO accounts (uuid, username) VALUES ('" + ACCOUNT + "', 'owner')")
        exec(
            connection,
            "INSERT INTO "
                + Conversation.TABLENAME
                + " (uuid, accountUuid, mode, attributes) VALUES "
                + "('"
                + CONVERSATION_CLEARED
                + "', '"
                + ACCOUNT
                + "', 0, '{\"last_clear_history\":\""
                + CLEARED_ANCHOR_TIME
                + ":"
                + CLEARED_ANCHOR_REFERENCE
                + "\"}')"
        )
        exec(
            connection,
            "INSERT INTO "
                + Conversation.TABLENAME
                + " (uuid, accountUuid, mode) VALUES "
                + "('"
                + CONVERSATION_PLAIN
                + "', '"
                + ACCOUNT
                + "', 0)"
        )
        exec(
            connection,
            "INSERT INTO "
                + Conversation.TABLENAME
                + " (uuid, accountUuid, mode, attributes) VALUES "
                + "('"
                + CONVERSATION_MUC
                + "', '"
                + ACCOUNT
                + "', 1, '{}')"
        )
        // The newest ordinary row of the cleared conversation.
        message(connection, "m-ordinary", CONVERSATION_CLEARED, CLEARED_MESSAGE_TIME, 0, 0, "srv-1", 0, null, "hei")
        // A same-language row: its body is the displayed text, and it is NULL in translated_body.
        message(connection, "m-finnish", CONVERSATION_CLEARED, 900L, 0, 0, null, 2, null, FINNISH_BODY)
        // A covered row: no displayed text, so it must stay NULL and stay unfindable.
        message(connection, "m-covered", CONVERSATION_CLEARED, 800L, 0, 0, null, 0, null, COVERED_BODY)
        // A translated row: translation_state 1, displayed text in translated_body, original in body.
        message(
            connection,
            "m-translated",
            CONVERSATION_CLEARED,
            700L,
            0,
            0,
            null,
            1,
            TRANSLATED_BODY,
            TRANSLATED_ORIGINAL
        )
        message(connection, "m-plain", CONVERSATION_PLAIN, PLAIN_ANCHOR_TIME, 0, 0, null, 0, null, "hello")
        message(connection, "m-muc", CONVERSATION_MUC, MUC_ANCHOR_TIME, 0, 0, null, 0, null, "muc")
        // A MUC-join private message: newer than everything, type 4.
        message(connection, "m-private", CONVERSATION_CLEARED, PRIVATE_TIME, 0, 4, ACCOUNT_ANCHOR_REFERENCE, 0, null, "join")
    }

    private fun message(
        connection: Connection,
        uuid: String,
        conversation: String,
        timeSent: Long,
        status: Int,
        type: Int,
        serverMsgId: String?,
        translationState: Int,
        translatedBody: String?,
        body: String?
    ) {
        try {
            connection.prepareStatement(
                "INSERT INTO messages (uuid, conversationUuid, timeSent, status, type, "
                    + "serverMsgId, translation_state, translated_body, body, encryption) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,0)"
            ).use { statement ->
                statement.setString(1, uuid)
                statement.setString(2, conversation)
                statement.setLong(3, timeSent)
                statement.setInt(4, status)
                statement.setInt(5, type)
                statement.setString(6, serverMsgId)
                statement.setInt(7, translationState)
                statement.setString(8, translatedBody)
                statement.setString(9, body)
                statement.execute()
            }
        } catch (e: SQLException) {
            throw IllegalStateException("could not insert " + uuid, e)
        }
    }

    /** `sqlite_master` as sorted text: what "the two callers cannot disagree" is compared on. */
    fun master(connection: Connection): String {
        val out = StringBuilder()
        try {
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT type, name, IFNULL(sql, '') FROM sqlite_master "
                        + "WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name"
                ).use { results ->
                    while (results.next()) {
                        out.append(results.getString(1))
                            .append(' ')
                            .append(results.getString(2))
                            .append('\n')
                            .append(results.getString(3))
                            .append('\n')
                    }
                }
            }
        } catch (e: SQLException) {
            throw IllegalStateException("could not read sqlite_master", e)
        }
        return out.toString()
    }

    /** `PRAGMA table_info` for one table, as `name|type|notnull|dflt|pk` lines. */
    fun tableInfo(connection: Connection, table: String): String {
        val out = StringBuilder()
        try {
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA table_info(`" + table + "`)").use { results ->
                    while (results.next()) {
                        out.append(results.getString("name"))
                            .append('|')
                            .append(results.getString("type"))
                            .append('|')
                            .append(results.getInt("notnull"))
                            .append('|')
                            .append(results.getString("dflt_value"))
                            .append('|')
                            .append(results.getInt("pk"))
                            .append('\n')
                    }
                }
            }
        } catch (e: SQLException) {
            throw IllegalStateException("could not read table_info for " + table, e)
        }
        return out.toString()
    }

    /** One table's column names, in the file's own order. */
    fun columnNames(connection: Connection, table: String): List<String> {
        val out = mutableListOf<String>()
        try {
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA table_info(`" + table + "`)").use { results ->
                    while (results.next()) {
                        out.add(results.getString("name"))
                    }
                }
            }
        } catch (e: SQLException) {
            throw IllegalStateException("could not read table_info for " + table, e)
        }
        return out
    }

    /**
     * Every row over `columns`, one line per row, nulls spelled NULL, ordered by rowid. This
     * is the data a rebuild must not touch: a create-copy-drop-rename whose copy is missing loses
     * exactly this and nothing else.
     */
    fun dumpRows(connection: Connection, table: String, columns: List<String>): String {
        val out = StringBuilder()
        val sql = "SELECT " + columns.joinToString(",") + " FROM " + table + " ORDER BY rowid"
        try {
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { results ->
                    while (results.next()) {
                        for (i in 1..columns.size) {
                            val value = results.getObject(i)
                            out.append(columns[i - 1])
                                .append('=')
                                .append(if (value == null) "NULL" else value)
                                .append('|')
                        }
                        out.append('\n')
                    }
                }
            }
        } catch (e: SQLException) {
            throw IllegalStateException("could not dump " + table, e)
        }
        return out.toString()
    }

    /** One scalar, for the seed assertions. */
    fun scalar(connection: Connection, sql: String): Any? {
        return try {
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { results ->
                    if (results.next()) results.getObject(1) else null
                }
            }
        } catch (e: SQLException) {
            throw IllegalStateException("could not run: " + sql, e)
        }
    }

    /**
     * The rows the seed wrote, as sorted text, with `updated_at` left out: that column is the
     * clock, and the two callers legitimately run at different instants. Everything else about a
     * seeded row is a decision, so a caller that stopped seeding — or seeded different anchors — is
     * visible here even though the DDL would look identical.
     */
    fun seededRows(connection: Connection): String {
        val out = StringBuilder()
        out.append(
            scalar(
                connection,
                "SELECT IFNULL(GROUP_CONCAT(uuid_line), '') FROM (SELECT account_uuid || '|' "
                    + "|| IFNULL(anchor_stanza_id, '') || '|' || anchor_time || '|' "
                    + "|| anchor_source || '|' || gap_end AS uuid_line FROM sync_cursor "
                    + "ORDER BY account_uuid)"
            )
        )
        out.append('\n')
        out.append(
            scalar(
                connection,
                "SELECT IFNULL(GROUP_CONCAT(uuid_line), '') FROM (SELECT conversation_uuid "
                    + "|| '|' || account_uuid || '|' || IFNULL(anchor_stanza_id, '') "
                    + "|| '|' || anchor_time || '|' || IFNULL(archive_first_id, '') "
                    + "|| '|' || swept_through AS uuid_line FROM sync_conversation "
                    + "ORDER BY conversation_uuid)"
            )
        )
        return out.toString()
    }

    private fun exec(connection: Connection, sql: String) {
        try {
            connection.createStatement().use { statement ->
                statement.execute(sql)
            }
        } catch (e: SQLException) {
            throw IllegalStateException("failed to execute: " + sql, e)
        }
    }

    // -- verbatim from DatabaseBackend's own constants --------------------------------------------

    /**
     * `presence_templates` at 75: `NUMBER` for `last_used` and the pair's inline `UNIQUE`, exactly
     * what `DatabaseBackend` wrote. It was `presence/PresenceQueries`'s constant until the lane
     * deleted the four legacy `CREATE`s nothing in production executed; this fixture is that
     * constant's only reader, so the spelling lives where it is read, as `ACCOUNTS` above does.
     */
    const val PRESENCE_TEMPLATES =
        "CREATE TABLE if not exists presence_templates(uuid TEXT, last_used NUMBER,message " +
            "TEXT,status TEXT,UNIQUE(message,status) ON CONFLICT REPLACE);"

    private const val ACCOUNTS =
        "create table if not exists accounts(uuid TEXT PRIMARY KEY,username TEXT,server " +
            "TEXT,password TEXT,display_name TEXT, status TEXT,status_message " +
            "TEXT,rosterversion TEXT,options NUMBER, avatar TEXT, keys TEXT, hostname " +
            "TEXT, resource TEXT,pinned_mechanism TEXT,pinned_channel_binding " +
            "TEXT,fast_mechanism TEXT,fast_token TEXT,ordering INTEGER DEFAULT 0,port " +
            "NUMBER DEFAULT 5222)"

    private fun messageIndexTable(column: String): String {
        return "CREATE VIRTUAL TABLE if not exists messages_index USING fts4" +
                " (uuid," +
                column +
                ",notindexed=\"uuid\",content=\"messages\",tokenize='unicode61')"
    }

    private fun messageInsertTrigger(column: String): String {
        return "CREATE TRIGGER if not exists after_message_insert AFTER INSERT ON messages BEGIN" +
                " INSERT INTO messages_index(rowid,uuid," +
                column +
                ") VALUES(NEW.rowid,NEW.uuid,NEW." +
                column +
                "); END;"
    }

    private fun messageUpdateTrigger(column: String): String {
        return "CREATE TRIGGER if not exists after_message_update UPDATE OF uuid," +
                column +
                " ON messages BEGIN UPDATE messages_index SET " +
                column +
                "=NEW." +
                column +
                ",uuid=NEW.uuid WHERE rowid=OLD.rowid; END;"
    }

    private fun messageDeleteTrigger(): String {
        return "CREATE TRIGGER if not exists after_message_delete AFTER DELETE ON messages BEGIN" +
                " DELETE FROM messages_index WHERE rowid=OLD.rowid; END;"
    }
}
