package uk.xa0.tulkki.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.annotation.Nullable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import java.net.URISyntaxException
import uk.xa0.tulkki.translation.TranslationSettings
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import uk.xa0.tulkki.ui.topup.TopUpHost
import uk.xa0.tulkki.ui.topup.TopUpScreen
import uk.xa0.tulkki.ui.topup.TopUpState

/**
 * Tulkki: the DeepSeek platform's own top-up page, in a WebView, so adding credit does not mean
 * leaving the app. It is the screen's <em>host</em>: it reads, and
 * [uk.xa0.tulkki.ui.topup.TopUpScreen] draws.
 *
 * <p>This screen exists because the API key cannot sign in anywhere but the API: it authenticates
 * `api.deepseek.com`, and the top-up page is a web session on `platform.deepseek.com`. So the owner
 * signs in once, here, and the WebView's own cookie jar is what remembers it - which is why nothing
 * in this class pre-fills a field, reads a value out of the page or captures anything. There is no
 * `addJavascriptInterface` and no injected script anywhere in this file: the page is the platform's,
 * and the only thing this app does with it is render it.
 *
 * <p>What it does beyond rendering is at the edges, where a browser would do it too: an address the
 * WebView must not load is handed to whatever app can open it ([TopUpPage] decides which), failed
 * loads are said out loud instead of showing an empty frame, and the system back button walks the
 * page's history first.
 *
 * <p>docs/MIGRATION.md "Design: the Compose UI" §3.3 rewrites this screen, and §7.4 says why the two
 * halves are split: "**Composables stay dumb**: take a `Ui*` state, emit ids. No `ViewModel` that
 * constructs an entity." So this class keeps only the WebView - the one thing a Composable cannot
 * own, because its state, its cookie jar and its dark mode are the platform's - and every drawing
 * call is gone. It composes [TopUpScreen] into the shared chrome and pushes each callback's own
 * transition into [TopUpHost.Session]'s state.
 *
 * <p>**The interpreter's switch is read before the page is built.** §3.3's off state is "removed with
 * the ledger - no interpretation, no spend, no top-up row": the row is already gone from the settings
 * page, and a screen reached anyway opens no payment page at all. With the interpreter off there is no
 * WebView here, no address is loaded, and the screen draws the ledger's own off card.
 *
 * <p>The layout is gone. `activity_topup.xml` kept a `MaterialToolbar` and the `ComposeView` this
 * class inflated by id; both are the shared chrome's now
 * ([uk.xa0.tulkki.ui.chrome.TulkkiChrome] draws the bar and the window insets, and the screen is
 * composed straight into it), and the file was deleted with them.
 */
class TopUpActivity : ActionBarActivity() {

    /**
     * The screen's state, held rather than re-set: this screen contains a live page, and re-setting
     * the content on every progress tick would detach and re-attach a WebView in the middle of a
     * payment.
     */
    private val session = TopUpHost.Session()

    /** The page, or null when the interpreter is off and no payment page is opened at all. */
    @Nullable
    private var webView: WebView? = null

    /** What "Try again" reloads: the last address the main frame started loading. */
    private var requestedUrl: String = TopUpPage.URL

    private val backCallback: OnBackPressedCallback =
        object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Back is the page's back first, so a redirect chain can be walked, and the
                // screen is only left when the history is.
                val view = webView
                if (view != null && view.canGoBack()) {
                    view.goBack()
                    return
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }

    protected override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The chrome draws its bar behind the system bars and hands the content the space they
        // leave, so the window must not inset itself for them first - on every API level, which is
        // what enableEdgeToEdge settles. See TulkkiChrome for the whole pattern.
        enableEdgeToEdge()

        onBackPressedDispatcher.addCallback(this, backCallback)

        val enabled = TranslationSettings.get(applicationContext).interpreter().enabled()
        if (enabled) {
            val view = WebView(this)
            configureWebView(view)
            view.webViewClient = TopUpWebViewClient()
            view.webChromeClient = TopUpChromeClient()
            webView = view
        }

        setTulkkiContent(darkTheme = darkTheme()) {
            TulkkiChrome(
                title = stringResource(R.string.tulkki_top_up_title),
                // The arrow is up-and-out, to the settings screen this was opened from - the parent
                // declared in the manifest - which is the XML action bar's own `finish()`. The
                // system back button is the other path, and it walks the page history first.
                onUp = { finish() },
            ) {
                TopUpScreen(
                    state = session.state,
                    enabled = enabled,
                    page = {
                        val view = webView
                        if (view != null) {
                            AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
                        }
                    },
                    onRetry = { retry() },
                    onOpenSettings = { finish() },
                )
            }
        }

