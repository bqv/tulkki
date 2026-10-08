package uk.xa0.tulkki.ui.projection

import androidx.compose.ui.graphics.ImageBitmap
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.messages.ConversationBodies
import uk.xa0.tulkki.data.messages.MessageSnapshot
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.data.model.Reaction
import uk.xa0.tulkki.translation.DisplayedBody
import uk.xa0.tulkki.translation.HeldSend
import uk.xa0.tulkki.translation.Interpreter

/**
 * The projector's cells: every rule `Design: the Compose UI` §2.1/§2.2/§2.3 hands to
 * [MessageProjection], over snapshots alone.
 *
 * <p>What a JVM cell here **cannot** reach, stated rather than discovered: an `ImageBitmap` needs a
 * graphics backend, and `:ui` has no Robolectric and no mocking library, so a [MessageFacts] fake in a
 * unit test can only answer `null` for a smear. That is why every "the original is not drawn" cell is
 * reached through the projection's own fallback - a concealed half with no pixels is a placeholder, never
 * text - and why the smear branch itself is the screenshot suite's (§7.1/§7.2). The fallback is a real
 * branch of the code, not a test-only one: a host that cannot render takes it too.
 *
 * <p>The one cell the owner asked for by name is [thePlaceholdersShapeIsNotAFunctionOfTheHiddenBody]: the
 * placeholder's shape must carry no fact about the words it stands in for.
 */
class MessageProjectionTest {

    @Test
    fun theTopHalfIsTheAppLanguageAndTheBottomHidesTheOtherSide() {
        val received =
            row(
                message(
                    id = "m1",
                    original = "die Antwort",
                    translated = "the answer",
                    translationState = Message.TRANSLATION_DONE.toLong(),
                    translationLang = "de",
                )
            )
        Assert.assertEquals(UiBody.Visible("the answer"), received.top)
        Assert.assertTrue(
            "somebody else's original is concealed, never drawn: ${received.bottom}",
            received.bottom is UiBody.Concealed,
        )
        Assert.assertTrue(received.divider)
        Assert.assertEquals("and no English row without its switch", UiEnglishRow.Absent, received.english)
        Assert.assertFalse("the screen owns selection, never the projector", received.selected)
        Assert.assertFalse(
            "neither body may print: $received",
            received.toString().contains("die Antwort") || received.toString().contains("the answer"),
        )
        // The owner's own bubble is the same two sides the other way round: the app language on top,
        // the text that went on the wire below it, and that one readable.
        val sent =
            row(
                message(
                    id = "m2",
                    status = Message.STATUS_SEND.toLong(),
                    original = "die Antwort",
                    translated = "the answer",
                    translationState = Message.TRANSLATION_DONE.toLong(),
                )
            )
        Assert.assertEquals(UiBody.Visible("the answer"), sent.top)
        Assert.assertEquals(UiBody.Visible("die Antwort"), sent.bottom)
    }

    @Test
    fun noCombinationOfSettingsMakesAReceivedOriginalReadable() {
        val received =
            message(
                id = "m1",
                original = "die Antwort",
                translated = "the answer",
                translationState = Message.TRANSLATION_DONE.toLong(),
            )
        for (showSecondHalf in BOOLEANS) {
            for (showConcealedOriginal in BOOLEANS) {
                for (concealOwnSecondHalf in BOOLEANS) {
                    val settings =
                        ProjectionSettings(
                            showSecondHalf,
                            showConcealedOriginal,
                            concealOwnSecondHalf,
                            false,
                            false,
                        )
                    val projected = row(received, settings = settings)
                    Assert.assertFalse(
                        "a contact's original is never readable, whatever the three switches say"
                            + " ($settings): ${projected.bottom}",
                        projected.bottom is UiBody.Visible,
                    )
                }
            }
        }
        // And the one conceal a switch may undo does reach the owner's own half.
        val sent =
            message(
                id = "m2",
                status = Message.STATUS_SEND.toLong(),
                original = "die Antwort",
                translated = "the answer",
                translationState = Message.TRANSLATION_DONE.toLong(),
            )
        val concealedOwn =
            row(sent, settings = ProjectionSettings(concealOwnSecondHalf = true))
        Assert.assertTrue(
            "the owner's own half is concealed on request: ${concealedOwn.bottom}",
            concealedOwn.bottom is UiBody.Concealed,
        )
    }

    @Test
    fun theSecondHalfSwitchOffTakesTheDividerWithTheHalf() {
        val sent =
            message(
                id = "m1",
                status = Message.STATUS_SEND.toLong(),
                original = "die Antwort",
                translated = "the answer",
                translationState = Message.TRANSLATION_DONE.toLong(),
            )
        val projected = row(sent, settings = ProjectionSettings(showSecondHalf = false))
        Assert.assertNull("the outer gate removes the half", projected.bottom)
        Assert.assertFalse("and the divider goes with it", projected.divider)
        Assert.assertEquals("the top half is untouched", UiBody.Visible("the answer"), projected.top)
    }

    @Test
    fun withTheInterpreterOffEveryRowIsOneHalfAndNoEnglishRow() {
        val off = conversation(interpreter = Interpreter.of("fi", "fi"))
        val projected =
            row(
                message(
                    id = "m1",
                    original = "die Antwort",
                    translated = "the answer",
                    translationState = Message.TRANSLATION_DONE.toLong(),
                    translationLang = "de",
                ),
                settings = ProjectionSettings(showEnglishRow = true, showSentEnglishRow = true),
                facts =
                    Facts().apply {
                        holdReason = HeldSend.HoldReason.NO_KEY
                        englishInHand = EnglishInHand("a bought English", null)
                    },
                conversation = off,
            )
        Assert.assertEquals("a plain client shows what arrived", UiBody.Visible("die Antwort"), projected.top)
        Assert.assertNull(projected.bottom)
        Assert.assertFalse(projected.divider)
        Assert.assertEquals(UiEnglishRow.Absent, projected.english)
    }

