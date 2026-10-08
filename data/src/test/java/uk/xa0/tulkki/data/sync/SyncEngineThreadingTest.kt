package uk.xa0.tulkki.data.sync

import java.lang.reflect.Proxy
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import uk.xa0.tulkki.xmpp.mam.MamFin
import uk.xa0.tulkki.xmpp.mam.MamReference
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef

/**
 * S5-6's threading cell: the engine does its work on its own thread, whichever entry point fires.
 *
 * <p><strong>What it pins, and why it is a cell rather than a comment.</strong> The owner's phone drew
 * the conversation list and then died 17 ms later with `Cannot access database on the main thread`
 * from `SyncEngine.persist`, reached through `XmppConnectionService.switchToForeground` →
 * `onServiceConnected` - i.e. the engine was called from the **main** thread and ran Room's blocking
 * calls right there. The rule that prevents it is "every event body goes through `dispatch`", and the
 * only way to hold that rule on a host is to hand the engine a store that records which thread touched
 * it and an executor that records what it was asked to run.
 *
 * <p><strong>What it does not exercise.</strong> A body is never run here, so the *content* of a
 * transition - and therefore the sweep the sink would receive - is not this cell's: the arithmetic is
 * `SyncStateMachineTest`'s, the ledger's is `GapLedgerTest`'s, and the sweep read is
 * `GapSweepQueryTest`'s. What is asserted is the dispatch itself, and the two synchronous asks that
 * cannot be dispatched (`anchorFor`): they run on the engine's thread and the caller waits, which is
 * what this cell measures by the thread the store was touched on.
 */
class SyncEngineThreadingTest {

    /** The store was touched, on this thread. Thrown rather than recorded so a touch cannot be missed. */
    private class TouchedOn(val thread: String) : RuntimeException("the store was touched on $thread")

    /**
     * A store that touches nothing: every accessor records the thread it was called from and then
     * throws, so an engine that reaches the file on the caller's thread fails loudly.
     */
    private class ThrowingStore : SyncStore {
        val touched = ArrayList<String>()

        private fun touch(): Nothing {
            val name = Thread.currentThread().name
            touched.add(name)
            throw TouchedOn(name)
        }

        override fun syncCursorDao() = touch()

        override fun syncGapDao() = touch()

        override fun syncConversationDao() = touch()

        override fun conversationDao() = touch()

        override fun markDelivery(conversationUuid: String, timeSent: Long, marker: Long) = touch()
    }

    /** An executor that records what it was asked to run and runs nothing. */
    private class Recording : Executor {
        val tasks = ArrayList<Runnable>()

        override fun execute(command: Runnable) {
            tasks.add(command)
        }
    }

    /** An executor that runs each task on a thread of the engine's own name, and waits for it. */
    private class OnItsOwnThread : Executor {
        override fun execute(command: Runnable) {
            val thread = Thread(command, "tulkki-sync")
            thread.start()
            thread.join()
        }
    }

    /**
     * Every event the island can fire is dispatched and touches nothing on the caller's thread.
     *
     * <p>The six are the whole seam: the session was established, a `<fin>`, an abort, a stored
     * message, CSI, and the session's end. The store throws on any touch and the executor runs
     * nothing, so an engine that did its work inline would fail on the first accessor - and the
     * count of recorded tasks is what says each of the six was dispatched rather than skipped.
     */
    @Test
    fun noEventTouchesTheStoreOnTheCallersThread() {
        val store = ThrowingStore()
        val worker = Recording()
        val engine = SyncEngine.forTest(store, worker, SweepSink { _, _ -> })
        val account = account("acct-1")
        val fin = fin()

        engine.onSessionEstablished(account, 1_000L, false)
        engine.onMamFin(account, fin)
        engine.onMamAborted(account, uk.xa0.tulkki.xmpp.mam.MamAbort.TIMEOUT)
        engine.onMessageStored(account, conversation("conv-1"), 10L, true)
        engine.onClientStateChanged(account, true)
        engine.onSessionEnded(account)

        assertEquals(
            "nothing may reach the store on the caller's thread; the touches were $store.touched",
            emptyList<String>(),
            store.touched,
        )
        assertEquals(
            "and each of the six events must have been handed to the engine's own thread",
            6,
            worker.tasks.size,
        )
    }

