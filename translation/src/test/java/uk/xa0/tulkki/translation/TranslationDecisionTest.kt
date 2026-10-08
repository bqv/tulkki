package uk.xa0.tulkki.translation

import uk.xa0.tulkki.data.model.Message
import org.junit.Assert
import org.junit.Test

/** The gate that decides whether money may be spent, and the free local decision after it. */
class TranslationDecisionTest {

    /**
     * The interpreter on - app Finnish with study German, two different languages. All three
     * answers take it as a required argument now, so every call below names the interpreter it is
     * asking about; the off answers have their own tests at the end of this class.
     */
    private val ON = Interpreter.of("fi", "de")

    /** A live, plain-text, received message: the only shape that is allowed to cost anything. */
    private fun live(body: String): TranslationDecision.Candidate {
        val candidate = TranslationDecision.Candidate()
        candidate.live(true, false, false)
        candidate.encryption = Message.ENCRYPTION_NONE
        candidate.body = body
        return candidate
    }

    @Test
    fun aLivePlainTextMessageIsEligible() {
        Assert.assertTrue(
                TranslationDecision.isEligible(live("Hey, are we still meeting tomorrow?"), ON))
    }

    @Test
    fun aMessageFromTheArchiveIsNeverEligible() {
        val candidate = live("Hello from last year")
        candidate.fromArchive = true
        Assert.assertFalse(
                "history must never be translated - this is the backfill storm",
                TranslationDecision.isEligible(candidate, ON))
    }

    @Test
    fun aDelayedMessageIsNeverEligible() {
        val candidate = live("Delivered while you were away")
        candidate.delayed = true
        Assert.assertFalse(
                "a XEP-0203 delay stamp means it arrived late, not now",
                TranslationDecision.isEligible(candidate, ON))
    }

    @Test
    fun aSentMessageIsNotEligible() {
        val candidate = live("Mine")
        candidate.received = false
        Assert.assertFalse(TranslationDecision.isEligible(candidate, ON))
    }

    @Test
    fun nothingButPlainTextIsEligible() {
        val file = live("caption")
        file.hasFileParams = true
        Assert.assertFalse(
                "files and images are not our business", TranslationDecision.isEligible(file, ON))

        val edit = live("fixed typo")
        edit.hasReplacement = true
        Assert.assertFalse(
                "edits and corrections are separate handling",
                TranslationDecision.isEligible(edit, ON))

        val reaction = live("great news")
        reaction.hasReactions = true
        Assert.assertFalse("reactions are not messages", TranslationDecision.isEligible(reaction, ON))

        val deleted = live("gone")
        deleted.deleted = true
        Assert.assertFalse(TranslationDecision.isEligible(deleted, ON))
    }

    @Test
    fun pgpIsNotEligibleBecauseTheBodyIsStillCiphertext() {
        val candidate = live("-----BEGIN PGP MESSAGE-----")
        candidate.encryption = Message.ENCRYPTION_PGP
        Assert.assertFalse(TranslationDecision.isEligible(candidate, ON))
    }

    @Test
    fun omemoAndOtrAreEligibleOnceDecrypted() {
        val axolotl = live("Hey, are we still meeting tomorrow?")
        axolotl.encryption = Message.ENCRYPTION_AXOLOTL
        Assert.assertTrue(TranslationDecision.isEligible(axolotl, ON))

        val otr = live("Hey, are we still meeting tomorrow?")
        otr.encryption = Message.ENCRYPTION_OTR
        Assert.assertTrue(TranslationDecision.isEligible(otr, ON))
    }

