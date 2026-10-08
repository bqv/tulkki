package uk.xa0.tulkki.ui.imageeditor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.theme.TulkkiEditorColors
import uk.xa0.tulkki.ui.theme.TulkkiEditorDimens
import uk.xa0.tulkki.ui.theme.TulkkiSpacing

/**
 * The image editor's Compose framing: the toolbar, the filter strip, the crop/rotate and draw bars,
 * the aspect-ratio strip and the three primary actions.
 *
 * <p>It is the replacement for the vendored `medialib.activities.EditActivity`'s XML shell. The
 * surfaces between the toolbar and the bars - the preview, the cropper and the drawing canvas - are
 * the three `slot`s the Activity hands in, because they are Android views this slice does not
 * rewrite (`EditorDrawCanvas`), third-party views it must not (`CropImageView`, `PhotoView`), or
 * both. The screen therefore owns the framing and none of the pixels, which is exactly the first
 * slice of the sweep.
 *
 * <p>Every callback is one edit step the old Activity had; none of them is improvised here:
 * [onRotate]/[onResize]/[onFlipHorizontally]/[onFlipVertically] and [onAspectRatio]/
 * [onOtherAspectRatio] are the crop/rotate steps, [onFilter] is the filter pack,
 * [onDrawColor]/[onBrushSize]/[onUndo] are the draw steps, and [onDone] is the single save path.
 */
@Composable
fun ImageEditorScreen(
    session: ImageEditorSession,
    preview: @Composable () -> Unit,
    crop: @Composable () -> Unit,
    draw: @Composable () -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
    onMode: (EditorMode) -> Unit,
    onRotate: () -> Unit,
    onResize: () -> Unit,
    onFlipHorizontally: () -> Unit,
    onFlipVertically: () -> Unit,
    onAspectPanel: () -> Unit,
    onAspectRatio: (Int) -> Unit,
    onOtherAspectRatio: () -> Unit,
    onFilter: (Int) -> Unit,
    onDrawColor: () -> Unit,
    onBrushSize: (Int) -> Unit,
    onUndo: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().background(TulkkiEditorColors.background),
    ) {
        EditorToolbar(session.title, onBack, onDone)

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (session.surface) {
                EditorSurface.PREVIEW -> preview()
                EditorSurface.CROP -> crop()
                EditorSurface.DRAW -> draw()
            }
        }

        if (session.mode == EditorMode.CROP && session.aspectPanelOpen) {
            AspectRatioBar(session.aspectRatio, onAspectRatio, onOtherAspectRatio)
        }

        when (session.mode) {
            EditorMode.FILTER -> FilterStrip(session.filters, session.selectedFilter, onFilter)
            EditorMode.CROP -> CropRotateBar(session.aspectPanelOpen, onRotate, onResize, onFlipHorizontally, onFlipVertically, onAspectPanel)
            EditorMode.DRAW -> DrawBar(session.drawColor, session.brushSize, onDrawColor, onBrushSize, onUndo)
            EditorMode.NONE -> Unit
        }

        PrimaryActionBar(session.mode, onMode)
    }
}

@Composable
private fun EditorToolbar(title: String, onBack: () -> Unit, onDone: () -> Unit) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .height(TulkkiEditorDimens.toolbarHeight)
                .background(TulkkiEditorColors.chrome),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(androidx.appcompat.R.drawable.abc_ic_ab_back_material),
                contentDescription = stringResource(R.string.back),
                tint = TulkkiEditorColors.onChrome,
            )
        }
        Text(
            text = title,
            color = TulkkiEditorColors.onChrome,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = TulkkiSpacing.sm),
        )
        IconButton(onClick = onDone) {
            Icon(
                painter = painterResource(R.drawable.rounded_done_outline_24),
                contentDescription = stringResource(R.string.done),
                tint = TulkkiEditorColors.onChrome,
            )
        }
    }
}

