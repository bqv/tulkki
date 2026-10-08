package uk.xa0.tulkki.data

import java.sql.Connection
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.schema.MIGRATION_75_76
import uk.xa0.tulkki.data.schema.Schema76
import uk.xa0.tulkki.data.schema.Schema77
import uk.xa0.tulkki.data.schema.Schema78
import uk.xa0.tulkki.data.schema.Schema79
import uk.xa0.tulkki.data.schema.Schema80
import uk.xa0.tulkki.data.sync.SyncQueries

/**
 * S5-2's migration, driven over a schema-74 fixture on the host, through the whole chain.
 *
 * <strong>The two steps, and which code owns each.</strong> `docs/MIGRATION.md`, "Design:
 * the data layer" §1.5: schema 75 is already spent — the legacy `onUpgrade`'s
 * `oldVersion < 75` guard was `recreateMessageIndex`, the FTS column change. So the sync
 * tables are 76, and Room's migration object is `MIGRATION_75_76`. A device that never took
 * the 75 step is nevertheless a real path in the history: S5-5 deleted the chain that could walk it
 * forward, so it now refuses to open at all (see `HistoryDatabase`), and
 * "Design: synchronisation" §7.1's `upgradingFrom74*` names stay because these tests model the
 * chain a 74 file used to walk. The chain is therefore:
 *
 * <ol>
 *   <li><strong>74 → 75</strong>, the legacy hook's, and <em>not executable on the host</em>: it was
 *       `DatabaseBackend.onUpgrade`, whose parameter is a
 *       `net.zetetic.database.sqlcipher.SQLiteDatabase` that no JVM test can instantiate, and
 *       the method is gone (S5-5). What
 *       is executable is the step's own statements, which is what {@link
 *       Schema75Fixture#takeTheSchema75Step} runs: `recreateMessageIndex`, verbatim.
 *   <li><strong>75 → 76</strong>, {@link Schema76#applyUpgrade}, which Room's `Migration`
 *       calls, and the fresh-install callback runs the other half of the same object.
 * </ol>
 *
 * On a real device at 74 the first step is the `LegacyPreflight` §3.2 describes, which this
 * commit does <em>not</em> add: Room owns the version now, a file at 74 has no registered migration
 * path, and a null path is a refused open — not a recreate. Measured on the artifact this module
 * compiles against (`javap -c` of room-runtime-android 2.7.0's `RoomOpenHelper.onUpgrade`):
 * `MigrationContainer.findMigrationPath(74, 76)` is called at bytecode 29, a null return reaches
 * `new IllegalStateException("A migration from 74 to 76 was required but not found. …")` at 320
 * and `athrow` at 360, and nothing on the path catches it. So the answer for a 74 file today is
 * <strong>none, and it is a crash</strong>; S5-5 chooses whether the preflight returns. What this class proves is that the SQL both
 * steps are made of composes: a 74 file walked through the 75 step and then 76 ends at the schema a
 * fresh install at 76 gets.
 *
 * What these cannot prove: that Room's identity hash accepts the file, that SQLCipher opens it,
 * or that the owner's own key still works — §4.4 says so in as many words, and none of these
 * assertions claim otherwise.
 */
class SyncMigrationTest {

