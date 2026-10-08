package uk.xa0.tulkki.ui.projection

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.messages.ConversationBodies
import uk.xa0.tulkki.data.messages.ConversationSnapshot
import uk.xa0.tulkki.data.messages.MessageSnapshot
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.translation.Interpreter
import uk.xa0.tulkki.ui.R

/**
 * `ui-8`'s cells over the conversation-list preview, docs/MIGRATION.md "Design: the Compose UI" §2.2's
 * `UiPreview` and §2.2.1 #6-#9.
 *
 * <p>What they hold, in order: **the branch order** - `UIHelper.getMessagePreview`'s, with the list's
 * encryption-before-file/image resolution of §2.2.1 #9's third contradiction; the five encryption kinds
 * and the three call kinds; the mute's precedence; the transfer lines with their description and
 * progress; a file's own description; and the body, which is `Covered` unless there is something to
 * show - the §3.5 rule that an interpreter that is off draws ordinary one-liners and no cover at all.
 *
 * <p>Every branch is reachable here because [PreviewWords] is injected: the cells record which resource
 * and which arguments the projection chose, which is the decision, while a device supplies the words.
 */
class MessagePreviewTest {

    /**
     * §2.2.1 #9's contradiction, resolved for the list: the preview tests encryption before file/image,
     * so a photo whose payload never decrypted says that rather than describing an image nobody read.
     * The bubble keeps the opposite order, and that is ui-9's.
     */
    @Test
    fun encryptionWinsOverTheFileItFailedToDecrypt() {
        val photo = snapshot(
            type = Message.TYPE_IMAGE.toLong(),
            encryption = Message.ENCRYPTION_AXOLOTL_FAILED.toLong(),
            relativeFilePath = "files/photo.jpg",
        )
        Assert.assertEquals(
            "an OMEMO-failed image is an encryption preview on the list",
            UiPreview.Encryption(EncryptionKind.OMEMO_DECRYPTION_FAILED),
            MessagePreview.of(photo, "Mikko", ON, words),
        )
    }

    /** The five kinds §2.2.1 #7 defines, one per `ENCRYPTION_*` the preview branches on. */
    @Test
    fun theFiveEncryptionKindsAreTheTreesOwn() {
        val cases =
            listOf(
                Message.ENCRYPTION_PGP to EncryptionKind.PGP,
                Message.ENCRYPTION_OTR to EncryptionKind.OTR,
                Message.ENCRYPTION_DECRYPTION_FAILED to EncryptionKind.PGP_DECRYPTION_FAILED,
                Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE to EncryptionKind.OMEMO_NOT_FOR_THIS_DEVICE,
                Message.ENCRYPTION_AXOLOTL_FAILED to EncryptionKind.OMEMO_DECRYPTION_FAILED,
            )
        for ((constant, kind) in cases) {
            Assert.assertEquals(
                "ENCRYPTION_$constant is $kind",
                UiPreview.Encryption(kind),
                MessagePreview.of(snapshot(encryption = constant.toLong()), "Mikko", ON, words),
            )
        }
        Assert.assertFalse(
            "nothing encrypted is not an encryption preview",
            MessagePreview.of(snapshot(original = "Hei"), "Mikko", ON, words) is UiPreview.Encryption,
        )
    }

    /** §2.2.1 #8: the three kinds, from the row's type and the status body the tree parses. */
    @Test
    fun aCallPreviewsAsItsKind() {
        val missed = snapshot(type = Message.TYPE_RTP_SESSION.toLong(), original = "false:0")
        val incoming = snapshot(type = Message.TYPE_RTP_SESSION.toLong(), original = "true:42")
        val outgoing = snapshot(type = Message.TYPE_RTP_SESSION.toLong(), original = "true:42", status = Message.STATUS_SEND.toLong())
        Assert.assertEquals(UiPreview.Call(CallKind.MISSED), MessagePreview.of(missed, "Mikko", ON, words))
        Assert.assertEquals(UiPreview.Call(CallKind.INCOMING), MessagePreview.of(incoming, "Mikko", ON, words))
        Assert.assertEquals(UiPreview.Call(CallKind.OUTGOING), MessagePreview.of(outgoing, "Mikko", ON, words))
    }

