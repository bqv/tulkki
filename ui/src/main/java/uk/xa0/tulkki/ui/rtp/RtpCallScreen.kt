package uk.xa0.tulkki.ui.rtp

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.chrome.ChromeMenuItem
import uk.xa0.tulkki.ui.chrome.TulkkiChrome

/**
 * The call screen: what `activity_rtp_session.xml` and `dialpad.xml` drew, in Compose. The two
 * layouts and the menu `menu/activity_rtp_session.xml` are deleted, and the Activity's own view tree
 * is gone with them - `RtpSessionActivity` composes this instead of `DataBindingUtil.setContentView`.
 *
 * <p>**The state is a class, not a parameter list.** The old screen was bound imperatively: the
 * Activity's `update*` methods reached into `binding` and set one view at a time. Those methods are
 * unchanged in what they decide; they now write [RtpCallState], whose fields are Compose snapshots,
 * so a write recomposes exactly the piece it names. The seven controls carry their whole state
 * (icon, accessible name, visibility, action) in [RtpCallButton], so the two composables below draw
 * what the old code built by mutating a view seven properties at a time.
 *
 * <p>**Two platform surfaces stay platform views.** `uk.xa0.tulkki.ui.widget.SurfaceViewRenderer`
 * is an `org.webrtc.SurfaceViewRenderer` and the in-call photo is a `ShapeableImageView` loaded by
 * `AvatarWorkerTask`; there is no Compose equivalent of either, so the Activity creates the three
 * views once and hands them in through the `remoteVideo`, `localVideo` and `contactPhoto` slots,
 * which it fills with `AndroidView`. The slots are invoked only while the state says the surface is
 * visible, and the screenshot fixture passes empty slots - which is why a cell renders no WebRTC
 * view and no avatar, and why the layoutlib cells are a picture of everything else.
 *
 * <p>**The state starts empty where the XML's views did not.** Three of the deleted layout's views
 * were VISIBLE at inflation and only hidden by the first state update: the verified shield, the
 * end-call button and the `using_account` line, which showed its own raw `%s` text until a contact
 * was resolved. The state starts with all three hidden, so the interval between `onCreate` and the
 * first `update*` draws no shield, no end-call button and no format specifier. Every decided state
 * is the same; only that inflate-time frame is not reproduced.
 *
 * <p>**The bar can go away.** The deleted layout hid its whole `app_bar_layout` during a connected
 * video call (and in picture-in-picture), so the screen composes [TulkkiChrome] only while
 * `appBarVisible` holds and is chrome-less otherwise - the same full-screen surface, not a second
 * style of screen. There is no up arrow: the manifest declares no parent for this Activity, so
 * AppCompat left the XML bar without one. The menu the old `onCreateOptionsMenu` inflated is state
 * (`menuHelpVisible`, `menuDialpadVisible`, `menuGotoChatVisible`, `menuSwitchToVideoVisible`) and
 * its live items are the chrome's overflow, in the XML's own order.
 *
 * @param state the call's whole UI state.
 * @param events the actions the Activity owns: the bar's overflow items and the dialpad's DTMF
 *     consumer.
 * @param remoteVideo the remote video surface, filled only while the remote wrapper is visible.
 * @param localVideo the local preview surface, filled only while the preview is visible.
 * @param contactPhoto the in-call photo, filled only while the incoming-call screen shows it.
 */
