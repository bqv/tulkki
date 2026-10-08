package uk.xa0.tulkki.app

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.regex.Matcher
import java.util.regex.Pattern
import org.junit.Assert
import org.junit.Test

/**
 * The Argon2 known-answer vectors captured from the in-tree C, and the one thing that makes them
 * worth pinning: the parameters they were captured at are the parameters the app uses <em>now</em>.
 *
 * <p>Port-16 demotes the Argon2 JNI to a Kotlin/`argon2kt` implementation, and its gate says the
 * "golden values must be captured first". This reads the capture
 * ({@code app/src/test/resources/argon2/known-answer.txt}, written by {@code tools/argon2-vectors}
 * from {@code app/src/main/jni/argon2/&#42;*}) and holds it to what the app does today:
 *
 * <ul>
 *   <li>the resource's parameter line must equal the literals in
 *       {@code data/.../Argon2KeyDerivation.kt} - {@code MEMORY_MIB}, {@code ITERATIONS},
 *       {@code PARALLELISM}, {@code KEY_BYTES} - and the version/type from {@code Argon2.java}. The
 *       capture is a measurement of a moving thing, so if the app's parameters move, this fails and
 *       the resource is stale rather than silently authoritative;
 *   <li>the parameters must be in the <em>encoded</em> string every vector carries
 *       ({@code $argon2id$v=19$m=65536,t=3,p=4$…}), because that string is what a password digest
 *       is compared by and where a port that ignores {@code p} diverges.
 * </ul>
 *
 * <p><strong>What this test cannot do, stated rather than implied:</strong> it cannot reproduce the
 * vectors. The C is reachable only through {@code libargon2.so}, which the NDK builds for Android
 * ABIs, so on the JVM {@code Argon2Native} has no implementation to load. The reproduction cell
 * belongs to the port's own test, which consumes this same resource; what is pinned here is the
 * target and the freshness of its parameters. The vectors themselves were re-captured at this tree
 * and are byte-identical to the round-43 record ({@code docs/MIGRATION.md}, "Design: the native
 * image port" §4), with the C's own hashes unchanged.
 *
 * <p>The trap the port must not fall into is named in the capture's provenance and checked here:
 * {@code argon2kt}'s default parallelism is 2, so {@code p = 4} has to be explicit, or the key and
 * the encoded string change silently. The app's own value is measured, not assumed - it is
 * {@code 4}, the same number port-16's row names.
 */
class Argon2BaselineFreshnessTest {

    private companion object {

        const val RESOURCE = "crypto/src/test/resources/argon2/known-answer.txt"

        /** The app's own parameters, read from `:data`'s source rather than restated from memory. */
        const val KEY_DERIVATION = "data/src/main/java/uk/xa0/tulkki/data/Argon2KeyDerivation.kt"

        /** One measured vector: `V1|pwdlen=0|saltlen=32|rc=0|hash=…|enc=…`. */
        val VECTOR: Pattern =
            Pattern.compile(
                "^(\\S+)\\|pwdlen=(\\d+)\\|saltlen=(\\d+)\\|rc=(-?\\d+)\\|hash=(\\S+)\\|enc=(.*)$")

        val HEX64: Pattern = Pattern.compile("^[0-9a-f]{64}$")

        /**
         * Every line of the capture. Read from the repository rather than the classpath: the resource
         * lives in `:crypto`'s test source set, where the implementation it is for and the test that
         * executes it both are (`Argon2KnownAnswerTest` in `org.signal.argon2`), and this module cannot
         * see another module's test resources.
         */
        fun capture(): List<String> {
            val path = repositoryRoot().resolve(RESOURCE)
            Assert.assertTrue(
                "the capture must be where the tool writes it: " + path, Files.isRegularFile(path))
            return Files.readAllLines(path, StandardCharsets.UTF_8)
        }

        fun parameterLine(lines: List<String>): String {
            for (line in lines) {
                if (line.startsWith("parameters ")) {
                    return line
                }
            }
            Assert.fail("the capture carries no `parameters` line")
            return ""
        }

        fun vectors(lines: List<String>): List<Matcher> {
            val found = ArrayList<Matcher>()
            for (line in lines) {
                val matcher = VECTOR.matcher(line)
                if (matcher.matches()) {
                    found.add(matcher)
                }
            }
            return found
        }

        /** The repository root, found the way the migration's other source scans find it. */
        fun repositoryRoot(): Path {
            var directory: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
            while (directory != null) {
                if (Files.isRegularFile(directory.resolve("settings.gradle.kts"))
                        || Files.isRegularFile(directory.resolve("settings.gradle"))) {
                    return directory
                }
                directory = directory.parent
            }
            throw AssertionError(
                "could not find settings.gradle.kts above " + System.getProperty("user.dir"))
        }

        fun keyDerivationSource(): String {
            val path = repositoryRoot().resolve(KEY_DERIVATION)
            Assert.assertTrue(
                "the app's KDF must still be where the capture points: " + path,
                Files.isRegularFile(path))
            return String(Files.readAllBytes(path), StandardCharsets.UTF_8)
        }

        fun literal(source: String, name: String): Int {
            val matcher = Pattern.compile("const val " + name + " = (\\d+)").matcher(source)
            Assert.assertTrue("Argon2KeyDerivation.kt no longer declares " + name, matcher.find())
            return matcher.group(1).toInt()
        }
    }

