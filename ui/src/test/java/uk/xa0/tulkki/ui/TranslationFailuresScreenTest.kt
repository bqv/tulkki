package uk.xa0.tulkki.ui

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Stream
import org.junit.Assert
import org.junit.Test

/**
 * The failures screen's one reversal, pinned where the screen actually is: the Compose row, as source
 * text, and the two layouts that used to draw it, as files that are gone.
 *
 * <p>Why source and not the screen: this module hosts no Activity, no Fragment and no Robolectric, so a
 * Composable cannot be rendered here - the same reason [TulkkiSettingsRowsTest] compares the settings
 * tree as text. What can be pinned is the thing that makes the reversal real: `FailuresScreen` draws
 * `failure.body`, the value `:data`'s two projections select and `TranslationFailures.Failure` carries,
 * and the row layout that had a field for it went with the fragment that inflated it, so nobody can
 * re-anchor this screen on a stale second copy.
 *
 * <p>A doc that contradicts the code is the next reader's trap, and this screen's doc contradicted it
 * until the owner's §3.2 reversal reached it, so one cell keeps the check that no surviving text
 * claims the message is hidden here.
 *
 * <p>Two more cells pin what item 17 added on top: a row now offers the action its kind permits, so
 * the screen must draw the actions [FailureAction] decides and route them through the host - and the
 * host must run the very symbols the conversation already runs, so the list is a second way in and
 * never a second way to spend. Those are source readings for the same reason the body cell is: no
 * Composable runs in this module, and the route lives in a Java fragment.
 */
class TranslationFailuresScreenTest {

    @Test
    fun theRowDrawsTheMessageItIsAbout() {
        val screen = read("src/main/java/uk/xa0/tulkki/ui/failures/FailuresScreen.kt")
        Assert.assertTrue(
            "the row must read the failure's own body, not the prose that names it",
            screen.contains("failure.body?.trim()"),
        )
        Assert.assertTrue("and the screen must draw it, not only carry it", screen.contains("Text(message"))
    }

    /**
     * The fragment and the two layouts that drew the old rows are gone: this screen is the one place a
     * failure is drawn, and an inflation nobody performs is exactly the stale copy this cell refuses.
     */
    @Test
    fun theLayoutsThatDrewTheOldRowsAreGone() {
        for (gone in GONE) {
            Assert.assertNull("$gone still exists, with no reader", resolve(gone))
        }
    }

    @Test
    fun noTextOnThisScreenStillClaimsTheMessageIsHiddenHere() {
        val intro = read("src/main/res/values/strings_tulkki.xml")
        Assert.assertFalse(
            "tulkki_failures_intro still says the text is never shown",
            intro.contains("The message text is never shown here"),
        )
        val screen = read("src/main/java/uk/xa0/tulkki/ui/failures/FailuresScreen.kt")
        Assert.assertFalse(
            "the screen's own docs still say it shows no message text",
            screen.contains("shows no message text"),
        )
        val fragment = read("src/main/java/uk/xa0/tulkki/ui/TranslationFailuresFragment.kt")
        Assert.assertFalse(
            "the host's own docs still say it shows no message text",
            fragment.contains("shows no message text"),
        )
    }

    /**
     * The row draws the actions the decision names: the screen asks [FailureAction] which a row
     * affords, and its one `when` routes each to the host. A screen that grew a second opinion about
     * what a kind permits - or a button that drew without a route - fails here.
     */
    @Test
    fun theRowOffersTheActionsItsKindPermitsThroughTheHost() {
        val screen = read("src/main/java/uk/xa0/tulkki/ui/failures/FailuresScreen.kt")
        Assert.assertTrue(
            "which actions a row affords is FailureAction's question, not the screen's",
            screen.contains("FailureAction.forFailure("),
        )
        for (route in ACTIONS) {
            Assert.assertTrue("the screen never emits $route", screen.contains("actions.$route("))
        }
    }

    /**
     * And the host's three actions are the conversation's own routes, symbol for symbol: the covered
     * bubble's tap for a received row, the composer bar's retry and its "send as written" for a held
     * send. A parallel route invented on this screen would pass its own test and fail this one.
     *
     * <p>The bar's sentences and slots moved to `ui/composer/HeldSendSurfaces.kt` when the held and
     * failed sends became Kotlin, so the retry and the exception are pinned on that home as well: the
     * bar's buttons must call the consumers the fragment builds from its own routes, never a route of
     * its own. The fragment's wrappers stay the named symbols the failures host calls, which is why
     * both sides are read here.
     */
    @Test
    fun theHostRunsTheRoutesTheConversationAlreadyRuns() {
        val host = read("src/main/java/uk/xa0/tulkki/ui/TranslationFailuresFragment.kt")
        for (route in ACTIONS) {
            Assert.assertTrue(
                "the host does not run $route",
                host.contains(HOST_CALLS.getValue(route)),
            )
        }
        // The covered bubble's translate-now tap left the adapter with the Compose list: the screen's
        // onBodyTap now reaches ConversationFragment.onBodyTap, which is where the request is made.
        val bubble = read("src/main/java/uk/xa0/tulkki/ui/ConversationFragment.kt")
        Assert.assertTrue(
            "the covered bubble's tap is UiHost.installed().requestTranslation",
            // The fragment chains installed() and requestTranslation() across two lines.
            Regex("UiHost\\.installed\\(\\)\\s*\\.requestTranslation\\(").containsMatchIn(bubble),
        )
        val bar = read("src/main/java/uk/xa0/tulkki/ui/ConversationFragment.kt")
        Assert.assertTrue(
            "the bar's retry is OutgoingTranslation.sendHeldNow",
            bar.contains("OutgoingTranslation.sendHeldNow("),
        )
        Assert.assertTrue(
            "the bar's exception is OutgoingTranslation.sendAsWritten",
            bar.contains("OutgoingTranslation.sendAsWritten("),
        )
        // The bar itself, whose buttons are the same two routes the fragment hands it.
        val heldBar = read("src/main/java/uk/xa0/tulkki/ui/composer/HeldSendSurfaces.kt")
        Assert.assertTrue(
            "the bar's retry button runs the host's own retry consumer",
            heldBar.contains("View.OnClickListener { translateNow.accept(message) }"),
        )
        Assert.assertTrue(
            "and its exception button runs the host's own send-as-written consumer",
            heldBar.contains("View.OnClickListener { sendAsWritten.accept(message) }"),
        )
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
        /** The two layouts the Compose screen replaced, and the fragment's own inflation of them. */
        val GONE =
            listOf(
                "src/main/res/layout/fragment_translation_failures.xml",
                "src/main/res/layout/item_translation_failure.xml",
            )

        /** The three actions the seam carries, and the symbol each one's route is spelled with. */
        val ACTIONS = listOf("translateNow", "retry", "sendAsWritten")

        val HOST_CALLS =
            mapOf(
                "translateNow" to "UiHost.installed().requestTranslation(",
                "retry" to "OutgoingTranslation.sendHeldNow(",
                "sendAsWritten" to "OutgoingTranslation.sendAsWritten(",
            )
    }
}
