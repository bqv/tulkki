package uk.xa0.tulkki.ui.projection

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import uk.xa0.tulkki.data.messages.ConversationSnapshot
import uk.xa0.tulkki.data.messages.MessageSnapshot
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.data.model.RtpSessionStatus
import uk.xa0.tulkki.data.utils.BackupMimeType
import uk.xa0.tulkki.data.utils.MimeUtils
import uk.xa0.tulkki.translation.DisplayedBody
import uk.xa0.tulkki.translation.Interpreter
import uk.xa0.tulkki.ui.R

/**
 * The words a preview line needs, injected.
 *
 * <p>docs/MIGRATION.md "Design: the Compose UI" §2.1 gives the projector a `context` parameter, and
 * this is that parameter narrowed to the one thing the projector uses it for: the file, transfer and
 * muted labels. It is a seam rather than an Android `Context` for §7.4's own reason - "so that the JVM
 * suite still reach it" - because a `Context` cannot be built in a JVM test and every branch below
 * would then be unreachable; `PreviewWords` is a production one-liner over `Context::getString` and a
 * recording fake in the cells.
 */
fun interface PreviewWords {
    fun get(@StringRes id: Int, vararg args: Any): String
}

/**
 * The facts only the running app knows: **nothing a snapshot carries decides any of them.**
 *
 * <p>§2.1 names this parameter `perProcess` and never defines it. The preview's branches depend on live
 * state - the per-occupant MUC mute, a moderator's removal, and the transfer's own status and progress
 * (a `Transferable` is a connection object, not a column) - and the list row adds three more: the unread
 * count (the tree asks `Conversation.unreadCount(XmppConnectionService)`), whether the conversation is
 * the owner's own note-to-self (`Conversation.withSelf` is `getContact().isSelf()`, a property of the
 * contact and not of this row), and whether a call with that contact is going on right now (the call
 * manager's own state). None of the six is a column of a conversation or a message snapshot.
 *
 * <p>[NONE] is the honest answer for a caller that knows none of them: an unmuted, unmoderated row with
 * no transfer and nothing unread. It is what `:ui`'s own cells use, so a cell never has to spell a live
 * state it is not about.
 */
interface PerProcess {

    /** Whether this received row is a MUC user the owner muted. */
    fun mutedOccupant(message: MessageSnapshot): Boolean

    /** Whether a room moderator removed the row: the tree's `moderated`. */
    fun moderated(message: MessageSnapshot): Boolean

    /** The row's live transfer, or [UiTransferState.None] when it has none. */
    fun transfer(message: MessageSnapshot): UiTransferState

    /** How many of a conversation's messages the owner has not read: the list row's own badge. */
    fun unreadCount(conversation: ConversationSnapshot): Int

    /** Whether this conversation is the owner's own note-to-self. */
    fun withSelf(conversation: ConversationSnapshot): Boolean

    /** Whether a call with this conversation's contact is going on right now. */
    fun ongoingCall(conversation: ConversationSnapshot): Boolean

    /** The row's presence dot, already narrowed to the vocabulary §2.2 draws with. */
    fun presence(conversation: ConversationSnapshot): UiPresence

    /** The row's notification mark, from the mute and the call that are live and not columns. */
    fun notification(conversation: ConversationSnapshot): UiNotification

    /** The account line, or `null` when there is nothing to tell apart: `UiAccountLine`'s rule. */
    fun accountLine(conversation: ConversationSnapshot): String?

    /**
     * A room's name candidates, or `null` on a one-to-one: `Conversation.getName`'s own four sources. The
     * **order** they are tried in belongs to [ConversationProjection], not here.
     */
    fun roomName(conversation: ConversationSnapshot): RoomName?

