package uk.xa0.tulkki.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import uk.xa0.tulkki.ui.ChooseAccountForProfilePictureActivity
import uk.xa0.tulkki.ui.MemorizingActivity
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.RtpSessionActivity
import uk.xa0.tulkki.ui.XmppActivity
import uk.xa0.tulkki.ui.utils.LiveLocationManager
import uk.xa0.tulkki.ui.utils.ThemeHelper
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.services.MemorizingTrustManager
import uk.xa0.tulkki.xmpp.services.XmppConnectionService

/**
 * Pair 11 of {@code docs/MIGRATION.md} "The cycle rules" §3 (D4): everything the XMPP island asks
 * of {@code :ui}, in one class, next to [XmppTulkkiHost], which is where the composition root already
 * keeps the other end of the boundary.
 *
 * <p>Each method is the body that used to sit inside the island, moved here verbatim: the four
 * activity/theme reach-ins that named a {@code :ui} class, the live-location manager the island and
 * its message parser used to call statically, and the TLS decision screen. Nothing about them is
 * behaviour, only ownership — the island decides, this names the class.
 *
 * <p>It lives in {@code :app} rather than in {@code :ui} on purpose: {@code :ui} naming an island class
 * is a {@code ui-reaches-island} violation, and pair 11's gate holds that ratchet at 242, so the
 * implementation half of a port cannot live there without moving the ratchet. {@code :app} may name
 * both ends, which is what a composition root is for.
 *
 * <p>It is a Kotlin `object`, which is exactly the hand-written singleton the Java was: the JVM sees
 * `public static final UiTulkkiPorts INSTANCE`, the spelling `XmppTulkkiHost` already asks for, and
 * the stateless class gets its one instance for free. The Java's `private` constructor appears here
 * as Kotlin's rule that an `object` has none.
 */
