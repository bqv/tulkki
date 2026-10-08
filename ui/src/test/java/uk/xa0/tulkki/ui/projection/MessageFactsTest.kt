package uk.xa0.tulkki.ui.projection

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.messages.ConversationBodies
import uk.xa0.tulkki.data.messages.MessageSnapshot
import uk.xa0.tulkki.data.model.Message

/**
 * `ui-9`'s cell over the second seam's "I know nothing" answer: the discipline `PerProcess.NONE` follows, so
 * a `:ui` cell never has to spell a live state it is not about.
 *
 * <p>The seam's own implementation - the host that reads the service - and the cells for each fact it
 * answers are ①b-ii's; what is pinned here is that the shape's default is the honest one and not a plausible
 * invention (a held row that is not held, a transfer that does not exist, a tap that is always offered).
 */
class MessageFactsTest {

    @Test
    fun noneIsTheAnswerOfACallerThatKnowsNoLiveState() {
        val facts = MessageFacts.NONE
        val row = row()
        Assert.assertNull("nothing is quoted", facts.quoted(row))
        Assert.assertEquals("no live crypto phase", CryptoPhase.NONE, facts.crypto(row))
        Assert.assertNull("nothing is held", facts.held(row))
        Assert.assertNull("no notes were bought", facts.review(row))
        Assert.assertEquals("no transfer", UiTransferState.None, facts.transfer(row))
        Assert.assertNull(
            "and no live row means no payload tree and no pixels: the cell draws its generic word rather"
                + " than the stored body, which is not the file's name",
            facts.attachment(row),
        )
        Assert.assertFalse("nor is a tap ever offered on doubt", facts.canTranslateNow(row))
        Assert.assertNull(
            "and a caller that cannot render a smear has none - which is why the image is a fact at all,"
                + " and why a projection may not fall back to the text it conceals",
            facts.smear(row),
        )
        Assert.assertNull("no English was ever bought for this row", facts.english(row))
        Assert.assertEquals(
            "and no live row means no declared span to read, so the composed body is what a snapshot knows",
            "Hei",
            facts.strippedBody(row),
        )
        Assert.assertNull(
            "nor is there a payload tree to read the declared reply fallback from, so a caller that"
                + " cannot resolve the target draws no quote rather than an empty one",
            facts.replyFallback(row),
        )
    }

    @Test
    fun theEnglishInHandIsWordsAndPixelsAndPrintsNeither() {
        val english = EnglishInHand("a bought English sentence nobody may log", null)
        Assert.assertEquals("the words are what a revealed row draws", "a bought English sentence nobody may log", english.text)
        Assert.assertNull("and a host that cannot draw leaves the projection its placeholder", english.blurred)
        val printed = english.toString()
        Assert.assertFalse(
            "the words must not reach a log line: $printed",
            printed.contains("bought English"),
        )
        Assert.assertTrue("the shape is what is left: $printed", printed.contains("chars"))
    }

    private fun row(): MessageSnapshot =
        MessageSnapshot(
            id = "m1",
            conversationId = "c1",
            timeSent = 0L,
            counterpart = "them@example.org",
            trueCounterpart = null,
            type = Message.TYPE_TEXT.toLong(),
            status = Message.STATUS_RECEIVED.toLong(),
            encryption = Message.ENCRYPTION_NONE.toLong(),
            delivery = 0L,
            read = 0L,
            deleted = 0L,
            fileDeleted = 0L,
            markable = 0L,
            oob = 0L,
            carbon = 0L,
            retractId = null,
            edited = null,
            serverMsgId = null,
            remoteMsgId = null,
            axolotlFingerprint = null,
            occupantId = null,
            relativeFilePath = null,
            fileParams = null,
            oobUri = null,
            errorMsg = null,
            bodyLanguage = null,
            reactions = null,
            readByMarkers = null,
            translationState = Message.TRANSLATION_NONE.toLong(),
            translationLang = null,
            timeReceived = null,
            subject = null,
            expireAt = 0L,
            ephemeralTimer = 0L,
            notificationDismissed = null,
            bodies = ConversationBodies(translated = null, original = "Hei"),
        )
}
