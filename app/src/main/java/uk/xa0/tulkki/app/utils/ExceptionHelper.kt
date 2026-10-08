package uk.xa0.tulkki.app.utils

import android.content.Context
import android.util.Log
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.base.Charsets
import com.google.common.io.Files
import java.io.File
import java.io.IOException
import uk.xa0.tulkki.app.BuildConfig
import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.AppSettings
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.XmppActivity
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.xmpp.utils.AccountUtils

/**
 * Installs the uncaught-exception handler and offers the crash report the next time the app opens.
 *
 * <p>An `object` with `@JvmStatic`s; `TulkkiApplication` and `UiAppHost` are the two callers.
 *
 * <p><strong>One restructure, forced by Kotlin's null rules and equivalent to the Java.</strong> The
 * Java wrote `activity == null ? null : activity.xmppConnectionService` and then returned when that
 * was null; it could go on using `activity` afterwards because the compiler kept the reference.
 * Kotlin cannot infer `activity != null` from `service != null` - a non-null service only means the
 * activity was not null - so the guard is split: `activity == null` returns first and the service
 * check follows. The set of inputs that return false here is exactly the Java's, and `activity` is
 * then genuinely non-null for the dialog.
 *
 * <p>`writeToStacktraceFile` was package-private and is `internal`, its only caller being
 * [ExceptionHandler] in this same package; Kotlin has no package-private, and this is the treatment
 * `LanguageCheck.code` already records.
 *
 * <p>**`Class.forName("io.sentry.Sentry")` is kept verbatim.** It is the §5.4 audit's one string in
 * this file: it names a third-party class that is not in the tree, and its absence is what decides
 * whether Tulkki shows its own crash dialog - so the string is load-bearing and unchanged.
 */
object ExceptionHelper {

    private const val FILENAME = "stacktrace.txt"

    @JvmStatic
    fun init(context: Context) {
        if (Thread.getDefaultUncaughtExceptionHandler() is ExceptionHandler) {
            return
        }
        Thread.setDefaultUncaughtExceptionHandler(ExceptionHandler(context))
    }

    @JvmStatic
    fun checkForCrash(activity: XmppActivity?): Boolean {
        try {
            Class.forName("io.sentry.Sentry")
            return false
        } catch (e: ClassNotFoundException) {
        }

        if (activity == null) {
            return false
        }
        val service: XmppConnectionService = activity.xmppConnectionService ?: return false
        val appSettings = AppSettings(activity)
        if (!appSettings.isSendCrashReports() || Config.BUG_REPORTS == null) {
            return false
        }
        val account = AccountUtils.getFirstNotDisabled(AccountRegistry.get().getAccounts())
        if (account == null) {
            return false
        }
        val file = File(activity.cacheDir, FILENAME)
        if (!file.exists()) {
            return false
        }
        val report: String
        try {
            report = Files.asCharSource(file, Charsets.UTF_8).read()
        } catch (e: IOException) {
            return false
        }
        if (file.delete()) {
            Log.d(Config.LOGTAG, "deleted crash report file")
        }
        val builder = MaterialAlertDialogBuilder(activity)
        builder.setTitle(activity.getString(R.string.crash_report_title, BuildConfig.APP_NAME))
        builder.setMessage(activity.getString(R.string.crash_report_message, BuildConfig.APP_NAME))
        builder.setPositiveButton(activity.getText(R.string.send_now)) { _, _ ->
            Log.d(
                    Config.LOGTAG,
                    "using account=" + account.getJid().asBareJid() + " to send in stack trace")
            val conversation =
                    service.findOrCreateConversation(
                            account, Config.BUG_REPORTS
                                    ?: throw NullPointerException("BUG_REPORTS is not configured"),
                            false, true) as Conversation
            val message = Message(conversation, report, Message.ENCRYPTION_NONE)
            service.sendMessage(message)
        }
        builder.setNegativeButton(activity.getText(R.string.send_never)) { _, _ ->
            appSettings.setSendCrashReports(false)
        }
        builder.create().show()
        return true
    }

    internal fun writeToStacktraceFile(context: Context, msg: String) {
        try {
            Files.asCharSink(File(context.cacheDir, FILENAME), Charsets.UTF_8).write(msg)
        } catch (e: IOException) {
            Log.w(Config.LOGTAG, "could not write stack trace to file", e)
        }
    }
}
