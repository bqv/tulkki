package uk.xa0.tulkki.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.TextView

/**
 * Draws text that must not be readable and must not be in the view tree.
 *
 * <p>This is one mechanism for one leak class, not four fixes. A covered bubble, a received
 * message's hidden second half, a reply quote's concealed half and the composer's reply preview all
 * need the same thing: the text must not be a `TextView`'s content, because whatever is in the
 * view can be read out of its accessibility node even while the screen shows a blur -
 * `docs/MIGRATION.md` "The leak surfaces" records exactly that on the covered bubble. A `RenderEffect` cannot
 * help, because it blurs what is drawn and the node still holds the string.
 *
 * <p>So the text is drawn once into a small bitmap and the bitmap is what the view shows. The view
 * itself is left with no text at all: there is no string in the tree, in a screenshot's metadata or
 * in an accessibility dump, only pixels that are too smeared to read. The view keeps no copy of it
 * either - the only thing it holds while the draw is pending is an identity token, and the string
 * lives in the posted runnable and nowhere else.
 *
 * <p>Cheap on purpose: the first line is drawn at [SCALE] of the final size and magnified
 * back up with filtering, so a full-size bitmap is never rasterised and the work is proportional to
 * a fraction of the line. The magnification is also where the unreadability comes from - glyph
 * strokes are thinner than a pixel at that scale, so what comes back is the shape and length of the
 * line, not its letters, on every API level (which also removes the API 31 dependency the previous
 * `RenderEffect` cover had).
 *
 * <p>Android types, no device state, and every colour it draws with comes from the view it is given,
 * so it inherits the theme in both light and dark.
 */
object ConcealedText {

    /**
     * How much smaller the first drawing is than what is shown. Ten, and then magnified back in two
     * filtered steps rather than one, because a single 1/6 magnification was measured on the
     * emulator and the words were still readable at 1:1 - each step is a low pass, and two of them
     * leave the line's shape and length while destroying the letters. Cheaper than drawing at 1:1
     * and blurring, and it works on every API level.
     */
    private const val SCALE = 10

