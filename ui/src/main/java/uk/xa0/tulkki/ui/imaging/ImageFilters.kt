package uk.xa0.tulkki.ui.imaging

/**
 * The six image filters, absorbed from `src/main/jni/NativeImageProcessor.cpp` (the C and its JNI
 * are deleted; this file is the only implementation left).
 *
 * The port is line-by-line faithful to the C, and it has to be, because the golden CRCs produced by
 * the C driver reproduce exactly over both the 456-case suite (pinned by
 * `app/src/test/.../ImageFiltersGoldenTest`) and the exhaustive 256³ sweep (the verification record
 * is `.toolchain/ndk/improc/README.md`). Every place where the C relies on a C-specific rule is
 * called out below with `C:`.
 *
 *  - `int` in the C is 32-bit two's complement and `>>` is an arithmetic shift on gcc/clang, so
 *    `shr` is the exact counterpart. `(unsigned int) x & 0xFF` is just `x and 0xFF`, because the
 *    conversion to unsigned is modulo 2^32 and only the low byte survives the mask.
 *  - `float`/`double` are IEEE-754 binary32/binary64 in C and in Kotlin, and both round to nearest
 *    even, so the float arithmetic is identical operation for operation.
 *  - Two subtleties are load-bearing and are reproduced deliberately:
 *      * in `contrast()` the whole expression is evaluated in **double** (the literals are
 *        `255.0`/`0.5`) and then stored back into a **float** before `(int)` truncates it — so the
 *        result is `(...).toFloat().toInt()`, never a direct `Double.toInt()`;
 *      * in `HSLtoRGB_Subfunction()` the `.66666` literal has **no** `f` suffix, so that one branch
 *        is double arithmetic while the other three are float. The `f` suffix is kept off it below.
 *  - `(unsigned int) someFloat` truncates toward zero, exactly like `Float.toInt()`/`Double.toInt()`.
 *    The C only ever converts non-negative values, so there is no UB/wraparound case to mirror.
 *  - `saturation()`'s inner `for (int i = 0; i < 3; i++)` shadows the outer loop variable in the C.
 *    The shadow is kept verbatim (with a NAME_SHADOWING suppression); the outer `i` is unaffected,
 *    which is what the C does too.
 *
 * The `in`/`out` parameters are `IntArray` and are mutated in place, like the C `int *pixels`.
 */
@Suppress("NAME_SHADOWING")
object ImageFilters {

    private const val ALPHA_MASK = 0xFF000000.toInt() // C: 0xFF000000
    private const val RED_MASK = 0x00FF0000
    private const val GREEN_MASK = 0x0000FF00
    private const val BLUE_MASK = 0x000000FF

    // ----------------------------------------------------------------------------------------
    // C: static void HSLtoRGB_Subfunction(unsigned int &c, const float &temp1,
    //                                     const float &temp2, const float &temp3)
    // The reference parameter is a return value here: `c` is assigned exactly once per call.
    // ----------------------------------------------------------------------------------------
    private fun HSLtoRGB_Subfunction(temp1: Float, temp2: Float, temp3: Float): Int {
        return if ((temp3 * 6) < 1)
            // C: (unsigned int) ((temp2 + (temp1 - temp2) * 6 * temp3) * 100)  -- all float
            ((temp2 + (temp1 - temp2) * 6 * temp3) * 100).toInt()
        else if ((temp3 * 2) < 1)
            // C: (unsigned int) (temp1 * 100)
            (temp1 * 100).toInt()
        else if ((temp3 * 3) < 2)
            // C: (unsigned int) ((temp2 + (temp1 - temp2) * (.66666 - temp3) * 6) * 100)
            // .66666 has NO `f` suffix in the C, so this whole branch is DOUBLE arithmetic.
            ((temp2 + (temp1 - temp2) * (0.66666 - temp3) * 6) * 100).toInt()
        else
            // C: (unsigned int) (temp2 * 100)
            (temp2 * 100).toInt()
    }

