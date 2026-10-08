package uk.xa0.tulkki.ui.conversation

import androidx.annotation.StringRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.theme.TulkkiSpacing

/**
 * Voice recording, `ConversationFragment`'s recorder lifted out of Java as two stateless surfaces: the
 * recording bar that `binding.recordingVoiceActivity` drew, and the mic that
 * `mRecordVoiceButtonListener` opened it from (`recordVoiceButton` and its listener are deleted with
 * the composer row that hid them; a recording's live trigger is the attach menu's `attach_record_voice`
 * row now, and this surface is still unhosted).
 *
 * <p>**The exact signatures, because the wiring lane hosts this by reading and not by rewriting:**
 *
 * ```
 * @Composable fun VoiceRecordingBar(
 *     state: VoiceRecordingState,
 *     onCancel: () -> Unit,
 *     onShare: () -> Unit,
 *     onTogglePause: () -> Unit,
 *     modifier: Modifier = Modifier,
 * )
 *
 * @Composable fun VoiceRecordButton(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier)
 * ```
 *
 * <p>**What the caller owns, and therefore what is in the parameter list.** Every durable fact is a
 * parameter and none of them is defaulted into this file: [VoiceRecordingState] carries the session -
 * whether the bar is up, whether the recorder is capturing or paused, the seconds it has run, whether
 * there is anything to share yet, and which of the two failures happened. [onCancel], [onShare] and
 * [onTogglePause] are the bar's three gestures, and [VoiceRecordButton]'s `onClick` is the mic's. The
 * caller is the session's owner because it is the recorder's owner: only it knows, synchronously,
 * whether `pause()` or `resume()` actually took, so a clock or a paused flag that this file remembered
 * would be a second answer to a question the recorder has already answered.
 *
 * <p>**What this file remembers, and nothing else.** The paused blink is transient UI state and lives
 * here: the Java blinked the timer with an infinite `AlphaAnimation` started in `pauseRecording` and
 * cleared in `resumeRecording` (`StartTimerAnimation`), which is a drawing detail and never a fact the
 * host could act on. The two `Composable`s hold no other `remember`, no `View`, no `Context`, no
 * fragment, no activity and no singleton.
 *
 * <p>**The recorder itself cannot be stateless, and this is the seam rather than an omission.** The
 * Java drove `MediaRecorder` directly - `startRecording`/`stopRecording`/`pauseRecording`/
 * `resumeRecording` (`ConversationFragment.java:6937-7060`) - and that needs a `Context`, a file, a
 * `FileObserver` and a worker thread. The smallest host that carries it is five verbs beside this
 * surface's state: `start()`, `pause()`, `resume()`, `stopAndSave()`, `stopAndDiscard()`. The host
 * runs them and reports the result as [VoiceRecordingState]; `start()` having failed is
 * [VoiceRecordingFailure.START] with the bar still up, which is exactly the Java's shape (it showed
 * the overlay, disabled the share button and put [R.string.unable_to_start_recording] where the timer
 * goes). **What a finished recording hands back never passes through this surface**: the host owns the
 * output file, so on `stopAndSave()` *it* builds upstream's `Uri.fromFile(outputFile)`, wraps it as an
 * `Attachment` of type `RECORDING` and feeds the composer's attachment previews - the
 * `mediaPreviewAdapter.addMediaPreviews(...)` call `Finisher` made (`ConversationFragment.java:7094-7100`)
 * - and *it* raises [VoiceRecordingFailure.SAVE] as [R.string.unable_to_save_recording] when that
 * fails, which the Java raised as a `Toast` this surface cannot draw.
 *
 * <p>**Not carried, and why.** Everything the Java hung off the window stays the host's, because a
 * stateless composable has no activity to hang it on: `setRequestedOrientation(SCREEN_ORIENTATION_LOCKED)`
 * and `FLAG_KEEP_SCREEN_ON` (start), their release and `oldOrientation` (stop),
 * `MediaRecorder.setPrivacySensitive(true)`, the `CountDownLatch`/`FileObserver` eight-second wait for
 * the file to be closed, and `activity.setResult(...)`. The back press
 * (`backPressedLeaveVoiceRecorder`, `ConversationFragment.java:1013-1022`) is the host's too: it is the
 * host that decides that back cancels a live recording, and it does it by calling [onCancel]'s own
 * effect. **There is no cancel-by-slide gesture in the Java** - cancel is the button
 * (`mCancelVoiceRecord`) and that back press, and there is no `OnTouchListener` anywhere in the file -
 * so this surface does not invent one; if one is ever wanted it belongs here, as a `drag` on the bar
 * with an arm distance beside [uk.xa0.tulkki.ui.conversation.SwipeToReply]'s three numbers.
 *
 * <p>**One deliberate rewrite.** The Java's counter wrapped at the hour: `minutes = (mStartTime % 3600)
 * / 60` (`ConversationFragment.java:7176`) showed `00:00` again at sixty minutes. [VoiceRecordingTime]
 * drops the modulo, so a long recording reads `60:00` and not as a fresh one. The migration's standing
 * licence covers it, and no owner can have depended on the wrap.
 *
 * <p>**The mic's visibility is not here.** The Java hid its `recordVoiceButton` (deleted now) whenever
 * the draft was non-empty or the conversation was not writable (`updateSendButton`,
 * `ConversationFragment.java:6326-6330`) and disabled it while a recording started; both are the
 * caller's reading, so the caller either draws [VoiceRecordButton] or does not, and passes
 * [VoiceRecordButton]'s `enabled`.
 *
 * @param state the session, all of it hoisted; an inactive state draws nothing at all, which is the
 *     Java's `recordingVoiceActivity` at `GONE`
 * @param onCancel the cancel affordance, or the host's own back press: `mCancelVoiceRecord`'s job -
 *     stop and delete the file rather than save it
 * @param onShare `mShareVoiceRecord`'s job: stop and keep the file, and hand it to the attachment
 *     previews; the Java disabled the button for a 500 ms beat first, which is the host's timing and
 *     is known here only through [VoiceRecordingState.canShare]
 * @param onTogglePause the timer's own tap, `mTimerClickListener`: pause a live recording, resume a
 *     paused one
 * @param modifier the caller's placement
 */
