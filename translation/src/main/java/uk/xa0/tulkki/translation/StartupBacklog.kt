package uk.xa0.tulkki.translation

import android.content.Context
import android.util.Log
import java.util.ArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import uk.xa0.tulkki.data.model.Message

/**
 * The gap a reconnect handed over, translated once it is in the database.
 *
 * <p>The rule this sits inside is unchanged and is the reason it is safe: <strong>Tulkki does not
 * translate the archive.</strong> What a server hands over on a reconnect is the <em>gap</em> - the
 * account's own catch-up query starts at the newest message this device already has, and even after a
 * long absence upstream never asks for more than `Config.MAM_MAX_CATCHUP` (five days) or
 * `Config.MAM_MAX_MESSAGES` (750) at a time. What the owner changed (2026-09-27) is that the gap is
 * translated <em>in full</em> rather than ten messages of it.
 *
 * <p><strong>The gap is recorded now, not inferred.</strong> S5-4 retired the inference this class
 * used to run on: "archive-delivered" is a column now (`messages.delivery`), the regions are
 * `sync_gap` rows, and the sync engine hands this class the exact rows its ledger opened - see
 * [sweep]. Nothing here reads a window, keeps a watermark or waits on a clock, and what proves a row
 * is new is the row itself: a message whose translation state has moved on is not a candidate, and
 * that holds across restarts.
 *
 * <p><strong>The cap is what bounds the spending.</strong> The pass enqueues; the queue's own pump
 * translates in batches of eight and stops at the daily token cap, which also stops sending - the
 * owner accepted that trade when they asked for the whole gap. Nothing here is counted twice.
 *
 * <p>The deciding - which rows qualify and in what order - is pure Kotlin and is exercised by JVM unit
 * tests. Reading the rows is [TranslationStore]'s job, and running the pass is [sweep], which the
 * composition root calls with the engine's own answer when a gap closes. `Row`'s five values stay
 * `@JvmField`s because the Java callers and the tests read them as fields.
 */
object StartupBacklog {

    /**
     * How many rows one live-miss sweep reads.
     *
     * <p>750 is upstream's own ceiling on one catch-up (`Config.MAM_MAX_MESSAGES`), so one read
     * covers everything a single catch-up can have delivered. The gap sweep does not need it - a gap
     * is bounded by construction - but the per-conversation live-miss read is a window over rows
     * nothing recorded, so its ceiling is this one.
     */
    const val READ_LIMIT = 750

    private const val TAG = "Tulkki"

    /** One thread for the pass: one read, a little local detection, one enqueue. */
    private val EXECUTOR: ExecutorService =
            Executors.newSingleThreadExecutor { runnable ->
                val thread = Thread(runnable, "tulkki-backlog")
                thread.isDaemon = true
                thread
            }

    /**
     * One stored message, as the selection needs it: its identity, the two facts that decide it, and
     * the same candidate the receive path builds for a live message.
     */
    class Row(
            @JvmField val messageUuid: String?,
            @JvmField val conversationUuid: String?,
            /**
             * The message's own time - when its sender sent it - which is what "oldest first" means
             * here. Deliberately not the row's arrival time: history is inserted in one burst, so
             * every message in a catch-up would arrive at the same moment and reading order would
             * mean nothing.
             */
            @JvmField val timeSent: Long,
            /** [Message.TRANSLATION_NONE] unless something has already answered for this row. */
            @JvmField val translationState: Int,
            /** The gate's own view of this message, built by `TranslationHooks.candidate`. */
            @JvmField val candidate: TranslationDecision.Candidate?
    )

    /**
     * Every message of `rows` that needs translating, oldest first.
     *
     * <p>Oldest first because the cap can still stop the pass's work part-way: the queue drains in the
     * order it was filled, so filling it in reading order means a truncated gap is missing its
     * <em>end</em> - the messages nearest the bottom of the conversation - rather than its beginning.
     *
     * <p>The interpreter is threaded to the rules that require it. Off - or absent - this returns
     * nothing, because [eligible] answers no for every row; there is no guard of its own here any
     * more, because the callers ([sweep]) do not start the pass at all when the interpreter is off.
     *
     * @param rows the candidate rows, in any order
     * @param appLanguage the app language, which a message already in it is not worth buying
     * @param interpreter the interpreter, threaded to the rules that require it
     */
    @JvmStatic
    fun everything(
            rows: List<Row?>?,
            appLanguage: String?,
            interpreter: Interpreter?
    ): List<Row> {
        val examined = ArrayList<Row>()
        if (rows != null) {
            for (row in rows) {
                if (row != null) {
                    examined.add(row)
                }
            }
        }
        // Oldest first, here rather than in the SQL: the order is a rule about what a truncated gap
        // looks like, and a rule is worth pinning rather than trusting a query's ORDER BY to keep.
        examined.sortWith(compareBy { it.timeSent })
        val chosen = ArrayList<Row>()
        for (row in examined) {
            if (eligible(row, appLanguage, interpreter)) {
                chosen.add(row)
            }
        }
        return chosen
    }

