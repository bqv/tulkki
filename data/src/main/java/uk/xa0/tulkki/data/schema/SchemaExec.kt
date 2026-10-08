package uk.xa0.tulkki.data.schema

/**
 * The four things the schema-76 work asks of a connection, and nothing else.
 *
 * There are two owners of the one file during the one commit that has both (S5-2): Room's `Migration`,
 * which is handed an `androidx.sqlite.db.SupportSQLiteDatabase`, and the legacy hook, which is handed a
 * `net.zetetic.database.sqlcipher.SQLiteDatabase`. They must execute the *same* statements - that is the
 * whole point of the shared list - so the statements and the reads that feed the seed live once, in
 * [Schema76], and this is the seam they run through. It is deliberately a plain interface rather than a
 * class: the JVM harness in `src/test` implements it over a plain JDBC connection, which is the only way
 * any of this can be executed on the host (SQLCipher ships Android ABIs only; see `docs/MIGRATION.md`,
 * "Design: the data layer" §4.4).
 *
 * [rows] returns `String[]` per row rather than a `Cursor` for the same reason: a test that had to fake
 * `android.database.Cursor` would be testing the fake. Nulls survive as nulls, because "no anchor" and
 * "anchor zero" are different rows in the seed.
 */
interface SchemaExec {

    fun exec(sql: String)

    fun exec(sql: String, args: Array<Any?>)

    /** Whether `table` has a column named `column`; the guarded-ALTER check. */
    fun hasColumn(table: String, column: String): Boolean

    /**
     * Whether `table` exists at all. S5-12's rebuilds need it: a table Tulkki added at schema 71 is
     * absent from a file that never reached it, and a rebuild or a prune against a table that is not
     * there throws.
     */
    fun hasTable(table: String): Boolean

    /** Every row of `sql`, each as its columns in order, read as text. */
    fun rows(sql: String, args: Array<String>): List<Array<String>>
}
