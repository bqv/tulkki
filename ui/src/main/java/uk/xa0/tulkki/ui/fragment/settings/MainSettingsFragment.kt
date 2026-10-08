package uk.xa0.tulkki.ui.fragment.settings

import android.content.Intent

import com.google.common.base.Strings

import uk.xa0.tulkki.translation.TranslationSettings
import uk.xa0.tulkki.ui.AboutActivity
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.TulkkiSettingsFragment
import uk.xa0.tulkki.ui.preferences.PreferenceItem
import uk.xa0.tulkki.ui.preferences.PreferenceScreenFragment
import uk.xa0.tulkki.ui.settings.MainSettingsPage

/**
 * The settings list `preferences_main.xml` described: nine doors and the about row, with the file
 * deleted with this class.
 *
 * <p>The rows themselves are [MainSettingsPage]'s - one function of one `Context` and three booleans,
 * so the screenshot cells render the list this screen draws and not a second copy of it. This class is
 * the host: it reads the three booleans, and it is what a row's key means.
 *
 * <p>The two rows with a live reading keep it: the connection row's summary follows
 * [ConnectionSettingsFragment.hideChannelDiscovery], and the Tulkki row's follows the interpreter,
 * which is read per render rather than cached, so this screen says the off state the moment the owner
 * comes back from the trip that changed it.
 *
 * <p>`android:copyingEnabled` on the about row is kept by the engine: a long press copies the build
 * line, which is how the owner has always read this build back to a bug report.
 */
class MainSettingsFragment : PreferenceScreenFragment() {

    override fun buildItems(): List<PreferenceItem> =
        MainSettingsPage.items(
            context = requireContext(),
            // `TranslationSettings.get(requireContext()).interpreter().enabled()`, read live.
            interpreterEnabled = TranslationSettings.get(requireContext()).interpreter().enabled(),
            channelDiscoveryHidden = ConnectionSettingsFragment.hideChannelDiscovery(),
            // `up.isVisible = !Strings.isNullOrEmpty(getString(R.string.default_push_server))`: a row
            // the build's push server removed is not drawn at all.
            upVisible = !Strings.isNullOrEmpty(getString(R.string.default_push_server)),
        )

    override fun onPreferenceClick(item: PreferenceItem) {
        when (item.key) {
            MainSettingsPage.KEY_INTERFACE -> openScreen(InterfaceSettingsFragment())
            MainSettingsPage.KEY_SECURITY -> openScreen(SecuritySettingsFragment())
            MainSettingsPage.KEY_PRIVACY -> openScreen(PrivacySettingsFragment())
            MainSettingsPage.KEY_NOTIFICATIONS -> openScreen(NotificationsSettingsFragment())
            MainSettingsPage.KEY_ATTACHMENTS -> openScreen(AttachmentsSettingsFragment())
            MainSettingsPage.KEY_CONNECTION -> openScreen(ConnectionSettingsFragment())
            MainSettingsPage.KEY_BACKUP -> openScreen(BackupSettingsFragment())
            MainSettingsPage.KEY_UP -> openScreen(UpSettingsFragment())
            MainSettingsPage.KEY_TULKKI -> openScreen(TulkkiSettingsFragment())
            // The XML row's `<intent android:action="android.intent.action.VIEW"
            // android:targetClass="uk.xa0.tulkki.ui.AboutActivity" .../>`, started the same way.
            MainSettingsPage.KEY_ABOUT ->
                startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setClassName(requireContext(), AboutActivity::class.java.name)
                )
        }
    }

    override fun onStart() {
        super.onStart()
        requireActivity().setTitle(R.string.title_activity_settings)
    }
}
