package uk.xa0.tulkki.ui.projection

/**
 * The received original item 17's decision five offers, and the second of the two owner-approved
 * exceptions to "the original is stored, never displayed" (the first is [UiEnglishRow]).
 *
 * <p>**It is a row of its own and not the bubble's second half.** A received message whose translation
 * genuinely failed has *no* second half at all: `BubbleHalves.of` answers `Bottom.NONE` for it - "nothing
 * was translated, so there is no second side: the one body is the bubble, and a body that needed
 * translating and did not get one is covered as it always was" - so the covered body stands where the
 * translation would be and this row is what sits under it. That is also what AGENTS.md describes: the
 * exception "takes the same shape as the row above" (the English row), not the shape of a divided
 * bubble. `SecondHalf`'s structural guarantee is therefore untouched by this type: no setting can make
 * a *half* readable, and this row is not a half.
 *
 * <p>The three states are the English row's, less the one it has for a purchase: nothing is bought here,
 * the original is already stored, so there is no `Pending`. Concealment is decided before this type
 * exists ([OriginalReveal]'s gate, which reads no setting), and [Concealed] carries a [UiConcealment]
 * rather than a `String` for the reason every concealed thing does - there is no text to reach.
 */
sealed interface UiOriginalRow {

    /** Nothing is offered: the row is not a received terminal failure, or the interpreter is off. */
    data object Absent : UiOriginalRow

    /** Offered, and not yet read: the strip, and a tap is what reads it. */
    data class Concealed(val concealment: UiConcealment) : UiOriginalRow

    /** The owner revealed it: the one path by which somebody else's original is drawn. */
    data class Visible(val text: String) : UiOriginalRow
}

/** The shape's name, for a row's `toString()`: §2.3 invariant 1(a) allows no `String` there. */
fun UiOriginalRow.kindName(): String =
    when (this) {
        UiOriginalRow.Absent -> "absent"
        is UiOriginalRow.Concealed -> "concealed"
        is UiOriginalRow.Visible -> "visible"
    }
