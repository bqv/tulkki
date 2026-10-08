package uk.xa0.tulkki.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.util.Rational
import uk.xa0.tulkki.xmpp.Config

class SurfaceViewRenderer : org.webrtc.SurfaceViewRenderer {

    private var aspectRatio = Rational(1, 1)

    private var onAspectRatioChanged: OnAspectRatioChanged? = null

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    public override fun onFrameResolutionChanged(
        videoWidth: Int,
        videoHeight: Int,
        rotation: Int
    ) {
        super.onFrameResolutionChanged(videoWidth, videoHeight, rotation)
        val rotatedWidth = if (rotation != 0 && rotation != 180) videoHeight else videoWidth
        val rotatedHeight = if (rotation != 0 && rotation != 180) videoWidth else videoHeight
        val currentRational = this.aspectRatio
        this.aspectRatio = Rational(rotatedWidth, rotatedHeight)
        Log.d(
            Config.LOGTAG,
            "onFrameResolutionChanged(" + rotatedWidth + "," + rotatedHeight + "," +
                aspectRatio + ")"
        )
        val listener = onAspectRatioChanged
        if (currentRational == this.aspectRatio || listener == null) {
            return
        }
        listener.onAspectRatioChanged(this.aspectRatio)
    }

    fun setOnAspectRatioChanged(onAspectRatioChanged: OnAspectRatioChanged?) {
        this.onAspectRatioChanged = onAspectRatioChanged
    }

    fun getAspectRatio(): Rational {
        return this.aspectRatio
    }

    interface OnAspectRatioChanged {
        fun onAspectRatioChanged(rational: Rational)
    }
}
