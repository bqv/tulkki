package uk.xa0.tulkki.app.utils

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Log
import androidx.annotation.BoolRes
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import uk.xa0.tulkki.app.receiver.SystemEventReceiver
import uk.xa0.tulkki.data.AppSettings
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.xmpp.Config

/**
 * The Android-version and permission checks every other `:app` file asks, plus the two service
 * starts that have to know whether a foreground service is required.
 *
 * <p>An `object` with `@JvmStatic`s; the Java declared no constructor and nothing constructs it.
 *
 * <p><strong>One trap, and it is a Kotlin language fact: Java's multi-catch has no Kotlin
 * equivalent.</strong> `targetsTwentySix` caught `NameNotFoundException | RuntimeException` - a
 * checked and an unchecked type at once - and Kotlin cannot write that clause. It is written as two
 * catch blocks with the same body, so exactly the same two types are caught and nothing else; a
 * `catch (Exception)` would have been wider and is not what the Java did.
 *
 * <p>The `@BoolRes` and `@RequiresApi` annotations are carried across (`api =` is how Kotlin names
 * `RequiresApi`'s element); the `@NonNull`s are dropped, because Kotlin's non-null parameter type
 * says the same thing and the Java annotation only existed to say it.
 */
object Compatibility {

    @JvmStatic
    fun hasStoragePermission(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(
                            context,
                            android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                            PackageManager.PERMISSION_GRANTED

    @JvmStatic
    fun s(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    @JvmStatic
    fun runsTwentyFour(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N

    @JvmStatic
    fun runsTwentySix(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    private fun getBooleanPreference(
            context: Context,
            name: String,
            @BoolRes res: Int
    ): Boolean = getPreferences(context).getBoolean(name, context.resources.getBoolean(res))

    private fun getPreferences(context: Context): SharedPreferences =
            PreferenceManager.getDefaultSharedPreferences(context)

    private fun targetsTwentySix(context: Context): Boolean {
        try {
            val packageManager = context.packageManager
            val applicationInfo = packageManager.getApplicationInfo(context.packageName, 0)
            return applicationInfo.targetSdkVersion >= 26
        } catch (e: PackageManager.NameNotFoundException) {
            return true // when in doubt…
        } catch (e: RuntimeException) {
            return true // when in doubt…
        }
    }

    @JvmStatic
    fun runsAndTargetsTwentySix(context: Context): Boolean =
            runsTwentySix() && targetsTwentySix(context)

    @JvmStatic
    fun keepForegroundService(context: Context): Boolean =
            runsAndTargetsTwentySix(context) ||
                    getBooleanPreference(
                            context,
                            AppSettings.KEEP_FOREGROUND_SERVICE,
                            R.bool.enable_foreground_service)

    @JvmStatic
    fun startService(context: Context, intent: Intent) {
        try {
            if (runsAndTargetsTwentySix(context)) {
                intent.putExtra(SystemEventReceiver.EXTRA_NEEDS_FOREGROUND_SERVICE, true)
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: RuntimeException) {
            Log.d(Config.LOGTAG, context.javaClass.simpleName + " was unable to start service")
        }
    }

    @SuppressLint("UnsupportedChromeOsCameraSystemFeature")
    @JvmStatic
    fun hasFeatureCamera(context: Context): Boolean {
        val packageManager = context.packageManager
        return packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    @JvmStatic
    fun getRestrictBackgroundStatus(connectivityManager: ConnectivityManager): Int {
        try {
            return connectivityManager.restrictBackgroundStatus
        } catch (e: Exception) {
            Log.d(
                    Config.LOGTAG,
                    "platform bug detected. Unable to get restrict background status",
                    e)
            return ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    @JvmStatic
    fun isActiveNetworkMetered(connectivityManager: ConnectivityManager): Boolean {
        try {
            return connectivityManager.isActiveNetworkMetered
        } catch (e: RuntimeException) {
            // when in doubt better assume it's metered
            return true
        }
    }

    @JvmStatic
    fun pgpStartIntentSenderOptions(): Bundle? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(
                                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                        .toBundle()
            } else {
                null
            }
}
