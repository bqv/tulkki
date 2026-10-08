package uk.xa0.tulkki.ui.fragment.settings

import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.preferences.PreferenceItem
import uk.xa0.tulkki.ui.preferences.PreferenceScreenFragment

/**
 * The three bubble switches `preferences_interface_bubbles.xml` held - `use_green_background`,
 * `align_start`, `show_avatars` - with their icons, summaries and `@bool` defaults unchanged, and the
 * file deleted with this class.
 */
class InterfaceBubblesSettingsFragment : PreferenceScreenFragment() {

    override fun buildItems(): List<PreferenceItem> {
        val items = ArrayList<PreferenceItem>()
        items.add(
            switchItem(
                USE_GREEN_BACKGROUND,
                R.string.pref_use_colorful_bubbles,
                R.string.pref_use_colorful_bubbles_summary,
                R.drawable.ic_colors_24dp,
                resources.getBoolean(uk.xa0.tulkki.data.R.bool.use_green_background),
            )
        )
        items.add(
            switchItem(
                ALIGN_START,
                R.string.pref_align_start,
                R.string.pref_align_start_summary,
                R.drawable.ic_format_align_left_24dp,
                resources.getBoolean(uk.xa0.tulkki.data.R.bool.align_start),
            )
        )
        items.add(
            switchItem(
                SHOW_AVATARS,
                R.string.pref_show_avatars,
                R.string.pref_show_avatars_summary,
                R.drawable.ic_account_circle_24dp,
                resources.getBoolean(uk.xa0.tulkki.data.R.bool.show_avatars),
            )
        )
        return items
    }

    override fun onStart() {
        super.onStart()
        requireActivity().setTitle(R.string.pref_title_bubbles)
    }

    companion object {
        private const val USE_GREEN_BACKGROUND = "use_green_background"
        private const val ALIGN_START = "align_start"
        private const val SHOW_AVATARS = "show_avatars"
    }
}
