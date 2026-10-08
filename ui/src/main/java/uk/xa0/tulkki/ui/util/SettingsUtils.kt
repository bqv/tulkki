package uk.xa0.tulkki.ui.util

import android.app.Activity
import android.view.WindowManager

import uk.xa0.tulkki.data.AppSettings

object SettingsUtils {

    @JvmStatic
    fun applyScreenshotSetting(activity: Activity) {
        val appSettings = AppSettings(activity)
        val activityWindow = activity.window
        if (appSettings.isAllowScreenshots()) {
            activityWindow.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activityWindow.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
