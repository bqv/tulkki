package uk.xa0.tulkki.translation

import java.util.ArrayList
import java.util.Collections
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.model.Message

/**
 * The MAM language sample's rule, pinned without a device.
 *
 * <p>Every case here is a way the sample could learn the wrong language, or overwrite something it
 * must not touch. The wrong language is not a cosmetic bug: the conversation's language is the target
 * of everything the owner sends, so a bad verdict sends their next message into a language the other
 * person may not read.
 */
class LanguageSampleTest {

    /**
     * The interpreter on - app Finnish with study German, two different languages. The sample's
     * rule is asked through the tap rule, which requires it; the sample's own off state is
     * {@code shouldSample}'s, and it is pinned at the bottom of this class.
     */
    private val ON = Interpreter.of("fi", "de")

    /** The off pair: app Finnish with study Finnish, the same language on both sides. */
    private val OFF = Interpreter.of("fi", "fi")

    private val GERMAN =
            "Hallo, treffen wir uns morgen zum Kaffee? Ich habe Neuigkeiten fuer dich."
    private val ENGLISH =
            "Hey, are we still meeting tomorrow for coffee? I have news for you."
    private val FINNISH =
            "Moi! Mennäänkö huomenna kahville, minulla on asiaa sinulle."

    private fun peer(body: String?): TranslationDecision.Candidate {
        val candidate = TranslationDecision.Candidate()
        candidate.received = true
        candidate.encryption = Message.ENCRYPTION_NONE
        candidate.body = body
        return candidate
    }

    private fun mine(body: String?): TranslationDecision.Candidate {
        val candidate = peer(body)
        candidate.received = false
        return candidate
    }

    // -- which archived messages may be read -----------------------------------------------------

    @Test
    fun aPlainProseMessageFromThePeerIsEligible() {
        Assert.assertTrue(LanguageSample.isEligible(peer(GERMAN), ON))
        Assert.assertTrue(LanguageSample.isEligible(peer(ENGLISH), ON))
    }

    /**
     * Constraint 4. Our own archived messages are originals in the app language; reading them would
     * drag the verdict toward Finnish and then translate the owner's outgoing messages into Finnish -
     * the exact bug the feature exists to prevent.
     */
    @Test
    fun ourOwnArchivedMessagesAreNeverEligibleHoweverForeignTheyLook() {
        Assert.assertFalse(LanguageSample.isEligible(mine(GERMAN), ON))
        Assert.assertFalse(LanguageSample.isEligible(mine(ENGLISH), ON))
        // And a window of only our own messages learns nothing rather than naming a language.
        Assert.assertNull(
                LanguageSample.read(listOf(mine(GERMAN), mine(GERMAN)), "fi", ON))
    }

    /**
     * Constraint on encrypted conversations: a sample is never decrypted, so ciphertext is skipped
     * rather than decrypted and an OMEMO or PGP conversation simply yields no verdict.
     */
    @Test
    fun ciphertextIsNeverSampledEvenWhenItCarriesAFallbackBody() {
        val omemo = peer(GERMAN)
        omemo.encryption = Message.ENCRYPTION_AXOLOTL
        Assert.assertFalse(LanguageSample.isEligible(omemo, ON))

        val pgp = peer(GERMAN)
        pgp.encryption = Message.ENCRYPTION_PGP
        Assert.assertFalse(LanguageSample.isEligible(pgp, ON))

        // A whole encrypted window is no evidence, not a wrong answer.
        Assert.assertNull(LanguageSample.read(listOf(omemo, pgp), "fi", ON))
    }

    @Test
    fun aFreshCandidateDefaultsToCiphertextSoAForgetfulCallerSamplesNothing() {
        Assert.assertFalse(LanguageSample.isEligible(TranslationDecision.Candidate(), ON))
    }

    @Test
    fun filesEditsReactionsAndRetractionsAreNotRead() {
        val file = peer(GERMAN)
        file.hasFileParams = true
        Assert.assertFalse(LanguageSample.isEligible(file, ON))

        val edit = peer(GERMAN)
        edit.hasReplacement = true
        Assert.assertFalse(LanguageSample.isEligible(edit, ON))

        val reaction = peer(GERMAN)
        reaction.hasReactions = true
        Assert.assertFalse(LanguageSample.isEligible(reaction, ON))

        val retracted = peer(GERMAN)
        retracted.deleted = true
        Assert.assertFalse(LanguageSample.isEligible(retracted, ON))
    }

