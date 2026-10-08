/*
Copyright (c) 2023 Evan Wallace

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
*/
package uk.xa0.tulkki.data.utils

object ThumbHash {

    /**
     * Encodes an RGBA image to a ThumbHash. RGB should not be premultiplied by A.
     *
     * @param w    The width of the input image. Must be ≤100px.
     * @param h    The height of the input image. Must be ≤100px.
     * @param rgba The pixels in the input image, row-by-row. Must have w*h*4 elements.
     * @return The ThumbHash as a byte array.
     */
    @JvmStatic
    fun rgbaToThumbHash(w: Int, h: Int, rgba: ByteArray): ByteArray {
        // Encoding an image larger than 100x100 is slow with no benefit
        if (w > 100 || h > 100) throw IllegalArgumentException(w.toString() + "x" + h + " doesn't fit in 100x100")

        // Determine the average color
        var avgR = 0.0f
        var avgG = 0.0f
        var avgB = 0.0f
        var avgA = 0.0f
        var i = 0
        var j = 0
        while (i < w * h) {
            val alpha = (rgba[j + 3].toInt() and 255) / 255.0f
            avgR += alpha / 255.0f * (rgba[j].toInt() and 255)
            avgG += alpha / 255.0f * (rgba[j + 1].toInt() and 255)
            avgB += alpha / 255.0f * (rgba[j + 2].toInt() and 255)
            avgA += alpha
            i++
            j += 4
        }
        if (avgA > 0) {
            avgR /= avgA
            avgG /= avgA
            avgB /= avgA
        }

        val hasAlpha = avgA < w * h
        val lLimit = if (hasAlpha) 5 else 7 // Use fewer luminance bits if there's alpha
        val lx = Math.max(1, Math.round((lLimit * w).toFloat() / Math.max(w, h).toFloat()))
        val ly = Math.max(1, Math.round((lLimit * h).toFloat() / Math.max(w, h).toFloat()))
        val l = FloatArray(w * h) // luminance
        val p = FloatArray(w * h) // yellow - blue
        val q = FloatArray(w * h) // red - green
        val a = FloatArray(w * h) // alpha

        // Convert the image from RGBA to LPQA (composite atop the average color)
        i = 0
        j = 0
        while (i < w * h) {
            val alpha = (rgba[j + 3].toInt() and 255) / 255.0f
            val r = avgR * (1.0f - alpha) + alpha / 255.0f * (rgba[j].toInt() and 255)
            val g = avgG * (1.0f - alpha) + alpha / 255.0f * (rgba[j + 1].toInt() and 255)
            val b = avgB * (1.0f - alpha) + alpha / 255.0f * (rgba[j + 2].toInt() and 255)
            l[i] = (r + g + b) / 3.0f
            p[i] = (r + g) / 2.0f - b
            q[i] = r - g
            a[i] = alpha
            i++
            j += 4
        }

        // Encode using the DCT into DC (constant) and normalized AC (varying) terms
        val lChannel = Channel(Math.max(3, lx), Math.max(3, ly)).encode(w, h, l)
        val pChannel = Channel(3, 3).encode(w, h, p)
        val qChannel = Channel(3, 3).encode(w, h, q)
        val aChannel = if (hasAlpha) Channel(5, 5).encode(w, h, a) else null

        // Write the constants
        val isLandscape = w > h
        val header24 =
            Math.round(63.0f * lChannel.dc) or
                (Math.round(31.5f + 31.5f * pChannel.dc) shl 6) or
                (Math.round(31.5f + 31.5f * qChannel.dc) shl 12) or
                (Math.round(31.0f * lChannel.scale) shl 18) or
                (if (hasAlpha) 1 shl 23 else 0)
        val header16 =
            (if (isLandscape) ly else lx) or
                (Math.round(63.0f * pChannel.scale) shl 3) or
                (Math.round(63.0f * qChannel.scale) shl 9) or
                (if (isLandscape) 1 shl 15 else 0)
        val acStart = if (hasAlpha) 6 else 5
        val acCount = lChannel.ac.size + pChannel.ac.size + qChannel.ac.size + (aChannel?.ac?.size ?: 0)
        val hash = ByteArray(acStart + (acCount + 1) / 2)
        hash[0] = header24.toByte()
        hash[1] = (header24 shr 8).toByte()
        hash[2] = (header24 shr 16).toByte()
        hash[3] = header16.toByte()
        hash[4] = (header16 shr 8).toByte()
        if (aChannel != null) {
            hash[5] =
                (Math.round(15.0f * aChannel.dc) or (Math.round(15.0f * aChannel.scale) shl 4)).toByte()
        }

        // Write the varying factors
        var acIndex = 0
        acIndex = lChannel.writeTo(hash, acStart, acIndex)
        acIndex = pChannel.writeTo(hash, acStart, acIndex)
        acIndex = qChannel.writeTo(hash, acStart, acIndex)
        if (aChannel != null) aChannel.writeTo(hash, acStart, acIndex)
        return hash
    }

