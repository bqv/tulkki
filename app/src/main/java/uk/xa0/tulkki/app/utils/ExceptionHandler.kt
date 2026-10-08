package uk.xa0.tulkki.app.utils

import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter
import java.io.Writer
import java.lang.Thread.UncaughtExceptionHandler
import uk.xa0.tulkki.app.services.NotificationService

/**
 * Tulkki's last-chance handler: it files the stacktrace and then lets the platform's own handler
 * finish the job.
 *
 * <p><strong>The null check is the port's one deliberate decision, and it is there to keep the
 * Java's ordering.</strong> The field is read once in the constructor from
 * `Thread.getDefaultUncaughtExceptionHandler()` and dereferenced at the end of
 * `uncaughtException`. Java left it a plain field and would throw there - *after* the stacktrace had
 * been written to the file; declaring it non-null in Kotlin would move that throw to construction
 * time, before the handler was ever installed, which is a real difference in a dying process. So the
 * field stays nullable and the throw is explicit, in the same place and of the same type. (ART's
 * `RuntimeInit` installs a default handler at process start, so no reachable path takes it.)
 *
 * <p>The constructor was package-private in the Java, called by `ExceptionHelper` in this same
 * package. Kotlin has no package-private, so it is `internal`; the widening is invisible in the
 * tree, because `ExceptionHelper` is the only caller there has ever been.
 *
 * <p>The class was not `final` in the Java, so it stays `open` - the port restricts nothing.
 */
open class ExceptionHandler internal constructor(private val context: Context) :
        UncaughtExceptionHandler {

    private val defaultHandler: UncaughtExceptionHandler? =
            Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        NotificationService.cancelIncomingCallNotification(context)
        val stringWriter: Writer = StringWriter()
        val printWriter = PrintWriter(stringWriter)
        throwable.printStackTrace(printWriter)
        val stacktrace = stringWriter.toString()
        printWriter.close()
        ExceptionHelper.writeToStacktraceFile(context, stacktrace)
        val delegate = defaultHandler
        if (delegate == null) {
            // Java dereferenced the same field here, after the stacktrace had been filed.
            throw NullPointerException()
        }
        delegate.uncaughtException(thread, throwable)
    }
}