    companion object {
        /** The answer of a caller with no live state: unmuted, unmoderated, no transfer, nothing unread. */
        val NONE: PerProcess =
            object : PerProcess {
                override fun mutedOccupant(message: MessageSnapshot): Boolean = false

                override fun moderated(message: MessageSnapshot): Boolean = false

                override fun transfer(message: MessageSnapshot): UiTransferState = UiTransferState.None

                override fun unreadCount(conversation: ConversationSnapshot): Int = 0

                override fun withSelf(conversation: ConversationSnapshot): Boolean = false

                override fun ongoingCall(conversation: ConversationSnapshot): Boolean = false

                override fun presence(conversation: ConversationSnapshot): UiPresence = UiPresence.UNKNOWN

                override fun notification(conversation: ConversationSnapshot): UiNotification =
                    UiNotification.NONE

                override fun accountLine(conversation: ConversationSnapshot): String? = null

                override fun roomName(conversation: ConversationSnapshot): RoomName? = null
            }
    }
}

/**
 * The conversation-list preview line, `Design: the Compose UI` §2.2's `UiPreview` and §2.2.1 #9's
 * "the branch order that decides them is `UIHelper.getMessagePreview`'s".
 *
 * <p>**The order is the tree's own, with one decision this row owns.** The list's renderer tests the
 * transferable first, then encryption, then file/image, then the call, then the body
 * (`ui/src/main/java/uk/xa0/tulkki/ui/utils/UIHelper.java:216-283`); the bubble tests file/image before
 * encryption, so an OMEMO-failed image is an `Encryption` preview in one and a media preview in the
 * other. §2.2.1 #9 leaves that contradiction "for the lane", and this is the list's projection, so the
 * list's order stands: **a message that did not decrypt is never described as an image.** The bubble
 * keeps its own order, and that contrast is `ui-9`'s to record.
 *
 * <p>**The three labels the tree produces as one localised string ride [UiPreview.Visible].**
 * §2.2.1 #6 marks `UiPreview.File(kind, name)` deferred - the preview produces one `getString` line and
 * never a kind, and `name` has no producer on this path at all - so the file, transfer and mute lines are
 * the drawn text itself and the `File` case waits for the bubble's attachment cell, which is where both
 * payloads exist.
 *
 * <p>**What is not here, named rather than guessed.** The markup flattening the tree does before
 * returning a body (`getSpannableBody`, the `QuoteSpan` sweep) needs the *entity's* spans and has no
 * `:ui` twin yet; the body below is the stored text. The reply-fallback strip **is** here: the body
 * comes from [MessageFacts.strippedBody] - the same seam the bubbles read - so a list row whose newest
 * message is a reply draws the reply's own words, not the original it quotes. The webxdc branch's
 * filename is not in a snapshot either (the tree's single filename read, §2.2.1 #6), so it falls to the
 * MIME string like the tree's own last branch. `UiTransferState.Failed` is reasonless (§2.2.1 #5 keeps
 * the typed `TransferFailure` deferred), so the tree's `file_transmission_failed` line is drawn from the
 * outcome alone and never from a cause.
 */
object MessagePreview {

    /**
     * One row's preview: the line the conversation list draws for its newest message.
     *
     * @param conversationName the name the interface shows for the conversation, which
     *     [DisplayedBody.needsTranslation] consults for the bare-name rule; `null` when the caller has
     *     none, in which case only the ping shape can tell it the body has no language.
     * @param facts the row-level live facts, [MessageFacts.NONE] when the caller knows none of them:
     *     [MessageFacts.strippedBody] is the reply-fallback strip this preview draws.
     */
    fun of(
        message: MessageSnapshot,
        conversationName: String?,
        interpreter: Interpreter,
        words: PreviewWords,
        perProcess: PerProcess = PerProcess.NONE,
        facts: MessageFacts = MessageFacts.NONE,
    ): UiPreview {
        val preview = line(message, conversationName, interpreter, words, perProcess, facts)
        // The deleted row drew an attachment's own mark beside whatever line the row had - a downloading
        // image was still an image - so the icon is attached here rather than at each branch below, and the
        // words and the mark can never disagree about what kind of file this is.
        if (!isFileOrImage(message) || preview !is UiPreview.Visible || preview.icon != null) {
            return preview
        }
        return UiPreview.Visible(preview.text, preview.draft, attachmentIcon(message))
    }

