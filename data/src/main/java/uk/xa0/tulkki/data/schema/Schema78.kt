package uk.xa0.tulkki.data.schema

import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabase
import uk.xa0.tulkki.data.TranslationTables

/**
 * Schema 78 (the `data` stage): the day's spend split by origin, and the shapes version 77 changed
 * after files had already taken it.
 *
 * <p>**Why the version moves at all, given 77 is still unshipped.** The three S5-12 shapes
 * (`translation_queue`'s key, `muted_participants`' owner, `webxdc_updates`' key) were edited
 * *inside* 77, after the S5-3 batches had already registered `MIGRATION_76_77`. A device that
 * installed a build in between stamped its file 77 with the legacy shapes, and no later build runs a
 * migration for a version the file already has - the owner's own phone died at launch on
 * `no such column: account_uuid`, and S5-12 patched the hole with an `onOpen` repair
 * (`Schema77.repairLateColumns`) because a callback cannot move a version. 78 is what lets the
 * repair go back where it belongs: a 77 file, correct or stale, is walked by `MIGRATION_77_78`, and
 * the stale ones are rebuilt into the shapes a correct 77 file already has.
 *
 * <p>**The two functions, and why they differ.** [applySchema] is the *whole* definition of 78, so
 * the fresh-install path calls it and nothing else - a fresh file is created at 78 directly rather
 * than by replaying 77 and then 78. [applyUpgrade] is only what a 77 file is missing: the guarded
 * repairs (a stale file is rebuilt, a correct one pays three reads) and this version's own new
 * table. The two converge because [applySchema] runs `Schema77.applySchema`, which already declares
 * the three tables in their 78 shapes; there is one spelling of each shape and the repair runs the
 * same `Schema76.rebuild` with the same columns and the same tail.
 *
 * <p>**No entity is declared here.** `translation_queue` becomes an entity in the same version, and
 * that is safe precisely because [applyUpgrade] runs the repair inside a `Migration` - before Room
 * validates anything. A callback could not have done that, which is the whole reason
 * `repairLateColumns` existed and the whole reason it is retired.
 *
 * <p>Foreign keys off is a checked precondition of [applyUpgrade], not a comment: the repairs drop
 * and recreate tables, and `DROP TABLE` on a parent with foreign keys on is an implicit `DELETE`
 * that fires cascades.
 */
object Schema78 {

    /** The file's version once this schema is in place. `DatabaseBackend.DATABASE_VERSION` names it. */
    const val VERSION = 78

    /** Every statement 78 adds, each `IF NOT EXISTS` so a second run is a no-op. */
    @JvmField
    val STATEMENTS: List<String> = listOf(TranslationTables.CREATE_USAGE_ORIGIN_TABLE)

    @JvmStatic fun applySchema(db: SupportSQLiteDatabase) = applySchema(SchemaExecs.of(db))

    @JvmStatic fun applySchema(db: SQLiteDatabase) = applySchema(SchemaExecs.of(db))

    /**
     * The whole definition of 78: everything 77 defines - its own table, the seven declared tables
     * it rebuilds, the three S5-12 shapes and the FTS delete trigger - plus this version's own
     * statements. The one caller is the fresh-install path, which needs the final shape and not the
     * history of how the file would have got there.
     */
    @JvmStatic
    fun applySchema(exec: SchemaExec) {
        Schema77.applySchema(exec)
        for (statement in STATEMENTS) {
            exec.exec(statement)
        }
    }

    @JvmStatic fun applyUpgrade(db: SupportSQLiteDatabase) = applyUpgrade(SchemaExecs.of(db))

    @JvmStatic fun applyUpgrade(db: SQLiteDatabase) = applyUpgrade(SchemaExecs.of(db))

    /**
     * What a 77 file lacks: the guarded late-shape repair, then this version's own statements.
     *
     * <p>The repair is `Schema77`'s own bodies - the same rebuilds with the same columns and tails
     * that `Schema77.attributeAndCascadeTheAccountScopedTables` runs for a file that never took 77 -
     * so a stale 77 file lands exactly where a correct one already is, key and cascade included.
     */
    @JvmStatic
    fun applyUpgrade(exec: SchemaExec) {
        Schema76.requireForeignKeysOff(exec)
        Schema77.repairLateColumns(exec)
        for (statement in STATEMENTS) {
            exec.exec(statement)
        }
    }
}
