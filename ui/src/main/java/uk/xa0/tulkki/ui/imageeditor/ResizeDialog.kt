package uk.xa0.tulkki.ui.imageeditor

import android.graphics.Point
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.theme.TulkkiSpacing

/**
 * The resize editor, the Compose rewrite of the vendored `medialib.dialogs.ResizeDialog`.
 *
 * <p>Its rules are the vendored ones: the width and height start at the crop rectangle's, each field
 * is clamped to the crop's own and, while "keep aspect ratio" is checked, moves the other to the
 * matching size; a size that is not positive is refused with the old toast and nothing is resized.
 * [onInvalid] is that toast, so the dialog itself still writes nothing.
 */
@Composable
fun ResizeDialog(
    size: Point,
    onInvalid: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (Point) -> Unit,
) {
    var width by remember(size) { mutableStateOf(size.x.toString()) }
    var height by remember(size) { mutableStateOf(size.y.toString()) }
    var keepAspectRatio by remember { mutableStateOf(true) }
    val ratio = size.x / size.y.toFloat()

    fun applyWidth(raw: String) {
        width = raw
        val parsed = raw.toIntOrNull() ?: 0
        val clamped = if (parsed > size.x) size.x else parsed
        if (parsed > size.x) {
            width = size.x.toString()
        }
        if (keepAspectRatio) {
            height = (clamped / ratio).toInt().toString()
        }
    }

    fun applyHeight(raw: String) {
        height = raw
        val parsed = raw.toIntOrNull() ?: 0
        val clamped = if (parsed > size.y) size.y else parsed
        if (parsed > size.y) {
            height = size.y.toString()
        }
        if (keepAspectRatio) {
            width = (clamped * ratio).toInt().toString()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.resize_and_save)) },
        confirmButton = {
            TextButton(
                onClick = {
                    val newWidth = width.toIntOrNull() ?: 0
                    val newHeight = height.toIntOrNull() ?: 0
                    if (newWidth <= 0 || newHeight <= 0) onInvalid() else onConfirm(Point(newWidth, newHeight))
                },
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(uk.xa0.tulkki.data.R.string.cancel)) }
        },
        text = {
            Column {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    NumberField(value = width, hint = R.string.width, onValueChange = ::applyWidth, modifier = Modifier.weight(1f))
                    Text(text = ":", modifier = Modifier.padding(horizontal = TulkkiSpacing.md))
                    NumberField(value = height, hint = R.string.height, onValueChange = ::applyHeight, modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = keepAspectRatio, onCheckedChange = { keepAspectRatio = it })
                    Text(text = stringResource(R.string.keep_aspect_ratio))
                }
            }
        },
    )
}

/** A digits-only field with a hint label, shared by the resize and custom-aspect-ratio dialogs. */
@Composable
internal fun NumberField(
    value: String,
    hint: Int,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { raw -> onValueChange(raw.filter { it.isDigit() }.take(NUMBER_MAX_LENGTH)) },
        label = { Text(stringResource(hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

private const val NUMBER_MAX_LENGTH = 6