    /** The line itself, before the attachment's mark is put beside it. */
    private fun line(
        message: MessageSnapshot,
        conversationName: String?,
        interpreter: Interpreter,
        words: PreviewWords,
        perProcess: PerProcess,
        facts: MessageFacts,
    ): UiPreview {
        if (perProcess.mutedOccupant(message)) {
            return UiPreview.Visible(words.get(R.string.tulkki_preview_muted))
        }
        val original = message.bodies.original.orEmpty()
        if (Message.DELETED_MESSAGE_BODY == original) {
            return UiPreview.Visible(words.get(R.string.message_has_disappeared))
        }
        val moderated = perProcess.moderated(message)
        if (!moderated) {
            transferLine(perProcess.transfer(message), message, words)?.let { return it }
        }
        encryptionOf(message)?.let { return UiPreview.Encryption(it) }
        if (!moderated && isFileOrImage(message)) {
            // The mark is `of`'s to add, so this is only the words.
            return UiPreview.Visible(words.get(fileDescription(message)))
        }
        callOf(message)?.let { return UiPreview.Call(it) }
        return body(message, conversationName, interpreter, facts)
    }

    /**
     * The 18sp message-type mark the deleted row drew for an attachment: `MediaAdapter.getImageDrawable`'s
     * chain, ported over the snapshot's own scalars rather than over the `Attachment` the tree built (which
     * needs the entity, and the row has none).
     *
     * <p>The MIME is [mimeType]'s derivation and nothing else, so the words and the mark can never disagree
     * about what kind of file this is. `LOCATION` and `RECORDING` are `Attachment`'s two extra kinds: a
     * `geo:` body is the first here, and the second - a voice recording - is not distinguishable from any
     * other audio row in a snapshot, so it draws the audio mark.
     */
    @DrawableRes
    private fun attachmentIcon(message: MessageSnapshot): Int {
        val mime = mimeType(message) ?: return R.drawable.ic_help_center_48dp
        return when {
            mime == "audio/x-m4b" -> R.drawable.ic_play_lesson_48dp
            mime.startsWith("audio/") -> R.drawable.ic_headphones_48dp
            mime == "text/calendar" || mime == "text/x-vcalendar" -> R.drawable.ic_event_48dp
            mime == "text/x-vcard" -> R.drawable.ic_person_48dp
            mime == "application/vnd.android.package-archive" -> R.drawable.ic_adb_48dp
            ARCHIVE_MIMES.contains(mime) -> R.drawable.ic_archive_48dp
            mime == "application/epub+zip" || mime == "application/vnd.amazon.mobi8-ebook" ->
                R.drawable.ic_book_48dp
            mime == BackupMimeType.MIME_TYPE -> R.drawable.ic_backup_48dp
            MimeUtils.DOCUMENT_MIMES.contains(mime) -> R.drawable.ic_description_48dp
            MimeUtils.SPREAD_SHEET_MIMES.contains(mime) -> R.drawable.ic_table_48dp
            MimeUtils.SLIDE_SHOW_MIMES.contains(mime) -> R.drawable.ic_slideshow_48dp
            mime == "application/gpx+xml" -> R.drawable.ic_tour_48dp
            mime.startsWith("image/") -> R.drawable.ic_image_48dp
            mime.startsWith("video/") -> R.drawable.ic_movie_48dp
            CODE_MIMES.contains(mime) -> R.drawable.ic_code_48dp
            mime == "message/rfc822" -> R.drawable.ic_email_48dp
            mime == "application/webxdc+zip" -> R.drawable.toys_and_games_24dp
            else -> R.drawable.ic_help_center_48dp
        }
    }

