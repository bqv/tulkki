package uk.xa0.tulkki.crypto

import android.app.PendingIntent

/**
 * The continuation of a PGP operation.
 *
 * Declared here, in `:crypto`, because the engine is what needs it and the caller is what the
 * engine may not name: `:crypto` naming `:ui`'s `UiCallback` was the whole of the forbidden
 * `:crypto` → `:ui` direction (pair 6 of docs/MIGRATION.md "The cycle rules" §3), and a package
 * move cannot fix a direction that points up. The port lives on the side that consumes it, so the
 * implementation is always the higher module's.
 *
 * The shape mirrors `UiCallback` method for method - a port is named after the operation whose
 * continuation it is, and mirrors the interface it replaces - so that pair 5's
 * `OmemoAccount`/`PgpStore`/`OtrPeer`, pair 7's `OmemoSessionPort` and pair 11's `UiCallback`
 * counterpart read as one family rather than three dialects. The *homes* differ, because the
 * consumer sits in a different module in each pair; that is the same shape declared where the
 * consumer is, not a second vocabulary.
 */
interface PgpCallback<T> {
    fun success(`object`: T)

    fun error(errorCode: Int, `object`: T?)

    fun userInputRequired(pi: PendingIntent?, `object`: T)
}