    private companion object {
        private const val NOW = 1_700_000_000_000L

        /** The three tables schema 76 rebuilds, because a declared `NUMBER` cannot be changed in place. */
        private val REBUILT = arrayOf("accounts", "conversations", "messages")

        /** The columns whose values a rebuild must not change: everything but the repair's own column. */
        private fun unchangedOf(columns: List<String>): List<String> {
            val out = columns.toMutableList()
            out.remove("translated_body")
            return out
        }

        /** A 74 file that has walked the legacy 75 step and then Room's 75 → 76 step. */
        private fun upgradedFrom74(): Connection {
            val connection = Schema75Fixture.openFrom74WithRows()
            Schema75Fixture.takeTheSchema75Step(connection)
            Schema76.applyUpgrade(JdbcSchemaExec(connection), NOW)
            // S5-3: the chain a device walks is 75 -> 76 -> 77, so a fixture that stops at 76 is a file
            // no device will ever have. Schema 78 adds the day-split table to the same walk.
            Schema77.applySchema(JdbcSchemaExec(connection))
            Schema78.applyUpgrade(JdbcSchemaExec(connection))
            Schema79.applyUpgrade(JdbcSchemaExec(connection))
            Schema80.applyUpgrade(JdbcSchemaExec(connection))
            return connection
        }

        private fun snapshot(connection: Connection): String {
            val out = StringBuilder(Schema75Fixture.master(connection))
            out.append(Schema75Fixture.tableInfo(connection, "messages"))
            out.append(
                Schema75Fixture.scalar(
                    connection,
                    "SELECT IFNULL(GROUP_CONCAT(account_uuid || '|' || IFNULL(anchor_stanza_id, '') " +
                        "|| '|' || anchor_time || '|' || anchor_source), '') FROM sync_cursor"))
            out.append('\n')
            out.append(
                Schema75Fixture.scalar(
                    connection,
                    "SELECT IFNULL(GROUP_CONCAT(conversation_uuid || '|' || IFNULL(anchor_stanza_id, '') " +
                        "|| '|' || anchor_time || '|' || swept_through), '') FROM sync_conversation"))
            out.append('\n')
            out.append(
                Schema75Fixture.scalar(
                    connection,
                    "SELECT IFNULL(GROUP_CONCAT(uuid || '|' || IFNULL(translated_body, '') || '|' || delivery), '') " +
                        "FROM messages"))
            return out.toString()
        }

        private fun longAt(connection: Connection, sql: String): Long {
            val value = Schema75Fixture.scalar(connection, sql)
            Assert.assertNotNull("no row for: " + sql, value)
            return (value as Number).toLong()
        }

        private fun textAt(connection: Connection, sql: String): String? {
            val value = Schema75Fixture.scalar(connection, sql)
            return if (value == null) null else value.toString()
        }
    }

    @Test
    fun upgradingFrom74CreatesTheThreeTablesAndTheDeliveryColumn() {
        val connection = upgradedFrom74()

        for (table in arrayOf("sync_cursor", "sync_conversation", "sync_gap")) {
            Assert.assertEquals(
                "the upgrade must create " + table,
                "table",
                Schema75Fixture.scalar(
                    connection,
                    "SELECT type FROM sqlite_master WHERE name = '" + table + "'"))
        }
        Assert.assertEquals(
            "the account sweep is one indexed query only if this index exists",
            "index",
            Schema75Fixture.scalar(
                connection,
                "SELECT type FROM sqlite_master WHERE name = '" +
                    SyncQueries.CONVERSATION_ACCOUNT_INDEX +
                    "'"))
        val messages = Schema75Fixture.tableInfo(connection, "messages")
        Assert.assertTrue(
            "messages must gain the delivery column: " + messages,
            messages.contains("delivery|INTEGER|1|" + Schema76.DELIVERY_UNKNOWN + "|0"))
    }

