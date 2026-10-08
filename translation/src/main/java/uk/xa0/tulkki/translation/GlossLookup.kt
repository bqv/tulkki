package uk.xa0.tulkki.translation

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import java.time.ZoneId
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * One tapped word, looked up: the cache first, then one request, then the answer is kept.
 *
 * <p>This is on demand and deliberately outside everything the messages go through. There is no
 * retry, no backoff and no batching: one request per tap, and a gloss is a question asked by a
 * finger, so an answer that arrives after the popup has been closed is worth less than the tokens it
 * cost. The one serialisation there is - a single worker thread - is deliberate. A failure is
 * therefore <em>said</em>, once, in the same vocabulary the cover uses - no key, cap reached, no
 * credit, unreachable, a bad answer - rather than retried in the background. A failure that belongs
 * to a question the owner has moved on from is said to nobody at all.
 *
 * <p>It shares the translation cache and the daily counter, because both are about the same money:
 * a word looked up twice is bought once, and every gloss is counted against the same cap as every
 * message. It does not touch the "messages translated today" figure, which counts messages.
 *
 * <p>The call runs off the caller's thread; the callback is posted back to it, so a caller on the
 * main thread can show the answer without another hop.
 *
 * <p><strong>With the interpreter off this class does nothing at all.</strong> The guard is the first
 * statement that can act, before [GlossLookups.begin] and before the worker: the answer is
 * [Callback.onNothingToGloss] on the first line, so there is no attempt to supersede, no
 * thread, no cache read, no key read, no cap read, no `DeepSeekClient`, no
 * `translation_cache` write and no `UsageLedger` row. A plain XMPP client has no reading
 * aid, and the renderer is not the only way in: a selection or a menu that reached here with a word
 * must not be able to buy one.
 *
 * <p>Pure Kotlin in shape, but Android- and okhttp-reaching by design. `Cancellable` is a plain
 * Kotlin interface (its Java implementors must keep working), so it takes no lambda: the one adapter
 * from an okhttp `Call` is written out where the call is handed to the attempt.
 */
object GlossLookup {

    private const val TAG = "Tulkki"

    /**
     * The one question being asked right now.
     *
     * <p>A tap makes its question current and cancels whatever was there before, so the wait behind a
     * lookup that is already out is as long as it takes to tear the socket down rather than as long
     * as the old answer takes to arrive.
     */
    private val LOOKUPS = GlossLookups()

    /**
     * One thread, like the tap path: two glosses must not race the same daily counter. The pool is
     * not widened, which is the whole point of keeping the line: the cache is read when a lookup
     * starts, so two lookups in flight could both miss the entry the other is still buying - the same
     * word, paid for twice - and both would count against the same daily cap at the same time.
     */
    private val EXECUTOR: ExecutorService =
            Executors.newSingleThreadExecutor { runnable ->
                val thread = Thread(runnable, "tulkki-gloss")
                thread.isDaemon = true
                thread
            }

    /** Where the answer goes: one of the two is always null. */
    interface Callback {
        fun onGloss(gloss: Gloss)

        fun onFailure(reason: HeldSend.HoldReason)

        /** Nothing to look up: the token is not a word, or is already in the study language. */
        fun onNothingToGloss()
    }

    /**
     * Look up one surface form.
     *
     * @param context any context; the settings and the cache are process-wide
     * @param surface the word as it was tapped
     * @param callback called once, on the caller's thread - and not at all when a later tap has
     *     superseded this one
     */
    @JvmStatic
    fun request(context: Context?, surface: String?, callback: Callback?) {
        request(context, surface, null, callback)
    }

    /**
     * The same lookup, with the sentence the word was tapped in.
     *
     * <p>A word with two lives is only explained correctly with its sentence in front of the model,
     * so the sentence is bought with the request and is part of what the answer is a function of - a
     * cache entry belongs to one word <em>in one sentence</em>.
     *
     * <p><strong>A request supersedes the one before it.</strong> The tap that arrives last is the
     * question worth asking, so the lookup still in flight is cancelled - its call stopped, not
     * merely ignored, or its tokens are spent anyway - and this one takes its place. Whatever the old
     * lookup produces afterwards is dropped: it is not delivered to the popup that asked (that one has
     * been replaced) and not to the one that replaced it.
     *
     * @param sentence the sentence the word sits in, or `null` when the caller has none
     */
    @JvmStatic
    fun request(context: Context?, surface: String?, sentence: String?, callback: Callback?) {
        if (callback == null) {
            return
        }
        // Java's own `trim()` (`<= ' '`), as in the Java this replaces.
        if (context == null || surface == null || surface.javaTrim().isEmpty()) {
            // Not a question, so it does not cancel the one that is: a caller with nothing to ask
            // must not stop a lookup somebody else is waiting for.
            post(callback) { callback.onNothingToGloss() }
            return
        }
        val applicationContext = context.applicationContext
        val appContext = applicationContext ?: context
        // Off before anything is begun: no attempt is superseded, nothing is queued, and the answer
        // is the one a plain client gives - there is nothing here to look up.
        if (!GlossLookups.mayBegin(TranslationSettings.get(context).interpreter())) {
            post(callback) { callback.onNothingToGloss() }
            return
        }
        // Before the work is queued, so the tap that supersedes this one cannot be overtaken by it:
        // everything the previous lookup still had to do stops being waited for here.
        val attempt = LOOKUPS.begin()
        EXECUTOR.execute { lookUp(appContext, surface, sentence, callback, attempt) }
    }

