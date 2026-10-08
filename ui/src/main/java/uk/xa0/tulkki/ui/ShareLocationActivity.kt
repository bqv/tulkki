package uk.xa0.tulkki.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.common.math.DoubleMath
import java.math.RoundingMode
import org.osmdroid.api.IGeoPoint
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import uk.xa0.tulkki.ui.util.LocationHelper
import uk.xa0.tulkki.ui.utils.LocationProvider
import uk.xa0.tulkki.ui.widget.Marker
import uk.xa0.tulkki.ui.widget.MyLocation
import uk.xa0.tulkki.xmpp.Config

/**
 * The share-location screen: an OpenStreetMap view the owner pans, a fix-to-position button, and the
 * cancel/share bar that hands the picked point back.
 *
 * <p>**The layout is gone.** `activity_share_location.xml` held a toolbar, a `CoordinatorLayout` with
 * the `MapView` and the fix `FloatingActionButton`, and the cancel/share `button_bar`; the file is
 * deleted, the bar is the shared chrome ([uk.xa0.tulkki.ui.chrome.TulkkiChrome]), and
 * `setSupportActionBar`/`configureActionBar`/`Activities.setStatusAndNavigationBarColors` went with
 * it. The `Snackbar` the `progress`-style coordinator hosted is the screen's own [SnackbarHost],
 * because the chrome owns the scaffold and does not expose one; the message, the "Enable" action and
 * the `LENGTH_INDEFINITE` duration are the same, and `dismiss`/`show` are the same flag's two values.
 *
 * <p>**The map stays a platform view.** `org.osmdroid.views.MapView` cannot be composed, so it is
 * built here rather than inflated and [ShareLocationScreen] hosts it through [AndroidView] - and it
 * is built in `onCreate` before the composition, because the base's `onPause`/`onResume`/state save
 * reach `LocationActivity`'s own map field and must find it whether or not the composition has run.
 * Adding attributes in XML and adding none in code is the same map: the deleted tag carried none.
 *
 * <p>**Everything the listeners did is state.** `fab`'s visibility and icon, and whether the
 * location-disabled snackbar is up, are [ShareLocationScreenState]; the share, cancel, fab and
 * snackbar-action branches are unchanged and still call the same methods they always did.
 */
class ShareLocationActivity : LocationActivity(), LocationListener {

    private lateinit var mapView: MapView
    private var marker_fixed_to_loc = false
    private var noAskAgain = false

    /** Everything the deleted layout drew, read by [ShareLocationScreen]. */
    private var state by mutableStateOf(
        // The layout's own initial values: the fab visible and fixed, the snackbar down.
        ShareLocationScreenState(fabVisible = true, fabFixed = true),
    )

