package uk.xa0.tulkki.xmpp.services

import uk.xa0.tulkki.libs.AudioDevice
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.jingle.RtpEndUserState
import uk.xa0.tulkki.xmpp.refs.AccountRef

/**
 * Tulkki: a Jingle RTP connection's state change and the audio-route change beside it, un-nested out
 * of `XmppConnectionService`.
 *
 * Converted from the Java, with one nullability reading and one signature reading:
 *
 *  * `sessionId` is **non-null**. Both the Kotlin caller chain - `XmppConnectionService.java:2877`
 *    passes its own `final String sessionId` into `UiUpdateDispatch.notifyRtpConnection`, whose
 *    parameter is already declared `sessionId: String` - and the second implementer,
 *    `RtpSessionActivity.kt:1464`, say so. `ConnectionService.kt:243` declared it `String?` against
 *    the Java platform type; that widening is a narrowing of itself and is corrected in the same
 *    commit, because a Kotlin override's parameter type must match exactly (measured with kotlinc
 *    2.3.21). `account`, `with` and `state` are non-null for the same reason: every caller passes
 *    values it just dereferenced.
 *  * `onAudioDeviceChanged` keeps the Java's **invariant** `Set<AudioDevice>`, hence
 *    `@JvmSuppressWildcards`. The wildcard is a fact about the type argument's **finality, not its
 *    language** (kotlinc 2.3.21, measured by lane E, 2026-10-08): Kotlin emits
 *    `Set<? extends AudioDevice>` for an enum type argument in a parameter position whether the
 *    enum is Java or Kotlin - only a Java `final class` argument drops the wildcard - so
 *    `AudioDevice`, now a Kotlin `enum class` (`libs/annotation/.../AudioDevice.kt`), needs the
 *    suppression exactly as much as the Java enum did. The neighbouring `AppRTCAudioManager.kt`
 *    carries the identical note for the identical reason. `selectedAudioDevice` is non-null: every
 *    implementation dereferences it.
 */
interface OnJingleRtpConnectionUpdate {
    fun onJingleRtpConnectionUpdate(
        account: AccountRef,
        with: Jid,
        sessionId: String,
        state: RtpEndUserState,
    )

    @JvmSuppressWildcards
    fun onAudioDeviceChanged(
        selectedAudioDevice: AudioDevice,
        availableAudioDevices: Set<AudioDevice>,
    )
}
