package uk.xa0.tulkki.ui.conversation

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert
import org.junit.Test

/**
 * The composer bar's *drawn shape*, pinned in the source it lives in - a **source cell**, for the
 * reason `DoubtHoldSurfaceTest` is one: the composer is a Composable no JVM unit-test runtime builds,
 * and what this pin protects is the owner's report that the bar was shouting.
 *
 * <p>Three facts the rework turned on, each of which can silently come back:
 *
 * <ul>
 *   <li>**The language block is one compact size in every state.** Its conversation slot draws
 *       `LanguageChip.tiers`' own second member - a code, or `LanguageChip.UNKNOWN`'s three `?` glyphs
 *       for "not known" - and the wording of the unset state lives in the chip's spoken name, not in a
 *       second visible line. Drawing `tulkki_pair_they_unknown` in that slot is exactly the wide,
 *       lopsided block the owner measured.
 *   <li>**Item 16's switch is the small chip in the composer's control row, above the field.** It is the
 *       composer's own shape with a check when the hold is in force, and it still writes
 *       `onDoubtHoldChanged`; the full-width labelled row it replaced must not come back.
 *   <li>**The chip and the language block are drawn above the field, never as fixed children of its
 *       row.** A `Row` measures its fixed children first and hands the weighted draft what is left, and
 *       at 360 dp the two of them beside four `IconButton`s left the field 2 dp and wrapped the hint
 *       down the middle of the row - the owner's "mate. where do i type".
 * </ul>
 *
 * <p>These are shape pins, not behaviour pins: the vocabulary (`ConversationEvents`' two verbs), the
 * resolution (`UiDoubtHold`) and the hold rules (`:translation`) have their own cells.
 */
class ComposerBarShapeTest {

    private val composer = "ui/src/main/java/uk/xa0/tulkki/ui/conversation/ConversationComposer.kt"

    @Test
    fun theLanguageBlockDrawsTheRulesTwoTiersAndNotASentence() {
        val drawing = code(composer)
        Assert.assertTrue(
            "the two tiers are the LanguageChip rule, not two locals a rewrite could swap",
            drawing.contains("LanguageChip.tiers(") &&
                drawing.contains("text = tiers.app") &&
                drawing.contains("text = tiers.conversation"),
        )
        Assert.assertFalse(
            "the visible conversation slot is the tier the rule answered, never the long sentence",
            drawing.contains("R.string.tulkki_pair_they_unknown"),
        )
        Assert.assertTrue(
            "the \"not known yet\" wording is the chip's spoken name, so the state is still named",
            drawing.contains("R.string.tulkki_language_chip_content_unknown"),
        )
        Assert.assertTrue(
            "the tap is still the picker's verb, on the block the owner taps",
            drawing.contains("events.onLanguageChipTap()"),
        )
    }

    @Test
    fun theLanguageBlockHasOneMinimumFootprintFromTheTheme() {
        val drawing = code(composer)
        Assert.assertTrue(
            "the block's minimum is the theme step that holds the three-glyph marker, so the marker " +
                "and a code occupy the same box",
            drawing.contains("widthIn(min = TulkkiSpacing.xl)"),
        )
        Assert.assertFalse(
            "and it is not a dimension literal, which §1.8 rule 1 forbids in a Composable",
            Regex("widthIn\\(min = [0-9]").containsMatchIn(drawing),
        )
    }

    @Test
    fun theDoubtHoldIsTheComposersOwnSmallChip() {
        val drawing = code(composer)
        Assert.assertTrue(
            "the control is a chip, the composer's own vocabulary rather than a second bar",
            drawing.contains("FilterChip("),
        )
        Assert.assertTrue(
            "and it reads and writes the value in force, never the stored tri-state",
            drawing.contains("selected = hold.inForce") && drawing.contains("onChanged(!hold.inForce)"),
        )
        Assert.assertFalse(
            "the full-width labelled switch row it replaced must not come back",
            drawing.contains("Switch(checked = hold.inForce"),
        )
        Assert.assertFalse(
            "nor may the label take the row's whole width again",
            drawing.contains("Row( modifier = modifier.fillMaxWidth().padding(bottom = TulkkiSpacing.xs)"),
        )
    }

