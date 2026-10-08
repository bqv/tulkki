package uk.xa0.tulkki.data

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.schema.RawTables
import uk.xa0.tulkki.data.schema.Schema76
import uk.xa0.tulkki.data.schema.Schema77

/**
 * S5-12: deleting an account leaves no orphans, and a deleted message leaves no FTS terms - both
 * measured by execution over the real DDL.
 *
 * <p><strong>The four tables the audit found, and the two mechanisms.</strong> `S5-12` established
 * that `DatabaseBackend.deleteAccount` is exactly one write and that four account-scoped tables are
 * not reached by it: `translation_queue` and `webxdc_updates` and `muted_participants` had no
 * foreign key, and `updb.push` lives in a second file where no cascade can exist at all. Three of
 * them are now the account's rows by `FOREIGN KEY … ON DELETE CASCADE`; the fourth is deleted
 * explicitly in the deletion path. This class executes the one write against a schema-77 fixture
 * that holds a row in every one of them and asserts they are all empty afterwards - and it asserts
 * the schema *shape* that makes the cascade possible, because a fixture with the old shape would
 * pass the row counts while proving nothing.
 *
 * <p><strong>What the fixture is, and what it is not.</strong> The file is the schema-75 fixture
 * walked to 77 by the same {@link Schema76}/{@link Schema77} objects the owner's migration runs, so
 * the tables and their foreign keys are the ones a device has. What cannot run on the host is
 * SQLCipher (`docs/MIGRATION.md`, "Design: the data layer" §4.4), and the statements of the
 * deletion path are therefore executed here as the SQL they are.
 */
class OrphanedAccountRowsTest {