    /**
     * The two `anchorFor` asks answer on the engine's thread, with the caller waiting. That is the one
     * remaining synchronous touch of the file, and the cell measures the thread it happens on rather
     * than trusting the comment in `await`.
     */
    @Test
    fun theAnchorAskTouchesTheStoreOnTheEnginesOwnThreadAndNotTheCallers() {
        val store = ThrowingStore()
        val engine = SyncEngine.forTest(store, OnItsOwnThread(), SweepSink { _, _ -> })
        val caller = Thread.currentThread().name

        try {
            engine.anchorFor(account("acct-1"))
            fail("the store throws when touched, so the ask cannot have answered")
        } catch (e: ExecutionException) {
            val touched = e.cause as TouchedOn
            assertEquals(
                "the DAO must be reached on the engine's own thread, not the caller's",
                "tulkki-sync",
                touched.thread,
            )
            assertTrue(
                "and the caller is a different thread, which is the whole point: $caller",
                caller != touched.thread,
            )
        }
    }

    /** `seedCursor` is a write, so it is dispatched like an event rather than run where it is asked. */
    @Test
    fun seedCursorIsDispatchedRatherThanWriteOnTheCallersThread() {
        val store = ThrowingStore()
        val worker = Recording()
        val engine = SyncEngine.forTest(store, worker, SweepSink { _, _ -> })

        engine.seedCursor(account("acct-1"), uk.xa0.tulkki.xmpp.mam.MamReference(5L, "ref"))

        assertEquals("the caller's thread saw nothing", emptyList<String>(), store.touched)
        assertEquals("and the write was handed to the engine's thread", 1, worker.tasks.size)
    }

    /**
     * The call site, which is where the phone's crash actually was: the host hands the engine's
     * answers on through the sink rather than unwrapping a returned list, so there is no path in which
     * an event's body runs on the thread that called it.
     */
    @Test
    fun theHostHandsSweepsOnThroughTheSinkAndDoesNotUnwrapTheEngine() {
        val host = uk.xa0.tulkki.data.RepoFiles.read("app/src/main/java/uk/xa0/tulkki/app/XmppTulkkiHost.kt")
        assertTrue(
            "the host must install the engine's own sweep callback",
            host.contains("SyncEngine.installSweepSink(") && host.contains("onSweep"),
        )
        for (entry in
            listOf(
                "onSessionEstablished",
                "onMamFin",
                "onMamAborted",
                "onClientStateChanged",
                "onSessionEnded",
            )) {
            assertTrue(
                "no override may wrap the engine's call in `sweep(...)` again: " + entry,
                !host.contains("sweep(engine()." + entry),
            )
        }
    }

    /** An `AccountRef` that answers its uuid and throws for everything else the seam could ask. */
    private fun account(uuid: String): AccountRef =
        Proxy.newProxyInstance(
            AccountRef::class.java.classLoader,
            arrayOf(AccountRef::class.java),
        ) { _, method, _ ->
            if (method.name == "getUuid") uuid else throw UnsupportedOperationException(method.name)
        } as AccountRef

    /** A `ConversationRef` that answers its uuid and throws for everything else. */
    private fun conversation(uuid: String): ConversationRef =
        Proxy.newProxyInstance(
            ConversationRef::class.java.classLoader,
            arrayOf(ConversationRef::class.java),
        ) { _, method, _ ->
            if (method.name == "getUuid") uuid else throw UnsupportedOperationException(method.name)
        } as ConversationRef

    /**
     * A `MamFin` the caller thread must not read: the event is dispatched before it is looked at, and
     * this one is built with an account ref that throws, so any read of it on the caller's thread is
     * a failure rather than a value.
     */
    private fun fin(): MamFin =
        MamFin(
            0L,
            0L,
            "ref",
            0,
            0,
            false,
            MamReference(0L),
            MamReference(0L),
            null,
            uk.xa0.tulkki.xmpp.services.MessageArchiveService.PagingOrder.NORMAL,
            true,
            account("never-read"),
            null,
        )
}
