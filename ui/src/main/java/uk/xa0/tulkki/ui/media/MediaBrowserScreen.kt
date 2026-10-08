package uk.xa0.tulkki.ui.media

import android.graphics.Bitmap
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.xa0.tulkki.data.FileBackends
import uk.xa0.tulkki.libs.AttachmentRef
import uk.xa0.tulkki.ui.R

/**
 * The browser's grid of thumbnails and its five tabs.
 *
 * <p>**What replaced what.** The deleted `activity_media_browser.xml` held an `AppBarLayout` (toolbar
 * + `TabLayout`) above a `ViewPager2` of five `MediaBrowserFragment`s, and each fragment was a
 * `RecyclerView` grid; `fragment_media_browser.xml` was that one `RecyclerView`. The toolbar is the
 * shared chrome now (the Activity's `TulkkiChrome`), the tabs are [ScrollableTabRow], the pages are a
 * [HorizontalPager] and the rows are [LazyVerticalGrid] items, so both layouts and the fragment are
 * gone. The grid's columns come from the same arithmetic `GridManager.setupLayoutManager` did - the
 * width divided by `R.dimen.browser_media_size` - and the preview size is the column width, which is
 * the `realWidth` it handed `MediaAdapter.setMediaSize`.
 *
 * <p>**The strings are the deleted menu's.** The chrome's overflow carries search-by-date, clear
 * search and the four selection actions, because the Activity owns those; this screen names only the
 * five tab labels and the Delete File item of a row's long-press menu.
 *
 * @param attachments every attachment the backend handed over, unfiltered.
 * @param selected the current selection; a non-empty selection is what the row's click means
 *     "toggle" instead of "open".
 * @param onOpen the row's click while nothing is selected - the old `ViewUtil.view`.
 * @param onToggleSelection the row's click while something is selected, and its long-press while
 *     nothing is.
 * @param onDeleteFile a long-press on a row that is already in selection mode, for a writable file.
 * @param deleteFileLabel the Delete File item's text, resolved by the caller.
 * @param previews the thumbnail lookup; the default is `FileBackends`' cached-then-decoded pair, and
 *     the screenshot fixture passes a stub so a cell needs no backend.
 */
@Composable
fun MediaBrowserScreen(
    attachments: List<AttachmentRef>,
    selected: Set<AttachmentRef>,
    onOpen: (AttachmentRef) -> Unit,
    onToggleSelection: (AttachmentRef) -> Unit,
    onDeleteFile: (AttachmentRef) -> Unit,
    deleteFileLabel: String,
    modifier: Modifier = Modifier,
    previews: suspend (AttachmentRef, Int) -> Bitmap? = ::fileBackendPreview,
) {
    val pagerState = rememberPagerState(pageCount = { MediaTab.entries.size })
    val scope = rememberCoroutineScope()
    Column(modifier = modifier.fillMaxSize()) {
        ScrollableTabRow(
            selectedTabIndex = pagerState.currentPage,
            edgePadding = 0.dp,
        ) {
            for ((index, tab) in MediaTab.entries.withIndex()) {
                Tab(
                    selected = index == pagerState.currentPage,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    text = { Text(stringResource(tab.labelRes)) },
                )
            }
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) { page ->
            MediaTabPage(
                tab = MediaTab.entries[page],
                attachments = attachments,
                selected = selected,
                onOpen = onOpen,
                onToggleSelection = onToggleSelection,
                onDeleteFile = onDeleteFile,
                deleteFileLabel = deleteFileLabel,
                previews = previews,
            )
        }
    }
}

/** One tab's page: its filtered album, laid out in as many columns as the width allows. */
@Composable
private fun MediaTabPage(
    tab: MediaTab,
    attachments: List<AttachmentRef>,
    selected: Set<AttachmentRef>,
    onOpen: (AttachmentRef) -> Unit,
    onToggleSelection: (AttachmentRef) -> Unit,
    onDeleteFile: (AttachmentRef) -> Unit,
    deleteFileLabel: String,
    previews: suspend (AttachmentRef, Int) -> Bitmap?,
) {
    val rows =
        remember(attachments, tab) {
            mediaAlbum(attachments.filter { mediaMatches(it, tab) }, true)
        }
    val desiredSize = dimensionResource(R.dimen.browser_media_size)
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // The old GridManager: columns from the available width and the desired cell size, and the
        // cell width as the preview size. `coerceAtLeast(1)` is the one liberty - the old division
        // by zero columns would have been an ArithmeticException, not a layout.
        val desiredPx = with(LocalDensity.current) { desiredSize.toPx() }
        val widthPx = constraints.maxWidth
        val columns = Math.round(widthPx / desiredPx).coerceAtLeast(1)
        val mediaSizePx = widthPx / columns
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(2.dp),
        ) {
            for (row in rows) {
                when (row) {
                    is MediaAlbumEntry.Day ->
                        item(key = "day-" + row.timestamp, span = { GridItemSpan(maxLineSpan) }) {
                            DayHeading(row.timestamp)
                        }
                    is MediaAlbumEntry.Row ->
                        item(key = row.attachment.getUuid().toString()) {
                            MediaCell(
                                attachment = row.attachment,
                                isSelected = selected.contains(row.attachment),
                                selectionMode = selected.isNotEmpty(),
                                mediaSizePx = mediaSizePx,
                                previews = previews,
                                onOpen = { onOpen(row.attachment) },
                                onToggleSelection = { onToggleSelection(row.attachment) },
                                onDeleteFile = { onDeleteFile(row.attachment) },
                                deleteFileLabel = deleteFileLabel,
                            )
                        }
                }
            }
        }
    }
}

