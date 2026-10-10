package uk.xa0.tulkki.ui.fragment.settings

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast

import androidx.core.content.ContextCompat

import com.google.android.material.color.DynamicColors

import uk.xa0.tulkki.data.AppSettings
import uk.xa0.tulkki.ui.BuildConfig
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.imageeditor.ImageEditorActivity
import uk.xa0.tulkki.ui.preferences.PreferenceItem
import uk.xa0.tulkki.ui.preferences.PreferenceScreenFragment
import uk.xa0.tulkki.ui.util.SettingsUtils
import uk.xa0.tulkki.ui.utils.ChatBackgroundHelper
import uk.xa0.tulkki.ui.utils.ThemeHelper

import java.io.File

/**
 * The appearance rows `preferences_interface.xml` held, over the same five categories, with the file
 * deleted with this class.
 *
 * <p>The theme rows are conditional and the condition is the old `updateCustomVisibility`, now a
 * per-render reading: the custom colours exist only when the stored theme is `custom` **and** the
 * platform is API 30 or newer, the four light colours only in light mode, the four dark ones only in
 * dark mode, the "is dark" switch only while "follow the system" is off, and the dynamic-colours row
 * only where the platform offers dynamic colour and no custom theme is in force. A row that does not
 * apply is absent, which is what `isVisible = false` drew.
 *
 * <p>The nine colour rows are `com.rarepebble.colorpicker.ColorPreference` still: the row opens the
 * library's own picker through the engine's COLOUR dialog, and the value is the stored ARGB int with
 * the same `@color/md_theme_*` default written on bind. The theme-change listener's `recreate()` and
 * the custom-colour listener's `ThemeHelper.applyCustomColors` plus `recreate()` are unchanged.
 */
class InterfaceSettingsFragment : PreferenceScreenFragment() {

