package uk.xa0.tulkki.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import androidx.annotation.NonNull
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.lang.ref.WeakReference

class AvatarMarker(
    context: Context,
    private val avatarDrawable: Drawable,
    position: GeoPoint?
) : Overlay(), Drawable.Callback {

    private var position: GeoPoint? = position

    private val pinPath = Path()
    private val clipPath = Path()
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpWidth: Int
    private val bmpHeight: Int
    private val cx: Float
    private val cy: Float
    private val innerRadius: Float

    private val mapPoint = Point()
    private val handler = Handler(Looper.getMainLooper())
    private var mapViewRef: WeakReference<MapView>? = null

    init {
        val d = context.resources.displayMetrics.density
        val avatarDiameter = 44 * d
        val whiteBorder = 3 * d
        val outerRadius = avatarDiameter / 2f + whiteBorder
        val tipHeight = 14 * d
        val tipHalfWidth = 5 * d
        val borderStroke = 1.5f * d
        val extra = Math.ceil(borderStroke.toDouble()).toFloat()

        bmpWidth = Math.ceil((2 * outerRadius + 2 * extra).toDouble()).toInt()
        bmpHeight = Math.ceil((2 * outerRadius + tipHeight + 2 * extra).toDouble()).toInt()
        cx = bmpWidth / 2f
        cy = outerRadius + extra
        innerRadius = outerRadius - whiteBorder

        // Combined teardrop path: arc over the top + tip lines
        val halfAngleRad = Math.atan2(tipHalfWidth.toDouble(), outerRadius.toDouble())
        val halfAngleDeg = Math.toDegrees(halfAngleRad)
        val junctionDy = (outerRadius * Math.cos(halfAngleRad)).toFloat()
        val arcStart = (90 - halfAngleDeg).toFloat()
        val arcSweep = (-(360 - 2 * halfAngleDeg)).toFloat()
        val circleRect = RectF(
            cx - outerRadius, cy - outerRadius, cx + outerRadius, cy + outerRadius
        )

        pinPath.moveTo(cx + tipHalfWidth, cy + junctionDy)
        pinPath.arcTo(circleRect, arcStart, arcSweep, false)
        pinPath.lineTo(cx, cy + outerRadius + tipHeight)
        pinPath.close()

        clipPath.addCircle(cx, cy, innerRadius, Path.Direction.CW)

        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = borderStroke * 2 // fill will cover inner half
        strokePaint.color = Color.argb(160, 60, 60, 60)
        strokePaint.strokeJoin = Paint.Join.ROUND
        strokePaint.strokeCap = Paint.Cap.ROUND

        fillPaint.style = Paint.Style.FILL
        fillPaint.color = Color.WHITE

        avatarDrawable.setCallback(this)
        if (avatarDrawable is Animatable) {
            avatarDrawable.start()
        }
    }

    fun setPosition(position: GeoPoint?) {
        this.position = position
    }

    override fun draw(c: Canvas, view: MapView, shadow: Boolean) {
        if (shadow) return
        val ref = mapViewRef
        if (ref == null || ref.get() == null) {
            mapViewRef = WeakReference(view)
        }

        view.projection.toPixels(position ?: view.mapCenter, mapPoint)

        c.save()
        c.translate(mapPoint.x - bmpWidth / 2f, (mapPoint.y - bmpHeight).toFloat())

        // Stroke first so fill covers its inner half, leaving only the outer border visible
        c.drawPath(pinPath, strokePaint)
        c.drawPath(pinPath, fillPaint)

        c.save()
        c.clipPath(clipPath)
        avatarDrawable.setBounds(
            (cx - innerRadius).toInt(), (cy - innerRadius).toInt(),
            (cx + innerRadius).toInt(), (cy + innerRadius).toInt()
        )
        avatarDrawable.draw(c)
        c.restore()

        c.restore()
    }

    override fun onDetach(mapView: MapView) {
        avatarDrawable.setCallback(null)
        if (avatarDrawable is Animatable) {
            avatarDrawable.stop()
        }
        mapViewRef = null
    }

    // --- Drawable.Callback ---

    override fun invalidateDrawable(@NonNull who: Drawable) {
        val mv = mapViewRef?.get()
        if (mv != null) mv.postInvalidate()
    }

    override fun scheduleDrawable(@NonNull who: Drawable, @NonNull what: Runnable, `when`: Long) {
        handler.postAtTime(what, who, `when`)
    }

    override fun unscheduleDrawable(@NonNull who: Drawable, @NonNull what: Runnable) {
        handler.removeCallbacksAndMessages(who)
    }
}