        // A rotation in the middle of paying must not drop the owner back at the start of the flow.
        // The state restored here is the history; the login is in the cookie jar, which is not
        // touched either way.
        if (enabled) {
            val view = webView ?: throw NullPointerException()
            if (savedInstanceState == null || view.restoreState(savedInstanceState) == null) {
                load(TopUpPage.URL)
            }
        }
    }

    /**
     * The browser this screen is, and nothing more permissive than it has to be.
     *
     * <p>What is switched on: script and DOM storage (the page is a single-page app), cookies
     * including third-party ones (a payment step is another origin), and the user agent below. What
     * is switched off is everything that would let a third-party page holding a session reach this
     * app's own data or a mixed-content hole in: no file access, no content access, no bridge, no
     * mixed content. The cookie jar is left alone, so the sign-in survives the visit - nothing here
     * ever clears it, and nothing reads it.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(view: WebView) {
        val settings = view.settings

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

        // The user agent is the WebView's own, with the two tokens that announce "embedded WebView"
        // taken out - see TopUpPage.browserUserAgent. The platform is behind bot protection, and a
        // client that tells the world it is a WebView is the first thing such a check rejects; what
        // is sent is still the truth about this device and this engine, so the page is served the
        // mobile site it was going to serve anyway. If the platform still refuses, the load fails
        // loudly below rather than looking like it worked.
        settings.userAgentString = TopUpPage.browserUserAgent(settings.userAgentString)

        // One window. A request for a second one is not dropped because of that: with
        // multiple-window support off - the default, kept explicit here - Android loads
        // target="_blank" and window.open requests as top-level navigations in this same WebView,
        // which is the only place this screen could put them, and they arrive back in
        // shouldOverrideUrlLoading below, so a payment's popup is still subject to the address
        // policy. onCreateWindow is therefore never called and is deliberately not implemented;
        // turning multiple windows on would mean creating a second WebView that this screen has
        // nowhere to show. The next flag is what keeps a popup the page opens without a user
        // gesture from being refused outright - and with one window it decides nothing more than
        // whether a navigation the page could already make with location.href is attempted.
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = true

        // Zoom is not a permission, and a payment page is mostly small print.
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false

        // Dark, because the app is dark by default and the owner asked for the page to match. There
        // is no third API to reach for: API 33+ has algorithmic darkening (off by default, hence
        // the call - it renders the page's own prefers-color-scheme dark style when the page has
        // one, and only darkens algorithmically when it does not); API 29-32 has the older
        // force-dark switch, set ON so it holds whatever the theme resolves to; API 23-28 has
        // neither and shows the page as the platform served it, because the only lever left there
        // would be injecting CSS into a page that holds a payment session, which this screen must
        // never do.
        //
        // Both calls key off the app's night mode: the WebView reports prefers-color-scheme: dark
        // whenever the theme it was created in has isLightTheme=false, and Tulkki's dark theme is
        // the one selected whenever the default or a stored preference picks it. So the
        // darkening rides on this activity keeping the app theme - give it a light theme and the
        // page goes light with it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            settings.setAlgorithmicDarkeningAllowed(true)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            settings.setForceDark(WebSettings.FORCE_DARK_ON)
        }

        // Cookies, third-party ones included, and never cleared. Nothing in this app reads a cookie
        // value; the jar is the platform session and only the platform gets to use it.
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(view, true)

        // The page paints over this, but it is what shows for the moment before the first paint:
        // the app's own surface, rather than a white flash in a dark screen.
        val surface = TypedValue()
        if (theme.resolveAttribute(android.R.attr.colorBackground, surface, true)) {
            view.setBackgroundColor(surface.data)
        }
    }

    protected override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView?.saveState(outState)
    }

    protected override fun onStop() {
        super.onStop()
        // Write the cookie jar out now rather than whenever the system gets round to it: the login
        // that makes the next visit painless lives there. This persists it; it never clears it.
        CookieManager.getInstance().flush()
    }

    protected override fun onDestroy() {
        val view = webView
        if (view != null) {
            val parent = view.parent as ViewGroup?
            if (parent != null) {
                parent.removeView(view)
            }
            view.destroy()
        }
        super.onDestroy()
    }

    private fun load(url: String) {
        webView?.loadUrl(url)
    }

    /** The panel's own control: the last address the main frame started loading, again. */
    private fun retry() {
        load(requestedUrl)
    }

    /**
     * Hands an address the WebView must not render to whatever on the phone can open it. This is the
     * step that lets a payment finish: Alipay, WeChat Pay and the banks answer to a scheme of their
     * own or to an app link, not to an http page, and a flow that stopped there would die at the
     * last step with no error at all.
     *
     * <p>The address is the page's, and it is passed on as it is: nothing is read out of it, kept,
     * or sent anywhere by this app.
     */
    private fun openInSystem(url: String) {
        val intent: Intent
        try {
            intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        } catch (e: URISyntaxException) {
            sayUnhandled(url)
            return
        }
        // An address from a web page is untrusted input: it may not name a component inside this
        // app or any other, and it is narrowed to what a link a browser would follow is allowed to
        // do.
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            openFallback(intent, url)
        } catch (e: SecurityException) {
            openFallback(intent, url)
        }
    }

    /**
     * Some intent:// addresses carry the page to use when no app answers. That page is the
     * payment's or the platform's, and it stays in this WebView like everything else.
     */
    private fun openFallback(intent: Intent, url: String) {
        val fallback = intent.getStringExtra("browser_fallback_url")
        if (fallback != null &&
            TopUpPage.destinationOf(fallback) == TopUpPage.Destination.WEBVIEW
        ) {
            load(fallback)
            return
        }
        sayUnhandled(url)
    }

    /** Said, not swallowed: the flow stopped here, and the page the owner is looking at did nothing. */
    private fun sayUnhandled(url: String) {
        session.unhandled(url)
    }

    /**
     * Whether the Compose tree is the dark one. The stored preference is what decides it - "a stored
     * preference always wins over the system" - and it reaches the resources through
     * `AppCompatDelegate.setDefaultNightMode`, applied at start-up and in `BaseActivity`,
     * so the Activity's own configuration is the answer and the screen consults no setting.
     */
    private fun darkTheme(): Boolean {
        val mode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    private inner class TopUpWebViewClient : WebViewClient() {

        /**
         * The deprecated overload is the one that carries the decision, and the modern one
         * delegates to it: `shouldOverrideUrlLoading(WebView, WebResourceRequest)` only
         * exists from API 24, so on API 23 - which this app still supports - the framework calls
         * this one, and a screen that implemented only the modern one would leave every payment
         * scheme unrouted on the oldest phones.
         */
        @Suppress("DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean {
            if (TopUpPage.destinationOf(url) == TopUpPage.Destination.WEBVIEW) {
                return false
            }
            openInSystem(url ?: throw NullPointerException())
            // True either way: the WebView must not then try to load an address it cannot render.
            return true
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            val target: Uri? = request.url
            return shouldOverrideUrlLoading(view, target?.toString())
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (url != null) {
                requestedUrl = url
            }
            // The screen's own rule: a start clears the old notice and any failure that was
            // standing, because the page that produced them is being replaced.
            session.started()
        }

        override fun onPageFinished(view: WebView, url: String) {
            // The failure these moved to is left standing if the page failed: a refusal arrives as
            // an HTTP error and then as a finished page, and the second callback must not clear the
            // first - which is the state's own rule, not a second one here.
            session.finished()
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError?,
        ) {
            if (!request.isForMainFrame) {
                return
            }
            val description = error?.description?.toString() ?: ""
            session.failed(
                TopUpState.Reason.Unreachable(request.url.toString(), description),
            )
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            response: WebResourceResponse?,
        ) {
            if (!request.isForMainFrame) {
                return
            }
            // The shape to expect here: the platform is behind bot protection, and it answers a
            // client it does not like with a 403 and a body that is not the page. Only the main
            // frame counts - a refused subresource is the page's own business, not a blank screen.
            session.failed(
                TopUpState.Reason.Http(
                    response?.statusCode ?: 0,
                    request.url.toString(),
                ),
            )
        }
    }

    /**
     * Present for the loading indication, and for the new-window half of the story above: where a
     * request for a second window goes is settled by `setSupportMultipleWindows`, which this
     * screen sets to false because it has exactly one window to give it.
     */
    private inner class TopUpChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            session.progressed(newProgress)
        }
    }
}
