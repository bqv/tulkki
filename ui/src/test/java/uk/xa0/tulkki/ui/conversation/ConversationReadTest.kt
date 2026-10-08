package uk.xa0.tulkki.ui.conversation

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.function.Consumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.messages.ConversationBodies
import uk.xa0.tulkki.data.messages.MessageSnapshot
import uk.xa0.tulkki.data.model.Message

/**
 * The host read's cells: `MessageSnapshots.watch` had no `:ui` caller, and these pin the caller that
 * closes the gap without a device, a database or a Compose runtime under test.
 *
 * <p>What a JVM cell can hold here is the subscription, not the SQLite behind it: [ConversationRead.stream]
 * opens the database, so its cover is the gate's build and the run, while [ConversationRead.subscribe] and
 * [ConversationHost.MessagesSession] take a `Flow` and so can be handed a fake stream. Three facts are
 * pinned - the read offers the snapshots it is given, a session that was never shown offers none, and the
 * release stops the watcher so no emission is left to land - plus the discipline a source reading is the
 * strongest check of: the read names nothing that spends a call.
 */
class ConversationReadTest {

    /**
     * The read's answer is the stream's own rows, in its order, and a re-emission replaces them. It is
     * the join the whole list depends on: `MessageSnapshots.watch(conversation)` re-emits one
     * conversation's rows, and nothing between the file and the session reorders, drops or copies a body.
     */
    @Test
    fun theReadOffersTheSnapshotsItIsGiven() {
        val first = listOf(snapshot("m1"), snapshot("m2"))
        val second = listOf(snapshot("m1"), snapshot("m2"), snapshot("m3"))
        val session = ConversationHost.MessagesSession()
        session.watch(flowOf(first, second), CoroutineScope(Dispatchers.Unconfined))
        Assert.assertEquals(
            "the rows are the read's own, in its order, and the later emission replaces them",
            listOf("m1", "m2", "m3"),
            session.rows?.map { it.id },
        )
        session.release()
    }

    /**
     * The host's own update path writes the same rows the watch filled. The drawn list has two
     * sources - the file's `Flow` ([ConversationRead.stream]) and the one-shot read the fragment's
     * `refresh()` pushes ([ConversationRead.read]) - and a session that kept them in separate places
     * would draw whichever arrived last. This pins that the host's push lands on the rows the watch
     * left and appends to them: the row a send or an arrival added must appear without a second
     * subscription, which is the update the three symptoms were.
     */
    @Test
    fun theHostsOwnUpdateAppendsToTheRowsTheWatchLeft() {
        val first = listOf(snapshot("m1"))
        val session = ConversationHost.MessagesSession()
        session.watch(flowOf(first), CoroutineScope(Dispatchers.Unconfined))
        Assert.assertEquals("the watch's first emission is the list", listOf("m1"), session.rows?.map { it.id })
        session.update(listOf(snapshot("m1"), snapshot("m2")))
        Assert.assertEquals(
            "a row the host read after the watch must land on the same state, not a second list",
            listOf("m1", "m2"),
            session.rows?.map { it.id },
        )
        session.release()
    }

    /**
     * The host's derived cache moves with the rows and never before them. `onRows` is the write's own
     * first step - the live host installs `XmppActivity.cacheMessageRows` there - because the reply
     * seam's quote answer is asked during the recomposition the write schedules and may not open the
     * database: a listener that ran after the state moved would let that projection read a cache one
     * write behind, and a listener that never ran would leave a reply unresolved.
     */
    @Test
    fun theHostsListenerRunsBeforeTheRowsStateMoves() {
        val session = ConversationHost.MessagesSession()
        var called = false
        var rowsSeenByListener: List<String>? = null
        session.onRows =
            Consumer<List<MessageSnapshot>> { _ ->
                called = true
                rowsSeenByListener = session.rows?.map { it.id }
            }
        session.update(listOf(snapshot("m1")))
        Assert.assertTrue("the listener runs for the write", called)
        Assert.assertNull(
            "and it runs before the state moves, so the projection it feeds sees the new cache with the new rows",
            rowsSeenByListener,
        )
        Assert.assertEquals("while the write itself lands", listOf("m1"), session.rows?.map { it.id })
    }

    /**
     * The off state offers none: a session that was never shown has no rows, and `null` is what
     * `ChatState` reads as "the first emission has not landed" rather than as an empty conversation. It
     * is the same three-valued shape the list's state keeps, and the interpreter's off state does not
     * suppress these rows - a plain client still draws its messages.
     */
    @Test
    fun offOffersNoRows() {
        val session = ConversationHost.MessagesSession()
        Assert.assertNull("a session that was never shown offers nothing", session.rows)
        session.release()
        Assert.assertNull("releasing a session that never watched leaves it offering nothing", session.rows)
    }

    /**
     * The lifecycle releases: after `release()` a later emission from a live source does not land, which
     * is what "never leave a live watcher behind" means. The source is hot, so the second value is a real
     * emission the cancelled collector must not receive.
     */
    @Test
    fun theReleaseStopsTheWatcher() {
        val source = MutableStateFlow(listOf(snapshot("m1")))
        val session = ConversationHost.MessagesSession()
        session.watch(source, CoroutineScope(Dispatchers.Unconfined))
        Assert.assertEquals("the current list arrives on subscribe", listOf("m1"), session.rows?.map { it.id })
        session.release()
        source.value = listOf(snapshot("m1"), snapshot("m2"))
        Assert.assertEquals(
            "a released watcher must not land another emission",
            listOf("m1"),
            session.rows?.map { it.id },
        )
    }

    /**
     * And the read spends nothing: it names no translation request, no held send and no client, so the
     * surface that draws snapshots cannot buy one. A source reading is the strongest check a module with
     * no service to fake can make of its own read.
     */
    @Test
    fun theReadSpendsNothing() {
        val read = read("ui/src/main/java/uk/xa0/tulkki/ui/conversation/ConversationRead.kt")
        for (spend in SPENDING) {
            Assert.assertFalse("the read must not run $spend", read.contains(spend))
        }
        Assert.assertFalse("the read must not name a DeepSeek client", read.contains("DeepSeek"))
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

    private fun snapshot(id: String): MessageSnapshot =
        MessageSnapshot(
            id = id,
            conversationId = "c1",
            timeSent = 0L,
            counterpart = "them@example.org",
            trueCounterpart = null,
            type = Message.TYPE_TEXT.toLong(),
            status = Message.STATUS_RECEIVED.toLong(),
            encryption = Message.ENCRYPTION_NONE.toLong(),
            delivery = 0L,
            read = 0L,
            deleted = 0L,
            fileDeleted = 0L,
            markable = 0L,
            oob = 0L,
            carbon = 0L,
            retractId = null,
            edited = null,
            serverMsgId = null,
            remoteMsgId = null,
            axolotlFingerprint = null,
            occupantId = null,
            relativeFilePath = null,
            fileParams = null,
            oobUri = null,
            errorMsg = null,
            bodyLanguage = null,
            reactions = null,
            readByMarkers = null,
            translationState = Message.TRANSLATION_NONE.toLong(),
            translationLang = null,
            timeReceived = null,
            subject = null,
            expireAt = 0L,
            ephemeralTimer = 0L,
            notificationDismissed = null,
            bodies = ConversationBodies(translated = null, original = null),
        )

    private companion object {
        /** Every symbol a translation or a send is spelled with, none of which the read may run. */
        val SPENDING =
            listOf(
                "requestTranslation(",
                "sendHeldNow(",
                "sendAsWritten(",
                "translateHeldNow(",
            )
    }
}
