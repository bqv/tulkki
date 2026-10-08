package uk.xa0.tulkki.app

import android.content.Context
import android.util.Log
import java.time.ZoneId
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.translation.DeepSeekClient
import uk.xa0.tulkki.translation.DisplayedBody
import uk.xa0.tulkki.translation.EnglishLookup
import uk.xa0.tulkki.translation.EnglishRow
import uk.xa0.tulkki.translation.FailureCause
import uk.xa0.tulkki.translation.HeldSend
import uk.xa0.tulkki.translation.Interpreter
import uk.xa0.tulkki.translation.TranslationCache
import uk.xa0.tulkki.translation.TranslationDecision
import uk.xa0.tulkki.translation.TranslationQueue
import uk.xa0.tulkki.translation.TranslationService
import uk.xa0.tulkki.translation.TranslationSettings
import uk.xa0.tulkki.translation.TranslationStore
import uk.xa0.tulkki.xmpp.services.XmppConnectionService

/**
 * The one place the receive path, the tap and the translation machinery meet.
 *
 * <p>There are two ways a message may be translated, and the difference is who asked. The automatic
 * pass starts here too, and it starts with the strictest question in the project: <em>did this
 * message arrive live?</em> Message Archive Management still inserts fetched history into
 * conversations upstream, and translating that would be years of messages in one burst - the exact
 * failure this feature exists to avoid. The two signals are the archive query that carried the
 * message and the XEP-0203 delay stamp on it, and either one alone is disqualifying.
 *
 * <p>The other way is a tap: one message, pointed at by a person, and [requestOne] is it. It may
 * translate a message the automatic pass refuses - that is the whole point of the gesture - but it is
 * still one message, still against the same daily cap and the same token counter, and it still cannot
 * translate a ciphertext body or a reaction.
 *
 * <p>Nothing expensive happens on the calling thread: a body that passes a gate is put in a table and
 * a job is scheduled. The request, the cap and the retry all happen later, off this thread.
 *
 * <p><strong>With the interpreter off, neither way in runs at all.</strong> That is not a second code
 * path and not a mode kept in step by hand: it is the one derived predicate ([Interpreter],
 * MIGRATION.md "Design: the interpreter off-switch" §1), consulted at exactly one place per way in -
 * [shouldQueue] for the automatic pass and [shouldRequest] for a tap - and both are extracted so a JVM
 * test drives the receive path's own decision instead of a hand-built predicate. Below either seam
 * nothing happens: no queue row, no WorkManager kick, no English bought, no service built. "Off" is
 * not a message that gets enqueued and then dropped.
 *
 * <p>Every entry point the Java callers name is a companion `@JvmStatic`: `XmppTulkkiHost` and
 * `UiAppHost` call `get`, `onIncomingMessage`, `pendingCount` and `requestOne`, `TranslationWorker`
 * calls `get`, and `TranslationHooksTest` calls `candidate`, `shouldQueue` and `shouldRequest`. Those
 * last two were package-private in the Java; they are `public` here because an `internal` companion
 * member is name-mangled and the Java test could no longer call it.
 */
class TranslationHooks private constructor() {

    /**
     * Where the answer to a tap goes. Called on the tap's own thread, never the main one.
     *
     * <p>It is a `fun interface` so Kotlin callers may pass a lambda; the one Java caller
     * (`UiAppHost`, `listener::accept`) needs no marker, since a Java method reference implements any
     * single-abstract-method interface.
     */
    fun interface Listener {
        /**
         * The attempt is over. {@code refusal} is what the bubble should say about not being able to
         * translate at all, or {@code null} when the request was attempted and the queue's own
         * write-back is what changes the message.
         */
        fun onFinished(refusal: DisplayedBody.Cover?)
    }

