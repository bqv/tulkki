package uk.xa0.tulkki.ui.utils

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Pair
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.Nullable
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.color.MaterialColors
import com.google.common.base.Strings
import com.google.common.collect.Lists
import com.google.common.primitives.Ints
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Calendar
import java.util.Date
import java.util.Locale
import uk.xa0.tulkki.data.PreviewIcons
import uk.xa0.tulkki.data.model.Call
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Conversational
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.data.model.MucOptions
import uk.xa0.tulkki.data.model.Presence
import uk.xa0.tulkki.data.model.Reaction
import uk.xa0.tulkki.data.model.RtpSessionStatus
import uk.xa0.tulkki.data.utils.BackupMimeType
import uk.xa0.tulkki.data.utils.DisplayNames
import uk.xa0.tulkki.data.utils.MessageUtils
import uk.xa0.tulkki.data.utils.MimeUtils
import uk.xa0.tulkki.data.utils.QuoteHelper
import uk.xa0.tulkki.translation.ConversationName
import uk.xa0.tulkki.libs.Transferable
import uk.xa0.tulkki.translation.DisplayedBody
import uk.xa0.tulkki.translation.Interpreter
import uk.xa0.tulkki.translation.TranslationSettings
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.util.MyLinkify
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.services.XmppConnectionService

object UIHelper {

    private val LOCATION_QUESTIONS = listOf(
        "where are you", // en
        "where are you now", // en
        "where are you right now", // en
        "whats your 20", // en
        "what is your 20", // en
        "what's your 20", // en
        "whats your twenty", // en
        "what is your twenty", // en
        "what's your twenty", // en
        "wo bist du", // de
        "wo bist du jetzt", // de
        "wo bist du gerade", // de
        "wo seid ihr", // de
        "wo seid ihr jetzt", // de
        "wo seid ihr gerade", // de
        "dónde estás", // es
        "donde estas" // es
    )

    private val PUNCTIONATION = listOf('.', ',', '?', '!', ';', ':')

    private const val SHORT_DATE_FLAGS =
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR or DateUtils.FORMAT_ABBREV_ALL
    private const val FULL_DATE_FLAGS =
        DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL or DateUtils.FORMAT_SHOW_DATE

    @JvmStatic
    fun readableTimeDifference(context: Context, time: Long, allowRelative: Boolean): String {
        return readableTimeDifference(context, time, false, allowRelative)
    }

    @JvmStatic
    fun readableTimeDifferenceFull(context: Context, time: Long, allowRelative: Boolean): String {
        return readableTimeDifference(context, time, true, allowRelative)
    }

    private fun readableTimeDifference(
        context: Context,
        time: Long,
        fullDate: Boolean,
        allowRelative: Boolean
    ): String {
        if (time == 0L) {
            return context.getString(R.string.just_now)
        }
        val date = Date(time)
        val difference = (System.currentTimeMillis() - time) / 1000
        return if (difference < 60 && allowRelative) {
            context.getString(R.string.just_now)
        } else if (difference < 60 * 2 && allowRelative) {
            context.getString(R.string.minute_ago)
        } else if (difference < 60 * 15 && allowRelative) {
            context.getString(R.string.minutes_ago, Math.round(difference / 60.0))
        } else if (today(date)) {
            val df = DateFormat.getTimeFormat(context)
            df.format(date)
        } else {
            if (fullDate) {
                DateUtils.formatDateTime(context, date.time, FULL_DATE_FLAGS)
            } else {
                DateUtils.formatDateTime(context, date.time, SHORT_DATE_FLAGS)
            }
        }
    }

    private fun today(date: Date): Boolean {
        return sameDay(date, Date(System.currentTimeMillis()))
    }

    @JvmStatic
    fun today(date: Long): Boolean {
        return sameDay(date, System.currentTimeMillis())
    }