    @Test
    fun upgradingFrom74SeedsTheAccountAnchorFromTheOldDerivations() {
        val connection = upgradedFrom74()

        Assert.assertEquals(
            "the account anchor is MamReference.max(getLastMessageReceived, getLastClearDate), " +
                "and getLastMessageReceived has no private-message filter — so the MUC " +
                "join message, not the clear history, is the anchor",
            Schema75Fixture.PRIVATE_TIME,
            longAt(connection, "SELECT anchor_time FROM sync_cursor WHERE account_uuid = '" + Schema75Fixture.ACCOUNT + "'"))
        Assert.assertEquals(
            Schema75Fixture.ACCOUNT_ANCHOR_REFERENCE,
            textAt(connection, "SELECT anchor_stanza_id FROM sync_cursor WHERE account_uuid = '" + Schema75Fixture.ACCOUNT + "'"))
        Assert.assertEquals(
            "anchor_source = SEEDED_FROM_STORE",
            SyncQueries.ANCHOR_SOURCE_SEEDED_FROM_STORE.toLong(),
            longAt(connection, "SELECT anchor_source FROM sync_cursor WHERE account_uuid = '" + Schema75Fixture.ACCOUNT + "'"))

        Assert.assertEquals(
            "a conversation's anchor is max(its clear history, its newest transmitted row) and " +
                "its swept floor starts there",
            Schema75Fixture.CLEARED_ANCHOR_TIME,
            longAt(connection, "SELECT anchor_time FROM sync_conversation WHERE conversation_uuid = '" + Schema75Fixture.CONVERSATION_CLEARED + "'"))
        Assert.assertEquals(
            Schema75Fixture.CLEARED_ANCHOR_REFERENCE,
            textAt(connection, "SELECT anchor_stanza_id FROM sync_conversation WHERE conversation_uuid = '" + Schema75Fixture.CONVERSATION_CLEARED + "'"))
        Assert.assertEquals(
            Schema75Fixture.CLEARED_ANCHOR_TIME,
            longAt(connection, "SELECT swept_through FROM sync_conversation WHERE conversation_uuid = '" + Schema75Fixture.CONVERSATION_CLEARED + "'"))
        Assert.assertNull(
            "archive_first_id is the one documented loss: mFirstMamReference was never persisted",
            Schema75Fixture.scalar(connection, "SELECT archive_first_id FROM sync_conversation WHERE conversation_uuid = '" + Schema75Fixture.CONVERSATION_CLEARED + "'"))
        Assert.assertEquals(
            Schema75Fixture.PLAIN_ANCHOR_TIME,
            longAt(connection, "SELECT anchor_time FROM sync_conversation WHERE conversation_uuid = '" + Schema75Fixture.CONVERSATION_PLAIN + "'"))
        Assert.assertNull(
            textAt(connection, "SELECT anchor_stanza_id FROM sync_conversation WHERE conversation_uuid = '" + Schema75Fixture.CONVERSATION_PLAIN + "'"))
        Assert.assertEquals(
            Schema75Fixture.MUC_ANCHOR_TIME,
            longAt(connection, "SELECT anchor_time FROM sync_conversation WHERE conversation_uuid = '" + Schema75Fixture.CONVERSATION_MUC + "'"))
    }

    @Test
    fun upgradingFrom74MarksEveryPreExistingMessageUnknownSoTheSweepNeverTouchesIt() {
        val connection = upgradedFrom74()
        Assert.assertEquals(
            "every row written before the column existed must read UNKNOWN, or the sweep would " +
                "walk back over years of history",
            0L,
            longAt(connection, "SELECT COUNT(*) FROM messages WHERE delivery <> " + Schema76.DELIVERY_UNKNOWN))
    }

    @Test
    fun upgradingFrom74IsIdempotentAcrossTwoRuns() {
        val connection = Schema75Fixture.openFrom74WithRows()
        Schema75Fixture.takeTheSchema75Step(connection)
        val exec = JdbcSchemaExec(connection)
        Schema76.applyUpgrade(exec, NOW)
        val once = snapshot(connection)
        Schema76.applyUpgrade(exec, NOW)
        Assert.assertEquals(
            "a second run must change neither the schema nor a seeded row: every statement is " +
                "IF NOT EXISTS, the delivery column is guarded, and the seed replaces its " +
                "own rows",
            once,
            snapshot(connection))
    }

    /**
     * The two definitions over one fixture, so their text is comparable: the same 75 file walked by
     * the migrations, and the same 75 file with the shared definitions applied. The *real* fresh
     * install - Room's generated `createAllTables` and then `HistoryDatabase.installFreshSchema`,
     * with no 75 file under it - is `FreshInstallSchemaTest`'s, and it compares by persisted
     * name rather than by `sqlite_master` text because Room's generated DDL is not the migration's.
     */
    @Test
    fun aFreshInstallAndAnUpgradeProduceTheSameSchema() {
        val fresh = Schema75Fixture.open()
        Schema76.applySchema(JdbcSchemaExec(fresh))
        Schema80.applySchema(JdbcSchemaExec(fresh))
        val upgraded = upgradedFrom74()
        Assert.assertEquals(
            "a new install and an owner's upgraded file must be the same schema; two paths that " +
                "can diverge is the defect this whole commit exists to prevent",
            Schema75Fixture.master(fresh),
            Schema75Fixture.master(upgraded))
        for (table in arrayOf(
            "accounts",
            "conversations",
            "messages",
            "blocked_jids",
            "sync_cursor",
            "sync_conversation",
            "sync_gap",
        )) {
            Assert.assertEquals(
                "PRAGMA table_info differs for " + table,
                Schema75Fixture.tableInfo(fresh, table),
                Schema75Fixture.tableInfo(upgraded, table))
        }
    }

