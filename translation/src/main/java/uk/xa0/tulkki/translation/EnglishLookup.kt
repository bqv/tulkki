package uk.xa0.tulkki.translation

import android.content.Context
import android.util.Log
import java.time.ZoneId
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * One received message's English, read out of the cache or bought once.
 *
 * <p>This is deliberately not the receive path's own machinery. [TranslationService] translates
 * into the app language and writes its answer onto the message row - `translated_body`,
 * `translation_state`, `translation_lang` - and that side of the bubble is already
 * occupied by what the message was translated into. So the English is bought exactly the way a
 * composer suggestion is ([OutgoingTranslation.suggest]): a cache lookup keyed by the text and
 * the target language, one request on a miss, the tokens counted against the same daily counter, and
 * the text handed back to whoever asked. <strong>Nothing here writes to the message row</strong>, so
 * no English string ever reaches the database - and therefore none of it can reach a notification,
 * a conversation-list preview or a search, all of which render the row.
 *
 * <p>The target is the literal [EnglishRow.ENGLISH], never `settings.appLanguage()`: the
 * whole point of the row is a language the settings do not decide.
 *
 * <p>Three things keep a second purchase from happening. The cache is consulted before every request,
 * so a re-tap - and a tap in a later process - is free. A miss that is already English is answered
 * with the text itself, which is what the owner would have been charged to be told. And a single
 * worker thread means two callers cannot both miss the entry the other is buying, which is exactly
 * the race a pool would open: the cache is read when the work starts, so the second request would be
 * made before the first one's answer was stored.
 *
 * <p>Failures are <em>said</em>, once, in the cover's own vocabulary - no key, the cap reached, no
 * credit, unreachable, a rejected key - and never retried here. A miss is not a failure and is not
 * remembered: [peek]'s answer is only ever "it is here" or "it is not here yet".
 *
 * <p><strong>With the interpreter off this class does nothing at all.</strong> [submit] asks
 * the mode before the worker is queued and before any store is touched, and answers
 * [Callback.onNotBought] - so no cache read, no key read, no cap read, no `DeepSeekClient`
 * and no `translation_cache` or `UsageLedger` row, whichever of [peek],
 * [request] or [prefetch] was called.
 *
 * <p>One difference from the Java this replaces, recorded rather than hidden: the Java wrote
 * `post(callback, callback::onNotBought)`, and a bound method reference throws before `post` can
 * ignore a null callback, so `prefetch(context, blankBody)` NPE'd. The Kotlin passes a lambda whose
 * receiver is null-checked, which is the behaviour all three entry points were written to have.
 */
object EnglishLookup {

    private const val TAG = "Tulkki"

    /**
     * One thread, for the reason [GlossLookup] has one: the cache is read when a lookup starts,
     * so two lookups in flight can both miss the entry the other is still buying - the same text,
     * paid for twice, and both counted against the same daily cap. Serialising them costs a caller at
     * most a wait; the cache is warm by the time the second one runs.
     */
    private val EXECUTOR: ExecutorService =
            Executors.newSingleThreadExecutor { runnable ->
                val thread = Thread(runnable, "tulkki-english")
                thread.isDaemon = true
                thread
            }

    /** Where the answer goes: exactly one of the three. */
    interface Callback {
        /**
         * The English is in hand: the cache had it, or the body was already English and is its own
         * answer. Never null and never blank.
         */
        fun onEnglish(text: String)

        /** Nothing is stored for this body and nothing was bought. Not a failure: ask again later. */
        fun onNotBought()

        /** It could not be had, and why - in the same words a covered bubble uses. */
        fun onUnavailable(reason: HeldSend.HoldReason)
    }

    /**
     * The English, if it has ever been bought, with nothing bought for it: one cache read, on the
     * lookup thread, so a bubble that is re-bound does not query the database on the main thread.
     *
     * <p>This is what a lazily bought row asks on every bind. A hit costs nothing and a miss costs a
     * point query, so the caller is expected to memoise the hit and to leave the miss unremembered -
     * the answer to "is it there yet" can change the moment an earlier request lands.
     */
    @JvmStatic
    fun peek(context: Context?, body: String?, callback: Callback?) {
        submit(context, body, callback, false)
    }

    /**
     * The English for this body: the cache first, then one request. It is what a tap calls, and it is
     * the only path here that may spend anything.
     *
     * @param body the received message's own text, `message.getBody(true)` as the receive path
     *     translated it - the reply's quoted fallback is somebody else's message and is not bought
     * @param callback called once, on the main thread; `null` when nobody is waiting, which is
     *     how [prefetch] warms the cache without a row to fill
     */
    @JvmStatic
    fun request(context: Context?, body: String?, callback: Callback?) {
        submit(context, body, callback, true)
    }

