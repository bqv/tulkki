package uk.xa0.tulkki.data.translation

/**
 * One `translation_queue` row as `:data` reads and writes it (S5-6).
 *
 * <p>**Why not `TranslationQueue.Item`.** That type is `:translation`'s, and `:data` may not import
 * it - the dependency runs the other way. So the row is its own value here and `TranslationStore`
 * maps between the two, field for field, in one place. Nothing about the row is decided here: it
 * carries the file's columns and no state machine, no backoff arithmetic and no language rule, all
 * of which stay in `:translation` where they are tested.
 *
 * [failedAt] is a `Long` and not a nullable, exactly as `Item.failedAt` has it: `0` is "no failure
 * time recorded", which is what a row written before schema 73 and a database where that migration
 * did not land both mean, and what the failures screen says rather than passing the arrival off as
 * the failure.
 */
class TranslationQueueRow @JvmOverloads constructor(
    val messageUuid: String,
    val conversationUuid: String?,
    val body: String,
    val targetLanguage: String?,
    val cacheKey: String,
    val state: Int,
    val attempts: Int,
    val nextAttemptAt: Long,
    val lastError: String?,
    val createdAt: Long,
    val failedAt: Long,
    /**
     * Item 17's cause, or `null` for "no cause recorded" - never "refused". Defaulted to absent
     * because `:translation`'s `TranslationStore` builds this row too and predates the column
     * (`TranslationStore`); `TranslationQueueStore` always passes it explicitly.
     */
    val failureCause: String? = null,
)
