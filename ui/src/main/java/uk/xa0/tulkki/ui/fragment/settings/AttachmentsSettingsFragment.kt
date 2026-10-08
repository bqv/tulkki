package uk.xa0.tulkki.ui.fragment.settings

import android.widget.Toast

import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.preferences.PreferenceItem
import uk.xa0.tulkki.ui.preferences.PreferenceScreenFragment
import uk.xa0.tulkki.ui.utils.UIHelper

/**
 * The attachment switches and lists `preferences_attachments.xml` held, split over the same three
 * categories - sending, receiving and the advanced options - and the file deleted with this class.
 *
 * <p>Two rows keep their programmatic shape from the old fragment: `auto_accept_file_size` draws its
 * entries from `R.array.file_size_values`, an int array, named by `UIHelper.filesizeToString` (or
 * "never" at zero), and `clear_blocked_media` is not a value at all - it clears the service's blocked
 * list and says so. The words of that confirmation are the old fragment's own hardcoded sentence,
 * kept verbatim.
 */
class AttachmentsSettingsFragment : PreferenceScreenFragment() {

    override fun buildItems(): List<PreferenceItem> {
        val items = ArrayList<PreferenceItem>()
        items.add(header(R.string.pref_category_sending))
        items.add(list(PICTURE_COMPRESSION, R.string.pref_picture_compression, R.drawable.ic_photo_24dp, R.array.picture_compression_entries, R.array.picture_compression_values, getString(uk.xa0.tulkki.xmpp.R.string.picture_compression)))
        items.add(list(VIDEO_COMPRESSION, R.string.pref_video_compression, R.drawable.ic_movie_24dp, R.array.video_compression_entries, R.array.video_compression_values, getString(R.string.video_compression)))
        items.add(header(R.string.pref_category_receiving))
        items.add(autoAcceptFileSize())
        items.add(
            switchItem(
                AUTO_ACCEPT_UNMETERED,
                R.string.always_accept_when_unmetered,
                R.string.pref_auto_accept_unmetered_summary,
                R.drawable.ic_wifi_24dp,
                resources.getBoolean(uk.xa0.tulkki.xmpp.R.bool.auto_accept_unmetered),
            )
        )
        items.add(
            switchItem(
                NOMEDIA,
                R.string.pref_hide_media_title,
                R.string.pref_hide_media_summary,
                R.drawable.browse_24dp,
                resources.getBoolean(uk.xa0.tulkki.xmpp.R.bool.default_nomedia),
            )
        )
        items.add(
            switchItem(
                DELETE_UNUSED_FILES,
                R.string.pref_delete_unused_files,
                R.string.pref_delete_unused_files_summary,
                R.drawable.ic_delete_24dp,
                resources.getBoolean(uk.xa0.tulkki.data.R.bool.delete_unused_files),
            )
        )
        items.add(
            switchItem(
                DEFAULT_STORE_MEDIA_SECURELY,
                R.string.store_media_only_in_cache,
                R.string.pref_store_media_in_cache,
                R.drawable.ic_archive_24dp,
                resources.getBoolean(uk.xa0.tulkki.data.R.bool.default_store_media_securely),
            )
        )

        items.add(header(R.string.pref_advanced_options))
        items.add(list(CACHE_DELETION_TIME, R.string.pref_cache_deletion_time, R.drawable.ic_auto_delete_24dp, R.array.cache_deletion_entries, R.array.cache_deletion_values, "0"))
        items.add(
            switchItem(
                INTERNAL_MEDIA_VIEWER,
                R.string.internal_meda_viewer_title,
                R.string.internal_meda_viewer_summary,
                R.drawable.outline_perm_media_24,
                resources.getBoolean(R.bool.internal_meda_viewer),
            )
        )
        items.add(
            switchItem(
                SKIP_IMAGE_EDITOR_SCREEN,
                R.string.pref_skip_image_editor_screen,
                R.string.pref_skip_image_editor_screen_summary,
                R.drawable.outline_edit_square_24,
                resources.getBoolean(R.bool.skip_image_editor_screen),
            )
        )
        items.add(
            PreferenceItem(
                key = CLEAR_BLOCKED_MEDIA,
                title = getString(R.string.clear_blocked_media),
                summary = getString(R.string.pref_clear_blocked_media_summary),
                icon = R.drawable.ic_delete_24dp,
            )
        )
        items.add(list(VOICE_MESSAGE_CODEC, R.string.voice_message_codec, R.drawable.audio_file_24dp, R.array.voice_codec_entries, R.array.voice_codec_values, "aac"))
        return items
    }

