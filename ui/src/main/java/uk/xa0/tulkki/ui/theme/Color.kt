package uk.xa0.tulkki.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The design system's colour half: the Material 3 scheme and the extended token set.
 *
 * <p>docs/MIGRATION.md "Design: the Compose UI" §1.1 states the shape: *"Two value maps, one key
 * set. `TulkkiColors.dark` and `TulkkiColors.light` are built from `md_theme_dark_*` /
 * `md_theme_light_*` in `values/colors-themed.xml`"*, and *"the XML is the source of truth during
 * the migration ... the two Kotlin maps are hand-mirrored, and a JVM test keeps them honest"*
 * (`TokenParityTest`).  So the slots below are literals, not `R.color` lookups: a JVM screenshot or
 * layout test has no Android runtime to resolve a resource in, which is the whole reason the maps
 * are mirrored rather than read.  `TokenParityTest` parses the two XML files as text and fails when
 * a literal above and the XML value it names have drifted apart.
 *
 * <p>`shadow` is mirrored in both maps and is deliberately *not* a scheme field: §1.2 lists it among
 * the slots that are "as in the XML", but `darkColorScheme`/`lightColorScheme` in the pinned
 * material3 1.4.0 have no `shadow` parameter at all (it left the scheme's public API), so the value
 * stays in the maps for the one real shadow §1.6 names (`toolbar_elevation`) and
 * `TokenParityTest` records it as the one slot the library dropped rather than a gap.
 *
 * <p>`chatBackground` (§1.2's last extended token) is deliberately absent, and now for a reason
 * that needs no painter at all: upstream's value in both `themes.xml` files was a *drawable*
 * (`chat_bg`, upstream's tiled branded watermark), and that wallpaper is deleted - a conversation with
 * no image of its own draws on the theme's own surface, which the scheme already carries as a
 * `Color`. So there is no separate token here and no resource for a screen to paint.
 */
object TulkkiColors {

    /**
     * The M3 slots, keyed by the scheme's own property names - the key set §1.1 calls "one key
     * set", so `darkSlots.keys == lightSlots.keys` and each key names both a ColorScheme property
     * and the suffix of the `md_theme_<light|dark>_<key>` XML colour it mirrors.
     */
    val darkSlots: Map<String, Color> =
        mapOf(
            "primary" to Color(0xFF7886B4),
            "onPrimary" to Color(0xFF2C4576),
            "primaryContainer" to Color(0xFF3E5EA2),
            "onPrimaryContainer" to Color(0xFF7984A8),
            "secondary" to Color(0xFF7188C3),
            "onSecondary" to Color(0xFF3E5EA2),
            "secondaryContainer" to Color(0xFF1B2842),
            "onSecondaryContainer" to Color(0xFFA1BAFA),
            "tertiary" to Color(0xFFB6C7F4),
            "onTertiary" to Color(0xFF2A3E64),
            // The two 8-digit ARGB values §1.1 (b) names: alpha is part of the colour, so the
            // literal is the XML's own 0x9C8596BF and not an opaque 0xFF prepended to it.
            "tertiaryContainer" to Color(0x9C8596BF),
            "onTertiaryContainer" to Color(0xFF101828),
            "error" to Color(0xFFFFB4AB),
            "errorContainer" to Color(0xFF93000A),
            "onError" to Color(0xFF690005),
            "onErrorContainer" to Color(0xFFFFDAD6),
            "background" to Color(0xFF1B1B1E),
            "onBackground" to Color(0xFFE1E2E7),
            "surface" to Color(0xFF1D1B1E),
            "onSurface" to Color(0xFFE1E2E7),
            "surfaceVariant" to Color(0xFF45464E),
            "onSurfaceVariant" to Color(0xFFC4C5CF),
            "outline" to Color(0xFF8E9098),
            "inverseOnSurface" to Color(0xFF191A1C),
            "inverseSurface" to Color(0xFFDDE3E3),
            "inversePrimary" to Color(0xFF006E1C),
            "shadow" to Color(0xFF000000),
            "surfaceTint" to Color(0xFF7DDC7A),
            "outlineVariant" to Color(0xFF424940),
            "scrim" to Color(0xFF000000),
        )

    /** The light half of the same key set.  The half that rots, because nobody looks at it. */
    val lightSlots: Map<String, Color> =
        mapOf(
            "primary" to Color(0xFF3E5EA2),
            "onPrimary" to Color(0xFFFFFFFF),
            "primaryContainer" to Color(0xFF7188C3),
            "onPrimaryContainer" to Color(0xFFFFFFFF),
            "secondary" to Color(0xFF283D6A),
            "onSecondary" to Color(0xFFFFFFFF),
            "secondaryContainer" to Color(0xFFDAE0FF),
            "onSecondaryContainer" to Color(0xFF3E5EA2),
            "tertiary" to Color(0xFF001676),
            "onTertiary" to Color(0xFFFFFFFF),
            "tertiaryContainer" to Color(0x9CB9CDFF),
            "onTertiaryContainer" to Color(0xFF251A00),
            "error" to Color(0xFFBA1A1A),
            "errorContainer" to Color(0xFFFFDAD6),
            "onError" to Color(0xFFFFFFFF),
            "onErrorContainer" to Color(0xFF410002),
            "background" to Color(0xFFFBFCFF),
            "onBackground" to Color(0xFF1B1C1E),
            "surface" to Color(0xFFFBFCFF),
            "onSurface" to Color(0xFF1B1B1E),
            "surfaceVariant" to Color(0xFFE0E1EB),
            "onSurfaceVariant" to Color(0xFF45474E),
            "outline" to Color(0xFF75777F),
            "inverseOnSurface" to Color(0xFFEBEDF1),
            "inverseSurface" to Color(0xFF2D3131),
            "inversePrimary" to Color(0xFF7ADCDA),
            "shadow" to Color(0xFF000000),
            "surfaceTint" to Color(0xFF006E63),
            "outlineVariant" to Color(0xFFBDC8C9),
            "scrim" to Color(0xFF000000),
        )

    /** Dark is the app's default (`AGENTS.md` "Dark by default"); light is a real tested option. */
    val dark: ColorScheme = darkSchemeOf(darkSlots)

    val light: ColorScheme = lightSchemeOf(lightSlots)

    /**
     * The presence dot's three colours, and the states that have none.
     *
     * <p>These are the tree's own values and deliberately **not** scheme roles: the deleted
     * `PresenceIndicator` drew `UIHelper.getColorForStatus`'s fixed green / orange / red in both themes
     * (`ui/src/main/java/uk/xa0/tulkki/ui/utils/UIHelper.java:637-650`), and a role would change hue with
     * the theme and stop matching the indicator the owner is comparing against. They are here rather than
     * in the screen because §1.8 rule 1 allows a colour literal in a Composable nowhere.
     *
     * <p>There is no `presenceOffline`: `getColorForStatus` answers `null` for `OFFLINE` and the old
     * indicator drew no dot at all, so "offline" and "unknown" are the same drawing - nothing - while
     * staying different states on the row. `UIHelper.java:637-650` is the source, and
     * `ConversationListScreenTest` reads it back as text so the three numbers cannot drift.
     */
    val presenceOnline: Color = Color(0xFF259B24)

    val presenceAway: Color = Color(0xFFFF9800)

    val presenceDnd: Color = Color(0xFFF44336)

    /** The extended tokens (§1.2) for a scheme, so a Composable never names a `Color` of its own. */
    fun tokensOf(scheme: ColorScheme, darkTheme: Boolean): TulkkiColorTokens =
        TulkkiColorTokens(
            bubbleSurface = scheme.surface,
            bubbleSurfaceHigh = scheme.surfaceVariant,
            bubblePrimary = scheme.primaryContainer,
            onBubblePrimary = scheme.onPrimaryContainer,
            bubbleSecondary = scheme.secondaryContainer,
            onBubbleSecondary = scheme.onSecondaryContainer,
            bubbleTertiary = scheme.tertiaryContainer,
            onBubbleTertiary = scheme.onTertiaryContainer,
            bubbleWarning = scheme.errorContainer,
            onBubbleWarning = scheme.onErrorContainer,
            // The two palette entries that are not scheme slots: colors-md.xml's `green_300` /
            // `green_600` and `amber_300` / `amber_700`.
            success = if (darkTheme) Color(0xFF81C784) else Color(0xFF43A047),
            warning = if (darkTheme) Color(0xFFFFD54F) else Color(0xFFFFA000),
            shimmerBase = scheme.surfaceVariant,
            shimmerHighlight = scheme.onSurface.copy(alpha = 0.06f),
            coverStrip = scheme.surfaceVariant,
            divider = scheme.outlineVariant,
            unreadAccent = scheme.primary,
        )

    private fun darkSchemeOf(slots: Map<String, Color>): ColorScheme =
        darkColorScheme(
            primary = slots.getValue("primary"),
            onPrimary = slots.getValue("onPrimary"),
            primaryContainer = slots.getValue("primaryContainer"),
            onPrimaryContainer = slots.getValue("onPrimaryContainer"),
            inversePrimary = slots.getValue("inversePrimary"),
            secondary = slots.getValue("secondary"),
            onSecondary = slots.getValue("onSecondary"),
            secondaryContainer = slots.getValue("secondaryContainer"),
            onSecondaryContainer = slots.getValue("onSecondaryContainer"),
            tertiary = slots.getValue("tertiary"),
            onTertiary = slots.getValue("onTertiary"),
            tertiaryContainer = slots.getValue("tertiaryContainer"),
            onTertiaryContainer = slots.getValue("onTertiaryContainer"),
            background = slots.getValue("background"),
            onBackground = slots.getValue("onBackground"),
            surface = slots.getValue("surface"),
            onSurface = slots.getValue("onSurface"),
            surfaceVariant = slots.getValue("surfaceVariant"),
            onSurfaceVariant = slots.getValue("onSurfaceVariant"),
            surfaceTint = slots.getValue("surfaceTint"),
            inverseSurface = slots.getValue("inverseSurface"),
            inverseOnSurface = slots.getValue("inverseOnSurface"),
            error = slots.getValue("error"),
            onError = slots.getValue("onError"),
            errorContainer = slots.getValue("errorContainer"),
            onErrorContainer = slots.getValue("onErrorContainer"),
            outline = slots.getValue("outline"),
            outlineVariant = slots.getValue("outlineVariant"),
            scrim = slots.getValue("scrim"),
        )

    private fun lightSchemeOf(slots: Map<String, Color>): ColorScheme =
        lightColorScheme(
            primary = slots.getValue("primary"),
            onPrimary = slots.getValue("onPrimary"),
            primaryContainer = slots.getValue("primaryContainer"),
            onPrimaryContainer = slots.getValue("onPrimaryContainer"),
            inversePrimary = slots.getValue("inversePrimary"),
            secondary = slots.getValue("secondary"),
            onSecondary = slots.getValue("onSecondary"),
            secondaryContainer = slots.getValue("secondaryContainer"),
            onSecondaryContainer = slots.getValue("onSecondaryContainer"),
            tertiary = slots.getValue("tertiary"),
            onTertiary = slots.getValue("onTertiary"),
            tertiaryContainer = slots.getValue("tertiaryContainer"),
            onTertiaryContainer = slots.getValue("onTertiaryContainer"),
            background = slots.getValue("background"),
            onBackground = slots.getValue("onBackground"),
            surface = slots.getValue("surface"),
            onSurface = slots.getValue("onSurface"),
            surfaceVariant = slots.getValue("surfaceVariant"),
            onSurfaceVariant = slots.getValue("onSurfaceVariant"),
            surfaceTint = slots.getValue("surfaceTint"),
            inverseSurface = slots.getValue("inverseSurface"),
            inverseOnSurface = slots.getValue("inverseOnSurface"),
            error = slots.getValue("error"),
            onError = slots.getValue("onError"),
            errorContainer = slots.getValue("errorContainer"),
            onErrorContainer = slots.getValue("onErrorContainer"),
            outline = slots.getValue("outline"),
            outlineVariant = slots.getValue("outlineVariant"),
            scrim = slots.getValue("scrim"),
        )
}

/**
 * §1.2's extended tokens, each defined in both maps.
 *
 * <p>A token exists because a screen needs it; there is no per-account bubble palette and no new
 * colour invented here (`IDEA-SCAN §5.1`).  The tokens that are simply a scheme slot (a bubble
 * surface, the divider, the unread accent) are *derived* in [TulkkiColors.tokensOf] rather than
 * declared twice, so the two maps cannot disagree with the scheme they belong to.
 */
data class TulkkiColorTokens(
    val bubbleSurface: Color,
    val bubbleSurfaceHigh: Color,
    val bubblePrimary: Color,
    val onBubblePrimary: Color,
    val bubbleSecondary: Color,
    val onBubbleSecondary: Color,
    val bubbleTertiary: Color,
    val onBubbleTertiary: Color,
    val bubbleWarning: Color,
    val onBubbleWarning: Color,
    val success: Color,
    val warning: Color,
    val shimmerBase: Color,
    val shimmerHighlight: Color,
    val coverStrip: Color,
    val divider: Color,
    val unreadAccent: Color,
)

/**
 * The extended tokens, provided by [TulkkiTheme].  Read as `LocalTulkkiColors.current.divider`, never
 * as a colour literal: §1.8 rule 1 fails a Composable that writes `Color(0x…)` or `R.color.`.
 */
val LocalTulkkiColors =
    staticCompositionLocalOf { TulkkiColors.tokensOf(TulkkiColors.light, darkTheme = false) }

/**
 * The image editor's own colours, and the reason §1.8 rule 1 has an exception here.
 *
 * <p>The editor is the screen that draws on top of a photograph, so its chrome is deliberately fixed
 * rather than scheme-derived: the deleted `activity_edit.xml` and its bottom bars were `@color/black26`
 * (`#42000000`) over a black canvas in both themes, with white icons and labels. The three literals are
 * those resources; they are here, not in the screen, because §1.8 rule 1 allows a colour literal in a
 * Composable nowhere.
 */
object TulkkiEditorColors {
    /** The surface behind the image, the XML's black editor root. */
    val background: Color = Color(0xFF000000)

    /** `@color/black26`, every bar's translucent chrome. */
    val chrome: Color = Color(0x42000000)

    /** White, the icons and labels over [chrome]. */
    val onChrome: Color = Color(0xFFFFFFFF)

    /**
     * The colour picker's square: the white, black and clear its two gradients are built from.
     *
     * <p>They are the vendored `medialib.views.ColorPickerSquare`'s own three, which it composed with
     * `PorterDuff.Mode.MULTIPLY`: `LinearGradient(WHITE, BLACK)` down and `LinearGradient(WHITE, hue)`
     * across. The Compose square draws the same three layers, so the values move here with it.
     */
    val pickerWhite: Color = Color(0xFFFFFFFF)

    val pickerBlack: Color = Color(0xFF000000)

    val pickerClear: Color = Color(0x00000000)
}
