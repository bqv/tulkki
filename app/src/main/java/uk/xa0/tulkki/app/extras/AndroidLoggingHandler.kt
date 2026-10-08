package uk.xa0.tulkki.app.extras

import android.util.Log
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogManager
import java.util.logging.LogRecord
import java.util.logging.SimpleFormatter

/**
 * Make JUL work on Android.
 * https://stackoverflow.com/a/9047282/8611
 *
 * <p>`open`, as the Java class was not `final`; `reset` is a `@JvmStatic` companion member, the one
 * call `XmppTulkkiHost` makes, and `new AndroidLoggingHandler()` still constructs it.
 *
 * <p>The one place where the port is deliberately not a word-for-word copy is the null check on the
 * record's throwable: Java read `record.getThrown()` twice and Kotlin cannot smart-cast a getter
 * call on another object, so the value is bound once to a local. A `LogRecord`'s throwable does not
 * change between two reads, so the two reads the Java made and the one this makes are the same value;
 * the surrounding `catch (RuntimeException)` is unchanged.
 *
 * <p>`getAndroidLevel` was package-private static and is `private` in the companion; `publish` is its
 * only caller in the Java too, and Kotlin has no package-private to preserve.
 */
open class AndroidLoggingHandler : Handler() {

    override fun close() {}

    override fun flush() {}

    override fun publish(record: LogRecord) {
        if (!super.isLoggable(record)) {
            return
        }

        val name: String = record.loggerName
        val maxLength = 30
        val tag = if (name.length > maxLength) name.substring(name.length - maxLength) else name

        try {
            val level = getAndroidLevel(record.level)
            val msg = SimpleFormatter().format(record)
            Log.println(level, tag, msg)
            val thrown = record.thrown
            if (thrown != null) {
                Log.println(level, tag, Log.getStackTraceString(thrown))
            }
        } catch (e: RuntimeException) {
            Log.e("AndroidLoggingHandler", "Error logging message.", e)
        }
    }

    companion object {

        @JvmStatic
        fun reset(rootHandler: Handler) {
            val rootLogger = LogManager.getLogManager().getLogger("")
            val handlers = rootLogger.handlers
            for (handler in handlers) {
                rootLogger.removeHandler(handler)
            }
            rootLogger.addHandler(rootHandler)
        }

        private fun getAndroidLevel(level: Level): Int {
            val value = level.intValue()

            if (value >= Level.SEVERE.intValue()) {
                return Log.ERROR
            } else if (value >= Level.WARNING.intValue()) {
                return Log.WARN
            } else if (value >= Level.INFO.intValue()) {
                return Log.INFO
            } else {
                return Log.DEBUG
            }
        }
    }
}