    @Test
    fun aBodyWithNoLanguageIsNotRead() {
        Assert.assertFalse(LanguageSample.isEligible(peer(null), ON))
        Assert.assertFalse(LanguageSample.isEligible(peer(""), ON))
        Assert.assertFalse(LanguageSample.isEligible(peer("   "), ON))
        Assert.assertFalse(LanguageSample.isEligible(peer("https://example.org/a/b/c"), ON))
        Assert.assertFalse(LanguageSample.isEligible(peer("+358 40 123 4567"), ON))
        Assert.assertFalse(LanguageSample.isEligible(null, ON))
    }

    // -- the aggregation rule --------------------------------------------------------------------

    @Test
    fun aStrictMajorityNamesTheLanguage() {
        Assert.assertEquals("de", LanguageSample.verdict(listOf("de", "de", "en")))
        Assert.assertEquals("de", LanguageSample.verdict(listOf("de", "en", "de", "de")))
        Assert.assertEquals("en", LanguageSample.verdict(listOf("en", "en", "en", "de")))
        Assert.assertEquals("de", LanguageSample.verdict(Collections.singletonList("de")))
    }

    /** A window that is genuinely split has no single send target, so it learns nothing. */
    @Test
    fun aTieLearnsNothing() {
        Assert.assertNull(LanguageSample.verdict(listOf("de", "en")))
        Assert.assertNull(LanguageSample.verdict(listOf("de", "en", "fi", "sv")))
        Assert.assertNull(LanguageSample.verdict(listOf("de", "de", "en", "en")))
    }

    @Test
    fun anEmptyOrUnreadableWindowLearnsNothing() {
        Assert.assertNull(LanguageSample.verdict(null))
        Assert.assertNull(LanguageSample.verdict(Collections.emptyList<String>()))
        Assert.assertNull(LanguageSample.verdict(listOf(null, null)))
        Assert.assertNull(LanguageSample.read(null, "fi", ON))
        Assert.assertNull(LanguageSample.read(Collections.emptyList<TranslationDecision.Candidate>(), "fi", ON))
    }

    @Test
    fun aWindowOfProseNamesTheLanguageByMajority() {
        val window =
                listOf(peer(GERMAN), peer(GERMAN), peer(ENGLISH), peer(null))
        Assert.assertEquals("de", LanguageSample.read(window, "fi", ON))
    }

    // -- a weak or und verdict changes nothing ---------------------------------------------------

    @Test
    fun aSingleTokenIsNeverAReadingHoweverConfidentTheDetectorIs() {
        // The profiles read a bare name as whatever its shape resembles - "Matti" comes back Maltese
        // at 0.96 - and a conversation's language is what the owner's next message is sent in.
        Assert.assertNull(LanguageSample.readOne("Matti", "fi", null))
        Assert.assertNull(LanguageSample.readOne("Joo", "fi", null))
    }

    @Test
    fun aBodyTooShortToHaveALanguageIsNotAReading() {
        Assert.assertNull(LanguageSample.readOne("ok", "fi", null))
        Assert.assertNull(LanguageSample.readOne("+1", "fi", null))
        Assert.assertNull(LanguageSample.readOne("   ", "fi", null))
        Assert.assertNull(LanguageSample.readOne(null, "fi", null))
    }

    @Test
    fun proseNamesItsLanguage() {
        Assert.assertEquals("de", LanguageSample.readOne(GERMAN, "fi", null))
        Assert.assertEquals("en", LanguageSample.readOne(ENGLISH, "fi", null))
    }

    /** The room is not speaking Finnish just because that is what we translate into. */
    @Test
    fun theAppLanguageIsNeverAConversationsLanguage() {
        Assert.assertNull(LanguageSample.readOne(FINNISH, "fi", null))
        Assert.assertNull(LanguageSample.readOne(FINNISH, "FI", null))
        Assert.assertNull(LanguageSample.readOne(FINNISH, " fi ", null))
        Assert.assertNull(LanguageSample.read(listOf(peer(FINNISH), peer(FINNISH)), "fi", ON))
        // ... and with no app language configured, Finnish is just another language.
        Assert.assertEquals("fi", LanguageSample.readOne(FINNISH, null, null))
    }

