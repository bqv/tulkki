package uk.xa0.tulkki.translation

import android.os.Handler
import android.os.Looper

/**
 * The one place a Tulkki answer is handed back to the main thread.
 *
 * <p>Four classes used to declare this and one of them did it differently. [OutgoingTranslation]
 * and [EnglishLookup] each held a <em>lazy</em> nested holder, with the same comment explaining
 * why: nothing they decide needs an Android `Looper` - the detection, the reuse checks, the
 * refusals are pure - and a handler built eagerly with the class makes every one of those decisions
 * unreachable to the JVM unit tests, which run against AGP's mockable `android.jar` where
 * `Looper.getMainLooper()` is not mocked. Touching such a class at all threw
 * `ExceptionInInitializerError`. [GlossLookup] still held the older eager form - which is
 * why it had no JVM test of any kind - and [uk.xa0.tulkki.app.MamLanguageSampler] built a brand-new
 * `Handler` per post.
 *
 * <p>This is the survivor: the lazy holder, public because two of the four call sites are in
 * `:app` while nothing this class needs is.
 *
 * <p>What it deliberately is <em>not</em> is a delivery policy. `MamLanguageSampler` wraps the
 * caller's runnable in a try/catch and swallows the failure, and `TranslationHooks` delivers a
 * tap's outcome to a listener the same way - both are decisions about one listener, and both stay at
 * their call sites. This class only posts.
 *
 * <p>The laziness is load-bearing and is kept: `handler` is a `by lazy`, so touching
 * `TulkkiMainThread` (which every caller does) does not build a `Handler` - only the first
 * [post] does, exactly as the Java's nested `Holder` deferred it to the first post.
 */
object TulkkiMainThread {

    /**
     * The handler, built on the first post rather than with the class, so that loading a class which
     * only <em>might</em> post to the interface does not require a looper.
     */
    private val handler: Handler by lazy { Handler(Looper.getMainLooper()) }

    /** Runs `work` on the main thread. */
    @JvmStatic
    fun post(work: Runnable) {
        handler.post(work)
    }
}
