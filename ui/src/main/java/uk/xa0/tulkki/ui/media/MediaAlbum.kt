package uk.xa0.tulkki.ui.media

import androidx.annotation.StringRes
import uk.xa0.tulkki.libs.AttachmentRef
import uk.xa0.tulkki.ui.R

/**
 * The five media browser tabs, in the old `MediaBrowserFragment.MediaType` order - which was also
 * the `ViewPager2` page order, so tab 0 is still All.
 */
enum class MediaTab(@StringRes val labelRes: Int) {
    ALL(R.string.tab_all),
    IMAGES(R.string.tab_images),
    VIDEOS(R.string.tab_videos),
    AUDIO(R.string.tab_audio),
    FILES(R.string.tab_files),
}

/** One row of an album: a day heading, or one attachment after it. */
sealed interface MediaAlbumEntry {
    data class Day(val timestamp: Long) : MediaAlbumEntry

    data class Row(val attachment: AttachmentRef) : MediaAlbumEntry
}

/**
 * The album's rows: `MediaAdapter.setAttachments`' interleave, extracted into one function because
 * two renderers now need it - the RecyclerView rows the contact and group details screens still use,
 * and the Compose grid the media browser draws - and two copies could drift.
 *
 * <p>A day heading whenever the calendar day, month or year changes, then the attachment. With
 * `showDateSeparators` off it is the attachments alone, in the order it was handed.
 */
fun mediaAlbum(attachments: List<AttachmentRef>, showDateSeparators: Boolean): List<MediaAlbumEntry> {
    val entries = ArrayList<MediaAlbumEntry>()
    if (attachments.isEmpty()) {
        return entries
    }
    if (!showDateSeparators) {
        for (attachment in attachments) {
            entries.add(MediaAlbumEntry.Row(attachment))
        }
        return entries
    }
    val calendar = java.util.Calendar.getInstance()
    var currentDay = -1
    var currentMonth = -1
    var currentYear = -1
    for (attachment in attachments) {
        calendar.setTimeInMillis(attachment.getTimestamp())
        val day = calendar.get(java.util.Calendar.DAY_OF_YEAR)
        val month = calendar.get(java.util.Calendar.MONTH)
        val year = calendar.get(java.util.Calendar.YEAR)
        if (day != currentDay || month != currentMonth || year != currentYear) {
            entries.add(MediaAlbumEntry.Day(attachment.getTimestamp()))
            currentDay = day
            currentMonth = month
            currentYear = year
        }
        entries.add(MediaAlbumEntry.Row(attachment))
    }
    return entries
}

/**
 * Which attachments a tab shows - the deleted `MediaBrowserFragment.matches`, arm for arm. The
 * recording test is the island `kind()` because the model's enum is not [AttachmentRef]'s.
 */
fun mediaMatches(attachment: AttachmentRef, tab: MediaTab): Boolean {
    val mime = attachment.getMime() ?: ""
    return when (tab) {
        MediaTab.ALL -> true
        MediaTab.IMAGES -> mime.startsWith("image/")
        MediaTab.VIDEOS -> mime.startsWith("video/")
        MediaTab.AUDIO ->
            mime.startsWith("audio/") || attachment.kind() == AttachmentRef.TypeRef.RECORDING
        MediaTab.FILES ->
            !mime.startsWith("image/") &&
                !mime.startsWith("video/") &&
                !mime.startsWith("audio/") &&
                attachment.kind() != AttachmentRef.TypeRef.RECORDING
    }
}