    /** The tree's first branch: a muted group member's line is the mute's, whatever the body says. */
    @Test
    fun aMutedRowSaysSoBeforeAnythingElse() {
        val muted = snapshot(original = "Hei, mitä kuuluu?")
        Assert.assertEquals(
            UiPreview.Visible("s${R.string.tulkki_preview_muted}"),
            MessagePreview.of(muted, "Mikko", ON, words, facts(muted = true)),
        )
        Assert.assertNotEquals(
            "and the body is not the line when it is",
            MessagePreview.of(muted, "Mikko", ON, words),
            MessagePreview.of(muted, "Mikko", ON, words, facts(muted = true)),
        )
    }

    /**
     * A transfer's own line, with the file's description inside it and the progress beside it - the
     * tree's `receiving_x_file`, `x_file_offered_for_download`, `offering_x_file`, `sending_x_file` and
     * `file_transmission_cancelled`, in the order the status decides.
     * <p>Every line here carries the attachment's own mark too, because the row draws the mark beside
     * whatever the transfer is doing: a downloading image is still an image.
     */
    @Test
    fun aTransferLineCarriesTheFileAndTheProgress() {
        val video = snapshot(type = Message.TYPE_FILE.toLong(), relativeFilePath = "files/clip.mp4")
        val description = "s${R.string.video}"
        val mark = R.drawable.ic_movie_48dp
        Assert.assertEquals(
            UiPreview.Visible("s${R.string.receiving_x_file}:$description|42", icon = mark),
            MessagePreview.of(video, "Mikko", ON, words, facts(transfer = UiTransferState.Downloading(42, null))),
        )
        Assert.assertEquals(
            UiPreview.Visible("s${R.string.x_file_offered_for_download}:$description", icon = mark),
            MessagePreview.of(video, "Mikko", ON, words, facts(transfer = UiTransferState.Offered(100))),
        )
        Assert.assertEquals(
            UiPreview.Visible("s${R.string.sending_x_file}:$description", icon = mark),
            MessagePreview.of(video, "Mikko", ON, words, facts(transfer = UiTransferState.Uploading(10))),
        )
        Assert.assertEquals(
            "an offered row is an offer, not a send",
            UiPreview.Visible("s${R.string.offering_x_file}:$description", icon = mark),
            MessagePreview.of(
                snapshot(type = Message.TYPE_FILE.toLong(), relativeFilePath = "files/clip.mp4", status = Message.STATUS_OFFERED.toLong()),
                "Mikko",
                ON,
                words,
                facts(transfer = UiTransferState.Uploading(10)),
            ),
        )
        Assert.assertEquals(
            UiPreview.Visible("s${R.string.file_transmission_cancelled}", icon = mark),
            MessagePreview.of(video, "Mikko", ON, words, facts(transfer = UiTransferState.Cancelled)),
        )
        Assert.assertEquals(
            "a failed transfer says so and never names a cause it cannot derive",
            UiPreview.Visible("s${R.string.file_transmission_failed}", icon = mark),
            MessagePreview.of(video, "Mikko", ON, words, facts(transfer = UiTransferState.Failed)),
        )
        Assert.assertEquals(
            "a transfer with nothing to say and a finished one both fall to the file's own line",
            UiPreview.Visible(description, icon = mark),
            MessagePreview.of(video, "Mikko", ON, words, facts(transfer = UiTransferState.Ready("file://clip.mp4"))),
        )
    }

    /** A file's line is its description - the tree's MIME chain - and never its name (§2.2.1 #6). */
    @Test
    fun aFileRowPreviewsAsItsDescription() {
        val photo = snapshot(type = Message.TYPE_IMAGE.toLong(), relativeFilePath = "files/photo.jpg")
        Assert.assertEquals(
            UiPreview.Visible("s${R.string.image}", icon = R.drawable.ic_image_48dp),
            MessagePreview.of(photo, "Mikko", ON, words),
        )
        val text = snapshot(type = Message.TYPE_FILE.toLong(), relativeFilePath = "files/notes.txt")
        Assert.assertEquals(
            UiPreview.Visible("s${R.string.plain_text_document}", icon = R.drawable.ic_description_48dp),
            MessagePreview.of(text, "Mikko", ON, words),
        )
    }

