package uk.xa0.tulkki.data.messages

import android.util.Log
import com.google.common.base.Stopwatch
import net.zetetic.database.sqlcipher.SQLiteDatabase
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.data.schema.Schema76
import uk.xa0.tulkki.data.schema.SchemaExec
import uk.xa0.tulkki.data.schema.SchemaExecs
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.utils.FtsUtils

/**
 * `messages/`'s capability: the FTS message index - its rebuild, its fragmentation decision and the
 * one search statement built over it.
 *
 * <p>**The next capability out of `DatabaseBackend`'s grab-bag** (`docs/MIGRATION.md`'s `port-45`
 * row is the inventory it comes out of). Six live methods move here: `requiresMessageIndexRebuild`
 * (the flag and its accessor), `rebuildMessagesIndex` twice, `isFtsIndexFragmented`,
 * `indexMustBeRebuilt` and `buildMessageSearchQuery`; `DatabaseBackend` is reduced to one-line
 * delegations, so every caller class compiles against what it did before.
 *
 * <p>**The home is an `object` that takes the `SQLiteDatabase`** rather than a store that owns it.
 * `DatabaseBackend` is the one owner of the file (`HistoryDatabase`'s Room connection, handed in by
 * `getInstance`); a package that opened its own would be the second owner the design forbids. The
 * two seam methods (`rebuildMessagesIndex`/`indexMustBeRebuilt`, over a [SchemaExec]) stay seam
 * methods, because that is the only spelling of the statement list a plain-JVM test can execute
 * (SQLCipher ships Android ABIs only).
 *
 * <p>**The Java-visible surface is unchanged: `@JvmStatic` on every method.** A `@JvmStatic`
 * member of an `object` is the only shape whose static bridge carries the un-mangled name - a
 * Kotlin `internal` member would be emitted as `rebuildMessagesIndex$data` and Java could not see
 * it (the `ScriptReading`/`TranslationLanguages` precedent). The static bridge is also what keeps
 * `SchemaExecs`' `internal` adapters reachable: this package is in the same module.
 *
 * <p>**The nested `SearchQuery` was un-nested in the same commit**, to a top-level type in this
 * package: the query builder left the outer class, and a type written `Outer.Inner` cannot be
 * handed back from here. Its two outside code spellings were the search tests' two locals.
 *
 * <p>**The null contracts are read off the callers.** `buildMessageSearchQuery`'s `uuid` is
 * nullable - the tests pass `null` and `SnapshotRepositories.search` hands in its own `String?` -
 * while `term` is the parsed list every caller passes non-null. The `SQLiteDatabase` and
 * [SchemaExec] arguments, and the `Boolean` answers, are non-null. No `@Throws`: nothing on this
 * path throws a checked exception the Java catches.
 */
object MessageIndexStore {

    /**
     * Tulkki: the message index is built from `translated_body`, not from `body`.
     *
     * <p>The search reads this table, and a search is an answer about words the interface refuses
     * to show: with the raw body indexed, typing a foreign word told the owner whether a covered
     * original contained it, which is the original's content escaping one query at a time.
     * Indexing the app-language side instead makes the index hold exactly what the interface would
     * draw for a row - the translation, or nothing at all while the row is covered - so a covered
     * body cannot be found, and a word the owner can read is what they can search for.
     *
     * <p>The column name is used in four places (this query, the table, both triggers) and
     * `Message.TRANSLATED_BODY` is the same string, so the table's `content=` rebuild reads the
     * same column the triggers write. `SearchInvariantTest` pins this file's spelling of it.
     */
    private val MESSAGE_INDEX_COLUMN = Message.TRANSLATED_BODY

    /**
     * The FTS4 shadow table the fragmentation count is asked of. SQLite creates it with the index,
     * so its absence is the index's absence - see [indexMustBeRebuilt].
     */
    private const val MESSAGE_INDEX_SHADOW_TABLE = "messages_index_segdir"

    /** More than this many segments means the index has grown badly and wants a rebuild. */
    private const val FRAGMENTED_SEGMENT_ROWS = 4

    /**
     * Whether an import left the index needing a rebuild. The Java field was never written, so the
     * answer is its initial value; it stays a property rather than an inlined literal so the
     * accessor still reads state, exactly as the Java did.
     */
    private var indexRebuildRequired: Boolean = false

    /**
     * The rebuild trigger an import sets; the accessor `XmppConnectionService` reads through
     * `DataStaticsHost`.
     */
    @JvmStatic
    fun requiresMessageIndexRebuild(): Boolean = indexRebuildRequired

    /**
     * The rebuild itself, over the live connection: the decision and the statements are the seam
     * methods below, so this method is only the connection and the timing log.
     */
    @JvmStatic
    fun rebuildMessagesIndex(db: SQLiteDatabase) {
        val stopwatch = Stopwatch.createStarted()
        rebuildMessagesIndex(SchemaExecs.of(db))
        Log.d(Config.LOGTAG, "rebuilt message index in " + stopwatch.stop().toString())
    }