@Composable
fun RtpCallScreen(
    state: RtpCallState,
    events: RtpCallEvents,
    remoteVideo: @Composable (Modifier) -> Unit,
    localVideo: @Composable (Modifier) -> Unit,
    contactPhoto: @Composable (Modifier) -> Unit,
) {
    // The XML's own order: help, dialpad, goto-chat, switch-to-video.
    val menu = mutableListOf<ChromeMenuItem>()
    if (state.menuHelpVisible) {
        menu.add(ChromeMenuItem(stringResource(R.string.help), onSelected = events.onHelp))
    }
    if (state.menuDialpadVisible) {
        menu.add(
            ChromeMenuItem(
                stringResource(R.string.action_dialpad), onSelected = events.onToggleDialpad))
    }
    if (state.menuGotoChatVisible) {
        menu.add(
            ChromeMenuItem(stringResource(R.string.switch_to_chat), onSelected = events.onGotoChat))
    }
    if (state.menuSwitchToVideoVisible) {
        menu.add(
            ChromeMenuItem(
                stringResource(R.string.switch_to_video), onSelected = events.onSwitchToVideo))
    }
    val body: @Composable () -> Unit = {
        CallBody(state, events, remoteVideo, localVideo, contactPhoto)
    }
    if (state.appBarVisible) {
        // No up arrow: the manifest declares no parent for this Activity, so AppCompat's
        // `setDefaultDisplayHomeAsUpEnabled(false)` left the XML bar without one, and the system
        // back button is what leaves the screen (`onBackPressed`).
        TulkkiChrome(title = state.title, menu = menu) { body() }
    } else {
        // The deleted `app_bar_layout` went GONE here, toolbar and header with it. Not a second
        // screen: the same body, drawn full-screen while the video call owns the window.
        body()
    }
}

/** The call screen's fields, each one a view the deleted layout bound imperatively. */
class RtpCallState {
    /** The toolbar title `setTitle(...)` put there; the state strings come from the same resources. */
    var title by mutableStateOf("")

    /** `with`: the contact's display name, or `null` while it has not been resolved. */
    var withName by mutableStateOf<String?>(null)

    /** `with_jid`: only the incoming/accepting states draw it. */
    var withJid by mutableStateOf<String?>(null)

    /** `duration`: the monospaced elapsed time, or `null` when the view was GONE. */
    var duration by mutableStateOf<String?>(null)

    /** `verified`: the green shield, drawn only outside picture-in-picture. */
    var verified by mutableStateOf(false)

    /** `support_warning`: the "might not support audio/video" card. */
    var supportWarning by mutableStateOf(false)

    /** `contact_photo`: visible only on the incoming-call screen in portrait mode. */
    var contactPhoto by mutableStateOf(false)

    /** `dialpad`: the menu item toggled it, and it survives the instance state. */
    var dialpadVisible by mutableStateOf(false)

    /**
     * `using_account`: the bottom-right line, or `null` when the view was GONE. It starts hidden
     * where the deleted `TextView` started VISIBLE with its own raw `%s` resource text, which the
     * first `updateIncomingCallScreen` replaced either way.
     */
    var usingAccount by mutableStateOf<String?>(null)

    /** The app bar, which the video states hide. */
    var appBarVisible by mutableStateOf(true)

    /** `pip_placeholder` and the two things inside it. */
    var pipPlaceholder by mutableStateOf(false)
    var pipWarning by mutableStateOf(false)
    var pipWaiting by mutableStateOf(false)

    /** The two video surfaces: the remote wrapper, the local preview. */
    var remoteVideo by mutableStateOf(false)
    var localVideo by mutableStateOf(false)

    /** `pip_local_mic_off_indicator`. */
    var micOffIndicator by mutableStateOf(false)

    /** The four items the old `onCreateOptionsMenu` decided the visibility of, live each time. */
    var menuHelpVisible by mutableStateOf(false)
    var menuDialpadVisible by mutableStateOf(false)
    var menuGotoChatVisible by mutableStateOf(false)
    var menuSwitchToVideoVisible by mutableStateOf(false)

    /** The three call controls: reject, accept, end. */
    var rejectCall by mutableStateOf(RtpCallButton())
    var acceptCall by mutableStateOf(RtpCallButton())
    var endCall by mutableStateOf(RtpCallButton())

    /** The in-call controls: microphone (left), speaker/video (right), camera flip (far right). */
    var actionLeft by mutableStateOf(RtpCallButton())
    var actionRight by mutableStateOf(RtpCallButton())
    var actionFarRight by mutableStateOf(RtpCallButton())
}

