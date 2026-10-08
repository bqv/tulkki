package uk.xa0.tulkki.app

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import uk.xa0.tulkki.app.extras.SpannedToXHTML
import uk.xa0.tulkki.app.services.EmojiInitializationService
import uk.xa0.tulkki.app.utils.ExceptionHelper
import uk.xa0.tulkki.app.utils.PhoneNumberUtilWrapper
import uk.xa0.tulkki.app.worker.WorkReArm
import uk.xa0.tulkki.data.AppSettings
import uk.xa0.tulkki.data.FileBackend
import uk.xa0.tulkki.data.text.XhtmlBody
import uk.xa0.tulkki.data.utils.PhoneNumberNormalizer
import uk.xa0.tulkki.data.view.ViewPorts
import uk.xa0.tulkki.translation.TranslationSettings
import uk.xa0.tulkki.ui.host.UiHost
import uk.xa0.tulkki.ui.view.UiViewPorts
import uk.xa0.tulkki.xmpp.services.XmppConnectionService

/**
 * Tulkki's process start: upstream's Application, one line of ours, and nothing in between.
 *
 * <p>Two commits made this one class. The Application upstream publishes carried the old project's
 * name, and the manifest named it until Tulkki needed a line of its own at startup: that line lived
 * in `TulkkiApp`, a subclass whose whole job was to call `super.onCreate()` and then
 * [TranslationWork.ensureScheduled]. The naming rename turned that class into this one and kept the
 * subclass; the fold deleted the subclass and moved its line here, so the manifest names this class
 * directly and startup is one `onCreate` again. The upstream name is kept rather than `TulkkiApp`
 * because after the fold the class is upstream's startup plus one line, and `TulkkiApplication`
 * says what the manifest is naming.
 *
 * <p>What it owns is what a process start owes: the translation queue's scheduling, so a process the
 * system started for no other reason - a WorkManager run after a kill, a reboot - picks up whatever
 * the queue still owes rather than leaving it until someone opens the app. [TranslationWork]
 * documents that mechanism and swallows its own failures, so a WorkManager that cannot be reached
 * never takes the app down at startup. **With the interpreter off it owes the opposite**: the three
 * records are cancelled rather than enqueued, because a pending WorkManager row is the one place the
 * off state could still wake the process up and ask the queue to spend.
 *
 * <p><strong>What must not go here is anything that reads the Keystore.</strong>
 * `TranslationSettings.get(this)` builds `EncryptedSharedPreferences` eagerly, over the Keystore
 * master key, on the main thread - so warming the settings singleton at process start would add that
 * cost to every cold start, including the many that never touch Tulkki, which is exactly the kind of
 * regression this class exists to avoid. It stays lazy, and `onCreate` stays cheap.
 *
 * <p>Kotlin notes from the port. The four statics are companion `@JvmStatic`s, so every call site
 * keeps the spelling it had: `TulkkiApplication.getContext()` (ExportBackupWorker) and
 * `getDesiredNightMode`/`isDynamicColorsDesired`/`isCustomColorsDesired` (UiAppHost), all of them
 * Kotlin now. `CONTEXT` stays a companion `var`, keeps `@SuppressLint("StaticFieldLeak")`, and
 * [getContext] answers `Context?` because it is null before `onCreate` - which is what the Java
 * returned, and its one reader hands it to a platform-typed Java method. Two installs are written
 * with parentheses rather than as trailing lambdas, and that is load-bearing: `PortInstallGuardTest`
 * reads this file's text and requires `PhoneNumberNormalizer.install(` and
 * `XmppConnectionService.installPortsFactory(` to appear inside `onCreate`'s body, and a trailing
 * lambda would spell both `install {`. The factory is a `TulkkiPorts.Factory` SAM rather than
 * `XmppTulkkiHost::install`, because a Java static is not a Kotlin callable reference, and the
 * normaliser is a lambda for the same reason - the SAM wants the two-argument overload.
 */
class TulkkiApplication : Application() {

