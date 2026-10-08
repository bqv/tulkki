package uk.xa0.tulkki.ui.utils

import android.content.Context
import android.os.SystemClock
import androidx.annotation.PluralsRes
import java.util.Locale
import uk.xa0.tulkki.ui.R

object TimeFrameUtils {

    private val TIME_FRAMES = arrayOf(
        TimeFrame(1000L, R.plurals.seconds),
        TimeFrame(60L * 1000, R.plurals.minutes),
        TimeFrame(60L * 60 * 1000, R.plurals.hours),
        TimeFrame(24L * 60 * 60 * 1000, R.plurals.days),
        TimeFrame(7L * 24 * 60 * 60 * 1000, R.plurals.weeks),
        TimeFrame(30L * 24 * 60 * 60 * 1000, R.plurals.months)
    )

    @JvmStatic
    fun resolve(context: Context, timeFrame: Long): String {
        for (i in TIME_FRAMES.size - 1 downTo 0) {
            val duration = TIME_FRAMES[i].duration
            val threshold = if (i > 0) TIME_FRAMES[i - 1].duration / 2 else 0L
            if (timeFrame >= duration - threshold) {
                val count =
                    (timeFrame / duration + (if (timeFrame % duration > duration / 2) 1 else 0)).toInt()
                return context.resources.getQuantityString(TIME_FRAMES[i].name, count, count)
            }
        }
        return context.resources.getQuantityString(TIME_FRAMES[0].name, 0, 0)
    }

    @JvmStatic
    fun formatTimePassed(since: Long, withMilliseconds: Boolean): String {
        return formatTimePassed(since, SystemClock.elapsedRealtime(), withMilliseconds)
    }

    @JvmStatic
    fun formatTimePassed(since: Long, to: Long, withMilliseconds: Boolean): String {
        val passed = if (since < 0) 0 else (to - since)
        return formatElapsedTime(passed, withMilliseconds)
    }

    @JvmStatic
    fun formatElapsedTime(elapsed: Long, withMilliseconds: Boolean): String {
        val hours = (elapsed / 3600000).toInt()
        val minutes = (elapsed / 60000).toInt() % 60
        val seconds = (elapsed / 1000).toInt() % 60
        val milliseconds = (elapsed / 100).toInt() % 10
        return if (hours > 0) {
            String.format(Locale.ENGLISH, "%d:%02d:%02d", hours, minutes, seconds)
        } else if (withMilliseconds) {
            String.format(Locale.ENGLISH, "%d:%02d.%d", minutes, seconds, milliseconds)
        } else {
            String.format(Locale.ENGLISH, "%d:%02d", minutes, seconds)
        }
    }

    private class TimeFrame(val duration: Long, @PluralsRes val name: Int)
}
