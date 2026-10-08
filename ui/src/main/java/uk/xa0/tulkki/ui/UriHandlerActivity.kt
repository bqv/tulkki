package uk.xa0.tulkki.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.common.base.Strings
import java.io.IOException
import java.util.regex.Pattern
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import uk.xa0.tulkki.app.http.HttpConnectionManager
import uk.xa0.tulkki.data.DatabaseBackend
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import uk.xa0.tulkki.ui.host.UiHost
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.utils.XmppUri

/**
 * The `xmpp:` URI handler: a spinner while the address is resolved, or the reason it could not be.
 *
 * <p>**The layout is gone.** `activity_uri_handler.xml` held a centred indeterminate `ProgressBar`
 * and, under it, the error `TextView` - two ids and the whole screen - and the file is deleted. The
 * layout had no toolbar, so there is no chrome here either: [UriHandlerScreen] draws the same
 * `?colorPrimaryContainer` surface, the same 24 dp padding, the same centred spinner and the same
 * error line the layout did. `Activities.setStatusAndNavigationBarColors` went with the bars, which
 * `enableEdgeToEdge` now owns.
 *
 * <p>**The `progress` surface still exists, as state.** The id the old `DataBinding` held was read
 * from this screen alone - bar one KDoc sentence in `service/AudioPlayer.kt`, which names it while
 * explaining what is left of the audio player - and the reads and writes are [UriHandlerScreenState]
 * now, with the same two rules: a main-action intent shows the spinner only while the link-header
 * call is still open, and an error hides it. Nothing else in the tree reads `R.id.progress`.
 */
class UriHandlerActivity : BaseActivity() {

    /** What the deleted layout drew: the spinner, and the error under it when one stands. */
    private var state by mutableStateOf(UriHandlerScreenState())

    private var call: Call? = null

    protected override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The layout had no toolbar, so this screen has no chrome - just the primary-container
        // surface. See UriHandlerScreen.
        enableEdgeToEdge()