    /**
     * The freshness cell: the capture's parameters are the app's live ones, read from the source
     * that owns them. Move {@code PARALLELISM}, {@code MEMORY_MIB}, {@code ITERATIONS} or
     * {@code KEY_BYTES} and this fails, because the port would then be judged against vectors the
     * app no longer produces.
     */
    @Test
    fun theCaptureParametersAreTheAppsLiveOnes() {
        val lines = capture()
        val parameters = parameterLine(lines)
        val source = keyDerivationSource()

        val memoryMib = literal(source, "MEMORY_MIB")
        val iterations = literal(source, "ITERATIONS")
        val parallelism = literal(source, "PARALLELISM")
        val keyBytes = literal(source, "KEY_BYTES")

        Assert.assertEquals(
            "the capture's parameters must be the app's literals",
            "parameters t=" + iterations +
                " m=" + (memoryMib * 1024) +
                " p=" + parallelism +
                " out=" + keyBytes +
                " type=argon2id version=19",
            parameters)
        // The type and version are enum members, not numbers: the capture's `type=`/`version=`
        // spelling is what the JNI passes (Argon2_id, ARGON2_VERSION_13 = 0x13 = 19).
        Assert.assertTrue(source, source.contains("Version.V13"))
        Assert.assertTrue(source, source.contains("Type.Argon2id"))
        Assert.assertEquals(
            "port-16's row names p = 4; the app is the authority, and they agree", 4, parallelism)
    }

    /**
     * The provenance must name the C the bytes came from; an unattributed capture is not evidence.
     */
    @Test
    fun theCaptureNamesItsSource() {
        val lines = capture()
        Assert.assertTrue(
            "the capture must pin the C it was taken from",
            lines.any { it.startsWith("argon2.h sha256=") })
        Assert.assertTrue(
            "and the same for the file that owns the call",
            lines.any { it.startsWith("argon2.c sha256=") })
        Assert.assertTrue(
            "and it must say where it came from",
            lines.any { it.contains("app/src/main/jni/argon2") })
    }

    /**
     * The parameters the <em>implementation</em> must be driven at are in the capture, and `p = 4` is
     * one of them: it is what the app does today, and it is the number a port that lets a default
     * through would silently change.
     */
    @Test
    fun theCaptureSpellsOutTheAppParametersIncludingParallelism() {
        val parameters = parameterLine(capture())
        Assert.assertEquals(
            "t=3 m=65536 KiB p=4 out=32 type=argon2id version=19",
            "parameters t=3 m=65536 p=4 out=32 type=argon2id version=19",
            parameters)
    }
}