    /** `MediaAdapter`'s two private lists, which the tree never published: archive and source archives. */
    private val ARCHIVE_MIMES =
        listOf(
            "application/x-7z-compressed",
            "application/zip",
            "application/vnd.rar",
            "application/x-tar",
            "application/gzip",
            "application/x-bzip2",
        )

    private val CODE_MIMES =
        listOf(
            "application/json",
            "application/xml",
            "text/xml",
            "text/x-c",
            "text/x-java",
            "text/x-script.python",
            "text/x-shellscript",
        )

    /**
     * One attachment's transfer line, or `null` when there is nothing to say about a transfer.
     *
     * <p>The two `null`s are the tree's own: no transferable at all, and a completed one - the tree's
     * `default` branch returns an empty line, and the file branch below is what draws a downloaded
     * attachment. `Ready` is that completed case here, because a `Transferable` is gone once the file
     * is on the phone; the tree's `getTransferable()` then answers nothing and file/image decides.
     */
    private fun transferLine(
        transfer: UiTransferState,
        message: MessageSnapshot,
        words: PreviewWords,
    ): UiPreview? =
        when (transfer) {
            is UiTransferState.None,
            is UiTransferState.Ready -> null
            is UiTransferState.Checking ->
                UiPreview.Visible(words.get(R.string.checking_x, words.get(fileDescription(message))))
            is UiTransferState.Downloading ->
                UiPreview.Visible(
                    words.get(R.string.receiving_x_file, words.get(fileDescription(message)), transfer.progress)
                )
            is UiTransferState.Offered ->
                UiPreview.Visible(
                    words.get(R.string.x_file_offered_for_download, words.get(fileDescription(message)))
                )
            is UiTransferState.Uploading ->
                if (message.status == Message.STATUS_OFFERED.toLong()) {
                    UiPreview.Visible(words.get(R.string.offering_x_file, words.get(fileDescription(message))))
                } else {
                    UiPreview.Visible(words.get(R.string.sending_x_file, words.get(fileDescription(message))))
                }
            is UiTransferState.Cancelled ->
                UiPreview.Visible(words.get(R.string.file_transmission_cancelled))
            is UiTransferState.Failed ->
                UiPreview.Visible(words.get(R.string.file_transmission_failed))
        }

    /** §2.2.1 #7's five reachable kinds, one per `Message.ENCRYPTION_*` the preview branches on. */
    private fun encryptionOf(message: MessageSnapshot): EncryptionKind? =
        when (message.encryption) {
            Message.ENCRYPTION_PGP.toLong() -> EncryptionKind.PGP
            Message.ENCRYPTION_OTR.toLong() -> EncryptionKind.OTR
            Message.ENCRYPTION_DECRYPTION_FAILED.toLong() -> EncryptionKind.PGP_DECRYPTION_FAILED
            Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE.toLong() -> EncryptionKind.OMEMO_NOT_FOR_THIS_DEVICE
            Message.ENCRYPTION_AXOLOTL_FAILED.toLong() -> EncryptionKind.OMEMO_DECRYPTION_FAILED
            else -> null
        }

    /** §2.2.1 #8's three kinds, from `TYPE_RTP_SESSION` and the status body the tree parses. */
    private fun callOf(message: MessageSnapshot): CallKind? {
        if (message.type != Message.TYPE_RTP_SESSION.toLong()) {
            return null
        }
        val status = RtpSessionStatus.of(message.bodies.original.orEmpty())
        val received = message.status == Message.STATUS_RECEIVED.toLong()
        return when {
            !status.successful && received -> CallKind.MISSED
            received -> CallKind.INCOMING
            else -> CallKind.OUTGOING
        }
    }

    /** The tree's own test: the three file-bearing message types, and nothing about the body. */
    private fun isFileOrImage(message: MessageSnapshot): Boolean =
        message.type == Message.TYPE_FILE.toLong() ||
            message.type == Message.TYPE_IMAGE.toLong() ||
            message.type == Message.TYPE_PRIVATE_FILE.toLong()