    @Test
    fun aCoveredBodyIsAPlaceholderWithItsReasonAndNeverTheWord() {
        val covered = message(id = "m1", original = "die Antwort", translationLang = "de")
        val refused = row(covered, facts = Facts().apply { holdReason = HeldSend.HoldReason.NO_KEY })
        val placeholder = (refused.top as UiBody.Concealed).concealment as UiConcealment.Placeholder
        Assert.assertEquals(
            "the reason the bubble says for itself is the row's own, from the seam's one ask",
            DisplayedBody.Cover.NO_KEY,
            placeholder.reason,
        )
        Assert.assertFalse(
            "a covered body is never the word it covers: $refused",
            refused.toString().contains("die Antwort"),
        )
        // Nothing known: the plain cover, and a tap is what asks.
        val plain = row(covered)
        Assert.assertEquals(
            DisplayedBody.Cover.TAP,
            ((plain.top as UiBody.Concealed).concealment as UiConcealment.Placeholder).reason,
        )
    }

    @Test
    fun thePlaceholdersShapeIsNotAFunctionOfTheHiddenBody() {
        val short = placeholderShape(row(message(id = "m1", original = "Ein kurzer Satz.")))
        val long =
            placeholderShape(
                row(
                    message(
                        id = "m1",
                        original = "Ein sehr langer Satz, der ueber mehrere Zeilen laeuft. ".repeat(20),
                    )
                )
            )
        Assert.assertEquals(
            "§6.1: the shape carries a line count and widths and no fact about the text",
            short,
            long,
        )
        Assert.assertEquals(2, short.lines)
        Assert.assertArrayEquals(floatArrayOf(0.75f, 0.5f), short.widths, 0f)
        // The seed is the message's, so the same row shimmers the same shape and two rows do not.
        val other = placeholderShape(row(message(id = "m2", original = "Ein kurzer Satz.")))
        Assert.assertNotEquals("a different row is a different shape", short.seed, other.seed)
        Assert.assertEquals(
            "and the same row is the same shape, whatever its body grew into",
            short,
            placeholderShape(row(message(id = "m1", original = "Ein kurzer Satz."))),
        )
    }

    @Test
    fun aHeldSendIsHeldAndTheStatusColumnAloneCannotTellIt() {
        val waiting = message(id = "m1", status = Message.STATUS_WAITING.toLong(), original = "the draft")
        val held =
            row(waiting, facts = Facts().apply { holdReason = HeldSend.HoldReason.CAP_REACHED })
        Assert.assertEquals(
            "the status alone cannot separate Tulkki's held row from upstream's in-flight one",
            UiDeliveryState.Held(HeldSend.HoldReason.CAP_REACHED),
            held.delivery,
        )
        Assert.assertEquals("the owner's own text stays readable while it waits", UiBody.Visible("the draft"), held.top)
        Assert.assertEquals(
            "the same row without the hold is upstream's in-flight one",
            UiDeliveryState.Sending,
            row(waiting).delivery,
        )
        Assert.assertEquals(UiDeliveryState.Sent, row(message(id = "m1", status = Message.STATUS_SEND.toLong())).delivery)
        Assert.assertEquals(
            UiDeliveryState.Delivered,
            row(message(id = "m1", status = Message.STATUS_SEND_RECEIVED.toLong())).delivery,
        )
        Assert.assertEquals(
            UiDeliveryState.Displayed,
            row(message(id = "m1", status = Message.STATUS_SEND_DISPLAYED.toLong())).delivery,
        )
        // The hole §2.2.1 #2 records: `Failed(reason)` has no vocabulary, and the projector does not
        // invent a success. The day it lands, this cell is the red one.
        Assert.assertEquals(
            "a failed send draws as Sending until the failure vocabulary exists",
            UiDeliveryState.Sending,
            row(message(id = "m1", status = Message.STATUS_SEND_FAILED.toLong())).delivery,
        )
        Assert.assertEquals(
            "a received row has no status line; the honest value is that it is here",
            UiDeliveryState.Delivered,
            row(message(id = "m1")).delivery,
        )
    }

    @Test
    fun aSystemRowDrawsItsOwnWordsAndNoHalf() {
        val projected =
            row(
                message(
                    id = "m1",
                    type = Message.TYPE_STATUS.toLong(),
                    original = "die Antwort",
                    translated = "the answer",
                    translationState = Message.TRANSLATION_DONE.toLong(),
                )
            )
        Assert.assertEquals(MessageType.SYSTEM, projected.type)
        Assert.assertEquals(
            "a status row's words are the app's own, so the concealment rules are not asked about them",
            UiBody.Visible("die Antwort"),
            projected.top,
        )
        Assert.assertNull(projected.bottom)
        Assert.assertFalse(projected.divider)
    }

    @Test
    fun aCallRowAndATransferRowDrawNoBodyText() {
        val call = row(message(id = "m1", type = Message.TYPE_RTP_SESSION.toLong(), original = "{\"duration\":3}"))
        Assert.assertEquals(MessageType.CALL, call.type)
        Assert.assertEquals("a call's body is the payload the preview parses", UiBody.Absent, call.top)
        val file = row(message(id = "m1", type = Message.TYPE_FILE.toLong(), original = "https://example.org/a.pdf"))
        Assert.assertEquals(MessageType.TRANSFER, file.type)
        Assert.assertEquals("a file row's body is an address, not prose", UiBody.Absent, file.top)
    }

