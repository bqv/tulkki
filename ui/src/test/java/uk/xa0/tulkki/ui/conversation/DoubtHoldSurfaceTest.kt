package uk.xa0.tulkki.ui.conversation

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert
import org.junit.Test

/**
 * Item 16's held-send surface, pinned in the source - a **source cell**, for the reason
 * `translation`'s `DoubtHoldConsultationTest` is one: nothing here is reachable from a JVM test. The
 * Compose conversation view is drawn from a state a host assembles, and the host does not exist yet,
 * so a cell that drove the surface would be testing a fixture rather than the screen.
 *
 * <p>Three contract sentences, each a way the surface can silently undo work `:translation` has
 * already measured:
 *
 * <ul>
 *   <li>**It must never re-decide.** `HeldSend.decide` is the send path's, and calling it from the
 *       surface re-buys the answer the owner already has - the bug the check exists for. The gate is
 *       the same shape: `ComposerGate.verdict` is asked where the send happens.
 *   <li>**It must not read or write the notes.** `ReviewStore.of` answers those for the bubble; a bar
 *       that read the store for itself would be a second answer to the same question.
 *   <li>**The doubt's sentence is the kind's own.** The bar reads `getBecause()` and nothing else, so
 *       the words cannot drift from the rule that decided the hold.
 * </ul>
 *
 * <p>And the switch's own half: `NULL` is "never chose" and follows the build's default, so the
 * resolution happens in exactly one place in this module. The host may read the column - it has to, to
 * hand the value over - but it must not answer it.
 *
 * <p>**The two "never" cells read the code with its comments removed**, which is deliberate rather
 * than a convenience: `UiComposer`'s KDoc *names* `ReviewStore.of` and `HeldSend.decide` in order to
 * record the contract, and a pin that could not tell a sentence about the rule from a call to it
 * would force the contract out of the documentation. What is asserted is what the code does.
 */
class DoubtHoldSurfaceTest {

    /** The Compose conversation surface: the screen, its composer and the types it is assembled from. */
    private val surface = "ui/src/main/java/uk/xa0/tulkki/ui/conversation"

    @Test
    fun theSurfaceNeverReDecidesAHold() {
        assertNothingInSurface("the hold and the gate are decided where the send happens, never on the screen that draws it") {
            it.contains("HeldSend.decide(") || it.contains("ComposerGate.verdict(")
        }
    }

    @Test
    fun theSurfaceNeverTouchesTheNotes() {
        assertNothingInSurface("ReviewStore.of already answers the notes for the bubble") { it.contains("ReviewStore") }
    }

    @Test
    fun theBarsDoubtSentenceIsTheKindsOwn() {
        val composer = code("$surface/ConversationComposer.kt")
        Assert.assertTrue(
            "the doubt branch reads the kind's own sentence and nothing else",
            composer.contains("is UiHold.Doubt -> hold.kind.because"),
        )
    }

    @Test
    fun theTriStateIsResolvedInExactlyOnePlace() {
        val resolvers =
            Files.walk(root().resolve("ui/src/main/java")).use { walk ->
                walk.filter(Files::isRegularFile)
                    .filter { it.toString().endsWith(".kt") || it.toString().endsWith(".java") }
                    .filter { code(it).contains("DoubtHold.inForce(") }
                    .map { root().relativize(it).toString() }
                    .sorted()
                    .toList()
            }
        Assert.assertEquals(
            "one place in this module answers \"never chose\", so the surface cannot answer it differently",
            listOf("ui/src/main/java/uk/xa0/tulkki/ui/projection/UiDoubtHold.kt"),
            resolvers,
        )
    }

    private fun assertNothingInSurface(what: String, forbidden: (String) -> Boolean) {
        val offenders =
            Files.walk(root().resolve(surface)).use { walk ->
                walk.filter(Files::isRegularFile)
                    .filter { it.toString().endsWith(".kt") || it.toString().endsWith(".java") }
                    .filter { forbidden(code(it)) }
                    .map { root().relativize(it).toString() }
                    .sorted()
                    .toList()
            }
        Assert.assertEquals(what, emptyList<String>(), offenders)
    }

    /** The file's source with its comments removed, flattened: one line, so a token cannot straddle. */
    private fun code(file: Path): String =
        String(Files.readAllBytes(file), StandardCharsets.UTF_8)
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("//[^\n]*"), " ")
            .replace(Regex("\\s+"), " ")

    private fun code(relative: String): String = code(root().resolve(relative))

    /** The checkout root, found by its `settings.gradle.kts` marker rather than by the first `src`. */
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
}
