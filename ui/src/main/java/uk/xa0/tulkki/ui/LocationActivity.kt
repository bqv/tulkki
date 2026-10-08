package uk.xa0.tulkki.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import org.osmdroid.api.IGeoPoint
import org.osmdroid.api.IMapController
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Overlay
import uk.xa0.tulkki.app.http.HttpConnectionManager
import uk.xa0.tulkki.ui.util.LocationHelper
import uk.xa0.tulkki.ui.widget.AvatarMarker
import uk.xa0.tulkki.ui.widget.Marker
import uk.xa0.tulkki.ui.widget.MyLocation
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.R as XmppR

abstract class LocationActivity : XmppActivity(), LocationListener {

    protected var locationManager: LocationManager? = null

    // The Java field the not-yet-ported ShowLocationActivity reads directly.
    @JvmField
    protected var hasLocationFeature = false

    @JvmField
    protected var myLoc: Location? = null

    private var map: MapView? = null

    @JvmField
    protected var mapController: IMapController? = null

    @JvmField
    protected var marker_icon: Bitmap? = null

    protected fun clearMarkers() {
        val current = map ?: throw NullPointerException()
        synchronized(current.overlays) {
            val toRemove = ArrayList<Overlay>()
            for (overlay in current.overlays) {
                if (overlay is Marker || overlay is MyLocation || overlay is AvatarMarker) {
                    toRemove.add(overlay)
                }
            }
            current.overlays.removeAll(toRemove)
        }
    }

    protected open fun updateLocationMarkers() {
        clearMarkers()
    }

    protected override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = applicationContext

