package uk.xa0.tulkki.ui.util

import android.content.Context
import android.content.pm.PackageManager

object ConversationMenuConfigurator {

    private var microphoneAvailable = false

    @JvmStatic
    fun reloadFeatures(context: Context) {
        microphoneAvailable = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
    }
}
