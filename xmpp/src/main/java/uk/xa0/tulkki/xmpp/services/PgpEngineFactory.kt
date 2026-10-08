package uk.xa0.tulkki.xmpp.services

import org.openintents.openpgp.util.OpenPgpApi

/**
 * Tulkki: how `:crypto` builds a [PgpEnginePort] over an `OpenPgpApi`.
 *
 * Converted from the Java. It is a **`fun interface`**, and that is load-bearing rather than
 * decorative: `:app` constructs it as a Kotlin SAM lambda -
 * `XmppTulkkiHost.kt:282 uk.xa0.tulkki.xmpp.services.PgpEngineFactory { api, service -> PgpEngine(api, service) }`.
 * Kotlin SAM conversion works implicitly for a Java interface and only for a `fun interface` once
 * the declaration is Kotlin, so a plain `interface` would break that call site.
 *
 * Both parameters are non-null: the lambda above dereferences neither defensively, and its only
 * caller, `ServiceSeams.kt:110-130`, resolves both before constructing.
 */
fun interface PgpEngineFactory {

    fun create(api: OpenPgpApi, service: XmppConnectionService): PgpEnginePort
}