    /**
     * Buy the English now, for a message that has just arrived, with nobody waiting: the same call as
     * [request] and the same cache, so the row finds it already bought when it is drawn.
     *
     * <p>Called from exactly one place - the receive path, where the message has already been found
     * eligible - because eligibility is what says a body may be spent on at all.
     */
    @JvmStatic
    fun prefetch(context: Context?, body: String?) {
        request(context, body, null)
    }

    /**
     * The cache key the English for this body lives under. Exposed so the one place that reads a
     * stored English and the one place that writes it cannot spell the key differently.
     */
    @JvmStatic
    fun cacheKey(body: String?): String = PromptBook.translationKey(body, EnglishRow.ENGLISH)

    private fun submit(
            context: Context?,
            body: String?,
            callback: Callback?,
            buy: Boolean
    ) {
        if (context == null || body == null || body.javaTrim().isEmpty()) {
            post(callback) { callback?.onNotBought() }
            return
        }
        // Off before the worker and before any store is touched: no cache read, no key read, no
        // `hasApiKey`, no `capReached`, no `DeepSeekClient`, no `translation_cache` write and no
        // `UsageLedger` row. The guard lives at this chokepoint rather than at each of the three
        // entries above because this one call is what all three run through, so the three cannot
        // answer differently about the mode.
        if (!TranslationSettings.get(context).interpreter().enabled()) {
            post(callback) { callback?.onNotBought() }
            return
        }
        val applicationContext = context.applicationContext
        val appContext = applicationContext ?: context
        EXECUTOR.execute { lookUp(appContext, body, callback, buy) }
    }

    private fun lookUp(context: Context, body: String, callback: Callback?, buy: Boolean) {
        val settings = TranslationSettings.get(context)
        val key = cacheKey(body)
        val cache = TranslationCache(TranslationStore(context))
        val cached = cache.get(key)
        if (cached != null && cached.translatedBody != null && !cached.translatedBody.javaTrim().isEmpty()) {
            val stored = cached.translatedBody
            post(callback) { callback?.onEnglish(stored) }
            return
        }
        if (!EnglishRow.needsBuying(body, null, settings.appLanguage(), settings.interpreter())) {
            // The body is already English (or has no language to translate). The text itself is the
            // answer, and the owner would have been charged to be told what they already have.
            post(callback) { callback?.onEnglish(body) }
            return
        }
        if (!buy) {
            post(callback) { callback?.onNotBought() }
            return
        }
        val local =
                HeldSend.localReason(
                        settings.hasApiKey(),
                        OutgoingTranslation.capReached(settings),
                        // The target is English and always known, so the only reasons left are the
                        // key and the cap - which is exactly what localReason is for.
                        EnglishRow.ENGLISH)
        if (local != null) {
            // Free, and the reason is the same for every row: the request is not made at all.
            post(callback) { callback?.onUnavailable(local) }
            return
        }
        try {
            val result =
                    DeepSeekClient(
                                    DeepSeekClient.defaultHttp(),
                                    settings.apiKey(),
                                    DeepSeekClient.endpointFor(settings.apiBaseUrl()),
                                    DeepSeekClient.DEFAULT_MODEL)
                            .translate(body, EnglishRow.ENGLISH)
            // Counted against the same counter every other DeepSeek call counts against, but not as
            // a message: this is an extra crutch beside a message's own translation, so
            // "messages translated today" does not move for it (see Spend).
            Spend.record(
                    settings,
                    result.usage,
                    System.currentTimeMillis(),
                    ZoneId.systemDefault(),
                    // Tied to no conversation, and that is the truth rather than a gap: the lookup is
                    // keyed by the body alone, so one purchase answers the same text wherever it
                    // appears.
                    null)
            // Before the answer is handed back, for the same reason the receive path stores first: a
            // crash between the two must not make the same text a second purchase.
            cache.store(
                    key,
                    result.detectedLanguage,
                    result.text,
                    result.totalTokens,
                    System.currentTimeMillis())
            post(callback) { callback?.onEnglish(result.text) }
        } catch (e: DeepSeekClient.TranslationException) {
            Log.d(TAG, "the English for a received message could not be bought: " + e.message)
            val reason = HeldSend.failureReason(e.retryable, e.message)
            post(callback) { callback?.onUnavailable(reason) }
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