    /**
     * Conceal the rest of this view's life.
     *
     * <p>Three cases, and the caption is the one caller-decided part of it:
     *
     * <ul>
     *   <li>`caption != null` - there is something to say that is *not* the original
     *       ("not translated yet", or why it cannot be translated). It is shown readably; a reason
     *       nobody can read is not an answer.
     *   <li>`original` is blank or null - there is nothing to draw, so the view is a bare
     *       strip: no text, no bitmap.
     *   <li>otherwise - the first line of `original` is drawn small, magnified back and shown
     *       as the view's own content. The string never enters the view.
     * </ul>
     *
     * @param view the view that must stop carrying the text
     * @param original the text being concealed, or `null` when there is none to draw
     * @param caption a readable caption instead of a smear, or `null`
     */
    @JvmStatic
    fun apply(view: TextView?, original: String?, caption: String?) {
        if (view == null) {
            return
        }
        view.setTag(R.id.tulkki_concealed_source, null)
        view.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null)
        if (caption != null) {
            view.setText(caption)
            return
        }
        if (original == null || original.trim().isEmpty()) {
            view.setText(null)
            return
        }
        // Out of the view before anything is drawn: whatever happens next, the string is not in the
        // tree while the bitmap is built, and the bitmap is what the next layout measures. The width
        // is read first, because clearing the text is what takes it away.
        val measured = availableWidth(view)
        view.setText(null)
        // The guard against drawing one message's shape under another one's bubble is this token, and
        // it is deliberately an object rather than the text: a tag that held the string would keep
        // the concealed original alive inside the view for as long as the concealment lasted, which
        // is the one thing this class exists to prevent. The string is held by the posted Runnable
        // below and by nothing else, and the tag is released before anything is drawn.
        val token = Any()
        view.setTag(R.id.tulkki_concealed_source, token)
        view.post { draw(view, token, original, measured) }
    }

    /** Put a view back to no concealment at all: no text, no smear. For a recycled binding. */
    @JvmStatic
    fun clear(view: TextView?) {
        if (view == null) {
            return
        }
        view.setTag(R.id.tulkki_concealed_source, null)
        view.setText(null)
        view.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null)
    }

    private fun draw(view: TextView, token: Any, original: String, measured: Int) {
        if (view.getTag(R.id.tulkki_concealed_source) !== token) {
            // The view was rebound to another message before this ran; drawing now would put one
            // message's shape under another one's bubble.
            return
        }
        // Released before the bitmap is built: from here the only copies of the string are this
        // parameter and the ellipsised line below, and both end with the call.
        view.setTag(R.id.tulkki_concealed_source, null)
        // The width read before the text was cleared if there was one, else the parent's - which is
        // the bubble's content width and is laid out by the time a posted runnable runs.
        val available = if (measured > 0) measured else availableWidth(view)
        if (available <= 0) {
            // No width to ellipsise to, and guessing one would make the bubble the wrong size. The
            // bare strip is the safe fallback, and it leaks nothing.
            return
        }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        paint.setTextSize(view.getTextSize())
        paint.setColor(view.getCurrentTextColor())
        val typeface: Typeface? = view.getTypeface()
        if (typeface != null) {
            paint.setTypeface(typeface)
        }
        val line: CharSequence =
            TextUtils.ellipsize(original, paint, available.toFloat(), TextUtils.TruncateAt.END)
        val metrics = paint.getFontMetrics()
        val lineHeight = Math.max(1, Math.round(metrics.descent - metrics.ascent))
        val lineWidth =
            Math.max(1, Math.min(available, Math.ceil(paint.measureText(line, 0, line.length).toDouble()).toInt()))
        val smallWidth = Math.max(1, lineWidth / SCALE)
        val smallHeight = Math.max(1, lineHeight / SCALE)

        val small = Bitmap.createBitmap(smallWidth, smallHeight, Bitmap.Config.ARGB_8888)
        val smallCanvas = Canvas(small)
        smallCanvas.scale(1f / SCALE, 1f / SCALE)
        smallCanvas.drawText(line, 0, line.length, 0f, -metrics.ascent, paint)

        // Magnified back in two filtered steps on purpose: each one is a low pass, and one step was
        // measured legible. The line's shape and length survive; the letters do not.
        val filter = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val midWidth = Math.max(1, lineWidth / 3)
        val midHeight = Math.max(1, lineHeight / 3)
        val mid = Bitmap.createBitmap(midWidth, midHeight, Bitmap.Config.ARGB_8888)
        Canvas(mid)
            .drawBitmap(
                small,
                Rect(0, 0, smallWidth, smallHeight),
                Rect(0, 0, midWidth, midHeight),
                filter)
        small.recycle()

        val smear = Bitmap.createBitmap(lineWidth, lineHeight, Bitmap.Config.ARGB_8888)
        val smearCanvas = Canvas(smear)
        smearCanvas.drawBitmap(
            mid,
            Rect(0, 0, midWidth, midHeight),
            Rect(0, 0, lineWidth, lineHeight),
            filter)
        mid.recycle()
        softenEdges(smear, smearCanvas)

        // DENSITY_NONE so the drawable is drawn at these pixel sizes and not scaled again by the
        // display density; its intrinsic size is then exactly the smear's.
        smear.setDensity(Bitmap.DENSITY_NONE)
        val drawable = BitmapDrawable(view.getResources(), smear)
        drawable.setFilterBitmap(true)
        view.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null)
    }

    /**
     * How far out the smear stays fully opaque, as a fraction of its own radius. The band outside it
     * eases to nothing, so the soft part is a border rather than the whole shape: a ramp that started
     * fading at the centre would turn a wide line into a faint smudge instead of smudged text.
     */
    private const val EDGE_PLATEAU = 0.65f

    /**
     * Dissolve the smear's own edges, so the blur fades into the bubble instead of stopping at a
     * rectangle. The blur itself is untouched: this changes the alpha of what was already drawn, and
     * nothing else.
     *
     * <p>The mask is a radial alpha ramp - the owner asked for the soft version, and a hard rounded
     * cut is not it - scaled to the bitmap's aspect. A radial gradient is circular and a text line is
     * wide and short, so an unscaled one inscribed in the bitmap would eat the ends of the line; the
     * [Matrix] makes the falloff follow the line's shape instead. It is applied with
     * `DST_IN`, which multiplies the destination's alpha by the mask's, so no colour is added
     * and the letters cannot come back.
     *
     * <p>Dithering is on: a smooth 8-bit alpha ramp over a wide, flat gradient bands, and the blur
     * underneath hides most of it but not all.
     */
    private fun softenEdges(smear: Bitmap, canvas: Canvas) {
        val halfWidth = smear.getWidth() / 2f
        val halfHeight = smear.getHeight() / 2f
        val falloff =
            RadialGradient(
                0f,
                0f,
                1f,
                intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0x00FFFFFF),
                floatArrayOf(0f, EDGE_PLATEAU, 1f),
                Shader.TileMode.CLAMP)
        val shape = Matrix()
        shape.setScale(halfWidth, halfHeight)
        shape.postTranslate(halfWidth, halfHeight)
        falloff.setLocalMatrix(shape)
        val mask = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        mask.setShader(falloff)
        mask.setXfermode(PorterDuffXfermode(PorterDuff.Mode.DST_IN))
        canvas.drawRect(0f, 0f, smear.getWidth().toFloat(), smear.getHeight().toFloat(), mask)
    }

    /**
     * The width the first line is ellipsised to. The view itself is empty by the time this runs (its
     * text was cleared, which is the point), so it measures zero; the parent is the honest source,
     * because it is the bubble's content width. Zero means "not known", and the caller falls back to
     * the strip rather than guessing.
     */
    private fun availableWidth(view: TextView): Int {
        val padding = view.getPaddingLeft() + view.getPaddingRight()
        var width = view.getWidth() - padding
        if (width > 0) {
            return width
        }
        val parent: ViewParent? = view.getParent()
        if (parent is View) {
            width = parent.getWidth() - padding
            val params = view.getLayoutParams()
            if (params is ViewGroup.MarginLayoutParams) {
                width -= params.leftMargin + params.rightMargin
            }
        }
        return width
    }
}