    @Test
    fun aTransferRowDrawsItsAttachmentAndNoOtherRowDoes() {
        val file = row(message(id = "m1", type = Message.TYPE_FILE.toLong(), original = "https://example.org/a.pdf"))
        val cell = attachmentOf(file)
        Assert.assertEquals(AttachmentKind.FILE, cell.kind)
        Assert.assertEquals(
            "no local file and no live transfer is the fourth state, not an empty bubble",
            UiTransferState.None,
            cell.state,
        )
        for (type in listOf(Message.TYPE_TEXT, Message.TYPE_STATUS, Message.TYPE_RTP_SESSION)) {
            Assert.assertNull(
                "only a transfer row grows a cell: $type",
                row(message(id = "m1", type = type.toLong(), original = "Hei!")).attachment,
            )
        }
    }

    @Test
    fun theStoredLocalFileIsReadyEvenWhenTheLiveTransferIsGone() {
        val ready =
            row(
                message(
                    id = "m1",
                    type = Message.TYPE_FILE.toLong(),
                    relativeFilePath = "files/a.pdf",
                )
            )
        Assert.assertEquals(UiTransferState.Ready("files/a.pdf"), attachmentOf(ready).state)
        Assert.assertEquals("files/a.pdf", attachmentOf(ready).localUri)
        val deleted =
            row(
                message(
                    id = "m1",
                    type = Message.TYPE_FILE.toLong(),
                    relativeFilePath = "files/a.pdf",
                    fileDeleted = 1L,
                )
            )
        Assert.assertEquals(
            "a deleted file is not drawn as ready: the snapshot's own flag decides",
            UiTransferState.None,
            attachmentOf(deleted).state,
        )
    }

    @Test
    fun theCellsFourStatesAreFourDifferentRows() {
        val image = Message.TYPE_IMAGE.toLong()
        val downloading = Facts().apply { transferState = UiTransferState.Downloading(42, 900L) }
        Assert.assertEquals(
            UiTransferState.Downloading(42, 900L),
            attachmentOf(row(message(id = "m1", type = image), facts = downloading)).state,
        )
        val ready = Facts().apply { transferState = UiTransferState.Ready("file:///phone/a.jpg") }
        Assert.assertEquals(
            "the host's absolute URI wins over the stored relative path",
            UiTransferState.Ready("file:///phone/a.jpg"),
            attachmentOf(row(message(id = "m1", type = image), facts = ready)).state,
        )
        val failed = Facts().apply { transferState = UiTransferState.Failed }
        Assert.assertEquals(
            UiTransferState.Failed,
            attachmentOf(row(message(id = "m1", type = image), facts = failed)).state,
        )
        val remote =
            attachmentOf(row(message(id = "m1", type = image, oobUri = "https://example.org/a.jpg")))
        Assert.assertEquals(UiTransferState.None, remote.state)
        Assert.assertEquals("https://example.org/a.jpg", remote.remoteUri)
        Assert.assertEquals(AttachmentKind.IMAGE, remote.kind)
        Assert.assertNull("an unfetched remote cell has no local file", remote.localUri)
    }

    @Test
    fun theSizeComesFromTheStoredTransferParametersOrTheLiveState() {
        val stored = row(message(id = "m1", type = Message.TYPE_FILE.toLong(), fileParams = "https://e.org/a|2048|0|0"))
        Assert.assertEquals(2048L, attachmentOf(stored).sizeBytes)
        val live = Facts().apply { transferState = UiTransferState.Offered(4096L) }
        Assert.assertEquals(
            "the live offer's size wins where it has one",
            4096L,
            attachmentOf(row(message(id = "m1", type = Message.TYPE_FILE.toLong(), fileParams = "https://e.org/a|2048|0|0"), facts = live)).sizeBytes,
        )
    }

    @Test
    fun aStoppedTransferWithAStoredReasonIsFailedAndItsWordsAreNotDrawn() {
        val file =
            row(
                message(
                    id = "m1",
                    type = Message.TYPE_FILE.toLong(),
                    errorMsg = "connection lost",
                )
            )
        Assert.assertEquals(UiTransferState.Failed, attachmentOf(file).state)
        Assert.assertFalse(
            "the failure is an outcome and never the stored words: ${file.attachment}",
            file.toString().contains("connection lost"),
        )
        val cancelled =
            row(
                message(
                    id = "m1",
                    type = Message.TYPE_FILE.toLong(),
                    errorMsg = Message.ERROR_MESSAGE_CANCELLED,
                )
            )
        Assert.assertEquals(
            "the tree tells the two terminal outcomes apart, so the cell does too",
            UiTransferState.Cancelled,
            attachmentOf(cancelled).state,
        )
    }

    @Test
    fun theCellsNameIsThePayloadTreesAndNeverTheStoredBody() {
        val facts =
            Facts().apply {
                attachmentInHand = AttachmentInHand("report.pdf", null)
                transferState = UiTransferState.Ready("file:///phone/report.pdf")
            }
        val body = "https://example.org/secret.pdf"
        val file = row(message(id = "m1", type = Message.TYPE_FILE.toLong(), original = body), facts = facts)
        val cell = attachmentOf(file)
        Assert.assertEquals("report.pdf", cell.name)
        Assert.assertEquals(listOf("m1"), facts.askedAbout("attachment"))
        Assert.assertNotEquals("the stored body is not the file's name", body, cell.name)
        Assert.assertFalse("the cell prints the name as a shape only", file.toString().contains("report.pdf"))
        Assert.assertFalse(file.toString().contains(body))
    }