    override fun buildItems(): List<PreferenceItem> {
        val items = ArrayList<PreferenceItem>()
        val custom = customThemeSelected()
        val customVisible = custom && Build.VERSION.SDK_INT >= 30
        val dark = requireSettingsActivity().isDark()

        items.add(header(R.string.pref_theme_title))
        if (DynamicColors.isDynamicColorAvailable() && !customVisible) {
            items.add(
                switchItem(
                    AppSettings.DYNAMIC_COLORS,
                    R.string.pref_dynamic_colors,
                    R.string.pref_dynamic_colors_summary,
                    R.drawable.ic_palette_24dp,
                    false,
                )
            )
        }
        items.add(theme())
        if (customVisible) {
            items.add(
                switchItem(
                    CUSTOM_THEME_AUTOMATIC,
                    R.string.follow_system_dark_mode,
                    null,
                    R.drawable.ic_dark_mode_24dp,
                    true,
                )
            )
            if (!preferences.getBoolean(CUSTOM_THEME_AUTOMATIC, true)) {
                items.add(
                    switchItem(
                        CUSTOM_THEME_DARK,
                        R.string.custom_theme_is_dark,
                        null,
                        R.drawable.ic_dark_mode_24dp,
                        false,
                    )
                )
            }
            items.add(
                switchItem(
                    CUSTOM_THEME_COLOR_MATCH,
                    R.string.color_match,
                    R.string.stay_true_to_my_color_inputs,
                    R.drawable.ic_palette_24dp,
                    false,
                )
            )
        }
        // The eight colour slots are always in the list and only their *drawing* follows the mode. An
        // old row was in the inflated tree whether or not `isVisible` was set, and writing its
        // `@color` default on bind is what makes a slot exist at all: `ThemeHelper` reads each one with
        // `contains`, so a slot the screen never wrote is a custom colour the theme cannot apply.
        items.add(colour(CUSTOM_THEME_PRIMARY, R.string.custom_primary_color, R.color.md_theme_light_primary, visible = customVisible && !dark))
        items.add(colour(CUSTOM_THEME_PRIMARY_DARK, R.string.custom_secondary_color, R.color.md_theme_light_secondary, visible = customVisible && !dark))
        items.add(colour(CUSTOM_THEME_ACCENT, R.string.custom_tertiary_color, R.color.md_theme_light_tertiary, visible = customVisible && !dark))
        items.add(colour(CUSTOM_THEME_BACKGROUND_PRIMARY, R.string.custom_background_color, R.color.md_theme_light_surface, visible = customVisible && !dark))
        items.add(colour(CUSTOM_DARK_THEME_PRIMARY, R.string.custom_primary_color, R.color.md_theme_dark_primary, visible = customVisible && dark))
        items.add(colour(CUSTOM_DARK_THEME_PRIMARY_DARK, R.string.custom_secondary_color, R.color.md_theme_dark_secondary, visible = customVisible && dark))
        items.add(colour(CUSTOM_DARK_THEME_ACCENT, R.string.custom_tertiary_color, R.color.md_theme_dark_tertiary, visible = customVisible && dark))
        items.add(colour(CUSTOM_DARK_THEME_BACKGROUND_PRIMARY, R.string.custom_background_color, R.color.md_theme_dark_surface, visible = customVisible && dark))

        items.add(header(R.string.appearance))
        items.add(
            PreferenceItem(
                key = IMPORT_BACKGROUND,
                title = getString(R.string.custom_background),
                summary = getString(R.string.pref_chat_background_summary),
                icon = R.drawable.ic_image_24dp,
            )
        )
        items.add(
            PreferenceItem(
                key = DELETE_BACKGROUND,
                title = getString(R.string.delete_background),
                summary = getString(R.string.pref_delete_background_summary),
                icon = R.drawable.ic_delete_24dp,
            )
        )
        items.add(switch(UNICOLORED_CHATBG, R.string.pref_use_unicolored_chatbg, R.string.pref_use_unicolored_chatbg_summary, R.drawable.rounded_hide_image_24, R.bool.use_unicolored_chatbg))
        items.add(switch(SHOW_CONTACT_STATUS, R.string.pref_show_contact_presence, R.string.pref_show_contact_presence_details, R.drawable.active_indicator, R.bool.show_contact_status))
        items.add(switch(ALWAYS_FULL_TIMESTAMPS, R.string.pref_always_show_full_timestamps, R.string.pref_always_show_full_timestamps_summary, R.drawable.outline_123_24, R.bool.always_full_timestamps))
        items.add(switch(SHOW_OWN_ACCOUNTS, R.string.pref_show_own_accounts, R.string.pref_show_own_accounts_summary, R.drawable.ic_person_24dp, R.bool.show_own_accounts))
        items.add(switch(PINNED_STATUS_MESSAGE, R.string.pref_pinned_status_message, R.string.pref_pinned_status_message_summary, R.drawable.rounded_info_24, R.bool.pinned_status_message))
        items.add(switch(COLORED_MUC_NAMES, R.string.pref_use_colored_muc_names, R.string.pref_use_colored_muc_names_summary, R.drawable.rounded_format_color_text_24, uk.xa0.tulkki.xmpp.R.bool.use_colored_muc_names))
        items.add(switch(SHOW_NAV_BAR, R.string.pref_show_navigation_bar, R.string.pref_show_navigation_bar_summary, R.drawable.rounded_bottom_navigation_24, R.bool.show_nav_bar))
        items.add(switch(SHOW_NAV_DRAWER, R.string.pref_show_show_nav_drawer, R.string.pref_show_show_nav_drawer_summary, R.drawable.menu_24dp, R.bool.show_nav_drawer))
        items.add(
            list(
                AVATAR_SHAPE,
                R.string.pref_avatars_shape,
                R.string.pref_avatar_shape_summary,
                R.drawable.rounded_account_circle_24,
                R.array.avatars_shape,
                R.array.avatars_shape_values,
                getString(R.string.avatar_shape),
                null,
                simpleSummary = false,
            )
        )
        items.add(
            PreferenceItem(
                key = InterfaceBubblesSettingsFragment::class.java.simpleName,
                title = getString(R.string.pref_chat_bubbles),
                summary = getString(R.string.pref_chat_bubbles_summary),
                icon = R.drawable.ic_forum_24dp,
            )
        )
        items.add(switch(LARGE_FONT, R.string.pref_large_font, R.string.pref_large_font_summary, R.drawable.ic_format_size_24dp, uk.xa0.tulkki.data.R.bool.large_font))
        items.add(switch(SHOW_DYNAMIC_TAGS, R.string.pref_show_dynamic_tags, R.string.pref_show_dynamic_tags_summary, R.drawable.ic_label_24dp, R.bool.show_dynamic_tags))
        items.add(switch(AppSettings.SHOW_LINK_PREVIEWS, R.string.show_link_previews, R.string.show_link_previews_summary, R.drawable.rounded_link_24, uk.xa0.tulkki.data.R.bool.show_link_previews))
        items.add(switch(PLAIN_TEXT_LINKS, R.string.pref_plain_text_links_title, R.string.pref_plain_text_links_summary, R.drawable.rounded_link_24, R.bool.plain_text_links))
        items.add(switch(SET_TEXT_COLLAPSABLE, R.string.pref_set_text_collapsable, R.string.pref_set_text_collapsable_summary, R.drawable.ic_keyboard_double_arrow_down_24dp, R.bool.set_text_collapsable))
        items.add(switch(AppSettings.SHOW_FORMATTING_MARKS, R.string.pref_show_formatting_marks, R.string.pref_show_formatting_marks_summary, R.drawable.rounded_format_bold_24, uk.xa0.tulkki.data.R.bool.show_formatting_marks))

        items.add(header(R.string.pref_category_operating_system))
        items.add(switch(AppSettings.ALLOW_SCREENSHOTS, R.string.pref_allow_screenshots, R.string.pref_allow_screenshots_summary, R.drawable.ic_screenshot_24dp, uk.xa0.tulkki.data.R.bool.allow_screenshots))
        items.add(switch(CUSTOM_TAB, R.string.pref_custom_tab, R.string.pref_custom_tab_summary, R.drawable.browse_24dp, R.bool.default_custom_tab))
        items.add(switch(SWIPE_TO_ARCHIVE, R.string.pref_swipe_to_archive_title, R.string.pref_swipe_to_archive_summary, R.drawable.ic_archive_24dp, R.bool.swipe_to_archive))

        items.add(header(R.string.pref_input_options))
        items.add(switch(SHOW_THREAD_FEATURE, R.string.pref_show_thread_feature, R.string.pref_show_thread_feature_summary, R.drawable.thread_hint, uk.xa0.tulkki.xmpp.R.bool.show_thread_feature))
        items.add(switch(SHOW_TEXT_FORMATTING, R.string.pref_showtextformatting, R.string.pref_showtextformatting_sum, R.drawable.rounded_format_bold_24, uk.xa0.tulkki.xmpp.R.bool.showtextformatting))
        items.add(
            list(
                QUICK_ACTION,
                R.string.pref_quick_action,
                R.string.pref_quick_action_summary,
                R.drawable.ic_send_time_extension_24dp,
                R.array.quick_actions,
                R.array.quick_action_values,
                getString(R.string.quick_action),
                R.string.choose_quick_action,
                simpleSummary = false,
            )
        )
        items.add(switch(SCROLL_TO_BOTTOM, R.string.pref_scroll_to_bottom, R.string.pref_scroll_to_bottom_summary, R.drawable.ic_vertical_align_bottom_24dp, R.bool.scroll_to_bottom))
        items.add(switch(START_SEARCHING, R.string.pref_start_search, R.string.pref_start_search_summary, R.drawable.ic_search_24dp, R.bool.start_searching))
        items.add(switch(FOLLOW_THREAD_IN_CHANNEL, R.string.pref_follow_thread_in_channel, R.string.pref_follow_thread_in_channel_summary, R.drawable.ic_replay_24dp, R.bool.follow_thread_in_channel))
        items.add(switch(JUMP_TO_COMMANDS_TAB, R.string.pref_jump_to_commands_tab, R.string.pref_jump_to_commands_tab_summary, R.drawable.ic_open_with_24dp, uk.xa0.tulkki.data.R.bool.jump_to_commands_tab))
        items.add(switch(COMPOSE_RICH_TEXT, R.string.compose_using_rich_text, R.string.compose_using_rich_text_summary, R.drawable.ic_format_size_24dp, R.bool.compose_rich_text))
        items.add(switch(SHOW_MUC_PM, R.string.pref_show_muc_pm_title, R.string.pref_show_muc_pm_summary, R.drawable.ic_announcement_24dp, R.bool.show_muc_pm))

        items.add(header(R.string.pref_keyboard_options))
        items.add(switch(DISPLAY_ENTER_KEY, R.string.pref_display_enter_key, R.string.pref_display_enter_key_summary, R.drawable.ic_keyboard_return_24dp, R.bool.display_enter_key))
        items.add(switch(ENTER_IS_SEND, R.string.pref_enter_is_send, R.string.pref_enter_is_send_summary, R.drawable.ic_send_24dp, R.bool.enter_is_send))
        return items
    }

