package uk.xa0.tulkki.xmpp.services

import android.content.Context
import androidx.annotation.StyleRes

/**
 * Tulkki: the theme this service puts itself into, and the custom colours that go with it.
 *
 * <p>The id is not the service's: `Theme.Tulkki` is `:ui`'s style - it lives in that module's
 * `values/themes.xml` and `values-night/themes.xml` and three child styles inherit from it - so
 * after the split `:xmpp` defines no such style and the id arrives from the composition root
 * through this port, exactly as the custom-colour call already did. {@code :app} hands the
 * static R field in rather than resolving the style by name, so a rename or a deletion is a
 * build error instead of a silent {@code setTheme(0)}
 * (the 3.8-r remaining hand-work commit cebe3e9388, section 4).
 */
interface ThemePort {

    @StyleRes fun theme(): Int

    fun applyCustomColors(context: Context)
}