    /** `UIHelper.getFileDescriptionString`'s chain, ported: the MIME decides the word, never the name. */
    @StringRes
    private fun fileDescription(message: MessageSnapshot): Int {
        val mime = mimeType(message) ?: return R.string.file
        return when {
            MimeUtils.AMBIGUOUS_CONTAINER_FORMATS.contains(mime) -> R.string.multimedia_file
            mime == "audio/x-m4b" -> R.string.audiobook
            mime.startsWith("audio/") -> R.string.audio
            mime.startsWith("video/") -> R.string.video
            mime == "image/gif" -> R.string.gif
            mime == "image/svg+xml" -> R.string.vector_graphic
            mime.startsWith("image/") || message.type == Message.TYPE_IMAGE.toLong() -> R.string.image
            mime.contains("pdf") -> R.string.pdf_document
            MimeUtils.WORD_DOCUMENT_MIMES.contains(mime) -> R.string.word_document
            mime == "application/vnd.android.package-archive" -> R.string.apk
            mime == uk.xa0.tulkki.data.utils.BackupMimeType.MIME_TYPE -> R.string.backup_file_description
            mime.contains("vcard") -> R.string.vcard
            mime == "text/x-vcalendar" || mime == "text/calendar" -> R.string.event
            mime == "application/epub+zip" || mime == "application/vnd.amazon.mobi8-ebook" -> R.string.ebook
            mime == "application/gpx+xml" -> R.string.gpx_track
            mime == "application/webxdc+zip" -> R.string.file
            mime == "text/plain" -> R.string.plain_text_document
            // The tree's last branch returns the MIME string itself, and there is no string for it.
            else -> R.string.file
        }
    }

    /**
     * `Message.getMimeType`'s derivation, over the snapshot's own scalars: the extension of the stored
     * path, else of the out-of-band address, else of the body's first line.
     */
    private fun mimeType(message: MessageSnapshot): String? {
        val path = message.relativeFilePath
        if (path != null) {
            return MimeUtils.guessMimeTypeFromExtension(MimeUtils.extractRelevantExtension(path))
        }
        val oob = message.oobUri ?: message.bodies.original?.split("\n")?.firstOrNull()
        val address = oob ?: return null
        return MimeUtils.guessMimeTypeFromExtension(MimeUtils.extractRelevantExtension(address))
    }

    /**
     * The body's own line: the translation when there is one, the cover when none could be had, and the
     * seam's fallback-free body underneath either.
     *
     * <p>The two body reads are deliberately different. Whether a translation was *needed* is a question
     * about the message as it arrived, so it keeps the composed `bodies.original`; what may be *drawn* is
     * [MessageFacts.strippedBody], because a reply's stored body is the quote it answers glued to its own
     * words, and the quote is somebody else's original - the same reason `MessageProjection` builds its
     * halves and its quote from the seam. With no live row the seam answers the composed body, so a
     * caller that cannot read the declared span keeps today's reading rather than losing the line.
     */
    private fun body(
        message: MessageSnapshot,
        conversationName: String?,
        interpreter: Interpreter,
        facts: MessageFacts,
    ): UiPreview {
        val composed = message.bodies.original.orEmpty()
        // An unset status reads as received: the tree's own default for a row the service has not
        // classified, and the only value that can ask for a translation at all.
        val status = message.status?.toInt() ?: Message.STATUS_RECEIVED
        val needed =
            DisplayedBody.needsTranslation(
                status,
                composed,
                conversationName,
                interpreter,
            )
        val displayed =
            DisplayedBody.of(
                facts.strippedBody(message),
                message.bodies.translated,
                message.translationState.toInt(),
                needed,
                interpreter,
            )
        if (displayed.isBlurred()) {
            return UiPreview.Covered
        }
        val line = displayed.text().trim()
        return if (line.isEmpty()) UiPreview.Absent else UiPreview.Visible(line)
    }
}
