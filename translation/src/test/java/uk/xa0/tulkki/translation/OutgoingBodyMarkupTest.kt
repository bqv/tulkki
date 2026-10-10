package uk.xa0.tulkki.translation

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.ArrayList
import org.junit.Assert
import org.junit.Test

/**
 * Tulkki: a translated outgoing body keeps the markup the composer wrote, and it is the body's own
 * type that reads it - a **source pin**, because the writeback is not reachable from a JVM cell.
 *
 * <p>Why nothing behavioural: [OutgoingTranslation.swap] is driven by an `EngineHost`, a `Message`
 * and a database write, and the one `XhtmlBody.Writer` in the tree (`SpannedToXHTML`) lives in
 * `:app` - above `:translation` and `:data` in the module map, so neither module's test source set
 * can name it - and it is written in `android.text` spans the unit tests run without (the tree has no
 * Robolectric). That is also why `SpannedToXHTML` itself has no cell. The parser's own half is pinned
 * behaviourally by `ui`'s `StyledTextTest`; what is left is the routing, and this is the tree's
 * instrument for routing it cannot call (`DoubtHoldConsultationTest` pins the same file the same way).
 *
 * <p>The defect this guards: `swap` used to write the composed wire body with `Message.setBody(String)`
 * - the overload that deliberately clears a stale XHTML alternate. The composer's body reaches the
 * model as a `Spanned` (ConversationFragment's draft), so the translation layer's `String` write threw
 * away the alternate the markup writer had just produced, and the message left without its
 * `<strong>`/`<em>`. The rule the fix must keep, and the cells below pin: the plain body stays
 * byte-for-byte (markers included), the alternate carries the markup, a body with no markup still
 * writes no alternate, and an already-styled body is untouched.
 */
class OutgoingBodyMarkupTest {

    private val SEND_PATH =
            "translation/src/main/java/uk/xa0/tulkki/translation/OutgoingTranslation.kt"

    private val BODY =
            "data/src/main/java/uk/xa0/tulkki/data/model/Message.kt"

    private val WRITEBACK = "message.setBodyKeepingMarkup(composed.recompose(wire))"

    @Test
    fun theWritebackKeepsTheBodyMarkup() {
        val sendPath = flattened(projectRoot().resolve(SEND_PATH))

        Assert.assertTrue(
                "the final write to an outgoing body must go through the body's markup-aware setter:" +
                        " " + WRITEBACK,
                sendPath.contains(WRITEBACK))
    }

    @Test
    fun noPlainBodyWriteSurvivesInTheModule() {
        // `setBody(String)` clears a stale alternate, which is right for a received body and wrong
        // for an outgoing one: the composer's styling is what would be cleared. `setBodyKeepingMarkup`
        // is the module's only body write, and clearing a body is `setBodyKeepingMarkup(null)`.
        val plain = ArrayList<String>()
        for (file in sources("translation/src/main/java")) {
            if (flattened(file).contains(".setBody(")) {
                plain.add(projectRoot().relativize(file).toString())
            }
        }

        Assert.assertEquals(
                "an outgoing body written with the plain String setter loses its XHTML alternate",
                emptyList<String>(),
                plain)
    }

    @Test
    fun theBodyItselfReadsTheMarkup() {
        // The parse stays behind the port the body owns, so the translation layer never has to know
        // the syntax: it names no parser and no span.
        val body = flattened(projectRoot().resolve(BODY))
        Assert.assertTrue(
                "the body's markup-aware setter must read the body through the port",
                body.contains("fun setBodyKeepingMarkup(body: String?)"))
        Assert.assertTrue(
                "the body's own type must ask for the markup rather than a second reader doing it",
                body.contains("XhtmlBody.markup(body)"))

        val sendPath = flattened(projectRoot().resolve(SEND_PATH))
        for (token in listOf("XhtmlBody", "ImStyleParser", "StylingHelper", "Spanned")) {
            Assert.assertFalse(
                    "the translation layer must not learn the markup syntax: it names " + token,
                    sendPath.contains(token))
        }
    }

    /** The module's main sources, so a new body write cannot hide in a file this test does not know. */
    private fun sources(relative: String): List<Path> =
            Files.walk(projectRoot().resolve(relative)).use { walk ->
                walk.filter { Files.isRegularFile(it) }
                        .filter {
                            val name = it.getFileName().toString()
                            name.endsWith(".java") || name.endsWith(".kt")
                        }
                        .toList()
            }

    private fun flattened(file: Path): String {
        return String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                .replace(Regex("\\s+"), " ")
    }

    /** The checkout root, found the way the module's other source scans find it. */
    private fun projectRoot(): Path {
        var directory: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        for (level in 0 until 6) {
            if (directory != null &&
                    (Files.isRegularFile(directory.resolve("settings.gradle.kts"))
                            || Files.isRegularFile(directory.resolve("settings.gradle")))) {
                return directory
            }
            directory = directory?.getParent()
        }
        throw AssertionError(
                "could not find settings.gradle.kts above " + System.getProperty("user.dir"))
    }
}
