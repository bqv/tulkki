package uk.xa0.tulkki.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.flow.drop
import uk.xa0.tulkki.ui.R

/**
 * The media viewer's framing: one page per attachment, fullscreen on black, with the row of actions the
 * deleted speed dial held.
 *
 * <p>**No chrome at all, on purpose.** The deleted `activity_media_viewer.xml` had no toolbar - it was
 * a `RelativeLayout` holding a `ViewPager2`, a `SpeedDialOverlayLayout` and a `SpeedDialView` - and
 * `MediaViewerActivity` hid the action bar in `onCreate` besides; the Activity's theme is
 * `Theme.Tulkki.FullScreen` (`windowNoTitle`, `windowActionBar=false`, a black window background).
 * A `TopAppBar` would be a bar this screen never had, and [uk.xa0.tulkki.ui.chrome.TulkkiChrome] has
 * no immersive mode, so this screen is `setTulkkiContent` without the chrome - the arrangement the
 * image editor and the top-up screen already use for their own fullscreen surfaces.
 *
 * <p>**Each page's surface is the caller's.** The `ViewPager2` is [HorizontalPager], with
 * `reverseLayout` standing in for the old `setLayoutDirection(View.LAYOUT_DIRECTION_RTL)`, and the
 * page inside it is the `page` slot: the `PhotoView` and `PlayerView` the deleted
 * `item_media_viewer.xml` held are Android views, so `MediaViewerActivity` hands them in and this file
 * knows nothing about media3 or Glide - the same split `TopUpScreen`'s `page` slot and the image
 * editor's three surface slots make, and for the same reason: a screenshot cell can then render the
 * whole screen around a surface layoutlib cannot draw.
 *
 * <p>**The SpeedDial is the [ActionStack]**: the same four items in the same order - Delete only for a
 * file this process may delete, then Open with, Share and Save to Downloads - the same `colorAccent`
 * background with white marks, the same `black87` scrim behind them while they are open, and the same
 * menu/cancel main button.
 *
 * @param pageCount how many attachments the pager has; the Activity's list size.
 * @param initialPage the page to open on, as `setCurrentItem(position, false)` chose it.
 * @param actionsVisible the old `showFAB`/`hideFAB` state; the dial's own openness is this screen's.
 * @param canDelete whether the current attachment's original file may be deleted.
 * @param onPageChange the old `onPageSelected`: this index became the current page.
 * @param onToggleActions a photo tap, which the old listener answered with `toggleFAB`.
 * @param onDelete/onOpen/onShare/onSave the four dial items, which the Activity still performs.
 * @param reloadToken bumped whenever a new attachment list arrives, because a second intent can hand
 *     over a list of exactly the same size and length is then no evidence anything changed.
 * @param initiallyExpanded the dial's starting openness, for a screenshot cell; it is closed in the
 *     running app until the main button is tapped.
 * @param page one page's surface, handed in by the Activity because it is an Android view.
 */
@Composable
fun MediaViewerScreen(
    pageCount: Int,
    initialPage: Int,
    actionsVisible: Boolean,
    canDelete: Boolean,
    onPageChange: (Int) -> Unit,
    onToggleActions: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    reloadToken: Int = 0,
    initiallyExpanded: Boolean = false,
    page: @Composable (index: Int, isCurrent: Boolean) -> Unit,
) {
    val pagerState =
        rememberPagerState(
            initialPage = initialPage.coerceAtLeast(0),
            pageCount = { pageCount },
        )
    var expanded by remember { mutableStateOf(initiallyExpanded) }

    // Hiding the dial puts it back to its menu button, which is what `hide()` then `show()` showed.
    LaunchedEffect(actionsVisible) {
        if (!actionsVisible) {
            expanded = false
        }
    }

    // The list arriving is the old `onMediaLoaded` + `notifyDataSetChanged` + `setCurrentItem`: the
    // first page is the current one, and the requested page is scrolled to without animation.
    LaunchedEffect(pageCount, initialPage, reloadToken) {
        if (pageCount == 0) return@LaunchedEffect
        if (pagerState.currentPage != initialPage) {
            pagerState.scrollToPage(initialPage)
        }
        onPageChange(pagerState.currentPage)
    }

    // A swipe afterwards. The first emission is the initial page and is already reported above, so a
    // page is selected exactly once.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.drop(1).collect { onPageChange(it) }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            // The deleted ViewPager2's `LAYOUT_DIRECTION_RTL`, which put page 0 on the right.
            reverseLayout = true,
        ) { index ->
            page(index, index == pagerState.currentPage)
        }

        if (actionsVisible) {
            if (expanded) {
                Box(
                    modifier =
                        Modifier.fillMaxSize()
                            .background(colorResource(uk.xa0.tulkki.data.R.color.black87))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { expanded = false },
                )
            }
            ActionStack(
                canDelete = canDelete,
                expanded = expanded,
                // The main button's own `SpeedDialView.toggle()`: it opens and closes the dial, and
                // only a photo tap hides the whole stack (`showFAB`/`hideFAB`).
                onToggle = { expanded = !expanded },
                onDelete = onDelete,
                onOpen = onOpen,
                onShare = onShare,
                onSave = onSave,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }
}

/**
 * The SpeedDial's replacement: the main button plus, while open, the four items above it with their
 * labels. The order is the old `addActionItem` order, so Delete sits closest to the button.
 */
@Composable
private fun ActionStack(
    canDelete: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The deleted SpeedDialView's `backgroundTint`/`sdMainFab*BackgroundColor`, which was `?colorAccent`
    // for the button and `@color/white` for the mark and each item's image.
    val accent =
        MaterialColors.getColor(
            LocalContext.current,
            androidx.appcompat.R.attr.colorAccent,
            MaterialTheme.colorScheme.primary.toArgb(),
        )
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (expanded) {
            if (canDelete) {
                ActionItem(R.drawable.ic_delete_24dp, stringResource(R.string.delete), accent, onDelete)
            }
            ActionItem(
                R.drawable.ic_open_in_new_white_24dp,
                stringResource(R.string.open_with),
                accent,
                onOpen,
            )
            ActionItem(R.drawable.ic_share_24dp, stringResource(R.string.share), accent, onShare)
            ActionItem(
                R.drawable.ic_save_24dp,
                stringResource(R.string.save_to_downloads),
                accent,
                onSave,
            )
        }
        FloatingActionButton(
            onClick = onToggle,
            containerColor = Color(accent),
            contentColor = Color.White,
        ) {
            Icon(
                painter =
                    painterResource(
                        if (expanded) R.drawable.ic_cancel_24dp else R.drawable.ic_menu_white_24dp),
                contentDescription =
                    stringResource(if (expanded) R.string.action_close else R.string.more_options),
                tint = Color.White,
            )
        }
    }
}

/** One labelled dial item: the label the SpeedDial drew beside the small button. */
@Composable
private fun ActionItem(
    icon: Int,
    label: String,
    accent: Int,
    onClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            modifier = Modifier.padding(end = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
        )
        SmallFloatingActionButton(
            onClick = onClick,
            containerColor = Color(accent),
            contentColor = Color.White,
        ) {
            Icon(painter = painterResource(icon), contentDescription = label, tint = Color.White)
        }
    }
}