    protected override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putBoolean(KEY_FIXED_TO_LOC, marker_fixed_to_loc)
    }

    protected override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)

        if (savedInstanceState.containsKey(KEY_FIXED_TO_LOC)) {
            this.marker_fixed_to_loc = savedInstanceState.getBoolean(KEY_FIXED_TO_LOC)
        }
    }

    protected override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val map = MapView(this)
        mapView = map
        setupMapView(map, LocationProvider.getGeoPoint(this))

        // The chrome draws its bar behind the system bars and hands the content the space they
        // leave, so the window must not inset itself for them first - on every API level, which is
        // what enableEdgeToEdge settles. See TulkkiChrome for the whole pattern.
        enableEdgeToEdge()

        setTulkkiContent(darkTheme = isDark()) {
            TulkkiChrome(
                title = stringResource(R.string.title_activity_share_location),
                onUp = { finish() },
            ) {
                ShareLocationScreen(
                    state = state,
                    map = {
                        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
                    },
                    onCancel = {
                        setResult(RESULT_CANCELED)
                        finish()
                    },
                    onShare = { shareLocation() },
                    onFab = { onFabPressed() },
                    onEnableLocation = { onEnablePressed() },
                )
            }
        }

        this.marker_fixed_to_loc = isLocationEnabledAndAllowed()
    }

    private fun shareLocation() {
        val result = Intent()
        val loc = myLoc
        if (marker_fixed_to_loc && loc != null) {
            result.putExtra("latitude", loc.latitude)
            result.putExtra("longitude", loc.longitude)
            result.putExtra("altitude", loc.altitude)
            result.putExtra(
                "accuracy",
                DoubleMath.roundToInt(loc.accuracy.toDouble(), RoundingMode.HALF_UP),
            )
        } else {
            val markerPoint: IGeoPoint = mapView.mapCenter
            result.putExtra("latitude", markerPoint.latitude)
            result.putExtra("longitude", markerPoint.longitude)
        }
        setResult(RESULT_OK, result)
        finish()
    }

    /** The fix button's branch, `fab.setOnClickListener` unchanged. */
    private fun onFabPressed() {
        if (!marker_fixed_to_loc) {
            if (!isLocationEnabled()) {
                startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            } else {
                requestPermissions(LocationActivity.REQUEST_CODE_FAB_PRESSED)
            }
        }
        toggleFixedLocation()
    }

    /** The snackbar's "Enable" action, `snackBar.setAction` unchanged. */
    private fun onEnablePressed() {
        if (isLocationEnabledAndAllowed()) {
            updateUi()
        } else if (!hasLocationPermissions()) {
            requestPermissions(LocationActivity.REQUEST_CODE_SNACKBAR_PRESSED)
        } else if (!isLocationEnabled()) {
            startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
    }

    public override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (grantResults.isNotEmpty() && grantResults[0] != PackageManager.PERMISSION_GRANTED &&
            permissions.isNotEmpty() &&
            (
                Manifest.permission.LOCATION_HARDWARE == permissions[0] ||
                    Manifest.permission.ACCESS_FINE_LOCATION == permissions[0] ||
                    Manifest.permission.ACCESS_COARSE_LOCATION == permissions[0]
                ) &&
            !shouldShowRequestPermissionRationale(permissions[0])
        ) {
            noAskAgain = true
        }

        if (!noAskAgain && requestCode == LocationActivity.REQUEST_CODE_SNACKBAR_PRESSED &&
            !isLocationEnabled() && hasLocationPermissions()
        ) {
            startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
        updateUi()
    }

    protected override fun gotoLoc(setZoomLevel: Boolean) {
        val loc = myLoc
        val controller = mapController
        if (loc != null && controller != null) {
            if (setZoomLevel) {
                controller.setZoom(Config.Map.FINAL_ZOOM_LEVEL)
            }
            controller.animateTo(GeoPoint(loc))
        }
    }

    // The Java base called this with null in onResume; the parameter stays nullable.
    protected override fun setMyLoc(location: Location?) {
        this.myLoc = location
    }

    protected override fun updateLocationMarkers() {
        super.updateLocationMarkers()
        val loc = myLoc
        if (loc != null) {
            this.mapView.overlays.add(MyLocation(this, null, loc))
            if (this.marker_fixed_to_loc) {
                this.mapView.overlays.add(Marker(marker_icon, GeoPoint(loc)))
            } else {
                this.mapView.overlays.add(Marker(marker_icon))
            }
        } else {
            this.mapView.overlays.add(Marker(marker_icon))
        }
    }

    public override fun onLocationChanged(location: Location) {
        if (this.myLoc == null) {
            this.marker_fixed_to_loc = true
        }
        updateUi()
        if (LocationHelper.isBetterLocation(location, this.myLoc)) {
            val oldLoc = this.myLoc
            this.myLoc = location

            // Don't jump back to the users location if they're not moving (more or less).
            if (oldLoc == null || (this.marker_fixed_to_loc && location.distanceTo(oldLoc) > 1)) {
                gotoLoc()
            }

            updateLocationMarkers()
        }
    }

    public override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    public override fun onProviderEnabled(provider: String) {}

    public override fun onProviderDisabled(provider: String) {}

    private fun isLocationEnabledAndAllowed(): Boolean =
        this.hasLocationFeature && this.hasLocationPermissions() && this.isLocationEnabled()

    private fun toggleFixedLocation() {
        this.marker_fixed_to_loc = isLocationEnabledAndAllowed() && !this.marker_fixed_to_loc
        if (this.marker_fixed_to_loc) {
            gotoLoc(false)
        }
        updateLocationMarkers()
        updateUi()
    }

    protected override fun updateUi() {
        val enabled = isLocationEnabledAndAllowed()
        val showSnackbar = hasLocationFeature && !noAskAgain && !enabled
        // The old icon swap hopped to the UI thread; a state write has to be there anyway.
        runOnUiThread {
            state =
                state.copy(
                    showLocationDisabledSnackbar = showSnackbar,
                    fabVisible = enabled,
                    fabFixed = marker_fixed_to_loc,
                )
        }
    }

    protected override fun onBackendConnected() {}

    companion object {
        private const val KEY_FIXED_TO_LOC = "fixed_to_loc"
    }
}

