package uk.xa0.tulkki.ui

import java.util.HashSet
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.translation.DisplayedBody
import uk.xa0.tulkki.translation.HeldSend
import uk.xa0.tulkki.translation.TranslationFailures

/**
 * The one table of words. Two states that mean different things must never read the same, because
 * the whole point of the cover captions and the usage screen's last-failure line is to tell the
 * owner which of "no key, no credit, cap reached, unreachable, rejected key" they are looking at.
 */
class TranslationTextTest {

    @Test
    fun everyCoverHasItsOwnWords() {
        val seen = HashSet<Int>()
        for (cover in DisplayedBody.Cover.values()) {
            val id = TranslationText.coverCaption(cover)
            Assert.assertNotEquals("no cover may be wordless", 0, id)
            Assert.assertTrue("two covers say the same thing: $cover", seen.add(id))
        }
    }

    @Test
    fun everyReasonKeepsItsOwnMeaning() {
        val seen = HashSet<Int>()
        for (reason in HeldSend.HoldReason.values()) {
            val id = TranslationText.failureReason(reason)
            Assert.assertNotEquals("no reason may be wordless", 0, id)
            if (reason == HeldSend.HoldReason.UNKNOWN_LANGUAGE) {
                // A held *send*, not a failed translation: it has to render as something, and
                // "DeepSeek could not translate the message" is the honest nearest thing.
                Assert.assertEquals(TranslationText.failureReason(HeldSend.HoldReason.FAILED), id)
                continue
            }
            Assert.assertTrue("two reasons read the same: $reason", seen.add(id))
        }
    }

    @Test
    fun thePlainCoverAndTheReasonsAreDifferentSentences() {
        // The cover tells the owner what to do; the usage screen only says what happened.
        Assert.assertNotEquals(
            TranslationText.coverCaption(DisplayedBody.Cover.NO_KEY),
            TranslationText.failureReason(HeldSend.HoldReason.NO_KEY),
        )
    }

    @Test
    fun aNullIsNotACrash() {
        Assert.assertEquals(R.string.tulkki_untranslated_tap, TranslationText.coverCaption(null))
        Assert.assertEquals(R.string.tulkki_failure_failed, TranslationText.failureReason(null))
    }

    @Test
    fun everyKindOfTimeSaysWhichItIs() {
        // A failures row shows either a failure's own time or the message's own time. Two rows that
        // meant different things must never read the same, or the screen would claim a failure time
        // it does not have.
        val seen = HashSet<Int>()
        for (whenValue in TranslationFailures.When.values()) {
            val id = TranslationText.failuresWhen(whenValue)
            Assert.assertNotEquals("no time may be wordless", 0, id)
            Assert.assertTrue("two kinds of time read the same: $whenValue", seen.add(id))
        }
        Assert.assertEquals(R.string.tulkki_failures_when_failed, TranslationText.failuresWhen(null))
    }

    @Test
    fun everyOutcomeSaysWhatHappensNext() {
        // Three, not two: a send is held and the retry is the owner's, so it may not read as either
        // "will be tried again" or "given up on".
        val seen = HashSet<Int>()
        for (next in TranslationFailures.Next.values()) {
            val id = TranslationText.failuresNext(next)
            Assert.assertNotEquals("no outcome may be wordless", 0, id)
            Assert.assertTrue("two outcomes read the same: $next", seen.add(id))
        }
        Assert.assertNotEquals(
            TranslationText.failuresNext(TranslationFailures.Next.HELD),
            TranslationText.failuresNext(TranslationFailures.Next.RETRY),
        )
        Assert.assertEquals(R.string.tulkki_failures_gave_up, TranslationText.failuresNext(null))
    }

    @Test
    fun anUnkeptReasonSaysSoRatherThanBorrowingAnothers() {
        // On this screen null means "the record did not reach this message", not "no failure to
        // report": it may not render as any real reason.
        val notKept = TranslationText.failureOrNotKept(null)
        Assert.assertEquals(R.string.tulkki_failures_reason_not_kept, notKept)
        for (reason in HeldSend.HoldReason.values()) {
            Assert.assertNotEquals(
                "an unkept reason must not read as $reason",
                notKept,
                TranslationText.failureOrNotKept(reason),
            )
        }
        Assert.assertEquals(
            TranslationText.failureReason(HeldSend.HoldReason.NO_KEY),
            TranslationText.failureOrNotKept(HeldSend.HoldReason.NO_KEY),
        )
    }
}