/**
 * One floating action button's whole state. The old code reached for the view for every one of
 * these (`setImageResource`, `setContentDescription`, `setOnClickListener`, `setVisibility`); the
 * state carries them instead, so the composable draws and the Activity decides.
 *
 * @param icon the button's `src`, `0` for a button that never had one set.
 * @param description the accessible name `setContentDescription` carried, so a screen reader reads
 *     the same sentence.
 * @param visible whether the button is drawn at all - the view's `VISIBLE` against its
 *     `GONE`/`INVISIBLE`, collapsed to one flag because the XML positioned every one of them
 *     absolutely, so hiding one never moved another.
 * @param onClick the action, or `null` for a control the old code made unclickable (the route the
 *     call is already on). A tap then does nothing, as it did.
 */
data class RtpCallButton(
    @DrawableRes val icon: Int = 0,
    val description: String? = null,
    val visible: Boolean = false,
    val onClick: (() -> Unit)? = null,
)

/**
 * The actions the Activity owns, handed in as plain functions so the screen names no Android type.
 *
 * @param onHelp the help overflow item.
 * @param onGotoChat the switch-to-chat overflow item.
 * @param onToggleDialpad the dialpad overflow item.
 * @param onSwitchToVideo the switch-to-video overflow item.
 * @param onDigit a dialpad key's DTMF tone: the old `clickConsumer` read the connection live and
 *     called `applyDtmfTone`.
 */
class RtpCallEvents(
    val onHelp: () -> Unit,
    val onGotoChat: () -> Unit,
    val onToggleDialpad: () -> Unit,
    val onSwitchToVideo: () -> Unit,
    val onDigit: (String) -> Unit,
)

