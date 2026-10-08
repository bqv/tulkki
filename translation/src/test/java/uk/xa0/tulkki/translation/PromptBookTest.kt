package uk.xa0.tulkki.translation

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The three editable instructions, and the one thing that makes editing them safe: the wording in
 * force is part of the identity of every answer it bought.
 *
 * <p>The claim these tests exist for is the failure mode, not the feature: an answer bought under the
 * shipped wording must not be handed to a request carrying an edited one. So the last test here buys
 * an answer, edits the instruction, and asks again - and the answer must be gone, in the same cache
 * table, under the same text and target language.
 */
class PromptBookTest {

    private lateinit var settings: TranslationSettings

    @Before
    fun setUp() {
        settings = TranslationSettings.inMemory()
    }

    /**
     * Leaves the process-wide settings as a fresh install for whatever test class runs next: the
     * instance is deliberately shared, and an edit left behind here would be an edit in someone
     * else's test.
     */
    @After
    fun tearDown() {
        TranslationSettings.inMemory()
    }

    @Test
    fun anUntouchedInstallSendsExactlyWhatItAlwaysSent() {
        assertEquals(
                DeepSeekClient.DEFAULT_SYSTEM_PROMPT,
                PromptBook.template(PromptBook.Kind.TRANSLATE))
        assertEquals(
                DeepSeekClient.DEFAULT_REVIEW_PROMPT,
                PromptBook.template(PromptBook.Kind.REVIEW))
        assertEquals(
                DeepSeekClient.DEFAULT_GLOSS_PROMPT,
                PromptBook.template(PromptBook.Kind.GLOSS))
        assertFalse(PromptBook.edited(PromptBook.Kind.TRANSLATE))
        assertFalse(PromptBook.edited(PromptBook.Kind.REVIEW))
        assertFalse(PromptBook.edited(PromptBook.Kind.GLOSS))
    }

    @Test
    fun anEditedInstructionIsTheOneInForce() {
        settings.setTranslatePrompt("Translate it, and keep it blunt. Answer in JSON.")

        assertEquals(
                "Translate it, and keep it blunt. Answer in JSON.",
                PromptBook.template(PromptBook.Kind.TRANSLATE))
        assertTrue(PromptBook.edited(PromptBook.Kind.TRANSLATE))
        // An edit to one instruction says nothing about the other two.
        assertFalse(PromptBook.edited(PromptBook.Kind.REVIEW))
        assertFalse(PromptBook.edited(PromptBook.Kind.GLOSS))
        assertEquals(
                DeepSeekClient.DEFAULT_GLOSS_PROMPT,
                PromptBook.template(PromptBook.Kind.GLOSS))
    }

    @Test
    fun clearingTheFieldPutsTheShippedWordingBack() {
        settings.setTranslatePrompt("something of my own")
        assertTrue(PromptBook.edited(PromptBook.Kind.TRANSLATE))

        // Blank, not merely empty: a field holding a newline is a field the owner emptied.
        settings.setTranslatePrompt("  \n ")
        assertFalse(PromptBook.edited(PromptBook.Kind.TRANSLATE))
        assertEquals(
                DeepSeekClient.DEFAULT_SYSTEM_PROMPT,
                PromptBook.template(PromptBook.Kind.TRANSLATE))
    }

    @Test
    fun savingTheShippedWordingBackIsNotAnEdit() {
        // What the editor does when it is opened and closed untouched: the field holds the shipped
        // wording and stores it. Storing the default is not editing it, so nothing about the cache
        // moves and no answer is thrown away for a field nobody changed.
        settings.setTranslatePrompt(DeepSeekClient.DEFAULT_SYSTEM_PROMPT)

        assertFalse(PromptBook.edited(PromptBook.Kind.TRANSLATE))
        assertEquals(
                CacheKey.of("Hei", "fi"), PromptBook.translationKey("Hei", "fi"))
    }

