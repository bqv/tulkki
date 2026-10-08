package uk.xa0.tulkki.data.translation

import java.time.Instant

/**
 * The translation queue as a screen reads it (S5-6; `docs/MIGRATION.md`, "Design: the synchronisation"
 * §3.3): how much is owed, how much has finally failed, and when the next attempt is due.
 *
 * <p>**It is the read model, not the table.** `translation_queue`'s rows are the pump's work list and
 * [TranslationQueueStore] is their door; this is the three numbers a badge or a diagnostics screen
 * draws from, and it is the vocabulary the coordinator fixed - `QueueSummary` beside `SyncStatus` and
 * `ConversationSummary` - so a `:ui` screen never opens the queue's table or knows a state number.
 *
 * <p>**The state numbers are the caller's**, exactly as they are for every other statement in this
 * package: which number means "pending" is `:translation`'s `TranslationQueue.Item.STATE_PENDING`,
 * and `:data` may not import `:translation`. [TranslationQueueDao.summary] therefore takes them as
 * parameters rather than holding a second spelling.
 *
 * <p>[nextAttemptAt] is null when nothing is pending, which is `MIN` over an empty set and not a
 * second reading of "zero": an instant of zero would be a time in 1970 and would draw as one.
 */
data class QueueSummary(
    /** Rows waiting for a translation attempt. */
    val pending: Int,
    /** Rows the retry schedule has given up on, until the owner asks for one again. */
    val failed: Int,
    /** When the earliest pending row is due, or null when the queue owes nothing. */
    val nextAttemptAt: Instant?,
)