@Composable
fun VoiceRecordingBar(
    state: VoiceRecordingState,
    onCancel: () -> Unit,
    onShare: () -> Unit,
    onTogglePause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.active) return
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = TulkkiSpacing.sm, vertical = TulkkiSpacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCancel) {
            Icon(
                painter = painterResource(R.drawable.ic_cancel_24dp),
                contentDescription = stringResource(R.string.action_cancel),
            )
        }
        val failure = state.failure
        if (failure != null) {
            // The Java put the start failure where the timer goes, in the body style it also used for
            // the sentence (`TextAppearance_Material3_BodyMedium`); the save failure reached a Toast
            // instead, and the host still owns that - this is the same sentence in the same slot.
            Text(
                text = stringResource(failure.words),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Text(
                text = VoiceRecordingTime.label(state.elapsedSeconds),
                style =
                    MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier =
                    Modifier
                        .alpha(if (state.paused) pausedBlink() else VISIBLE_ALPHA)
                        .clickable(
                            role = Role.Button,
                            onClickLabel =
                                stringResource(
                                    if (state.recording) R.string.tulkki_recording_pause
                                    else R.string.tulkki_recording_resume,
                                ),
                            onClick = onTogglePause,
                        ),
            )
        }
        IconButton(onClick = onShare, enabled = state.canShare) {
            Icon(
                painter = painterResource(R.drawable.ic_send_24dp),
                contentDescription = stringResource(R.string.share),
                tint = if (state.canShare) MaterialTheme.colorScheme.primary else LocalContentColor.current,
            )
        }
    }
}

/**
 * The mic, the deleted `mRecordVoiceButtonListener` (`ConversationFragment.java:902`): one tap opens
 * the recording session, and nothing else about it is here - see [VoiceRecordingBar] for the whole
 * signature and the host seam.
 *
 * <p>`enabled` is the Java's `setEnabled(false)` while a start is in flight; whether to draw it at all
 * is `updateSendButton`'s visibility rule and stays with the caller, because that rule reads the draft
 * and the conversation's writability, neither of which is this surface's.
 */
@Composable
fun VoiceRecordButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Icon(
            painter = painterResource(R.drawable.ic_mic_24dp),
            contentDescription = stringResource(R.string.attach_record_voice),
        )
    }
}

