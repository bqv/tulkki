package uk.xa0.tulkki.ui

import android.content.res.Configuration
import android.os.Bundle
import android.util.Patterns
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.compose.setTulkkiContent

/**
 * The about screen: the version banner, the licence and the library list.
 *
 * <p>**The layout is gone.** `activity_about.xml` held a toolbar and one scrollable `TextView`
 * (`android:autoLink="web"`, monospace, `?textAppearanceBodyMedium`, the `card_padding_regular`
 * margin), and the file is deleted. The bar is the shared chrome
 * ([uk.xa0.tulkki.ui.chrome.TulkkiChrome]) now and the body is [AboutScreen]; `setSupportActionBar`,
 * `configureActionBar`, `setTitle` and `Activities.setStatusAndNavigationBarColors` went with the bar,
 * which owns the title, the up arrow and the system-bar colours itself.
 *
 * <p>**The body was never a `WebView`.** The audit's "WebView-ish" guess is wrong: it is a plain
 * monospaced `TextView` whose addresses `android:autoLink="web"` made tappable, so there is no view
 * to keep and no [androidx.compose.ui.viewinterop.AndroidView] here. [AboutScreen] draws the same
 * string in [MaterialTheme]'s `bodyMedium`, monospaced, and makes the same addresses
 * [LinkAnnotation.Url]s. The one liberty is the resource's own `<b>Tulkki</b>`: the bold is a
 * platform `getText()` span, so the Compose text draws the word plainly.
 */
class AboutActivity : ActionBarActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The chrome draws its bar behind the system bars and hands the content the space they
        // leave, so the window must not inset itself for them first - on every API level, which is
        // what enableEdgeToEdge settles. See TulkkiChrome for the whole pattern.
        enableEdgeToEdge()

        setTulkkiContent(darkTheme = darkTheme()) {
            TulkkiChrome(
                // `setTitle` went with the action bar; the chrome draws this instead.
                title = stringResource(R.string.title_activity_about_x, BuildConfig.APP_NAME),
                // The arrow `configureActionBar(supportActionBar)` switched on, which ran `finish()`.
                onUp = { finish() },
            ) {
                AboutScreen(text = stringResource(R.string.pref_about_message))
            }
        }
    }

    /**
     * Whether the Compose tree is the dark one. The stored preference is what decides it - "a stored
     * preference always wins over the system" - and it reaches the resources through
     * `AppCompatDelegate.setDefaultNightMode`, applied at start-up and in `BaseActivity`, so the
     * Activity's own configuration is the answer and the screen consults no setting.
     */
    private fun darkTheme(): Boolean {
        val mode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }
}

/**
 * The about screen's body: one scrollable, monospaced paragraph with its addresses tappable.
 *
 * <p>`text` is the caller's, already resolved, so this composable names no string of its own - the
 * same split the chrome makes. It is the deleted `TextView`'s four attributes and nothing else:
 * `verticalScroll` is the `ScrollView`, [MaterialTheme.typography] `bodyMedium` is the
 * `?textAppearanceBodyMedium`, [FontFamily.Monospace] is `android:fontFamily="monospace"` (and the
 * `android:typeface` beside it), and the 16 dp padding is `card_padding_regular`.
 */
@Composable
fun AboutScreen(text: String, modifier: Modifier = Modifier) {
    val linkColor = MaterialTheme.colorScheme.primary
    val body = remember(text, linkColor) { webLinks(text, linkColor) }
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            text = body,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * The string with every web address in it a [LinkAnnotation.Url] - which is what
 * `android:autoLink="web"` (`Linkify.WEB_URLS`, which reads [Patterns.WEB_URL]) did to the view.
 * The links take the theme's link colour, the one the view's own `textColorLink` resolved to.
 *
 * <p>The match is trimmed of the sentence punctuation that follows it, so `(https://…/x).` links the
 * address and leaves the brackets and the full stop as text, exactly as `Linkify`'s own trailing
 * trim does; the whole matched tail is then appended as the link's text.
 */
private fun webLinks(text: String, linkColor: Color): AnnotatedString {
    val styles = TextLinkStyles(SpanStyle(color = linkColor))
    val matcher = Patterns.WEB_URL.matcher(text)
    return buildAnnotatedString {
        var last = 0
        while (matcher.find()) {
            val start = matcher.start()
            val url = matcher.group().trimEnd(')', '.', ',', ';')
            append(text, last, start)
            withLink(LinkAnnotation.Url(url, styles)) { append(url) }
            last = start + url.length
        }
        append(text, last, text.length)
    }
}