    // ----------------------------------------------------------------------------------------
    // C: void saturation(int *pixels, float level, int width, int height)
    // ----------------------------------------------------------------------------------------
    fun doSaturation(pixels: IntArray, level: Float, width: Int, height: Int) {
        var r: Int
        var g: Int
        var b: Int

        var temp1: Float
        var temp2: Float
        var temp3: Float

        var rPercent: Float
        var gPercent: Float
        var bPercent: Float

        var maxColor: Float
        var minColor: Float
        var L: Float
        var S: Float
        var H: Float

        for (i in 0 until width * height) {
            r = (pixels[i] shr 16) and 0xFF
            g = (pixels[i] shr 8) and 0xFF
            b = pixels[i] and 0xFF

            rPercent = r.toFloat() / 255
            gPercent = g.toFloat() / 255
            bPercent = b.toFloat() / 255

            maxColor = 0f
            if ((rPercent >= gPercent) && (rPercent >= bPercent)) {
                maxColor = rPercent
            }
            if ((gPercent >= rPercent) && (gPercent >= bPercent))
                maxColor = gPercent
            if ((bPercent >= rPercent) && (bPercent >= gPercent))
                maxColor = bPercent

            minColor = 0f
            if ((rPercent <= gPercent) && (rPercent <= bPercent))
                minColor = rPercent
            if ((gPercent <= rPercent) && (gPercent <= bPercent))
                minColor = gPercent
            if ((bPercent <= rPercent) && (bPercent <= gPercent))
                minColor = bPercent

            L = 0f
            S = 0f
            H = 0f

            L = (maxColor + minColor) / 2

            if (maxColor == minColor) {
                S = 0f
                H = 0f
            } else {
                if (L < .50f) {
                    S = (maxColor - minColor) / (maxColor + minColor)
                } else {
                    S = (maxColor - minColor) / (2 - maxColor - minColor)
                }
                if (maxColor == rPercent) {
                    H = (gPercent - bPercent) / (maxColor - minColor)
                }
                if (maxColor == gPercent) {
                    H = 2 + (bPercent - rPercent) / (maxColor - minColor)
                }
                if (maxColor == bPercent) {
                    H = 4 + (rPercent - gPercent) / (maxColor - minColor)
                }
            }
            // C: S = (unsigned int) (S * 100);  -- float, truncated, then stored back into float
            S = (S * 100).toInt().toFloat()
            // C: L = (unsigned int) (L * 100);
            L = (L * 100).toInt().toFloat()
            H = H * 60
            if (H < 0)
                H += 360

            S *= level
            if (S > 100) {
                S = 100f
            } else if (S < 0) {
                S = 0f
            }

            L = L / 100
            S = S / 100
            H = H / 360

            if (S == 0f) {
                // C: r/g/b are `unsigned int` on the left of a float expression
                r = (L * 100).toInt()
                g = (L * 100).toInt()
                b = (L * 100).toInt()
            } else {
                temp1 = 0f
                if (L < .50f) {
                    temp1 = L * (1 + S)
                } else {
                    temp1 = L + S - (L * S)
                }

                temp2 = 2 * L - temp1

                temp3 = 0f
                // The inner `i` shadows the outer one in the C too. Kept verbatim; the outer `i`
                // is untouched by the inner loop, which is exactly what the C does.
                for (i in 0 until 3) {
                    when (i) {
                        0 -> { // red
                            temp3 = H + .33333f
                            if (temp3 > 1)
                                temp3 -= 1
                            r = HSLtoRGB_Subfunction(temp1, temp2, temp3)
                        }
                        1 -> { // green
                            temp3 = H
                            g = HSLtoRGB_Subfunction(temp1, temp2, temp3)
                        }
                        2 -> { // blue
                            temp3 = H - .33333f
                            if (temp3 < 0)
                                temp3 += 1
                            b = HSLtoRGB_Subfunction(temp1, temp2, temp3)
                        }
                        else -> {
                        }
                    }
                }
            }
            r = ((r.toFloat() / 100) * 255).toInt()
            g = ((g.toFloat() / 100) * 255).toInt()
            b = ((b.toFloat() / 100) * 255).toInt()

            pixels[i] = pixels[i] and ALPHA_MASK or ((r shl 16) and RED_MASK) or
                ((g shl 8) and GREEN_MASK) or
                (b and BLUE_MASK)
        }
    }

    // ----------------------------------------------------------------------------------------
    // C: static void colorOverlay(int *pixels, int depth, float red, float green, float blue,
    //                             int width, int height)
    // ----------------------------------------------------------------------------------------
    fun doColorOverlay(
        pixels: IntArray,
        depth: Int,
        red: Float,
        green: Float,
        blue: Float,
        width: Int,
        height: Int,
    ) {
        var r: Float
        var g: Float
        var b: Float

        for (i in 0 until width * height) {
            r = ((pixels[i] shr 16) and 0xFF).toFloat()
            g = ((pixels[i] shr 8) and 0xFF).toFloat()
            b = (pixels[i] and 0xFF).toFloat()

            // C: the channels are `float` here, and `depth * red` is int * float -> float
            r += (depth * red)
            if (r > 255) {
                r = 255f
            }

            g += (depth * green)
            if (g > 255) {
                g = 255f
            }

            b += (depth * blue)
            if (b > 255) {
                b = 255f
            }

            pixels[i] = pixels[i] and ALPHA_MASK or ((r.toInt() shl 16) and RED_MASK) or
                ((g.toInt() shl 8) and GREEN_MASK) or
                (b.toInt() and BLUE_MASK)
        }
    }