    @Test
    fun aDecryptedPgpBodyCanBeBoughtByATapButCiphertextNeverCan() {
        // The shape the PGP decryption service leaves behind when it succeeds: it replaces the row's
        // body with the plaintext and marks the row ENCRYPTION_DECRYPTED, and nothing re-offers that
        // row to Tulkki. So it is covered like archive history - readable one message at a time, when
        // the owner taps it - and this is the tap's half of the rule. Before this, the tap answered
        // "this message cannot be translated" for a body the database already held in the clear.
        val decrypted = live("Guten Morgen! Wie geht es dir heute?")
        decrypted.encryption = Message.ENCRYPTION_DECRYPTED
        Assert.assertTrue(
                "a decrypted body is a readable body: the tap must buy this one message",
                TranslationDecision.isRequestable(decrypted, ON))

        // The refusal of a body that is still a blob is exactly as it was, on both ways in: a
        // ciphertext body must never be sent anywhere, automatic pass or tap.
        val ciphertext = live("-----BEGIN PGP MESSAGE-----")
        ciphertext.encryption = Message.ENCRYPTION_PGP
        Assert.assertFalse(
                "ciphertext must stay unrequestable",
                TranslationDecision.isRequestable(ciphertext, ON))
        Assert.assertFalse(
                "and unreachable by the automatic pass",
                TranslationDecision.isEligible(ciphertext, ON))
    }

    @Test
    fun theOtherEncryptionValuesKeepTheirAnswers() {
        // One row per Message.ENCRYPTION_* constant, so a new value has to be answered for here
        // rather than silently inheriting a default. DECRYPTED is the only one this change moved.
        Assert.assertTrue(TranslationDecision.isReadableBody(Message.ENCRYPTION_NONE))
        Assert.assertFalse(
                "still ciphertext when it is stored",
                TranslationDecision.isReadableBody(Message.ENCRYPTION_PGP))
        Assert.assertTrue(TranslationDecision.isReadableBody(Message.ENCRYPTION_OTR))
        Assert.assertTrue(
                "the decryption service's plaintext is readable",
                TranslationDecision.isReadableBody(Message.ENCRYPTION_DECRYPTED))
        Assert.assertFalse(
                "a decryption that failed never became text",
                TranslationDecision.isReadableBody(Message.ENCRYPTION_DECRYPTION_FAILED))
        Assert.assertTrue(TranslationDecision.isReadableBody(Message.ENCRYPTION_AXOLOTL))
        Assert.assertFalse(
                TranslationDecision.isReadableBody(Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE))
        Assert.assertFalse(TranslationDecision.isReadableBody(Message.ENCRYPTION_AXOLOTL_FAILED))
    }

    @Test
    fun aLinkOrACodeHasNoLanguage() {
        Assert.assertFalse(TranslationDecision.hasLanguage("https://example.com/some/page?x=1", null))
        Assert.assertFalse(TranslationDecision.hasLanguage("xmpp:someone@example.com", null))
        Assert.assertFalse(TranslationDecision.hasLanguage("123456", null))
        Assert.assertFalse(TranslationDecision.hasLanguage("😂😂😂", null))
        Assert.assertFalse(TranslationDecision.hasLanguage("  ", null))
        Assert.assertFalse(TranslationDecision.hasLanguage(null, null))
        Assert.assertFalse(TranslationDecision.hasLanguage("+1", null))
    }

    @Test
    fun somethingWithLettersHasLanguage() {
        Assert.assertTrue(TranslationDecision.hasLanguage("ok", null))
        Assert.assertTrue(TranslationDecision.hasLanguage("Привет", null))
        Assert.assertTrue(TranslationDecision.hasLanguage("Hei, mitä kuuluu?", null))
    }

    @Test
    fun aMessageAlreadyInTheAppLanguageIsNotTranslatedLocally() {
        Assert.assertEquals(
                TranslationDecision.Verdict.SAME_LANGUAGE,
                TranslationDecision.classify(
                        "Moi! Mennäänkö huomenna kahville, minulla on asiaa sinulle.", "fi", null, ON))
    }

    @Test
    fun anotherLanguageIsSentToTheModel() {
        Assert.assertEquals(
                TranslationDecision.Verdict.TRANSLATE,
                TranslationDecision.classify(
                        "Hey, are we still meeting tomorrow for coffee? I have news.", "fi", null, ON))
    }

    @Test
    fun aLinkIsNotSentAnywhere() {
        Assert.assertEquals(
                TranslationDecision.Verdict.NO_LANGUAGE,
                TranslationDecision.classify("https://example.com/a/b", "fi", null, ON))
    }