/**
 * The recorder's own two failures, in the vocabulary the Java already had: a start that never began,
 * and a save that did not land.
 *
 * <p>Both carry the string the Java used, so the words are the tree's and not this file's: `START` is
 * `unable_to_start_recording`, which the Java wrote into the timer's own slot, and `SAVE` is
 * `unable_to_save_recording`, which it raised as a `Toast`. They are an enum rather than two booleans
 * so "a start failure and a save failure at once" cannot be represented - the second is only ever
 * reachable after the first did not happen.
 */
enum class VoiceRecordingFailure(@StringRes val words: Int) {
    /** `startRecording()`'s failure branch: the bar stays up with nothing to share. */
    START(R.string.unable_to_start_recording),

    /** `stopRecording(true)`'s failure branch: the file was not handed over. */
    SAVE(R.string.unable_to_save_recording),
}

/**
 * Everything about a recording session that outlives a recomposition, and therefore everything the
 * caller must own. Each field is one of the Java fragment's own, so the wiring lane can read the two
 * side by side.
 *
 * <p>An inactive state is the honest empty: it draws nothing, which is `recordingVoiceActivity` at
 * `GONE` before a recording starts and after a cancel or a save. [elapsedSeconds]' zero is the XML's own
 * `00:00` default and not a missing value, and [canShare] is false until a recorder has actually
 * started, exactly as the Java left `shareButton` disabled through the overlay's life until then.
 *
 * @param active whether the recording bar is up, `recordingVoiceActivity`'s visibility
 * @param recording whether the recorder is capturing right now, the Java's `recording`: false while
 *     paused *and* false after a stop, which is why [paused] asks [failure] as well
 * @param elapsedSeconds the Java's `mStartTime`, advanced by the caller's own tick while [recording];
 *     the bar only formats it ([VoiceRecordingTime])
 * @param canShare whether there is a finished-enough recording to hand over, `shareButton`'s enabled
 *     state; the host clears it while a save is in flight
 * @param failure which of the two failures happened, or `null` for an ordinary session
 */
data class VoiceRecordingState(
    val active: Boolean = false,
    val recording: Boolean = false,
    val elapsedSeconds: Int = 0,
    val canShare: Boolean = false,
    val failure: VoiceRecordingFailure? = null,
) {

    /**
     * Whether the timer should blink: up but not capturing and not failed. It is `mTimerClickListener`'s
     * two branches read as one fact - the Java asked `recording && visible` to pause and
     * `!recording && visible` to resume, and after a failed start it would have asked the second about a
     * recorder that never ran.
     */
    val paused: Boolean
        get() = active && !recording && failure == null
}

/**
 * The elapsed-time label, as arithmetic rather than a Composable's branch, for the reason
 * [SwipeToReply] is: the one number in this surface that can be got wrong gets a cell and a test.
 *
 * <p>`MM:SS`, monospace, two digits each - the XML's own `00:00` - and **minutes do not wrap at the
 * hour**; the Java's `(mStartTime % 3600) / 60` did, and that is the one behaviour deliberately not
 * carried (see [VoiceRecordingBar]). A negative or absurdly large reading is clamped at zero rather
 * than thrown on, so a caller's arithmetic error is a wrong clock and never a crash.
 */
object VoiceRecordingTime {

    /** The label for a recording that has run this many seconds. */
    @JvmStatic
    fun label(elapsedSeconds: Int): String {
        val total = elapsedSeconds.coerceAtLeast(0)
        val minutes = total / 60
        val seconds = total % 60
        return "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    }
}

/**
 * The paused timer's blink: the Java's `AlphaAnimation(0.0f, 1.0f)`, half a second each way and
 * reversing forever. Called only from the paused branch, so nothing animates while a recording is
 * live or while no bar is drawn.
 */
@Composable
private fun pausedBlink(): Float {
    val transition = rememberInfiniteTransition(label = "voice recording paused")
    val blink by
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(BLINK_MILLIS), RepeatMode.Reverse),
            label = "voice recording blink",
        )
    return blink
}

/** The Java's `anim.setDuration(500)`, both ways. */
private const val BLINK_MILLIS = 500

/** A live timer's alpha: the XML's own opacity, and what `clearAnimation()` left behind. */
private const val VISIBLE_ALPHA = 1f
