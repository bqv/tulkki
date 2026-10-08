package uk.xa0.tulkki.data.schema

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The Room migration from the file the owner has (75) to the schema this commit declares (76).
 *
 * <p>Its body is [Schema76.applyUpgrade] and nothing else: "Design: the data layer" §4.2 makes the
 * callers' inability to disagree the design's whole job, and the fresh-install callback runs
 * [Schema76.applySchema] out of the same object. The legacy `DatabaseBackend.onUpgrade` hook was
 * the second caller for one commit and went in S5-5.
 *
 * <p>It deliberately registers no destructive-fallback option - `DestructiveMigrationBanTest` fails
 * on the builder methods' own spelling, comments included, which is why this one does not print it -
 * and the reason is the file: a wrong migration must refuse to open rather than recreate itself over
 * the owner's years of messages. See §3.3 for the one
 * measurement (Room adopting a file with no `room_master_table`) that decides whether this version
 * pair is the right one at all.
 */
val MIGRATION_75_76: Migration =
    object : Migration(75, Schema76.VERSION) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Schema76.applyUpgrade(db)
        }
    }

/**
 * The migration that adds `blocking/`'s table (76 -> 77). It runs [Schema77.applySchema] and
 * nothing else, for the same reason the 75 -> 76 migration runs [Schema76.applyUpgrade]: the
 * fresh-install callback and the Room migration must execute one definition.
 */
val MIGRATION_76_77: Migration =
    object : Migration(Schema76.VERSION, Schema77.VERSION) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Schema77.applySchema(db)
        }
    }

/**
 * The migration the day-split ledger arrives in (77 -> 78), and with it the retirement of the
 * `onOpen` repair.
 *
 * <p>It runs [Schema78.applyUpgrade] and nothing else: the guarded S5-12 late-shape repair - the
 * same bodies `Schema77.repairLateColumns` holds, and for one commit the only caller of them - and
 * this version's own statements. A file that took a stale 77 build is rebuilt into the shapes a
 * correct 77 file already has; a file that took a correct one pays three reads.
 *
 * <p>This is the position the repair needed and did not have: a `Migration` runs **before** Room
 * validates the declared entities, so `translation_queue`'s declaration in this same version is
 * checked against a table that has already been repaired.
 */
val MIGRATION_77_78: Migration =
    object : Migration(Schema77.VERSION, Schema78.VERSION) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Schema78.applyUpgrade(db)
        }
    }

/**
 * The migration the per-conversation doubt-hold switch arrives in (78 -> 79). It runs
 * [Schema79.applyUpgrade] and nothing else: this version adds one guarded nullable column to the
 * conversation table, and a file that reached 78 was already repaired by `MIGRATION_77_78` - running
 * 78's stale-77 repair again is not what that shape means.
 */
val MIGRATION_78_79: Migration =
    object : Migration(Schema78.VERSION, Schema79.VERSION) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Schema79.applyUpgrade(db)
        }
    }

/**
 * The migration item 17's per-row failure cause arrives in (79 -> 80). It runs
 * [Schema80.applyUpgrade] and nothing else: this version adds one guarded nullable column to the
 * translation queue, and a file that reached 79 has already been repaired by `MIGRATION_77_78`.
 */
val MIGRATION_79_80: Migration =
    object : Migration(Schema79.VERSION, Schema80.VERSION) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Schema80.applyUpgrade(db)
        }
    }

/** Every migration the opener registers. Five today; the array is the shape, not a promise. */
object TulkkiMigrations {
    @JvmField
    val ALL: Array<Migration> =
        arrayOf(
            MIGRATION_75_76,
            MIGRATION_76_77,
            MIGRATION_77_78,
            MIGRATION_78_79,
            MIGRATION_79_80,
        )
}
