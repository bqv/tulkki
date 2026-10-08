package uk.xa0.tulkki.data.model

import uk.xa0.tulkki.libs.Avatarable
import uk.xa0.tulkki.data.utils.DisplayNames
import uk.xa0.tulkki.libs.Jid

/**
 * One logged call as the database hands it to the call list: who, when, how it ended, and whether it
 * was a video call.
 *
 * <p>Ported from Java by the `port` stage (port-2). The decisions, recorded rather than inherited:
 *
 * 1. **The six getters stay `val`s, and two of them are spelled for the getter Java wrote.** A Kotlin
 *    `val contact` generates `getContact()`, `val status` generates `getStatus()`, and so on, so the
 *    JVM surface is Java's - but Kotlin's `is`-prefix rule means the property has to be written
 *    `isVideoCall`/`isSuccessful` to generate `isVideoCall()`/`isSuccessful()`. Spelling them
 *    `videoCall`/`successful` would have produced `getVideoCall()`/`getSuccessful()` and broken the
 *    one Java site that names this type, `:ui`'s `UIHelper.getCallInfo` (`:607`), which calls
 *    `call.getStatus()` and `call.isSuccessful()`.
 * 2. **[Avatarable]'s two members are `override fun`s.** `Avatarable` is Kotlin now, so `override` is
 *    mandatory where Java's `@Override` was advisory, and the bodies are Java's: both read the
 *    constructor property [contact] directly rather than through the generated getter.
 * 3. **Nothing is annotated.** The six getters are generated methods, not fields, and the one Java
 *    reader calls them as methods, so there is no `@JvmField` a creditor asks for and no `@JvmStatic`
 *    (the type has no static member). **Interop debt: zero.**
 * 4. **Measured: the type is unreachable from the tree today.** `grep` finds no `new Call(` and no
 *    `Call.class` anywhere, and `UIHelper.getCallInfo` - the only site that names the type - has no
 *    caller of its own. The port therefore preserves the API without claiming a consumer; deleting
 *    the type is its own decision and its own commit, not a translation.
 *
 * It stays a plain `class`, not a `data class`, and final: Java had identity equality (nothing
 * overrides `equals`) and no `extends Call` exists in the tree (`grep` finds none).
 */
class Call(
    val contact: String,
    val jid: Jid,
    val startTime: Long,
    val status: Int,
    val isVideoCall: Boolean,
    val isSuccessful: Boolean,
) : Avatarable {

    /** The colour a generated avatar for this call is drawn over. */
    override fun getAvatarBackgroundColor(): Int = DisplayNames.getColorForName(contact)

    /** The name a generated avatar for this call shows. */
    override fun getAvatarName(): String = contact
}
