package uk.xa0.tulkki.ui.adapter

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Stream
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.translation.DisplayedBody
import uk.xa0.tulkki.translation.HeldSend
import uk.xa0.tulkki.translation.Interpreter
import uk.xa0.tulkki.ui.projection.Direction
import uk.xa0.tulkki.ui.projection.OriginalReveal

/**
 * Item 17's decision five at the surface the Compose conversation actually hosts: `MessageProjection`'s
 * own `offersOriginal`, `ConversationHost.revealOriginal` and `ConversationScreen`'s offered-original
 * strip.
 *
 * <p>It read `MessageAdapter`'s `offersOriginal` / `displayOriginalRow` / `tapOriginalRow` while the XML
 * adapter drew the rows; ui-9 moved the drawing to Compose and the adapter is an API-only stub now, so
 * the pin follows the surface. The gate is not a second rule either way: the projection hands the row's
 * own facts to [OriginalReveal.eligible], the one decision function both surfaces call. So what is
 * pinned here is that composition, spelled the way the projection spells it, plus the two properties of
 * its source that no runtime cell could see: no display switch reaches the gate, and the reveal spends
 * nothing.
 *
 * <p>The five cells answer the five behaviours the decision names: a genuinely failed received
 * translation offers its original; a pending row does not; the interpreter off offers nothing whatever
 * a stale reason says; no setting can create it; and the tap reveals rather than translates.
 */
class OriginalRowRevealTest {

    /**
     * The adapter's own composition, as `offersOriginal` builds it: the display decision made over the
     * row's body and state, then [OriginalReveal.eligible] over the direction, the cover and the
     * reason the store recorded for this very message. Nothing here is a second implementation - it is
     * the same three row facts the Java method passes.
     */
    private fun offered(
        status: Int,
        body: String,
        translationState: Int,
        reason: HeldSend.HoldReason?,
        interpreter: Interpreter = Interpreter.of("fi", "en"),
    ): Boolean {
        val displayed =
            DisplayedBody.of(
                body,
                null,
                translationState,
                DisplayedBody.needsTranslation(status, body, "Alice", interpreter),
                interpreter,
            )
        return OriginalReveal.eligible(
            if (status == Message.STATUS_RECEIVED) Direction.INCOMING else Direction.OUTGOING,
            displayed.isBlurred(),
            reason,
        )
    }

    @Test
    fun aGenuinelyFailedReceivedTranslationOffersItsOriginal() {
        Assert.assertTrue(
            "translation was needed and did not happen, and the terminal reason is this row's",
            offered(
                Message.STATUS_RECEIVED,
                "die Antwort",
                Message.TRANSLATION_FAILED,
                HeldSend.HoldReason.FAILED,
            ),
        )
    }

    @Test
    fun aPendingRowOffersNothing() {
        Assert.assertFalse(
            "covered, but nothing was attempted: there is no failure to be the deciding fact",
            offered(Message.STATUS_RECEIVED, "die Antwort", Message.TRANSLATION_NONE, null),
        )
    }

    @Test
    fun withTheInterpreterOffNothingIsOffered() {
        val off = Interpreter.of("fi", "fi")
        Assert.assertFalse(
            "off, nothing was owed a translation, so nothing is covered",
            DisplayedBody.needsTranslation(Message.STATUS_RECEIVED, "die Antwort", "Alice", off),
        )
        Assert.assertFalse(
            "and a stale recorded reason cannot cover a row the interpreter is not translating",
            offered(
                Message.STATUS_RECEIVED,
                "die Antwort",
                Message.TRANSLATION_NONE,
                HeldSend.HoldReason.FAILED,
                off,
            ),
        )
    }

    @Test
    fun noDisplaySwitchReachesTheGate() {
        // The projection computes the gate in one assignment, before it reads a setting; its boundary is
        // the comment that opens the next step of the row composition.
        val projection = read(PROJECTION)
        val start = projection.indexOf("val offersOriginal =")
        Assert.assertTrue("the projection's own gate is gone; move this pin with it", start >= 0)
        val stop = projection.indexOf("Only a text row divides", start)
        Assert.assertTrue("the gate's boundary comment is gone; move this pin with it", stop > start)
        val gate = projection.substring(start, stop)
        Assert.assertTrue(
            "the one decision function answers it, not a condition written here",
            gate.contains("OriginalReveal.eligible("),
        )
        for (switch in DISPLAY_SWITCHES) {
            Assert.assertFalse(
                "$switch must not be an input to the gate - no setting can create the reveal",
                gate.contains(switch),
            )
        }
    }