    override fun onCreate() {
        // `super.onCreate()` first: it is the shape every Android startup wants.
        super.onCreate()
        CONTEXT = applicationContext
        // The UI half of the boundary goes in first, before anything in the process can ask for it.
        // The reach that makes it a crash rather than a nuisance is measured: the service this
        // process starts calls `themePort().applyCustomColors` in its own onCreate, that port is
        // `UiTulkkiPorts` - the one `:app` class that names `:ui` for the island - and
        // `ThemeHelper.applyCustomColors` asks `UiHost.installed()` on its first line, so a build
        // that never installed the host dies before the service finishes starting. The second reach
        // is every screen: `:ui`'s own `BaseActivity.onStart` asks the same accessor, and an activity
        // can be launched with no service in the process at all. `:ui` may not name `:app`, so the
        // install belongs here, in the composition root - not beside the ports the service takes.
        UiHost.install(UiAppHost)
        // Tulkki's boundary: the ports every service takes in its own onCreate, and the engine's
        // host. This runs before any Service exists; see XmppTulkkiHost.
        XmppConnectionService.installPortsFactory(
                uk.xa0.tulkki.xmpp.services.TulkkiPorts.Factory { service ->
                    XmppTulkkiHost.install(service)
                })
        // Pair 7's trust port goes in here rather than in the service's onCreate, because two of its
        // five callers are static and may run before any service exists: Config's contact-domain
        // check, reached from the model, and HttpConnectionManager.okHttpClient, which Glide's module
        // calls at Glide's own init. The service re-asserts it in onCreate.
        XmppConnectionService.installTrustPort(XmppTulkkiHost.trustPorts())
        // 3.7 pair 9 cluster (b): the `:data` statics the island reaches, installed here for the
        // same reason as the trust port - the callers include static ones that may run before any
        // service exists (`TLSSocketFactory`, `Resolver`), so there is no `onCreate` to hang them
        // off. Unlike the other ports this one carries a singleton with no service in it, because
        // every body is a one-line forward to a `:data` static.
        XmppConnectionService.installDataStatics(DataStaticsHost)
        // Pair 8b's ports: the last view work `:data` does in place (clickable spans, the clipboard,
        // the soft keyboard, the URI-handler screen, and one preference). One call, before anything
        // can reach `ViewPorts.linkedText()`; an uninstalled port throws rather than no-ops.
        ViewPorts.install(
                UiViewPorts,
                UiViewPorts,
                UiViewPorts,
                UiViewPorts,
                UiViewPorts)
        // 3.7 pair 2's one port: `Conversation`'s command form normalises a `tel:` field through
        // `:data`'s own declaration, and the parser that knows the owner's country lives in `:app`
        // (`PhoneNumberUtilWrapper` reads `:ui`'s LocationProvider, so it cannot move). Installed
        // here because it needs nothing but a Context; an uninstalled port throws rather than
        // returning the number unchanged.
        PhoneNumberNormalizer.install(
                PhoneNumberNormalizer { context, input ->
                    PhoneNumberUtilWrapper.normalize(context, input)
                })
        // And the markup writer: `Message`'s XHTML half stayed in the model (its own
        // `updateReplyTo` appends through it), so `:data` asks for the writer instead of naming it.
        XhtmlBody.install(SpannedToXHTML)
        // The one-time move of the collection directory from upstream's name to ours. Before any
        // screen can name a path, and after the boundary is installed; a no-op on a fresh install.
        FileBackend.migrateLegacyStorageDirectory()
        EmojiInitializationService.execute(applicationContext)
        ExceptionHelper.init(applicationContext)
        applyThemeSettings()
        // Now both halves of the startup repair, in this order: the re-arm cancels the translation
        // queue's two one-shot rows (their enqueue policies cannot rewrite a stale worker class name),
        // and `ensureScheduled` immediately below enqueues a fresh -now, which drains whatever the
        // queue still owes and lays the next -later back down. It is fingerprint-guarded, so it does
        // that once per installed build and nothing on an ordinary start.
        WorkReArm.ifNeeded(applicationContext)
        // Tulkki's one line, folded in from the TulkkiApp subclass this commit deletes - and now a
        // decision rather than a statement: the queue is scheduled only while the interpreter is on,
        // and off the three WorkManager records are cancelled instead. A pending record is the one
        // place the off state could wake the process up on its own, so "off" has to be a state: with
        // nothing enqueued there is nothing to fire, and no worker can order the queue.
        //
        // The mode is asked through `interpreterAtStartup`, not `TranslationSettings.get`: the
        // latter builds the Keystore-backed store for the DeepSeek key, which the note above forbids
        // here. The two languages are ordinary preferences, and the static reads the same keys,
        // defaults and blank rule the instance does.
        //
        // Off, two things happen, and they are two halves of the same state. `cancel` stops the
        // wake-up existing; `clearInterpreterState` drops what the last on-mode run was still
        // carrying - the pending queue rows, whose captured target language is no longer the app
        // language, and the interpreter's last-failure record. This is the half that catches a flip
        // made while the process was dead, where no settings write could observe it; a flip made
        // while it runs is caught at the write site and calls the same method.
        if (TranslationSettings.interpreterAtStartup(applicationContext).enabled()) {
            TranslationWork.ensureScheduled(applicationContext)
        } else {
            TranslationWork.cancel(applicationContext)
            TranslationSettings.clearInterpreterState(applicationContext)
        }
    }

