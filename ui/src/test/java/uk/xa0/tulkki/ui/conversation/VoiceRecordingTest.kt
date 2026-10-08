package uk.xa0.tulkki.ui.conversation

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.ui.R

/**
 * The voice recording surface's one piece of arithmetic, and its two readings of a state - the clock
 * the bar draws and the fact that decides whether it blinks.
 *
 * <p>**The hour is the cell that matters.** `ConversationFragment.tick()` computed
 * `(mStartTime % 3600) / 60`, so a recording that passed sixty minutes showed `00:00` again - a fresh
 * recording's label on a file that was an hour long. [VoiceRecordingBar] records that as the one
 * deliberate rewrite, and this is where it is pinned, so nothing restores the modulo by "fixing" the
 * formatter back to the Java.
 *
 * <p>The two failures carry the tree's own strings rather than words invented here, so the words a
 * future lane changes in `strings_tulkki.xml` stay the ones the surface draws.
 */
class VoiceRecordingTest {

    @Test
    fun theLabelIsTwoDigitsEachAndTheXmlsOwnDefault() {
        Assert.assertEquals("00:00", VoiceRecordingTime.label(0))
        Assert.assertEquals("00:05", VoiceRecordingTime.label(5))
        Assert.assertEquals("00:59", VoiceRecordingTime.label(59))
        Assert.assertEquals("01:00", VoiceRecordingTime.label(60))
        Assert.assertEquals("59:59", VoiceRecordingTime.label(3_599))
    }

    @Test
    fun minutesDoNotWrapAtTheHour() {
        Assert.assertEquals("60:00", VoiceRecordingTime.label(3_600))
        Assert.assertEquals("61:01", VoiceRecordingTime.label(3_661))
        Assert.assertEquals("600:00", VoiceRecordingTime.label(36_000))
    }

    @Test
    fun aNonsenseReadingIsAWrongClockAndNeverACrash() {
        Assert.assertEquals("00:00", VoiceRecordingTime.label(-1))
        Assert.assertEquals("00:00", VoiceRecordingTime.label(Int.MIN_VALUE))
    }

    @Test
    fun pausedIsUpButNotCapturingAndNotFailed() {
        val live = VoiceRecordingState(active = true, recording = true, canShare = true)
        Assert.assertFalse("a live recording is not paused", live.paused)

        Assert.assertTrue("up and not capturing is paused", live.copy(recording = false).paused)

        Assert.assertFalse(
            "a start that never happened is a failure, not a pause",
            live.copy(recording = false, failure = VoiceRecordingFailure.START).paused,
        )
        Assert.assertFalse("nothing drawn is nothing blinking", VoiceRecordingState().paused)
        Assert.assertFalse(
            "a failure while the bar is down is not a pause either",
            VoiceRecordingState(active = false, failure = VoiceRecordingFailure.SAVE).paused,
        )
    }

    @Test
    fun theTwoFailuresAreTheTreesOwnWords() {
        Assert.assertEquals(
            R.string.unable_to_start_recording,
            VoiceRecordingFailure.START.words,
        )
        Assert.assertEquals(R.string.unable_to_save_recording, VoiceRecordingFailure.SAVE.words)
    }
}
