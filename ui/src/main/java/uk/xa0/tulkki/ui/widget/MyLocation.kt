package uk.xa0.tulkki.ui.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.location.Location
import androidx.core.content.ContextCompat
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.TileSystem
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.mylocation.SimpleLocationOverlay
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.xmpp.Config

class MyLocation(ctx: Context, icon: Bitmap?, location: Location) :
    SimpleLocationOverlay(icon) {
    private val position = GeoPoint(location)
    private val accuracy = location.accuracy
    private val mapCenterPoint = Point()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        val accent = ContextCompat.getColor(ctx, R.color.blue500)
        fill.color = accent
        fill.style = Paint.Style.FILL
        outline.color = accent
        outline.alpha = 50
        outline.style = Paint.Style.FILL
    }

    override fun draw(c: Canvas, view: MapView, shadow: Boolean) {
        super.draw(c, view, shadow)

        view.projection.toPixels(position, mapCenterPoint)
        c.drawCircle(
            mapCenterPoint.x.toFloat(),
            mapCenterPoint.y.toFloat(),
            maxOf(
                (Config.Map.MY_LOCATION_INDICATOR_SIZE +
                    Config.Map.MY_LOCATION_INDICATOR_OUTLINE_SIZE).toFloat(),
                accuracy /
                    TileSystem.GroundResolution(position.latitude, view.zoomLevel).toFloat()
            ),
            outline
        )
        c.drawCircle(
            mapCenterPoint.x.toFloat(),
            mapCenterPoint.y.toFloat(),
            Config.Map.MY_LOCATION_INDICATOR_SIZE.toFloat(),
            fill
        )
    }
}