    @Test
    fun aShortGreetingIsSentBecauseTheDetectorHasNoOpinion() {
        // "ok" is not confidently Finnish, and the model is a better judge of a two-letter message
        // than an offline profile is; skipping it would hide a message.
        Assert.assertEquals(
                TranslationDecision.Verdict.TRANSLATE,
                TranslationDecision.classify("ok", "fi", null, ON))
    }

    @Test
    fun aCandidateThatWasNeverFilledInIsNotEligible() {
        Assert.assertFalse(TranslationDecision.isEligible(TranslationDecision.Candidate(), ON))
        Assert.assertFalse(TranslationDecision.isEligible(null, ON))
    }

    // -- asked for: one message the owner pointed at ------------------------------------------------

    @Test
    fun anExplicitTapMayTranslateHistoryTheAutomaticPassRefuses() {
        // The whole point of the gesture: the automatic pass never translates the archive in bulk,
        // and a person pointing at one message is not a bulk.
        val archived = live("Hello from last year")
        archived.fromArchive = true
        Assert.assertFalse(TranslationDecision.isEligible(archived, ON))
        Assert.assertTrue(TranslationDecision.isRequestable(archived, ON))

        val delayed = live("Delivered while you were away")
        delayed.delayed = true
        Assert.assertFalse(TranslationDecision.isEligible(delayed, ON))
        Assert.assertTrue(TranslationDecision.isRequestable(delayed, ON))
    }

    @Test
    fun askingForSomethingUnreachableIsStillRefused() {
        // Who asked changes what may be queued, never what may be bought: pointing at a ciphertext
        // blob, a file, an edit, a reaction or a body with no language pays for nothing.
        val pgp = live("-----BEGIN PGP MESSAGE-----")
        pgp.encryption = Message.ENCRYPTION_PGP
        Assert.assertFalse(TranslationDecision.isRequestable(pgp, ON))

        val file = live("caption")
        file.hasFileParams = true
        Assert.assertFalse(TranslationDecision.isRequestable(file, ON))

        val edit = live("fixed typo")
        edit.hasReplacement = true
        Assert.assertFalse(TranslationDecision.isRequestable(edit, ON))

        val reaction = live("great news")
        reaction.hasReactions = true
        Assert.assertFalse(TranslationDecision.isRequestable(reaction, ON))

        val deleted = live("gone")
        deleted.deleted = true
        Assert.assertFalse(TranslationDecision.isRequestable(deleted, ON))

        val link = live("https://example.com/a/b")
        Assert.assertFalse(TranslationDecision.isRequestable(link, ON))
    }

    @Test
    fun ourOwnMessagesCannotBeAskedFor() {
        val sent = live("Mine")
        sent.received = false
        Assert.assertFalse(TranslationDecision.isRequestable(sent, ON))
        Assert.assertFalse(TranslationDecision.isRequestable(null, ON))
        Assert.assertFalse(TranslationDecision.isRequestable(TranslationDecision.Candidate(), ON))
    }

    @Test
    fun everyEligibleMessageIsAlsoRequestable() {
        // The asked-for gate is the automatic one with the archive rule taken out, so it can only
        // ever be more permissive - never a second, stricter rule that would make a tap do nothing.
        Assert.assertTrue(TranslationDecision.isRequestable(live("Hey, are we meeting tomorrow?"), ON))
    }

    @Test
    fun aTwoCharacterTokenIsTranslatedRatherThanShownRaw() {
        // The concrete case: app language Finnish, a peer writes German "ja". The detector has no
        // opinion below three characters, so this is TRANSLATE - the message is queued, translated
        // and therefore covered until it is - and `classify`'s single-token SAME_LANGUAGE asymmetry
        // (no prose requirement, unlike ConversationLanguage.read) cannot fire on it. Pinned rather
        // than reasoned: this is the "does anything render that raw" question, answered no.
        Assert.assertTrue(TextLanguage.detect("ja").isUnknown())
        Assert.assertEquals(
                TranslationDecision.Verdict.TRANSLATE,
                TranslationDecision.classify("ja", "fi", null, ON))
        Assert.assertTrue(
                "and it is a message translation was needed for, so it is covered, not raw",
                DisplayedBody.needsTranslation(Message.STATUS_RECEIVED, "ja", null, ON))
    }