    /**
     * The retry action's wording is UI copy, not a prompt: editing it must move no cache key.
     *
     * <p>The owner may edit it on the pattern the three instructions use, and the one thing that
     * pattern must not drag in is the cache identity. So this buys an answer, edits the retry
     * wording, and asks again - and the same question finds the same row under the same key, while
     * every prompt's own identity and every key derived from one is byte-identical to what it was.
     * {@code PromptBook} does not read the setting at all; that is the mechanism, and this is the
     * cell that fails if a later edit decides the "UI copy" half should be folded in.
     */
    @Test
    fun editingTheRetryWordingMovesNoCacheKey() {
        val rows: MutableMap<String?, TranslationCache.Entry> = HashMap()
        val cache = TranslationCache(MapStore(rows))
        val key = PromptBook.translationKey("Hei, mitä kuuluu?", "fi")
        val reAsk = PromptBook.translationKey("Hei, mitä kuuluu?", "fi", true)
        val translateIdentity = PromptBook.identity(PromptBook.Kind.TRANSLATE)
        val reviewIdentity = PromptBook.identity(PromptBook.Kind.REVIEW)
        val glossIdentity = PromptBook.identity(PromptBook.Kind.GLOSS)
        cache.store(key, "fi", "Hello, how are you?", 140, 1L)

        settings.setRetryWording("Ask again, and mean it")

        assertEquals(key, PromptBook.translationKey("Hei, mitä kuuluu?", "fi"))
        assertEquals(reAsk, PromptBook.translationKey("Hei, mitä kuuluu?", "fi", true))
        assertEquals(translateIdentity, PromptBook.identity(PromptBook.Kind.TRANSLATE))
        assertEquals(reviewIdentity, PromptBook.identity(PromptBook.Kind.REVIEW))
        assertEquals(glossIdentity, PromptBook.identity(PromptBook.Kind.GLOSS))
        assertFalse("a retry wording is not a prompt", PromptBook.edited(PromptBook.Kind.TRANSLATE))
        assertEquals(
                "and the answer bought before the edit is still found",
                "Hello, how are you?",
                cache.get(PromptBook.translationKey("Hei, mitä kuuluu?", "fi"))!!.translatedBody)
    }

    @Test
    fun theKeysOfTheWordingsThatDidNotMoveAreTheOnesTheirRowsLiveUnder() {
        // Every answer already in the table was bought with the shipped wording of its prompt. For the
        // two prompts whose shipped wording has not changed since, the key is therefore the one already
        // in the table - nothing is bought again by an upgrade - and that is the half of this mechanism
        // that keeps it from being a cache flush on every release.
        assertEquals(
                CacheKey.of("Hei, mitä kuuluu?", "fi"),
                PromptBook.translationKey("Hei, mitä kuuluu?", "fi"))
        assertEquals(
                CacheKey.of(GlossKey.NAMESPACE, "talo", "en"), GlossKey.of("talo", "en"))
        // The review wording did move - the notes stopped punishing puhekieli - so its key moved with
        // it and the notes bought under the old wording are no longer found. That is the same
        // mechanism, and it is the cost of that change: paid once, and stated here rather than
        // discovered on a bubble whose notes have gone.
        assertNotEquals(
                CacheKey.of(ReviewKey.NAMESPACE, "mina olen", "en"),
                ReviewKey.of("mina olen", "en"))
    }

    @Test
    fun aReAskedRefusalMissesTheAnswerItWasRefused() {
        val rows: MutableMap<String?, TranslationCache.Entry> = HashMap()
        val cache = TranslationCache(MapStore(rows))
        val refused = PromptBook.translationKey("Hei, mitä kuuluu?", "fi")
        cache.store(refused, "fi", "Hello, how are you?", 140, 1L)

        // The owner's tap on the refused message: the same text, asked again with the app's own
        // literal clause. It is a different question, so it must not be handed the refused answer.
        val reAsk = PromptBook.translationKey("Hei, mitä kuuluu?", "fi", true)
        assertNotEquals(refused, reAsk)
        assertTrue(PromptBook.isReAsk(reAsk, "Hei, mitä kuuluu?", "fi"))
        assertFalse(PromptBook.isReAsk(refused, "Hei, mitä kuuluu?", "fi"))
        assertNull(cache.get(reAsk))

        // Nothing was evicted and nothing was migrated: the first question's row is still there.
        assertEquals("Hello, how are you?", cache.get(refused)!!.translatedBody)
        // The re-ask's own answer is its own row, and re-reading it is free.
        cache.store(reAsk, "fi", "Hi, how are you?", 60, 2L)
        assertEquals("Hi, how are you?", cache.get(reAsk)!!.translatedBody)
        assertEquals("Hello, how are you?", cache.get(refused)!!.translatedBody)
    }