    private fun lookUp(
            context: Context,
            surface: String,
            sentence: String?,
            callback: Callback,
            attempt: GlossLookups.Attempt
    ) {
        try {
            if (attempt.isCancelled()) {
                // Superseded before the worker even reached it: no cache read, no request, and
                // nothing to say to a callback the owner has stopped watching.
                return
            }
            val settings = TranslationSettings.get(context)
            val studyLanguage = settings.studyLanguage()
            val word = surface.javaTrim()
            // The same rule the tap targets were chosen by, asked again here: a caller that got past
            // the spans (a selection, a menu) must not be able to buy a lookup for a non-word - and,
            // asked with the interpreter, off it answers empty for every word.
            if (GlossText.words(word, studyLanguage, settings.interpreter()).isEmpty()) {
                report(attempt, callback) { callback.onNothingToGloss() }
                return
            }
            val cache = TranslationCache(TranslationStore(context))
            val key = GlossKey.of(word, studyLanguage, sentence)
            val cached = cache.get(key)
            if (cached != null) {
                val stored = GlossParser.parse(word, cached.translatedBody)
                if (stored != null) {
                    report(attempt, callback) { callback.onGloss(stored) }
                    return
                }
                // An entry that no longer parses is treated as absent, not as an answer: the shape of
                // a gloss could have changed since it was stored, and showing a broken one helps
                // nobody.
            }
            if (!settings.hasApiKey()) {
                report(attempt, callback) { callback.onFailure(HeldSend.HoldReason.NO_KEY) }
                return
            }
            if (OutgoingTranslation.capReached(settings)) {
                report(attempt, callback) { callback.onFailure(HeldSend.HoldReason.CAP_REACHED) }
                return
            }
            val bought =
                    DeepSeekClient(
                                    DeepSeekClient.defaultHttp(),
                                    settings.apiKey(),
                                    DeepSeekClient.endpointFor(settings.apiBaseUrl()),
                                    DeepSeekClient.DEFAULT_MODEL)
                            // The call is handed back before it goes out, so a tap that arrives
                            // while it is on the wire stops it instead of paying for a word nobody
                            // is waiting for any more.
                            .gloss(word, studyLanguage, sentence) { call ->
                                attempt.attach(
                                        object : GlossLookups.Cancellable {
                                            override fun cancel() {
                                                call.cancel()
                                            }
                                        })
                            }
            attempt.settle(
                    bought.usage,
                    object : GlossLookups.Settlement {
                        override fun count(usage: DeepSeekClient.Usage) {
                            // A gloss is a question, not a message: the tokens are counted against
                            // the same cap, and "messages translated today" does not move (Spend).
                            Spend.record(
                                    settings,
                                    usage,
                                    System.currentTimeMillis(),
                                    ZoneId.systemDefault(),
                                    // A gloss belongs to no conversation: the request carries the
                                    // sentence the word was tapped in and the answer is keyed by that
                                    // sentence (GlossKey), so two rooms can share one purchase.
                                    null)
                        }

                        override fun keep() {
                            // Stored as the four-field JSON the parser reads, so the next tap goes
                            // through the same parse as the first one did and cannot come back as
                            // something new. Only a question that is still being asked gets here.
                            cache.store(
                                    key,
                                    studyLanguage,
                                    rawAnswerOf(bought),
                                    bought.totalTokens,
                                    System.currentTimeMillis())
                            report(attempt, callback) { callback.onGloss(bought.gloss) }
                        }
                    })
        } catch (e: DeepSeekClient.TranslationException) {
            // A cancelled lookup reports nothing, failure caption included: the owner never sees the
            // question they abandoned, so there is nothing to explain about it. Checking it here is
            // also what keeps a cancelled call - which fails with the ordinary "unreachable" error -
            // from being drawn as an outage.
            if (attempt.isCancelled()) {
                return
            }
            Log.d(TAG, "a gloss could not be bought: " + e.message)
            val reason = HeldSend.failureReason(e.retryable, e.message)
            report(attempt, callback) { callback.onFailure(reason) }
        } finally {
            LOOKUPS.finish(attempt)
        }
    }

    /** The answer as it will be cached: the same JSON the parser reads back. */
    private fun rawAnswerOf(answer: DeepSeekClient.GlossAnswer): String {
        val root = JsonObject()
        root.addProperty(GlossParser.FIELD_DICTIONARY, answer.gloss.dictionary())
        root.addProperty(GlossParser.FIELD_ENDING, answer.gloss.ending())
        root.addProperty(GlossParser.FIELD_CASE, answer.gloss.grammaticalCase())
        root.addProperty(GlossParser.FIELD_GLOSS, answer.gloss.meaning())
        return root.toString()
    }

    /**
     * Say one thing to the caller, unless the question has been superseded.
     *
     * <p>Checked twice on purpose, and the second check is the one that matters: a tap can arrive
     * between the worker's post and the main thread running it, and the answer to the old word must
     * not be drawn over the new word's spinner. Nothing at all is reported for a superseded lookup -
     * no gloss, and no failure either, because that question was abandoned rather than answered.
     */
    private fun report(attempt: GlossLookups.Attempt, callback: Callback, work: Runnable) {
        if (attempt.isCancelled()) {
            return
        }
        post(callback) {
            if (!attempt.isCancelled()) {
                work.run()
            }
        }
    }

    private fun post(callback: Callback?, work: Runnable) {
        if (callback == null) {
            return
        }
        TulkkiMainThread.post(work)
    }
}

/**
 * `String.trim()` with Java's own definition: everything at or below U+0020.
 */
private fun String.javaTrim(): String = trim { it <= ' ' }
