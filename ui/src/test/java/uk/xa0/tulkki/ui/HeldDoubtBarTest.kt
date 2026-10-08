package uk.xa0.tulkki.ui

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert
import org.junit.Test

/**
 * The held doubt's bar, pinned in the source it now lives in: the two kinds get two sentences, and the
 * retry is a button on the bar rather than an instruction to tap the bubble.
 *
 * <p>A **source cell**, for the reason the conversation surface's own cells are: the bar is drawn
 * through `ComposerBarController` into a `ComposeView` no JVM unit-test runtime builds, and the
 * sentence and its action are chosen by `HeldSendSurfaces`, which is Kotlin and reads a `Context`. The
 * bar itself moved out of `ConversationFragment` when the held and failed surfaces became Kotlin
 * (`ui/composer/HeldSendSurfaces.kt`), so this reads that home rather than the fragment: the pin is on
 * what the code decides, not on which file used to decide it.
 *
 * <p>Two facts the defect turned on (`docs/MIGRATION.md` item 16): "Not sent: ... Tap to send it." was one
 * sentence for two kinds, and it is false for the echo - the answer *is* the owner's own words, so
 * its tap re-asks and never sends - while the bubble's tap is installed per row by a verdict
 * recomputed where the row is bound, so a row whose verdict no longer answers HOLD has no listener at
 * all. The bar is always on the screen and already has the button slot the cap's own "Raise cap"
 * uses, which is where the action belongs.
 */
class HeldDoubtBarTest {

    @Test
    fun theEchoKindSaysNothingWasTranslated() {
        val bar = code(HELD)
        Assert.assertTrue(
            "an answer that came back unchanged must not read as if it had been translated",
            bar.contains("R.string.tulkki_hold_doubt_echo"),
        )
        Assert.assertTrue(
            "and its tap is a re-ask, whose wording is the owner-editable setting, not a string literal",
            bar.contains("retryWording()"),
        )
        Assert.assertFalse(
            "the shipped wording lives in RetryWording, so the surface must not keep a second copy",
            bar.contains("R.string.tulkki_hold_doubt_retry"),
        )
    }

    @Test
    fun theDoubtfulLanguageKindOffersTheSendItsTapMeans() {
        val bar = code(HELD)
        Assert.assertTrue(
            "only the doubtful language has a stored answer this tap may send",
            bar.contains("LanguageCheck.Doubt.DOUBTFUL_LANGUAGE"),
        )
        Assert.assertTrue(bar.contains("R.string.tulkki_hold_doubt_send"))
    }

    @Test
    fun theActionIsTheBarAndNotTheBubble() {
        Assert.assertTrue(
            "the bar's own button runs the one retry, the same entry the bubble's tap uses",
            code(HELD).contains("View.OnClickListener { translateNow.accept(message) }"),
        )
    }

    @Test
    fun noSentenceSendsTheOwnerToTheBubble() {
        Assert.assertFalse(
            "a tap the bubble may have no listener for is not an instruction to print",
            code(STRINGS).contains("Tap to send it"),
        )
    }

    /** The file's source with its comments removed, flattened: one line, so a token cannot straddle. */
    private fun code(relative: String): String =
        String(Files.readAllBytes(root().resolve(relative)), StandardCharsets.UTF_8)
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("//[^\n]*"), " ")
            .replace(Regex("\\s+"), " ")

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

    private companion object {
        /** The bar's own home: the Kotlin surface that chooses each sentence and its action. */
        const val HELD = "ui/src/main/java/uk/xa0/tulkki/ui/composer/HeldSendSurfaces.kt"
        const val STRINGS = "ui/src/main/res/values/strings.xml"
    }
}
