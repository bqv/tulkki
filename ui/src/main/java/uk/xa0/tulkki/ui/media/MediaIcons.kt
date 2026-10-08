package uk.xa0.tulkki.ui.media

import androidx.annotation.DrawableRes
import uk.xa0.tulkki.data.utils.BackupMimeType
import uk.xa0.tulkki.data.utils.MimeUtils
import uk.xa0.tulkki.libs.AttachmentRef
import uk.xa0.tulkki.ui.R

/**
 * The mime-to-icon map the media rows draw when there is no thumbnail to draw.
 *
 * <p>It was `MediaAdapter`'s private `getImageDrawable`, and it moves here because the two item
 * layouts are gone and two different renderers now need the same answer: the RecyclerView rows
 * [MediaAdapter] still builds for the contact and group details screens, and the Compose grid
 * [MediaBrowserScreen] draws. One copy, so the two cannot disagree about what a `.zip` looks like.
 *
 * <p>The map is the old one, arm for arm: the island `kind()` wins over the mime for a location and a
 * recording, and everything else is the mime. `getMime()` is nullable on the ref, and a null mime is
 * the old `null`/empty arm - `ic_help_center_48dp`.
 */
object MediaIcons {

    private val ARCHIVE_MIMES: List<String> =
        listOf(
            "application/x-7z-compressed",
            "application/zip",
            "application/rar",
            "application/x-gtar",
            "application/x-tar",
        )

    private val CODE_MIMES: List<String> = listOf("text/html", "text/xml")

    /**
     * The icon for one attachment. The overload is total: a `String` is not an [AttachmentRef] and a
     * model `Attachment` is not a `String`, so both callers resolve without ambiguity.
     */
    @JvmStatic
    @DrawableRes
    fun drawableFor(attachment: AttachmentRef): Int {
        return when (attachment.kind()) {
            AttachmentRef.TypeRef.LOCATION -> R.drawable.ic_location_pin_48dp
            AttachmentRef.TypeRef.RECORDING -> R.drawable.ic_mic_48dp
            else -> drawableFor(attachment.getMime())
        }
    }

    /** The icon for one mime type, which is the whole map apart from the two island kinds. */
    @JvmStatic
    @DrawableRes
    fun drawableFor(mime: String?): Int {
        if (mime.isNullOrEmpty()) {
            return R.drawable.ic_help_center_48dp
        }
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
}
