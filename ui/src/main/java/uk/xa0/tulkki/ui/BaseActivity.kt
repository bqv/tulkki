package uk.xa0.tulkki.ui

import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import uk.xa0.tulkki.ui.host.UiHost
import uk.xa0.tulkki.ui.util.SettingsUtils

abstract class BaseActivity : AppCompatActivity() {
    private var isDynamicColors: Boolean? = null

    public override fun onStart() {
        super.onStart()
        val desiredNightMode = UiHost.installed().desiredNightMode(this)
        if (setDesiredNightMode(desiredNightMode)) {
            return
        }
        val isDynamicColors = UiHost.installed().dynamicColorsDesired(this)
        setDynamicColors(isDynamicColors)
    }

    protected override fun onResume() {
        super.onResume()
        SettingsUtils.applyScreenshotSetting(this)
    }

    fun setDynamicColors(isDynamicColors: Boolean) {
        if (this.isDynamicColors == null) {
            this.isDynamicColors = isDynamicColors
        } else {
            if (this.isDynamicColors != isDynamicColors) {
                Log.i(
                    "Recreating {} because dynamic color setting has changed",
                    javaClass.simpleName,
                )
                recreate()
            }
        }
    }

    fun setDesiredNightMode(desiredNightMode: Int): Boolean {
        if (desiredNightMode == AppCompatDelegate.getDefaultNightMode()) {
            return false
        }
        AppCompatDelegate.setDefaultNightMode(desiredNightMode)
        Log.i("Recreating {} because desired night mode has changed", javaClass.simpleName)
        recreate()
        return true
    }
}