    @Test
    fun aReAskAndAnEditedPromptAreTwoIndependentFactsAboutOneKey() {
        val shipped = PromptBook.translationKey("Hei", "fi", true)

        settings.setTranslatePrompt("Translate it, but keep the rhythm.")

        val edited = PromptBook.translationKey("Hei", "fi", true)
        assertNotEquals(shipped, edited)
        assertTrue(PromptBook.isReAsk(edited, "Hei", "fi"))
        // And it is still not the plain question under either wording.
        assertNotEquals(edited, PromptBook.translationKey("Hei", "fi"))
        assertNotEquals(shipped, PromptBook.translationKey("Hei", "fi"))
    }


    @Test
    fun anEditMovesTheKeyOfItsOwnCacheAndNoOther() {
        val translation = PromptBook.translationKey("Hei", "fi")
        val gloss = GlossKey.of("talo", "en")
        val review = ReviewKey.of("mina olen", "en")

        settings.setTranslatePrompt("Translate it, but keep the rhythm.")

        assertNotEquals(translation, PromptBook.translationKey("Hei", "fi"))
        assertEquals(gloss, GlossKey.of("talo", "en"))
        assertEquals(review, ReviewKey.of("mina olen", "en"))

        settings.setGlossPrompt("Explain the word in one line.")
        assertNotEquals(gloss, GlossKey.of("talo", "en"))
        assertEquals(review, ReviewKey.of("mina olen", "en"))

        settings.setReviewPrompt("Comment, but only on the verbs.")
        assertNotEquals(review, ReviewKey.of("mina olen", "en"))
    }

    @Test
    fun anEditMissesTheCacheRatherThanHittingIt() {
        val rows: MutableMap<String?, TranslationCache.Entry> = HashMap()
        val cache = TranslationCache(MapStore(rows))
        val key = PromptBook.translationKey("Hei, mitä kuuluu?", "fi")
        cache.store(key, "fi", "Hello, how are you?", 140, 1L)
        assertEquals("Hello, how are you?", cache.get(key)!!.translatedBody)

        settings.setTranslatePrompt("Translate chat messages into %1\$s, in the imperative.")

        val afterEdit = PromptBook.translationKey("Hei, mitä kuuluu?", "fi")
        assertNotEquals(key, afterEdit)
        // The old answer is still there and untouched - it is simply not what the edited instruction
        // is asking for, so the next request buys its own.
        assertNull(cache.get(afterEdit))
        assertEquals("Hello, how are you?", cache.get(key)!!.translatedBody)

        // And putting the shipped wording back finds it again, because its identity never moved.
        settings.setTranslatePrompt("")
        assertEquals(
                "Hello, how are you?",
                cache.get(PromptBook.translationKey("Hei, mitä kuuluu?", "fi"))!!.translatedBody)
    }

    /**
     * The shipped wording, pinned by its fingerprint.
     *
     * <p>The identity of an answer is the wording in force, so these three strings are what every
     * cache key in the app is derived from. They are pinned for one reason: changing what this build
     * sends is a decision about every answer already bought, and a decision that shows up as a failing
     * test is a decision someone made on purpose. There is nothing to bump when one of them moves -
     * {@link PromptBook#suffix} folds the new fingerprint into the key by itself, and the answers
     * bought under the old wording stay in the table, unfound, rather than being handed to a question
     * they do not answer. Update the value below and say in the commit what the new wording buys.
     */
    @Test
    fun theShippedWordingIsPinned() {
        assertEquals(
                "the translate instruction changed: every translation cached under the old wording"
                        + " stays in the table and is no longer found, so the commit that moved this"
                        + " should say what the new wording buys",
                "6a81d6504554e7c8",
                PromptBook.identity(PromptBook.Kind.TRANSLATE))
        assertEquals(
                "the review instruction changed: the notes cached under the old wording are no longer"
                        + " found, which is the effect a change to what a note may say is meant to have",
                "4177af192abbce89",
                PromptBook.identity(PromptBook.Kind.REVIEW))
        assertEquals(
                "the gloss instruction changed: a gloss cached under the old wording is no longer"
                        + " found, so a re-tap buys its own answer",
                "03b9bfbd2207577a",
                PromptBook.identity(PromptBook.Kind.GLOSS))
    }

