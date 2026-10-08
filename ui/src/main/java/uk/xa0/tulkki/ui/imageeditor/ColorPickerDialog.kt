package uk.xa0.tulkki.ui.imageeditor

import android.graphics.Color as AndroidColor
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.theme.TulkkiEditorColors
import uk.xa0.tulkki.ui.theme.TulkkiEditorDimens
import uk.xa0.tulkki.ui.theme.TulkkiSpacing

/** The vendored `ColorPickerDialog`'s own bound: the five most recent colours it kept. */
internal const val RECENT_COLORS_NUMBER = 5

/**
 * The colour picker, the Compose rewrite of the vendored `medialib.dialogs.ColorPickerDialog` and the
 * `medialib.views.ColorPickerSquare` it drove.
 *
 * <p>The behaviour is the old pair's: a hue and a saturation/value edit over the current colour, a
 * six-digit hex field that both reflects and sets it, an old and a new swatch, the recent colours from
 * the editor's preferences, a long press that copies the old hex, and a positive/cancel result. What
 * changes is the shape: the vendored vertical hue image and `ImageView` cursor are one Compose
 * `Slider` and one drawn ring, because the migration's licence is to redesign the screen and not to
 * drop the edit.
 *
 * <p>The dialog writes nothing. The colour that was picked, and the recent-colour list it is folded
 * into, belong to [ImageEditorActivity].
 */
@Composable
fun ColorPickerDialog(
    initialColor: Int,
    recentColors: List<Int>,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val hsv = remember(initialColor) { FloatArray(3).also { AndroidColor.colorToHSV(initialColor, it) } }
    var hue by remember { mutableStateOf(hsv[0]) }
    var saturation by remember { mutableStateOf(hsv[1]) }
    var value by remember { mutableStateOf(hsv[2]) }
    var hex by remember { mutableStateOf(hexOf(initialColor)) }

    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    fun currentColor(): Int =
        if (hex.length == HEX_LENGTH) {
            runCatching { AndroidColor.parseColor("#$hex") }.getOrDefault(hsvColor(hue, saturation, value))
        } else {
            hsvColor(hue, saturation, value)
        }

    fun setHsv(newHue: Float, newSaturation: Float, newValue: Float) {
        hue = newHue
        saturation = newSaturation
        value = newValue
        hex = hexOf(hsvColor(newHue, newSaturation, newValue))
    }

    fun applyHex(candidate: String) {
        hex = candidate
        if (candidate.length == HEX_LENGTH) {
            runCatching { AndroidColor.parseColor("#$candidate") }.getOrNull()?.let { parsed ->
                AndroidColor.colorToHSV(parsed, hsv)
                hue = hsv[0]
                saturation = hsv[1]
                value = hsv[2]
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(currentColor()) }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(uk.xa0.tulkki.data.R.string.cancel)) }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(TulkkiSpacing.sm),
            ) {
                SaturationValueSquare(hue = hue, saturation = saturation, value = value, onSaturationValue = ::setHsv)

                Slider(
                    value = hue,
                    onValueChange = { setHsv(it, saturation, value) },
                    valueRange = HUE_RANGE,
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "#${hexOf(initialColor)}",
                        modifier =
                            Modifier.pointerInput(initialColor) {
                                detectTapGestures(
                                    onLongPress = {
                                        clipboard.setText(AnnotatedString(hexOf(initialColor)))
                                        Toast.makeText(context, R.string.value_copied_to_clipboard_show, Toast.LENGTH_SHORT).show()
                                    },
                                )
                            },
                    )
                    Text(text = "#", modifier = Modifier.padding(horizontal = TulkkiSpacing.xs))
                    OutlinedTextField(
                        value = hex,
                        onValueChange = { raw -> applyHex(raw.filter(::isHexDigit).take(HEX_LENGTH).uppercase()) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(TulkkiSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    ColorSwatch(initialColor)
                    ColorSwatch(currentColor())
                }

                if (recentColors.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(TulkkiSpacing.md)) {
                        recentColors.take(RECENT_COLORS_NUMBER).forEach { recent ->
                            ColorSwatch(recent, clickable = true) { applyHex(hexOf(recent)) }
                        }
                    }
                }
            }
        },
    )
}

/** The vendored `ColorPickerSquare`'s two-axis edit, redrawn as one three-layer Compose square. */
@Composable
private fun SaturationValueSquare(
    hue: Float,
    saturation: Float,
    value: Float,
    onSaturationValue: (Float, Float, Float) -> Unit,
) {
    val currentOnSaturationValue by rememberUpdatedState(onSaturationValue)
    Canvas(
        modifier =
            Modifier.size(TulkkiEditorDimens.pickerSquare).pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    val height = size.height.toFloat().coerceAtLeast(1f)
                    currentOnSaturationValue(hue, saturationAt(down.position.x, width), valueAt(down.position.y, height))
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            break
                        }
                        currentOnSaturationValue(hue, saturationAt(change.position.x, width), valueAt(change.position.y, height))
                        change.consume()
                    }
                }
            },
    ) {
        drawRect(color = Color.hsv(hue, 1f, 1f))
        drawRect(brush = Brush.horizontalGradient(listOf(TulkkiEditorColors.pickerWhite, TulkkiEditorColors.pickerClear)))
        drawRect(brush = Brush.verticalGradient(listOf(TulkkiEditorColors.pickerClear, TulkkiEditorColors.pickerBlack)))
        val cursor = Offset(saturation * size.width, (1f - value) * size.height)
        val radius = TulkkiEditorDimens.pickerCursor.toPx() / 2f
        drawCircle(
            color = TulkkiEditorColors.pickerWhite,
            radius = radius,
            center = cursor,
            style = Stroke(width = TulkkiEditorDimens.selectionStroke.toPx()),
        )
        drawCircle(
            color = TulkkiEditorColors.pickerBlack,
            radius = radius,
            center = cursor,
            style = Stroke(width = TulkkiEditorDimens.hairline.toPx()),
        )
    }
}

@Composable
private fun ColorSwatch(color: Int, clickable: Boolean = false, onClick: () -> Unit = {}) {
    Box(
        modifier =
            Modifier.size(TulkkiEditorDimens.pickerSwatch)
                .clip(CircleShape)
                .background(Color(color))
                .border(TulkkiEditorDimens.hairline, TulkkiEditorColors.pickerBlack, CircleShape)
                .clickable(enabled = clickable, onClick = onClick),
    )
}

private fun isHexDigit(character: Char) = character.isDigit() || character in 'a'..'f' || character in 'A'..'F'

private fun saturationAt(x: Float, width: Float) = (x / width).coerceIn(0f, 1f)

private fun valueAt(y: Float, height: Float) = 1f - (y / height).coerceIn(0f, 1f)

private fun hsvColor(hue: Float, saturation: Float, value: Float) = AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value))

/** The vendor's `getHexCode` without the `#`: six upper-case digits of the 24-bit colour. */
private fun hexOf(color: Int) = String.format("%06X", 0xFFFFFF and color)

private const val HEX_LENGTH = 6

private val HUE_RANGE = 0f..360f
