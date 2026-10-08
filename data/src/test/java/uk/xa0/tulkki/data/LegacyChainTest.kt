package uk.xa0.tulkki.data

import androidx.room.migration.Migration
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.schema.TulkkiMigrations

/**
 * S5-5's first gate, mechanically: `DatabaseBackend` has no `onCreate`, no `onUpgrade` and no
 * `CREATE`, and the file below 75 that the deleted chain used to walk forward now refuses.
 *
 * The scan is here rather than in a comment because the deletion is the whole point of the commit,
 * and a source scan is the only thing that keeps the class from growing a schema half back. It is
 * the same shape as `DestructiveMigrationBanTest`: text over `src/main`, no allow-list, and the
 * assertion is about the literals a DDL statement cannot be written without.
 *
 * The second cell is the choice the row asked to be made out loud rather than drifted into: the
 * pre-75 preflight is **deleted**. A file below 75 has no registered path, so Room's
 * `RoomOpenHelper.onUpgrade` throws instead of recreating anything - the S5-5 row's own measurement -
 * and `HistoryDatabase`'s class note records it. Deleting the preflight is the moment a device below
 * 75 can no longer open its database, and this test is what makes that moment visible to the next
 * writer: adding a 74 -> 75 path means adding a migration whose start version is below 75, and this
 * fails on it.
 *
 * **The scan's target is Kotlin now, and the needles moved with it.** `DatabaseBackend.java`
 * converted in `5e5e7241df` and left this file reading a path that no longer exists, which made the
 * cell error out rather than prove anything; the path here names the `.kt` and the lifecycle needles
 * are the Kotlin spellings, because a Java `void onCreate(SQLiteDatabase` can never appear in a
 * Kotlin file and the check would have been vacuous.
 */
class LegacyChainTest {

    private val backend = "data/src/main/java/uk/xa0/tulkki/data/DatabaseBackend.kt"

    /**
     * The literals, quoted: a DDL statement is a string literal, so the quote is what separates the
     * ban from prose about it.
     */
    private val ddlStarts = arrayOf(
        "\"CREATE TABLE", "\"CREATE INDEX", "\"CREATE VIRTUAL", "\"CREATE TRIGGER", "\"ALTER TABLE",
    )

    private val lifecycle = arrayOf(
        "fun onCreate(", "fun onUpgrade(", "fun onConfigure(", "fun createSchema(",
    )

    @Test
    fun theConnectionOwnerHasNoSchemaLifecycleAndNoDdl() {
        val text = RepoFiles.read(backend)
        for (name in lifecycle) {
            Assert.assertFalse(
                "DatabaseBackend must not declare " + name + "; the schema half moved to" +
                    " `RawTables` and the packages in S5-5",
                text.contains(name))
        }
        for (literal in ddlStarts) {
            Assert.assertFalse(
                "DatabaseBackend must not spell DDL: found " + literal + " in " + backend,
                text.contains(literal))
        }
        Assert.assertFalse(
            "the legacy chain's own guarded helper must be gone with it",
            text.contains("ensureMessageFileDeletedColumn"))
        Assert.assertFalse(
            "and the 74-guard chain's table builders, one by one",
            text.contains("recreateMessageIndex") || text.contains("canonicalizeJids"))
    }

    /**
     * The preflight is deleted, not kept: every registered migration starts at 75 or above, so a 75
     * file has a path and a 74 file has none. A 74 -> 75 migration would mean restoring the legacy
     * DDL chain S5-1 removed, which the row calls a new task if it is ever wanted.
     */
    @Test
    fun noRegisteredMigrationStartsBelowSeventyFive() {
        Assert.assertTrue(
            "there must be at least one registered migration or this cell proves nothing",
            TulkkiMigrations.ALL.size > 0)
        for (migration in TulkkiMigrations.ALL) {
            Assert.assertTrue(
                "no migration may start below 75: a pre-75 path is the preflight that S5-5" +
                    " deleted on purpose (found " +
                    migration.startVersion +
                    " -> " +
                    migration.endVersion +
                    ")",
                migration.startVersion >= 75)
        }
        Assert.assertEquals(
            "the version in force is the last migration's end, and Room owns it",
            80,
            DatabaseBackend.DATABASE_VERSION)
    }
}