/** The date heading: the deleted `item_date_separator.xml`'s text, drawable and colours. */
@Composable
private fun DayHeading(timestamp: Long) {
    val context = LocalContext.current
    Text(
        text =
            DateUtils.formatDateTime(
                context,
                timestamp,
                DateUtils.FORMAT_SHOW_DATE or
                    DateUtils.FORMAT_SHOW_YEAR or
                    DateUtils.FORMAT_SHOW_WEEKDAY,
            ),
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.secondary,
    )
}

/**
 * One attachment's square cell.
 *
 * <p>It is the deleted `item_media.xml` and `MediaAdapter.onBindViewHolder` together: a
 * `SquareFrameLayout` (here [Modifier.aspectRatio] 1), a 2 dp frame, the `centerInside` thumbnail or
 * the mime icon on `colorSurfaceContainerHighest` at `?colorOnSurface`, the `#80000000` overlay and
 * the trailing white check while selected, and the same tap/long-press meaning. The long-press only
 * opens Delete File when the row is already in selection mode and its original file is writable,
 * which is what the old `setOnCreateContextMenuListener` did after the long-click listener declined
 * the event.
 */
@Composable
private fun MediaCell(
    attachment: AttachmentRef,
    isSelected: Boolean,
    selectionMode: Boolean,
    mediaSizePx: Int,
    previews: suspend (AttachmentRef, Int) -> Bitmap?,
    onOpen: () -> Unit,
    onToggleSelection: () -> Unit,
    onDeleteFile: () -> Unit,
    deleteFileLabel: String,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var bitmap by remember(attachment, mediaSizePx) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(attachment, mediaSizePx) {
        bitmap = previews(attachment, mediaSizePx)
    }
    Box(
        modifier =
            Modifier.aspectRatio(1f)
                .padding(2.dp)
                .combinedClickable(
                    onClick = { if (selectionMode) onToggleSelection() else onOpen() },
                    onLongClick = {
                        if (!selectionMode) {
                            onToggleSelection()
                        } else if (originalFileIsWritable(attachment)) {
                            menuOpen = true
                        }
                    },
                ),
    ) {
        val container = MaterialTheme.colorScheme.surfaceContainerHighest
        val thumbnail = bitmap
        if (attachment.renderThumbnail() && thumbnail != null) {
            Image(
                bitmap = thumbnail.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().background(container),
                contentScale = ContentScale.Inside,
            )
        } else if (attachment.renderThumbnail()) {
            // MediaAdapter's loading colour, while the decode runs.
            Box(modifier = Modifier.fillMaxSize().background(Color(0xFF333333)))
        } else {
            Image(
                painter = painterResource(MediaIcons.drawableFor(attachment)),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().background(container),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                contentScale = ContentScale.Inside,
            )
        }
        if (isSelected) {
            Box(modifier = Modifier.fillMaxSize().background(Color(0x80000000)))
            Icon(
                painter = painterResource(R.drawable.ic_check_24dp),
                contentDescription = null,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                tint = Color.White,
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(deleteFileLabel) },
                onClick = {
                    menuOpen = false
                    onDeleteFile()
                },
            )
        }
    }
}

/** The old context-menu guard: the original file exists and this process may delete it. */
private fun originalFileIsWritable(attachment: AttachmentRef): Boolean {
    val path = FileBackends.get().getOriginalPath(attachment.getUri()) ?: return false
    return java.io.File(path).canWrite()
}

/** `FileBackends`' cached bitmap first and its decode second, which is `MediaAdapter`'s pair. */
private suspend fun fileBackendPreview(attachment: AttachmentRef, sizePx: Int): Bitmap? =
    withContext(Dispatchers.IO) {
        FileBackends.get().getPreviewForUri(attachment, sizePx, true)
            ?: FileBackends.get().getPreviewForUri(attachment, sizePx, false)
    }
