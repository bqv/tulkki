package uk.xa0.tulkki.ui.compose

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The Compose host, and the whole of ui-1's "with no screen" promise.
 *
 * <p>docs/MIGRATION.md, "Compose: what the survey changed" asks for *"a `ComposeView` host (every
 * Activity already extends `AppCompatActivity`, so `setContent` works without a base-class swap)"*.
 * `ComposeView` is `final` in the pinned Compose UI, so the host is not a subclass but the two
 * extension points every route needs, and neither waits for a screen to exist:
 *
 *  * [setTulkkiContent] on a `ComposeView` - the view route.  A migrated XML screen declares
 *    `<androidx.compose.ui.platform.ComposeView android:id="…" />` where its old view group was, and
 *    its Activity calls this on the inflated view.
 *  * [setTulkkiContent] on a `ComponentActivity` - the Activity route.  Every Tulkki Activity is an
 *    `AppCompatActivity`, so it is a `ComponentActivity` already and a migrated screen is one call
 *    with no base class changed.
 *
 * <p>Both compose [TulkkiTheme], so a screen cannot draw outside it, and both set
 * `DisposeOnViewTreeLifecycleDestroyed` rather than Compose's own default
 * (`DisposeOnDetachedFromWindowOrReleasedFromPool`): a ComposeView inside an XML screen is detached
 * and re-attached on rotation and on a fragment's view recreation, and the default would dispose the
 * composition and lose scroll position and `rememberSaveable` state with it.
 */
fun ComposeView.setTulkkiContent(
    darkTheme: Boolean = true,
    dynamicColors: Boolean = false,
    content: @Composable () -> Unit,
) {
    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    setContent { TulkkiTheme(darkTheme = darkTheme, dynamicColors = dynamicColors) { content() } }
}

/** The same host, built in code rather than declared in a layout. */
fun tulkkiComposeView(
    context: Context,
    darkTheme: Boolean = true,
    dynamicColors: Boolean = false,
    content: @Composable () -> Unit,
): ComposeView = ComposeView(context).apply { setTulkkiContent(darkTheme, dynamicColors, content) }

/** The Activity route: `setContent`, themed, on an Activity that is already a `ComponentActivity`. */
fun ComponentActivity.setTulkkiContent(
    darkTheme: Boolean = true,
    dynamicColors: Boolean = false,
    content: @Composable () -> Unit,
) {
    setContent { TulkkiTheme(darkTheme = darkTheme, dynamicColors = dynamicColors) { content() } }
}
