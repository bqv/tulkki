package uk.xa0.tulkki.data.utils

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Parcel
import android.os.Parcelable
import com.google.common.base.MoreObjects
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.libs.AttachmentRef
import java.io.File
import java.util.ArrayList
import java.util.Collections
import java.util.UUID

/**
 * One media attachment of a message: its ids, its address and the little metadata the media browser, the
 * media preview and the send path all read.
 *
 * Pair 11 of `docs/MIGRATION.md` "The cycle rules" §3 moved this class down from
 * `uk.xa0.tulkki.ui.util.Attachment` to `:data`. It was the last `:data` -> `:ui` site (D9, pair 8b's
 * residue): `uk.xa0.tulkki.data.FileBackend` builds these in `convertToAttachments`, reads one in
 * `getPreviewForUri` and measures a list of them in `allFilesUnderSize`, and a port could not fix that -
 * the media list crosses the boundary from `:data` outward, so the type has to be one `:data` may name.
 * Nothing in the class is view work: it holds a `Uri`, a mime type, a timestamp and three ids, and the
 * only Android types it sees besides `Uri` are `Context`, `Intent` and `Parcel`.
 *
 * Every `:ui` reader was retargeted in the same commit and none of them changed a line of behaviour.
 * `XmppConnectionService` (an island) names it once, which is the pre-existing `:xmpp` -> `:data`
 * direction pair 9 retypes.
 *
 * C5-E3 declares the island's view of this class, `uk.xa0.tulkki.libs.AttachmentRef`, which the
 * media-viewing chain speaks so that `XmppConnectionService` names no `:data` type on that path. Only the
 * seven members the chain reads are on it and [Attachment] implements it with one addition of its own:
 * [kind], because `Type` is compared by identity in `:ui` and the model already declares [getType].
 */
