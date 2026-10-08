package uk.xa0.tulkki.app

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import uk.xa0.tulkki.ui.imaging.ImageFilters

/**
 * The absorbed image filters run against the C's own 456-case golden.
 *
 * <p>{@code app/src/test/resources/image/filters-456.txt} is the output of the deleted
 * {@code src/main/jni/NativeImageProcessor.cpp}, captured before anything moved. This test rebuilds
 * every case exactly as the C driver did - same deterministic input sets, same curve tables, same
 * parameters - runs {@link ImageFilters} on it and compares both the input digest (so a generator
 * divergence is distinguishable from an arithmetic one) and the output digest. It is the demotion's
 * cell: it reddens if the port drifts from the C in any bit.
 *
 * <p>The 256³ exhaustive sweep is deliberately not data in the tree (the resource's header says
 * re-capturing it as a digest is this commit's own step); it lives in
 * {@code .toolchain/ndk/improc/README.md} as a verdict and its C/JVM outputs are compared there. Do
 * not read a green run of this test as the whole gate: two single-token {@code doSaturation} float
 * mutations score 456/456 and are caught only by that sweep.
 */
class ImageFiltersGoldenTest {

    private companion object {

        const val RESOURCE = "/image/filters-456.txt"

        /** Input-set sizes, in the C driver's order. */
        val SET_N = intArrayOf(256, 16, 8, 256, 256, 256, 256, 16)

        val CURVE_NAMES = arrayOf("identity", "inverted", "random", "zero")

        val CH_NAMES = arrayOf("III", "INV", "IRI", "NUL", "R--", "RNR")

        /** How each channel-curve mode builds its three tables; 0 means NULL. */
        val CH_KINDS =
            arrayOf(
                intArrayOf(1, 1, 1),
                intArrayOf(2, 1, 1),
                intArrayOf(1, 3, 1),
                intArrayOf(0, 0, 0),
                intArrayOf(3, 0, 0),
                intArrayOf(3, 0, 3),
            )

        /** The counts the capture recorded: 40 + 56 + 80 + 72 + 136 + 72 = 456. */
        fun expected(): Map<String, Int> {
            val counts = LinkedHashMap<String, Int>()
            counts["applyRGBCurve"] = 40
            counts["applyChannelCurves"] = 56
            counts["doBrightness"] = 80
            counts["doContrast"] = 72
            counts["doColorOverlay"] = 136
            counts["doSaturation"] = 72
            return counts
        }

        fun lines(): List<String> {
            val lines = ArrayList<String>()
            val input = ImageFiltersGoldenTest::class.java.getResourceAsStream(RESOURCE)
            assertNotNull("the golden must be on the test classpath: " + RESOURCE, input)
            input!!.use { stream ->
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
                    var line: String? = reader.readLine()
                    while (line != null) {
                        lines.add(line)
                        line = reader.readLine()
                    }
                }
            }
            assertTrue(
                "the capture must carry the C's FP model header",
                lines.any { it.startsWith("# FLT_EVAL_METHOD=") })
            return lines
        }
    }

    @Test
    fun everyGoldenCaseMatchesTheAbsorbedFilters() {
        val seen = LinkedHashMap<String, Int>()
        var total = 0
        for (line in lines()) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue
            }
            // Kotlin's split needs a non-negative limit: 0 is "no limit", and like Java's -1 it
            // keeps a trailing empty field rather than dropping it (so a malformed line still
            // fails the six-field assertion below, as the Java original did).
            val fields = line.split('|', ignoreCase = false, limit = 0)
            assertEquals(
                "a case line is function|case|geometry|params|input|output: " + line,
                6,
                fields.size)

            val function = fields[0]
            val setId = fields[1].substring(1).toInt()
            val geometry = fields[2].split('x')
            val width = geometry[0].toInt()
            val height = geometry[1].toInt()
            val params = fields[3]

            val pixels = buildSet(setId)
            assertEquals(
                "a case's geometry must cover its input set: " + line,
                width * height,
                pixels.size)
            assertEquals("input digest diverged: " + line, fields[4], hx(crc32(pixels)))

            run(function, params, pixels, width, height)
            assertEquals("output digest differs from the C: " + line, fields[5], hx(crc32(pixels)))

            val before = seen[function]
            seen[function] = if (before == null) 1 else before + 1
            total++
        }
        assertEquals("the 456-case set", 456, total)
        assertEquals("the six filters, and nothing else, are the port's surface", expected(), seen)
    }

    /**
     * The NULL-channel quirk, as its own cell. The C computes a NULL channel by shifting the
     * <em>pixel</em>, not the channel, so a null red puts the blue byte in the red slot
     * ({@code (pixels[i] << 16) & 0x00FF0000}). It is reachable through {@code ToneCurveSubFilter}
     * and it is a deliberate upstream bug the port preserves; this pins it in one line rather than
     * only inside the mixed-null {@code NUL}/{@code R--}/{@code RNR} modes above.
     */
    @Test
    fun aNullChannelShiftsThePixelRatherThanTheChannel() {
        val identity = IntArray(256)
        for (i in 0 until 256) {
            identity[i] = i
        }

        val pixels = intArrayOf(0x00123456)
        ImageFilters.applyChannelCurves(pixels, null, identity, identity, 1, 1)

        assertEquals("null red puts the blue byte 0x56 in the red slot", 0x00563456, pixels[0])
        assertNotEquals("and it is not the channel the null stands for", 0x00123456, pixels[0])
    }

    // ------------------------------------------------------------------------------- dispatch

    private fun run(function: String, params: String, pixels: IntArray, width: Int, height: Int) {
        when (function) {
            "applyRGBCurve" -> {
                val name = between(params, "tab=", "-")
                ImageFilters.applyRGBCurve(pixels, curveTable(name), width, height)
                return
            }
            "applyChannelCurves" -> {
                val mode = channelMode(params)
                val index = indexOf(CH_NAMES, mode)
                if (index < 0) {
                    fail("unknown channel-curve mode: " + mode)
                }
                val kinds = CH_KINDS[index]
                ImageFilters.applyChannelCurves(
                    pixels, byKind(kinds[0]), byKind(kinds[1]), byKind(kinds[2]), width, height)
                return
            }
            "doBrightness" -> {
                ImageFilters.doBrightness(
                    pixels, params.substring("v=".length).toInt(), width, height)
                return
            }
            "doContrast" -> {
                ImageFilters.doContrast(pixels, bits(params, "val="), width, height)
                return
            }
            "doColorOverlay" -> {
                val parts = params.split(',')
                val depth = parts[0].substring("d=".length).toInt()
                ImageFilters.doColorOverlay(
                    pixels,
                    depth,
                    floatBits(parts[1].substring("rgb=".length)),
                    floatBits(parts[2]),
                    floatBits(parts[3]),
                    width,
                    height)
                return
            }
            "doSaturation" -> {
                ImageFilters.doSaturation(pixels, bits(params, "lvl="), width, height)
                return
            }
            else -> fail("unknown function in the golden: " + function)
        }
    }

    /** The hex payload between a prefix and a following marker, e.g. {@code tab=identity-f0e359bb}. */
    private fun between(text: String, prefix: String, marker: String): String {
        val start = text.indexOf(prefix) + prefix.length
        if (start < prefix.length) {
            fail("the parameter does not carry " + prefix + ": " + text)
        }
        val end = text.indexOf(marker, start)
        return if (end < 0) text.substring(start) else text.substring(start, end)
    }

    /** A channel-curve mode name; one of them carries hyphens ({@code R--}), so it is matched whole. */
    private fun channelMode(params: String): String {
        for (name in CH_NAMES) {
            if (params.startsWith("mode=" + name + "-")) {
                return name
            }
        }
        fail("unknown channel-curve mode in: " + params)
        throw AssertionError("unreachable")
    }

    /** {@code val=} / {@code lvl=} carry the raw float bits as hex, exactly as the wire does. */
    private fun bits(params: String, prefix: String): Float =
        Float.fromBits(java.lang.Long.parseUnsignedLong(params.substring(prefix.length), 16).toInt())

    private fun floatBits(hex: String): Float =
        Float.fromBits(java.lang.Long.parseUnsignedLong(hex, 16).toInt())

    private fun indexOf(names: Array<String>, name: String): Int {
        for (i in names.indices) {
            if (names[i] == name) {
                return i
            }
        }
        return -1
    }

    // --------------------------------------------------------------------------------- inputs

    /** splitmix64, byte for byte the C driver's PRNG. */
    private class Rng {
        private var state = 0L

        fun seed(value: Long) {
            state = value
        }

        fun next(): Long {
            state += -7046029254386353131L // 0x9E3779B97F4A7C15
            var z = state
            z = (z xor (z ushr 30)) * -4658895280553007687L // 0xBF58476D1CE4E5B9
            z = (z xor (z ushr 27)) * -7723592293110705685L // 0x94D049BB133111EB
            return z xor (z ushr 31)
        }

        fun u32(): Int = (next() ushr 32).toInt()
    }

    private fun buildSet(setId: Int): IntArray {
        val n = SET_N[setId]
        val pixels = IntArray(n)
        val rng = Rng()
        when (setId) {
            0 -> {
                for (i in 0 until n) {
                    pixels[i] = 0xFF000000.toInt() or (i shl 16) or (i shl 8) or i
                }
                return pixels
            }
            1 -> {
                val table =
                    intArrayOf(
                        0x00000000, 0xFF000000.toInt(), 0x00FFFFFF, 0xFFFFFFFF.toInt(),
                        0x00FF0000, 0x0000FF00, 0x000000FF, 0xFFFF0000.toInt(),
                        0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0x7F7F7F7F, 0x80808080.toInt(),
                        0x01010101, 0xFEFEFEFE.toInt(), 0x7FFFFFFF, 0x80000000.toInt())
                System.arraycopy(table, 0, pixels, 0, n)
                return pixels
            }
            2 -> {
                val table =
                    intArrayOf(
                        0x00000000, 0xFF000000.toInt(), 0x00FFFFFF, 0xFFFFFFFF.toInt(),
                        0x00000001, 0x00FFFFFE, 0xFF000001.toInt(), 0xFFFFFFFE.toInt())
                System.arraycopy(table, 0, pixels, 0, n)
                return pixels
            }
            3 -> {
                rng.seed(0x0123456789ABCDEFL)
                for (i in 0 until n) {
                    pixels[i] = rng.u32()
                }
                return pixels
            }
            4 -> {
                rng.seed(-2401053089206453570L) // 0xDEADBEEFCAFEBABE
                for (i in 0 until n) {
                    pixels[i] = rng.u32() and 0x00FFFFFF
                }
                return pixels
            }
            5 -> {
                rng.seed(0x0F1E2D3C4B5A6978L)
                for (i in 0 until n) {
                    pixels[i] = rng.u32() or 0xFF000000.toInt()
                }
                return pixels
            }
            6 -> {
                for (i in 0 until n) {
                    val value = (i * 37) and 0xFF
                    pixels[i] = (i shl 24) or (value shl 16) or (value shl 8) or value
                }
                return pixels
            }
            7 -> {
                var k = 0
                for (alpha in 0 until 2) {
                    for (corner in 0 until 8) {
                        val a = if (alpha != 0) 0xFF else 0x00
                        val r = if ((corner and 4) != 0) 0xFF else 0x00
                        val g = if ((corner and 2) != 0) 0xFF else 0x00
                        val b = if ((corner and 1) != 0) 0xFF else 0x00
                        pixels[k++] = (a shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
                return pixels
            }
            else -> throw IllegalArgumentException("unknown set " + setId)
        }
    }

    // --------------------------------------------------------------------------- curve tables

    private fun curveTable(name: String): IntArray {
        val index = indexOf(CURVE_NAMES, name)
        if (index < 0) {
            throw IllegalArgumentException("unknown curve table " + name)
        }
        return when (index) {
            0 -> tableIdentity()
            1 -> tableInverted()
            2 -> tableRandom()
            else -> IntArray(256)
        }
    }

    private fun tableIdentity(): IntArray {
        val table = IntArray(256)
        for (i in 0 until 256) {
            table[i] = i
        }
        return table
    }

    private fun tableInverted(): IntArray {
        val table = IntArray(256)
        for (i in 0 until 256) {
            table[i] = 255 - i
        }
        return table
    }

    private fun tableRandom(): IntArray {
        val rng = Rng()
        rng.seed(0x0000000000C0FFEEL)
        val table = IntArray(256)
        for (i in 0 until 256) {
            table[i] = rng.u32() and 0xFF
        }
        return table
    }

    /** 1 identity, 2 inverted, 3 random, anything else NULL (the C's NULL-channel path). */
    private fun byKind(kind: Int): IntArray? =
        when (kind) {
            1 -> tableIdentity()
            2 -> tableInverted()
            3 -> tableRandom()
            else -> null
        }

    // ------------------------------------------------------------------------------------- crc

    /** The driver's CRC-32: the buffer's ints, least-significant byte first. */
    private fun crc32(buffer: IntArray): Int {
        val crc = CRC32()
        val bytes = ByteArray(4)
        for (value in buffer) {
            bytes[0] = value.toByte()
            bytes[1] = (value ushr 8).toByte()
            bytes[2] = (value ushr 16).toByte()
            bytes[3] = (value ushr 24).toByte()
            crc.update(bytes)
        }
        return crc.value.toInt()
    }

    private fun hx(value: Int): String = "%08x".format(value)
}
