package uk.xa0.tulkki.ui

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert
import org.junit.Test

/**
 * The send failure's surface, pinned in the source it now lives in: both actions live on the bar, and
 * the resting chain finds the row after a restart.
 *
 * <p>A **source cell**, for the reason `HeldDoubtBarTest` is one: the bar is a `ComposeView` no JVM
 * unit-test runtime builds, and the decision behind it is `HeldSendSurfaces`, which is Kotlin and
 * reads a `Context`. That surface moved out of `ConversationFragment` when the held and failed sends
 * became Kotlin, so the cells that pinned the reading read its own home; the fragment keeps the
 * transport and the two routes the retry and "send as written" run, and one cell still pins those on
 * the fragment. The defect this exists for is exactly the gap between "the decision was covered" and
 * "the delivery was not": the tap was installed per row and the resting chain recomputed the language
 * and the key only, so a row found after a restart had no bar and no affordance at all.
 *
 * <p>What is pinned:
 *
 * <ul>
 *   <li>the failure's own surface offers the retry **and** "send as written" on the bar - the bubble
 *       is not the affordance, because the reading aid's gloss span shadows the body's listener and a
 *       one-word body leaves a couple of pixels of the retry's own tap target;
 *   <li>the resting chain scans the conversation's messages for a send failure and for a held row,
 *       so a restart re-draws the bar rather than leaving an orphan;
 *   <li>the bar has a second button slot, because a bar with one can only carry one action however
 *       the fragment is written.
 * </ul>
 */
class SendFailureBarTest {

    @Test
    fun theFailureSurfaceOffersTheRetryAndSendAsWrittenOnTheBar() {
        val bar = code(HELD)
        Assert.assertTrue(
            "the failure's own surface is a method of its own",
            bar.contains("fun showSendFailure(message: Message?)"),
        )
        Assert.assertTrue(
            "the retry is the one held-send retry, not a second route",
            bar.contains("View.OnClickListener { translateNow.accept(message) }"),
        )
        Assert.assertTrue(
            "and the owner's exception is wired beside it on the same bar",
            bar.contains("sendAsWritten.accept(message)"),
        )
        Assert.assertTrue(
            "the send-as-written label is its own copy",
            bar.contains("R.string.tulkki_hold_send_as_written"),
        )
    }

    @Test
    fun theRestingChainFindsASendFailureAfterARestart() {
        val bar = code(HELD)
        Assert.assertTrue(
            "the resting chain looks at the rows themselves, not only the language and the key",
            bar.contains("conversation.messages"),
        )
        Assert.assertTrue(
            "a send failure is the row's own persisted state, so a restart cannot lose it",
            bar.contains("isSendFailure(message)"),
        )
        Assert.assertTrue(
            "and the same scan finds a row still held, so its retry is reachable too",
            bar.contains("restingHeldSend("),
        )
        Assert.assertTrue(
            "both are drawn from the refresh that every resume and conversation update runs",
            bar.contains("restingSendFailure("),
        )
    }

    @Test
    fun aRestingHoldCarriesTheRetryWhateverStoppedIt() {
        val bar = code(HELD)
        Assert.assertTrue(
            "a recorded reason is drawn through the one sentence table, not as a bare reason",
            bar.contains("HeldSendText.holdMessage(context, reason)"),
        )
        Assert.assertTrue(
            "the cap stays the one bar with its own way out",
            bar.contains("reason == HeldSend.HoldReason.CAP_REACHED"),
        )
    }

    @Test
    fun theBarHasASecondButtonSlot() {
        Assert.assertTrue(
            "a two-action bar needs a second slot, or the alt action is never drawn",
            code(BAR).contains("val altLabel") && code(BAR).contains("val altAction"),
        )
        Assert.assertTrue(
            "and the renderer draws that second button, not only carries it",
            code(BAR).contains("TextButton(onClick = { altAction.onClick(null) })"),
        )
        Assert.assertTrue(
            "the failure's own call fills the slot with the send-as-written label",
            code(HELD).contains("context.getString(R.string.tulkki_hold_send_as_written),"),
        )
    }

    @Test
    fun theFailureSurfaceDoesNotTalkTheOwnerIntoTheBubble() {
        Assert.assertFalse(
            "the gloss span shadows the body's listener, so an instruction to tap the bubble is false",
            code(HELD).contains("Tap to send it"),
        )
        Assert.assertFalse(
            "and the shipped copy does not print it either",
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
        /** The reading's home: which row is failed, which is held, and what the bar draws. */
        const val HELD = "ui/src/main/java/uk/xa0/tulkki/ui/composer/HeldSendSurfaces.kt"

        /** The bar's renderer: the sentence and the two button slots it draws. */
        const val BAR = "ui/src/main/java/uk/xa0/tulkki/ui/composer/ComposerBar.kt"
        const val STRINGS = "ui/src/main/res/values/strings.xml"
    }
}