    /**
     * A window where the readings do not agree is discarded in full: the sample leaves the
     * conversation exactly as unknown as it found it.
     */
    @Test
    fun aWindowOfForeignBodiesThatDoNotAgreeChangesNothing() {
        Assert.assertNull(
                LanguageSample.read(listOf(peer(GERMAN), peer(ENGLISH)), "fi", ON))
    }

    // -- never overwrite anything ----------------------------------------------------------------

    @Test
    fun onlyAGenuinelyUnknownLanguageIsWorthAskingAbout() {
        Assert.assertTrue(LanguageSample.shouldSample(null, null, ON))
        Assert.assertTrue(LanguageSample.shouldSample("", "  ", ON))
        Assert.assertTrue(LanguageSample.shouldSample(TextLanguage.UNKNOWN, TextLanguage.UNKNOWN, ON))
    }

    @Test
    fun anEstablishedDetectionIsNeverSampledOver() {
        Assert.assertFalse(LanguageSample.shouldSample("de", null, ON))
        Assert.assertFalse(LanguageSample.shouldSample("de", "", ON))
        Assert.assertFalse(LanguageSample.shouldSample(" de ", "und", ON))
    }

    @Test
    fun anOverrideIsNeverSampledOverAndNeverTouched() {
        Assert.assertFalse(LanguageSample.shouldSample(null, "de", ON))
        Assert.assertFalse(LanguageSample.shouldSample("de", "de", ON))
        Assert.assertFalse(LanguageSample.shouldSample(TextLanguage.UNKNOWN, "fi", ON))
    }

    // -- the interpreter off: nothing is asked and nothing is learned -----------------------------

    /**
     * The sampler's own gate, and the only one on that path that needs no device. {@code (null, null)}
     * is the shape that <em>asks</em> when on - a conversation whose language is genuinely unknown -
     * and off the same shape answers false before either stored value is read, because the mode is
     * the first statement. That is what makes {@code MamLanguageSampler.consider} return above its
     * {@code PENDING} entry, its {@code SAMPLED} bookkeeping and its query: no archive traffic, no
     * sampling bookkeeping, and nothing learned about a conversation's language while off.
     */
    @Test
    fun anOffInterpreterNeverAsksTheArchive() {
        Assert.assertTrue(
                "the on-state control: an unknown language on both sides is worth a sample",
                LanguageSample.shouldSample(null, null, ON))

        for (off in offModes()) {
            Assert.assertFalse(
                    "off, the conversation stays unknown and the archive is not asked",
                    LanguageSample.shouldSample(null, null, off))
            Assert.assertFalse(
                    "and an established detection cannot be the reason off",
                    LanguageSample.shouldSample("de", null, off))
        }
    }

    /**
     * One level down, and the same fact: a window that names a language when on names none off,
     * because every reading is asked through {@code isEligible} - which is the tap rule, whose own
     * first statement is the mode (S4-3) - so every candidate is skipped and the verdict is nothing.
     * A sample that cannot be asked cannot produce a language even if a stanza arrived.
     *
     * <p>This cell names the two real off modes and not {@code null}: the tap rule declares the
     * interpreter non-null (it is Kotlin), so {@code null} is not a value this path can be handed -
     * unlike {@code shouldSample}, which answers it off. That difference is stated rather than
     * papered over, and {@code StartupBacklog.eligible} answers {@code null} for the same reason this
     * one cannot.
     */
    @Test
    fun anOffInterpreterLearnsNothingFromAWindow() {
        val window =
                listOf(peer(GERMAN), peer(GERMAN), peer(ENGLISH))

        Assert.assertEquals("de", LanguageSample.read(window, "fi", ON))

        Assert.assertNull(LanguageSample.read(window, "fi", OFF))
        Assert.assertNull(
                LanguageSample.read(window, "fi", Interpreter.of("fi", Interpreter.NONE)))
    }

    /** The interpreter's off states, so the rule cannot be reading a different one from the others. */
    private fun offModes(): Array<Interpreter?> {
        return arrayOf(OFF, Interpreter.of("fi", Interpreter.NONE), null)
    }

    // -- the bounds ------------------------------------------------------------------------------