    companion object {

        private const val TAG = "Tulkki"

        @Volatile
        private var instance: TranslationService? = null

        /**
         * One thread for the taps: each is a single message's worth of work, and two of them must not
         * race the same daily cap. A daemon thread, because a tap must never keep the process alive.
         */
        private val EXECUTOR: ExecutorService =
                Executors.newSingleThreadExecutor { runnable ->
                    val thread = Thread(runnable, "tulkki-tap")
                    thread.isDaemon = true
                    thread
                }

        /** The process-wide service, built on first use. C1: `public` for the callers the move left in
         * `uk.xa0.tulkki.translation` — the access it had was an accident of the shared package. */
        @JvmStatic
        fun get(context: Context): TranslationService {
            val existing = instance
            if (existing != null) {
                return existing
            }
            synchronized(TranslationHooks::class.java) {
                val again = instance
                if (again != null) {
                    return again
                }
                val store = TranslationStore(context)
                val settings = TranslationSettings.get(context)
                val built =
                        TranslationService(
                                TranslationQueue(store),
                                TranslationCache(store),
                                settings,
                                // Tulkki: the engine's write side of "what Tulkki has done
                                // today", handed over as the :translation-declared port rather
                                // than by naming :ui's class from the engine - see
                                // TranslationActivityPort.
                                settings.activity(),
                                { apiKey ->
                                    val client = DeepSeekClient(apiKey)
                                    TranslationService.Client.over(client)
                                },
                                store,
                                ZoneId.systemDefault(),
                                { message -> Log.d(TAG, message) })
                instance = built
                return built
            }
        }

        /** How many messages are waiting for a translation: what the usage screen's "waiting" means. */
        @JvmStatic
        fun pendingCount(context: Context): Int = get(context).pendingCount()

        /**
         * Called from the receive path, once the message row exists.
         *
         * <p>With the interpreter off this method enqueues nothing: see [shouldQueue], which is the
         * only place either way in asks the question.
         *
         * @param fromArchive the message came out of a MAM query ({@code query != null} upstream)
         * @param delayed it carries a XEP-0203 delay stamp, so it was delivered late rather than now
         * @param replacement it is an edit, correction or retraction of an earlier message
         */
        @JvmStatic
        fun onIncomingMessage(
                service: XmppConnectionService,
                message: Message,
                fromArchive: Boolean,
                delayed: Boolean,
                replacement: Boolean) {
            try {
                // The function and the local would share the name in Kotlin, where a local is in scope
                // for the rest of the block; the Java got away with it because methods live in their
                // own namespace. The call is qualified so nothing here reads the local back.
                val candidate =
                        TranslationHooks.candidate(
                                message,
                                // true: drop the reply fallback. The quoted text is rendered from the
                                // message it quotes, so translating it would pay twice for somebody
                                // else's words.
                                message.getBody(true),
                                fromArchive,
                                delayed,
                                replacement)
                if (candidate.fromArchive || candidate.delayed) {
                    // History, refused by design and refused in bulk: a line per archived message
                    // would flood the log and tell nobody anything.
                    return
                }
                val settings = TranslationSettings.get(service)
                val interpreter = settings.interpreter()
                if (!shouldQueue(candidate, interpreter)) {
                    if (interpreter.enabled()) {
                        // Only the rules can have said no here, because the interpreter was on: a live
                        // message from someone else is the one shape that must never be refused at this
                        // point, so a refusal is a bug and the log has to name the condition that did it.
                        Log.d(
                                TAG,
                                "a live message will not be translated: " + refusal(message, candidate))
                    }
                    // With the interpreter off, "no" is not a refusal either: the app is simply a plain
                    // client. Nothing below this line runs, which is what "off" has to mean - no queue
                    // row, no kick, no purchase.
                    return
                }
                val conversation = message.getConversation()
                if (conversation == null) {
                    // A received message with no conversation cannot be queued: the row it needs lives
                    // under a conversation uuid, which is why the call below dereferences one. The NPE
                    // this guard replaces was at least visible in the catch at the foot of this method,
                    // and a bare `return` here would not be - so the drop says so, at the same level as
                    // the other skip lines in this hook.
                    Log.d(TAG, "a live message has no conversation; not queued for translation")
                    return
                }
                get(service)
                        .enqueue(
                                message.getUuid(),
                                conversation.getUuid(),
                                candidate.body,
                                System.currentTimeMillis())
                // The counterweight to the refusal line: a live message that produced no log line at all
                // is a message this hook was never called for, which is a different bug.
                Log.d(TAG, "queued a live message for translation")
                TranslationWork.kick(service)
                // Tulkki: with "tap to unblur" off, the English is bought now and shown blurred until
                // the owner taps it. This is the only call site of the eager half, and it is here for a
                // reason: everything above is the verdict that says this message may be spent on at all
                // - it arrived live, it is plain text, it is not a file, an edit or a reaction, and it
                // has language in it. Asking for an English anywhere that has a body but no verdict
                // would send a ciphertext blob to the API; asking from the bubble instead would buy one
                // for every message a scroll re-binds.
                //
                // Tulkki: with the interpreter off this line is unreachable twice over - the guard above
                // returns before it - and `buysOnArrival` answers `false` on its own first statement as
                // well, so no later caller can reach `EnglishLookup.prefetch` from an off interpreter.
                if (EnglishRow.buysOnArrival(
                        settings.showBlurredEnglish(),
                        settings.unblurEnglishOnTap(),
                        settings.appLanguage(),
                        settings.interpreter())) {
                    EnglishLookup.prefetch(service, candidate.body)
                }
            } catch (e: RuntimeException) {
                // Whatever happens here, the message itself is already stored and must still be shown.
                Log.e(TAG, "could not queue a received message for translation", e)
            }
        }

        /**
         * The receive path's one question: may this arriving message be queued for translation?
         *
         * <p>Extracted rather than inlined so the JVM test drives the receive path's own decision - the
         * real mapping, the real rule, the real interpreter - instead of a hand-built predicate that
         * only looks like it (MIGRATION.md "Design: the interpreter off-switch" §5, and §2.1 where this
         * is "the guard"). The [Interpreter] is a required argument, so a call site cannot compile
         * without deciding, and the off state is one derived setting rather than a second code path.
         *
         * <p>{@code false} is the whole of the off state: everything downstream - the queue row, the
         * WorkManager kick, the English prefetch, [get] itself - is skipped, not performed and then
         * undone. The archive and delay clauses stay in the caller, where they are refused silently and
         * in bulk.
         *
         * <p>It is a delegation now rather than {@code interpreter.enabled() && isEligible(candidate)}:
         * the interpreter is a required argument of the rule itself, so there is nothing left to say
         * here. Its own signature follows the rules' argument order - what is being asked about first,
         * the interpreter last - so there is one call shape for this parameter everywhere.
         */
        @JvmStatic
        fun shouldQueue(
                candidate: TranslationDecision.Candidate,
                interpreter: Interpreter): Boolean =
                TranslationDecision.isEligible(candidate, interpreter)

        /**
         * The tap's one question: may this one message be enqueued because the owner asked for it?
         *
         * <p>It is a seam of its own rather than [shouldQueue] because it is a different question: a tap
         * may translate a message the automatic pass refuses, since "it came from history" is not a
         * reason to refuse a message someone deliberately pointed at. What is the same is the off state
         * - {@code false}, and no executor, no enqueue, no pump - and, like [shouldQueue], this is now a
         * delegation to the rule that requires the interpreter.
         */
        @JvmStatic
        fun shouldRequest(
                candidate: TranslationDecision.Candidate,
                interpreter: Interpreter): Boolean =
                TranslationDecision.isRequestable(candidate, interpreter)

        /**
         * The owner tapped a covered message: translate this one, now.
         *
         * <p>This is the "asked for" way in, and it is deliberately not the automatic one: the gate above
         * stays exactly as strict, because a batch of history is the cost storm and one message a person
         * pointed at is not. It goes into the same queue, so the shared cache, the daily cap and the
         * token counter all apply unchanged, and it is the queue - not this method - that decides whether
         * the request may be made at all.
         *
         * <p>The listener always hears back, so a tap can never leave a bubble saying "translating" for
         * ever: either the pass was attempted and the queue reports the outcome on the message itself, or
         * there is nothing to attempt and the reason is handed back here.
         *
         * <p>With the interpreter off there is nothing a tap could ask for and [shouldRequest] answers so
         * before any work is scheduled: no {@code EXECUTOR.execute}, no enqueue, no pump.
         */
        @JvmStatic
        fun requestOne(
                service: XmppConnectionService?,
                message: Message?,
                listener: Listener?) {
            if (service == null || message == null) {
                post(listener, DisplayedBody.Cover.FAILED)
                return
            }
            val candidate =
                    TranslationHooks.candidate(message, message.getBody(true), false, false, false)
            val interpreter = TranslationSettings.get(service).interpreter()
            if (!shouldRequest(candidate, interpreter)) {
                if (interpreter.enabled()) {
                    // Pointing at this body cannot help: it is ciphertext, a file, an edit or a
                    // reaction.
                    Log.d(TAG, "a tapped message cannot be translated: " + refusal(message, candidate))
                    post(listener, DisplayedBody.Cover.CANNOT_TRANSLATE)
                } else {
                    // Only the interpreter said no, and that is not a refusal of this message: with it
                    // off there is nothing a tap could ask for - no EXECUTOR.execute, no enqueue, no
                    // pump - and no bubble is covered, so there is nothing to report either.
                    post(listener, null)
                }
                return
            }
            val messageUuid = message.getUuid()
            // Tulkki: one read, one null answer - the two-call form below it could not smart-cast.
            val conversationUuid = message.getConversation()?.getUuid()
            val body = candidate.body
            EXECUTOR.execute {
                try {
                    val settings = TranslationSettings.get(service)
                    val translations = get(service)
                    val now = System.currentTimeMillis()
                    translations.enqueueExplicit(
                            messageUuid,
                            conversationUuid,
                            body,
                            refusedByTheCheck(service, message, settings),
                            now)
                    val outcome = translations.pump(now)
                    val delay = translations.nextWakeUpMillis(now, outcome)
                    if (delay >= 0) {
                        // A retry, or tomorrow after the cap: the same wake-up the worker sets.
                        TranslationWork.wakeAt(service, delay)
                    }
                    post(listener, localRefusal(settings, message, outcome, now))
                } catch (e: RuntimeException) {
                    Log.e(TAG, "could not translate a tapped message", e)
                    post(listener, DisplayedBody.Cover.FAILED)
                }
            }
        }

        /**
         * Whether this tap must ask again *differently*: the local check refused the answer, so
         * repeating the same request would only reproduce the refusal (docs/MIGRATION.md item 17, six).
         *
         * <p>The deciding fact is the row's own **cause**, `check_refused`
         * ([FailureCause.CHECK_REFUSED]): the check's refusal is recorded as its own cause, so a tap
         * can tell it from every other way a message stays covered - a key, the cap, the network, an
         * unusable answer - instead of guessing from the reason, which flattens all of them onto one
         * word. The interpreter is read first, exactly as it is for the request itself: off, nothing
         * was translated and nothing is re-asked. A sent row is never the check's business.
         */
        private fun refusedByTheCheck(
                service: Context,
                message: Message,
                settings: TranslationSettings): Boolean {
            if (!settings.interpreter().enabled()) {
                return false
            }
            if (message.getStatus() != Message.STATUS_RECEIVED) {
                return false
            }
            if (message.getTranslationState() != Message.TRANSLATION_FAILED) {
                return false
            }
            return TranslationStore(service).causeFor(message.getUuid()) ==
                    FailureCause.CHECK_REFUSED
        }

        /**
         * Why the pass could not even start on what was asked for, or {@code null} when it was attempted.
         *
         * <p>The pass's own outcome is the authority, not a second guess here: a keyless or capped account
         * is what {@code noApiKey} and {@code capReached} already say. A message the pass did reach is
         * left alone - whatever failed or succeeded there is on the message, with its reason.
         */
        private fun localRefusal(
                settings: TranslationSettings,
                message: Message,
                outcome: TranslationService.Outcome,
                now: Long): DisplayedBody.Cover? {
            if (outcome.noApiKey) {
                settings.activity().recordFailure(HeldSend.HoldReason.NO_KEY, null, message.getUuid(), now)
                return DisplayedBody.Cover.NO_KEY
            }
            if (outcome.capReached && message.getTranslationState() == Message.TRANSLATION_NONE) {
                settings.activity()
                        .recordFailure(HeldSend.HoldReason.CAP_REACHED, null, message.getUuid(), now)
                return DisplayedBody.Cover.CAP_REACHED
            }
            return null
        }

        private fun post(listener: Listener?, refusal: DisplayedBody.Cover?) {
            if (listener == null) {
                return
            }
            try {
                listener.onFinished(refusal)
            } catch (e: RuntimeException) {
                Log.e(TAG, "a tap's answer could not be delivered", e)
            }
        }

        /**
         * Everything about an arriving message that decides whether Tulkki may spend on it.
         *
         * <p>Package-private so the JVM test can put a real [Message] through the same mapping the
         * receive path uses, rather than a hand-built candidate that only looks like it. Every wrong
         * value here is a message that is silently never translated, which is exactly the bug this
         * method had.
         *
         * <p>The body is a parameter rather than a read of {@code message.getBody(true)} here, and it is
         * the only thing about the message that is: upstream's {@code getBody} goes through
         * {@code android.util.Pair}, which the unit-test android.jar stubs out to return nothing at all,
         * and a mapping the test cannot call is a mapping the test cannot pin.
         *
         * <p>C1: `public` because the move put this class in `uk.xa0.tulkki.app` and its callers (the receive
         * path in `uk.xa0.tulkki.translation`, and the tests that pin the mapping) in `uk.xa0.tulkki.translation`.
         */
        @JvmStatic
        fun candidate(
                message: Message,
                body: String?,
                fromArchive: Boolean,
                delayed: Boolean,
                replacement: Boolean): TranslationDecision.Candidate =
                TranslationStore.candidate(message, body, fromArchive, delayed, replacement)

        /**
         * Which condition of [TranslationDecision.isRequestable] said no, in the words of the rule
         * itself, with the two facts the interface cannot show: the message's status (which is how a live
         * message is told from a carbon, a sent one or an echo) and its encryption (a body that is still
         * ciphertext must never be bought).
         *
         * <p>The body is never logged - only how much of it there is. The original is exactly what the
         * cover exists to keep off every channel, and a log is a channel.
         */
        private fun refusal(
                message: Message,
                candidate: TranslationDecision.Candidate): String {
            val facts =
                    " (status=" +
                            message.getStatus() +
                            ", encryption=" +
                            message.getEncryption() +
                            ", body=" +
                            (if (candidate.body == null) {
                                "null"
                            } else {
                                candidate.body!!.length.toString() + " chars"
                            }) +
                            ")"
            if (!candidate.received) {
                return "not received$facts"
            }
            if (candidate.deleted) {
                return "deleted$facts"
            }
            if (candidate.hasFileParams) {
                return "a file$facts"
            }
            if (candidate.hasReplacement) {
                return "an edit$facts"
            }
            if (candidate.hasReactions) {
                return "a reaction$facts"
            }
            if (!TranslationDecision.isReadableBody(candidate.encryption)) {
                return "not plaintext$facts"
            }
            if (!TranslationDecision.hasLanguage(candidate.body, candidate.conversationName)) {
                return "no language in the body$facts"
            }
            return "eligible$facts"
        }
    }
}
