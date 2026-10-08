package uk.xa0.tulkki.data.backup

/**
 * One column of one backed-up row: the file's own name for it, and the text the file holds.
 *
 * <p>`null` is SQL's `NULL` and nothing else. A value that is not text - a `NUMBER` column, a
 * `BOOLEAN` - is read as the text SQLite renders it to, because the backup file is a JSON document
 * of `{table, values:{column: value}}` objects and every value in it is written as text or as null
 * (`ExportBackupWorker` writes a JSON number for exactly one column, and that is its serializer's
 * rule rather than this read's). Nothing here converts a value: the read answers what the file
 * holds, and the one place that changes a value on the way out is the exporter's own column rules.
 *
 * <p>**The names are the file's, and that is the point.** A backed-up row is only faithful if it
 * carries every column the table has, under the name the table has: the importer inserts by name,
 * so a column this read forgot is a column the owner's restore silently loses. `BackupQueries`
 * therefore selects `*` from each named table rather than listing columns, and
 * `BackupExportTest` compares each read's columns with the table's own `PRAGMA table_info`.
 */
class BackupColumn(
    /** The column's name, exactly as `PRAGMA table_info` spells it. */
    val name: String,
    /** The value, or null for SQL's `NULL`. */
    val value: String?,
) {
    override fun toString(): String = name + "=" + (value ?: "null")
}

/**
 * One backed-up row: the table it belongs to, and its columns in the file's own order.
 *
 * <p>**Why this is a read model and not a raw row.** The export used to hand the worker a
 * `SQLiteDatabase` and a table name, and the worker composed `select * from <name> where <column>=?`
 * itself - so every table, every column and every selection was spelled outside `:data`, and none of
 * them was `:data`'s to know. This type is the opposite: the caller names one of the export's own
 * sets ([BackupExport.account], [BackupExport.messageRows]), `:data` owns the statement that
 * answers it, and [table] and [columns] are what the file holds rather than a shape the caller chose.
 * There is no statement a caller can build from this.
 */
class BackupRow(
    /** The persisted table this row belongs to. */
    val table: String,
    /** The row's columns, in the order the file declares them. */
    val columns: List<BackupColumn>,
) {
    /** The column's value by name, or `null` when the row has no such column at all. */
    fun value(column: String): String? = columns.firstOrNull { it.name == column }?.value

    override fun toString(): String = table + "(" + columns.joinToString(",") + ")"
}