    // -- the interpreter off: no verdict is a translation and nothing may be bought -----------------

    /**
     * The off state of both spend gates, on the one shape they allow when the interpreter is on: a
     * live, plain-text, received message. The candidate is the same one
     * {@code aLivePlainTextMessageIsEligible} asserts is eligible, so a {@code false} here can only
     * be the predicate and never the message.
     */
    @Test
    fun theOffInterpreterRefusesTheOtherwiseEligibleShape() {
        val candidate = live("Hey, are we still meeting tomorrow?")
        Assert.assertTrue(
                "with the interpreter on this is the shape both ways in allow",
                TranslationDecision.isEligible(candidate, ON))
        Assert.assertTrue(TranslationDecision.isRequestable(candidate, ON))

        val off = Interpreter.of("fi", "fi")
        Assert.assertFalse(
                "with the interpreter off the automatic pass queues nothing",
                TranslationDecision.isEligible(candidate, off))
        Assert.assertFalse(
                "and there is no covered bubble for a tap to ask from either",
                TranslationDecision.isRequestable(candidate, off))
    }

    /** Every off shape of the truth table, and both ways in, not only the same-language pair. */
    @Test
    fun everyOffPairRefusesBothWaysIn() {
        val candidate = live("Hey, are we still meeting tomorrow?")
        val offPairs = arrayOf(
                arrayOf("fi", "fi"),
                arrayOf("de", "de"),
                arrayOf("en", "en"),
                arrayOf("fi", Interpreter.NONE),
                arrayOf(Interpreter.NONE, "fi"),
                arrayOf("en", Interpreter.NONE),
                arrayOf(Interpreter.NONE, "en"),
                arrayOf(Interpreter.NONE, Interpreter.NONE),
        )
        for (pair in offPairs) {
            val off = Interpreter.of(pair[0], pair[1])
            Assert.assertFalse(
                    "app=" + pair[0] + " study=" + pair[1] + ": the automatic pass must refuse",
                    TranslationDecision.isEligible(candidate, off))
            Assert.assertFalse(
                    "app=" + pair[0] + " study=" + pair[1] + ": a tap must refuse too",
                    TranslationDecision.isRequestable(candidate, off))
        }
    }

    /**
     * {@code classify} never names a translation while the interpreter is off, whatever the body
     * would say with it on: both bodies below are {@code TRANSLATE} with it on, so the off answer is
     * the predicate and not the language rules.
     */
    @Test
    fun theOffInterpreterNeverTranslatesAnything() {
        val prose = "Hey, are we still meeting tomorrow for coffee? I have news."
        val greeting = "ok"
        val off = Interpreter.of("fi", "fi")
        Assert.assertEquals(
                "the prose is a translation to buy when the interpreter is on",
                TranslationDecision.Verdict.TRANSLATE,
                TranslationDecision.classify(prose, "fi", null, ON))
        Assert.assertEquals(
                "and so is the two-character greeting",
                TranslationDecision.Verdict.TRANSLATE,
                TranslationDecision.classify(greeting, "fi", null, ON))
        Assert.assertEquals(
                "with it off neither is a translation",
                TranslationDecision.Verdict.NO_LANGUAGE,
                TranslationDecision.classify(prose, "fi", null, off))
        Assert.assertEquals(
                TranslationDecision.Verdict.NO_LANGUAGE,
                TranslationDecision.classify(greeting, "fi", null, off))
        Assert.assertEquals(
                "and nothing is reported as already in the app language either: off is not a reading",
                TranslationDecision.Verdict.NO_LANGUAGE,
                TranslationDecision.classify(
                        "Moi! Mennäänkö huomenna kahville, minulla on asiaa sinulle.",
                        "fi",
                        null,
                        off))
    }
}
