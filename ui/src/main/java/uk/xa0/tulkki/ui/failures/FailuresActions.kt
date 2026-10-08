package uk.xa0.tulkki.ui.failures

import uk.xa0.tulkki.translation.TranslationFailures

/**
 * What a failures row asks the host to do, emitted by the row and performed by the host.
 *
 * <p>It is the same split the rest of the screens make (§7.4, "**Composables stay dumb**: take a
 * `Ui*` state, emit ids"): the screen decides *which* action a row affords, from the row's own
 * disposition through [FailureAction], and the host owns the reading a Composable may not make - it
 * resolves the row's message and calls the routes the conversation already has. This interface is
 * declared inside `:ui` and implemented by `:ui`'s own host (`TranslationFailuresFragment`), so it
 * is not a new seam between `:ui` and the composition root.
 *
 * <p>Three methods and not one with a verb: the two kinds take different routes, and the retry and
 * "send as written" are different decisions about the same send. Not performing them here is the
 * point - [FailureAction] says which are offered, and the host says what they mean.
 */
interface FailuresActions {

    /** Translate this one received message now, exactly as the covered bubble's tap does. */
    fun translateNow(failure: TranslationFailures.Failure)

    /** Re-attempt this one held send through the conversation bar's own retry. */
    fun retry(failure: TranslationFailures.Failure)

    /** Send this one held send as the owner wrote it, through the bar's own exception. */
    fun sendAsWritten(failure: TranslationFailures.Failure)
}