    /** The theme list row. Its summary is its own entry, because the XML said so. */
    private fun theme(): PreferenceItem =
        list(
            AppSettings.THEME,
            R.string.pref_theme_title,
            null,
            R.drawable.ic_dark_mode_24dp,
            R.array.themes,
            R.array.themes_values,
            getString(R.string.theme),
            null,
            simpleSummary = true,
        )

    /** Whether the theme in force is the custom one, the way the old `summary` comparison asked it. */
    private fun customThemeSelected(): Boolean {
        val (labels, values) = listFromArrays(R.array.themes, R.array.themes_values)
        val value = preferences.getString(AppSettings.THEME, getString(R.string.theme))
        return getString(R.string.pref_theme_custom) == listSummary(labels, values, value)?.toString()
    }

    override fun onPreferenceClick(item: PreferenceItem) {
        when (item.key) {
            InterfaceBubblesSettingsFragment::class.java.simpleName ->
                openScreen(InterfaceBubblesSettingsFragment())
            IMPORT_BACKGROUND -> {
                if (requireSettingsActivity()
                        .hasStoragePermission(ChatBackgroundHelper.REQUEST_IMPORT_BACKGROUND)
                ) {
                    ChatBackgroundHelper.openBGPicker(this)
                }
            }
            DELETE_BACKGROUND -> deleteBackground()
        }
    }

