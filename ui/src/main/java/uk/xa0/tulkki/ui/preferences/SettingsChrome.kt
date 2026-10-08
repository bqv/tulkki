package uk.xa0.tulkki.ui.preferences

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import uk.xa0.tulkki.ui.R

/**
 * Settings' own bar: the `MaterialToolbar` `activity_settings.xml` carried, as the same Material 3
 * `TopAppBar` the Compose chrome draws.
 *
 * <p>**Why this is here and not in `ui/.../chrome/`.** The chrome's [uk.xa0.tulkki.ui.chrome.TulkkiChrome]
 * is one `Scaffold` whose content is the screen, and settings cannot be that screen: its screens stay
 * fragments, because `TranslationFailuresFragment` is one and because `SettingsActivity` addresses
 * [uk.xa0.tulkki.ui.TulkkiSettingsFragment] by class name. A fragment's view has to be created into a
 * container that is already attached, so the container is a `View` and the bar is a sibling of it -
 * a shape a `Scaffold` cannot express. The chrome directory is frozen for this lane, so the bar is
 * drawn here from the same pieces: the title is `typography.titleLarge` (the theme's
 * `textAppearanceTitleLarge`), the arrow is the theme's own `homeAsUpIndicator`,
 * `R.drawable.ic_arrow_back_24dp`, and the content description is the same `R.string.back`.
 *
 * <p>[SettingsTopBar] is what `SettingsActivity` draws above the fragment container. [SettingsChrome]
 * is the same bar over a screen, for a screenshot cell and nothing else: no Activity renders through
 * it, and the two share the one bar so a picture and the screen cannot disagree.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTopBar(title: CharSequence?, onUp: () -> Unit) {
    TopAppBar(
        title = {
            Text(
                text = title?.toString().orEmpty(),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            IconButton(onClick = onUp) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back_24dp),
                    contentDescription = stringResource(R.string.back),
                )
            }
        },
    )
}

/** The bar over a screen, for a fixture that has no Activity to draw the two halves of the shell. */
@Composable
fun SettingsChrome(title: CharSequence?, onUp: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxWidth()) { SettingsTopBar(title, onUp) }
            Box(modifier = Modifier.weight(1f)) { content() }
        }
    }
}
