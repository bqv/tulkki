package uk.xa0.tulkki.ui.projection

import androidx.compose.ui.graphics.ImageBitmap
import uk.xa0.tulkki.translation.DisplayedBody

/**
 * The typed body docs/MIGRATION.md "Design: the Compose UI" §2.2 gives in full, and §2.3 invariant 1
 * is written against: "a typed body, not a `String` ... the body is a closed type so a raw `String`
 * is not merely forbidden but *unavailable* to a composable, a `toString()`, a saved-state bundle or
 * a log line". The original is not a `String` anywhere in these two types: the only one that carries
 * text is [UiBody.Visible], and it carries the side that is *drawn*.
 *
 * <p>These live in `:ui` and not in `:data` for the reason "Design: the data layer" §2.7 gives:
 * [UiConcealment.Smear] holds an `ImageBitmap`, and `:data` may not depend on `:ui` (or on Compose).
 */
sealed interface UiBody {

    /** The app-language side, or a body that needed nothing: the only text a bubble may draw. */
    data class Visible(val text: String) : UiBody

    /** A body that is not drawn as text: pixels, or a placeholder with a reason. */
    data class Concealed(val concealment: UiConcealment) : UiBody

    /** Nothing to draw - a body that is not text at all (a transfer, a call, a system note). */
    data object Absent : UiBody
}

/**
 * Why a body is not readable, and what stands in its place.
 *
 * <p>§2.3 invariant 1 records the one deliberate deviation from `IDEA-SCAN §1.1`: "where 1.1
 * sketches `Concealed(key)`, ours is `Concealed(Smear(image))` - a bitmap, not a key. A key would
 * force the Composable to look the text up ... a bitmap contains no text to look up, cannot be read
 * back by `uiautomator` ... This is stated because it is a deliberate deviation."
 */
sealed interface UiConcealment {

    /**
     * The hidden second half, a concealed quote, or an English row: pixels only. [revealable] is
     * whether a tap may reveal it, which is the projection's answer and never a setting read here.
     */
    data class Smear(val image: ImageBitmap, val revealable: Boolean) : UiConcealment

    /**
     * Translation was needed and did not happen (pending, failed, capped, no key, refused): the
     * shimmer, and the reason it says for itself. [reason] is `:translation`'s own
     * [DisplayedBody.Cover] - the enum §1.7's `CoverCaption` row names ("`DisplayedBody.Cover` via
     * `TranslationText`") - because a second cover vocabulary in `:ui` would be a second answer to
     * the same question, and §2.3 invariant 2 makes the projector the one that asks it.
     *
     * <p>[revealable] is [Smear.revealable]'s own fact, and it is here **because the no-pixels path
     * must not lose it**: a host that cannot render a smear conceals with a placeholder, and if the
     * exception lived only on `Smear`, then whether a genuinely failed received message could offer
     * its original would depend on whether that host could draw pixels. The projector answers it for
     * both paths from the same gate, [OriginalReveal], and a cover's own placeholder never carries it
     * (a cover's tap is "translate this one, now", never "show the original").
     */
    data class Placeholder(
        val shape: PlaceholderShape,
        val reason: DisplayedBody.Cover?,
        val revealable: Boolean = false,
    ) : UiConcealment
}

/**
 * The shape of a body, for a `toString()` that prints "ids and shapes only" (§2.2's `UiMessage`
 * writes `${top.kindName()}`). It is a function and not a property on purpose: §2.3 invariant 1(a)
 * asks a test to prove the only `String`-typed *property* in these types is `Visible.text`, and the
 * name of a shape is not a body.
 */
fun UiBody.kindName(): String =
    when (this) {
        is UiBody.Visible -> "visible"
        is UiBody.Concealed -> "concealed"
        UiBody.Absent -> "absent"
    }

/** The same for the English row, whose shapes a bubble's log line may name and whose text it may not. */
fun UiEnglishRow.kindName(): String =
    when (this) {
        UiEnglishRow.Absent -> "absent"
        UiEnglishRow.Pending -> "pending"
        is UiEnglishRow.Concealed -> "concealed"
        is UiEnglishRow.Visible -> "visible"
    }

/**
 * The content-shaped stand-in "Design: the Compose UI" §6 specifies: a measured-height placeholder
 * rather than a grey bar, seeded so a row keeps the same shape across recompositions.
 *
 * <p>`widths` is a `FloatArray`, so the generator's equality is structural (§2.3 invariant 8 -
 * "stable, cheap equality") and is written out rather than left to the reference comparison a
 * `data class` would otherwise give an array.
 */
data class PlaceholderShape(val lines: Int, val widths: FloatArray, val seed: Long) {

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is PlaceholderShape &&
                lines == other.lines &&
                seed == other.seed &&
                widths.contentEquals(other.widths))

    override fun hashCode(): Int = 31 * (31 * lines + seed.hashCode()) + widths.contentHashCode()
}
