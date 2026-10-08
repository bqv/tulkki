package uk.xa0.tulkki.xmpp.services

import android.content.Context

/**
 * Tulkki: the OMEMO setting, in island vocabulary. Converted from the Java.
 *
 * It is a **`fun interface`** because `:app` builds it as a Kotlin SAM lambda -
 * `XmppTulkkiHost.kt:285 uk.xa0.tulkki.xmpp.services.OmemoSettingsPort { context -> OmemoSetting.load(context) }`
 * - and Kotlin's implicit SAM conversion is a Java-only courtesy: once the declaration is Kotlin
 * only `fun interface` keeps that call site compiling. `context` is non-null, matching the lambda.
 */
fun interface OmemoSettingsPort {

    fun apply(context: Context)
}
