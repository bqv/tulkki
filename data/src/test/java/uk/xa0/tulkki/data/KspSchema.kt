package uk.xa0.tulkki.data

import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern

/**
 * What Room's own generated code believes the schema is, read off the KSP output.
 *
 * <p><strong>Why this is the instrument.</strong> `docs/MIGRATION.md`, "Design: the data
 * layer" §2.5 and the answer recorded in it: a DAO or an entity that Room never processed is
 * <em>silent</em> - the compile is green, the `_Impl` is simply absent, and nothing warns.
 * The generated `HistoryDatabase_Impl.kt` is where Room's own view of the schema is written
 * down, so a test that reads it and executes what it finds is the strongest check available on a
 * host with no SQLCipher: room's `createAllTables` statements are real SQL, and a
 * `PRAGMA table_info` over them is exactly what `RoomConnectionManager.onMigrate`'s
 * `onValidateSchema` compares the owner's upgraded file against.
 *
 * <p><strong>What it does not do.</strong> It does not run Room's comparison, and it does not open
 * the owner's encrypted file. It reads build output that only exists after KSP has run, so a test
 * using it is a check of the compile's own product rather than of the runtime.
 */
object KspSchema {

    private const val HISTORY_DATABASE_IMPL =
            "data/build/generated/ksp/debug/kotlin/uk/xa0/tulkki/data/HistoryDatabase_Impl.kt"

    /**
     * The tree's second Room database (S5-3). It is a separate `@Database` in a separate file,
     * so its generated implementation is a separate build output - and the same silent-absence
     * failure applies to it: no `_Impl` means Room never processed the class.
     */
    private const val UPDB_DATABASE_IMPL =
            "data/build/generated/ksp/debug/kotlin/uk/xa0/tulkki/data/updb/UnifiedPushDatabase_Impl.kt"

    /** `connection.execSQL("…")`, the form Room's generated `createAllTables` emits. */
    private val EXEC_SQL = Pattern.compile("execSQL\\(\"((?:[^\"\\\\]|\\\\.)*)\"\\)")

    /** The generated database implementation, or a failure that names the missing build output. */
    fun databaseSource(): String {
        return databaseSource(HISTORY_DATABASE_IMPL, "HistoryDatabase_Impl")
    }

    /** The UnifiedPush database's generated implementation, with the same failure. */
    fun updbDatabaseSource(): String {
        return databaseSource(UPDB_DATABASE_IMPL, "UnifiedPushDatabase_Impl")
    }

    private fun databaseSource(relative: String, simpleName: String): String {
        val generated: Path = RepoFiles.root().resolve(relative)
        if (!Files.isRegularFile(generated)) {
            throw IllegalStateException(
                    "Room generated no "
                            + simpleName
                            + " at "
                            + generated
                            + ": KSP has not run, or the @Database class is not registered")
        }
        return RepoFiles.read(generated)
    }

    /**
     * Every `CREATE` statement Room's generated `createAllTables` emits whose text names
     * one of `names`, in generation order. The backticked identifier is what makes the match
     * exact: `sync_cursor` is the table's Room-quoted name, so a statement that merely
     * mentions the word in a foreign key is not selected by its own name alone.
     */
    fun ddlFor(vararg names: String): List<String> {
        return ddlIn(databaseSource(), *names)
    }

    /** The same, for the UnifiedPush database's generated `createAllTables`. */
    fun updbDdlFor(vararg names: String): List<String> {
        return ddlIn(updbDatabaseSource(), *names)
    }

    private fun ddlIn(source: String, vararg names: String): List<String> {
        val matcher = EXEC_SQL.matcher(source)
        val out = mutableListOf<String>()
        while (matcher.find()) {
            val statement = unescape(matcher.group(1))
            if (!statement.startsWith("CREATE ")) {
                continue
            }
            for (name in names) {
                if (statement.contains("`" + name + "`")) {
                    out.add(statement)
                    break
                }
            }
        }
        return out
    }

    /** Kotlin escapes a backtick inside a double-quoted literal; the SQL wants the bare one. */
    private fun unescape(literal: String): String {
        return literal.replace("\\`", "`").replace("\\\"", "\"").replace("\\\\", "\\")
    }
}
