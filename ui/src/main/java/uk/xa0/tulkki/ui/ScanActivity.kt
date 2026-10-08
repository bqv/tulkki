package uk.xa0.tulkki.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.Vibrator
import android.util.Log
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.Result
import com.google.zxing.ResultPointCallback
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.EnumMap
import uk.xa0.tulkki.ui.chrome.ChromeMenuItem
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import uk.xa0.tulkki.ui.service.CameraManager
import uk.xa0.tulkki.ui.widget.ScannerView
import uk.xa0.tulkki.xmpp.Config

/**
 * The QR-code scanner: a live camera preview with the scanner's mask over it.
 *
 * <p>**The layout is gone.** `activity_scan.xml` held a toolbar, a `TextureView` preview and the
 * `ScannerView` mask, and the file is deleted; the bar is the shared chrome
 * ([uk.xa0.tulkki.ui.chrome.TulkkiChrome]) now and `setSupportActionBar`/`configureActionBar`/
 * `Activities.setStatusAndNavigationBarColors` went with it, as did `menu/scan_activity.xml` - its
 * one always-on "Show QR Code" item is the chrome's overflow, wired to the same
 * `XmppActivity.showQrCode()` the `onOptionsItemSelected` path called. The hardcoded
 * `actionBar.setTitle("Scan Contact QR Code")` becomes `R.string.scan_contact_qr_code`, the same
 * words in the one new string this screen needed.
 *
 * <p>**Both surfaces stay platform views.** The camera can only render into a `TextureView`, and
 * `ScannerView` is a custom `View` with its own `onDraw`; [ScanScreen] hosts the two through
 * [AndroidView], created here rather than inflated, exactly as they were. They are created in
 * `onCreate` before the composition so the lifecycle below (`onResume`'s `maybeOpenCamera`,
 * `onDestroy`'s listener detach) sees them whether or not the composition has run yet.
 *
 * <p>@author Andreas Schildbach
 */