    @JvmStatic
    fun yesterday(date: Long): Boolean {
        val cal1 = Calendar.getInstance()
        val cal2 = Calendar.getInstance()
        cal1.add(Calendar.DAY_OF_YEAR, -1)
        cal2.time = Date(date)
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
            cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    @JvmStatic
    fun sameDay(a: Long, b: Long): Boolean {
        return sameDay(Date(a), Date(b))
    }

    private fun sameDay(a: Date, b: Date): Boolean {
        val cal1 = Calendar.getInstance()
        val cal2 = Calendar.getInstance()
        cal1.time = a
        cal2.time = b
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
            cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    @JvmStatic
    fun lastseen(context: Context, active: Boolean, time: Long): String {
        val difference = (System.currentTimeMillis() - time) / 1000
        return if (active) {
            context.getString(R.string.online_right_now)
        } else if (difference < 60) {
            context.getString(R.string.last_seen_now)
        } else if (difference < 60 * 2) {
            context.getString(R.string.last_seen_min)
        } else if (difference < 60 * 60) {
            context.getString(R.string.last_seen_mins, Math.round(difference / 60.0))
        } else if (difference < 60 * 60 * 2) {
            context.getString(R.string.last_seen_hour)
        } else if (difference < 60 * 60 * 24) {
            context.getString(
                R.string.last_seen_hours, Math.round(difference / (60.0 * 60.0))
            )
        } else if (difference < 60 * 60 * 48) {
            context.getString(R.string.last_seen_day)
        } else {
            context.getString(
                R.string.last_seen_days, Math.round(difference / (60.0 * 60.0 * 24.0))
            )
        }
    }

    @JvmStatic
    fun identiconHash(name: String): Int {
        try {
            val sha1 = MessageDigest.getInstance("SHA-1")
            val digest = sha1.digest(name.toByteArray(StandardCharsets.UTF_8))
            return Ints.fromByteArray(digest)
        } catch (e: Exception) {
            return 0
        }
    }

    /** Kept as a `:ui` entry point; the rule lives in [DisplayNames]. */
    @JvmStatic
    fun getColorForName(name: String?): Int {
        return DisplayNames.getColorForName(name)
    }

    @JvmStatic
    fun getMessagePreview(
        context: XmppConnectionService,
        message: Message
    ): Pair<CharSequence, Boolean> {
        return getMessagePreview(context, message, 0)
    }

    @JvmStatic
    fun getMessagePreview(
        context: XmppConnectionService,
        message: Message,
        @ColorInt textColor: Int
    ): Pair<CharSequence, Boolean> {
        // Tulkki: `getTransferable()` answers `uk.xa0.tulkki.libs.Transferable` (2026-10-08, one
        // type for both sides); `getStatus`/`getProgress` and the `STATUS_*` labels below all come
        // from it.
        val d = message.getTransferable()
        val moderated = message.getModerated() != null
        val muted = message.getStatus() == Message.STATUS_RECEIVED &&
            (message.getConversation() as Conversational).getMode() == Conversation.MODE_MULTI &&
            context.isMucUserMuted(
                (message.getConversation() as Conversational).getAccount()?.getUuid(),
                "" + (message.getConversation() as Conversational).getJid(),
                message.getOccupantId()
            )
        if (muted) {
            return Pair<CharSequence, Boolean>("Muted", false)
        } else if (Message.DELETED_MESSAGE_BODY == message.getBody()) {
            return Pair<CharSequence, Boolean>(context.getString(R.string.message_has_disappeared), true)
        } else if (d != null && !moderated) {
            when (d.getStatus()) {
                Transferable.STATUS_CHECKING ->
                    return Pair<CharSequence, Boolean>(
                        context.getString(
                            R.string.checking_x,
                            getFileDescriptionString(context, message)
                        ),
                        true
                    )
                Transferable.STATUS_DOWNLOADING ->
                    return Pair<CharSequence, Boolean>(
                        context.getString(
                            R.string.receiving_x_file,
                            getFileDescriptionString(context, message),
                            d.getProgress()
                        ),
                        true
                    )
                Transferable.STATUS_OFFER,
                Transferable.STATUS_OFFER_CHECK_FILESIZE ->
                    return Pair<CharSequence, Boolean>(
                        context.getString(
                            R.string.x_file_offered_for_download,
                            getFileDescriptionString(context, message)
                        ),
                        true
                    )
                Transferable.STATUS_FAILED ->
                    return Pair<CharSequence, Boolean>(
                        context.getString(R.string.file_transmission_failed), true
                    )
                Transferable.STATUS_CANCELLED ->
                    return Pair<CharSequence, Boolean>(
                        context.getString(R.string.file_transmission_cancelled), true
                    )
                Transferable.STATUS_UPLOADING -> {
                    if (message.getStatus() == Message.STATUS_OFFERED) {
                        return Pair<CharSequence, Boolean>(
                            context.getString(
                                R.string.offering_x_file,
                                getFileDescriptionString(context, message)
                            ),
                            true
                        )
                    } else {
                        return Pair<CharSequence, Boolean>(
                            context.getString(
                                R.string.sending_x_file,
                                getFileDescriptionString(context, message)
                            ),
                            true
                        )
                    }
                }
                else -> return Pair<CharSequence, Boolean>("", false)
            }
        } else if (message.getEncryption() == Message.ENCRYPTION_PGP) {
            return Pair<CharSequence, Boolean>(context.getString(R.string.pgp_message), true)
        } else if (message.getEncryption() == Message.ENCRYPTION_OTR) {
            return Pair<CharSequence, Boolean>(context.getString(R.string.otr_message), true)
        } else if (message.getEncryption() == Message.ENCRYPTION_DECRYPTION_FAILED) {
            return Pair<CharSequence, Boolean>(context.getString(R.string.decryption_failed), true)
        } else if (message.getEncryption() == Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE) {
            return Pair<CharSequence, Boolean>(
                context.getString(R.string.not_encrypted_for_this_device), true
            )
        } else if (message.getEncryption() == Message.ENCRYPTION_AXOLOTL_FAILED) {
            return Pair<CharSequence, Boolean>(
                context.getString(R.string.omemo_decryption_failed), true
            )
        } else if (message.isFileOrImage() && !moderated) {
            return Pair<CharSequence, Boolean>(getFileDescriptionString(context, message), true)
        } else if (message.getType() == Message.TYPE_RTP_SESSION) {
            val rtpSessionStatus = RtpSessionStatus.of(message.getBody())
            val received = message.getStatus() == Message.STATUS_RECEIVED
            if (!rtpSessionStatus.successful && received) {
                return Pair<CharSequence, Boolean>(context.getString(R.string.missed_call), true)
            } else {
                return Pair<CharSequence, Boolean>(
                    context.getString(
                        if (received) R.string.incoming_call else R.string.outgoing_call
                    ),
                    true
                )
            }
        } else {
            // Tulkki: this feeds the notification, the conversation-list preview and search. A body
            // that needed translating and did not get one must not be shown raw here either, so it
            // becomes an opaque placeholder rather than the original. The interpreter is read last
            // and consulted first: off, this is the plain preview a client without Tulkki draws.
            //
            // getBody(true): the question is what this message's own text needs. The reply fallback
            // is somebody else's text quoted from an earlier message, so counting its language here
            // covers a reply whose own body is an emoji or a link for no reason - and the fallback is
            // taken back out of the rendered preview further down anyway, by the QuoteSpan sweep.
            val interpreter = TranslationSettings.get(context).interpreter()
            val displayed = DisplayedBody.of(
                message.getBody(true),
                message.getTranslatedBody(),
                message.getTranslationState(),
                DisplayedBody.needsTranslation(
                    message.getStatus(),
                    message.getBody(true),
                    ConversationName.of(message.getConversation()),
                    interpreter
                ),
                interpreter
            )
            if (displayed.isBlurred()) {
                return Pair<CharSequence, Boolean>(
                    context.getString(R.string.tulkki_untranslated), false
                )
            }
            if (displayed.isTranslation()) {
                // The translation is the message. There is no markup in it to process.
                return Pair<CharSequence, Boolean>(displayed.text(), false)
            }
            // getBody(true) here too, and for the same reason one level up: the branch below returns
            // this string verbatim, and the QuoteSpan sweep that takes the reply fallback out lives
            // past it, in the final else. With getBody() an action that carries a fallback returned
            // the peer's quoted original straight into the notification, the inbox line and the
            // conversation-list preview - the same shape the bubble's second half was fixed for, on
            // the one surface the audit believed was covered. The declared fallback span survives the
            // read, so stripping it removes exactly the quote and nothing else.
            val body = MessageUtils.filterLtrRtl(message.getBody(true))
            if (body.startsWith(Message.ME_COMMAND)) {
                return Pair<CharSequence, Boolean>(
                    body.replace(
                        Regex("^" + Message.ME_COMMAND),
                        UIHelper.getMessageDisplayName(message) + " "
                    ),
                    false
                )
            } else if (message.isGeoUri()) {
                return Pair<CharSequence, Boolean>(context.getString(R.string.location), true)
            } else if (!moderated &&
                (message.treatAsDownloadable() || MessageUtils.unInitiatedButKnownSize(message))
            ) {
                return Pair<CharSequence, Boolean>(
                    context.getString(
                        R.string.x_file_offered_for_download,
                        getFileDescriptionString(context, message)
                    ),
                    true
                )
            } else {
                val fallbackImg: Drawable = ResourcesCompat.getDrawable(
                    context.resources, R.drawable.ic_photo_24dp, null
                ) ?: throw NullPointerException("fallbackImg")
                fallbackImg.setBounds(
                    0, 0, fallbackImg.intrinsicWidth, fallbackImg.intrinsicHeight
                )
                val styledBody = message.getSpannableBody(null, fallbackImg)
                val processMarkup = styledBody.getSpans(
                    0, body.length, Message.PlainTextSpan::class.java
                ).isNotEmpty()
                if (textColor != 0 && processMarkup) {
                    StylingHelper.format(styledBody, 0, styledBody.length - 1, textColor, false)
                }
                val conversation = message.getConversation() as Conversational
                // The Java passed the model's unannotated getters straight into addLinks, which
                // dereferences both - so a null account or jid was an NPE inside the linkifier, not a
                // preview without links. The port's contract is nullable, so the NPE is named here
                // instead, at the same point in the same path.
                MyLinkify.addLinks(
                    styledBody,
                    conversation.getAccount() ?: throw NullPointerException("conversation has no account"),
                    conversation.getJid() ?: throw NullPointerException("conversation has no jid"),
                    context
                )

                for (quote in Lists.reverse(
                    Lists.newArrayList(
                        *styledBody.getSpans(
                            0, styledBody.length, android.text.style.QuoteSpan::class.java
                        )
                    )
                )) {
                    val start = styledBody.getSpanStart(quote)
                    val end = styledBody.getSpanEnd(quote)
                    if (start < 0 || end < 0 || (start == 0 && end == styledBody.length)) continue
                    styledBody.delete(start, end)
                    styledBody.removeSpan(quote)
                }
                if (!processMarkup) return Pair<CharSequence, Boolean>(styledBody, false)

                val builder = SpannableStringBuilder()
                for (l in CharSequenceUtils.split(styledBody, '\n')) {
                    if (l.length > 0) {
                        if (l.toString() == "```") {
                            continue
                        }
                        val first = l[0]
                        if (!QuoteHelper.isPositionQuoteStart(l, 0)) {
                            val line = CharSequenceUtils.trim(l)
                            if (line.length == 0) {
                                continue
                            }
                            val last = line[line.length - 1]
                            if (builder.length != 0) {
                                builder.append(' ')
                            }
                            builder.append(line)
                            if (!PUNCTIONATION.contains(last)) {
                                break
                            }
                        }
                    }
                }
                if (builder.length == 0) {
                    builder.append(body.trim { it <= ' ' })
                }
                return Pair<CharSequence, Boolean>(builder, false)
            }
        }
    }

    /**
     * Tulkki: the text of a message for a surface that cannot cover a body itself - the notification
     * inbox lines. A body that needed translating and did not get one becomes a placeholder instead
     * of leaking the original.
     *
     * `getBody(true)` and not `getBody()`: the reply fallback is a copy of the referenced message in
     * the other person's language, and this method returns its text verbatim - there is no
     * `getSpannableBody` pass here to take the quote back out. A reply that needed no translation
     * (already the app language, or a link) is otherwise exactly the case that puts somebody else's
     * original into the notification.
     */
    @JvmStatic
    fun getDisplayedBody(context: Context, message: Message): CharSequence {
        // Tulkki: the interpreter is read last and consulted first, so off this is the original body
        // a plain XMPP client puts on the notification's inbox line and on a quoted reaction.
        val interpreter = TranslationSettings.get(context).interpreter()
        val displayed = DisplayedBody.of(
            message.getBody(true),
            message.getTranslatedBody(),
            message.getTranslationState(),
            DisplayedBody.needsTranslation(
                message.getStatus(),
                message.getBody(true),
                ConversationName.of(message.getConversation()),
                interpreter
            ),
            interpreter
        )
        if (displayed.isBlurred()) {
            return context.getString(R.string.tulkki_untranslated)
        }
        return displayed.text()
    }

    @JvmStatic
    fun isLastLineQuote(body: String): Boolean {
        if (body.endsWith("\n")) {
            return false
        }
        val lines = body.split("\n")
        if (lines.isEmpty()) {
            return false
        }
        val line = lines[lines.size - 1]
        if (line.isEmpty()) {
            return false
        }
        val first = line[0]
        return first == '>' &&
            QuoteHelper.isPositionFollowedByQuoteableCharacter(line, 0) || first == '\u00bb'
    }

    @JvmStatic
    fun shorten(input: CharSequence): CharSequence {
        return if (input.length > 256) StylingHelper.subSequence(input, 0, 256) else input
    }

    /** Kept as a `:ui` entry point; the rule lives in [QuoteHelper]. */
    @JvmStatic
    fun isPositionPrecededByBodyStart(body: CharSequence, pos: Int): Boolean {
        return QuoteHelper.isPositionPrecededByBodyStart(body, pos)
    }

    /** Kept as a `:ui` entry point; the rule lives in [QuoteHelper]. */
    @JvmStatic
    fun isPositionPrecededByLineStart(body: CharSequence, pos: Int): Boolean {
        return QuoteHelper.isPositionPrecededByLineStart(body, pos)
    }

    /** Kept as a `:ui` entry point; the rule lives in [QuoteHelper]. */
    @JvmStatic
    fun isPositionFollowedByQuoteableCharacter(body: CharSequence, pos: Int): Boolean {
        return QuoteHelper.isPositionFollowedByQuoteableCharacter(body, pos)
    }

    /** Kept as a `:ui` entry point; the rule lives in [DisplayNames]. */
    @JvmStatic
    fun getDisplayName(user: MucOptions.User): String? {
        return DisplayNames.getDisplayName(user)
    }

    @JvmStatic
    fun concatNames(users: List<MucOptions.User>): String {
        return concatNames(users, users.size)
    }

    @JvmStatic
    fun concatNames(users: List<MucOptions.User>, max: Int): String {
        val builder = StringBuilder()
        val shortNames = users.size >= 3
        for (i in 0 until minOf(users.size, max)) {
            if (builder.length != 0) {
                builder.append(", ")
            }
            val name = UIHelper.getDisplayName(users[i])
            if (name != null) {
                builder.append(if (shortNames) name.split("\\s+".toRegex())[0] else name)
            }
        }
        return builder.toString()
    }

    @JvmStatic
    fun getFileDescriptionString(context: Context, message: Message): String {
        val mime = message.getMimeType()
        if (mime.isNullOrEmpty()) {
            return context.getString(R.string.file)
        } else if (MimeUtils.AMBIGUOUS_CONTAINER_FORMATS.contains(mime)) {
            return context.getString(R.string.multimedia_file)
        } else if (mime == "audio/x-m4b") {
            return context.getString(R.string.audiobook)
        } else if (mime.startsWith("audio/")) {
            return context.getString(R.string.audio)
        } else if (mime.startsWith("video/")) {
            return context.getString(R.string.video)
        } else if (mime == "image/gif") {
            return context.getString(R.string.gif)
        } else if (mime == "image/svg+xml") {
            return context.getString(R.string.vector_graphic)
        } else if (mime.startsWith("image/") || message.getType() == Message.TYPE_IMAGE) {
            return context.getString(R.string.image)
        } else if (mime.contains("pdf")) {
            return context.getString(R.string.pdf_document)
        } else if (MimeUtils.WORD_DOCUMENT_MIMES.contains(mime)) {
            return context.getString(R.string.word_document)
        } else if (mime == "application/vnd.android.package-archive") {
            return context.getString(R.string.apk)
        } else if (mime == BackupMimeType.MIME_TYPE) {
            return context.getString(R.string.backup_file_description)
        } else if (mime.contains("vcard")) {
            return context.getString(R.string.vcard)
        } else if (mime == "text/x-vcalendar" || mime == "text/calendar") {
            return context.getString(R.string.event)
        } else if (mime == "application/epub+zip" ||
            mime == "application/vnd.amazon.mobi8-ebook"
        ) {
            return context.getString(R.string.ebook)
        } else if (mime == "application/gpx+xml") {
            return context.getString(R.string.gpx_track)
        } else if (mime == "application/webxdc+zip") {
            val name = message.getFileParams().getName()
            return if (name != null && name.length < 20) {
                name
            } else {
                "Widget"
            }
        } else if (mime == "text/plain") {
            return context.getString(R.string.plain_text_document)
        } else {
            return mime
        }
    }

    /** Kept as a `:ui` entry point; the rule lives in [DisplayNames]. */
    @JvmStatic
    fun getMessageDisplayName(message: Message): String? {
        return DisplayNames.getMessageDisplayName(message)
    }

    /** Kept as a `:ui` entry point; the rule lives in [DisplayNames]. */
    @JvmStatic
    fun getDisplayName(conversation: Conversational, reaction: Reaction): String {
        return DisplayNames.getDisplayName(conversation, reaction)
    }

    @JvmStatic
    fun getMessageHint(context: Context, conversation: Conversation): String {
        return when (conversation.getNextEncryption()) {
            Message.ENCRYPTION_NONE -> {
                if (Config.multipleEncryptionChoices()) {
                    context.getString(R.string.send_message)
                } else {
                    context.getString(R.string.send_message_to_x, conversation.getName())
                }
            }
            Message.ENCRYPTION_OTR -> context.getString(R.string.send_otr_message)
            Message.ENCRYPTION_AXOLOTL -> {
                val axolotlService = (
                    conversation.getAccount() ?: throw NullPointerException("account")
                    ).getAxolotlService()
                if (axolotlService != null && axolotlService.trustedSessionVerified(conversation)) {
                    context.getString(R.string.send_omemo_x509_message)
                } else {
                    context.getString(R.string.send_encrypted_message)
                }
            }
            else -> context.getString(R.string.send_encrypted_message)
        }
    }

    /** Kept as a `:ui` entry point; the rule lives in [DisplayNames]. */
    @JvmStatic
    fun getDisplayedMucCounterpart(counterpart: Jid?): String {
        return DisplayNames.getDisplayedMucCounterpart(counterpart)
    }

    @JvmStatic
    fun receivedLocationQuestion(message: Message?): Boolean {
        if (message == null ||
            message.getStatus() != Message.STATUS_RECEIVED ||
            message.getType() != Message.TYPE_TEXT
        ) {
            return false
        }
        val body = Strings.nullToEmpty(message.getBody())
            .trim { it <= ' ' }
            .lowercase(Locale.getDefault())
            .replace("?", "")
            .replace("¿", "")
        return LOCATION_QUESTIONS.contains(body)
    }

    @JvmStatic
    fun setStatus(textView: TextView, status: Presence.Status) {
        val (text, color) = when (status) {
            Presence.Status.CHAT -> R.string.presence_chat to R.color.green_800
            Presence.Status.ONLINE -> R.string.presence_online to R.color.green_800
            Presence.Status.AWAY -> R.string.presence_away to R.color.amber_800
            Presence.Status.XA -> R.string.presence_xa to R.color.orange_800
            Presence.Status.DND -> R.string.presence_dnd to R.color.red_800
            else -> throw IllegalStateException()
        }
        textView.setText(text)
        textView.backgroundTintList = ColorStateList.valueOf(
            MaterialColors.harmonizeWithPrimary(
                textView.context,
                ContextCompat.getColor(textView.context, color)
            )
        )
    }

    @JvmStatic
    fun getCallInfo(context: Context, call: Call): String {
        val received = call.status == Message.STATUS_RECEIVED
        return if (!call.isSuccessful && received) {
            context.getString(R.string.missed_call)
        } else {
            context.getString(if (received) R.string.incoming_call else R.string.outgoing_call)
        }
    }

    @JvmStatic
    fun filesizeToString(size: Long): String {
        return if (size > (1.5 * 1024 * 1024)) {
            Math.round(size * 1f / (1024 * 1024)).toString() + " MiB"
        } else if (size >= 1024) {
            Math.round(size * 1f / 1024).toString() + " KiB"
        } else {
            "$size B"
        }
    }

    @JvmStatic
    fun getColoredUsername(service: XmppConnectionService, message: Message): SpannableString {
        val user = SpannableString.valueOf(UIHelper.getMessageDisplayName(message))
        user.setSpan(
            StyleSpan(Typeface.BOLD), 0, user.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        if (service.colored_muc_names()) {
            user.setSpan(
                ForegroundColorSpan(message.getAvatarBackgroundColor()),
                0,
                user.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return user
    }

    @JvmStatic
    @Nullable
    fun getColorForStatus(status: Presence.Status?): Int? {
        if (status == null) {
            return null
        }

        return when (status) {
            Presence.Status.CHAT -> 0xff259b24.toInt()
            Presence.Status.AWAY -> 0xffff9800.toInt()
            Presence.Status.XA -> 0xfff44336.toInt()
            Presence.Status.DND -> 0xfff44336.toInt()
            Presence.Status.ONLINE -> 0xff259b24.toInt()
            else -> null
        }
    }

    @JvmStatic
    fun getReadableEphemeralDuration(context: Context, seconds: Int): String {
        if (seconds <= 0) return ""
        return if (seconds <= 30) {
            context.getString(R.string.ephemeral_messages_30_seconds)
        } else if (seconds <= 60) {
            context.getString(R.string.ephemeral_messages_1_minute)
        } else if (seconds <= 300) {
            context.getString(R.string.ephemeral_messages_5_minutes)
        } else if (seconds <= 1800) {
            context.getString(R.string.ephemeral_messages_30_minutes)
        } else if (seconds <= 3600) {
            context.getString(R.string.ephemeral_messages_1_hour)
        } else if (seconds <= 21600) {
            context.getString(R.string.ephemeral_messages_6_hours)
        } else if (seconds <= 43200) {
            context.getString(R.string.ephemeral_messages_12_hours)
        } else if (seconds <= 86400) {
            context.getString(R.string.ephemeral_messages_1_day)
        } else if (seconds <= 604800) {
            context.getString(R.string.ephemeral_messages_1_week)
        } else {
            context.getString(R.string.ephemeral_messages_1_month)
        }
    }

    /**
     * Tulkki: which call-status icon an RTP session shows.
     *
     * This used to be `RtpSessionStatus.getDrawable` inside `:data`, which made a data class name
     * four drawables that live in `:ui`: after the split `:data` owns no `res/` tree for them, so the
     * choice moves to the module that draws it, where the ids are its own (the 3.8-r renamed
     * hand-work commit 945ecf86b8, section 2.3). Its caller - `CallsActivity` - goes through here
     * rather than repeating the four-way choice, as the deleted `CallsAdapter` did.
     */
    @JvmStatic
    @DrawableRes
    fun rtpSessionStatusIcon(received: Boolean, successful: Boolean): Int {
        if (received) {
            return if (successful) R.drawable.ic_call_received_24dp else R.drawable.ic_call_missed_24db
        }
        return if (successful) R.drawable.ic_call_made_24dp else R.drawable.ic_call_missed_outgoing_24dp
    }

    /**
     * Tulkki: the seven drawable ids `:data`'s `FileBackend` draws its preview badges with.
     *
     * After the split `:data` owns no `res/` tree for them, so the ids are named here - in the module
     * that owns them - and handed down as a [PreviewIcons] value from the one place `FileBackend` is
     * built (`:app`'s `DataStaticsHost.newFileBackend`). The same shape [rtpSessionStatusIcon] has for
     * the call-status icons, and for the same reason: the ids stay written in exactly one place, in
     * the module that can resolve them (the 3.8-r remaining hand-work commit cebe3e9388, section 2).
     */
    @JvmStatic
    fun previewIcons(): PreviewIcons {
        return PreviewIcons(
            R.drawable.audio_file_24dp,
            R.drawable.play_gif_black,
            R.drawable.play_gif_white,
            R.drawable.play_video_black,
            R.drawable.play_video_white,
            R.drawable.open_pdf_black,
            R.drawable.open_pdf_white
        )
    }
}
