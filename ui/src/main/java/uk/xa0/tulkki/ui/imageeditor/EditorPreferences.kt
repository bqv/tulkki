package uk.xa0.tulkki.ui.imageeditor

import android.content.Context
import android.graphics.Color
import uk.xa0.tulkki.ui.R
import java.util.LinkedList

/**
 * The image editor's stored settings, the rewrite of the vendored `medialib.helpers.Config` and the
 * constants it shared a file's package with (`medialib.helpers.Constants`).
 *
 * <p>The preference file and every key are the vendored ones on purpose: an owner who has used the
 * editor before keeps their last crop ratio, draw colour and brush size. Only what the editor still
 * reads survives - the vendored `Config` also carried a path helper and four SDK predicates nothing
 * called, and those are gone.
 */
class EditorPreferences(context: Context) {
    private val prefs = context.getSharedPreferences(PREFERENCE_FILE, Context.MODE_PRIVATE)

    /** The draw colour an untouched editor starts from, `@color/editor_draw_default_color`. */
    private val defaultDrawColor = context.getColor(R.color.editor_draw_default_color)

    /** The picker's recent colours, newest first; the vendored default is red, blue, green, yellow, black. */
    var colorPickerRecentColors: LinkedList<Int>
        get() = LinkedList(prefs.getString(COLOR_PICKER_RECENT_COLORS, null)?.lines()?.map { it.toInt() } ?: DEFAULT_RECENT_COLORS)
        set(recentColors) = prefs.edit().putString(COLOR_PICKER_RECENT_COLORS, recentColors.joinToString(separator = "\n")).apply()

    var lastEditorCropAspectRatio: Int
        get() = prefs.getInt(LAST_EDITOR_CROP_ASPECT_RATIO, ASPECT_RATIO_FREE)
        set(value) = prefs.edit().putInt(LAST_EDITOR_CROP_ASPECT_RATIO, value).apply()

    var lastEditorCropOtherAspectRatioX: Float
        get() = prefs.getFloat(LAST_EDITOR_CROP_OTHER_ASPECT_RATIO_X, 2f)
        set(value) = prefs.edit().putFloat(LAST_EDITOR_CROP_OTHER_ASPECT_RATIO_X, value).apply()

    var lastEditorCropOtherAspectRatioY: Float
        get() = prefs.getFloat(LAST_EDITOR_CROP_OTHER_ASPECT_RATIO_Y, 1f)
        set(value) = prefs.edit().putFloat(LAST_EDITOR_CROP_OTHER_ASPECT_RATIO_Y, value).apply()

    var lastEditorDrawColor: Int
        get() = prefs.getInt(LAST_EDITOR_DRAW_COLOR, defaultDrawColor)
        set(value) = prefs.edit().putInt(LAST_EDITOR_DRAW_COLOR, value).apply()

    var lastEditorBrushSize: Int
        get() = prefs.getInt(LAST_EDITOR_BRUSH_SIZE, 50)
        set(value) = prefs.edit().putInt(LAST_EDITOR_BRUSH_SIZE, value).apply()

    private companion object {
        const val PREFERENCE_FILE = "media_config_prefs"

        const val COLOR_PICKER_RECENT_COLORS = "color_picker_recent_colors"
        const val LAST_EDITOR_CROP_ASPECT_RATIO = "last_editor_crop_aspect_ratio"
        const val LAST_EDITOR_CROP_OTHER_ASPECT_RATIO_X = "last_editor_crop_other_aspect_ratio_x_2"
        const val LAST_EDITOR_CROP_OTHER_ASPECT_RATIO_Y = "last_editor_crop_other_aspect_ratio_y_2"
        const val LAST_EDITOR_DRAW_COLOR = "last_editor_draw_color"
        const val LAST_EDITOR_BRUSH_SIZE = "last_editor_brush_size"

        val DEFAULT_RECENT_COLORS =
            LinkedList(listOf(Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW, Color.BLACK))
    }
}

/**
 * The aspect ratios the editor crops to, the vendored `medialib.helpers` constants unchanged.
 *
 * <p>They are integers because that is what the stored preference is; the pair each maps to is
 * applied by [ImageEditorActivity.updateAspectRatio].
 */
const val ASPECT_RATIO_FREE = 0
const val ASPECT_RATIO_ONE_ONE = 1
const val ASPECT_RATIO_FOUR_THREE = 2
const val ASPECT_RATIO_SIXTEEN_NINE = 3
const val ASPECT_RATIO_OTHER = 4

/** The extra a caller may pass to name the image's real path, the vendored `REAL_FILE_PATH`. */
const val REAL_FILE_PATH = "real_file_path_2"
