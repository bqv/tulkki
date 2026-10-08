package uk.xa0.tulkki.app

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.xa0.tulkki.xmpp.utils.XmlHelper

/**
 * Tulkki: two ways a peer's bytes could reach us as something we then write out - the port of
 * upstream `76d1bbc304` (port-11's span read, group D).
 *
 * <p>`XmlHelper.appendEncodedEntities` copied control characters straight into the document it
 * builds, so a body a peer sent could produce **invalid XML we then serialize**. `XmlReader`
 * guarded the **first** read of an element against a stream that stopped mid-tag, but not the read
 * inside its loop - which is the one a truncated document actually reaches, and the one that left
 * `nextTag` null.
 *
 * <p>They are two different instruments for a reason. The first drives the public API and fails by
 * its own assertion; the second is a **source pin**, because `XmlReader` cannot be fed a truncated
 * stream off-device at all (it is built on `android.util.Xml`'s pull parser, a stub, so the cell
 * dies `Method newPullParser in android.util.Xml not mocked` before reaching the guard). Both fail
 * against the tree before the fix - and a cell that could not compile would be a compile-time
 * absence rather than evidence, which is why neither introduces a symbol the unfixed tree lacks.
 */
class XmlHardeningTest {

    @Test
    fun aControlCharacterDoesNotSurviveIntoTheEncodedDocument() {
        val plain = StringBuilder()
        XmlHelper.appendEncodedEntities("a\u0001b", plain)
        assertEquals("the control character must be dropped, not written", "ab", plain.toString())

        val escaped = StringBuilder()
        XmlHelper.appendEncodedEntities("a<\u0002b>c", escaped)
        assertEquals("the markup is still escaped around it", "a&lt;b&gt;c", escaped.toString())
        assertFalse(
            "and nothing below 0x20 but tab, newline and carriage return survives",
            escaped.toString().indexOf('\u0002') >= 0)
    }

    @Test
    fun bothOfTheElementsReadsAreGuardedAgainstAStreamThatStops() {
        val source = flattened(readerSource())
        // The Kotlin port (`9adcea9866`) spells the Java's `Tag nextTag = this.readTag();` and the
        // null-check that followed it as one elvis expression, so the read and the guard are
        // re-pointed together: the fact is unchanged - `readElement` reads an element exactly twice
        // and each read turns a null tag into the interruption instead of dereferencing it.
        val read = "nextTag = this.readTag()"
        val guard = "?: throw IOException(\"interrupted mid tag\")"

        var reads = 0
        var guarded = 0
        var i = source.indexOf(read)
        while (i >= 0) {
            reads++
            val window = minOf(source.length, i + read.length + 300)
            if (source.substring(i + read.length, window).contains(guard)) {
                guarded++
            }
            i = source.indexOf(read, i + 1)
        }

        assertEquals("readElement has exactly two reads to guard: the first and the loop's", 2, reads)
        assertEquals(
            "each of them must turn a null tag into the interruption, not dereference it",
            reads,
            guarded)
    }

    /**
     * The runtime proof of the second behaviour is not available to a JVM cell: `XmlReader` is built
     * on `android.util.Xml`'s pull parser, which is a stub off-device (`Method newPullParser in
     * android.util.Xml not mocked`), so a truncated stream cannot be fed to it here. The guard is
     * therefore pinned where it lives, and the throw itself remains a device/harness look.
     */
    private fun readerSource(): Path {
        val named =
            projectRoot().resolve("xmpp/src/main/java/uk/xa0/tulkki/xml/XmlReader.java")
        if (Files.isRegularFile(named)) {
            return named
        }
        // The Kotlin port moves a file from `.java` to `.kt` without moving the path, so the sibling
        // spelling is tried before the assertion fires: the pin reads the text the file holds, never
        // the language it is written in (the fallback `OsHeldNamesTest`, `PortInstallGuardTest` and
        // `RecurringBackupTest` already carry).
        val sibling =
            projectRoot().resolve("xmpp/src/main/java/uk/xa0/tulkki/xml/XmlReader.kt")
        if (Files.isRegularFile(sibling)) {
            return sibling
        }
        throw AssertionError(
            "XmlReader is at neither source spelling under " + projectRoot().resolve("xmpp"))
    }

    /** The checkout root, found the way this module's other source scans find it. */
    private fun projectRoot(): Path {
        var directory: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        var level = 0
        while (level < 6 && directory != null) {
            if (Files.isRegularFile(directory.resolve("settings.gradle.kts"))
                    || Files.isRegularFile(directory.resolve("settings.gradle"))) {
                return directory
            }
            directory = directory.parent
            level++
        }
        throw AssertionError(
            "could not find settings.gradle.kts above " + System.getProperty("user.dir"))
    }

    private fun flattened(file: Path): String =
        String(Files.readAllBytes(file), StandardCharsets.UTF_8).replace(Regex("\\s+"), " ")
}