    @Test
    fun theReactionCountIsTheGroupsSizeAndMineIsTheDocumentsReceivedNegated() {
        val projected = row(message(id = "m1", original = "Hei", reactions = REACTIONS))
        Assert.assertEquals(
            listOf(
                UiReaction("\uD83D\uDC4D", 2, true),
                UiReaction("\uD83C\uDF89", 1, false),
            ),
            projected.reactions,
        )
    }

    @Test
    fun aCustomEmojiGroupIsNotDrawnBecauseTheChipCarriesNoImage() {
        Assert.assertTrue(
            "the document must decode, or this cell proves nothing: ${Reaction.fromString(CUSTOM_REACTION)}",
            Reaction.fromString(CUSTOM_REACTION).isNotEmpty(),
        )
        Assert.assertEquals(
            "a custom emoji's chip is an image `UiReaction` has no field for",
            emptyList<UiReaction>(),
            row(message(id = "m1", original = "Hei", reactions = CUSTOM_REACTION)).reactions,
        )
    }

    @Test
    fun theReadingAidsTargetsAreTheDrawnWordsOfTheAppLanguageHalf() {
        val projected =
            row(
                message(
                    id = "m1",
                    original = "Hallo Welt",
                    translated = "Hei maailma",
                    translationState = Message.TRANSLATION_DONE.toLong(),
                    translationLang = "de",
                ),
                conversation = conversation(studyLanguage = null),
            )
        Assert.assertEquals(UiBody.Visible("Hei maailma"), projected.top)
        Assert.assertEquals(
            "the offsets belong to the string the screen draws, not to the original",
            listOf(UiGlossWord("Hei", 0, 3), UiGlossWord("maailma", 4, 11)),
            projected.gloss,
        )
    }

    @Test
    fun theReadingAidHasNoTargetsWhileTheInterpreterIsOff() {
        val off = conversation(interpreter = Interpreter.of("fi", "fi"), studyLanguage = null)
        Assert.assertEquals(
            "off: no token, no tap target, no card - the empty list is the whole surface",
            emptyList<UiGlossWord>(),
            row(message(id = "m1", original = "Hallo Welt"), conversation = off).gloss,
        )
    }

    @Test
    fun aCoveredBodyOffersNoWordsBecauseItsTapIsSpokenFor() {
        val covered =
            row(
                message(id = "m1", original = "die Antwort", translationLang = "de"),
                conversation = conversation(studyLanguage = null),
            )
        Assert.assertTrue(
            "the row must be covered, or this cell proves nothing: ${covered.top}",
            covered.top is UiBody.Concealed,
        )
        Assert.assertEquals(emptyList<UiGlossWord>(), covered.gloss)
    }

    @Test
    fun aSentReplysReadableHalfIsTheBodyWithoutItsReplyFallback() {
        // A sent reply's wire body is the quote it carries plus the owner's own words, and the quote is
        // the referenced row's original - drawn by `quote` and concealed there. The halves must be built
        // from the seam's fallback-free side, or the quote is printed a second time as readable text.
        val reply =
            message(
                id = "m1",
                status = Message.STATUS_SEND.toLong(),
                original = "> die Frage\nmy answer",
                translated = "the answer",
                translationState = Message.TRANSLATION_DONE.toLong(),
            )
        val facts = Facts().apply { strippedBodies = mapOf("m1" to "my answer") }
        val projected = row(reply, facts = facts)
        Assert.assertEquals(
            "the readable half is the owner's own text, not the quoted original it carries",
            UiBody.Visible("my answer"),
            projected.bottom,
        )
        Assert.assertEquals(
            "and the strip is the seam's, asked once about this row",
            listOf("m1"),
            facts.askedAbout("strippedBody"),
        )
    }

    @Test
    fun aQuoteIsTheReferencedRowAndACoveredQuoteCarriesNoText() {
        val reply =
            message(
                id = "m1",
                status = Message.STATUS_SEND.toLong(),
                original = "die Antwort",
                translated = "the answer",
                translationState = Message.TRANSLATION_DONE.toLong(),
            )
        val referenced =
            message(
                id = "m0",
                original = "die Frage",
                translated = "the question",
                translationState = Message.TRANSLATION_DONE.toLong(),
                translationLang = "de",
            )
        val quoted = row(reply, facts = Facts().apply { quotedRow = referenced }).quote
        Assert.assertEquals("the quote is the row, by reference", MessageId("m0"), quoted!!.messageId)
        Assert.assertEquals(UiBody.Visible("the question"), quoted.top)
        Assert.assertTrue(
            "a quote of somebody else's row conceals its other side too: ${quoted.bottom}",
            quoted.bottom is UiBody.Concealed,
        )
        Assert.assertTrue(quoted.divider)
        // A referenced row that needed a translation and has none: nothing to draw on top, so no half
        // and no divider, and the cover says so rather than the words.
        val covered =
            row(reply, facts = Facts().apply { quotedRow = message(id = "m0", original = "die Frage") }).quote
        val placeholder = ((covered!!.top as UiBody.Concealed).concealment as UiConcealment.Placeholder)
        Assert.assertEquals(DisplayedBody.Cover.TAP, placeholder.reason)
        Assert.assertNull(covered.bottom)
        Assert.assertFalse(covered.divider)
    }