    /**
     * The wordings that predate the fingerprint are frozen, because the rows they hold are the whole
     * point of the exception.
     *
     * <p>Every answer already in the cache was bought under one of these three strings, keyed without
     * a fingerprint. So this list is history: it must not be regenerated from the current defaults
     * (the review entry would vanish, orphaning every note bought before the colloquial-wording rule),
     * and it must not be tidied (dropping any of the three orphans that prompt's whole history). Only
     * a test can notice either, because the effect is invisible until an upgrade finds its cache gone.
     */
    @Test
    fun theWordingsThatPredateTheFingerprintAreFrozen() {
        assertEquals(
                setOf("6a81d6504554e7c8", "2130a33339d44e7c", "03b9bfbd2207577a"),
                PromptBook.preFingerprintIdentities())
        // The review wording is deliberately no longer on that list: it changed, so it keys under its
        // new fingerprint, while the two wordings that did not change keep the keys their rows live
        // under. That asymmetry is the mechanism working, not the list drifting.
        assertFalse(
                PromptBook.preFingerprintIdentities()
                        .contains(PromptBook.identity(PromptBook.Kind.REVIEW)))
        assertTrue(
                PromptBook.preFingerprintIdentities()
                        .contains(PromptBook.identity(PromptBook.Kind.TRANSLATE)))
        assertTrue(
                PromptBook.preFingerprintIdentities()
                        .contains(PromptBook.identity(PromptBook.Kind.GLOSS)))
    }

    /**
     * A wording the build no longer ships buys under its own key, and the two that did not move do not.
     *
     * <p>This is the half the hand-bumped versions never covered: translate's answers are keyed by text
     * and target language alone, so before this mechanism a shipped change to the translate wording
     * left every translation under the old one still reachable. The other half matters just as much -
     * a mechanism that re-keyed on every build would flush a cache that nothing had invalidated - so
     * both are asserted here, one against a prompt whose wording moved and two against prompts whose
     * wording did not.
     */
    @Test
    fun aWordingTheBuildNoLongerShipsBuysUnderItsOwnKey() {
        // The keys are hashes, so "carries the fingerprint" means the namespace that went into the
        // hash is the namespaced one - reconstructed here rather than searched for in the digest.
        assertEquals(
                "the review wording moved, so its key carries the new fingerprint",
                CacheKey.of(
                        ReviewKey.NAMESPACE + "@" + PromptBook.identity(PromptBook.Kind.REVIEW),
                        "mina olen",
                        "en"),
                ReviewKey.of("mina olen", "en"))
        assertEquals(
                "the gloss wording did not move, so its rows are still found",
                CacheKey.of(GlossKey.NAMESPACE, "kuusi", "en"),
                GlossKey.of("kuusi", "en"))
        assertEquals(
                "and neither did the translate wording, whose key has no namespace at all",
                CacheKey.of("Hei", "fi"),
                PromptBook.translationKey("Hei", "fi"))
        assertTrue(PromptBook.suffix(PromptBook.Kind.TRANSLATE).isEmpty())
    }

    @Test
    fun theFingerprintIsStableAndShort() {
        settings.setTranslatePrompt("Translate it, and keep it blunt.")
        val once = PromptBook.identity(PromptBook.Kind.TRANSLATE)
        val twice = PromptBook.identity(PromptBook.Kind.TRANSLATE)

        assertEquals(once, twice)
        assertEquals(16, once.length)
        assertTrue(once, once.matches(Regex("[0-9a-f]{16}")))

        // Two edits that differ anywhere are two different identities - this is the whole guarantee.
        settings.setTranslatePrompt("Translate it, and keep it blunt!")
        assertNotEquals(once, PromptBook.identity(PromptBook.Kind.TRANSLATE))
    }

    private class MapStore(private val rows: MutableMap<String?, TranslationCache.Entry>) :
            TranslationCache.Store {

        override fun get(cacheKey: String): TranslationCache.Entry? {
            return rows.get(cacheKey)
        }

        override fun put(entry: TranslationCache.Entry) {
            rows.put(entry.cacheKey, entry)
        }
    }
}