/** The screen's own draw order, which is the layout's: placeholder, header, body, controls, overlays. */
@Composable
private fun CallBody(
    state: RtpCallState,
    events: RtpCallEvents,
    remoteVideo: @Composable (Modifier) -> Unit,
    localVideo: @Composable (Modifier) -> Unit,
    contactPhoto: @Composable (Modifier) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (state.pipPlaceholder) {
            // `pip_placeholder`, the first child in the XML.
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (state.pipWaiting) {
                    CircularProgressIndicator(color = Color.White)
                }
                if (state.pipWarning) {
                    Icon(
                        painter = painterResource(R.drawable.ic_warning_24dp),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = Color.White,
                    )
                }
            }
        }
        if (state.remoteVideo) {
            // `remote_video_wrapper`: full-bleed black below the bar, the remote frame centred in
            // it, and the button row still drawn over it. The header is not drawn in the states
            // that show it (a connected video call hides the bar), so this covers the whole body.
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                remoteVideo(Modifier.wrapContentSize())
            }
        }
        Column(modifier = Modifier.fillMaxSize()) {
            if (state.appBarVisible) {
                CallHeader(state)
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // The middle area: everything between the bar and the button row.
                if (state.supportWarning) {
                    SupportWarningCard(Modifier.align(Alignment.TopCenter))
                }
                state.duration?.let {
                    Text(
                        text = it,
                        modifier =
                            Modifier.align(Alignment.TopCenter)
                                .padding(
                                    top = dimensionResource(R.dimen.rtp_session_duration_top_margin)),
                        style = MaterialTheme.typography.titleLarge,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (state.contactPhoto) {
                    Box(
                        modifier =
                            Modifier.align(Alignment.Center)
                                .size(dimensionResource(R.dimen.publish_avatar_size)),
                    ) {
                        contactPhoto(Modifier.fillMaxSize())
                    }
                }
                if (state.dialpadVisible) {
                    Dialpad(
                        onDigit = events.onDigit,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
                if (state.localVideo) {
                    Box(
                        modifier =
                            Modifier.align(Alignment.TopEnd)
                                .padding(top = 24.dp, end = 24.dp)
                                .size(
                                    width = dimensionResource(R.dimen.local_video_preview_width),
                                    height = dimensionResource(R.dimen.local_video_preview_height),
                                ),
                    ) {
                        localVideo(Modifier.fillMaxSize())
                    }
                }
                if (state.verified) {
                    Icon(
                        painter = painterResource(R.drawable.ic_verified_user_24dp),
                        contentDescription = null,
                        modifier =
                            Modifier.align(Alignment.TopStart)
                                .padding(
                                    start = 16.dp,
                                    top = dimensionResource(
                                        R.dimen.rtp_session_duration_top_margin),
                                )
                                .size(40.dp)
                                .alpha(0.7f),
                        tint = colorResource(R.color.light_green_600),
                    )
                }
            }
            CallButtons(state)
        }
        if (state.micOffIndicator) {
            Icon(
                painter = painterResource(R.drawable.ic_mic_off_24dp),
                contentDescription = null,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).alpha(0.7f),
                tint = Color.White,
            )
        }
        state.usingAccount?.let {
            Text(
                text = it,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/** `with` and `with_jid`, and the deleted layout's 32 dp spacer under them. */
@Composable
private fun CallHeader(state: RtpCallState) {
    Column(modifier = Modifier.fillMaxWidth()) {
        state.withName?.let {
            Text(
                text = it,
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.displayLarge,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        state.withJid?.let {
            Text(
                text = it,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
    }
}

/** `support_warning`: the error-container card with the warning icon and the capability sentence. */
@Composable
private fun SupportWarningCard(modifier: Modifier) {
    Card(
        modifier =
            modifier.padding(
                start = 24.dp,
                end = 24.dp,
                top = dimensionResource(R.dimen.rtp_session_duration_top_margin),
            ),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_warning_48dp),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = stringResource(R.string.clients_may_not_support_av),
                modifier = Modifier.padding(start = 16.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

/**
 * The button row: reject and accept inside the deleted layout's 288 dp box, and the end-call group
 * centred over them with the microphone to its left and the speaker/video and camera-flip controls
 * to its right. The margins are the layout's own dimens.
 */
@Composable
private fun CallButtons(state: RtpCallState) {
    Box(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Box(modifier = Modifier.align(Alignment.Center).width(288.dp)) {
            if (state.rejectCall.visible) {
                CallFab(
                    button = state.rejectCall,
                    container = colorResource(R.color.red_300),
                    content = Color.White,
                    modifier = Modifier.align(Alignment.CenterStart).padding(16.dp),
                )
            }
            if (state.acceptCall.visible) {
                CallFab(
                    button = state.acceptCall,
                    container = colorResource(R.color.green_300),
                    content = Color.White,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(16.dp),
                )
            }
        }
        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.actionLeft.visible) {
                RouteFab(
                    button = state.actionLeft,
                    modifier =
                        Modifier.padding(
                            horizontal = dimensionResource(R.dimen.in_call_fab_margin)),
                )
            }
            if (state.endCall.visible) {
                CallFab(
                    button = state.endCall,
                    container = MaterialTheme.colorScheme.error,
                    content = MaterialTheme.colorScheme.onError,
                    modifier =
                        Modifier.padding(
                            horizontal = dimensionResource(R.dimen.in_call_fab_margin_center)),
                )
            }
            if (state.actionRight.visible) {
                RouteFab(
                    button = state.actionRight,
                    modifier =
                        Modifier.padding(
                            horizontal = dimensionResource(R.dimen.in_call_fab_margin)),
                )
            }
            if (state.actionFarRight.visible) {
                RouteFab(
                    button = state.actionFarRight,
                    modifier =
                        Modifier.padding(
                            horizontal = dimensionResource(R.dimen.in_call_fab_margin)),
                )
            }
        }
    }
}

/** A 72 dp call control - the layout's `fabCustomSize`, with its 36 dp icon. */
@Composable
private fun CallFab(
    button: RtpCallButton,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
) {
    FloatingActionButton(
        onClick = { button.onClick?.invoke() },
        modifier = modifier.size(72.dp),
        containerColor = container,
        contentColor = content,
    ) {
        if (button.icon != 0) {
            Icon(
                painter = painterResource(button.icon),
                contentDescription = button.description,
                modifier = Modifier.size(36.dp),
                tint = content,
            )
        }
    }
}

/**
 * An in-call control in the theme's small surface style: the layout's
 * `?attr/floatingActionButtonSmallSurfaceStyle`, which is the surface-container container with the
 * theme's primary as the icon colour.
 */
@Composable
private fun RouteFab(button: RtpCallButton, modifier: Modifier = Modifier) {
    SmallFloatingActionButton(
        onClick = { button.onClick?.invoke() },
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        if (button.icon != 0) {
            Icon(
                painter = painterResource(button.icon),
                contentDescription = button.description,
            )
        }
    }
}

/** One dialpad key: a digit, its letters, and the strings the DTMF consumer receives. */
private data class DialpadKeySpec(
    val number: String,
    val letters: String?,
    val extra: String? = null,
)

/** The deleted `dialpad.xml`'s twelve keys, in its own rows and with its own letters. */
private val DIALPAD_ROWS =
    listOf(
        listOf(DialpadKeySpec("1", null), DialpadKeySpec("2", "ABC"), DialpadKeySpec("3", "DEF")),
        listOf(DialpadKeySpec("4", "GHI"), DialpadKeySpec("5", "JKL"), DialpadKeySpec("6", "MNO")),
        listOf(DialpadKeySpec("7", "PQRS"), DialpadKeySpec("8", "TUV"), DialpadKeySpec("9", "WXYZ")),
        listOf(DialpadKeySpec("*", null), DialpadKeySpec("0", null, "+"), DialpadKeySpec("#", null)),
    )

/**
 * The DTMF dialpad. Each key is one clickable box whose tap sends the digit the deleted layout put
 * in its `android:tag`; the `+` beside the zero is the layout's own decorative `TextView` and is
 * *not* a key of its own - the whole zero box sends `0`, exactly as the tag on `dialpad_0_holder`
 * did. The sizes are the deleted `DialpadNumberStyle`/`DialpadLetterStyle` dimens.
 */
@Composable
private fun Dialpad(onDigit: (String) -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    // The deleted styles' `android:textSize` values. `dimensionResource` resolves a sp dimen with
    // the font scale already applied, so `toSp` puts it back on the same axis Compose reads.
    val numberDp = dimensionResource(R.dimen.dialpad_text_size)
    val letterDp = dimensionResource(R.dimen.smaller_text_size)
    val plusDp = dimensionResource(R.dimen.actionbar_text_size)
    val numberSize = with(density) { numberDp.toSp() }
    val letterSize = with(density) { letterDp.toSp() }
    val plusSize = with(density) { plusDp.toSp() }
    val rowMargin = dimensionResource(R.dimen.activity_margin)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(top = dimensionResource(R.dimen.medium_margin)),
    ) {
        DIALPAD_ROWS.forEachIndexed { rowIndex, keys ->
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .padding(
                            bottom =
                                if (rowIndex == DIALPAD_ROWS.lastIndex) {
                                    rowMargin
                                } else {
                                    dimensionResource(R.dimen.medium_margin)
                                },
                        ),
            ) {
                keys.forEachIndexed { keyIndex, key ->
                    val edge =
                        when (keyIndex) {
                            0 -> PaddingValues(start = rowMargin)
                            2 -> PaddingValues(end = rowMargin)
                            else -> PaddingValues()
                        }
                    DialpadKey(
                        spec = key,
                        numberSize = numberSize,
                        letterSize = letterSize,
                        plusSize = plusSize,
                        onClick = { onDigit(key.number) },
                        modifier = Modifier.weight(1f).fillMaxHeight().padding(edge),
                    )
                }
            }
        }
    }
}

/** One key: the digit (and the zero's `+`) at the top, the letters under it. */
@Composable
private fun DialpadKey(
    spec: DialpadKeySpec,
    numberSize: TextUnit,
    letterSize: TextUnit,
    plusSize: TextUnit,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Box(modifier = modifier.clickable(onClick = onClick), contentAlignment = Alignment.TopCenter) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row {
                Text(text = spec.number, fontSize = numberSize)
                spec.extra?.let {
                    Text(
                        text = it,
                        modifier = Modifier.padding(start = dimensionResource(R.dimen.small_margin)),
                        fontSize = plusSize,
                    )
                }
            }
            spec.letters?.let {
                Text(
                    text = it,
                    modifier =
                        Modifier.padding(bottom = dimensionResource(R.dimen.medium_margin))
                            .alpha(0.8f),
                    fontSize = letterSize,
                )
            }
        }
    }
}