@Suppress("DEPRECATION")
class ScanActivity :
    XmppActivity(),
    TextureView.SurfaceTextureListener,
    ActivityCompat.OnRequestPermissionsResultCallback {

    private val cameraManager = CameraManager()
    private lateinit var scannerView: ScannerView
    private lateinit var previewView: TextureView

    @Volatile
    private var surfaceCreated = false

    private lateinit var vibrator: Vibrator
    private lateinit var cameraThread: HandlerThread

    @Volatile
    private lateinit var cameraHandler: Handler

    private val closeRunnable: Runnable = object : Runnable {
        override fun run() {
            cameraHandler.removeCallbacksAndMessages(null)
            cameraManager.close()
        }
    }

    private val fetchAndDecodeRunnable: Runnable = object : Runnable {
        private val reader = QRCodeReader()
        private val hints: MutableMap<DecodeHintType, Any> =
            EnumMap(DecodeHintType::class.java)

        override fun run() {
            cameraManager.requestPreviewFrame { data, _ -> decode(data) }
        }

        private fun decode(data: ByteArray) {
            val source = cameraManager.buildLuminanceSource(data)
            val bitmap = BinaryBitmap(HybridBinarizer(source))

            try {
                hints[DecodeHintType.NEED_RESULT_POINT_CALLBACK] =
                    ResultPointCallback { dot -> runOnUiThread { scannerView.addDot(dot) } }
                val scanResult = reader.decode(bitmap, hints)

                runOnUiThread { handleResult(scanResult) }
            } catch (x: ReaderException) {
                // retry
                cameraHandler.post(fetchAndDecodeRunnable)
            } finally {
                reader.reset()
            }
        }
    }

    private val openRunnable: Runnable = object : Runnable {
        override fun run() {
            try {
                val camera = cameraManager.open(
                    previewView,
                    displayRotation(),
                    !DISABLE_CONTINUOUS_AUTOFOCUS,
                )

                val framingRect = cameraManager.getFrame() ?: throw NullPointerException()
                val framingRectInPreview = RectF(cameraManager.getFramePreview() ?: throw NullPointerException())
                framingRectInPreview.offsetTo(0f, 0f)
                val cameraFlip =
                    cameraManager.getFacing() == Camera.CameraInfo.CAMERA_FACING_FRONT
                val cameraRotation = cameraManager.getOrientation()

                runOnUiThread {
                    scannerView.setFraming(
                        framingRect,
                        framingRectInPreview,
                        displayRotation(),
                        cameraRotation,
                        cameraFlip,
                    )
                }

                val focusMode = camera.parameters.focusMode
                val nonContinuousAutoFocus =
                    Camera.Parameters.FOCUS_MODE_AUTO == focusMode ||
                        Camera.Parameters.FOCUS_MODE_MACRO == focusMode

                if (nonContinuousAutoFocus) {
                    cameraHandler.post(AutoFocusRunnable(camera))
                }

                cameraHandler.post(fetchAndDecodeRunnable)
            } catch (x: Exception) {
                Log.d(Config.LOGTAG, "problem opening camera", x)
            }
        }

        private fun displayRotation(): Int {
            val rotation = windowManager.defaultDisplay.rotation
            if (rotation == Surface.ROTATION_0) {
                return 0
            } else if (rotation == Surface.ROTATION_90) {
                return 90
            } else if (rotation == Surface.ROTATION_180) {
                return 180
            } else if (rotation == Surface.ROTATION_270) {
                return 270
            } else {
                throw IllegalStateException("rotation: $rotation")
            }
        }
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        // The two surfaces the deleted layout inflated, built here. `ScannerView`'s attributes were
        // the layout's and carried nothing, so the null is the XML's own empty `AttributeSet`.
        scannerView = ScannerView(this, null)
        previewView = TextureView(this).apply {
            keepScreenOn = true
            setSurfaceTextureListener(this@ScanActivity)
        }

        // The chrome draws its bar behind the system bars and hands the content the space they
        // leave, so the window must not inset itself for them first - on every API level, which is
        // what enableEdgeToEdge settles. See TulkkiChrome for the whole pattern.
        enableEdgeToEdge()

        setTulkkiContent(darkTheme = isDark()) {
            TulkkiChrome(
                title = stringResource(R.string.scan_contact_qr_code),
                onUp = { finish() },
                menu = listOf(ChromeMenuItem(stringResource(R.string.show_qr_code)) { showQrCode() }),
            ) {
                ScanScreen(
                    preview = {
                        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                    },
                    mask = {
                        AndroidView(factory = { scannerView }, modifier = Modifier.fillMaxSize())
                    },
                )
            }
        }

        cameraThread = HandlerThread("cameraThread", Process.THREAD_PRIORITY_BACKGROUND)
        cameraThread.start()
        cameraHandler = Handler(cameraThread.getLooper())
    }

    protected override fun onResume() {
        super.onResume()
        maybeOpenCamera()
    }

    public override fun onPause() {
        cameraHandler.post(closeRunnable)

        super.onPause()
    }

    protected override fun onDestroy() {
        // cancel background thread
        cameraHandler.removeCallbacksAndMessages(null)
        cameraThread.quit()

        previewView.setSurfaceTextureListener(null)

        super.onDestroy()
    }

    public override fun onBackendConnected() {}

    public override fun refreshUiReal() {}

    private fun maybeOpenCamera() {
        if (surfaceCreated &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            cameraHandler.post(openRunnable)
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        surfaceCreated = true
        maybeOpenCamera()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        surfaceCreated = false
        return true
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    public override fun onAttachedToWindow() {
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
    }

    public override fun onBackPressed() {
        scannerView.setVisibility(View.GONE)
        setResult(RESULT_CANCELED)
        postFinish()
    }

    public override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_FOCUS, KeyEvent.KEYCODE_CAMERA -> {
                // don't launch camera app
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP -> {
                cameraHandler.post {
                    cameraManager.setTorch(keyCode == KeyEvent.KEYCODE_VOLUME_UP)
                }
                return true
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    fun handleResult(scanResult: Result) {
        vibrator.vibrate(VIBRATE_DURATION)

        scannerView.setIsResult(true)

        val result = Intent()
        result.putExtra(INTENT_EXTRA_RESULT, scanResult.text)
        setResult(RESULT_OK, result)
        postFinish()
    }

    private fun postFinish() {
        Handler().postDelayed({ finish() }, 50)
    }

    private inner class AutoFocusRunnable(private val camera: Camera) : Runnable {
        private val autoFocusCallback: Camera.AutoFocusCallback =
            object : Camera.AutoFocusCallback {
                override fun onAutoFocus(success: Boolean, camera: Camera) {
                    // schedule again
                    cameraHandler.postDelayed(this@AutoFocusRunnable, AUTO_FOCUS_INTERVAL_MS)
                }
            }

        override fun run() {
            try {
                camera.autoFocus(autoFocusCallback)
            } catch (x: Exception) {
                Log.d(Config.LOGTAG, "problem with auto-focus, will not schedule again", x)
            }
        }
    }

    companion object {
        const val INTENT_EXTRA_RESULT = "result"

        const val REQUEST_SCAN_QR_CODE = 0x0987
        private const val REQUEST_CAMERA_PERMISSIONS_TO_SCAN = 0x6789

        private const val VIBRATE_DURATION = 50L
        private const val AUTO_FOCUS_INTERVAL_MS = 2500L
        private val DISABLE_CONTINUOUS_AUTOFOCUS = Build.MODEL == "GT-I9100" || // Galaxy S2
            Build.MODEL == "SGH-T989" || // Galaxy S2
            Build.MODEL == "SGH-T989D" || // Galaxy S2 X
            Build.MODEL == "SAMSUNG-SGH-I727" || // Galaxy S2 Skyrocket
            Build.MODEL == "GT-I9300" || // Galaxy S3
            Build.MODEL == "GT-N7000" // Galaxy Note

        @JvmStatic
        fun scan(activity: Activity) {
            if (ContextCompat.checkSelfPermission(
                    activity,
                    Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                val intent = Intent(activity, ScanActivity::class.java)
                activity.startActivityForResult(intent, REQUEST_SCAN_QR_CODE)
            } else {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.CAMERA),
                    REQUEST_CAMERA_PERMISSIONS_TO_SCAN,
                )
            }
        }

        @JvmStatic
        fun onRequestPermissionResult(
            activity: Activity,
            requestCode: Int,
            grantResults: IntArray,
        ) {
            if (requestCode != REQUEST_CAMERA_PERMISSIONS_TO_SCAN) {
                return
            }
            if (grantResults.isNotEmpty()) {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    scan(activity)
                } else {
                    Toast.makeText(
                        activity,
                        R.string.qr_code_scanner_needs_access_to_camera,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }
}

/**
 * The scanner's body: the camera preview with the mask over it, where the deleted
 * `activity_scan.xml`'s `RelativeLayout` held the same two children in the same order.
 *
 * <p>The two surfaces are slots because both are Android views (a `TextureView` and the custom
 * `ScannerView`) and neither can be composed; the Activity hands both in, and there is no
 * screenshot cell for this screen for exactly that reason - it is a platform surface from edge to
 * edge, so a cell would pin only the chrome.
 */
@Composable
fun ScanScreen(
    preview: @Composable () -> Unit,
    mask: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        preview()
        mask()
    }
}