    @Test
    fun theTapRevealsAndDoesNotTranslate() {
        val host = read(HOST)
        val tap = methodBody(host, "fun revealOriginal(")
        Assert.assertTrue(
            "the reveal is the owner's per-message set, exactly as the English row's is",
            tap.contains("revealedOriginals = revealedOriginals + MessageId(id)"),
        )
        Assert.assertFalse("it must not look anything up", tap.contains("EnglishLookup"))
        Assert.assertFalse("and it must not spend a translation call", tap.contains("requestTranslation"))
        val screen = read(SCREEN)
        Assert.assertTrue(
            "the strip carries the reveal as its own tap",
            screen.contains("onReveal = { events.onRevealOriginal(row.id.uuid) }"),
        )
        Assert.assertTrue(
            "while the covered body keeps its own translate-now tap",
            screen.contains("onTranslate = { events.onBodyTap(row.id.uuid) }"),
        )
    }

    /**
     * The body of the one method whose signature contains [signature], from its opening brace to its
     * matching close. A source reading, because the class it reads cannot be loaded here; the two
     * methods it is asked about carry no brace inside a literal, so counting is enough.
     */
    private fun methodBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        Assert.assertTrue("$signature is gone", start >= 0)
        val open = source.indexOf('{', start)
        Assert.assertTrue("$signature has no body", open > start)
        var depth = 0
        for (at in open until source.length) {
            when (source[at]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return source.substring(open, at + 1)
                    }
                }
            }
        }
        throw AssertionError("unbalanced braces after $signature")
    }

    private fun read(relative: String): String {
        val path = locate(relative)
        return String(Files.readAllBytes(path), StandardCharsets.UTF_8)
    }

    /** The file `relative` names; throws when it is not there, which is what a positive read needs. */
    private fun locate(relative: String): Path =
        resolve(relative) ?: throw AssertionError("could not locate $relative under ${root()}")

    /**
     * The file `relative` names, or null. The Gradle working directory is the module and the repository
     * root is found by its `settings.gradle.kts` marker rather than by walking up to the first `src`, which
     * would stop at the module - so a path that is genuinely gone has to be answered with null rather
     * than with the first candidate's absence.
     */
    private fun resolve(relative: String): Path? {
        val root = root()
        val direct = root.resolve(relative)
        if (Files.exists(direct)) {
            return direct
        }
        val directories =
            Files.list(root).use { stream: Stream<Path> -> stream.filter(Files::isDirectory).sorted().toList() }
        for (directory in directories) {
            val name = directory.fileName.toString()
            if (name.startsWith(".") || name == "build" || name == "src") {
                continue
            }
            val candidate = directory.resolve(relative)
            if (Files.exists(candidate)) {
                return candidate
            }
        }
        return null
    }

    private fun root(): Path {
        var directory: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        for (level in 0 until 6) {
            if (directory != null &&
                    (Files.isRegularFile(directory.resolve("settings.gradle.kts"))
                            || Files.isRegularFile(directory.resolve("settings.gradle")))) {
                return directory
            }
            directory = directory?.parent
        }
        throw AssertionError("could not find settings.gradle.kts above ${System.getProperty("user.dir")}")
    }

    private companion object {
        /** The Compose projection that draws the row, and the two files that carry its reveal. */
        const val PROJECTION = "src/main/java/uk/xa0/tulkki/ui/projection/MessageProjection.kt"

        const val HOST = "src/main/java/uk/xa0/tulkki/ui/conversation/ConversationHost.kt"

        const val SCREEN = "src/main/java/uk/xa0/tulkki/ui/conversation/ConversationScreen.kt"

        /** Every display switch the second half and the English row read; none is an input here. */
        val DISPLAY_SWITCHES =
            listOf(
                "showSecondHalf",
                "showConcealedOriginal",
                "concealOwnSecondHalf",
                "showBlurredEnglish",
                "showEnglishRetranslation",
            )
    }
}
