package uk.xa0.tulkki.data.translation

import uk.xa0.tulkki.data.TranslationTables
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Message

/**
 * `translation/`'s failures-screen statements (S5-6): the two projections behind the list.
 *
 * <p>**Why the projections live here and the deciding does not.** `:translation`'s
 * `TranslationFailures` decides everything the screen shows - which rows still stand, in what order,
 * what each one's reason and time are - and it is pure Java over a list, so a JVM test exercises it.
 * Reading the rows is `SQLite`'s, and `SQLite` is `:data`'s, so the two statements move here and
 * `TranslationFailures` keeps only the deciding. The rows are handed on as [QueueFailureRow] and
 * [SendFailureRow], and `TranslationStore` maps them into the deciding type.
 *
 * <p>**Both select the message's own text**, which is the owner's reversal of "never show the
 * original" for this one surface (recorded in `TranslationFailures`' class comment): the screen lists
 * a failure with the words it is about. The cache key - which is derived from the text - has no
 * business in either read, and a cell in `TranslationFailureQueriesTest` says so.
 */
internal object TranslationFailureQueries {

    /** The alias both projections give the conversation's own address. */
    const val CONVERSATION_JID = "conversation_jid"

    /**
     * The received messages that failed, from the translation queue.
     *
     * <p>`attempts > 0` is the whole of the narrowing: a message that never failed is not a failure,
     * and the order is deliberately not in the SQL - a `LIMIT` here would cut before the
     * still-outstanding filter ran, so a settled row could push out a live one.
     *
     * <p>The join is a `LEFT` one: a failure whose conversation has been deleted since is still a
     * failure, and it is shown with the address rather than dropped. `body` is selected on purpose,
     * and the row cascades with its message, so a failure whose text is gone is not in this result set
     * at all.
     */
    @JvmField
    val QUEUE_FAILURES: String =
        "SELECT q." +
            TranslationTables.QUEUE_MESSAGE_UUID +
            ", q." +
            TranslationTables.QUEUE_CONVERSATION_UUID +
            ", q." +
            TranslationTables.QUEUE_BODY +
            ", q." +
            TranslationTables.QUEUE_STATE +
            ", q." +
            TranslationTables.QUEUE_ATTEMPTS +
            ", q." +
            TranslationTables.QUEUE_CREATED_AT +
            ", q." +
            TranslationTables.QUEUE_FAILED_AT +
            ", q." +
            TranslationTables.QUEUE_LAST_ERROR +
            ", q." +
            TranslationTables.QUEUE_FAILURE_CAUSE +
            ", c." +
            Conversation.CONTACTJID +
            " AS " +
            CONVERSATION_JID +
            " FROM " +
            TranslationTables.QUEUE_TABLE +
            " q LEFT JOIN " +
            Conversation.TABLENAME +
            " c ON c." +
            Conversation.UUID +
            " = q." +
            TranslationTables.QUEUE_CONVERSATION_UUID +
            " WHERE q." +
            TranslationTables.QUEUE_ATTEMPTS +
            " > 0"

    /**
     * The sends whose translation failed, from the message rows themselves.
     *
     * <p>An outgoing message that needs translating is written to the database <em>before</em> the
     * attempt, so it is its own record of the failure. The reason is not on this row - the app keeps
     * the last failure, not a list of them - which is where that limit shows. `body` is selected on
     * purpose, the same reversal; `deleted = 0` keeps the one case with nothing to show out.
     */
    @JvmField
    val SEND_FAILURES: String =
        "SELECT m." +
            Message.UUID +
            ", m." +
            Message.CONVERSATION +
            ", m." +
            Message.BODY +
            ", m." +
            Message.TIME_SENT +
            ", c." +
            Conversation.CONTACTJID +
            " AS " +
            CONVERSATION_JID +
            " FROM " +
            Message.TABLENAME +
            " m LEFT JOIN " +
            Conversation.TABLENAME +
            " c ON c." +
            Conversation.UUID +
            " = m." +
            Message.CONVERSATION +
            " WHERE m." +
            Message.TRANSLATION_STATE +
            "=? AND m." +
            Message.STATUS +
            ">? AND m." +
            Message.DELETED +
            "=0"
}
