package uk.xa0.tulkki.ui.conversation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert
import org.junit.Test

/**
 * The staged strip's own shapes, without an Android context: an empty strip is no strip, an id that
 * names nothing is ignored rather than thrown on, and the strip's answer is a copy the send path may
 * mutate while it iterates.
 *
 * <p>**The thumbnails are the half a JVM cell cannot reach**, and that is deliberate rather than a
 * gap: `FileBackends.get()` needs the live service, so the load's evidence is the source reading in
 * [ConversationHostTest] (the background `getPreviewForUri`), not a constructed bitmap here. What
 * this cell pins is the state around it - the empty answers and the id arithmetic
 * (`UUID.fromString` on an opaque id) that a fragment's tap or remove reaches first.
 */
class StagedAttachmentsTest {

    /** A strip whose loads never run: the scope is immediate, so a launch stays inside the call. */
    private fun staged(): StagedAttachments =
        StagedAttachments(
            previewSize = 72,
            onChanged = Runnable {},
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

    @Test
    fun anEmptyStripIsNoStripAndNamesNothing() {
        val staged = staged()
        Assert.assertFalse(staged.hasAttachments())
        Assert.assertEquals(0, staged.count())
        Assert.assertTrue("an empty list is no strip at all", staged.strip().isEmpty())
        Assert.assertTrue(staged.attachments().isEmpty())
        Assert.assertNull(staged.byId("not-a-uuid"))
        staged.release()
    }

    /**
     * The strip is named by the host's opaque id, never a filename, and an id nothing answers - or
     * one that is not a uuid at all - is no change and no exception. It is the same "the row travels
     * as its id and the host resolves it again" convention every other event keeps.
     */
    @Test
    fun anIdThatNamesNothingIsIgnoredRatherThanThrownOn() {
        val staged = staged()
        staged.remove("not-a-uuid")
        staged.remove("1f0f5a3e-3d5a-4a6e-8b7f-2c1d0e9a8b7c")
        Assert.assertFalse(staged.hasAttachments())
        Assert.assertNull(staged.byId("1f0f5a3e-3d5a-4a6e-8b7f-2c1d0e9a8b7c"))
        staged.clear()
        Assert.assertTrue(staged.strip().isEmpty())
        staged.release()
    }
}
