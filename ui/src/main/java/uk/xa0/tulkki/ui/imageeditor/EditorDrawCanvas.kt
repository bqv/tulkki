package uk.xa0.tulkki.ui.imageeditor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput

/**
 * One committed stroke: the path, and the colour and width it was drawn with.
 *
 * <p>It replaces the vendored `medialib.models.PaintOptions`, which existed only to be the value of
 * `EditorDrawCanvas`'s `LinkedHashMap<Path, PaintOptions>`. A stroke now carries its own options, so
 * the map and the type both go.
 */
private class DrawStroke(val path: Path, val color: Int, val width: Float)

/**
 * The drawing surface's state: the committed strokes, the stroke in progress, and the current paint.
 *
 * <p>It is the Compose rewrite of the vendored `medialib.views.EditorDrawCanvas`, an Android `View`
 * the Compose editor could only host through an `AndroidView`. The drawing behaviour is that class's,
 * reproduced rather than redesigned: a freehand path, a dot on a tap, multi-touch strokes abandoned
 * rather than drawn, and `undo` dropping the last committed stroke. `toBitmap` is its `getBitmap`:
 * a white sheet with the background and every stroke on it.
 *
 * <p>The state is held by [ImageEditorActivity] and read by [EditorDrawSurface], which is the only
 * thing that needs to be a Composable.
 */
class EditorDrawState {
    private val committedStrokes = mutableStateListOf<DrawStroke>()

    // The path being drawn. `neverEqualPolicy` matters: a `Path` is mutable and compares by
    // reference, so the same instance re-assigned after a `quadTo` must still invalidate the draw.
    private var livePath by mutableStateOf(Path(), neverEqualPolicy())

    private var liveColor = 0
    private var liveWidth = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var startX = 0f
    private var startY = 0f

    private val paint =
        Paint().apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }

    /** The laid-out surface size, in pixels, which is the size [toBitmap] saves. */
    var canvasWidth = 0
        private set

    var canvasHeight = 0
        private set

    /** The colour the next stroke is drawn with; the vendor's `updateColor`. */
    fun updateColor(color: Int) {
        liveColor = color
    }

    /** The stroke width in pixels; the vendor's `updateBrushSize`, already scaled by its caller. */
    fun updateBrushWidth(width: Float) {
        liveWidth = width
    }

    fun setCanvasSize(width: Int, height: Int) {
        canvasWidth = width
        canvasHeight = height
    }

    /** The vendor's `undo`: drop the last committed stroke, and nothing when there is none. */
    fun undo() {
        if (committedStrokes.isNotEmpty()) {
            committedStrokes.removeAt(committedStrokes.lastIndex)
        }
    }

    /**
     * The vendor's `getBitmap`: a white ARGB sheet with the background and every committed stroke.
     *
     * <p>The stroke in progress is not included, exactly as the vendor's `draw(canvas)` included the
     * in-progress path only because it had not yet been committed on the save path.
     */
    fun toBitmap(background: Bitmap?): Bitmap {
        val bitmap = Bitmap.createBitmap(canvasWidth.coerceAtLeast(1), canvasHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        background?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        paint.style = Paint.Style.STROKE
        for (stroke in committedStrokes) {
            paint.color = stroke.color
            paint.strokeWidth = stroke.width
            canvas.drawPath(stroke.path, paint)
        }
        return bitmap
    }

    internal fun beginStroke(x: Float, y: Float) {
        livePath = Path().apply { moveTo(x, y) }
        lastX = x
        lastY = y
        startX = x
        startY = y
    }

    internal fun extendStroke(x: Float, y: Float) {
        livePath.quadTo(lastX, lastY, (x + lastX) / 2f, (y + lastY) / 2f)
        lastX = x
        lastY = y
        livePath = livePath
    }

    internal fun endStroke(wasMultitouch: Boolean) {
        if (!wasMultitouch) {
            livePath.lineTo(lastX, lastY)

            // Draw a dot on a plain tap, the vendor's own trick.
            if (startX == lastX && startY == lastY) {
                livePath.lineTo(lastX, lastY + 2f)
                livePath.lineTo(lastX + 1f, lastY + 2f)
                livePath.lineTo(lastX + 1f, lastY)
            }
        }

        committedStrokes.add(DrawStroke(livePath, liveColor, liveWidth))
        livePath = Path()
    }

    internal fun drawInto(background: Bitmap?, scope: DrawScope) {
        scope.drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            background?.let { native.drawBitmap(it, 0f, 0f, null) }
            for (stroke in committedStrokes) {
                paint.color = stroke.color
                paint.strokeWidth = stroke.width
                native.drawPath(stroke.path, paint)
            }
            paint.color = liveColor
            paint.strokeWidth = liveWidth
            native.drawPath(livePath, paint)
        }
    }
}

/**
 * The drawing surface itself: one `Canvas` over [state], with the vendored touch handling.
 *
 * <p>A stroke begins on the first finger down, follows single-pointer moves, and is abandoned (but
 * still consumed) when a second pointer appears, which is `EditorDrawCanvas.onTouchEvent`'s
 * `mWasMultitouch` rule.
 */
@Composable
fun EditorDrawSurface(state: EditorDrawState, background: Bitmap?, modifier: Modifier = Modifier) {
    Canvas(
        modifier =
            modifier.pointerInput(state) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    state.beginStroke(down.position.x, down.position.y)
                    down.consume()

                    var wasMultitouch = false
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1) {
                            wasMultitouch = true
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            break
                        }
                        if (!wasMultitouch) {
                            state.extendStroke(change.position.x, change.position.y)
                        }
                        change.consume()
                    }

                    state.endStroke(wasMultitouch)
                }
            },
    ) {
        state.drawInto(background, this)
    }
}