    /**
     * "The search index, decided": mirror the displayed text into `translated_body` for rows
     * that needed no translation, and leave covered rows NULL — a covered row has no displayed text,
     * so it has nothing to index, and that is the leak rule rather than an oversight.
     *
     * Run through the chain on purpose: the 75 step leaves `messages_index` empty (its
     * rebuild is deferred), so this also exercises the repair's second half — the rows the update
     * trigger could not reach because they are not in the index yet.
     */
    @Test
    fun theRepairMirrorsTheDisplayedTextAndLeavesCoveredRowsAlone() {
        val connection = upgradedFrom74()

        Assert.assertEquals(
            Schema75Fixture.FINNISH_BODY,
            textAt(connection, "SELECT translated_body FROM messages WHERE uuid = 'm-finnish'"))
        Assert.assertNull(
            "a pending row is covered: it has no displayed text and must stay unindexed",
            Schema75Fixture.scalar(connection, "SELECT translated_body FROM messages WHERE uuid = 'm-covered'"))
        Assert.assertEquals(
            "the mirrored body must be findable",
            1L,
            longAt(connection, "SELECT COUNT(*) FROM messages_index WHERE messages_index MATCH 'puhun'"))
        Assert.assertEquals(
            "a covered body must not be findable, which is the whole point of the repair " +
                "writing the displayed text rather than the body",
            0L,
            longAt(connection, "SELECT COUNT(*) FROM messages_index WHERE messages_index MATCH 'salainen'"))
        Assert.assertEquals(
            "nothing may be indexed twice",
            0L,
            longAt(
                connection,
                "SELECT COUNT(*) FROM (SELECT rowid FROM messages_index GROUP BY rowid HAVING COUNT(*) > 1)"))
        Assert.assertEquals(
            "a row that was translated must still be findable by its displayed text: the 75 step " +
                "left the index empty and the belt only rescues same-language rows, so this " +
                "is what the rebuild's re-read is for",
            1L,
            longAt(connection, "SELECT COUNT(*) FROM messages_index WHERE messages_index MATCH '" + Schema75Fixture.TRANSLATED_BODY + "'"))
        Assert.assertEquals(
            "and its concealed original must not be findable, before or after the rebuild",
            0L,
            longAt(connection, "SELECT COUNT(*) FROM messages_index WHERE messages_index MATCH '" + Schema75Fixture.TRANSLATED_ORIGINAL + "'"))
    }

    /**
     * SQLite treats NULLs as distinct in a unique index, so an account-wide gap row with a null
     * conversation could be inserted twice and the composite primary key would not stop it. Room
     * will not warn about the nullable spelling.
     */
    @Test
    fun theAccountWideGapScopeColumnIsNotNullWithAnEmptyDefault() {
        val connection = Schema75Fixture.open()
        Schema76.applySchema(JdbcSchemaExec(connection))
        val gap = Schema75Fixture.tableInfo(connection, "sync_gap")
        Assert.assertTrue(
            "sync_gap.conversation_uuid must be NOT NULL DEFAULT '', and the composite primary " +
                "key names it second (account_uuid, conversation_uuid, gap_start, region): " +
                gap,
            gap.contains("conversation_uuid|TEXT|1|''|2"))
    }