class Attachment private constructor(
    private val uuidValue: UUID,
    private val uriValue: Uri,
    private val typeValue: Type,
    private val mimeValue: String?,
    private val timestampValue: Long,
    private val conversationUuidValue: String?,
) : Parcelable, AttachmentRef {

    private constructor(
        uri: Uri,
        type: Type,
        mime: String?,
        timestamp: Long,
        conversationUuid: String?,
    ) : this(UUID.randomUUID(), uri, type, mime, timestamp, conversationUuid)

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeParcelable(uriValue, flags)
        dest.writeString(mimeValue)
        dest.writeString(uuidValue.toString())
        dest.writeString(typeValue.toString())
        dest.writeLong(timestampValue)
        dest.writeString(conversationUuidValue)
    }

    override fun describeContents(): Int = 0

    override fun getUri(): Uri = uriValue

    override fun getMime(): String? = mimeValue

    override fun getUuid(): UUID = uuidValue

    override fun getTimestamp(): Long = timestampValue

    override fun getConversationUuid(): String? = conversationUuidValue

    fun getType(): Type = typeValue

    /**
     * Tulkki: C5-E3 - the island's view of [getType]. The name is different because a class may not
     * declare two methods differing only in return type, and `:ui` compares this enum by identity, so
     * the island has to hold island constants - see `AttachmentRef`'s comment. Not a cast and not a
     * second source of truth: the mapping is [kindOf] and `AttachmentKindRefTest` pins it name for name.
     */
    override fun kind(): AttachmentRef.TypeRef = kindOf(typeValue)

    override fun toString(): String =
        MoreObjects.toStringHelper(this)
            .add("uri", uriValue)
            .add("type", typeValue)
            .add("uuid", uuidValue)
            .add("mime", mimeValue)
            .add("timestamp", timestampValue)
            .add("conversationUuid", conversationUuidValue)
            .toString()

    override fun renderThumbnail(): Boolean =
        typeValue == Type.IMAGE ||
            (typeValue == Type.FILE && mimeValue != null && renderFileThumbnail(mimeValue))

    enum class Type {
        FILE,
        IMAGE,
        LOCATION,
        RECORDING,
    }

    companion object {

        @JvmField
        val CREATOR: Parcelable.Creator<Attachment> =
            object : Parcelable.Creator<Attachment> {
                override fun createFromParcel(parcel: Parcel): Attachment = fromParcel(parcel)

                override fun newArray(size: Int): Array<Attachment?> = arrayOfNulls(size)
            }

        private fun fromParcel(parcel: Parcel): Attachment {
            val uri = parcel.readParcelable<Uri>(Uri::class.java.classLoader)
            val mime = parcel.readString()
            val uuid = UUID.fromString(parcel.readString())
            val type = Type.valueOf(parcel.readString() ?: throw NullPointerException())
            val timestamp = parcel.readLong()
            val conversationUuid = parcel.readString()
            return Attachment(
                uuid,
                uri ?: throw NullPointerException(),
                type,
                mime,
                timestamp,
                conversationUuid,
            )
        }

        /**
         * The mapping itself, static so the JVM test can pin it without an [Attachment] (whose only
         * constructors want a `Uri`, i.e. an Android type).
         */
        @JvmStatic
        fun kindOf(type: Type): AttachmentRef.TypeRef =
            when (type) {
                Type.FILE -> AttachmentRef.TypeRef.FILE
                Type.IMAGE -> AttachmentRef.TypeRef.IMAGE
                Type.LOCATION -> AttachmentRef.TypeRef.LOCATION
                Type.RECORDING -> AttachmentRef.TypeRef.RECORDING
            }

        @JvmStatic
        fun canBeSendInBand(attachments: List<Attachment>): Boolean {
            for (attachment in attachments) {
                if (attachment.typeValue != Type.LOCATION && "https" != attachment.uriValue.scheme) {
                    return false
                }
            }
            return true
        }

        @JvmStatic
        fun of(context: Context, uri: Uri, type: Type): List<Attachment> {
            val mime = if (type == Type.LOCATION) null else MimeUtils.guessMimeTypeFromUri(context, uri)
            return Collections.singletonList(
                Attachment(uri, type, mime, System.currentTimeMillis(), null),
            )
        }

        @JvmStatic
        fun of(message: Message): Attachment {
            val uuid = UUID.fromString(message.getUuid())
            val conversationUuid =
                (message.getConversation() ?: throw NullPointerException()).getUuid()
            if (message.isGeoUri()) {
                return Attachment(
                    uuid,
                    Uri.EMPTY,
                    Type.LOCATION,
                    null,
                    message.getTimeSent(),
                    conversationUuid,
                )
            }
            val mime = message.getMimeType()
            if (mime != null && MimeUtils.AMBIGUOUS_CONTAINER_FORMATS.contains(mime)) {
                val fileParams = message.getFileParams()
                return if (fileParams.width > 0 && fileParams.height > 0) {
                    Attachment(uuid, Uri.EMPTY, Type.FILE, "video/*", message.getTimeSent(), conversationUuid)
                } else if (fileParams.runtime > 0) {
                    Attachment(uuid, Uri.EMPTY, Type.FILE, "audio/*", message.getTimeSent(), conversationUuid)
                } else {
                    Attachment(
                        uuid,
                        Uri.EMPTY,
                        Type.FILE,
                        "application/octet-stream",
                        message.getTimeSent(),
                        conversationUuid,
                    )
                }
            }
            return Attachment(uuid, Uri.EMPTY, Type.FILE, mime, message.getTimeSent(), conversationUuid)
        }

        @JvmStatic
        fun of(context: Context, uris: List<Uri?>, type: String?): List<Attachment> {
            val attachments = ArrayList<Attachment>()
            for (uri in uris) {
                if (uri == null) {
                    continue
                }
                val mime = MimeUtils.guessMimeTypeFromUriAndMime(context, uri, type)
                attachments.add(
                    Attachment(
                        uri,
                        if (mime != null && isImage(mime)) Type.IMAGE else Type.FILE,
                        mime,
                        System.currentTimeMillis(),
                        null,
                    ),
                )
            }
            return attachments
        }

        @JvmStatic
        fun of(uuid: UUID, file: File, mime: String?): Attachment =
            of(uuid, file, mime, System.currentTimeMillis(), null)

        @JvmStatic
        fun of(
            uuid: UUID,
            file: File,
            mime: String?,
            timestamp: Long,
            conversationUuid: String?,
        ): Attachment =
            Attachment(
                uuid,
                Uri.fromFile(file),
                if (mime != null && (isImage(mime) || mime.startsWith("video/"))) {
                    Type.IMAGE
                } else {
                    Type.FILE
                },
                mime,
                timestamp,
                conversationUuid,
            )

        @JvmStatic
        fun extractAttachments(context: Context, intent: Intent?, type: Type): List<Attachment> {
            val uris = ArrayList<Attachment>()
            if (intent == null) {
                return uris
            }
            val contentType = intent.type
            val data = intent.data
            if (data == null) {
                val clipData = intent.clipData
                if (clipData != null) {
                    for (i in 0 until clipData.itemCount) {
                        val uri = clipData.getItemAt(i).uri
                        val mime = MimeUtils.guessMimeTypeFromUriAndMime(context, uri, contentType)
                        uris.add(Attachment(uri, type, mime, System.currentTimeMillis(), null))
                    }
                }
            } else {
                val mime = MimeUtils.guessMimeTypeFromUriAndMime(context, data, contentType)
                uris.add(Attachment(data, type, mime, System.currentTimeMillis(), null))
            }
            return uris
        }

        private fun renderFileThumbnail(mime: String): Boolean =
            mime.startsWith("video/") || isImage(mime) || "application/pdf" == mime

        private fun isImage(mime: String): Boolean = mime.startsWith("image/")
    }
}
