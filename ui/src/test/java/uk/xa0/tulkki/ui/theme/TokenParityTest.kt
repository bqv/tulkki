package uk.xa0.tulkki.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import java.io.File
import org.junit.Assert
import org.junit.Test

/**
 * The JVM test docs/MIGRATION.md "Design: the Compose UI" §1.1 asks for by name, and the reason the
 * two maps in [TulkkiColors] may be literals at all.
 *
 * <p>§1.1: *"The XML is the source of truth during the migration ... So the two Kotlin maps are
 * hand-mirrored, and a JVM test keeps them honest: `TokenParityTest` parses `values/colors-themed.xml`,
 * `values/themes.xml` and `values-night/themes.xml` as plain files (no Android runtime needed) and
 * asserts (a) every key in the Kotlin dark map exists in the light map and vice versa, (b) each
 * Kotlin value equals the XML value it came from, including the two 8-digit ARGB values that already
 * exist ... (c) the Compose scheme's key set equals the set of `colorX` attributes the two
 * `themes.xml` files actually set."*
 *
 * <p>All three are here, and (c) carries the allow-list §1.1 sanctions: `shadow`, `scrim`,
 * `surfaceTint` and `outlineVariant` are in `colors-themed.xml` but set by no `themes.xml`
 * attribute, and the paragraph records why - a template leftover no component may use until a call
 * site needs it, and then the call site is named here.  (`shadow` is also the one of the four the
 * pinned material3's `ColorScheme` has no property for, which is the other allow-list below.)
 */
class TokenParityTest {

    @Test
    fun bothSlotMapsCarryTheSameKeySet() {
        Assert.assertFalse("the dark map is empty", TulkkiColors.darkSlots.isEmpty())
        Assert.assertFalse("the light map is empty", TulkkiColors.lightSlots.isEmpty())
        Assert.assertEquals(
            "dark and light must be one key set (§1.1 (a)); the half that rots is light",
            TulkkiColors.darkSlots.keys,
            TulkkiColors.lightSlots.keys,
        )
    }

    @Test
    fun everyMirroredSlotIsTheThemesColorItCameFrom() {
        val themed = colorsIn(read(THEMED))
        for ((mode, slots) in listOf("dark" to TulkkiColors.darkSlots, "light" to TulkkiColors.lightSlots)) {
            for ((key, color) in slots) {
                val name = "md_theme_${mode}_$key"
                val hex = themed[name]
                Assert.assertNotNull("colors-themed.xml defines no $name", hex)
                Assert.assertEquals(name, argb(hex!!), color.toArgb())
            }
        }
    }

    @Test
    fun everyThemeColorInTheFileIsMirrored() {
        val themed = colorsIn(read(THEMED))
        for ((name, _) in themed) {
            val mode = if (name.startsWith("md_theme_dark_")) "dark" else "light"
            val key = name.removePrefix("md_theme_${mode}_")
            val slots = if (mode == "dark") TulkkiColors.darkSlots else TulkkiColors.lightSlots
            Assert.assertTrue(
                "colors-themed.xml has $name but the $mode map has no \"$key\"",
                slots.containsKey(key),
            )
        }
    }

    @Test
    fun theTwoThemesXmlFilesResolveEveryColorTheySet() {
        val referenced = HashMap<String, MutableSet<String>>()
        for ((mode, file) in listOf("light" to LIGHT_THEME, "dark" to DARK_THEME)) {
            val refs =
                MD_THEME_REFERENCE.findAll(read(file))
                    .filter { it.groupValues[1] == mode }
                    .map { it.groupValues[2] }
                    .toMutableSet()
            Assert.assertFalse("$file references no md_theme colour at all", refs.isEmpty())
            referenced[mode] = refs
        }
        for ((mode, slots) in listOf("dark" to TulkkiColors.darkSlots, "light" to TulkkiColors.lightSlots)) {
            val refs = referenced.getValue(mode)
            for (key in refs) {
                Assert.assertTrue(
                    "$mode themes.xml sets a colour whose key \"$key\" is not in the $mode map",
                    slots.containsKey(key),
                )
            }
            val unused = slots.keys - refs - UNREFERENCED_IN_THEMES_XML
            Assert.assertTrue(
                "the $mode map mirrors $unused, which no themes.xml attribute sets and no call site "
                    + "names: delete it or add its call site to this test's allow-list (§1.1)",
                unused.isEmpty(),
            )
        }
    }

    @Test
    fun theTwoPaletteTokensAreTheColorsMdEntriesTheDesignNames() {
        val palette = colorsIn(read(PALETTE))
        val dark = TulkkiColors.tokensOf(TulkkiColors.dark, darkTheme = true)
        val light = TulkkiColors.tokensOf(TulkkiColors.light, darkTheme = false)
        Assert.assertEquals("colors-md.xml green_300", argb(palette.getValue("green_300")), dark.success.toArgb())
        Assert.assertEquals("colors-md.xml green_600", argb(palette.getValue("green_600")), light.success.toArgb())
        Assert.assertEquals("colors-md.xml amber_300", argb(palette.getValue("amber_300")), dark.warning.toArgb())
        Assert.assertEquals("colors-md.xml amber_700", argb(palette.getValue("amber_700")), light.warning.toArgb())
    }