    /**
     * The rebuild itself, and it **builds the index when there is none**.
     *
     * <p>It runs `messages/`'s own statement list - the eight indexes, the FTS4 table and its three
     * triggers, all `IF NOT EXISTS` - and then the deferred `'rebuild'`, which re-reads the table
     * through the indexed column (see [Schema76.MESSAGE_INDEX_STATEMENTS]). That is what makes
     * [isFtsIndexFragmented]'s "it must be rebuilt" answer for an absent index an answer the caller
     * can act on rather than a crash moving one call later: a file created by a build whose
     * fresh-install callback never ran - the owner's file - has no `messages_index` at all, and the
     * bare `'rebuild'` this method used to run failed with `no such table: messages_index`.
     *
     * <p>The list is the migration's and the fresh install's ([uk.xa0.tulkki.data.schema.RawTables]),
     * not a copy, so the three paths cannot drift. Over the [SchemaExec] seam because it is the only
     * spelling of this statement list a JVM test can execute (SQLCipher ships Android ABIs only).
     */
    @JvmStatic
    fun rebuildMessagesIndex(exec: SchemaExec) {
        for (statement in Schema76.MESSAGE_INDEX_STATEMENTS) {
            exec.exec(statement)
        }
    }

    /**
     * Whether the message index has to be rebuilt, which includes the case where it is **not there
     * at all** (Tulkki fix).
     *
     * <p>The count is asked of [MESSAGE_INDEX_SHADOW_TABLE], the FTS4 shadow table SQLite creates
     * with the index. A file that never had the index has no such table, and the query used to
     * throw straight out of here - `SQLiteException: no such table: messages_index_segdir (code 1)`
     * - killing the restore thread on a fresh install's first launch. An index that does not exist
     * cannot be "not fragmented": it must be **built**, and building it is exactly what the caller
     * does next, so absence answers `true` rather than throwing.
     *
     * <p>The caller's contract is unchanged: `restoreFromDatabase` runs this after
     * `requiresMessageIndexRebuild()` and rebuilds when either says so.
     *
     * <p>The decision is taken over the [SchemaExec] seam rather than a raw cursor so the absent
     * branch is executable on the host, over the statements' own SQL, with a plain-JVM SQLite
     * (`MessageIndexRebuildTest`) - the device run is what proves SQLCipher's own file.
     */
    @JvmStatic
    fun isFtsIndexFragmented(db: SQLiteDatabase): Boolean =
        indexMustBeRebuilt(SchemaExecs.of(db))

    /** [isFtsIndexFragmented]'s decision, apart from the connection. */
    @JvmStatic
    fun indexMustBeRebuilt(exec: SchemaExec): Boolean {
        if (!exec.hasTable(MESSAGE_INDEX_SHADOW_TABLE)) {
            return true
        }
        val rows = exec.rows("SELECT count(*) FROM $MESSAGE_INDEX_SHADOW_TABLE", emptyArray<String>())
        val count: String? = if (rows.isEmpty()) null else rows[0].getOrNull(0)
        if (count == null) {
            // The count could not be read at all, which is not evidence of a healthy index.
            return true
        }
        return count.toInt() > FRAGMENTED_SEGMENT_ROWS
    }

    /**
     * Tulkki (S4-14): the one spelling of the search query, returned rather than run.
     *
     * <p>It is a seam with a reason, not a tidy-up. The query is the whole of the leak rule on this
     * surface - it matches [MESSAGE_INDEX_COLUMN], which is `translated_body`, so a row the
     * interface would cover has nothing to match and can never be a result - and a JVM test can
     * execute it against a fixture where SQLCipher cannot open the owner's file. A test that
     * re-spelled the SQL would prove nothing about the query the app runs; this one runs it.
     *
     * <p>It is not mode-aware and must not become so: the interpreter decides how a result is
     * *drawn* (the bubble's own decision, through `MessageAdapter`), never what can be found.
     */
    @JvmStatic
    fun buildMessageSearchQuery(term: List<String>, uuid: String?): SearchQuery {
        val matchString = FtsUtils.toMatchString(term)
        // Use a subquery so SQLite drives the query from the FTS index: it resolves the MATCH
        // to a small set of rowids first, then fetches only those rows from messages by rowid
        // (the fastest possible access). The old 3-table JOIN left query plan choice ambiguous.
        val columns =
            "m.*, c." +
                Conversation.CONTACTJID +
                ", c." +
                Conversation.ACCOUNT +
                ", c." +
                Conversation.MODE
        val encryptionFilter =
            Message.ENCRYPTION +
                " NOT IN(" +
                Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE +
                "," +
                Message.ENCRYPTION_PGP +
                "," +
                Message.ENCRYPTION_DECRYPTION_FAILED +
                "," +
                Message.ENCRYPTION_AXOLOTL_FAILED +
                ")"
        val typeFilter =
            Message.TYPE + " IN(" + Message.TYPE_TEXT + "," + Message.TYPE_PRIVATE + ")"
        val sql = StringBuilder()
        val selectionArgs: Array<String>
        sql.append("SELECT ")
            .append(columns)
            .append(" FROM ")
            .append(Message.TABLENAME)
            .append(" m")
            .append(" JOIN ")
            .append(Conversation.TABLENAME)
            .append(" c ON m.")
            .append(Message.CONVERSATION)
            .append("=c.")
            .append(Conversation.UUID)
            .append(" WHERE m.rowid IN (SELECT rowid FROM messages_index WHERE ")
            .append(MESSAGE_INDEX_COLUMN)
            .append(" MATCH ?)")
            .append(" AND ")
            .append(encryptionFilter)
            .append(" AND ")
            .append(typeFilter)
        if (uuid == null) {
            selectionArgs = arrayOf(matchString)
        } else {
            selectionArgs = arrayOf(matchString, uuid)
            sql.append(" AND c.").append(Conversation.UUID).append("=?")
        }
        sql.append(" ORDER BY m.")
            .append(Message.TIME_SENT)
            .append(" DESC LIMIT ")
            .append(Config.MAX_SEARCH_RESULTS)
        return SearchQuery(sql.toString(), selectionArgs, matchString)
    }
}
