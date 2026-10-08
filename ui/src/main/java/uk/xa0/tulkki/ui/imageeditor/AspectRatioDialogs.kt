package uk.xa0.tulkki.ui.imageeditor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
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
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.theme.TulkkiSpacing

/** The vendored `dialog_other_aspect_ratio.xml`'s two columns, in its own order. */
private val LANDSCAPE_RATIOS =
    listOf(
        "2:1" to Pair(2f, 1f),
        "3:2" to Pair(3f, 2f),
        "4:3" to Pair(4f, 3f),
        "5:3" to Pair(5f, 3f),
        "16:9" to Pair(16f, 9f),
        "19:9" to Pair(19f, 9f),
    )

private val PORTRAIT_RATIOS =
    listOf(
        "1:2" to Pair(1f, 2f),
        "2:3" to Pair(2f, 3f),
        "3:4" to Pair(3f, 4f),
        "3:5" to Pair(3f, 5f),
        "9:16" to Pair(9f, 16f),
        "9:19" to Pair(9f, 19f),
    )

/**
 * The crop's "other" aspect-ratio picker, the Compose rewrite of the vendored
 * `medialib.dialogs.OtherAspectRatioDialog` and the `CustomAspectRatioDialog` it opened.
 *
 * <p>Every ratio the vendored two `RadioGroup`s offered is here - six landscape, six portrait, and
 * the custom row - and picking one hands the pair back and closes, exactly as the vendored
 * `ratioPicked`/`customRatioPicked` did. The radio marks follow the last used ratio; a ratio that
 * matches none of the twelve leaves the custom row marked, which is the vendored `else -> 0` case.
 */
@Composable
fun OtherAspectRatioDialog(
    lastOtherAspectRatio: Pair<Float, Float>?,
    onDismiss: () -> Unit,
    onPicked: (Pair<Float, Float>) -> Unit,
) {
    var customOpen by remember { mutableStateOf(false) }

    if (customOpen) {
        CustomAspectRatioDialog(
            defaultAspectRatio = lastOtherAspectRatio,
            onDismiss = { customOpen = false },
            onPicked = onPicked,
        )
        return
    }

    val customSelected = (LANDSCAPE_RATIOS + PORTRAIT_RATIOS).none { it.second == lastOtherAspectRatio }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(uk.xa0.tulkki.data.R.string.cancel)) }
        },
        text = {
            Row(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TulkkiSpacing.xxs)) {
                    LANDSCAPE_RATIOS.forEach { (label, ratio) ->
                        RatioOption(label, ratio == lastOtherAspectRatio) { onPicked(ratio) }
                    }
                    RatioOption(stringResource(R.string.custom), customSelected) { customOpen = true }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TulkkiSpacing.xxs)) {
                    PORTRAIT_RATIOS.forEach { (label, ratio) ->
                        RatioOption(label, ratio == lastOtherAspectRatio) { onPicked(ratio) }
                    }
                }
            }
        },
    )
}

/** The width/height editor the custom row opens, the vendored `CustomAspectRatioDialog`. */
@Composable
fun CustomAspectRatioDialog(
    defaultAspectRatio: Pair<Float, Float>?,
    onDismiss: () -> Unit,
    onPicked: (Pair<Float, Float>) -> Unit,
) {
    var width by remember { mutableStateOf(defaultAspectRatio?.first?.toInt()?.toString().orEmpty()) }
    var height by remember { mutableStateOf(defaultAspectRatio?.second?.toInt()?.toString().orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onPicked(Pair(width.toFloatOrNull() ?: 0f, height.toFloatOrNull() ?: 0f)) },
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(uk.xa0.tulkki.data.R.string.cancel)) }
        },
        text = {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                NumberField(value = width, hint = R.string.width, onValueChange = { width = it }, modifier = Modifier.weight(1f))
                Text(text = ":", modifier = Modifier.padding(horizontal = TulkkiSpacing.md))
                NumberField(value = height, hint = R.string.height, onValueChange = { height = it }, modifier = Modifier.weight(1f))
            }
        },
    )
}

@Composable
private fun RatioOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = TulkkiSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text = label)
    }
}
