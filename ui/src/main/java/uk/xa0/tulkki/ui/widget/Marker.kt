package uk.xa0.tulkki.ui.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.mylocation.SimpleLocationOverlay

/**
 * An immutable marker overlay.
 */
class Marker(
    private val icon: Bitmap?,
    position: GeoPoint?
) : SimpleLocationOverlay(icon) {
    private var position: GeoPoint? = position
    private val mapPoint = Point()

    /**
     * Create a marker overlay which will be drawn centered in the view.
     * @param icon A bitmap icon for the marker
     */
    constructor(icon: Bitmap?) : this(icon, null)

    fun setPosition(position: GeoPoint?) {
        this.position = position
    }

    override fun draw(c: Canvas, view: MapView, shadow: Boolean) {
        super.draw(c, view, shadow)

        // If no position was set for the marker, draw it centered in the view.
        view.projection.toPixels(position ?: view.mapCenter, mapPoint)

        val icon = this.icon ?: throw NullPointerException()
        c.drawBitmap(
            icon,
            (mapPoint.x - icon.width / 2).toFloat(),
            (mapPoint.y - icon.height).toFloat(),
            null
        )
    }
}
