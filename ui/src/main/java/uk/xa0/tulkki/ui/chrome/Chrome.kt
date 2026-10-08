package uk.xa0.tulkki.ui.chrome

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import uk.xa0.tulkki.ui.R

/**
 * Tulkki's Compose chrome: one `Scaffold` with a Material 3 `TopAppBar`, for every screen whose
 * layout has been deleted. The tree had no `Scaffold` and no `TopAppBar` before this file, so every
 * conversion from here on hangs its screen from this rather than building a second one like it.
 *
 * <p>**The pattern, for the lane that converts the next screen.** A shell conversion is
 * [TulkkiChrome] around the screen that already exists, plus the pieces of the old shell it
 * replaces:
 *
 * ```kotlin
 * override fun onCreate(savedInstanceState: Bundle?) {
 *     super.onCreate(savedInstanceState)
 *     // The chrome owns the system bars now, so the window must not inset itself for them;
 *     // `enableEdgeToEdge()` is what makes the chrome's own window insets mean anything on
 *     // every API level.
 *     enableEdgeToEdge()
 *     setTulkkiContent(darkTheme = isDark()) {
 *         TulkkiChrome(
 *             title = stringResource(R.string.some_title),   // `setTitle` goes with the toolbar
 *             onUp = { finish() },                           // omit when the XML bar drew no arrow
 *             menu = listOf(ChromeMenuItem(stringResource(R.string.some_action)) { act() }),
 *         ) {
 *             TheScreen(state = session.state)               // the same content, unchanged
 *         }
 *     }
 * }
 * ```
 *
 * An item the old XML menu drew with a check mark passes `checked = <state>` (see
 * [ChromeMenuItem]): the chrome draws the tree's `R.drawable.ic_check_24dp` in the item's trailing
 * slot, exactly as the XML item's `android:checkable` drew its own, and the label stays the
 * caller's.
 *
 *  1. Delete the `setContentView`/`DataBindingUtil.setContentView` call, the layout file, and the
 *     menu file whose still-reachable items the `menu` list now carries.
 *  2. Delete `setSupportActionBar`, `configureActionBar`, `setTitle` and
 *     `Activities.setStatusAndNavigationBarColors`: the action bar, the title and the system-bar
 *     colours were the XML toolbar's own, and they are this file's now.
 *  3. A `*Host.show(composeView, …)` door exists only while a layout is there to hang the
 *     `ComposeView` on. A Kotlin host calls its screen's composable directly, inside the chrome,
 *     exactly as the lambda above does.
 *  4. Keep the screen. This file draws chrome and has no opinion about what is inside it; the
 *     `actions` slot is there for a bar that needs an item the overflow would hide.
 *
 * <p>The `@ExperimentalMaterial3Api` opt-in the bar needs lives on [TulkkiChrome] itself, so a
 * screen that calls the chrome needs none of its own.
 *
 * <p>**Why it looks the way it does.** The owner's brief was "take liberties but try to stick
 * broadly to the same look", so every default here is the theme attribute the XML toolbar already
 * read and no colour, icon or size is invented: the container and title colours are the
 * `colorScheme.surface`/`onSurface` that `?attr/colorSurface`/`?attr/colorOnSurface` resolve to
 * under `Theme.Tulkki`; the title is `typography.titleLarge`, the style the theme's
 * `textAppearanceTitleLarge` carried, so weight and size are unchanged (the theme's Noto Sans is
 * not carried into Compose - no other Compose screen here does that either, and the chrome names no
 * font); the arrow is `R.drawable.ic_arrow_back_24dp`, which is the theme's own `homeAsUpIndicator`,
 * in the same leading position; the overflow is `R.drawable.ic_more_horiz_24dp` in the toolbar's
 * trailing position. The one deliberate difference is the small top app bar's own 64 dp height
 * against the XML toolbars' `?attr/actionBarSize` (56 dp).
 *
 * <p>The window insets are the chrome's: the `TopAppBar` draws its surface behind the status bar
 * and pads its own content down to it, while the `Scaffold` hands the content the rest of the
 * bars' space, so a screen inside it needs to know nothing about either.
 *
 * @param title the screen's title, already resolved by the caller (`stringResource`), so this file
 *     names no string of its own.
 * @param onUp the up/back affordance, or `null` for a screen that has none. The system back button
 *     is not this slot's business - the framework already owns it.
 * @param menu the items the screen's XML menu carried, in the overflow; an empty list draws no
 *     overflow button.
 * @param actions always-visible trailing actions, drawn before the overflow button. This is the
 *     `TopAppBar`'s own `RowScope`, so an action is an `IconButton` like the overflow's.
 * @param content the screen itself, drawn inside the space the system bars leave.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TulkkiChrome(
    title: String,
    onUp: (() -> Unit)? = null,
    menu: List<ChromeMenuItem> = emptyList(),
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { UpButton(onUp) },
                actions = {
                    actions()
                    if (menu.isNotEmpty()) {
                        Overflow(menu)
                    }
                },
            )
        },
    ) { insets ->
        Box(modifier = Modifier.fillMaxSize().padding(insets)) { content() }
    }
}

/**
 * One entry in the chrome's overflow menu.
 *
 * @param label the item's text, resolved by the caller so the chrome holds no string resources of
 *     its own.
 * @param checked whether the item is drawn with the tree's `R.drawable.ic_check_24dp` in the menu
 *     item's trailing slot, as the XML menu's `android:checkable` item drew its own when checked.
 *     Off by default, so an ordinary item passes nothing.
 * @param onSelected what the item does. The menu is closed before this runs, exactly as the
 *     framework's own menu does.
 */
data class ChromeMenuItem(
    val label: String,
    val checked: Boolean = false,
    val onSelected: () -> Unit,
)

/** The up/back affordance, or nothing at all: a screen without one gets no leading icon box. */
@Composable
private fun UpButton(onUp: (() -> Unit)?) {
    if (onUp == null) {
        return
    }
    IconButton(onClick = onUp) {
        Icon(
            painter = painterResource(R.drawable.ic_arrow_back_24dp),
            contentDescription = stringResource(R.string.back),
        )
    }
}

/** The trailing overflow: an icon that opens the items, and no icon when there are none. */
@Composable
private fun Overflow(menu: List<ChromeMenuItem>) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(
            painter = painterResource(R.drawable.ic_more_horiz_24dp),
            contentDescription = stringResource(R.string.more_options),
        )
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        for (item in menu) {
            DropdownMenuItem(
                text = { Text(item.label) },
                trailingIcon = if (item.checked) {
                    {
                        Icon(
                            painter = painterResource(R.drawable.ic_check_24dp),
                            contentDescription = null,
                        )
                    }
                } else {
                    null
                },
                onClick = {
                    open = false
                    item.onSelected()
                },
            )
        }
    }
}
