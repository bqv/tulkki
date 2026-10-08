package uk.xa0.tulkki.ui.util

import android.content.Intent

class ActivityResult private constructor(
    @JvmField val requestCode: Int,
    @JvmField val resultCode: Int,
    @JvmField val data: Intent?,
) {

    companion object {
        @JvmStatic
        fun of(requestCode: Int, resultCode: Int, data: Intent?): ActivityResult =
            ActivityResult(requestCode, resultCode, data)
    }
}