@Composable
private fun FilterStrip(filters: List<EditorFilter>, selected: Int, onFilter: (Int) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().background(TulkkiEditorColors.chrome),
        contentPadding = PaddingValues(TulkkiSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(TulkkiSpacing.sm),
    ) {
        itemsIndexed(filters) { index, item ->
            val thumbnail = remember(item.thumbnail) { item.thumbnail.asImageBitmap() }
            val selection =
                if (index == selected) {
                    Modifier.border(TulkkiEditorDimens.selectionStroke, MaterialTheme.colorScheme.primary)
                } else {
                    Modifier
                }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onFilter(index) },
            ) {
                Image(
                    bitmap = thumbnail,
                    contentDescription = item.name,
                    modifier = Modifier.size(TulkkiEditorDimens.filterThumbnail).then(selection),
                )
                Text(
                    text = item.name,
                    color = TulkkiEditorColors.onChrome,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AspectRatioBar(current: Int, onAspectRatio: (Int) -> Unit, onOtherAspectRatio: () -> Unit) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .height(TulkkiEditorDimens.actionsBarHeight)
                .background(TulkkiEditorColors.chrome),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AspectRatioLabel(stringResource(R.string.free_aspect_ratio), current == ASPECT_RATIO_FREE) { onAspectRatio(ASPECT_RATIO_FREE) }
        AspectRatioLabel("1:1", current == ASPECT_RATIO_ONE_ONE) { onAspectRatio(ASPECT_RATIO_ONE_ONE) }
        AspectRatioLabel("4:3", current == ASPECT_RATIO_FOUR_THREE) { onAspectRatio(ASPECT_RATIO_FOUR_THREE) }
        AspectRatioLabel("16:9", current == ASPECT_RATIO_SIXTEEN_NINE) { onAspectRatio(ASPECT_RATIO_SIXTEEN_NINE) }
        AspectRatioLabel(stringResource(R.string.other_aspect_ratio), current == ASPECT_RATIO_OTHER) { onOtherAspectRatio() }
    }
}

@Composable
private fun AspectRatioLabel(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        color = if (selected) MaterialTheme.colorScheme.primary else TulkkiEditorColors.onChrome,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.clickable(onClick = onClick).padding(TulkkiEditorDimens.actionPadding),
    )
}

@Composable
private fun CropRotateBar(
    aspectPanelOpen: Boolean,
    onRotate: () -> Unit,
    onResize: () -> Unit,
    onFlipHorizontally: () -> Unit,
    onFlipVertically: () -> Unit,
    onAspectPanel: () -> Unit,
) {
    EditorActionsBar(
        actions =
            listOf(
                EditorActionSpec(R.drawable.ic_rotate_right_vector, R.string.rotate, false, onRotate),
                EditorActionSpec(R.drawable.ic_minimize_vector, R.string.resize, false, onResize),
                EditorActionSpec(R.drawable.ic_aspect_ratio_vector, R.string.crop, aspectPanelOpen, onAspectPanel),
                EditorActionSpec(R.drawable.ic_flip_horizontally_vector, R.string.flip_horizontally, false, onFlipHorizontally),
                EditorActionSpec(R.drawable.ic_flip_vertically_vector, R.string.flip_vertically, false, onFlipVertically),
            )
    )
}

@Composable
private fun DrawBar(
    drawColor: Int,
    brushSize: Int,
    onDrawColor: () -> Unit,
    onBrushSize: (Int) -> Unit,
    onUndo: () -> Unit,
) {
    val scale = maxOf(0.03f, brushSize / 100f)
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .height(TulkkiEditorDimens.actionsBarHeight)
                .background(TulkkiEditorColors.chrome),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Slider(
            value = brushSize.toFloat(),
            onValueChange = { onBrushSize(it.toInt()) },
            valueRange = 0f..100f,
            modifier = Modifier.weight(1f).padding(horizontal = TulkkiSpacing.lg),
        )
        Box(
            modifier =
                Modifier.size(TulkkiEditorDimens.colorSwatch * scale)
                    .clip(CircleShape)
                    .background(Color(drawColor))
                    .border(TulkkiEditorDimens.hairline, TulkkiEditorColors.onChrome, CircleShape)
                    .clickable(onClick = onDrawColor),
        )
        IconButton(onClick = onUndo) {
            Icon(
                painter = painterResource(R.drawable.ic_undo_vector),
                contentDescription = stringResource(R.string.undo),
                tint = TulkkiEditorColors.onChrome,
            )
        }
    }
}

@Composable
private fun PrimaryActionBar(mode: EditorMode, onMode: (EditorMode) -> Unit) {
    EditorActionsBar(
        actions =
            listOf(
                EditorActionSpec(R.drawable.ic_photo_filter_vector, R.string.filter, mode == EditorMode.FILTER) { onMode(EditorMode.FILTER) },
                EditorActionSpec(R.drawable.ic_crop_rotate_vector, R.string.transform, mode == EditorMode.CROP) { onMode(EditorMode.CROP) },
                EditorActionSpec(R.drawable.ic_draw_vector, R.string.draw, mode == EditorMode.DRAW) { onMode(EditorMode.DRAW) },
            )
    )
}

private class EditorActionSpec(
    val icon: Int,
    val label: Int,
    val selected: Boolean,
    val onClick: () -> Unit,
)

@Composable
private fun EditorActionsBar(actions: List<EditorActionSpec>) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .height(TulkkiEditorDimens.actionsBarHeight)
                .background(TulkkiEditorColors.chrome),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEach { action ->
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight().clickable(onClick = action.onClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(action.icon),
                    contentDescription = stringResource(action.label),
                    tint = if (action.selected) MaterialTheme.colorScheme.primary else TulkkiEditorColors.onChrome,
                )
            }
        }
    }
}
