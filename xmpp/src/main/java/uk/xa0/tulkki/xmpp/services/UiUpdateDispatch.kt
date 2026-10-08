package uk.xa0.tulkki.xmpp.services

import android.content.Context
import android.graphics.Bitmap
import uk.xa0.tulkki.libs.AudioDevice
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.OnKeyStatusUpdated
import uk.xa0.tulkki.xmpp.OnUpdateBlocklist
import uk.xa0.tulkki.xmpp.crypto.OmemoSessionPort
import uk.xa0.tulkki.xmpp.forms.Data
import uk.xa0.tulkki.xmpp.jingle.RtpEndUserState
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ContactRef

/**
 * Tulkki: the ui update dispatch, lifted out of `XmppConnectionService`
 *.
 *
 * Every method here was a fan-out over one of chunk `C34`'s private weak listener sets. Those sets
 * and the snapshot helper `threadSafeList` belong to chunks that do not move with this one, and the
 * **snapshot travels in** rather than a visibility being widened: the service takes it under
 * `LISTENER_LOCK` and hands the already-copied list over, so the listeners are still called outside
 * the lock, exactly as the Java's `for (… : threadSafeList(…))` did. That is also why each method
 * here is a plain loop over its parameter.
 *
 * `updateRosterUi`'s contract survives intact: the guard belonged to the pre-port
 * **one-argument** overload (which delegated to the two-argument one with `null`), so it throws
 * `IllegalArgumentException` only when `PRESENCE` arrives **without** a contact — and then before
 * any listener is called. The two-argument overload never threw, and the ordinary
 * `PresenceParser` path hands in a real contact. `displayCaptchaRequest` is the one
 * method with a guard of its own — it reads the live set's size and answers false before it scales
 * anything — so that test travels in as a boolean and the snapshot follows it.
 * `OnUpdateBlocklist` and `OnKeyStatusUpdated` are island top-level interfaces; the rest are the
 * service's own nested listener types, named here in full.
 */
object UiUpdateDispatch {

    @JvmStatic
    fun notifyRtpConnection(
        account: AccountRef,
        withJid: Jid,
        sessionId: String,
        state: RtpEndUserState,
        listeners: List<OnJingleRtpConnectionUpdate>,
    ) {
        for (listener in listeners) {
            listener.onJingleRtpConnectionUpdate(account, withJid, sessionId, state)
        }
    }

    @JvmStatic
    fun notifyAudioDeviceChanged(
        selectedAudioDevice: AudioDevice,
        availableAudioDevices: Set<AudioDevice>,
        listeners: List<OnJingleRtpConnectionUpdate>,
    ) {
        for (listener in listeners) {
            listener.onAudioDeviceChanged(selectedAudioDevice, availableAudioDevices)
        }
    }

    @JvmStatic
    fun updateAccount(listeners: List<OnAccountUpdate>) {
        for (listener in listeners) {
            listener.onAccountUpdate()
        }
    }

    /**
     * The guard is the pre-port one-argument overload's, restored to its condition: the Java threw
     * in `updateRosterUi(reason)` and delegated to `updateRosterUi(reason, null)`; the two-argument
     * method it delegated to had no guard at all. Collapsing both into this one function carried the
     * throw over unconditionally, which made the ordinary `PresenceParser -> updateRosterUi(reason,
     * contact)` path crash. The faithful union of the two overloads is `PRESENCE` **and** a null
     * contact; the reason is checked before the listeners run.
     */
    @JvmStatic
    fun updateRoster(
        reason: UpdateRosterReason,
        contact: ContactRef?,
        listeners: List<OnRosterUpdate>,
    ) {
        if (reason == UpdateRosterReason.PRESENCE && contact == null) {
            throw IllegalArgumentException("PRESENCE must also come with a contact")
        }
        for (listener in listeners) {
            listener.onRosterUpdate(reason, contact)
        }
    }

    @JvmStatic
    @Suppress("DEPRECATION")
    fun displayCaptchaRequest(
        context: Context,
        hasListeners: Boolean,
        account: AccountRef,
        id: String,
        data: Data,
        captcha: Bitmap,
        listeners: List<OnCaptchaRequested>,
    ): Boolean {
        if (hasListeners) {
            val metrics = context.resources.displayMetrics
            val scaled =
                Bitmap.createScaledBitmap(
                    captcha,
                    (captcha.width * metrics.scaledDensity).toInt(),
                    (captcha.height * metrics.scaledDensity).toInt(),
                    false,
                )
            for (listener in listeners) {
                listener.onCaptchaRequested(account, id, data, scaled)
            }
            return true
        }
        return false
    }

    @JvmStatic
    fun updateBlocklist(
        status: OnUpdateBlocklist.Status,
        listeners: List<OnUpdateBlocklist>,
    ) {
        for (listener in listeners) {
            listener.OnUpdateBlocklist(status)
        }
    }

    @JvmStatic
    fun updateMucRoster(listeners: List<OnMucRosterUpdate>) {
        for (listener in listeners) {
            listener.onMucRosterUpdate()
        }
    }

    /**
     * `report` is nullable, because the Java this replaces was: `AxolotlService.registerDevices`
     * reports a device-list change with a literal `null` (`AxolotlService.kt`), the pre-port
     * `XmppConnectionService` took an unannotated `FetchStatus` and forwarded it unchanged, and the
     * listeners are Java. Typing it non-null here added a `checkNotNullParameter` that the null
     * call site had never had to satisfy, and it killed the process on the first device-list change
     * after connect.
     */
    @JvmStatic
    fun keyStatusUpdated(
        report: OmemoSessionPort.FetchStatus?,
        listeners: List<OnKeyStatusUpdated>,
    ) {
        for (listener in listeners) {
            listener.onKeyStatusUpdated(report)
        }
    }
}
