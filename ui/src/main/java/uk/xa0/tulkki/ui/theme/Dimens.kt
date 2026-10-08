package uk.xa0.tulkki.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * The design system's spacing scale, docs/MIGRATION.md "Design: the Compose UI" §1.4: "A 4 dp base,
 * with every step traceable to `src/main/res/values/dimens.xml` so a reviewer can map the old and new
 * screens", each step naming what it existed as.
 *
 * <p>These live here because §1.8 rule 1 forbids a dimension literal in a Composable: "a JVM test that
 * reads the `:ui` Compose sources as text ... and fails on ... a `<number>.dp` literal outside
 * `theme/Dimens.kt` and `theme/Color.kt`". A screen that writes `16.dp` is therefore already a defect,
 * which is why this file lands with the first screen that needs it rather than with that screen's own
 * spacing.
 */
object TulkkiSpacing {
    /** `—` (new; the smallest step). */
    val xxs = 2.dp

    /** `input_label_vertical_spacing`, `input_label_horizontal_spacing`. */
    val xs = 4.dp

    /** `list_padding`, `card_padding_list`, `activity_horizontal_margin`. */
    val sm = 8.dp

    /** `image_button_padding`. */
    val md = 12.dp

    /** `card_padding_regular`, `avatar_item_distance`. */
    val lg = 16.dp

    /** `—` (screen gutters on a Compose surface). */
    val xl = 24.dp

    /** `—` (empty-state breathing room). */
    val xxl = 32.dp
}

/**
 * §1.5's shapes: "`bubble_radius` 10 dp, `avatar_radius` 10 dp, `image_radius` 6 dp, cards
 * `MaterialTheme.shapes.medium` (12 dp), the language chip fully rounded (pill) ... Nothing else in the
 * app is a new radius". Cards take the theme's own medium shape, so they are not spelled here.
 */
object TulkkiShape {

    /**
     * `bubble_radius` itself, for the one drawing that has no `Shape` to hand: `Placeholder`'s bars
     * are drawn with `drawRoundRect`, whose corner is a `CornerRadius` and not a `Shape`, and §6.2
     * asks for "the same radius family as the bubble so it reads as a blurred line of text rather
     * than a loading skeleton". The `Shape` below is built from this number, so the two cannot drift.
     */
    val bubbleCorner = 10.dp

    val bubble = RoundedCornerShape(bubbleCorner)
    val avatar = RoundedCornerShape(10.dp)
    val image = RoundedCornerShape(6.dp)
}

/**
 * §1.6: "`toolbar_elevation` (4 dp) is the one real shadow, kept for the app bar and the composer when
 * it floats. Everything else is tonal ... and a message bubble has **no** elevation." So there is one
 * token here and there is nothing to add for a card or a bubble.
 */
object TulkkiElevation {
    val toolbar = 4.dp
}

/**
 * The composer's own fixed size, so §1.8 rule 1 has somewhere to put it: the pending-attachment
 * square is the Java media-preview strip's `media_preview_size` (72 dp), carried here rather than
 * spelled in the strip's Composable.
 */
object TulkkiComposerDimens {
    /** `media_preview_size`, each staged attachment's square in the pending strip. */
    val attachmentPreview = 72.dp
}

/**
 * The image editor's fixed chrome sizes, so §1.8 rule 1 has somewhere to put them.
 *
 * <p>The editor is the one screen in the tree that is deliberately full-bleed and dark in both
 * themes, so its bars have their own heights rather than the design system's spacing: three of them
 * are the XML `dimens.xml` the editor's deleted layouts used (`bottom_actions_height` 64 dp,
 * `bottom_filters_thumbnail_size` 76 dp, `bottom_editor_color_picker_size` 48 dp), and the toolbar
 * is what the XML took from `?attr/actionBarSize`.
 */
object TulkkiEditorDimens {
    /** `?attr/actionBarSize`, the deleted `activity_edit.xml`'s toolbar height. */
    val toolbarHeight = 56.dp

    /** `bottom_actions_height`, shared by the primary, crop/rotate, draw and aspect-ratio bars. */
    val actionsBarHeight = 64.dp

    /** `normal_margin`, the padding around each bar's icon. */
    val actionPadding = 12.dp

    /** `bottom_filters_thumbnail_size`, the filter strip's cell. */
    val filterThumbnail = 76.dp

    /** `bottom_editor_color_picker_size`, the draw bar's colour swatch. */
    val colorSwatch = 48.dp

    /** The filter strip's selection ring, `stroke_background`'s 2 dp stroke. */
    val selectionStroke = 2.dp

    /** The sliver a swatch or a slider needs; a shape outline, not a border. */
    val hairline = 1.dp

    /**
     * The colour picker's box, from the deleted `dialog_color_picker.xml`: the 240 dp saturation/value
     * square, the 30 dp new/old and recent swatches (`colorpicker_hue_width`), and the
     * `large_margin` 16 dp cursor the vendored `ColorPickerDialog` moved over the square.
     */
    val pickerSquare = 240.dp

    val pickerSwatch = 30.dp

    val pickerCursor = 16.dp
}

/**
 * The ad-hoc command page's fixed sizes, so the Compose renderer keeps §1.8 rule 1's promise:
 * each is the deleted XML's own number, carried here rather than spelled in a Composable.
 *
 * * [progressHeight] and [webViewHeight] are `command_progress_bar.xml`'s 130 dp bar and the
 *   `command_webview.xml` panel's own area, given a fixed height because the page is a
 *   `LazyColumn` and a weighted child of a wrap-content row measures to nothing.
 * * [optionListHeight] is `command_search_list_field.xml`'s 200 dp list.
 * * [defaultButtonMinHeight] is `command_button_grid_field.xml`'s 75 dp default button.
 */
object TulkkiCommandDimens {
    val progressHeight = 130.dp
    val webViewHeight = 320.dp
    val optionListHeight = 200.dp
    val defaultButtonMinHeight = 75.dp
}

/**
 * The reading aid's card, from the deleted `gloss_popup.xml`/`gloss_row.xml` (the Compose card keeps
 * their own numbers, so the aid reads as the same aid): the 14 dp corner and 8 dp elevation of the
 * `MaterialCardView`, the label column's 100 dp, the value's 190 dp ceiling and the 10 dp between
 * them, and the 328 dp the whole card is bounded to - "the widest row is 100dp of label + 10dp +
 * 190dp of value, which is 328dp of card with its padding", inside a 360 dp screen.
 */
object TulkkiGlossDimens {
    val cardCorner = 14.dp
    val cardElevation = 8.dp
    val cardMaxWidth = 328.dp
    val labelWidth = 100.dp
    val valueMaxWidth = 190.dp
    val labelGap = 10.dp

    /** The spinner under the word while the request is out; the XML let it measure itself. */
    val progress = 24.dp
}
