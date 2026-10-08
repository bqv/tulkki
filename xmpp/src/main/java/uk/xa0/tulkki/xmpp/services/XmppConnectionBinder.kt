package uk.xa0.tulkki.xmpp.services

import android.os.Binder

/**
 * Tulkki: the service's binder, un-nested out of `XmppConnectionService` and converted from the Java.
 *
 * The nested class was an inner class, so `getService()` answered its enclosing instance
 * implicitly; the un-nesting gave the service a constructor argument, and this conversion keeps it
 * as a field. The service is **non-null**: `XmppConnectionService.java:304` constructs the binder
 * with `this` and the getter is dereferenced at every call site.
 *
 * `getService()` stays a **function**, not a `val service` property, and that is deliberate.
 * Kotlin synthesises a property from a *Java* getter but not from a Kotlin function, and every
 * caller uses the function spelling today: `BarcodeProvider.kt:188`,
 * `CallIntegrationConnectionService.kt:601`, `ConnectionService.kt:60`,
 * `UpdateNowPlayingService.kt:47` and `XmppActivity.kt:178-179` all call `binder.getService()`. A
 * Kotlin `val` would silently drop those call sites, while a function keeps every Java caller too
 * (`getService()` is the JVM name either way).
 */
class XmppConnectionBinder(private val service: XmppConnectionService) : Binder() {

    fun getService(): XmppConnectionService = service
}
