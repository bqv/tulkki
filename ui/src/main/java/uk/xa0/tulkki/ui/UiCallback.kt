package uk.xa0.tulkki.ui

import android.app.PendingIntent

import uk.xa0.tulkki.crypto.PgpCallback

/**
 * The UI's continuation, and the app's general callback: uploads, media and posts use it as much as
 * PGP does.
 *
 * `extends PgpCallback<T>` is pair 6 of docs/MIGRATION.md "The cycle rules" §3: the PGP engine may not
 * name `:ui`, so `:crypto` declares the continuation as a port and this is where it is implemented -
 * without a method changing and without one call site moving. It is the one `:ui` -> `:crypto` name
 * that the ratchet is allowed to see here, and D13 (`:ui.allow += [":crypto"]`) is declared in the
 * same commit that adds it.
 *
 * `extends uk.xa0.tulkki.xmpp.services.UiCallbackPort<T>` is pair 11 (§3, D4) and is the same shape for the
 * island: the XMPP service, the HTTP upload connection and the download manager all take a
 * continuation, may not name this one, and now take the island's twin. Nothing moved: the three
 * methods are the same three, the ~40 call sites in `:ui` and `:app` still pass a `UiCallback`, and
 * `XmppConnectionService`'s own two anonymous instances simply implement the port instead.
 *
 * The island's name is written in full rather than imported on purpose: an `import` line naming an
 * island class is a `ui-reaches-island` site - this file already carries one, the `PgpCallback` above,
 * which is pair 6's - and pair 11's gate holds that ratchet at 242, so the twin is named in the body
 * exactly as the `:data` retypes in this commit are.
 */
interface UiCallback<T> :
    PgpCallback<T>,
    uk.xa0.tulkki.xmpp.services.UiCallbackPort<T> {

    override fun success(obj: T)

    override fun error(errorCode: Int, obj: T?)

    override fun userInputRequired(pi: PendingIntent?, obj: T)
}
