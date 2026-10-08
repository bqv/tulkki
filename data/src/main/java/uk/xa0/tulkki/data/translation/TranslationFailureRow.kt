package uk.xa0.tulkki.data.translation

/**
 * One `translation_queue` row as the failures screen reads it (S5-6).
 *
 * <p>`:translation`'s `TranslationFailures.Failure` is that module's type - it carries the deciding,
 * the reason vocabulary and the label on the time - and `:data` may not import it. So the file's row
 * is its own value here and `TranslationStore` maps it, field for field, in one place.
 *
 * [failedAt] is a `Long` and not a nullable, exactly as the queue's own row has it: `0` is "no failure
 * time recorded", which is what a row written before schema 73 means, and the screen says so rather
 * than passing the arrival off as the failure.
 */
class QueueFailureRow(
    val messageUuid: String,
    val conversationUuid: String?,
    /** The message's own text, which the screen shows - the owner's reversal, see the queries. */
    val body: String?,
    val state: Int,
    val attempts: Int,
    val createdAt: Long,
    val failedAt: Long,
    val lastError: String?,
    /** The conversation's address as the database holds it, or null when it is gone. */
    val conversationJid: String?,
    /**
     * Item 17's cause for this row, or `null` for "no cause recorded" - an ordinary retryable API
     * failure or a row that never failed. The consumer is `:translation`'s `TranslationFailures`,
     * which owns the vocabulary these strings mean.
     */
    val failureCause: String?,
)

/**
 * One outgoing message's row as the failures screen reads it (S5-6): the sends whose translation
 * failed and which therefore never left the phone.
 */
class SendFailureRow(
    val messageUuid: String,
    val conversationUuid: String?,
    /** The message's own text, which the screen shows. */
    val body: String?,
    val timeSent: Long,
    /** The conversation's address as the database holds it, or null when it is gone. */
    val conversationJid: String?,
)
