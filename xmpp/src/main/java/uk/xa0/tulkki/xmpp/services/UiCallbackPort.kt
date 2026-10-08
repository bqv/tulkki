package uk.xa0.tulkki.xmpp.services

import android.app.PendingIntent

/**
 * Tulkki: the callback the HTTP and upload paths hand the UI.
 *
 * Method for method with `uk.xa0.tulkki.ui.UiCallback`, which now extends this. The island's
 * three users (this interface, `HttpConnectionManager` and `HttpUploadConnection`) take the port;
 * every caller in `:ui` and `:app` still passes a `UiCallback`.
 *
 * `T` keeps its implicit `Any?` bound and `success` takes a bare `T`, so an answer of `null` is
 * spelled by instantiating the port with a nullable type argument (`UiCallbackPort<Void?>`), not by
 * widening this declaration. `error`'s object and `userInputRequired`'s are the shapes
 * `uk.xa0.tulkki.crypto.PgpCallback` and `uk.xa0.tulkki.ui.UiCallback` already declare, because the
 * UI twin overrides all three here and neither twin moved.
 */
interface UiCallbackPort<T> {

    fun success(`object`: T)

    fun error(errorCode: Int, `object`: T?)

    fun userInputRequired(pi: PendingIntent?, `object`: T)
}