    override fun onPreferenceChange(item: PreferenceItem, newValue: Any?): Boolean {
        when (item.key) {
            AppSettings.THEME -> {
                if (newValue is String) {
                    requireSettingsActivity().recreate()
                }
            }
            AppSettings.DYNAMIC_COLORS -> {
                requireSettingsActivity().setDynamicColors(java.lang.Boolean.TRUE == newValue)
            }
        }
        return true
    }

    override fun onSharedPreferenceChanged(key: String) {
        super.onSharedPreferenceChanged(key)
        if (key == AppSettings.ALLOW_SCREENSHOTS) {
            SettingsUtils.applyScreenshotSetting(requireActivity())
        }
        if (CUSTOM_THEME_KEYS.contains(key)) {
            ThemeHelper.applyCustomColors(requireService())
            Thread { runOnUiThread { requireActivity().recreate() } }.start()
        }
    }

    override fun onStart() {
        super.onStart()
        requireActivity().setTitle(R.string.pref_title_interface)
    }

    private fun deleteBackground() {
        try {
            val bgfile: File = ChatBackgroundHelper.getBgFile(requireSettingsActivity(), null)
            if (bgfile.exists()) {
                bgfile.delete()
                Toast.makeText(
                        requireSettingsActivity(),
                        R.string.delete_background_success,
                        Toast.LENGTH_LONG,
                    )
                    .show()
            } else {
                Toast.makeText(requireSettingsActivity(), R.string.no_background_set, Toast.LENGTH_LONG)
                    .show()
            }
        } catch (e: Exception) {
            Toast.makeText(requireSettingsActivity(), R.string.delete_background_failed, Toast.LENGTH_LONG)
                .show()
            throw RuntimeException(e)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK) {
            if (requestCode == ChatBackgroundHelper.REQUEST_IMPORT_BACKGROUND) {
                val imageUri: Uri? = data?.getData()
                if (imageUri != null) {
                    val editIntent = Intent(activity, ImageEditorActivity::class.java)
                    editIntent.setData(imageUri)
                    editIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivityForResult(editIntent, REQUEST_EDIT_BACKGROUND)
                    return
                }
            } else if (requestCode == REQUEST_EDIT_BACKGROUND) {
                var uri: Uri? = data?.getParcelableExtra<Uri>(ImageEditorActivity.KEY_EDITED_URI)
                if (uri == null && data != null) {
                    uri = data.getData()
                }

                if (uri != null) {
                    val resultIntent = Intent()
                    resultIntent.setData(uri)
                    ChatBackgroundHelper.onActivityResult(
                        requireSettingsActivity(),
                        ChatBackgroundHelper.REQUEST_IMPORT_BACKGROUND,
                        resultCode,
                        resultIntent,
                        null,
                    )
                }
                return
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty()) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                ChatBackgroundHelper.onRequestPermissionsResult(
                    this,
                    requestCode,
                    permissions,
                    grantResults,
                )
            } else {
                Toast.makeText(
                        requireSettingsActivity(),
                        getString(R.string.no_storage_permission, BuildConfig.APP_NAME),
                        Toast.LENGTH_SHORT,
                    )
                    .show()
            }
        }
    }

    /** A `SwitchPreferenceCompat` over one of the module `@bool` defaults. */
    private fun switch(
        key: String,
        title: Int,
        summary: Int,
        icon: Int,
        defaultRes: Int,
    ): PreferenceItem = switchItem(key, title, summary, icon, resources.getBoolean(defaultRes))

    /**
     * A `ListPreference` from two string arrays. When [simpleSummary] is true the row's summary is its
     * selected entry, which is what `app:useSimpleSummaryProvider` did; otherwise it is [summary].
     */
    private fun list(
        key: String,
        title: Int,
        summary: Int?,
        icon: Int,
        entriesRes: Int,
        valuesRes: Int,
        default: String,
        dialogTitle: Int?,
        simpleSummary: Boolean,
    ): PreferenceItem {
        val (labels, values) = listFromArrays(entriesRes, valuesRes)
        val value = preferences.getString(key, default)
        return PreferenceItem(
            key = key,
            title = getString(title),
            summary = if (simpleSummary) listSummary(labels, values, value) else summary?.let { getString(it) },
            icon = icon,
            kind = PreferenceItem.Kind.LIST,
            labels = labels,
            values = values,
            value = value,
            dialogTitle = dialogTitle?.let { getString(it) },
            defaultValue = default,
        )
    }

    /** A `ColorPreference`: the library's picker, the stored ARGB int, the `@color` default. */
    private fun colour(
        key: String,
        title: Int,
        defaultColourRes: Int,
        visible: Boolean = true,
    ): PreferenceItem {
        val default = ContextCompat.getColor(requireContext(), defaultColourRes)
        return PreferenceItem(
            key = key,
            title = getString(title),
            icon = R.drawable.ic_palette_24dp,
            kind = PreferenceItem.Kind.COLOUR,
            colour = preferences.getInt(key, default),
            defaultColour = default,
            visible = visible,
        )
    }

    private fun header(title: Int): PreferenceItem =
        PreferenceItem(title = getString(title), kind = PreferenceItem.Kind.HEADER)

    companion object {
        private const val CUSTOM_THEME_AUTOMATIC = "custom_theme_automatic"
        private const val CUSTOM_THEME_DARK = "custom_theme_dark"
        private const val CUSTOM_THEME_COLOR_MATCH = "custom_theme_color_match"
        private const val CUSTOM_THEME_PRIMARY = "custom_theme_primary"
        private const val CUSTOM_THEME_PRIMARY_DARK = "custom_theme_primary_dark"
        private const val CUSTOM_THEME_ACCENT = "custom_theme_accent"
        private const val CUSTOM_THEME_BACKGROUND_PRIMARY = "custom_theme_background_primary"
        private const val CUSTOM_DARK_THEME_PRIMARY = "custom_dark_theme_primary"
        private const val CUSTOM_DARK_THEME_PRIMARY_DARK = "custom_dark_theme_primary_dark"
        private const val CUSTOM_DARK_THEME_ACCENT = "custom_dark_theme_accent"
        private const val CUSTOM_DARK_THEME_BACKGROUND_PRIMARY = "custom_dark_theme_background_primary"
        private const val IMPORT_BACKGROUND = "import_background"
        private const val DELETE_BACKGROUND = "delete_background"
        private const val UNICOLORED_CHATBG = "unicolored_chatbg"
        private const val SHOW_CONTACT_STATUS = "show_contact_status"
        private const val ALWAYS_FULL_TIMESTAMPS = "always_full_timestamps"
        private const val SHOW_OWN_ACCOUNTS = "show_own_accounts"
        private const val PINNED_STATUS_MESSAGE = "pinned_status_message"
        private const val COLORED_MUC_NAMES = "colored_muc_names"
        private const val SHOW_NAV_BAR = "show_nav_bar"
        private const val SHOW_NAV_DRAWER = "show_nav_drawer"
        private const val AVATAR_SHAPE = "avatar_shape"
        private const val LARGE_FONT = "large_font"
        private const val SHOW_DYNAMIC_TAGS = "show_dynamic_tags"
        private const val PLAIN_TEXT_LINKS = "plain_text_links"
        private const val SET_TEXT_COLLAPSABLE = "set_text_collapsable"
        private const val CUSTOM_TAB = "custom_tab"
        private const val SWIPE_TO_ARCHIVE = "swipe_to_archive"
        private const val SHOW_THREAD_FEATURE = "show_thread_feature"
        private const val SHOW_TEXT_FORMATTING = "showtextformatting"
        private const val QUICK_ACTION = "quick_action"
        private const val SCROLL_TO_BOTTOM = "scroll_to_bottom"
        private const val START_SEARCHING = "start_searching"
        private const val FOLLOW_THREAD_IN_CHANNEL = "follow_thread_in_channel"
        private const val JUMP_TO_COMMANDS_TAB = "jump_to_commands_tab"
        private const val COMPOSE_RICH_TEXT = "compose_rich_text"
        private const val SHOW_MUC_PM = "show_muc_pm"
        private const val DISPLAY_ENTER_KEY = "display_enter_key"
        private const val ENTER_IS_SEND = "enter_is_send"

        private const val REQUEST_EDIT_BACKGROUND = 9124

        /** The eleven keys whose change repaints the theme and recreates the Activity. */
        private val CUSTOM_THEME_KEYS =
            setOf(
                CUSTOM_THEME_AUTOMATIC,
                CUSTOM_THEME_DARK,
                CUSTOM_THEME_COLOR_MATCH,
                CUSTOM_THEME_PRIMARY,
                CUSTOM_THEME_PRIMARY_DARK,
                CUSTOM_THEME_ACCENT,
                CUSTOM_THEME_BACKGROUND_PRIMARY,
                CUSTOM_DARK_THEME_PRIMARY,
                CUSTOM_DARK_THEME_PRIMARY_DARK,
                CUSTOM_DARK_THEME_ACCENT,
                CUSTOM_DARK_THEME_BACKGROUND_PRIMARY,
            )
    }
}
