package uk.xa0.tulkki.ui.rtp

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The call screen's four cells - the incoming call and a connected audio call, each in both themes
 * (docs/MIGRATION.md "Design: the Compose UI" §7.1/§7.2, the harness the chrome and the other
 * converted screens use).
 *
 * <p>**What the cells pin.** Everything the deleted `activity_rtp_session.xml` and `dialpad.xml`
 * drew that a preview can draw: the chrome's title and overflow, `with` and `with_jid`, the
 * monospaced duration, the verified shield, the "using account" line, the dialpad with its letters,
 * and both button rows - reject and accept inside the 288 dp box, and the end-call group with the
 * microphone and the audio-route control beside it. The names and the accessible names are the real
 * string resources, read through `stringResource`, so a wording change moves a pixel here.
 *
 * <p>**What they skip, and why.** The two WebRTC surfaces (`remote_video` and `local_video`) and
 * the in-call `contact_photo` are platform views - `org.webrtc.SurfaceViewRenderer` and a
 * `ShapeableImageView` loaded by `AvatarWorkerTask` - that layoutlib cannot draw and a preview
 * cannot construct, so [RtpCallScreen] takes them as slots and these fixtures pass empty ones. No
 * cell renders a video frame or an avatar; `RtpSessionActivity` fills the same slots with
 * `AndroidView` at runtime.
 *
 * <p>The fixture states are [RtpCallState] values, which is what the screen is handed, so a
 * reference here is a picture of the state and not of a hand-written row that could drift.
 */
@PreviewTest
@Preview(
    name = "rtp-incoming-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun RtpIncomingDarkScreenshot() = Fixture(incoming = true, darkTheme = true)

@PreviewTest
@Preview(
    name = "rtp-incoming-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun RtpIncomingLightScreenshot() = Fixture(incoming = true, darkTheme = false)

@PreviewTest
@Preview(
    name = "rtp-call-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun RtpCallDarkScreenshot() = Fixture(incoming = false, darkTheme = true)

@PreviewTest
@Preview(
    name = "rtp-call-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun RtpCallLightScreenshot() = Fixture(incoming = false, darkTheme = false)

/** The screen where production composes it, themed, with the three platform slots left empty. */
@Composable
private fun Fixture(incoming: Boolean, darkTheme: Boolean) {
    TulkkiTheme(darkTheme = darkTheme) {
        RtpCallScreen(
            state = callState(incoming),
            events = QuietEvents,
            remoteVideo = { _ -> },
            localVideo = { _ -> },
            contactPhoto = { _ -> },
        )
    }
}

@Composable
private fun callState(incoming: Boolean): RtpCallState {
    val usingAccountText = stringResource(R.string.using_account, "jcapulet@example.com")
    val incomingTitle = stringResource(R.string.rtp_state_incoming_call)
    val connectedTitle = stringResource(uk.xa0.tulkki.data.R.string.rtp_state_connected)
    val dismiss = stringResource(R.string.dismiss_call)
    val answer = stringResource(R.string.answer_call)
    val hangUp = stringResource(R.string.hang_up)
    val earpiece = stringResource(R.string.call_is_using_earpiece_tap_to_switch_to_speaker)
    return remember(incoming) {
        RtpCallState().apply {
            withName = "Juliet Capulet"
            menuGotoChatVisible = true
            if (incoming) {
                title = incomingTitle
                withJid = "jcapulet@example.com"
                usingAccount = usingAccountText
                rejectCall =
                    RtpCallButton(
                        icon = R.drawable.ic_call_end_24dp,
                        description = dismiss,
                        visible = true,
                        onClick = {},
                    )
                acceptCall =
                    RtpCallButton(
                        icon = R.drawable.ic_call_24dp,
                        description = answer,
                        visible = true,
                        onClick = {},
                    )
            } else {
                title = connectedTitle
                duration = "01:23"
                verified = true
                dialpadVisible = true
                endCall =
                    RtpCallButton(
                        icon = R.drawable.ic_call_end_24dp,
                        description = hangUp,
                        visible = true,
                        onClick = {},
                    )
                actionLeft =
                    RtpCallButton(
                        icon = R.drawable.ic_mic_24dp,
                        visible = true,
                        onClick = {},
                    )
                actionRight =
                    RtpCallButton(
                        icon = R.drawable.ic_volume_off_24dp,
                        description = earpiece,
                        visible = true,
                        onClick = {},
                    )
            }
        }
    }
}

/** The five events, doing nothing: a cell pins a picture, not a call. */
private val QuietEvents =
    RtpCallEvents(
        onHelp = {},
        onGotoChat = {},
        onToggleDialpad = {},
        onSwitchToVideo = {},
        onDigit = {},
    )