    /**
     * The one mistake a rebuild can make that costs the owner their history: a create-copy-drop-
     * rename whose copy is missing, partial or column-shifted. Every row of all three rebuilt
     * tables, over every column the file had *before* the rebuild, is compared name for name and
     * value for value - and the count of columns proves nothing was silently added beyond the one
     * marker schema 76 introduces.
     */
    @Test
    fun theRebuildKeepsEveryRowAndEveryColumnNameForName() {
        val connection = Schema75Fixture.openFrom74WithRows()
        Schema75Fixture.takeTheSchema75Step(connection)
        val columnsBefore: MutableMap<String, List<String>> = linkedMapOf()
        val rowsBefore: MutableMap<String, String> = linkedMapOf()
        for (table in REBUILT) {
            val columns = Schema75Fixture.columnNames(connection, table)
            columnsBefore[table] = columns
            // `translated_body` is the one column the upgrade deliberately writes: the search-index
            // repair mirrors the displayed text into it for rows that needed no translation. It has
            // its own test; what must be untouched here is every other column and every row.
            rowsBefore[table] = Schema75Fixture.dumpRows(connection, table, unchangedOf(columns))
        }
        Assert.assertTrue(
            "the messages fixture must have rows, or this test proves nothing: " +
                rowsBefore["messages"],
            rowsBefore["messages"]!!.lines().count() >= 6)

        Schema76.applyUpgrade(JdbcSchemaExec(connection), NOW)

        for (table in REBUILT) {
            // `translated_body` is the one column the upgrade deliberately writes: the search-index
            // repair mirrors the displayed text into it for rows that needed no translation. It is
            // asserted by its own test; what must be untouched is every other column and every row.
            val before = columnsBefore[table]!!
            val unchanged = unchangedOf(before)
            val after = Schema75Fixture.columnNames(connection, table)
            Assert.assertTrue(
                "the rebuild of " + table + " dropped or renamed a column: " + before + " -> " + after,
                after.containsAll(before))
            Assert.assertEquals(
                "the rebuild of " + table + " added more than it should: " + after,
                before.size + (if (table == "messages") 1 else 0),
                after.size)
            Assert.assertEquals(
                "every value of every row of " + table + " must survive the rebuild",
                rowsBefore[table],
                Schema75Fixture.dumpRows(connection, table, unchanged))
        }
        Assert.assertEquals(
            "the rows written before `delivery` existed must all read UNKNOWN",
            0L,
            longAt(connection, "SELECT COUNT(*) FROM messages WHERE delivery <> " + Schema76.DELIVERY_UNKNOWN))
    }

    /**
     * What the rebuild is *for*: a `NUMBER` declaration normalises to `UNDEFINED`, which no Room
     * entity can emit, and no `ALTER` can change a declared type. After the rebuild no rebuilt
     * table declares one, every default survives, and the marker column is exactly what the guarded
     * helper says.
     */
    @Test
    fun theRebuiltTablesDeclareTypesAnEntityCanSay() {
        val connection = upgradedFrom74()

        for (table in REBUILT) {
            val info = Schema75Fixture.tableInfo(connection, table)
            Assert.assertFalse(
                "no column of " + table + " may still be declared NUMBER, which Room reads as " +
                    "UNDEFINED: " + info,
                info.contains("|NUMBER|"))
            Assert.assertEquals(
                "the rebuild must not leave its scratch table behind",
                0L,
                longAt(
                    connection,
                    "SELECT COUNT(*) FROM sqlite_master WHERE name = '" + table + "_new'"))
        }
        Assert.assertTrue(
            "accounts.port keeps its default across the rebuild: " +
                Schema75Fixture.tableInfo(connection, "accounts"),
            Schema75Fixture.tableInfo(connection, "accounts").contains("port|INTEGER|0|5222|0"))
        Assert.assertTrue(
            "the conversation table's `created` column is the nullable timestamp it was: " +
                Schema75Fixture.tableInfo(connection, "conversations"),
            Schema75Fixture.tableInfo(connection, "conversations").contains("created|INTEGER|0|null|0"))
        Assert.assertTrue(
            "messages.delivery is NOT NULL with the UNKNOWN default: " +
                Schema75Fixture.tableInfo(connection, "messages"),
            Schema75Fixture.tableInfo(connection, "messages")
                .contains("delivery|INTEGER|1|" + Schema76.DELIVERY_UNKNOWN + "|0"))
    }

