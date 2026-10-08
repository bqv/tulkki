package uk.xa0.tulkki.xmpp.services

import android.app.PendingIntent
import android.content.Intent

/**
 * Tulkki: the OpenPGP engine, in island vocabulary. Converted from the Java.
 *
 * The Java wrote every parameter unannotated, and the only implementation - `:crypto`'s `PgpEngine`,
 * already Kotlin - decides each one, so this declaration is read off `PgpEngine.kt:315-345` rather
 * than off the Java's blank text:
 *
 *  * `fetchKeyId`: `account`, `status` and `signature` are **all nullable**. The implementation
 *    declares `fetchKeyId(account: Any?, status: String?, signature: String?)`, and the island's
 *    callers supply exactly that: `PresenceParser.kt:457` passes `packet.findChildContent("status")`,
 *    which is a `String?`, and `:185` passes `status.getContent()` only on a branch that found the
 *    child. `Object` was Java's unconstrained type and maps to `Any?` for the same reason.
 *  * `generateSignature`'s `intent` is **nullable**, and there is direct call-site proof:
 *    `EditAccountActivity.kt:1317` declares `private fun generateSignature(intent: Intent?, …)` and
 *    calls the port with it, and `:1305` passes a literal `null` down that path. `account`,
 *    `callback` and `getIntentForKey`'s `pgpKeyId` are non-null there; `status` is the Java's
 *    non-null `String` and the implementation agrees.
 *  * `getIntentForKey` **returns `PendingIntent?`**. The implementation answers
 *    `result.getParcelableExtra<PendingIntent>(...)`, which is null when OpenKeychain returns no
 *    result intent, and one caller already reads it as nullable (`ui/.../ui/MucUsersActivity.kt:287`
 *    reads it as `val intent = pgpEngine.getIntentForKey(...) ?: return`; the deleted
 *    `ui/adapter/UserAdapter.kt:95` did the same with `if (intent != null)`).
 *  * The `<T>` members stay generic and unbounded, with `callback: Any?` - the island may name
 *    neither `PgpCallback` nor the `Omemo*` types the engine's own overloads ride on, so the
 *    callback travels as an `Object` and `PgpEngine` casts it back.
 */
interface PgpEnginePort {

    fun fetchKeyId(account: Any?, status: String?, signature: String?): Long

    fun <T> encrypt(message: T, callback: Any?)

    fun <T> chooseKey(account: T, callback: Any?)

    fun <T> hasKey(contact: T, callback: Any?)

    fun generateSignature(intent: Intent?, account: Any?, status: String, callback: Any?)

    fun getIntentForKey(pgpKeyId: Long): PendingIntent?
}
