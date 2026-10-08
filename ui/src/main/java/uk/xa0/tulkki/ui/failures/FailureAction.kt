package uk.xa0.tulkki.ui.failures

import uk.xa0.tulkki.translation.TranslationFailures

/**
 * What one failures row can ask for, decided by the failure's own disposition and nothing else.
 *
 * <p>`TranslationFailures.Next` already says what happens next, and the two kinds want opposite
 * things. A received message is the queue's - it will be tried again, or the schedule has run out -
 * and either way the owner may ask for that one message now, exactly as the cover's tap does: the
 * automatic pass translates in bulk against the cap, while a tap translates one message the pass
 * would defer. A send is the owner's: nothing re-attempts it, so the row offers the two actions the
 * conversation's bar already carries - the retry that buys a fresh answer, and "send as written",
 * which is the owner's one exception to "nothing is sent untranslated".
 *
 * <p>It is a pure function of the disposition, so [FailureActionTest] exercises it on the JVM with
 * no device and no `Context`, and the screen's own `when` cannot grow an opinion of its own.
 */
enum class FailureAction {
    /** Translate this one received message now: the cover's own tap, offered from the list. */
    TRANSLATE_NOW,

    /** The conversation bar's retry: ask DeepSeek again for this one held send. */
    RETRY,

    /** The conversation bar's exception: send this one held row as the owner wrote it. */
    SEND_AS_WRITTEN;

    companion object {

        /**
         * The actions a row offers, in the order it draws them.
         *
         * <p>A received row offers the one action whether the queue still means to retry or has
         * given up - the difference is what the row says happens next, not what the owner may ask
         * for. A held send offers both of the bar's, retry first because sending the original is
         * the deliberate exception and not the default.
         *
         * <p>A `null` disposition offers nothing: it is a disposition no factory produces, and an
         * unknown one must not become a button.
         */
        @JvmStatic
        fun forFailure(next: TranslationFailures.Next?): List<FailureAction> =
            when (next) {
                TranslationFailures.Next.RETRY,
                TranslationFailures.Next.GAVE_UP -> listOf(TRANSLATE_NOW)
                TranslationFailures.Next.HELD -> listOf(RETRY, SEND_AS_WRITTEN)
                null -> emptyList()
            }
    }
}