    @Test
    fun theLiveCryptoPhaseRefinesTheStoredSchemeAndNeverUnfailsIt() {
        Assert.assertEquals(UiEncryption.None, row(message(id = "m1")).encryption)
        Assert.assertEquals(
            "the armour is the stored body, so an unclassified PGP row is encrypted",
            UiEncryption.Encrypted,
            row(message(id = "m1", encryption = Message.ENCRYPTION_PGP.toLong())).encryption,
        )
        Assert.assertEquals(
            UiEncryption.Pending,
            row(
                message(id = "m1", encryption = Message.ENCRYPTION_PGP.toLong()),
                facts = Facts().apply { cryptoPhase = CryptoPhase.PENDING },
            ).encryption,
        )
        Assert.assertEquals(
            UiEncryption.Decrypted,
            row(
                message(id = "m1", encryption = Message.ENCRYPTION_PGP.toLong()),
                facts = Facts().apply { cryptoPhase = CryptoPhase.DECRYPTED },
            ).encryption,
        )
        Assert.assertEquals(
            "a phase cannot un-fail a row the column already calls failed",
            UiEncryption.Failed(EncryptionFailure.PGP),
            row(
                message(id = "m1", encryption = Message.ENCRYPTION_DECRYPTION_FAILED.toLong()),
                facts = Facts().apply { cryptoPhase = CryptoPhase.DECRYPTED },
            ).encryption,
        )
        Assert.assertEquals(
            UiEncryption.Failed(EncryptionFailure.OMEMO),
            row(message(id = "m1", encryption = Message.ENCRYPTION_AXOLOTL_FAILED.toLong())).encryption,
        )
        Assert.assertEquals(
            UiEncryption.NotForThisDevice,
            row(message(id = "m1", encryption = Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE.toLong())).encryption,
        )
        Assert.assertEquals(
            "OMEMO arrives in the clear, so an unclassified row is decrypted",
            UiEncryption.Decrypted,
            row(message(id = "m1", encryption = Message.ENCRYPTION_AXOLOTL.toLong())).encryption,
        )
    }

    @Test
    fun anEnglishRowIsBlurredUntilTheOwnerRevealsIt() {
        val translated =
            message(
                id = "m1",
                original = "die Antwort",
                translated = "the answer",
                translationState = Message.TRANSLATION_DONE.toLong(),
                translationLang = "de",
            )
        val facts = Facts().apply { englishInHand = EnglishInHand("the purchased English", null) }
        val english = ProjectionSettings(showEnglishRow = true)
        val blurred = row(translated, settings = english, facts = facts)
        Assert.assertTrue(
            "in hand and unrevealed is drawn concealed: ${blurred.english}",
            blurred.english is UiEnglishRow.Concealed,
        )
        val revealed = row(translated, settings = english, facts = facts, revealed = setOf(MessageId("m1")))
        Assert.assertEquals(UiEnglishRow.Visible("the purchased English"), revealed.english)
        // The master switch off: no row at all, whatever is in hand.
        Assert.assertEquals(
            UiEnglishRow.Absent,
            row(translated, facts = facts).english,
        )
        // The sent side's own switch, and a room that already speaks English draws none of it.
        val sent = message(id = "m2", status = Message.STATUS_SEND.toLong(), original = "die Antwort")
        Assert.assertEquals(
            UiEnglishRow.Absent,
            row(sent, settings = ProjectionSettings(showSentEnglishRow = true), facts = facts).english,
        )
        Assert.assertTrue(
            "and in a room that speaks something else the sent retranslation is a row",
            row(
                sent,
                settings = ProjectionSettings(showSentEnglishRow = true),
                facts = facts,
                conversation = conversation(conversationLanguage = "de"),
            ).english is UiEnglishRow.Concealed,
        )
        // The original-was-English exception: nothing was bought, and the row's own words are the English.
        val wasEnglish =
            message(
                id = "m3",
                original = "the original English",
                translated = "die Uebersetzung",
                translationState = Message.TRANSLATION_DONE.toLong(),
                translationLang = "en",
            )
        Assert.assertEquals(
            UiEnglishRow.Visible("the original English"),
            row(wasEnglish, settings = english, revealed = setOf(MessageId("m3"))).english,
        )
    }

    @Test
    fun theRunsAreComputedFromTheWholeList() {
        val rows =
            rows(
                message(id = "m1", original = "one", timeSent = 1_000L),
                message(id = "m2", original = "two", timeSent = 2_000L),
                message(id = "m3", original = "three", timeSent = 3_000L),
            )
        Assert.assertTrue("the head of the run", rows[0].run.firstOfRun)
        Assert.assertTrue(rows[0].run.showAvatar)
        Assert.assertFalse(rows[0].run.lastOfRun)
        Assert.assertFalse(rows[1].run.firstOfRun)
        Assert.assertFalse(rows[1].run.lastOfRun)
        Assert.assertTrue("the tail of a run of three", rows[2].run.lastOfRun)
        // The window is `Config.MESSAGE_MERGE_WINDOW`: two rows further apart than it are two runs.
        val apart =
            rows(
                message(id = "m1", original = "one", timeSent = 1_000L),
                message(id = "m2", original = "two", timeSent = 1_000L + 90_001L),
            )
        Assert.assertTrue("past the window a new run opens", apart[1].run.firstOfRun)
        // And so is a direction change, which is the other half of the run key.
        val changed =
            rows(
                message(id = "m1", original = "one", timeSent = 1_000L),
                message(
                    id = "m2",
                    original = "two",
                    timeSent = 2_000L,
                    status = Message.STATUS_SEND.toLong(),
                ),
            )
        Assert.assertTrue("the owner's answer is a new run", changed[1].run.firstOfRun)
    }

