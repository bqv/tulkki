package uk.xa0.tulkki.translation

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item 17's cause vocabulary, and the two rules that keep it one vocabulary rather than two.
 *
 * <p>The stored strings are pinned literally: they are what a `translation_queue` row carries, and a
 * rename that moved them would orphan every row written under the old word (a row with an unknown
 * cause reads as "no cause recorded", which is a silent loss). The four clearable words are also
 * checked against `HeldSend.HoldReason`'s own names, because the app already has one spelling of "no
 * key", "no credit", "the cap" and "a rejected key" and a second spelling of the same fact is exactly
 * what `TranslationFailures` refuses to keep.
 */
class FailureCauseTest {

    @Test
    fun theStoredWordsAreTheColumnsOwn() {
        assertEquals("no_key", FailureCause.NO_KEY.stored())
        assertEquals("rejected_key", FailureCause.REJECTED_KEY.stored())
        assertEquals("no_credit", FailureCause.NO_CREDIT.stored())
        assertEquals("cap_reached", FailureCause.CAP_REACHED.stored())
        assertEquals("check_refused", FailureCause.CHECK_REFUSED.stored())
    }

    @Test
    fun theClearableWordsAreTheReasonsTheAppAlreadyNames() {
        for (cause in
                arrayOf(
                        FailureCause.NO_KEY,
                        FailureCause.REJECTED_KEY,
                        FailureCause.NO_CREDIT,
                        FailureCause.CAP_REACHED)) {
            val reason = HeldSend.HoldReason.valueOf(cause.name)
            assertEquals(
                    "a cause that clears must spell what the reason already spells: " + cause,
                    reason.name.lowercase(Locale.ROOT),
                    cause.stored())
            assertEquals("and map back to itself: " + cause, cause, FailureCause.fromReason(reason))
        }
    }

    @Test
    fun theRefusalIsTheOneCauseNoRetryMayClear() {
        assertFalse(FailureCause.CHECK_REFUSED.clearable())
        for (cause in FailureCause.values()) {
            if (cause != FailureCause.CHECK_REFUSED) {
                assertTrue("cleared by an event outside the row: " + cause, cause.clearable())
            }
        }
        // And it is not a reason the app already had: the refusal rides as `FAILED`, which is also an
        // unusable answer, so the cause is the only place the two can be told apart.
        assertNull(FailureCause.fromReason(HeldSend.HoldReason.FAILED))
    }

    @Test
    fun aRetryableFailureAndASendHoldAreNotCauses() {
        // The backoff axis owns a network failure; a cause must not claim it.
        assertNull(FailureCause.fromReason(HeldSend.HoldReason.UNREACHABLE))
        // An unknown conversation language is a hold on a *send*, never a received row's cause.
        assertNull(FailureCause.fromReason(HeldSend.HoldReason.UNKNOWN_LANGUAGE))
        assertNull(FailureCause.fromReason(null))
    }

    @Test
    fun anUnknownOrAbsentWordReadsAsNoCauseRecorded() {
        assertNull(FailureCause.fromStored(null))
        assertNull(
                "a word a later build stopped writing must not look like a refusal",
                FailureCause.fromStored("something-else"))
        for (cause in FailureCause.values()) {
            assertEquals(cause, FailureCause.fromStored(cause.stored()))
        }
    }
}
