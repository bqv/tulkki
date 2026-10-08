package uk.xa0.tulkki.xmpp.models

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * XEP-0082 timestamp parsing, the one static helper on the model path. Converted from the Java final
 * utility class; the null guard still answers [IllegalArgumentException] rather than Kotlin's
 * intrinsic, `@Throws(ParseException)` keeps the checked clause `delay.Delay.kt` still catches, and
 * the fractional-seconds read is preserved read for read.
 */
object Timestamps {

    @JvmStatic
    @Throws(ParseException::class)
    fun parse(input: String?): Long {
        if (input == null) {
            throw IllegalArgumentException("timestamp should not be null")
        }
        val timestamp = input.replace("Z", "+0000")
        val simpleDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
        val milliseconds = getMilliseconds(timestamp)
        val formatted = timestamp.substring(0, 19) + timestamp.substring(timestamp.length - 5)
        val date = simpleDateFormat.parse(formatted) ?: throw IllegalArgumentException("Date was null")
        return date.time + milliseconds
    }

    private fun getMilliseconds(timestamp: String): Long {
        if (timestamp.length >= 25 && timestamp[19] == '.') {
            val millis = timestamp.substring(19, timestamp.length - 5)
            try {
                val fractions = ("0" + millis).toDouble()
                return Math.round(1000 * fractions)
            } catch (e: NumberFormatException) {
                return 0
            }
        }
        return 0
    }
}