    /**
     * The body's own line, and the §3.5 rule that the interpreter's two modes differ: off, a preview is
     * the message as it arrived; on, a body that needed translating and did not get one is covered, and
     * a translation that exists is the line. An empty row has nothing to draw at all.
     */
    @Test
    fun theBodyIsCoveredUnlessThereIsSomethingToShow() {
        val finnish = snapshot(original = "Hei, mitä kuuluu?")
        Assert.assertEquals(
            "a received body with the interpreter on, and no translation: covered",
            UiPreview.Covered,
            MessagePreview.of(finnish, "Mikko", ON, words),
        )
        Assert.assertEquals(
            "off, a plain client draws the message as it arrived",
            UiPreview.Visible("Hei, mitä kuuluu?"),
            MessagePreview.of(finnish, "Mikko", OFF, words),
        )
        Assert.assertEquals(
            "a translation is the line",
            UiPreview.Visible("Hi, how are you?"),
            MessagePreview.of(
                snapshot(
                    original = "Hei, mitä kuuluu?",
                    translated = "Hi, how are you?",
                    translationState = Message.TRANSLATION_DONE.toLong(),
                ),
                "Mikko",
                ON,
                words,
            ),
        )
        Assert.assertEquals(
            "an empty row draws nothing",
            UiPreview.Absent,
            MessagePreview.of(snapshot(original = ""), "Mikko", OFF, words),
        )
    }

    /** The recording words seam: the resource and its arguments, as the projection chose them. */
    private val words = PreviewWords { id, args ->
        if (args.isEmpty()) "s$id" else "s$id:" + args.joinToString("|")
    }

    /** A live state the cell is not about: [PerProcess.NONE], or one answer overridden. */
    private fun facts(
        muted: Boolean = false,
        moderated: Boolean = false,
        transfer: UiTransferState = UiTransferState.None,
    ): PerProcess =
        object : PerProcess {
            override fun mutedOccupant(message: MessageSnapshot): Boolean = muted

            override fun moderated(message: MessageSnapshot): Boolean = moderated

            override fun transfer(message: MessageSnapshot): UiTransferState = transfer

            override fun unreadCount(conversation: ConversationSnapshot): Int = 0

            override fun withSelf(conversation: ConversationSnapshot): Boolean = false

            override fun ongoingCall(conversation: ConversationSnapshot): Boolean = false

            override fun presence(conversation: ConversationSnapshot): UiPresence = UiPresence.UNKNOWN

            override fun notification(conversation: ConversationSnapshot): UiNotification =
                UiNotification.NONE

            override fun accountLine(conversation: ConversationSnapshot): String? = null

            override fun roomName(conversation: ConversationSnapshot): RoomName? = null
        }

    private companion object {
        /** The interpreter on, and off: two different languages, and the shipped pair equal to itself. */
        val ON: Interpreter = Interpreter.of("fi", "en")

        val OFF: Interpreter = Interpreter.of("fi", "fi")
    }

    /** One row, with only the fields a preview branch reads spelled by the cell. */
    private fun snapshot(
        id: String = "m1",
        type: Long? = Message.TYPE_TEXT.toLong(),
        status: Long? = Message.STATUS_RECEIVED.toLong(),
        encryption: Long? = Message.ENCRYPTION_NONE.toLong(),
        translationState: Long = Message.TRANSLATION_NONE.toLong(),
        original: String? = null,
        translated: String? = null,
        relativeFilePath: String? = null,
        oobUri: String? = null,
    ): MessageSnapshot =
        MessageSnapshot(
            id = id,
            conversationId = "c1",
            timeSent = 0L,
            counterpart = "them@example.org",
            trueCounterpart = null,
            type = type,
            status = status,
            encryption = encryption,
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
            relativeFilePath = relativeFilePath,
            fileParams = null,
            oobUri = oobUri,
            errorMsg = null,
            bodyLanguage = null,
            reactions = null,
            readByMarkers = null,
            translationState = translationState,
            translationLang = null,
            timeReceived = null,
            subject = null,
            expireAt = 0L,
            ephemeralTimer = 0L,
            notificationDismissed = null,
            bodies = ConversationBodies(translated = translated, original = original),
        )
}
