package uk.xa0.tulkki.ui

import android.animation.ValueAnimator
import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import uk.xa0.tulkki.translation.DisplayedBody
import uk.xa0.tulkki.ui.projection.PlaceholderShape
import uk.xa0.tulkki.ui.theme.LocalTulkkiColors
import uk.xa0.tulkki.ui.theme.TulkkiShape

/**
 * One turn of the band, in milliseconds: slow enough that a covered bubble does not strobe, fast
 * enough that a reader sees the shimmer as "something is happening" rather than as a dead block.
 * The cover is not a progress bar - §6.1 draws no caption for `TAP` or `TRANSLATING` and lets the
 * animation be the progress - so this number is the whole of what says "working".
 */
private const val SHIMMER_PERIOD_MS = 1400

/**
 * The covered body's pixels, docs/MIGRATION.md "Design: the Compose UI" §6.2: bars "drawn in
 * `Modifier.drawWithCache` with `drawRoundRect` (corner radius from `TulkkiShape`), filled with
 * `shimmerBase`", and "the highlight is one `Brush.linearGradient` with three stops (`shimmerBase` ->
 * `shimmerHighlight` -> `shimmerBase`) whose x-offset comes from `Placeholder.highlightAt(phase,
 * width)`". The geometry is [Placeholder]'s and every colour is a theme token, so this file decides
 * nothing about the shape and no literal appears in it.
 *
 * <p>**Animated only while attached.** The phase comes from `rememberInfiniteTransition`, the
 * composition's own frame clock: it stops when the composable leaves composition - a `LazyColumn`
 * item scrolled out of the window - and when the host window is not started, which is exactly the
 * property the View implementation had to hand-roll. There is deliberately no `ObjectAnimator`, no
 * `Choreographer` and no `postDelayed` here; those keep running with nothing on screen. When
 * `ValueAnimator.areAnimatorsEnabled()` is false (API 26+, where "remove animations" is a system
 * setting) the bars are drawn static, at the same height and still the tap target.
 *
 * <p>**It is the tap target**, and one tap means §"a message that genuinely could not be translated"
 * 's only verb: "translate this one, now". Nothing here enqueues anything - the caller's `onClick`
 * is the host's own `requestOne`, which is idempotent per message, so a second tap while a
 * translation is in flight buys nothing.
 *
 * <p>**The spoken form survives.** The words "Not translated yet - tap to translate" stop being drawn
 * but not being said: the content description is
 * `TranslationText.coverCaption(reason ?: DisplayedBody.Cover.TAP)`, and this modifier adds no `Text`
 * semantics at all, so a screen reader reads the reason and `uiautomator` finds no body to dump.
 *
 * <p>**Two things this modifier deliberately does not do**, because a `Modifier` draws inside the
 * bounds it is given and knows nothing above them. It does not draw the reason *under* the bars -
 * §6.2's caption policy draws that line for every cover except `TAP` and `TRANSLATING`, and the
 * bubble that owns the column does it with the same `TranslationText.coverCaption`. And it does not
 * reserve its own height: §6.2 reserves it from the shape (`lines * lineHeight`) at the call site, so
 * the list does not jump while a translation is in flight.
 *
 * <p>**The two measurements are parameters, and the sketch's are not.** §6.2 sketches
 * `shimmer(shape, reason, onClick)`. A bar's thickness is the row's own line box - `Placeholder`
 * decides it from `lineHeight` and `textSize` - and this modifier will not invent typography by
 * reading a `LocalTextStyle` it cannot see the placeholder's scope of. The caller hands the two
 * numbers over, exactly as it hands over the shape.
 */
@Composable
fun Modifier.shimmer(
    shape: PlaceholderShape,
    reason: DisplayedBody.Cover?,
    lineHeight: Float,
    textSize: Float,
    onClick: () -> Unit,
): Modifier {
    val tokens = LocalTulkkiColors.current
    val caption = stringResource(TranslationText.coverCaption(reason ?: DisplayedBody.Cover.TAP))
    val animatorsOn =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && ValueAnimator.areAnimatorsEnabled()
    val transition = rememberInfiniteTransition(label = "placeholder")
    val phase =
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(SHIMMER_PERIOD_MS, easing = LinearEasing)),
            label = "placeholderPhase",
        )
    return this
        .semantics { contentDescription = caption }
        .clickable(role = Role.Button, onClick = onClick)
        .drawWithCache {
            // The geometry and the two colours are cacheable: none of them changes with the phase, so
            // only the draw below re-runs on a frame. `phase.value` is read there and not here.
            val bars = Placeholder.bars(shape, size.width)
            val inset = Placeholder.BAR_INSET * density
            val thickness = (Placeholder.barHeight(lineHeight, textSize) - inset).coerceAtLeast(0f)
            val corner = CornerRadius(TulkkiShape.bubbleCorner.toPx())
            val base = tokens.shimmerBase
            val highlight = tokens.shimmerHighlight
            onDrawBehind {
                val travelled = if (animatorsOn) phase.value else 0f
                val (from, to) = Placeholder.highlightAt(travelled, size.width)
                // Three stops, so the band has no hard edge on either side of it; outside the
                // gradient's own segment the brush clamps to `shimmerBase`, which is what makes one
                // brush draw both the bars and the band.
                val brush =
                    Brush.linearGradient(
                        colors = listOf(base, highlight, base),
                        start = Offset(from, 0f),
                        end = Offset(to, 0f),
                    )
                for ((line, bar) in bars.withIndex()) {
                    drawRoundRect(
                        brush = brush,
                        topLeft = Offset(0f, line * lineHeight + inset / 2f),
                        size = Size(bar, thickness),
                        cornerRadius = corner,
                    )
                }
            }
        }
}
