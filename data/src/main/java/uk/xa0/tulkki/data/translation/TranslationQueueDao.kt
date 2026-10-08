package uk.xa0.tulkki.data.translation

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * The queue's counts, as a screen collects them (schema 78, S5-6).
 *
 * <p>**The SQL is spelled here and nowhere else, and the test reads it back off this annotation**,
 * exactly as `blocking/`'s DAO is written: a `const val` named by the annotation compiles to the same
 * string, but a later reader must be able to see the query Room will run without opening another
 * file. `TranslationQueueSummaryTest` asserts the annotation equals
 * [TranslationQueueQueries.SUMMARY] and then executes that string over a JDBC fixture.
 *
 * <p>**The state numbers are parameters, not literals.** Which number means "pending" is
 * `:translation`'s fact and `:data` may not import it; a `:data` constant would be a second answer
 * that only a test could keep in step. The caller passes them.
 *
 * <p>**Why the read is one statement rather than three queries combined.** The three numbers are one
 * fact about one table at one instant; three `Flow`s combined would be three snapshots that can
 * disagree, and `MIN` over an empty set is SQL `NULL` - which is the honest answer for a queue that
 * owes nothing, and is not the same as an instant of zero.
 *
 * <p>**Registration is the accessor.** `daos = [...]` alone is silently inert: Room processes a DAO
 * only when the database exposes it, so `HistoryDatabase.translationQueueDao()` is what makes this
 * real. `roomGeneratedTheImplementation` in the test is the instrument that says so.
 *
 * <p>`internal`, like every DAO here: the module's public surface is the read models
 * (`docs/MIGRATION.md`, "Design: the data layer" §2.5).
 */
@Dao
internal interface TranslationQueueDao {

    /**
     * The three numbers, and the instant the earliest pending row is due. `Flow`, so a screen redraws
     * when the pump changes the table rather than polling a static.
     */
    @Query(
        "SELECT (SELECT COUNT(*) FROM translation_queue WHERE state = :pendingState) AS pending, " +
            "(SELECT COUNT(*) FROM translation_queue WHERE state = :failedState) AS failed, " +
            "(SELECT MIN(next_attempt_at) FROM translation_queue WHERE state = :pendingState) " +
            "AS nextAttemptAt",
    )
    fun summary(pendingState: Int, failedState: Int): Flow<QueueSummary>
}