    @Test
    fun anEmptyListProjectsToNothing() {
        Assert.assertTrue(
            MessageProjection.of(emptyList(), MessageFacts.NONE, ProjectionSettings.SHIPPED, conversation()).isEmpty()
        )
    }

    @Test
    fun theQuoteBorrowsTheReferencedRowsPixelsAndNotThisRows() {
        val reply =
            message(
                id = "m1",
                status = Message.STATUS_SEND.toLong(),
                original = "die Antwort",
                translated = "the answer",
                translationState = Message.TRANSLATION_DONE.toLong(),
            )
        val facts =
            Facts().apply {
                quotedRow =
                    message(
                        id = "m0",
                        original = "die Frage",
                        translated = "the question",
                        translationState = Message.TRANSLATION_DONE.toLong(),
                    )
            }
        row(reply, facts = facts)
        Assert.assertEquals(
            "the concealed half of a quote is the *quoted* row's original, so its pixels are asked of that row",
            listOf("m0"),
            facts.askedAbout("smear"),
        )
        Assert.assertEquals("and the reference is resolved for this row", listOf("m1"), facts.askedAbout("quoted"))
    }

    @Test
    fun oneRowAsksEachFactOnceAndAboutItself() {
        val translated =
            message(
                id = "m1",
                original = "die Antwort",
                translated = "the answer",
                translationState = Message.TRANSLATION_DONE.toLong(),
            )
        val facts = Facts()
        val projected = row(translated, facts = facts)
        Assert.assertTrue("this row's own half is concealed", projected.bottom is UiBody.Concealed)
        Assert.assertEquals(
            "one fact, one ask, about this row: ${facts.asked}",
            listOf("m1"),
            facts.askedAbout("smear"),
        )
        Assert.assertEquals("the reason is asked once, not once per reader", listOf("m1"), facts.askedAbout("held"))
        Assert.assertEquals(listOf("m1"), facts.askedAbout("crypto"))
        Assert.assertEquals(listOf("m1"), facts.askedAbout("review"))
        Assert.assertEquals(listOf("m1"), facts.askedAbout("transfer"))
        Assert.assertEquals(listOf("m1"), facts.askedAbout("english"))
        Assert.assertEquals(listOf("m1"), facts.askedAbout("quoted"))
    }

    @Test
    fun theEnglishRowIsAskedForOnATextRowOnly() {
        val inHand = EnglishInHand("the purchased English", null)
        val settings = ProjectionSettings(showEnglishRow = true, showSentEnglishRow = true)
        val translated =
            message(
                id = "m1",
                original = "die Antwort",
                translated = "the answer",
                translationState = Message.TRANSLATION_DONE.toLong(),
            )
        val textFacts = Facts().apply { englishInHand = inHand }
        Assert.assertTrue(
            row(translated, settings = settings, facts = textFacts).english is UiEnglishRow.Concealed
        )
        Assert.assertEquals(listOf("m1"), textFacts.askedAbout("english"))
        for (type in listOf(Message.TYPE_STATUS, Message.TYPE_RTP_SESSION, Message.TYPE_FILE)) {
            val facts = Facts().apply { englishInHand = inHand }
            row(
                message(id = "m1", type = type.toLong(), original = "die Antwort"),
                settings = settings,
                facts = facts,
            )
            Assert.assertEquals(
                "a row that is not a text row grows no English row: $type",
                emptyList<String>(),
                facts.askedAbout("english"),
            )
        }
    }

    @Test
    fun everyTypedFactLandsInItsOwnField() {
        val cards = UiReview(emptyList(), emptyList())
        val facts =
            Facts().apply {
                cryptoPhase = CryptoPhase.PENDING
                reviewCards = cards
                transferState = UiTransferState.Offered(3L)
                tapOffered = true
            }
        val projected =
            row(
                message(
                    id = "m1",
                    status = Message.STATUS_SEND.toLong(),
                    encryption = Message.ENCRYPTION_PGP.toLong(),
                    original = "die Antwort",
                    translated = "the answer",
                    translationState = Message.TRANSLATION_DONE.toLong(),
                ),
                facts = facts,
            )
        Assert.assertEquals("the live phase, not the column alone", UiEncryption.Pending, projected.encryption)
        Assert.assertEquals("the bought notes", cards, projected.review)
        Assert.assertEquals("the live transfer", UiTransferState.Offered(3L), projected.transfer)
        Assert.assertTrue("and the tap the seam offers", projected.canTranslateNow)
    }

    /**
     * Item 17's decision five, in the projector: the failure gate is the **only** path that turns
     * somebody else's original into readable text, and the owner's tap is the only thing that takes it.
     *
     * <p>These are the two cells the coordinator named as the ones that matter: a row that is merely
     * pending never reveals, and no setting can make one reveal.
     */
    @Test
    fun aFailedReceivedRowOffersItsOriginalOnlyAfterTheOwnersTap() {
        val failed =
            message(
                id = "m1",
                original = "die Antwort",
                translated = null,
                translationState = Message.TRANSLATION_FAILED.toLong(),
            )
        val concealed = row(failed, facts = heldFacts(HeldSend.HoldReason.FAILED))
        val strip = (concealed.original as UiOriginalRow.Concealed).concealment
        Assert.assertTrue("the strip is drawn, and concealed: $strip", strip is UiConcealment.Placeholder)
        Assert.assertTrue(
            "the failure gate's own tap is offered even where the host cannot draw pixels",
            (strip as UiConcealment.Placeholder).revealable,
        )
        Assert.assertTrue("a blurred row has no second half at all", concealed.bottom == null)
        Assert.assertFalse("and it is not text until the owner asks", concealed.toString().contains("die Antwort"))

        val revealed =
            row(
                failed,
                facts = heldFacts(HeldSend.HoldReason.FAILED),
                revealedOriginals = setOf(MessageId("m1")),
            )
        Assert.assertEquals(
            "the owner's tap is the one path that makes it readable",
            UiOriginalRow.Visible("die Antwort"),
            revealed.original,
        )
    }