    fun applyThemeSettings() {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        if (sharedPreferences == null) {
            return
        }
        applyThemeSettings(sharedPreferences)
    }

    private fun applyThemeSettings(sharedPreferences: SharedPreferences) {
        AppCompatDelegate.setDefaultNightMode(getDesiredNightMode(this, sharedPreferences))
        val dynamicColorsOptions =
                DynamicColorsOptions.Builder()
                        .setPrecondition { activity, _ -> isDynamicColorsDesired(activity) }
                        .build()
        DynamicColors.applyToActivitiesIfAvailable(this, dynamicColorsOptions)
    }

    companion object {

        @SuppressLint("StaticFieldLeak")
        private var CONTEXT: Context? = null

        @JvmStatic
        fun getContext(): Context? = TulkkiApplication.CONTEXT

        @JvmStatic
        fun getDesiredNightMode(context: Context): Int {
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
            if (sharedPreferences == null) {
                return AppCompatDelegate.getDefaultNightMode()
            }
            return getDesiredNightMode(context, sharedPreferences)
        }

        @JvmStatic
        fun isDynamicColorsDesired(context: Context): Boolean {
            val preferences = PreferenceManager.getDefaultSharedPreferences(context)
            if (isCustomColorsDesired(context)) return false
            return preferences.getBoolean(AppSettings.DYNAMIC_COLORS, false)
        }

        @JvmStatic
        fun isCustomColorsDesired(context: Context): Boolean {
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
            val theme =
                    sharedPreferences.getString(
                            AppSettings.THEME,
                            context.getString(uk.xa0.tulkki.ui.R.string.theme))
            return "custom" == theme
        }

        private fun getDesiredNightMode(
                context: Context,
                sharedPreferences: SharedPreferences): Int {
            var theme =
                    sharedPreferences.getString(
                            AppSettings.THEME,
                            context.getString(uk.xa0.tulkki.ui.R.string.theme))

            // Migrate old themes to equivalent custom
            if ("oledblack" == theme) {
                theme = "custom"
                val p = PreferenceManager.getDefaultSharedPreferences(context)
                p.edit()
                        .putString(AppSettings.THEME, "custom")
                        .putBoolean("custom_theme_automatic", false)
                        .putBoolean("custom_theme_dark", true)
                        .putBoolean("custom_theme_color_match", true)
                        .putInt(
                                "custom_dark_theme_primary",
                                context.getColor(uk.xa0.tulkki.ui.R.color.white))
                        .putInt(
                                "custom_dark_theme_primary_dark",
                                context.getColor(android.R.color.black))
                        .putInt(
                                "custom_dark_theme_accent",
                                context.getColor(uk.xa0.tulkki.ui.R.color.yeller))
                        .putInt(
                                "custom_dark_theme_background_primary",
                                context.getColor(android.R.color.black))
                        .commit()
            }

            // Migrate old themes to equivalent custom
            if ("obsidian" == theme) {
                theme = "custom"
                val p = PreferenceManager.getDefaultSharedPreferences(context)
                p.edit()
                        .putString(AppSettings.THEME, "custom")
                        .putBoolean("custom_theme_automatic", false)
                        .putBoolean("custom_theme_dark", true)
                        .putInt(
                                "custom_dark_theme_primary",
                                context.getColor(uk.xa0.tulkki.ui.R.color.black_blue))
                        .putInt(
                                "custom_dark_theme_primary_dark",
                                context.getColor(uk.xa0.tulkki.ui.R.color.black_blue))
                        .putInt(
                                "custom_dark_theme_accent",
                                context.getColor(uk.xa0.tulkki.ui.R.color.yeller))
                        .putInt(
                                "custom_dark_theme_background_primary",
                                context.getColor(uk.xa0.tulkki.ui.R.color.blacker_blue))
                        .commit()
            }

            if ("custom" == theme) {
                if (sharedPreferences.getBoolean("custom_theme_automatic", false)) {
                    return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                return if (sharedPreferences.getBoolean("custom_theme_dark", false)) {
                    AppCompatDelegate.MODE_NIGHT_YES
                } else {
                    AppCompatDelegate.MODE_NIGHT_NO
                }
            }

            return getDesiredNightMode(theme)
        }

        // `theme` is nullable because its one caller reads it from SharedPreferences, whose
        // `getString` is `@Nullable`; the Java took null here and answered MODE_NIGHT_YES through the
        // two failed `equals` tests, which is what this still does.
        @JvmStatic
        fun getDesiredNightMode(theme: String?): Int {
            if ("automatic" == theme) {
                return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            } else if ("light" == theme) {
                return AppCompatDelegate.MODE_NIGHT_NO
            } else {
                return AppCompatDelegate.MODE_NIGHT_YES
            }
        }
    }
}