/**
 * What [ShareLocationScreen] draws: whether the location-disabled snackbar is up, and whether the
 * fix button is on screen and fixed to the owner's position.
 *
 * <p>The values are the deleted layout's `fab` visibility and `ic_gps_fixed`/`ic_gps_not_fixed`
 * choice, plus the snackbar's `show`/`dismiss` state; the defaults are the layout's own.
 */
data class ShareLocationScreenState(
    val showLocationDisabledSnackbar: Boolean = false,
    val fabVisible: Boolean = true,
    val fabFixed: Boolean = true,
)

/**
 * The share-location body: the map with the fix button over its bottom end, the snackbar above the
 * button bar, and the cancel/share bar itself.
 *
 * <p>Sizes are the deleted layout's: the fab's 16 dp margin, the button bar's 16 dp by 8 dp padding,
 * the cancel a `Widget.Material3.Button.TextButton` like its style said and the share the default
 * button. The map is a slot because `MapView` is a platform view and cannot be composed; the
 * screenshot cells pass an empty one, exactly as the add-reaction and viewer screens do for the emoji
 * grid and the player page.
 */
@Composable
fun ShareLocationScreen(
    state: ShareLocationScreenState,
    map: @Composable () -> Unit,
    onCancel: () -> Unit,
    onShare: () -> Unit,
    onFab: () -> Unit,
    onEnableLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val message = stringResource(R.string.location_disabled)
    val actionLabel = stringResource(R.string.enable)
    // `snackBar.show()`/`snackBar.dismiss()`: the effect runs while the flag is up, and cancelling it
    // is the dismiss.
    LaunchedEffect(state.showLocationDisabledSnackbar) {
        if (state.showLocationDisabledSnackbar) {
            val result =
                snackbarHostState.showSnackbar(
                    message = message,
                    actionLabel = actionLabel,
                    duration = SnackbarDuration.Indefinite,
                )
            if (result == SnackbarResult.ActionPerformed) {
                onEnableLocation()
            }
        }
    }
    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            map()
            if (state.fabVisible) {
                FloatingActionButton(
                    onClick = onFab,
                    modifier =
                        Modifier.align(Alignment.BottomEnd)
                            .padding(16.dp),
                ) {
                    Icon(
                        painter =
                            painterResource(
                                if (state.fabFixed) R.drawable.ic_gps_fixed_24dp
                                else R.drawable.ic_gps_not_fixed_24dp
                            ),
                        contentDescription =
                            stringResource(
                                if (state.fabFixed) R.string.action_unfix_from_location
                                else R.string.action_fix_to_location
                            ),
                    )
                }
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onCancel) {
                Text(stringResource(uk.xa0.tulkki.data.R.string.cancel))
            }
            Button(onClick = onShare) { Text(stringResource(R.string.share)) }
        }
    }
}