    @Test
    fun aPendingRowNeverRevealsWhateverIsAskedOfIt() {
        val pending =
            message(
                id = "m1",
                original = "die Antwort",
                translated = null,
                translationState = Message.TRANSLATION_NONE.toLong(),
            )
        // No recorded reason: nothing was attempted, so there is no failure to be the deciding fact -
        // and the owner's tap on a strip that was never offered changes nothing either.
        val projected = row(pending, revealedOriginals = setOf(MessageId("m1")))
        Assert.assertEquals(
            "a pending row offers nothing, so the tap has nothing to reveal",
            UiOriginalRow.Absent,
            projected.original,
        )
    }

    @Test
    fun noSettingCanRevealSomebodyElsesOriginal() {
        val failed =
            message(
                id = "m1",
                original = "die Antwort",
                translated = null,
                translationState = Message.TRANSLATION_FAILED.toLong(),
            )
        // Every switch that has anything to say about a second half, all on, and the row is still
        // concealed: the exception is the failure gate's, and no flag combination reaches it.
        val projected =
            row(
                failed,
                facts = heldFacts(HeldSend.HoldReason.FAILED),
                settings =
                    ProjectionSettings(
                        showSecondHalf = true,
                        showConcealedOriginal = true,
                        concealOwnSecondHalf = false,
                    ),
            )
        Assert.assertTrue(
            "the strip is offered, and concealed: ${projected.original}",
            projected.original is UiOriginalRow.Concealed,
        )
        Assert.assertFalse(projected.toString().contains("die Antwort"))
    }

    /** A seam that answers one row's recorded reason and nothing else, so the gate can be asked. */
    private fun heldFacts(reason: HeldSend.HoldReason): MessageFacts =
        object : MessageFacts {
            override fun quoted(message: MessageSnapshot): MessageSnapshot? = null

            override fun crypto(message: MessageSnapshot): CryptoPhase = CryptoPhase.NONE

            override fun held(message: MessageSnapshot): HeldSend.HoldReason = reason

            override fun review(message: MessageSnapshot): UiReview? = null

            override fun transfer(message: MessageSnapshot): UiTransferState = UiTransferState.None

            override fun canTranslateNow(message: MessageSnapshot): Boolean = true

            override fun smear(message: MessageSnapshot): ImageBitmap? = null

            override fun english(message: MessageSnapshot): EnglishInHand? = null

            override fun strippedBody(message: MessageSnapshot): String? = message.bodies.original

            override fun replyFallback(message: MessageSnapshot): String? = null
        }

    // ---- fixtures ---------------------------------------------------------------------------------

    private fun row(
        message: MessageSnapshot,
        facts: MessageFacts = MessageFacts.NONE,
        settings: ProjectionSettings = ProjectionSettings.SHIPPED,
        conversation: ConversationFacts = conversation(),
        revealed: Set<MessageId> = emptySet(),
        revealedOriginals: Set<MessageId> = emptySet(),
    ): UiMessage =
        rows(
                message,
                facts = facts,
                settings = settings,
                conversation = conversation,
                revealed = revealed,
                revealedOriginals = revealedOriginals,
            )
            .single()

    private fun rows(
        vararg messages: MessageSnapshot,
        facts: MessageFacts = MessageFacts.NONE,
        settings: ProjectionSettings = ProjectionSettings.SHIPPED,
        conversation: ConversationFacts = conversation(),
        revealed: Set<MessageId> = emptySet(),
        revealedOriginals: Set<MessageId> = emptySet(),
    ): List<UiMessage> =
        MessageProjection.of(messages.toList(), facts, settings, conversation, revealed, revealedOriginals)

    private fun placeholderShape(row: UiMessage): PlaceholderShape =
        ((row.top as UiBody.Concealed).concealment as UiConcealment.Placeholder).shape

    /** The cell a transfer row carries, or a failed assertion rather than an NPE. */
    private fun attachmentOf(row: UiMessage): UiAttachment =
        row.attachment ?: throw AssertionError("the row drew no attachment cell: $row")

    private fun conversation(
        interpreter: Interpreter = Interpreter.of("fi", "en"),
        appLanguage: String = "fi",
        studyLanguage: String? = "en",
        conversationLanguage: String? = "en",
        conversationName: String? = "Alice",
        mergeWindow: Long = 90_000L,
        group: Boolean = false,
        forceNames: Boolean = false,
    ): ConversationFacts =
        ConversationFacts(
            interpreter,
            appLanguage,
            studyLanguage,
            conversationLanguage,
            conversationName,
            mergeWindow,
            group,
            forceNames,
        )