    /** The bound is part of the design: small enough for one page, large enough to vote. */
    @Test
    fun theSampleIsSmallAndBounded() {
        Assert.assertEquals(25, LanguageSample.MAX_MESSAGES)
        Assert.assertTrue(
                "a sample must not be a catch-up",
                LanguageSample.MAX_MESSAGES < 50) // upstream's own Config.PAGE_SIZE
        Assert.assertTrue(LanguageSample.MAX_PER_RUN > 0 && LanguageSample.MAX_PER_RUN <= 10)
    }

    @Test
    fun theWindowReachesBackThirtyDaysAndNoFurther() {
        val now = 1_700_000_000_000L
        Assert.assertEquals(now - 30L * 24L * 60L * 60L * 1000L, LanguageSample.windowStart(now))
        Assert.assertEquals(30L * 24L * 60L * 60L * 1000L, LanguageSample.WINDOW_MILLIS)
    }

    /** Guards against a test that would pass on an empty fixture. */
    @Test
    fun theFixturesAreActuallyConfidentProse() {
        Assert.assertTrue(
                "the German fixture must be confidently German: "
                        + TextLanguage.detect(GERMAN),
                TextLanguage.detect(GERMAN).isProbably("de", TextLanguage.TRUSTWORTHY_CONFIDENCE))
        Assert.assertTrue(
                "the English fixture must be confidently English: "
                        + TextLanguage.detect(ENGLISH),
                TextLanguage.detect(ENGLISH)
                        .isProbably("en", TextLanguage.TRUSTWORTHY_CONFIDENCE))
        Assert.assertTrue(
                "the Finnish fixture must be confidently Finnish: "
                        + TextLanguage.detect(FINNISH),
                TextLanguage.detect(FINNISH)
                        .isProbably("fi", TextLanguage.TRUSTWORTHY_CONFIDENCE))
    }

    /** The eligible list must not silently drop everything, which would make the test above vacuous. */
    @Test
    fun eligibilityKeepsTheEligibleOnes() {
        val window =
                listOf(peer(GERMAN), mine(GERMAN), peer(null))
        val kept = ArrayList<String?>()
        for (candidate in window) {
            if (LanguageSample.isEligible(candidate, ON)) {
                kept.add(candidate.body)
            }
        }
        Assert.assertEquals(Collections.singletonList(GERMAN), kept)
    }

    /**
     * The one property the two eligibility rules never asserted together: the sample's rule is the
     * tap path's rule <em>plus plaintext</em>, and the two differ on exactly that one clause.
     *
     * <p>Until this landed they were two implementations of one rule, which is how one of them could
     * have been tightened without the other. Now there is one implementation, and this is the
     * relation between the two public names over it.
     */
    @Test
    fun samplingIsRequestingPlusPlaintext() {
        val candidates = ArrayList<TranslationDecision.Candidate>()
        for (body in arrayOf(GERMAN, ENGLISH, null, "", "https://example.org/a/b")) {
            candidates.add(peer(body)) // the peer's own text
            candidates.add(mine(body)) // our own, which is never evidence of a room's language
        }
        val file = peer(GERMAN)
        file.hasFileParams = true
        candidates.add(file)
        val edit = peer(GERMAN)
        edit.hasReplacement = true
        candidates.add(edit)
        val reaction = peer(GERMAN)
        reaction.hasReactions = true
        candidates.add(reaction)
        val retracted = peer(GERMAN)
        retracted.deleted = true
        candidates.add(retracted)
        // A candidate nobody filled in: one type, one set of safe defaults, both rules reading them.
        candidates.add(TranslationDecision.Candidate())

        for (candidate in candidates) {
            Assert.assertEquals(
                    "the sample's rule is the tap's rule, candidate for candidate",
                    TranslationDecision.isRequestable(candidate, ON),
                    LanguageSample.isEligible(candidate, ON))
        }

        // And the one clause they differ on is plaintext, in one direction only.
        for (readableCiphertext in
                intArrayOf(
                        Message.ENCRYPTION_OTR,
                        Message.ENCRYPTION_AXOLOTL,
                        Message.ENCRYPTION_DECRYPTED)) {
            val ciphertext = peer(GERMAN)
            ciphertext.encryption = readableCiphertext
            Assert.assertTrue(
                    "a tap may buy the one message behind a readable ciphertext",
                    TranslationDecision.isRequestable(ciphertext, ON))
            Assert.assertFalse("a sample is never decrypted", LanguageSample.isEligible(ciphertext, ON))
        }
    }
}