    /**
     * `DROP TABLE messages` takes the eight indexes and the three FTS triggers down with it - an
     * index and a trigger belong to their table - so the rebuild has to put them back, and the
     * search index has to be re-read through them because the rowids are new.
     */
    @Test
    fun theRebuiltMessagesTableKeepsItsIndexesAndTheSearchIndexMachinery() {
        val connection = upgradedFrom74()
        val master = Schema75Fixture.master(connection)

        for (index in arrayOf(
            "message_conversation_index",
            "message_deleted_index",
            "message_expire_at_index",
            "message_file_deleted_index",
            "message_file_path_index",
            "message_time_index",
            "message_time_received_index",
            "message_type_index",
        )) {
            Assert.assertTrue("the rebuild lost the index " + index + ":\n" + master, master.contains("index " + index + "\n"))
        }
        for (trigger in arrayOf("after_message_insert", "after_message_update", "after_message_delete")) {
            Assert.assertTrue(
                "the rebuild lost the trigger " + trigger + ":\n" + master,
                master.contains("trigger " + trigger + "\n"))
        }
        Assert.assertTrue(
            "the search index must still be external-content FTS4 over messages",
            master.contains("messages_index USING fts4"))
        Assert.assertEquals(
            "and it must still answer for the row the repair mirrored",
            1L,
            longAt(connection, "SELECT COUNT(*) FROM messages_index WHERE messages_index MATCH 'puhun'"))
    }

    /**
     * The migration does **not** write `PRAGMA user_version`: Room owns it and sets 76 once the
     * migration returns. A migration that wrote it would be a second owner of the version, and the
     * version pair is the migration's identity.
     */
    @Test
    fun theUpgradeLeavesUserVersionToRoom() {
        val connection = Schema75Fixture.openFrom74WithRows()
        Schema75Fixture.takeTheSchema75Step(connection)
        val exec = JdbcSchemaExec(connection)
        exec.exec("PRAGMA user_version = 75")

        Schema76.applyUpgrade(exec, NOW)

        Assert.assertEquals(
            "nothing in the shared definition may write the version; Room sets it",
            75L,
            longAt(connection, "PRAGMA user_version"))
        Assert.assertEquals("the version this schema names", 76, Schema76.VERSION)
        Assert.assertEquals("the migration's start version", 75, MIGRATION_75_76.startVersion)
        Assert.assertEquals("the migration's end version", 76, MIGRATION_75_76.endVersion)
    }

    /**
     * The refusal that stands between the rebuild and a cascade. `DROP TABLE` on a parent fires
     * `ON DELETE CASCADE` when foreign keys are on, and the rebuild drops the very parents whose
     * children it is carrying across - so with them on this must refuse *before* the first drop,
     * leaving every row where it was.
     */
    @Test
    fun theRebuildRefusesToRunWithForeignKeysEnabled() {
        val connection = Schema75Fixture.openWithRows()
        val exec = JdbcSchemaExec(connection)
        exec.exec("PRAGMA foreign_keys = ON")
        val rowsBefore = Schema75Fixture.dumpRows(connection, "messages", Schema75Fixture.columnNames(connection, "messages"))

        try {
            Schema76.applyUpgrade(exec, NOW)
            Assert.fail(
                "the rebuild must refuse with foreign keys on: DROP TABLE would fire the " +
                    "cascades and delete the rows it is copying")
        } catch (expected: IllegalStateException) {
            Assert.assertTrue(
                "the refusal must say why: " + expected.message,
                expected.message!!.contains("foreign keys off"))
        }

        Assert.assertEquals(
            "a refused rebuild must not have touched a row",
            rowsBefore,
            Schema75Fixture.dumpRows(connection, "messages", Schema75Fixture.columnNames(connection, "messages")))
        Assert.assertEquals(
            "and must not leave a half-built scratch table behind",
            0L,
            longAt(connection, "SELECT COUNT(*) FROM sqlite_master WHERE name LIKE '%\\_new' ESCAPE '\\'"))
    }
}