    @Test
    fun eachSchemeIsBuiltFromItsOwnMapAndNoOther() {
        for ((mode, slots, scheme) in
            listOf(
                Triple("dark", TulkkiColors.darkSlots, TulkkiColors.dark),
                Triple("light", TulkkiColors.lightSlots, TulkkiColors.light),
            )) {
            var checked = 0
            for ((key, color) in slots) {
                val getter = "get" + key.replaceFirstChar { it.uppercase() }
                // A `Color` is an inline value class over a `ULong`, so the getter's JVM name is
                // mangled (`getPrimary-0d7_KjU`) and its return type is the primitive it erases to.
                // Both facts are Kotlin's, not this test's, and comparing the underlying packed
                // value is what makes the comparison a `Color` comparison all the same.
                val method =
                    ColorScheme::class.java.methods
                        .firstOrNull {
                            (it.name == getter || it.name.startsWith("$getter-")) &&
                                it.parameterCount == 0
                        }
                if (method == null) {
                    Assert.assertTrue(
                        "the $mode map mirrors \"$key\" but the pinned material3 ColorScheme has no "
                            + "such property, and it is not the one slot the library dropped",
                        key in NOT_A_SCHEME_SLOT,
                    )
                    continue
                }
                checked++
                Assert.assertEquals(
                    "the $mode scheme's $key is not the $mode map's value",
                    color.value.toLong(),
                    method.invoke(scheme) as Long,
                )
            }
            Assert.assertEquals(
                "the $mode scheme was not walked: only the dropped slot may be skipped",
                slots.size - NOT_A_SCHEME_SLOT.size,
                checked,
            )
        }
    }

    @Test
    fun theSlotsThePaletteDoesNotNameKeepTheirOwnPolarity() {
        // The palette names 30 slots and material3's builders have more: the `surfaceContainer*`
        // steps §1.6 puts cards on, `surfaceBright`/`surfaceDim` and the fixed roles take the
        // builder's own baseline, so they are the one place a light scheme can silently be a dark
        // one. Comparing every *mirrored* slot cannot see that (both builders are handed the same
        // 30 values); this cell is what a `darkSchemeOf(lightSlots)` slip has to fail.
        val pairs =
            listOf(
                "surfaceContainer" to (TulkkiColors.light.surfaceContainer to TulkkiColors.dark.surfaceContainer),
                "surfaceContainerHigh" to
                    (TulkkiColors.light.surfaceContainerHigh to TulkkiColors.dark.surfaceContainerHigh),
                "surfaceBright" to (TulkkiColors.light.surfaceBright to TulkkiColors.dark.surfaceBright),
            )
        for ((slot, colours) in pairs) {
            val (light, dark) = colours
            Assert.assertTrue(
                "the light scheme's $slot is not lighter than the dark scheme's, so one of the two "
                    + "was built by the other polarity's builder",
                light.luminance() > dark.luminance(),
            )
        }
    }

    /** Every `@color/md_theme_<mode>_<name>` a `themes.xml` sets, and nothing else. */
    private val MD_THEME_REFERENCE = Regex("@color/md_theme_(light|dark)_([A-Za-z]+)")

    /** The scheme slots §1.1's allow-list names: in both XML files, set by no `themes.xml` attr. */
    private val UNREFERENCED_IN_THEMES_XML = setOf("shadow", "scrim", "surfaceTint", "outlineVariant")

    /**
     * The one mirrored slot the pinned material3 has no `ColorScheme` property for: §1.2 says the
     * scheme carries `shadow`, and 1.4.0's `darkColorScheme`/`lightColorScheme` have no such
     * parameter, so it lives in the maps (with the XML value) and nowhere else.
     */
    private val NOT_A_SCHEME_SLOT = setOf("shadow")

    private fun colorsIn(xml: String): Map<String, String> =
        COLOR_ENTRY.findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }

    private val COLOR_ENTRY = Regex("<color name=\"([A-Za-z0-9_]+)\">#([0-9A-Fa-f]{6,8})</color>")

    /** `#RRGGBB` and `#AARRGGBB` both, because alpha is part of the colour (§1.1 (b)). */
    private fun argb(hex: String): Int = (if (hex.length == 6) "FF$hex" else hex).toLong(16).toInt()

    /**
     * AGP runs unit tests with the module directory as the working directory, so a lookup that
     * silently found nothing would look green while proving nothing - the search is positive and it
     * throws with the working directory it started from.
     */
    private fun read(relative: String): String {
        val start = File("").absoluteFile
        var dir: File? = start
        while (dir != null && !File(dir, "settings.gradle.kts").isFile &&
                !File(dir, "settings.gradle").isFile) {
            dir = dir.parentFile
        }
        val root = dir ?: throw AssertionError("no checkout root above $start")
        val file = File(root, relative)
        if (!file.isFile) {
            throw AssertionError("missing $file (working directory ${start.path})")
        }
        return file.readText()
    }

    private companion object {
        const val THEMED = "ui/src/main/res/values/colors-themed.xml"
        const val PALETTE = "ui/src/main/res/values/colors-md.xml"
        const val LIGHT_THEME = "ui/src/main/res/values/themes.xml"
        const val DARK_THEME = "ui/src/main/res/values-night/themes.xml"
    }
}
