package uk.xa0.tulkki.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext

/**
 * The one theme object the Compose tree hangs from.
 *
 * <p>docs/MIGRATION.md "Design: the Compose UI" §1.1: *"The scheme is a factory, not a
 * constant"* - the same inputs `ThemeHelper` already overrides `Theme.Tulkki` with at runtime,
 * namely the stored theme preference, the custom-theme colours and the `dynamic_colors` switch
 * (Material You, where the platform offers it).
 *
 * <p>What is here is the half that needs no settings layer: the two hand-mirrored maps in
 * [TulkkiColors], the platform's dynamic scheme, and the extended tokens.  The custom-theme
 * colours arrive with the settings abstraction (`ui-4`/`ui-7`), which is also where the stored
 * `theme` preference (`dark` by default, `automatic` / `light` / `custom` as well) is resolved into
 * [darkTheme]; §1.1 names that input `customColours` without ever defining its type, so nothing is
 * invented for it here.
 *
 * <p>`darkTheme` defaults to `true` because `dark` is the stored preference's default
 * (`AGENTS.md`, "Dark by default") and not because the system's setting is ignored: a caller with
 * the resolved preference always passes it.
 *
 * <p>Note that this is deliberately *not* `MODE_NIGHT_YES` forced on the XML side: `Theme.Tulkki`
 * cannot be deleted until the last XML screen is gone (§Theme), so the two themes coexist and this
 * one is parallel, not a replacement.
 */
@Composable
fun TulkkiTheme(
    darkTheme: Boolean = true,
    dynamicColors: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme =
        when {
            dynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            darkTheme -> TulkkiColors.dark
            else -> TulkkiColors.light
        }
    CompositionLocalProvider(
        LocalTulkkiColors provides TulkkiColors.tokensOf(scheme, darkTheme),
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
