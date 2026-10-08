package uk.xa0.tulkki.ui.imageeditor

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zomato.photofilters.imageprocessors.Filter

/**
 * One cell of the editor's filter strip: the pack's name, the processed thumbnail the strip draws,
 * and the filter the preview applies.
 *
 * <p>It replaces the vendored `medialib.models.FilterItem` and
 * `medialib.helpers.FilterThumbnailsManager`, which existed only to feed the RecyclerView that
 * `FiltersAdapter` drove; the strip is a `LazyRow` now, so the item type is what the strip needs and
 * nothing else.
 */
data class EditorFilter(val name: String, val thumbnail: Bitmap, val filter: Filter)

/** What the editor draws between the toolbar and its action bars. */
enum class EditorSurface {
    PREVIEW,
    CROP,
    DRAW,
}

/**
 * Which primary action is open; `NONE` is a closed panel over whatever surface is already drawn.
 *
 * <p>It keeps the vendored `EditActivity`'s one oddity on purpose: the three primary buttons toggle,
 * and toggling the open one off leaves its surface visible (`EditActivity.updatePrimaryActionButtons`),
 * so "the action drawn highlighted" and "the surface drawn" are two different states and are two
 * different fields here.
 */
enum class EditorMode {
    NONE,
    FILTER,
    CROP,
    DRAW,
}

/**
 * The observable state of the Compose image editor, written by [ImageEditorActivity] and read by the
 * screen.
 *
 * <p>Every field is a `mutableStateOf`, so the Activity drives the screen by assignment and never by
 * rebuilding a composition; the same shape `TopUpHost.Session` uses. The imperative half - the
 * `CropImageView`, the `EditorDrawCanvas`, Glide and the keep-or-replace dialogs - stays in the
 * Activity, because those are the vendored leaves this slice does not own yet.
 */
class ImageEditorSession {
    /** The chat's name, drawn as the toolbar title; blank when the caller passed none. */
    var title by mutableStateOf("")

    var mode by mutableStateOf(EditorMode.NONE)

    var surface by mutableStateOf(EditorSurface.PREVIEW)

    /** The crop view's aspect-ratio strip, the vendored `currCropRotateAction`. */
    var aspectPanelOpen by mutableStateOf(true)

    var aspectRatio by mutableStateOf(ASPECT_RATIO_FREE)

    var lastOtherAspectRatio by mutableStateOf<Pair<Float, Float>?>(null)

    /** The filtered bitmap the preview draws; null before the image has loaded. */
    var preview by mutableStateOf<Bitmap?>(null)

    /** The filter strip, empty until the first FILTER action builds it. */
    var filters by mutableStateOf<List<EditorFilter>>(emptyList())

    var selectedFilter by mutableStateOf(0)

    var drawColor by mutableStateOf(0)

    var brushSize by mutableStateOf(50)

    /**
     * The draw surface's background, sized to fit the editor's canvas and centred in it; null until
     * the DRAW action is opened for the first time.
     */
    var drawBackground by mutableStateOf<Bitmap?>(null)

    /** The colour picker, opened by the draw bar's swatch and drawn by the activity. */
    var colorPickerOpen by mutableStateOf(false)

    /** The crop's "other" aspect-ratio picker, opened by the aspect bar's last label. */
    var otherAspectRatioOpen by mutableStateOf(false)

    /** The resize editor, opened by the crop/rotate bar and shown with the crop rectangle's size. */
    var resizeOpen by mutableStateOf(false)
}