        setTulkkiContent(darkTheme = isDarkTheme()) {
            UriHandlerScreen(state = state)
        }
    }

    /**
     * The stored-night-mode read `XmppActivity.isDark()` makes, repeated because this screen's host
     * is a [BaseActivity] and not an `XmppActivity`.
     */
    private fun isDarkTheme(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    public override fun onStart() {
        super.onStart()
        handleIntent(getIntent())
    }

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    public override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        finish()
    }

    private fun handleUri(uri: Uri): Boolean {
        return handleUri(uri, false)
    }

    private fun handleUri(uri: Uri, scanned: Boolean): Boolean {
        val xmppUri = XmppUri(uri)
        val accounts: List<Jid> = DatabaseBackend.getInstance(this).getAccountJids(false)

        if (UiHost.installed().tokenRegistrySupported() && xmppUri.isValidJid()) {
            val preAuth = xmppUri.getParameter(XmppUri.PARAMETER_PRE_AUTH)
            val jid = xmppUri.getJid() ?: throw NullPointerException()
            if (xmppUri.isAction(XmppUri.ACTION_REGISTER)) {
                if (jid.getLocal() != null && accounts.contains(jid.asBareJid())) {
                    showError(uk.xa0.tulkki.xmpp.R.string.account_already_exists)
                    return false
                }
                val intent =
                    UiHost.installed().tokenRegistrationIntent(this, jid.toString(), preAuth)
                startActivity(intent)
                return true
            }
            if (accounts.isEmpty() &&
                xmppUri.isAction(XmppUri.ACTION_ROSTER) &&
                "y".equals(
                    Strings.nullToEmpty(xmppUri.getParameter(XmppUri.PARAMETER_IBR))
                        .trim { it <= ' ' },
                    ignoreCase = true,
                )
            ) {
                val intent = UiHost.installed().tokenRegistrationIntent(
                    this,
                    jid.getDomain().toString(),
                    preAuth,
                )
                intent.putExtra(StartConversationActivity.EXTRA_INVITE_URI, xmppUri.toString())
                startActivity(intent)
                return true
            }
        } else if (xmppUri.isAction(XmppUri.ACTION_REGISTER)) {
            showError(R.string.account_registrations_are_not_supported)
            return false
        }

        if (accounts.isEmpty()) {
            if (xmppUri.isValidJid()) {
                val intent = UiHost.installed().signUpIntent(this, false)
                intent.putExtra(StartConversationActivity.EXTRA_INVITE_URI, xmppUri.toString())
                startActivity(intent)
                return true
            } else {
                showError(R.string.invalid_jid)
                return false
            }
        }

        val intent: Intent
        if (xmppUri.isAction(XmppUri.ACTION_MESSAGE) || xmppUri.isAction("command")) {
            val jid = xmppUri.getJid()
            val body = xmppUri.getBody()

            if (jid != null) {
                val clazz = findShareViaAccountClass()
                if (clazz != null) {
                    intent = Intent(this, clazz)
                    intent.data = uri
                } else {
                    intent = Intent(this, StartConversationActivity::class.java)
                    intent.action = Intent.ACTION_VIEW
                    intent.data = uri
                    intent.putExtra("account", accounts[0].toString())
                }
            } else {
                intent = Intent(this, ShareWithActivity::class.java)
                intent.action = Intent.ACTION_SEND
                intent.type = "text/plain"
                intent.putExtra(Intent.EXTRA_TEXT, body)
            }
        } else if (accounts.contains(xmppUri.getJid())) {
            intent = Intent(applicationContext, EditAccountActivity::class.java)
            intent.action = Intent.ACTION_VIEW
            intent.putExtra(
                "jid",
                (xmppUri.getJid() ?: throw NullPointerException()).asBareJid().toString(),
            )
            intent.data = uri
            intent.putExtra("scanned", scanned)
        } else if (xmppUri.isValidJid()) {
            intent = Intent(applicationContext, StartConversationActivity::class.java)
            intent.action = Intent.ACTION_VIEW
            intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            intent.putExtra("scanned", scanned)
            intent.data = uri
        } else {
            showError(R.string.invalid_jid)
            return false
        }
        startActivity(intent)
        return true
    }

    private fun checkForLinkHeader(url: HttpUrl) {
        Log.d(Config.LOGTAG, "checking for link header on $url")
        val call =
            HttpConnectionManager.okHttpClient(this)
                .newCall(Request.Builder().url(url).head().build())
        this.call = call
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.d(Config.LOGTAG, "unable to check HTTP url", e)
                    showErrorOnUiThread(R.string.no_xmpp_adddress_found)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (response.isSuccessful) {
                        val linkHeader = response.header("Link")
                        if (linkHeader != null && processLinkHeader(linkHeader)) {
                            return
                        }
                    }
                    showErrorOnUiThread(R.string.no_xmpp_adddress_found)
                }
            },
        )
    }

    private fun processLinkHeader(header: String): Boolean {
        val matcher = LINK_HEADER_PATTERN.matcher(header)
        if (matcher.find()) {
            val group = matcher.group()
            val link = group.substring(1, group.length - 1)
            if (handleUri(Uri.parse(link))) {
                finish()
                return true
            }
        }
        return false
    }

    private fun showError(@StringRes error: Int) {
        state = state.copy(showProgress = false, errorRes = error)
    }

    private fun showErrorOnUiThread(@StringRes error: Int) {
        runOnUiThread { showError(error) }
    }

    private fun handleIntent(data: Intent?) {
        val action = data?.getAction() ?: return
        when (action) {
            Intent.ACTION_MAIN -> {
                val current = call
                state =
                    state.copy(
                        showProgress = current != null && !current.isCanceled(),
                    )
            }
            Intent.ACTION_VIEW, Intent.ACTION_SENDTO -> {
                if (handleUri(
                        (data ?: throw NullPointerException()).getData()
                            ?: throw NullPointerException(),
                    )
                ) {
                    finish()
                }
            }
            ACTION_SCAN_QR_CODE -> {
                Log.d(Config.LOGTAG, "scan. allow=" + allowProvisioning())
                setIntent(createMainIntent())
                startActivityForResult(
                    Intent(this, ScanActivity::class.java),
                    REQUEST_SCAN_QR_CODE,
                )
            }
        }
    }

    private fun createMainIntent(): Intent {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.putExtra(EXTRA_ALLOW_PROVISIONING, allowProvisioning())
        return intent
    }

    private fun allowProvisioning(): Boolean {
        return true
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        super.onActivityResult(requestCode, resultCode, intent)
        if (requestCode == REQUEST_SCAN_QR_CODE && resultCode == RESULT_OK) {
            val allowProvisioning = allowProvisioning()
            val rawResult = (intent ?: throw NullPointerException())
                .getStringExtra(ScanActivity.INTENT_EXTRA_RESULT)
            if (Strings.isNullOrEmpty(rawResult)) {
                finish()
                return
            }
            val result = rawResult ?: throw NullPointerException()
            if (result.startsWith("BEGIN:VCARD\n")) {
                val matcher = V_CARD_XMPP_PATTERN.matcher(result)
                if (matcher.find()) {
                    if (handleUri(Uri.parse(matcher.group(2)), true)) {
                        finish()
                    }
                } else {
                    showError(R.string.no_xmpp_adddress_found)
                }
                return
            } else if (looksLikeJsonObject(result) && allowProvisioning) {
                UiHost.installed().provision(this, result)
                finish()
                return
            }
            val uri = Uri.parse(result.trim { it <= ' ' })
            if (allowProvisioning &&
                "https".equals(uri.scheme, ignoreCase = true) &&
                !XmppUri.INVITE_DOMAIN.equals(uri.host, ignoreCase = true)
            ) {
                val httpUrl = uri.toString().toHttpUrlOrNull()
                if (httpUrl != null) {
                    checkForLinkHeader(httpUrl)
                } else {
                    finish()
                }
            } else if (handleUri(uri, true)) {
                finish()
            } else {
                setIntent(Intent(Intent.ACTION_VIEW, uri))
            }
        } else {
            finish()
        }
    }

    private fun looksLikeJsonObject(input: String): Boolean {
        val trimmed = Strings.nullToEmpty(input).trim { it <= ' ' }
        return trimmed[0] == '{' && trimmed[trimmed.length - 1] == '}'
    }

    protected fun hasStoragePermission(requestCode: Int): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        ) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    requestCode,
                )
                false
            } else {
                true
            }
        } else {
            true
        }
    }

    companion object {
        const val ACTION_SCAN_QR_CODE = "scan_qr_code"
        private const val EXTRA_ALLOW_PROVISIONING = "extra_allow_provisioning"
        private const val REQUEST_SCAN_QR_CODE = 0x1234
        private const val REQUEST_CAMERA_PERMISSIONS_TO_SCAN = 0x6789
        private const val REQUEST_CAMERA_PERMISSIONS_TO_SCAN_AND_PROVISION = 0x6790
        private val V_CARD_XMPP_PATTERN = Pattern.compile("\nIMPP([^:]*):(xmpp:.+)\n")
        private val LINK_HEADER_PATTERN = Pattern.compile("<(.*?)>")

        @JvmStatic
        fun scan(activity: Activity) {
            scan(activity, false)
        }

        @JvmStatic
        fun scan(activity: Activity, provisioning: Boolean) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                val intent = Intent(activity, UriHandlerActivity::class.java)
                intent.action = UriHandlerActivity.ACTION_SCAN_QR_CODE
                if (provisioning) {
                    intent.putExtra(EXTRA_ALLOW_PROVISIONING, true)
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                activity.startActivity(intent)
            } else {
                activity.requestPermissions(
                    arrayOf(Manifest.permission.CAMERA),
                    if (provisioning) {
                        REQUEST_CAMERA_PERMISSIONS_TO_SCAN_AND_PROVISION
                    } else {
                        REQUEST_CAMERA_PERMISSIONS_TO_SCAN
                    },
                )
            }
        }

        @JvmStatic
        fun onRequestPermissionResult(
            activity: Activity,
            requestCode: Int,
            grantResults: IntArray,
        ) {
            if (requestCode != REQUEST_CAMERA_PERMISSIONS_TO_SCAN &&
                requestCode != REQUEST_CAMERA_PERMISSIONS_TO_SCAN_AND_PROVISION
            ) {
                return
            }
            if (grantResults.isNotEmpty()) {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    if (requestCode == REQUEST_CAMERA_PERMISSIONS_TO_SCAN_AND_PROVISION) {
                        scan(activity, true)
                    } else {
                        scan(activity)
                    }
                } else {
                    Toast.makeText(
                        activity,
                        R.string.qr_code_scanner_needs_access_to_camera,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }

        private fun findShareViaAccountClass(): Class<*>? {
            return try {
                Class.forName("uk.xa0.tulkki.ui.ShareViaAccountActivity")
            } catch (e: ClassNotFoundException) {
                null
            }
        }
    }
}

