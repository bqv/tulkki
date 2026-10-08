package uk.xa0.tulkki.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.location.Location
import android.location.LocationListener
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.common.base.Strings
import com.google.common.primitives.Doubles
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import uk.xa0.tulkki.ui.chrome.ChromeMenuItem
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import uk.xa0.tulkki.ui.util.LocationHelper
import uk.xa0.tulkki.ui.util.UriHelper
import uk.xa0.tulkki.ui.utils.GeoHelper
import uk.xa0.tulkki.ui.utils.LiveLocationManager
import uk.xa0.tulkki.ui.utils.LocationProvider
import uk.xa0.tulkki.ui.widget.AvatarMarker
import uk.xa0.tulkki.ui.widget.Marker
import uk.xa0.tulkki.ui.widget.MyLocation
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.services.XmppConnectionService

/**
 * The show-location screen: someone's position on an OpenStreetMap view, with copy, share and
 * navigate.
 *
 * <p>**The layout is gone.** `activity_show_location.xml` held a toolbar, the `MapView` and the
 * navigate `FloatingActionButton`; the file is deleted, the bar is the shared chrome
 * ([uk.xa0.tulkki.ui.chrome.TulkkiChrome]) and
 * `setSupportActionBar`/`configureActionBar`/`Activities.setStatusAndNavigationBarColors` went with
 * it, along with `menu/menu_show_location.xml` - its three items are the chrome's overflow now, in
 * the same order, calling the same three branches `onOptionsItemSelected` used to pick.
 *
 * <p>**The map stays a platform view.** `org.osmdroid.views.MapView` cannot be composed, so it is
 * built here rather than inflated and [ShowLocationScreen] hosts it through [AndroidView]; it is
 * created in `onCreate` before the composition because `LocationActivity`'s own `onPause`/`onResume`
 * and state save reach its map field and must find it regardless. The deleted tag carried no
 * attributes, so building it in code adds nothing the XML had.
 *
 * <p>The navigate button's visibility is `resolveActivityInfo` state, exactly as `updateUi` set the
 * fab's before; everything else is the old code with `binding.map` renamed to [mapView].
 */
