package uk.xa0.tulkki.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.xa0.tulkki.data.model.Message

/**
 * Tulkki: item 16's two doubt kinds, and the opposite taps they want - the rule that had no caller
 * in main code until the tap's entry ({@code OutgoingTranslation.sendHeldNow}) asked
 * {@link HeldDoubt#sendsStoredAnswer}.
 *
 * <p>The distinction is the whole point of the item. An **echo or an empty answer** is nothing
 * translated: the stored text is the owner's own words, so sending it would put the original on the
 * wire, and the tap must discard it and buy a fresh answer. An answer merely in a **doubtful
 * language** is a real translation that no reader could vouch for - doubt rather than evidence - and
 * the owner's tap accepts it, so that one is sent as it stands and buys nothing.
 *
 * <p>The last cell is the persistence half: the message survives being killed, so the answer and the
 * kind have to as well. The answer lives in the message's own row (inert while the state is not
 * `DONE`, `HeldSendTest`'s territory) and the kind in the settings store, and the cell reads the kind
 * back through a **new** settings object over the same store - which is exactly what the next process
 * builds.
 */
class HeldDoubtTest {

    @Test
    fun anEchoOrAnEmptyAnswerIsNeverSentByTheTap() {
        assertEquals(
                LanguageCheck.Tap.RE_TRANSLATE,
                HeldDoubt.tapFor(LanguageCheck.Doubt.NOTHING_TRANSLATED))
        assertFalse(HeldDoubt.sendsStoredAnswer(LanguageCheck.Doubt.NOTHING_TRANSLATED))

        // The two verdicts that are the same fact at the check's own level, asked through `tap()`.
        assertEquals(
                LanguageCheck.Tap.RE_TRANSLATE,
                LanguageCheck.of(
                                "Wie geht es dir heute?",
                                "Wie geht es dir heute?",
                                "fi",
                                "fi",
                                "de")
                        .tap())
        assertEquals(
                LanguageCheck.Tap.RE_TRANSLATE,
                LanguageCheck.of("Wie geht es dir heute?", "   ", "fi", "fi", "de").tap())

        // Nothing at all stored: a plain held send keeps the behaviour it always had.
        assertEquals(LanguageCheck.Tap.RE_TRANSLATE, HeldDoubt.tapFor(null))
        assertFalse(HeldDoubt.sendsStoredAnswer(null))
    }

    @Test
    fun aDoubtfulLanguageIsSentAsHeldAndBuysNothing() {
        assertEquals(
                LanguageCheck.Tap.SEND_AS_HELD,
                HeldDoubt.tapFor(LanguageCheck.Doubt.DOUBTFUL_LANGUAGE))
        assertTrue(HeldDoubt.sendsStoredAnswer(LanguageCheck.Doubt.DOUBTFUL_LANGUAGE))
    }

    @Test
    fun theStoredKindRoundTripsThroughItsOwnName() {
        for (doubt in LanguageCheck.Doubt.values()) {
            assertEquals(
                    "the stored form is the enum's own name",
                    doubt,
                    HeldDoubt.parse(HeldDoubt.name(doubt)))
        }
        assertNull(HeldDoubt.parse(null))
        assertNull(HeldDoubt.parse(""))
        assertNull(HeldDoubt.parse("  "))
        assertNull("an unknown name is no kind at all, not a guess", HeldDoubt.parse("SOMETHING_ELSE"))
        assertEquals("hold-doubt:message-1", HeldDoubt.key("message-1"))
    }

    @Test
    fun aHeldDoubtSurvivesTheProcessBeingKilled() {
        // What `holdOnDoubt` writes: the kind into the settings store, the answer into the message.
        val uuid = "message-1"
        val store = TranslationSettings.MapPrefs()
        val held = TranslationSettings(store, store)
        held.setHeldDoubt(uuid, LanguageCheck.Doubt.DOUBTFUL_LANGUAGE)

        // A kill loses every live object; the next process builds a settings object over the same
        // store, and that is what decides this message's tap.
        val reloaded = TranslationSettings(store, store)
        assertEquals(
                LanguageCheck.Doubt.DOUBTFUL_LANGUAGE,
                reloaded.heldDoubt(uuid))
        assertTrue(HeldDoubt.sendsStoredAnswer(reloaded.heldDoubt(uuid)))

        // The answer the reloaded process reads is the message's own stored one, and it is inert for
        // the draft the owner keeps seeing while the state is `FAILED`.
        val draft = "Kiitos!"
        val stored = "Thank you!"
        assertEquals(
                "the kept answer must not disturb the draft while the message is held",
                draft,
                HeldSend.draftOf(draft, Message.TRANSLATION_FAILED, stored))
        assertFalse("and it is what the tap sends", stored.trim().isEmpty())

        // Releasing the message is a removal, so the next process carries no kind at all.
        reloaded.setHeldDoubt(uuid, null)
        assertNull(TranslationSettings(store, store).heldDoubt(uuid))
    }
}