    override fun onPreferenceClick(item: PreferenceItem) {
        if (item.key == CLEAR_BLOCKED_MEDIA) {
            requireService().clearBlockedMedia()
            runOnUiThread {
                Toast.makeText(
                        requireActivity(),
                        "Blocked media will be displayed again",
                        Toast.LENGTH_LONG,
                    )
                    .show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        requireActivity().setTitle(R.string.pref_attachments)
    }

    /**
     * The one row the old fragment built by hand: `setValues(autoAcceptFileSize,
     * R.array.file_size_values)` with the entry named by a byte count. Its static summary was empty, so
     * the entry is the whole summary - `useSimpleSummaryProvider`'s own fallback.
     */
    private fun autoAcceptFileSize(): PreferenceItem {
        val (labels, values) =
            listFromIntArray(R.array.file_size_values) { value ->
                if (value <= 0) {
                    getString(R.string.never)
                } else {
                    UIHelper.filesizeToString(value.toLong())
                }
            }
        val default = resources.getInteger(uk.xa0.tulkki.xmpp.R.integer.auto_accept_filesize).toString()
        val value = preferences.getString(AUTO_ACCEPT_FILE_SIZE, default)
        return PreferenceItem(
            key = AUTO_ACCEPT_FILE_SIZE,
            title = getString(R.string.pref_automatic_download),
            summary = listSummary(labels, values, value),
            icon = R.drawable.ic_download_24dp,
            kind = PreferenceItem.Kind.LIST,
            labels = labels,
            values = values,
            value = value,
            defaultValue = default,
        )
    }

    /** A `ListPreference` whose entries and values are two string arrays and whose summary is its entry. */
    private fun list(
        key: String,
        title: Int,
        icon: Int,
        entriesRes: Int,
        valuesRes: Int,
        default: String,
    ): PreferenceItem {
        val (labels, values) = listFromArrays(entriesRes, valuesRes)
        val value = preferences.getString(key, default)
        return PreferenceItem(
            key = key,
            title = getString(title),
            summary = listSummary(labels, values, value),
            icon = icon,
            kind = PreferenceItem.Kind.LIST,
            labels = labels,
            values = values,
            value = value,
            defaultValue = default,
        )
    }

    private fun header(title: Int): PreferenceItem =
        PreferenceItem(title = getString(title), kind = PreferenceItem.Kind.HEADER)

    companion object {
        private const val PICTURE_COMPRESSION = "picture_compression"
        private const val VIDEO_COMPRESSION = "video_compression"
        private const val AUTO_ACCEPT_FILE_SIZE = "auto_accept_file_size"
        private const val AUTO_ACCEPT_UNMETERED = "auto_accept_unmetered"
        private const val NOMEDIA = "nomedia"
        private const val DELETE_UNUSED_FILES = "delete_unused_files"
        private const val DEFAULT_STORE_MEDIA_SECURELY = "default_store_media_securely"
        private const val CACHE_DELETION_TIME = "cache_deletion_time"
        private const val INTERNAL_MEDIA_VIEWER = "internal_meda_viewer"
        private const val SKIP_IMAGE_EDITOR_SCREEN = "skip_image_editor_screen"
        private const val CLEAR_BLOCKED_MEDIA = "clear_blocked_media"
        private const val VOICE_MESSAGE_CODEC = "voice_message_codec"
    }
}