    /**
     * Whether this row is one the pass may spend on.
     *
     * <p>The structural half is [TranslationDecision.isRequestable] rather than
     * [TranslationDecision.isEligible], and that is the whole of the exception the owner
     * accepted: `isEligible` is exactly `isRequestable` plus the two archive clauses ("not from a
     * query", "not delay-stamped"), and those two clauses are what the bounded pass exists to step
     * around. Nothing else is relaxed - still only somebody else's message, still no file, no edit,
     * no reaction, no deleted row, and still nothing whose body is ciphertext.
     *
     * <p>The local word is the other half: [TranslationDecision.classify] refuses a ping, the
     * conversation's own name, a link, a code and anything else with no language in it, and marks a
     * body already in the app language as needing no request.
     *
     * <p>The [Interpreter] reaches this method because the two rules it asks now require it.
     * With the interpreter off those two rules answer no by themselves, so the guard below is not the
     * only thing standing between an off interpreter and a chosen row. It is kept as the first
     * statement anyway, for two reasons: this method's own contract is "off is false" and it should
     * not depend on another class's clause order, and those two rules declare the interpreter
     * <em>non-null</em> - they are Kotlin - so without it a caller that handed nothing over would
     * throw instead of getting the answer the step's convention gives every other rule.
     */
    @JvmStatic
    fun eligible(row: Row?, appLanguage: String?, interpreter: Interpreter?): Boolean {
        if (interpreter == null || !interpreter.enabled()) {
            return false
        }
        if (row == null || row.candidate == null) {
            return false
        }
        if (row.translationState != Message.TRANSLATION_NONE) {
            // Answered once, whatever the answer was: translated, found already in the app language,
            // or given up on. A second pass must never buy any of them again, and this is the whole
            // of the never-twice property - it is read from the row, so it holds across restarts.
            return false
        }
        if (!TranslationDecision.isRequestable(row.candidate, interpreter)) {
            return false
        }
        return TranslationDecision.classify(
                        row.candidate.body,
                        appLanguage,
                        row.candidate.conversationName,
                        interpreter) ==
                TranslationDecision.Verdict.TRANSLATE
    }

    /**
     * The gap sweep: the rows the ledger's open regions name, enqueued in reading order.
     *
     * <p>This is what replaces the retired pass's trigger. `MessageArchiveService.processFin`
     * used to ask `!anyCatchup(account)` - a shared mutable set - and call an installed catch-up
     * hook; it now reports the `<fin>` to the sync engine, and the engine answers here with the
     * uuids of the `delivery = 1` rows inside the regions whose close just closed the account's
     * gap.
     *
     * <p>The interpreter guard is the first statement. Off, the pass does not begin, no row is read
     * and nothing is enqueued.
     *
     * <p>The work is handed to its own thread and the caller returns immediately: this is reached from
     * the connection's reader thread.
     */
    @JvmStatic
    fun sweep(context: Context?, candidates: List<String>?) {
        if (context == null || candidates == null || candidates.isEmpty()) {
            return
        }
        if (!TranslationSettings.get(context).interpreter().enabled()) {
            return
        }
        try {
            EXECUTOR.execute { pass(context, candidates) }
        } catch (e: RuntimeException) {
            Log.e(TAG, "could not start the pass over the gap the catch-up closed", e)
        }
    }

    /**
     * The sweep itself, on its own thread.
     *
     * <p>The database handle is not the service's: [TranslationStore] reads and writes through
     * the process-wide backend, so a thread that outlives this service still uses the same open
     * database, and the queue rows it writes are wanted across restarts by design. Everything in this
     * method is a [RuntimeException] away from being logged and dropped rather than crashing.
     */
    private fun pass(context: Context, candidates: List<String>) {
        try {
            val host = EngineHost.of(context)
            val settings = TranslationSettings.get(context)
            val rows = TranslationStore(context).historyCandidates(candidates)
            val chosen = everything(rows, settings.appLanguage(), settings.interpreter())
            if (chosen.isNotEmpty()) {
                // The existing queue, in the existing way, followed by the existing kick: the cap,
                // the token counter, the per-message language check, the retry and the failure record
                // are all the queue's and are not duplicated here.
                val translations = host.translationService(context)
                val now = System.currentTimeMillis()
                for (row in chosen) {
                    translations.enqueue(
                            row.messageUuid, row.conversationUuid, row.candidate?.body, now)
                }
                host.kickTranslationWork(context)
            }
            Log.d(
                    TAG,
                    "the gap sweep read " + rows.size + " messages and queued " + chosen.size)
        } catch (e: RuntimeException) {
            // The rows are still in the database and the ledger still holds the regions: a later
            // gap close sweeps them again, and the queue's own insert is a no-op for what it has.
            Log.e(TAG, "could not translate the rows the gap sweep named", e)
        }
    }
}