    /**
     * Decodes a ThumbHash to an RGBA image. RGB is not be premultiplied by A.
     *
     * @param hash The bytes of the ThumbHash.
     * @return The width, height, and pixels of the rendered placeholder image.
     */
    @JvmStatic
    fun thumbHashToRGBA(hash: ByteArray): Image {
        // Read the constants
        val header24 =
            (hash[0].toInt() and 255) or ((hash[1].toInt() and 255) shl 8) or ((hash[2].toInt() and 255) shl 16)
        val header16 = (hash[3].toInt() and 255) or ((hash[4].toInt() and 255) shl 8)
        val lDc = (header24 and 63).toFloat() / 63.0f
        val pDc = ((header24 shr 6) and 63).toFloat() / 31.5f - 1.0f
        val qDc = ((header24 shr 12) and 63).toFloat() / 31.5f - 1.0f
        val lScale = ((header24 shr 18) and 31).toFloat() / 31.0f
        val hasAlpha = (header24 shr 23) != 0
        val pScale = ((header16 shr 3) and 63).toFloat() / 63.0f
        val qScale = ((header16 shr 9) and 63).toFloat() / 63.0f
        val isLandscape = (header16 shr 15) != 0
        val lx = Math.max(3, if (isLandscape) (if (hasAlpha) 5 else 7) else header16 and 7)
        val ly = Math.max(3, if (isLandscape) header16 and 7 else (if (hasAlpha) 5 else 7))
        val aDc = if (hasAlpha) (hash[5].toInt() and 15).toFloat() / 15.0f else 1.0f
        val aScale = ((hash[5].toInt() shr 4) and 15).toFloat() / 15.0f

        // Read the varying factors (boost saturation by 1.25x to compensate for quantization)
        val acStart = if (hasAlpha) 6 else 5
        var acIndex = 0
        val lChannel = Channel(lx, ly)
        val pChannel = Channel(3, 3)
        val qChannel = Channel(3, 3)
        val aChannel = if (hasAlpha) Channel(5, 5) else null
        acIndex = lChannel.decode(hash, acStart, acIndex, lScale)
        acIndex = pChannel.decode(hash, acStart, acIndex, pScale * 1.25f)
        acIndex = qChannel.decode(hash, acStart, acIndex, qScale * 1.25f)
        if (aChannel != null) {
            aChannel.decode(hash, acStart, acIndex, aScale)
        }
        val lAc = lChannel.ac
        val pAc = pChannel.ac
        val qAc = qChannel.ac
        val aAc = aChannel?.ac

        // Decode using the DCT into RGB
        val ratio = thumbHashToApproximateAspectRatio(hash)
        val w = Math.round(if (ratio > 1.0f) 32.0f else 32.0f * ratio)
        val h = Math.round(if (ratio > 1.0f) 32.0f / ratio else 32.0f)
        val rgba = ByteArray(w * h * 4)
        val cxStop = Math.max(lx, if (hasAlpha) 5 else 3)
        val cyStop = Math.max(ly, if (hasAlpha) 5 else 3)
        val fx = FloatArray(cxStop)
        val fy = FloatArray(cyStop)
        var y = 0
        var pixel = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                var l = lDc
                var p = pDc
                var q = qDc
                var a = aDc

                // Precompute the coefficients
                for (cx in 0 until cxStop) {
                    fx[cx] = Math.cos(Math.PI / w * (x + 0.5f) * cx).toFloat()
                }
                for (cy in 0 until cyStop) {
                    fy[cy] = Math.cos(Math.PI / h * (y + 0.5f) * cy).toFloat()
                }

                // Decode L
                var cy = 0
                var index = 0
                while (cy < ly) {
                    val fy2 = fy[cy] * 2.0f
                    var cx = if (cy > 0) 0 else 1
                    while (cx * ly < lx * (ly - cy)) {
                        l += lAc[index] * fx[cx] * fy2
                        cx++
                        index++
                    }
                    cy++
                }

                // Decode P and Q
                cy = 0
                index = 0
                while (cy < 3) {
                    val fy2 = fy[cy] * 2.0f
                    var cx = if (cy > 0) 0 else 1
                    while (cx < 3 - cy) {
                        val f = fx[cx] * fy2
                        p += pAc[index] * f
                        q += qAc[index] * f
                        cx++
                        index++
                    }
                    cy++
                }

                // Decode A
                if (aAc != null) {
                    cy = 0
                    index = 0
                    while (cy < 5) {
                        val fy2 = fy[cy] * 2.0f
                        var cx = if (cy > 0) 0 else 1
                        while (cx < 5 - cy) {
                            a += aAc[index] * fx[cx] * fy2
                            cx++
                            index++
                        }
                        cy++
                    }
                }

                // Convert to RGB
                val b = l - 2.0f / 3.0f * p
                val r = (3.0f * l - b + q) / 2.0f
                val g = r - q
                rgba[pixel] = Math.max(0, Math.round(255.0f * Math.min(1.0f, r))).toByte()
                rgba[pixel + 1] = Math.max(0, Math.round(255.0f * Math.min(1.0f, g))).toByte()
                rgba[pixel + 2] = Math.max(0, Math.round(255.0f * Math.min(1.0f, b))).toByte()
                rgba[pixel + 3] = Math.max(0, Math.round(255.0f * Math.min(1.0f, a))).toByte()
                x++
                pixel += 4
            }
            y++
        }
        return Image(w, h, rgba)
    }

    /**
     * Extracts the average color from a ThumbHash. RGB is not be premultiplied by A.
     *
     * @param hash The bytes of the ThumbHash.
     * @return The RGBA values for the average color. Each value ranges from 0 to 1.
     */
    @JvmStatic
    fun thumbHashToAverageRGBA(hash: ByteArray): RGBA {
        val header =
            (hash[0].toInt() and 255) or ((hash[1].toInt() and 255) shl 8) or ((hash[2].toInt() and 255) shl 16)
        val l = (header and 63).toFloat() / 63.0f
        val p = ((header shr 6) and 63).toFloat() / 31.5f - 1.0f
        val q = ((header shr 12) and 63).toFloat() / 31.5f - 1.0f
        val hasAlpha = (header shr 23) != 0
        val a = if (hasAlpha) (hash[5].toInt() and 15).toFloat() / 15.0f else 1.0f
        val b = l - 2.0f / 3.0f * p
        val r = (3.0f * l - b + q) / 2.0f
        val g = r - q
        return RGBA(
            Math.max(0.0f, Math.min(1.0f, r)),
            Math.max(0.0f, Math.min(1.0f, g)),
            Math.max(0.0f, Math.min(1.0f, b)),
            a,
        )
    }

    /**
     * Extracts the approximate aspect ratio of the original image.
     *
     * @param hash The bytes of the ThumbHash.
     * @return The approximate aspect ratio (i.e. width / height).
     */
    @JvmStatic
    fun thumbHashToApproximateAspectRatio(hash: ByteArray): Float {
        val header = hash[3]
        val hasAlpha = (hash[2].toInt() and 0x80) != 0
        val isLandscape = (hash[4].toInt() and 0x80) != 0
        val lx = if (isLandscape) (if (hasAlpha) 5 else 7) else header.toInt() and 7
        val ly = if (isLandscape) header.toInt() and 7 else (if (hasAlpha) 5 else 7)
        return lx.toFloat() / ly.toFloat()
    }

    class Image(
        @JvmField var width: Int,
        @JvmField var height: Int,
        @JvmField var rgba: ByteArray,
    )

    class RGBA(
        @JvmField var r: Float,
        @JvmField var g: Float,
        @JvmField var b: Float,
        @JvmField var a: Float,
    )

    private class Channel(val nx: Int, val ny: Int) {

        var dc: Float = 0.0f
        var ac: FloatArray
        var scale: Float = 0.0f

        init {
            var n = 0
            for (cy in 0 until ny) {
                var cx = if (cy > 0) 0 else 1
                while (cx * ny < nx * (ny - cy)) {
                    n++
                    cx++
                }
            }
            ac = FloatArray(n)
        }

        fun encode(w: Int, h: Int, channel: FloatArray): Channel {
            var n = 0
            val fx = FloatArray(w)
            for (cy in 0 until ny) {
                var cx = 0
                while (cx * ny < nx * (ny - cy)) {
                    var f = 0.0f
                    for (x in 0 until w) {
                        fx[x] = Math.cos(Math.PI / w * cx * (x + 0.5f)).toFloat()
                    }
                    for (y in 0 until h) {
                        val fy = Math.cos(Math.PI / h * cy * (y + 0.5f)).toFloat()
                        for (x in 0 until w) {
                            f += channel[x + y * w] * fx[x] * fy
                        }
                    }
                    f /= w * h
                    if (cx > 0 || cy > 0) {
                        ac[n] = f
                        n++
                        scale = Math.max(scale, Math.abs(f))
                    } else {
                        dc = f
                    }
                    cx++
                }
            }
            if (scale > 0) {
                for (i in ac.indices) {
                    ac[i] = 0.5f + 0.5f / scale * ac[i]
                }
            }
            return this
        }

        fun decode(hash: ByteArray, start: Int, index: Int, scale: Float): Int {
            var idx = index
            for (i in ac.indices) {
                val data = hash[start + (idx shr 1)].toInt() shr ((idx and 1) shl 2)
                ac[i] = ((data and 15).toFloat() / 7.5f - 1.0f) * scale
                idx++
            }
            return idx
        }

        fun writeTo(hash: ByteArray, start: Int, index: Int): Int {
            var idx = index
            for (v in ac) {
                val at = start + (idx shr 1)
                hash[at] = (hash[at].toInt() or (Math.round(15.0f * v) shl ((idx and 1) shl 2))).toByte()
                idx++
            }
            return idx
        }
    }
}