    // ----------------------------------------------------------------------------------------
    // C: static void contrast(int width, int height, int *pixels, float value)
    // ----------------------------------------------------------------------------------------
    fun doContrast(pixels: IntArray, value: Float, width: Int, height: Int) {
        var red: Float
        var green: Float
        var blue: Float

        for (i in 0 until width * height) {
            red = ((pixels[i] shr 16) and 0xFF).toFloat()
            green = ((pixels[i] shr 8) and 0xFF).toFloat()
            blue = (pixels[i] and 0xFF).toFloat()

            // C: every literal here is a double literal, so the expression is evaluated in
            // double and then stored back into a `float` variable. The .toFloat() is the store.
            red = (((((red / 255.0) - 0.5) * value) + 0.5) * 255.0).toFloat()
            green = (((((green / 255.0) - 0.5) * value) + 0.5) * 255.0).toFloat()
            blue = (((((blue / 255.0) - 0.5) * value) + 0.5) * 255.0).toFloat()

            if (red > 255)
                red = 255f
            else if (red < 0)
                red = 0f

            if (green > 255)
                green = 255f
            else if (green < 0)
                green = 0f

            if (blue > 255)
                blue = 255f
            else if (blue < 0)
                blue = 0f

            val r = red.toInt()
            val g = green.toInt()
            val b = blue.toInt()
            pixels[i] = pixels[i] and ALPHA_MASK or ((r shl 16) and RED_MASK) or
                ((g shl 8) and GREEN_MASK) or
                (b and BLUE_MASK)
        }
    }

    // ----------------------------------------------------------------------------------------
    // C: static void brightness(int width, int height, int *pixels, int value)
    // ----------------------------------------------------------------------------------------
    fun doBrightness(pixels: IntArray, value: Int, width: Int, height: Int) {
        var red: Int
        var green: Int
        var blue: Int

        for (i in 0 until width * height) {
            red = (pixels[i] shr 16) and 0xFF
            green = (pixels[i] shr 8) and 0xFF
            blue = pixels[i] and 0xFF

            red += value
            green += value
            blue += value

            if (red > 255)
                red = 255
            else if (red < 0)
                red = 0

            if (green > 255)
                green = 255
            else if (green < 0)
                green = 0

            if (blue > 255)
                blue = 255
            else if (blue < 0)
                blue = 0

            pixels[i] = pixels[i] and ALPHA_MASK or ((red shl 16) and RED_MASK) or
                ((green shl 8) and GREEN_MASK) or
                (blue and BLUE_MASK)
        }
    }

    // ----------------------------------------------------------------------------------------
    // C: static void applyChannelCurves(int width, int height, int *pixels, int *r, int *g,
    //                                   int *b)
    //
    // A NULL channel is a reachable upstream bug the port preserves on purpose: the C shifts the
    // *pixel* rather than the channel (`(pixels[i] << 16) & 0x00FF0000`), so a null red puts the
    // blue byte in the red slot. `ToneCurveSubFilter` may supply only some of r/g/b and
    // `ImageProcessor.applyCurves` passes the rest as null. Fixing it would be a behaviour change
    // and needs its own decision; the golden pins it as it is.
    // ----------------------------------------------------------------------------------------
    fun applyChannelCurves(
        pixels: IntArray,
        r: IntArray?,
        g: IntArray?,
        b: IntArray?,
        width: Int,
        height: Int,
    ) {
        var red: Int
        var green: Int
        var blue: Int
        var alpha: Int

        for (i in 0 until width * height) {
            if (r != null)
                red = (r[(pixels[i] shr 16) and 0xFF] shl 16) and RED_MASK
            else
                red = (pixels[i] shl 16) and RED_MASK

            if (g != null)
                green = (g[(pixels[i] shr 8) and 0xFF] shl 8) and GREEN_MASK
            else
                green = (pixels[i] shl 8) and GREEN_MASK

            if (b != null)
                blue = b[pixels[i] and 0xFF] and BLUE_MASK
            else
                blue = pixels[i] and BLUE_MASK

            alpha = pixels[i] and ALPHA_MASK

            pixels[i] = alpha or red or green or blue
        }
    }

    // ----------------------------------------------------------------------------------------
    // C: static void applyRGBCurve(int width, int height, int *pixels, int *rgb)
    // ----------------------------------------------------------------------------------------
    fun applyRGBCurve(pixels: IntArray, rgb: IntArray, width: Int, height: Int) {
        val r = IntArray(256)
        val g = IntArray(256)
        val b = IntArray(256)
        for (i in 0 until 256) {
            r[i] = (rgb[i] shl 16) and RED_MASK
            g[i] = (rgb[i] shl 8) and GREEN_MASK
            b[i] = rgb[i] and BLUE_MASK
        }

        for (i in 0 until width * height) {
            pixels[i] =
                ALPHA_MASK and pixels[i] or r[(pixels[i] shr 16) and 0xFF] or
                g[(pixels[i] shr 8) and 0xFF] or
                b[pixels[i] and 0xFF]
        }
    }
}