    private companion object {

        private const val ACCOUNT = "acct-orphan"

        private const val CONVERSATION = "conv-orphan"

        private const val MESSAGE = "msg-orphan"

        private const val MUC = "room@conference.example.org"

        private const val OCCUPANT = "occ-1"

        private const val FTS_TERM = "orphantoken"

        /**
         * `translation_queue`'s **pre-S5-12** declaration, byte for byte what `TranslationTables` wrote
         * before the foreign key: `TEXT PRIMARY KEY` with no `NOT NULL` and no key to `messages`. The
         * fixture has to start here, because the schema-77 rebuild is what adds the key - a fixture that
         * started at the new shape would be asserting the migration's output against itself.
         */
        private const val CREATE_QUEUE_LEGACY =
            "CREATE TABLE IF NOT EXISTS translation_queue (" +
                "message_uuid TEXT PRIMARY KEY," +
                "conversation_uuid TEXT," +
                "body TEXT NOT NULL," +
                "target_language TEXT," +
                "cache_key TEXT NOT NULL," +
                "state INTEGER NOT NULL DEFAULT 0," +
                "attempts INTEGER NOT NULL DEFAULT 0," +
                "next_attempt_at INTEGER NOT NULL DEFAULT 0," +
                "last_error TEXT," +
                "created_at INTEGER NOT NULL," +
                "failed_at INTEGER)"

        /** `UpdbQueries.DELETE_BY_ACCOUNT`'s own text, spelled here so the second file is not imported. */
        private const val DELETE_PUSH_BY_ACCOUNT =
            "DELETE FROM push WHERE account = :account"

        private const val PUSH_TABLE =
            "CREATE TABLE IF NOT EXISTS push (_id INTEGER NOT NULL, account TEXT, transport TEXT, " +
                "application TEXT NOT NULL, instance TEXT NOT NULL, endpoint TEXT, " +
                "expiration INTEGER DEFAULT 0, PRIMARY KEY(_id), UNIQUE(instance))"

        /**
         * A schema-77 database with one account, one conversation, one message, and a row in each of
         * the four tables the deletion path has to clear - plus the queue's raw body, so the privacy
         * half of the finding is what the assertion is about.
         *
         * <p>The legacy shapes are installed first and the two migrations are then run over them, which
         * is the path an owner's file takes. That matters: the schema-77 rebuilds are what add the
         * foreign keys and what replace the FTS delete trigger, so a fixture that started at the new
         * shapes would be asserting the migration's output against itself.
         */
        private fun withAnAccountAndItsChildren(): Connection {
            val connection = Schema75Fixture.open()
            exec(connection, CREATE_QUEUE_LEGACY)
            exec(connection, TranslationTables.CREATE_QUEUE_INDEX)
            exec(connection, RawTables.CREATE_WEBXDC_LEGACY)
            exec(connection, RawTables.CREATE_MUTED_LEGACY)
            JdbcSchemaExec(connection).exec("PRAGMA foreign_keys=OFF")
            Schema76.applyUpgrade(JdbcSchemaExec(connection), 4242L)
            Schema77.applySchema(JdbcSchemaExec(connection))

            exec(connection, "INSERT INTO accounts (uuid, username) VALUES (?, 'me')", ACCOUNT)
            exec(
                connection,
                "INSERT INTO conversations (uuid, accountUuid, contactJid, mode) VALUES (?, ?, ?, 1)",
                CONVERSATION,
                ACCOUNT,
                MUC)
            exec(
                connection,
                "INSERT INTO messages (uuid, conversationUuid, body, translated_body, type, status, translation_state) " +
                    "VALUES (?, ?, 'RAW ORIGINAL BODY', ?, 0, 0, 1)",
                MESSAGE,
                CONVERSATION,
                "hello " + FTS_TERM)
            // The queue row the finding is about: it keeps the deleted message's raw body.
            exec(
                connection,
                "INSERT INTO translation_queue (message_uuid, conversation_uuid, body, cache_key, created_at) " +
                    "VALUES (?, ?, 'RAW ORIGINAL BODY', 'key', 1)",
                MESSAGE,
                CONVERSATION)
            exec(
                connection,
                "INSERT INTO webxdc_updates (conversationUuid, sender, thread, payload) VALUES (?, 'peer', 't', 'p')",
                CONVERSATION)
            exec(
                connection,
                "INSERT INTO muted_participants (account_uuid, muc_jid, occupant_id) VALUES (?, ?, ?)",
                ACCOUNT,
                MUC,
                OCCUPANT)
            return connection
        }

        /** The three tables the deletion path must clear, and the one it must not leave terms in. */
        private fun captureTables(): List<String> {
            val out = mutableListOf<String>()
            out.add(TranslationTables.QUEUE_TABLE)
            out.add(RawTables.WEBXDC_TABLE)
            out.add(RawTables.MUTED_TABLE)
            return out
        }

        /** Which of the finding's tables holds a row, so a fixture that failed to seed is visible. */
        private fun populatedCaptureTables(connection: Connection): List<String> {
            val out = mutableListOf<String>()
            for (table in captureTables()) {
                if (scalarLong(connection, "SELECT COUNT(*) FROM " + table) > 0) {
                    out.add(table)
                }
            }
            return out
        }

        private fun ftsMatches(connection: Connection, term: String): Int {
            return scalarLong(
                    connection,
                    "SELECT COUNT(*) FROM messages_index WHERE messages_index MATCH '" + term + "'")
                .toInt()
        }

        /** A row count for a table that may not have the named column; a missing table counts as zero. */
        private fun countOrDefault(
            connection: Connection, table: String, column: String, value: String
        ): Int {
            try {
                return scalarLong(
                        connection,
                        "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = '" + value + "'")
                    .toInt()
            } catch (e: IllegalStateException) {
                return 0
            }
        }

        /**
         * How many mutes {@link RawTables#MUTED_FOR_ACCOUNT} - the read the service loads its cache
         * from - answers for one account. Nothing here re-spells the query: a test that did would prove
         * nothing about the statement the app runs.
         */
        private fun rowsForAccount(connection: Connection, accountUuid: String): Int {
            try {
                connection.prepareStatement(RawTables.MUTED_FOR_ACCOUNT).use { statement ->
                    statement.setObject(1, accountUuid)
                    statement.executeQuery().use { results ->
                        var rows = 0
                        while (results.next()) {
                            rows++
                        }
                        return rows
                    }
                }
            } catch (e: SQLException) {
                throw IllegalStateException("could not run MUTED_FOR_ACCOUNT", e)
            }
        }

        private fun assertCascade(connection: Connection, table: String, target: String) {
            Assert.assertEquals(
                table +
                    " must carry ON DELETE CASCADE to " +
                    target +
                    ", or deleting the account leaves its rows behind",
                target,
                foreignKeyTarget(connection, table))
            Assert.assertTrue(
                table + "'s foreign key to " + target + " must be ON DELETE CASCADE",
                foreignKeyOnDelete(connection, table)!!.contains("CASCADE"))
        }

        private fun foreignKeyTarget(connection: Connection, table: String): String? {
            try {
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA foreign_key_list(`" + table + "`)").use { results ->
                        return if (results.next()) results.getString("table") else null
                    }
                }
            } catch (e: SQLException) {
                throw IllegalStateException("could not read foreign_key_list for " + table, e)
            }
        }

        private fun foreignKeyOnDelete(connection: Connection, table: String): String? {
            try {
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA foreign_key_list(`" + table + "`)").use { results ->
                        return if (results.next()) results.getString("on_delete") else null
                    }
                }
            } catch (e: SQLException) {
                throw IllegalStateException("could not read foreign_key_list for " + table, e)
            }
        }

        private fun scalarLong(connection: Connection, sql: String): Long {
            try {
                connection.createStatement().use { statement ->
                    statement.executeQuery(sql).use { results ->
                        return if (results.next()) results.getLong(1) else 0L
                    }
                }
            } catch (e: SQLException) {
                throw IllegalStateException("could not run: " + sql, e)
            }
        }

        /** A fresh in-memory SQLite, for the second file's own table. */
        private fun plainMemory(): Connection {
            try {
                return java.sql.DriverManager.getConnection("jdbc:sqlite::memory:")
            } catch (e: SQLException) {
                throw IllegalStateException("could not open an in-memory SQLite", e)
            }
        }

        private fun exec(connection: Connection, sql: String, vararg args: Any?) {
            try {
                connection.prepareStatement(sql).use { statement ->
                    for (i in args.indices) {
                        statement.setObject(i + 1, args[i])
                    }
                    statement.execute()
                }
            } catch (e: SQLException) {
                throw IllegalStateException("failed to execute: " + sql, e)
            }
        }
    }

    /**
     * The one write the app makes, and then the row counts. `DatabaseBackend.deleteAccount` is
     * `DELETE FROM accounts WHERE uuid=?`; with foreign keys on - which `HistoryDatabase`'s
     * `onOpen` turns on for every connection - the three foreign keys this commit added fire, and
     * the fixture's own copy of the cross-file `push` delete stands in for the second file.
     */
    @Test
    fun deletingAnAccountLeavesNoOrphanedRowsInAnyCaptureTable() {
        val connection = withAnAccountAndItsChildren()
        Assert.assertEquals(
            "the fixture must start with a row in every table the finding names",
            3,
            populatedCaptureTables(connection).size)
        Assert.assertEquals(
            "and the search term must be indexed before the delete, or the FTS half proves " +
                "nothing",
            1,
            ftsMatches(connection, FTS_TERM))

        // The app's steady state: HistoryCallbacks.onOpen enables the constraints for the pool.
        JdbcSchemaExec(connection).exec("PRAGMA foreign_keys=ON")
        exec(connection, "DELETE FROM accounts WHERE uuid=?", ACCOUNT)
        // `updb.push` is not in this file and is not asserted here: it is the *second* SQLCipher
        // file, the one statement no cascade can carry, and `theSecondFilesPushRows...` executes it
        // over that file's own table.

        Assert.assertEquals(
            "the account's own row must be gone, or the rest of this test is measuring a " +
                "deletion that did not happen",
            0,
            countOrDefault(connection, "accounts", "uuid", ACCOUNT))
        Assert.assertEquals(
            "muted_participants must not keep the deleted account's rows: without the own " +
                "`account_uuid` foreign key it does, and the leak is real",
            0,
            countOrDefault(connection, RawTables.MUTED_TABLE, RawTables.MUTED_ACCOUNT, ACCOUNT))
        for (table in captureTables()) {
            Assert.assertEquals(
                table +
                    " still holds rows after its account was deleted: the foreign key " +
                    "that makes it the account's row is missing, or is not ON DELETE CASCADE",
                0L,
                scalarLong(connection, "SELECT COUNT(*) FROM " + table))
        }
        Assert.assertEquals(
            "messages_index still matches the deleted account's search term",
            0,
            ftsMatches(connection, FTS_TERM))
    }

    /**
     * The schema shape, not just the row counts: the cascade that clears a table has to be *in the
     * file*, and a fixture that quietly kept the pre-S5-12 shape would make the test above pass for
     * the wrong reason. Each pair is a table and the foreign-key target `PRAGMA foreign_key_list`
     * must name.
     */
    @Test
    fun theCascadesAreInTheSchemaAndNotOnlyInTheTest() {
        val connection = withAnAccountAndItsChildren()
        assertCascade(connection, "translation_queue", "messages")
        assertCascade(connection, RawTables.WEBXDC_TABLE, "conversations")
        assertCascade(connection, RawTables.MUTED_TABLE, "accounts")

        Assert.assertEquals(
            "translation_queue must reference the message itself, not the conversation: a " +
                "message removed on its own is the leak the finding measured",
            "messages",
            foreignKeyTarget(connection, "translation_queue"))
    }

    /**
     * The FTS half, executed: this is the auditor's measurement turned into a cell.
     *
     * <p>`messages_index` is external-content FTS4, so a delete has to read the row out of
     * `messages` to know which terms to drop. With the shipped `AFTER DELETE` trigger the row is
     * already gone and the delete resolves to nothing; the term survives. This asserts the term is
     * *found* before the delete and *gone* after it, which is only true with a `BEFORE DELETE`
     * trigger - and the migration is what replaces the old one on the owner's file.
     */
    @Test
    fun deletingAMessageTakesItsSearchTermsWithIt() {
        val connection = withAnAccountAndItsChildren()
        Assert.assertEquals(
            "the fixture's message must be findable before the delete, or the assertion after " +
                "it proves nothing",
            1,
            ftsMatches(connection, FTS_TERM))

        exec(connection, "DELETE FROM messages WHERE uuid=?", MESSAGE)

        Assert.assertEquals(
            "the deleted message's terms are still in messages_index: the delete trigger ran " +
                "AFTER the content row was gone, which is the leak S5-12 fixes",
            0,
            ftsMatches(connection, FTS_TERM))
    }

    /**
     * The cross-account mute leak, executed through the read the service actually calls.
     *
     * <p>`loadMutedMucUsers()` read the whole table, so a mute written under one of the owner's
     * accounts was handed to every other account with a room of the same JID - the audit's second
     * finding. This runs {@link RawTables#MUTED_FOR_ACCOUNT} for both accounts and asserts the
     * second's answer is empty, which is a statement about the read and not only about the row's
     * account column: dropping the account predicate from that query is what reddens it.
     */
    @Test
    fun aMuteWrittenForOneAccountIsNotTheOtherAccounts() {
        val connection = withAnAccountAndItsChildren()
        exec(connection, "INSERT INTO accounts (uuid, username) VALUES ('acct-other', 'other')")
        exec(
            connection,
            "INSERT INTO conversations (uuid, accountUuid, contactJid, mode) VALUES " +
                "('conv-other', 'acct-other', ?, 1)",
            MUC)

        Assert.assertEquals(
            "the room's mute is the first account's mute",
            1,
            rowsForAccount(connection, ACCOUNT))

        Assert.assertEquals(
            "the second account must not inherit the first's mute of the same room: the mute " +
                "was keyed by the room alone, which is the leak",
            0,
            rowsForAccount(connection, "acct-other"))
    }

    /**
     * The fifth table, and the one no cascade can reach: `updb`'s `push` is a *second* SQLCipher
     * file (`docs/MIGRATION.md`, "Design: the data layer" §2.6), so the account's registrations are
     * removed by an explicit delete in the deletion path. This executes that statement over the
     * `push` file's own rebuilt shape and asserts the account's rows go and another account's stay.
     */
    @Test
    fun theSecondFilesPushRowsAreDeletedByAccountAndNoOneElses() {
        val connection = plainMemory()
        exec(connection, PUSH_TABLE)
        exec(
            connection,
            "INSERT INTO push (account, transport, application, instance, expiration) " +
                "VALUES (?, 'tr', 'app', 'inst-a', 0)",
            ACCOUNT)
        exec(
            connection,
            "INSERT INTO push (account, transport, application, instance, expiration) " +
                "VALUES ('acct-keep', 'tr', 'app', 'inst-b', 0)")

        DaoSql.execNamed(connection, DELETE_PUSH_BY_ACCOUNT, "account", ACCOUNT)

        Assert.assertEquals(
            "the deleted account's push registration must be gone: nothing in the history " +
                "file can cascade into this one",
            0,
            scalarLong(connection, "SELECT COUNT(*) FROM push WHERE account='" + ACCOUNT + "'")
                .toInt())
        Assert.assertEquals(
            "another account's registration must survive",
            1,
            scalarLong(connection, "SELECT COUNT(*) FROM push WHERE account='acct-keep'").toInt())
    }

    /**
     * The deletion path is the app's, not the test's: `DatabaseBackend.deleteAccount` is still the
     * one entry point, and it is one accounts write plus the cross-file push delete. A pin on the
     * source is the only instrument for that on a host where SQLCipher cannot run
     * (`OpenHelperFactoryTest` uses the same one for the same reason), and it is here so the wire
     * cannot be dropped without a red test.
     *
     * <p><strong>The pin follows the write; it does not weaken it.</strong> `port-45` (`6ed022cfbf`)
     * moved the statement out of `DatabaseBackend` and into `AccountStore.delete`, so the entry
     * point is pinned as the delegation - the writable database, the account's uuid and the push
     * cleanup - and the statement itself is read where it now lives. The three facts are the three
     * the old pin held: the accounts row is deleted, the account's push registrations are cleared
     * because `updb` is a second file no cascade can reach, and `deleteAccount` is the one place
     * that wires the two together.
     */
    @Test
    fun theDeletionPathIsOneWritePlusTheCrossFilePushDelete() {
        val backend =
            RepoFiles.read("data/src/main/java/uk/xa0/tulkki/data/DatabaseBackend.kt")
        val method = backend.indexOf("override fun deleteAccount(account: AccountRef): Boolean =")
        Assert.assertTrue("deleteAccount must exist in DatabaseBackend", method > 0)
        val entry = backend.substring(method, backend.indexOf("\n\n", method))
        Assert.assertTrue(
            "deleteAccount must still be the one entry point that writes the account row: it " +
                "hands AccountStore.delete the writable database, the account's uuid and " +
                "the push cleanup",
            entry.contains("AccountStore.delete(") &&
                entry.contains("getWritableDatabase()") &&
                entry.contains("account.getUuid()") &&
                entry.contains("this::clearDeletedAccountPushRegistrations"))
        val store =
            RepoFiles.read("data/src/main/java/uk/xa0/tulkki/data/accounts/AccountStore.kt")
        Assert.assertTrue(
            "AccountStore.delete must delete the accounts row itself, by the published table " +
                "and key (AccountQueries.TABLE/UUID are Account.TABLENAME/Account.UUID)",
            store.contains(
                "db.delete(AccountQueries.TABLE, AccountQueries.UUID + \"=?\"," +
                    " arrayOf(uuid))"))
        val cleanup = backend.indexOf("private fun clearDeletedAccountPushRegistrations(")
        Assert.assertTrue("the push cleanup must exist in DatabaseBackend", cleanup > 0)
        val handoff = backend.substring(cleanup, backend.indexOf("\n    }", cleanup))
        Assert.assertTrue(
            "the cleanup deleteAccount hands over must clear the account's push registrations: " +
                "updb is a second file and no cascade can reach it",
            handoff.contains("deleteByAccount(accountUuid)"))
    }
}
