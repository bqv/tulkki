package uk.xa0.tulkki.app

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.HashSet
import java.util.LinkedHashMap
import org.junit.Assert
import org.junit.Test

/**
 * The image-filter port's 456-case baseline, and the counts the port is judged against.
 *
 * <p>Port-16 absorbs the six JNI filters into Kotlin, and its gate says the golden values "must be
 * captured first". This reads {@code app/src/test/resources/image/filters-456.txt} - the in-tree C's
 * own output over the deterministic 456-case set, captured before anything moved - and holds the
 * capture to its own shape: six functions, the counts the measurement recorded, and the six-field
 * line the sweep compares by.
 *
 * <p><strong>Why 456 cases are not the whole gate, recorded here because the file cannot say it:</strong>
 * the cases alone do not catch two single-token mutations of {@code doSaturation}'s float precision
 * ({@code .66666} → {@code .66666f}, and a regrouping of {@code (temp1-temp2)*6*temp3}) - each still
 * scores 456/456 and is caught only by the exhaustive 256³ sweep
 * ({@code docs/MIGRATION.md}, "Design: the native image port" §3.2). So this resource is half of the
 * baseline; the other half is the sweep, whose golden is deliberately not here (see the file's
 * header) and is the demotion commit's own step.
 *
 * <p>What this test can and cannot do: it pins the target and its counts, and it reddens if the
 * resource is edited - it does not run the filters. The port's own test consumes the same file and
 * compares its output to these lines; that comparison is the demotion's cell, not this round's.
 */
class ImageFiltersBaselineTest {

    private companion object {

        const val RESOURCE = "/image/filters-456.txt"

        /** The counts measured at capture: 40 + 56 + 80 + 72 + 136 + 72 = 456. */
        val EXPECTED: Map<String, Int> = expected()

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
            val input = ImageFiltersBaselineTest::class.java.getResourceAsStream(RESOURCE)
            Assert.assertNotNull("the baseline must be on the test classpath: " + RESOURCE, input)
            input!!.use { stream ->
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
                    var line: String? = reader.readLine()
                    while (line != null) {
                        lines.add(line)
                        line = reader.readLine()
                    }
                }
            }
            return lines
        }

        /** The measured cases, keyed by function: every non-comment line, split on the pipe. */
        fun counts(lines: List<String>): Map<String, Int> {
            val counts = LinkedHashMap<String, Int>()
            for (line in lines) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue
                }
                // Kotlin's split needs a non-negative limit: 0 is "no limit", and like Java's -1
                // it keeps a trailing empty field rather than dropping it (so a malformed line
                // still fails the six-field assertion below, as the Java original did).
                val fields = line.split('|', ignoreCase = false, limit = 0)
                Assert.assertEquals(
                    "a case line is function|case|geometry|params|inputHash|outputHash: " + line,
                    6,
                    fields.size)
                Assert.assertTrue(
                    "a case must carry a value digest, not an empty field: " + line,
                    fields[4].length >= 8 && fields[5].length >= 8)
                val previous = counts[fields[0]]
                counts[fields[0]] = if (previous == null) 1 else previous + 1
            }
            return counts
        }
    }

    @Test
    fun theBaselineIsTheSixFiltersWithTheCountsTheCaptureRecorded() {
        val measured = counts(lines())
        Assert.assertEquals(
            "the six filters, and nothing else, are the port's surface", EXPECTED, measured)
        var total = 0
        for (count in measured.values) {
            total += count
        }
        Assert.assertEquals("the 456-case set", 456, total)
    }

    /**
     * The compiler and the FP model are part of the baseline: the same code at a different
     * {@code FLT_EVAL_METHOD} or with a differently sized {@code float} is a different oracle, and
     * {@code -ffast-math} differs on 61 of these very lines.
     */
    @Test
    fun theBaselineNamesTheModelItWasProducedUnder() {
        val lines = lines()
        Assert.assertTrue(
            "the capture must carry the C's FP model header",
            lines.any { it.startsWith("# FLT_EVAL_METHOD=") })
        Assert.assertTrue(
            "and it must say which C it came from",
            lines.any { it.contains("NativeImageProcessor.cpp") })
        Assert.assertTrue(
            "and that the exhaustive sweep is not in this file, so the gap is named",
            lines.any { it.contains("256^3") })
    }

    /**
     * A baseline is only usable if the port can find the cases it must answer: one line per case, no
     * duplicates. A duplicated line would let a port that handles 455 of them pass by luck.
     */
    @Test
    fun everyCaseAppearsExactlyOnce() {
        val cases = ArrayList<String>()
        for (line in lines()) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue
            }
            cases.add(line)
        }
        Assert.assertEquals("one line per case", 456, cases.size)
        Assert.assertEquals("and no duplicate case", 456, HashSet(cases).size)
    }
}