    /**
     * The regression's own shape, in the source: the two fixed children that ate the draft draw in the
     * composer's control row, and the input row is the weighted field beside its `IconButton`s. This is
     * the source half of the pin - it cannot measure dp, so the arithmetic lives in the composer's own
     * KDoc - and the 360 dp screenshot cell is the picture.
     *
     * <p>Why it is a cell at all: a `Row` measures its fixed children first and hands the weighted
     * field what is left, so a control that moves into the row silently takes the draft's room without
     * changing a single line the compiler checks. The owner's report was exactly that, so the placement
     * is the fact worth pinning.
     */
    @Test
    fun theLanguageBlockAndTheDoubtChipDrawAboveTheFieldNotBesideIt() {
        val drawing = code(composer)
        val controls = body(drawing, "private fun ComposerControls(")
        val inputRow = body(drawing, "private fun ComposerInputRow(")
        Assert.assertTrue(
            "the control row draws both fixed children: the pair and the hold switch",
            controls.contains("LanguagePairBlock(pair, events)") &&
                controls.contains("ConversationDoubtHold(hold, events::onDoubtHoldChanged)"),
        )
        Assert.assertTrue(
            "and the input row keeps the field, weighted, so it takes what the fixed children leave",
            inputRow.contains("DraftField(") && inputRow.contains("modifier = Modifier.weight(1f)"),
        )
        Assert.assertFalse(
            "neither fixed child is measured in the input row: at 360 dp the two of them beside four " +
                "48 dp IconButtons left the field 2 dp, which is the owner's report",
            inputRow.contains("LanguagePairBlock(") ||
                inputRow.contains("ConversationDoubtHold(") ||
                inputRow.contains("FilterChip("),
        )
        Assert.assertTrue(
            "the control row is drawn first, so it is the row above the field",
            drawing.indexOf("ComposerControls(composer, events)") in
                1 until drawing.indexOf("ComposerInputRow(composer, events)"),
        )
    }

    @Test
    fun theDoubtChipsVisibleWordIsShortAndItsSpokenNameIsTheWholeSentence() {
        val drawing = code(composer)
        Assert.assertTrue(
            "the full sentence is the chip's accessible name, so the setting is still named",
            drawing.contains("val words = stringResource(R.string.tulkki_doubt_hold)") &&
                drawing.contains("contentDescription = words"),
        )
        Assert.assertTrue(
            "and the visible word is the chip's own short string, not the sentence again",
            drawing.contains("R.string.tulkki_doubt_hold_chip"),
        )
        Assert.assertTrue(
            "the project ships that short word",
            code("ui/src/main/res/values/strings_tulkki.xml")
                .contains("<string name=\"tulkki_doubt_hold_chip\">"),
        )
    }

    /** The file's source with its comments removed, flattened: one line, so a token cannot straddle. */
    private fun code(relative: String): String =
        String(Files.readAllBytes(root().resolve(relative)), StandardCharsets.UTF_8)
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("//[^\n]*"), " ")
            .replace(Regex("\\s+"), " ")

    /**
     * One declaration's body from the flattened source, by brace balance: the flattened text has no
     * comments and no braces in string literals here, so the first `{` after the header opens it and
     * the matching `}` closes it. Slicing by the *next* declaration would be the brittle version - the
     * public composables between the two rows have no `private fun` header to stop at.
     */
    private fun body(drawing: String, header: String): String {
        val start = drawing.indexOf(header)
        Assert.assertTrue("$header is declared in the composer", start >= 0)
        val open = drawing.indexOf('{', start)
        var depth = 0
        for (index in open until drawing.length) {
            when (drawing[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return drawing.substring(open + 1, index)
                }
            }
        }
        throw AssertionError("unbalanced braces after $header")
    }

    /** The checkout root, found by its `settings.gradle.kts` marker rather than by the first `src`. */
    private fun root(): Path {
        var directory: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        for (level in 0 until 6) {
            if (directory != null &&
                    (Files.isRegularFile(directory.resolve("settings.gradle.kts"))
                            || Files.isRegularFile(directory.resolve("settings.gradle")))) {
                return directory
            }
            directory = directory?.parent
        }
        throw AssertionError("could not find settings.gradle.kts above ${System.getProperty("user.dir")}")
    }
}