class ShowLocationActivity : LocationActivity(), LocationListener,
    LiveLocationManager.PositionListener, uk.xa0.tulkki.xmpp.services.OnConversationUpdate {

    private var loc: GeoPoint = LocationProvider.FALLBACK
    private lateinit var mapView: MapView
    private var liveSessionId: String? = null
    private var avatarMarker: AvatarMarker? = null
    private var genericMarker: Marker? = null

    /** `fab` visibility: whether a navigation app can take the geo URI. */
    private var navigationAvailable by mutableStateOf(true)

    private fun createGeoUri(): Uri =
        Uri.parse("geo:" + this.loc.latitude + "," + this.loc.longitude)

    protected override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val map = MapView(this)
        mapView = map
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setupMapView(map, this.loc)

        // The chrome draws its bar behind the system bars and hands the content the space they
        // leave, so the window must not inset itself for them first - on every API level, which is
        // what enableEdgeToEdge settles. See TulkkiChrome for the whole pattern.
        enableEdgeToEdge()

        setTulkkiContent(darkTheme = isDark()) {
            TulkkiChrome(
                title = stringResource(R.string.title_activity_show_location),
                onUp = { finish() },
                menu =
                    listOf(
                        ChromeMenuItem(stringResource(R.string.action_share_location)) {
                            shareLocation()
                        },
                        ChromeMenuItem(stringResource(R.string.open_with)) { openWith() },
                        ChromeMenuItem(stringResource(R.string.action_copy_location)) {
                            copyLocation()
                        },
                    ),
            ) {
                ShowLocationScreen(
                    navigationAvailable = navigationAvailable,
                    map = {
                        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
                    },
                    onNavigate = { startNavigation() },
                )
            }
        }

        val intent: Intent? = getIntent()
        if (intent == null) {
            return
        }
        liveSessionId = intent.getStringExtra("live_session_id")
        when (Strings.nullToEmpty(intent.getAction())) {
            "eu.siacs.conversations.location.show" -> {
                if (intent.hasExtra("longitude") && intent.hasExtra("latitude")) {
                    val longitude = intent.getDoubleExtra("longitude", 0.0)
                    val latitude = intent.getDoubleExtra("latitude", 0.0)
                    this.loc = GeoPoint(latitude, longitude)
                }
            }
            Intent.ACTION_VIEW -> {
                val uri = intent.data
                if (uri != null) {
                    val point = try {
                        GeoHelper.parseGeoPoint(uri)
                    } catch (e: Exception) {
                        null
                    }
                    if (point != null) {
                        this.loc = point
                        val query = UriHelper.parseQueryString(uri.query)
                        val z = query["z"]
                        val zoom = if (z.isNullOrEmpty()) null else Doubles.tryParse(z)
                        if (zoom != null) {
                            Log.d(Config.LOGTAG, "inferring zoom level " + zoom + " from geo uri")
                            (mapController ?: throw NullPointerException()).setZoom(zoom)
                            gotoLoc(false)
                        }
                    }
                }
            }
        }
        updateLocationMarkers()
        gotoLoc(true)
    }

    protected override fun gotoLoc(setZoomLevel: Boolean) {
        val controller = mapController
        if (controller != null) {
            if (setZoomLevel) {
                controller.setZoom(Config.Map.FINAL_ZOOM_LEVEL)
            }
            controller.animateTo(this.loc)
        }
    }

    public override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateUi()
    }

    protected override fun setMyLoc(location: Location?) {
        this.myLoc = location
    }

    protected override fun updateLocationMarkers() {
        super.updateLocationMarkers()
        val ownLocation = myLoc
        if (ownLocation != null) {
            this.mapView.overlays.add(MyLocation(this, null, ownLocation))
        }
        val avatarDrawable = LiveLocationManager.getInstance().getSessionAvatar(liveSessionId)
        if (avatarDrawable != null) {
            var marker = this.avatarMarker
            if (marker == null) {
                marker = AvatarMarker(this, avatarDrawable, this.loc)
                this.avatarMarker = marker
            } else {
                marker.setPosition(this.loc)
            }
            this.mapView.overlays.add(marker)
        } else {
            var marker = this.genericMarker
            if (marker == null) {
                marker = Marker(this.marker_icon, this.loc)
                this.genericMarker = marker
            } else {
                marker.setPosition(this.loc)
            }
            this.mapView.overlays.add(marker)
        }
    }

    public override fun onResume() {
        super.onResume()
        if (liveSessionId != null) {
            LiveLocationManager.getInstance().addListener(this)
            val incoming = LiveLocationManager.getInstance().getSession(liveSessionId)
            if (incoming != null && !incoming.isExpired()) {
                this.loc = GeoPoint(incoming.latitude, incoming.longitude)
                updateLocationMarkers()
                gotoLoc(false)
            } else {
                // outgoing session: apply the last sent position
                for (outgoing in ArrayList(
                        LiveLocationManager.getInstance().allOutgoingSessions,
                    )
                ) {
                    if (liveSessionId == outgoing.sessionId && !outgoing.isExpired()) {
                        this.loc = GeoPoint(outgoing.latitude, outgoing.longitude)
                        updateLocationMarkers()
                        gotoLoc(false)
                        break
                    }
                }
            }
        }
    }

    public override fun onPause() {
        super.onPause()
        if (liveSessionId != null) {
            LiveLocationManager.getInstance().removeListener(this)
        }
    }

    public override fun onPositionUpdate(sessionId: String, latitude: Double, longitude: Double) {
        if (liveSessionId != null && liveSessionId == sessionId) {
            runOnUiThread {
                this.loc = GeoPoint(latitude, longitude)
                updateLocationMarkers()
                gotoLoc(false)
                if (::mapView.isInitialized) {
                    this.mapView.postInvalidate()
                }
            }
        }
    }

    public override fun onSessionExpired(sessionId: String) {
        // Session expired; title could be updated here
    }

    /** `action_copy_location`: the geo URI on the clipboard, with the old toast. */
    private fun copyLocation() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText("location", createGeoUri().toString())
        (clipboard ?: throw NullPointerException()).setPrimaryClip(clip)
        Toast.makeText(this, R.string.url_copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }

    /** `action_share_location`: the geo URI as text, through the chooser. */
    private fun shareLocation() {
        val shareIntent = Intent()
        shareIntent.action = Intent.ACTION_SEND
        shareIntent.putExtra(Intent.EXTRA_TEXT, createGeoUri().toString())
        shareIntent.type = "text/plain"
        try {
            startActivity(Intent.createChooser(shareIntent, getText(R.string.share_with)))
        } catch (e: ActivityNotFoundException) {
            // This should happen only on faulty androids because normally chooser is always
            // available
            Toast.makeText(
                this,
                R.string.no_application_found_to_open_file,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    /** `action_open_with`: the geo URI handed to whatever can view it. */
    private fun openWith() {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = createGeoUri()
        try {
            startActivity(Intent.createChooser(intent, getText(R.string.open_with)))
        } catch (e: ActivityNotFoundException) {
            // This should happen only on faulty androids because normally chooser is always
            // available
            Toast.makeText(
                this,
                R.string.no_application_found_to_open_file,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun startNavigation() {
        startActivity(getStartNavigationIntent())
    }

    private fun getStartNavigationIntent(): Intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse(
            "google.navigation:q=" +
                this.loc.latitude +
                "," +
                this.loc.longitude,
        ),
    )

    protected override fun updateUi() {
        val intent = getStartNavigationIntent()
        val activityInfo: ActivityInfo? = intent.resolveActivityInfo(packageManager, 0)
        navigationAvailable = activityInfo != null
    }

    public override fun onLocationChanged(location: Location) {
        if (LocationHelper.isBetterLocation(location, this.myLoc)) {
            this.myLoc = location
            updateLocationMarkers()
        }
    }

    public override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    public override fun onProviderEnabled(provider: String) {}

    public override fun onProviderDisabled(provider: String) {}

    public override fun onConversationUpdate() {
        updateUi()
    }

    public override fun onConversationUpdate(newCaps: Boolean) {
        updateUi()
    }

    protected override fun onBackendConnected() {}
}

/**
 * The show-location body: the map, with the navigate button over its bottom end when a navigation
 * app can take the geo URI.
 *
 * <p>The button's 16 dp end/bottom margin and its `ic_directions_24dp` are the deleted layout's, and
 * so is the content description the layout gave it. The map is a slot because `MapView` is a
 * platform view and cannot be composed; the screenshot cells pass an empty one, as the add-reaction
 * and viewer screens do for their own platform surfaces.
 */
@Composable
fun ShowLocationScreen(
    navigationAvailable: Boolean,
    map: @Composable () -> Unit,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        map()
        if (navigationAvailable) {
            FloatingActionButton(
                onClick = onNavigate,
                modifier =
                    Modifier.align(Alignment.BottomEnd)
                        .padding(16.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_directions_24dp),
                    contentDescription = stringResource(R.string.action_unfix_from_location),
                )
            }
        }
    }
}
