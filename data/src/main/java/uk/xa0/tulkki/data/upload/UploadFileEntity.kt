package uk.xa0.tulkki.data.upload

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * `cids` — the local-file map: which content-addressed file backs which `messages.relativeFilePath`.
 * Losing it loses the owner's attachments, so it is the owner's data rather than a cache
 * (`docs/MIGRATION.md`, "Design: the data layer" §1.7).
 *
 * **Why this table and not `messages`/`conversations` (S5-1).** Room validates a file it did not
 * create by comparing every declared column's normalised type *affinity*
 * (`TableInfo.Column.equalsCommon`, last term, unconditional). Room normalises a declared type with
 * no `INT`/`CHAR`/`CLOB`/`TEXT`/`BLOB`/`REAL`/`FLOA`/`DOUB` in it to `ColumnInfo.UNDEFINED`, and
 * this schema declares its numeric columns `NUMBER`; the entity side can only ever emit
 * `TEXT|INTEGER|REAL|BLOB`, because `ColumnInfo.SQLiteTypeAffinity` has no `UNDEFINED` member and
 * `PropertyProcessor` overwrites `typeAffinity` with the type adapter's affinity anyway. So every
 * `NUMBER` column would compare `UNDEFINED` against `INTEGER` and the open would be refused.
 * `cids` is the table whose *every* declared type is `TEXT`, so it is the only one this step can
 * declare without lying about the file.
 *
 * **It is also what the annotation forces.** Room rejects a `@Database` with an empty entity list
 * outright (`@Database annotation must specify list of entities`), measured by running KSP, so
 * "declare nothing until the migration lands" is not available.
 *
 * The declarations are deliberately the *persisted* shape rather than a Kotlin-shaped one: the
 * names are the column names as the device spells them, and the nullability is `PRAGMA table_info`'s
 * `notnull` (`cid` and `path` are `NOT NULL`; `url` was added by a guarded `ALTER` and is nullable).
 * `SchemaNameTest` compares the two against `src/test/resources/schema-75.sql`, so a drift fails a
 * test instead of the owner's open.
 */
@Entity(tableName = "cids")
internal data class UploadFileEntity(
    @PrimaryKey @ColumnInfo(name = "cid") val cid: String,
    @ColumnInfo(name = "path") val path: String,
    @ColumnInfo(name = "url") val url: String?,
)