        val packageManager = ctx.packageManager
        hasLocationFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION) ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS) ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_NETWORK)
        this.locationManager =
            this.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        this.marker_icon = BitmapFactory.decodeResource(ctx.resources, R.drawable.marker)

        // Ask for location permissions if location services are enabled and we're
        // just starting the activity (we don't want to keep pestering them on every
        // screen rotation or if there's no point because it's disabled anyways).
        if (savedInstanceState == null) {
            requestPermissions(REQUEST_CODE_CREATE)
        }

        val config = Configuration.getInstance()
        config.load(ctx, getPreferences())
        config.setUserAgentValue(BuildConfig.APPLICATION_ID + "/" + BuildConfig.VERSION_CODE)
        val useTor = getBooleanPreference("use_tor", XmppR.bool.use_tor)
        val useI2P = getBooleanPreference("use_i2p", XmppR.bool.use_i2p)
        if (useTor || useI2P) {
            config.setHttpProxy(HttpConnectionManager.getProxy(useI2P))
        }
    }

    protected override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        val current = map ?: throw NullPointerException()
        val center = current.mapCenter
        outState.putParcelable(KEY_LOCATION, GeoPoint(center.latitude, center.longitude))
        outState.putDouble(KEY_ZOOM_LEVEL, current.zoomLevelDouble)
    }

    protected override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)

        if (savedInstanceState.containsKey(KEY_LOCATION)) {
            val center: GeoPoint? = savedInstanceState.getParcelable(KEY_LOCATION)
            (mapController ?: throw NullPointerException()).setCenter(center)
        }
        if (savedInstanceState.containsKey(KEY_ZOOM_LEVEL)) {
            (mapController ?: throw NullPointerException())
                .setZoom(savedInstanceState.getDouble(KEY_ZOOM_LEVEL))
        }
    }

    protected fun setupMapView(mapView: MapView, pos: GeoPoint) {
        map = mapView
        mapView.overlays.add(CopyrightOverlay(this))
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        mapView.setMultiTouchControls(true)
        mapView.setTilesScaledToDpi(false)
        mapController = mapView.controller
        val controller = mapController ?: throw NullPointerException()
        controller.setZoom(Config.Map.INITIAL_ZOOM_LEVEL)
        controller.setCenter(pos)
    }

    protected fun gotoLoc() {
        val current = map ?: throw NullPointerException()
        gotoLoc(current.zoomLevelDouble == Config.Map.INITIAL_ZOOM_LEVEL)
    }

    protected abstract fun gotoLoc(setZoomLevel: Boolean)

    protected abstract fun setMyLoc(location: Location?)

    protected fun requestLocationUpdates() {
        val manager = locationManager
        if (!hasLocationFeature || manager == null) {
            return
        }

        Log.d(Config.LOGTAG, "Requesting location updates...")
        val lastKnownLocationGps: Location?
        val lastKnownLocationNetwork: Location?

        try {
            if (manager.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                lastKnownLocationGps =
                    manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)

                if (lastKnownLocationGps != null) {
                    setMyLoc(lastKnownLocationGps)
                }
                manager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    Config.Map.LOCATION_FIX_TIME_DELTA,
                    Config.Map.LOCATION_FIX_SPACE_DELTA,
                    this,
                )
            } else {
                lastKnownLocationGps = null
            }

            if (manager.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                lastKnownLocationNetwork =
                    manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (lastKnownLocationNetwork != null && LocationHelper.isBetterLocation(
                        lastKnownLocationNetwork,
                        lastKnownLocationGps,
                    )
                ) {
                    setMyLoc(lastKnownLocationNetwork)
                }
                manager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    Config.Map.LOCATION_FIX_TIME_DELTA,
                    Config.Map.LOCATION_FIX_SPACE_DELTA,
                    this,
                )
            }

            // If something else is also querying for location more frequently than we are, the battery is already being
            // drained. Go ahead and use the existing locations as often as we can get them.
            if (manager.allProviders.contains(LocationManager.PASSIVE_PROVIDER)) {
                manager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 0L, 0f, this)
            }
        } catch (ignored: SecurityException) {
            // Do nothing if the users device has no location providers.
        }
    }

    @Throws(SecurityException::class)
    protected fun pauseLocationUpdates() {
        locationManager?.removeUpdates(this)
    }

    public override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.getItemId() == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    public override fun onPause() {
        super.onPause()
        Configuration.getInstance().save(this, getPreferences())
        (map ?: throw NullPointerException()).onPause()
        try {
            pauseLocationUpdates()
        } catch (ignored: SecurityException) {
        }
    }

    protected abstract fun updateUi()

    protected fun mapAtInitialLoc(): Boolean {
        val current = map ?: throw NullPointerException()
        return current.zoomLevelDouble == Config.Map.INITIAL_ZOOM_LEVEL
    }

    public override fun onResume() {
        super.onResume()
        Configuration.getInstance().load(this, getPreferences())
        val current = map ?: throw NullPointerException()
        current.onResume()
        this.setMyLoc(null)
        requestLocationUpdates()
        updateLocationMarkers()
        updateUi()
        current.setTileSource(TileSourceFactory.MAPNIK)
        current.setTilesScaledToDpi(true)

        if (mapAtInitialLoc()) {
            gotoLoc()
        }
    }

    protected fun hasLocationPermissions(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    protected fun requestPermissions(request_code: Int) {
        if (!hasLocationPermissions()) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                request_code,
            )
        }
    }

    public override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        for (j in grantResults.indices) {
            if (Manifest.permission.ACCESS_FINE_LOCATION == permissions[j] ||
                Manifest.permission.ACCESS_COARSE_LOCATION == permissions[j]
            ) {
                if (grantResults[j] == PackageManager.PERMISSION_GRANTED) {
                    requestLocationUpdates()
                }
            }
        }
    }

    protected fun isLocationEnabled(): Boolean =
        try {
            val locationMode = android.provider.Settings.Secure.getInt(
                contentResolver,
                android.provider.Settings.Secure.LOCATION_MODE,
            )
            locationMode != android.provider.Settings.Secure.LOCATION_MODE_OFF
        } catch (e: Exception) {
            false
        }

    protected override fun refreshUiReal() {
        updateUi()
    }

    companion object {
        const val REQUEST_CODE_CREATE = 0
        const val REQUEST_CODE_FAB_PRESSED = 1
        const val REQUEST_CODE_SNACKBAR_PRESSED = 2

        private const val KEY_LOCATION = "loc"
        private const val KEY_ZOOM_LEVEL = "zoom"
    }
}