/**
 * What [UriHandlerScreen] draws: whether the spinner is on screen and, under it, which error stands.
 *
 * <p>The two values are the two views the deleted layout held. `showProgress` starts true, as the
 * `ProgressBar` started `VISIBLE`; a null [errorRes] is the `TextView`'s `invisible` state with no
 * text, and the old `setText` is the resource id here.
 */
data class UriHandlerScreenState(
    val showProgress: Boolean = true,
    @StringRes val errorRes: Int? = null,
)

/**
 * The URI handler's surface: the spinner and the error line, centred on the primary container.
 *
 * <p>Every value is the deleted layout's own: the `?colorPrimaryContainer` background, the 24 dp
 * padding, the spinner centred in the parent, and the error 16 dp under it, horizontally centred,
 * in `?colorOnPrimaryContainer` at `?textAppearanceBodyMedium`. The spinner's slot is kept while it
 * is hidden, because `View.INVISIBLE` kept the `ProgressBar`'s space and the error sat at the same
 * height whether or not the spin was still running.
 */
@Composable
fun UriHandlerScreen(state: UriHandlerScreenState, modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (state.showProgress) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            val error = state.errorRes
            if (error != null) {
                Text(
                    text = stringResource(error),
                    modifier = Modifier.padding(top = 16.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