    /**
     * A mutable stand-in for the host, so a cell spells only the facts it is about. The properties carry
     * distinct names for the reason this whole holder exists: a fact and the question it answers must not
     * be mistakable for one another.
     */
    private class Facts : MessageFacts {
        var quotedRow: MessageSnapshot? = null
        var cryptoPhase: CryptoPhase = CryptoPhase.NONE
        var holdReason: HeldSend.HoldReason? = null
        var reviewCards: UiReview? = null
        var transferState: UiTransferState = UiTransferState.None
        var attachmentInHand: AttachmentInHand? = null
        var englishInHand: EnglishInHand? = null
        var tapOffered: Boolean = false
        var smearImage: ImageBitmap? = null

        /**
         * The fallback-free body per row id, from a host that could find the declared reply span. A row
         * this map does not name answers the composed body, the same honest nothing the seam's own
         * `NONE` gives.
         */
        var strippedBodies: Map<String, String> = emptyMap()

        /**
         * Every question the projection asked, as `fact:rowId`, in the order it asked them. The seam is
         * asked *about a row*, and a cell that only checks the answer cannot tell the row a reply borrowed
         * its pixels from - which is what this records.
         */
        val asked = mutableListOf<String>()

        /** The rows one fact was asked about, in order. */
        fun askedAbout(fact: String): List<String> =
            asked.filter { it.startsWith("$fact:") }.map { it.substringAfter(':') }

        override fun quoted(message: MessageSnapshot): MessageSnapshot? {
            asked += "quoted:${message.id}"
            return quotedRow
        }

        override fun crypto(message: MessageSnapshot): CryptoPhase {
            asked += "crypto:${message.id}"
            return cryptoPhase
        }

        override fun held(message: MessageSnapshot): HeldSend.HoldReason? {
            asked += "held:${message.id}"
            return holdReason
        }

        override fun review(message: MessageSnapshot): UiReview? {
            asked += "review:${message.id}"
            return reviewCards
        }

        override fun transfer(message: MessageSnapshot): UiTransferState {
            asked += "transfer:${message.id}"
            return transferState
        }

        override fun attachment(message: MessageSnapshot): AttachmentInHand? {
            asked += "attachment:${message.id}"
            return attachmentInHand
        }

        override fun english(message: MessageSnapshot): EnglishInHand? {
            asked += "english:${message.id}"
            return englishInHand
        }

        override fun strippedBody(message: MessageSnapshot): String? {
            asked += "strippedBody:${message.id}"
            return strippedBodies[message.id] ?: message.bodies.original
        }

        /**
         * The fallback a host found behind the reply's declared span, per row id. `null` for a row the
         * map does not name is the same answer the seam's own `NONE` gives - "not a reply at all" -
         * which is the distinction `MessageProjection.quote` needs to tell no quote from a targetless
         * one; a named row with `""` is a reply whose span could not be placed.
         */
        var replyFallbacks: Map<String, String?> = emptyMap()

        override fun replyFallback(message: MessageSnapshot): String? {
            asked += "replyFallback:${message.id}"
            return replyFallbacks[message.id]
        }

        override fun canTranslateNow(message: MessageSnapshot): Boolean {
            asked += "canTranslateNow:${message.id}"
            return tapOffered
        }

        override fun smear(message: MessageSnapshot): ImageBitmap? {
            asked += "smear:${message.id}"
            return smearImage
        }
    }

    private fun message(
        id: String,
        conversationId: String = "c1",
        status: Long = Message.STATUS_RECEIVED.toLong(),
        type: Long = Message.TYPE_TEXT.toLong(),
        encryption: Long = Message.ENCRYPTION_NONE.toLong(),
        translationState: Long = Message.TRANSLATION_NONE.toLong(),
        translationLang: String? = null,
        original: String? = null,
        translated: String? = null,
        reactions: String? = null,
        timeSent: Long = 0L,
        relativeFilePath: String? = null,
        fileParams: String? = null,
        oobUri: String? = null,
        errorMsg: String? = null,
        fileDeleted: Long = 0L,
    ): MessageSnapshot =
        MessageSnapshot(
            id = id,
            conversationId = conversationId,
            timeSent = timeSent,
            counterpart = "bob@example.org",
            trueCounterpart = null,
            type = type,
            status = status,
            encryption = encryption,
            delivery = 0L,
            read = 0L,
            deleted = 0L,
            fileDeleted = fileDeleted,
            markable = 0L,
            oob = 0L,
            carbon = 0L,
            retractId = null,
            edited = null,
            serverMsgId = null,
            remoteMsgId = null,
            axolotlFingerprint = null,
            occupantId = null,
            relativeFilePath = relativeFilePath,
            fileParams = fileParams,
            oobUri = oobUri,
            errorMsg = errorMsg,
            bodyLanguage = null,
            reactions = reactions,
            readByMarkers = null,
            translationState = translationState,
            translationLang = translationLang,
            timeReceived = null,
            subject = null,
            expireAt = 0L,
            ephemeralTimer = 0L,
            notificationDismissed = null,
            bodies = ConversationBodies(translated = translated, original = original),
        )

    private companion object {
        val BOOLEANS = booleanArrayOf(true, false)

        /** Two of the owner's and one of somebody else's: a group's count is its size, not a key. */
        val REACTIONS =
            """
            [
              {"reaction":"👍","received":false},
              {"reaction":"👍","received":true},
              {"reaction":"🎉","received":true}
            ]
            """
                .trimIndent()

        /** A custom emoji: its group key carries a cid, and the chip's image has no field here. */
        val CUSTOM_REACTION =
            """
            [
              {"reaction":"blobcat","cid":"bafkqaaa","received":true}
            ]
            """
                .trimIndent()
    }
}