object UiTulkkiPorts :
        uk.xa0.tulkki.xmpp.services.LiveLocationHook,
        uk.xa0.tulkki.xmpp.services.RtpSessionPort,
        uk.xa0.tulkki.xmpp.services.ThemePort,
        uk.xa0.tulkki.xmpp.services.ProfilePictureActivityPort,
        MemorizingTrustManager.DecisionScreenPort {

    // -- the live-location manager -----------------------------------------------------------------
    //
    // The island reads a session's ids, feeds it positions and asks whether a preview is due; every
    // method is the manager's own accessor. `outgoingMessageUuid` is what
    // `XmppConnectionService.getLiveLocationMessageUuid` used to do by hand over
    // `getAllOutgoingSessions()`, and the `OutgoingSession` type stays on this side of the boundary.

    override fun hasSession(sessionId: String): Boolean =
            LiveLocationManager.getInstance().getSession(sessionId) != null

    override fun sessionConversationUuid(sessionId: String): String? =
            LiveLocationManager.getInstance().getSession(sessionId)?.conversationUuid

    override fun sessionMessageUuid(sessionId: String): String? =
            LiveLocationManager.getInstance().getSession(sessionId)?.messageUuid

    override fun registerIncomingSession(
            sessionId: String,
            conversationUuid: String?,
            messageUuid: String?,
            lat: Double,
            lon: Double,
            expiresAt: Long) {
        LiveLocationManager.getInstance()
                .registerIncomingSession(
                        sessionId, conversationUuid, messageUuid, lat, lon, expiresAt)
    }

    override fun updateIncomingPosition(sessionId: String, lat: Double, lon: Double) {
        LiveLocationManager.getInstance().updateIncomingPosition(sessionId, lat, lon)
    }

    override fun isPreviewRefreshDue(sessionId: String, throttleMs: Long): Boolean =
            LiveLocationManager.getInstance().isPreviewRefreshDue(sessionId, throttleMs)

    override fun expireIncomingSession(sessionId: String) {
        LiveLocationManager.getInstance().expireIncomingSession(sessionId)
    }

    override fun sessionIdForMessage(messageUuid: String): String? =
            LiveLocationManager.getInstance().getSessionIdForMessage(messageUuid)

    override fun registerOutgoingSession(
            conversationUuid: String,
            sessionId: String,
            messageUuid: String,
            expiresAt: Long,
            lat: Double,
            lon: Double) {
        LiveLocationManager.getInstance()
                .registerOutgoingSession(
                        conversationUuid, sessionId, messageUuid, expiresAt, lat, lon)
    }

    override fun notifyOutgoingPositionUpdate(sessionId: String, lat: Double, lon: Double) {
        LiveLocationManager.getInstance().notifyOutgoingPositionUpdate(sessionId, lat, lon)
    }

    override fun outgoingMessageUuid(sessionId: String): String? {
        for (outgoing in LiveLocationManager.getInstance().allOutgoingSessions) {
            if (sessionId == outgoing.sessionId) {
                return outgoing.messageUuid
            }
        }
        return null
    }

    override fun clearOutgoingSession(conversationUuid: String) {
        LiveLocationManager.getInstance().clearOutgoingSession(conversationUuid)
    }

    override fun setOnSessionExpired(callback: Runnable) {
        LiveLocationManager.getInstance().setOnSessionExpiredUiCallback(callback)
    }

    // -- the call screen ---------------------------------------------------------------------------

    override fun sessionIdExtra(): String = RtpSessionActivity.EXTRA_SESSION_ID

    override fun launchAcceptCall(
            context: Context,
            accountJid: String,
            with: String,
            sessionId: String) {
        val intent = Intent(context, RtpSessionActivity::class.java)
        intent.action = RtpSessionActivity.ACTION_ACCEPT_CALL
        intent.putExtra(XmppActivity.EXTRA_ACCOUNT, accountJid)
        intent.putExtra(RtpSessionActivity.EXTRA_WITH, with)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        intent.putExtra(RtpSessionActivity.EXTRA_SESSION_ID, sessionId)
        context.startActivity(intent)
    }

    // -- the theme and the profile-picture component ------------------------------------------------

    // Tulkki, 3.8-r: `setTheme` used to name `R.style.Theme_Tulkki` inside the island, which after
    // the split defines no style at all. The style is `:ui`'s, so the id is handed in from here -
    // the composition root, which may name both ends - through the port the island already had.
    // The reference is spelled BARE on purpose: pre-split there is one merged `R`, and the boundary
    // harness's re-point qualifies it to `:ui`'s `R` after the apply. Writing the post-split FQN by
    // hand here is the one spelling the pre-split build cannot resolve
    // (the 3.8-r remaining hand-work commit cebe3e9388, section 4.3).
    override fun theme(): Int = R.style.Theme_Tulkki

    override fun applyCustomColors(context: Context) {
        ThemeHelper.applyCustomColors(context)
    }

    override fun setEnabled(context: Context, enabled: Boolean) {
        try {
            val name = ComponentName(context, ChooseAccountForProfilePictureActivity::class.java)
            val targetState =
                    if (enabled) {
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    } else {
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    }
            context.packageManager
                    .setComponentEnabledSetting(name, targetState, PackageManager.DONT_KILL_APP)
        } catch (e: IllegalStateException) {
            Log.d(Config.LOGTAG, "unable to toggle profile picture activity")
        }
    }

    // -- the TLS decision screen --------------------------------------------------------------------

    override fun open(
            context: Context,
            decisionUri: String,
            decisionId: Int,
            message: String,
            titleId: Int) {
        val intent = Intent(context, MemorizingActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        intent.data = Uri.parse(decisionUri)
        intent.putExtra(MemorizingTrustManager.DECISION_INTENT_ID, decisionId)
        intent.putExtra(MemorizingTrustManager.DECISION_INTENT_CERT, message)
        intent.putExtra(MemorizingTrustManager.DECISION_TITLE_ID, titleId)
        context.startActivity(intent)
    }
}
