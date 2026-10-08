package uk.xa0.tulkki.ui.conversation

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.ui.projection.Anchor

/**
 * The conversation list's scroll-stability rules, `Design: the Compose UI` §4.3, and the two shapes
 * the owner's report named: "the actual scroll-to-bottom state is broken" and the "scroll to bottom"
 * control "floating somewhere at the top of the screen".
 *
 * <p>The decisions are [Anchor]'s and pure, so they are cells here rather than a device reading. What
 * is left in the screen is only the `LazyListState` write a JVM cell cannot reach, and that half is
 * pinned as source: a grown layout re-anchors a following reader, taps and jumps never write the pin,
 * and the control is the Compose list's own drawing while the Java view stays `GONE`.
 */
class AnchoredListRulesTest {

    /**
     * The owner's "last few messages hidden": a row whose top is drawn while its bottom is still cut
     * is not at the bottom. The old test - `lastVisible >= lastIndex` - called it the bottom and let
     * the jump-to-latest control hide over a half-hidden newest message.
     */
    @Test
    fun aFullyVisibleNewestRowIsTheOnlyBottom() {
        Assert.assertTrue(
            "the newest row, its whole height inside the viewport",
            Anchor.newestFullyVisible(
                lastIndex = 4,
                lastVisibleIndex = 4,
                lastVisibleBottom = 900,
                viewportEnd = 900,
            ),
        )
        Assert.assertTrue(
            "and the ordinary case with the last row well inside",
            Anchor.newestFullyVisible(
                lastIndex = 4,
                lastVisibleIndex = 4,
                lastVisibleBottom = 860,
                viewportEnd = 900,
            ),
        )
    }

    /** And the three ways it is not the bottom, each of which the old test answered wrong. */
    @Test
    fun aClippedOrAbsentNewestRowIsNotTheBottom() {
        Assert.assertFalse(
            "the newest row's bottom is past the viewport's end",
            Anchor.newestFullyVisible(
                lastIndex = 4,
                lastVisibleIndex = 4,
                lastVisibleBottom = 940,
                viewportEnd = 900,
            ),
        )
        Assert.assertFalse(
            "the newest row is not drawn at all",
            Anchor.newestFullyVisible(
                lastIndex = 4,
                lastVisibleIndex = 3,
                lastVisibleBottom = 900,
                viewportEnd = 900,
            ),
        )
        Assert.assertFalse(
            "a list with no items yet is not at a bottom",
            Anchor.newestFullyVisible(
                lastIndex = -1,
                lastVisibleIndex = -1,
                lastVisibleBottom = 0,
                viewportEnd = 900,
            ),
        )
    }

    /**
     * The pin is the reader's own answer, so it is written only at the end of the reader's own scroll.
     * This is the rule the old shape got wrong: it read its own re-anchoring scroll and the frames a
     * grown layout rewrote as "the reader scrolled away", unpinned a following reader, and every later
     * arrival then landed below the fold.
     */
    @Test
    fun thePinIsWrittenOnlyWhenTheReadersHandHasStopped() {
        Assert.assertTrue(
            "the hand was moving and has stopped, and the position moved",
            Anchor.settled(moved = true, scrolling = false, wasScrolling = true),
        )
        Assert.assertFalse(
            "a programmatic scroll this screen started is not the reader leaving",
            Anchor.settled(moved = true, scrolling = false, wasScrolling = false),
        )
        Assert.assertFalse(
            "a reading taken mid-motion is not the end of one",
            Anchor.settled(moved = true, scrolling = true, wasScrolling = true),
        )
        Assert.assertFalse(
            "a layout that grew changes the drawing, never the position",
            Anchor.settled(moved = false, scrolling = false, wasScrolling = true),
        )
    }

    /**
     * A following reader whose newest row is below the fold is carried back to it, and the branch does
     * not wait for a key to change: that is the tap on a covered row whose translation replaces the
     * cover and changes the row's height with no id changing.
     */
    @Test
    fun aGrownLayoutReAnchorsAFollowingReaderWithoutAKeyChange() {
        val screen = read(SCREEN)
        Assert.assertTrue(
            "the follow branch re-anchors on the lived bottom fact, not only on the ids",
            screen.contains("pinned && !newest && reading.canScrollForward ->") &&
                screen.contains("listState.scrollToItem(reading.keys.lastIndex)"),
        )
        Assert.assertTrue(
            "and the pin is written only at the end of the reader's own scroll",
            screen.contains("Anchor.settled(") &&
                screen.contains("pinned = !reading.canScrollForward"),
        )
    }

    /**
     * The jump-to-latest control is the Compose list's drawing and the Java view is gone for good.
     * The owner saw the Java `FloatingActionButton` "floating somewhere at the top of the screen and
     * moving about randomly": its `RelativeLayout` anchor was the `GONE` `messages_view`, a zero-height
     * box, and showing it was the old `toggleScrollDownButton`'s whole job. The one fact the drawn
     * control still needs - the count of messages that arrived while the reader was away - travels on
     * the session, so the Java badge has no orphan to sit on either.
     */
    @Test
    fun theControlIsTheComposeListsAndTheJavaOneIsNeverShown() {
        val screen = read(SCREEN)
        Assert.assertTrue(
            "the list draws the control itself",
            screen.contains("private fun ScrollToBottom(") &&
                screen.contains("if (!anchored.atBottom)") &&
                screen.contains("anchored.jumpTo(items.lastIndex, follow = true)"),
        )
        val fragment = read(FRAGMENT)
        Assert.assertFalse(
            "neither the Java button nor the badge it anchored is touched at all any more",
            fragment.contains("scrollToBottomButton") || fragment.contains("unreadCountCustomView"),
        )
        Assert.assertTrue(
            "and the count rides the session the Compose control draws from",
            fragment.contains("tulkkiMessagesSession.unread("),
        )
        val host = read(HOST)
        Assert.assertTrue(
            "the page hands the count and the history-part jump to the list",
            host.contains("unreadCount = messages.unreadCount,") &&
                host.contains("onJumpToLatest = { onJumpToLatest.run() },"),
        )
    }

    private fun read(relative: String): String =
        String(Files.readAllBytes(locate(relative).toAbsolutePath()), StandardCharsets.UTF_8)

    private fun locate(relative: String): Path = root().resolve(relative)

    /** The repository root, found by its `settings.gradle.kts` marker above the module working directory. */
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
        const val SCREEN = "ui/src/main/java/uk/xa0/tulkki/ui/conversation/ConversationScreen.kt"
        const val HOST = "ui/src/main/java/uk/xa0/tulkki/ui/conversation/ConversationHost.kt"
        const val FRAGMENT = "ui/src/main/java/uk/xa0/tulkki/ui/ConversationFragment.kt"
    }
}
