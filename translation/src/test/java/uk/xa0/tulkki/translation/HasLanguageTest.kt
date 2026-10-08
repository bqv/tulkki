package uk.xa0.tulkki.translation

import org.junit.Assert
import org.junit.Test

/**
 * The one rule that decides whether a body has language, and the guard on the merge that made it one.
 *
 * <p>There used to be two copies: {@code TranslationDecision.hasLanguage} on the receive path and
 * {@code ComposerGate.hasLanguage} for the composer. They disagreed about every link shape that was
 * not a complete URI with no whitespace in it, and {@code docs/MIGRATION.md} "The merges" §D listed
 * that disagreement. Its table was wrong for three of the five "bare scheme" rows: it called {@code
 * "xmpp:"}, {@code "mailto:"} and {@code "geo:"} divergences, but {@link Ping}'s whole-body shape
 * answers for all three before either link rule is reached, so both copies already refused them.
 * Only {@code "https://"} and {@code "http://"} genuinely diverged there. The table below is that
 * measurement re-checked against the surviving rule, not against a reading of it.
 *
 * <p>Both halves of the old pair are pinned: the survivor's own answer, and the composer's verdict,
 * which is what the deleted copy used to decide. They have to be the same answer, because there is
 * one rule now and the composer asks it first.
 */
class HasLanguageTest {

    /** The interpreter on: app Finnish, study German, so neither side names English or NONE. */
    private val ON = Interpreter.of("fi", "de")

    /** body, whether the one rule finds language in it. */
    private val TABLE: Array<Array<Any>> =
            arrayOf(
                    // The genuine divergence, and the half the merge widened: a bare http(s) scheme is not
                    // language, so the receive path stops buying it and covering the bubble with nothing to fix.
                    arrayOf<Any>("https://", false),
                    arrayOf<Any>("http://", false),
                    arrayOf<Any>("HTTPS://", false),
                    // The rows §D got wrong: a scheme, a colon and nothing else is a ping (Ping.SHAPE), and both
                    // copies answer at that check before either link rule. These never diverged.
                    arrayOf<Any>("xmpp:", false),
                    arrayOf<Any>("mailto:", false),
                    arrayOf<Any>("geo:", false),
                    arrayOf<Any>("https:", false),
                    arrayOf<Any>("ab:", false),
                    // The other genuine divergence, and the half the merge tightened: whitespace means Java's
                    // \s, not a literal space, so a scheme followed by a newline or a tab is text with a URI in
                    // it and is translated on both paths now.
                    arrayOf<Any>("https://a\nb", true),
                    arrayOf<Any>("https://a\tb", true),
                    arrayOf<Any>("https://a\rb", true),
                    arrayOf<Any>("xmpp:foo\nbar", true),
                    arrayOf<Any>("mailto:a\rb", true),
                    arrayOf<Any>("geo:60\nx", true),
                    // Complete links, which both copies always agreed about.
                    arrayOf<Any>("https://example.org/x", false),
                    arrayOf<Any>("https://example.org/a?b=1#c", false),
                    arrayOf<Any>("geo:60.17,24.94", false),
                    // The rest of the plan's "same" row, plus the link with a literal space in it, which neither
                    // rule ever read as a URI.
                    arrayOf<Any>("42", false),
                    arrayOf<Any>("\uD83D\uDE02\uD83D\uDE02\uD83D\uDE02", false),
                    arrayOf<Any>("+1", false),
                    arrayOf<Any>(":-)", false),
                    arrayOf<Any>("ok", true),
                    arrayOf<Any>("https://a b", true))

    @Test
    fun oneRuleAnswersEveryRowTheTwoCopiesDisagreedAbout() {
        for (row in TABLE) {
            val body = row[0] as String
            Assert.assertEquals(
                    "TranslationDecision.hasLanguage(" + body + ")",
                    row[1],
                    TranslationDecision.hasLanguage(body, null))
        }
    }

    @Test
    fun theComposerAsksThatSameRuleFirst() {
        for (row in TABLE) {
            val body = row[0] as String
            val hasLanguage = row[1] as Boolean
            // The conversation's language is deliberately unknown: the composer's own first question
            // is the only thing under test, and with no language to hold for, "the rule finds
            // language" and "not sent as it stands" are the same answer for every row.
            val verdict = ComposerGate.verdict(body, "fi", null, null, ON)
            Assert.assertEquals(
                    "the gate must send every body the one rule calls no-language, and hold every"
                            + " body it calls language: "
                            + body,
                    hasLanguage,
                    verdict != ComposerGate.Verdict.SEND)
        }
    }

    @Test
    fun theBareSchemeIsSentAndAUrlSplitByWhitespaceIsHeld() {
        // The plan's own two examples, pinned by name. "https://" changed on the receive side (it
        // was called language there, bought, handed back by the model and covered with nothing to
        // fix); "https://a\nb" changed on the compose side (the deleted copy sent it as typed,
        // because a newline is not a space). Which held verdict a language body gets past the gate
        // is the detector's business, so what is pinned is that it is not sent as it stands.
        Assert.assertTrue(TranslationDecision.hasLanguage("https://a\nb", null))
        Assert.assertEquals(
                ComposerGate.Verdict.SEND, ComposerGate.verdict("https://", "fi", "de", null, ON))
        Assert.assertNotEquals(
                ComposerGate.Verdict.SEND,
                ComposerGate.verdict("https://a\nb", "fi", "de", null, ON))
    }
}
