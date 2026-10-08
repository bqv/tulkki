package uk.xa0.tulkki.ui.projection

/**
 * The English row's whole state, "Design: the Compose UI" §2.2.
 *
 * <p>§2.2 fixes all four cases and nothing else: `Absent | Pending | Concealed(Smear) |
 * Visible(text)`. Which one a received message gets is `:translation`'s `EnglishRow.of`'s decision,
 * made before this type exists (`MIGRATION.md` "Design: the Compose UI" §2.3 invariant 2), and the
 * owner-approved exception AGENTS.md bounds it to: a received message whose original was already
 * English, hardcoded `en`, off by default, blurred, tap-revealed.
 *
 * <p>[Concealed] carries the whole [UiConcealment] rather than only its `Smear`, because that is
 * the contract §2.2 writes (`data class Concealed(val concealment: UiConcealment)`); the projection
 * is what guarantees it is a smear when the row is drawn at all.
 */
sealed interface UiEnglishRow {

    /** No row: the switch is off, the bubble has nothing to divide, or the app is English. */
    data object Absent : UiEnglishRow

    /** The row is drawn, the English is not bought yet: a bare bar, and a tap is what buys it. */
    data object Pending : UiEnglishRow

    /** The English is in hand and must not be readable: pixels only. */
    data class Concealed(val concealment: UiConcealment) : UiEnglishRow

    /** The owner revealed it. */
    data class Visible(val text: String) : UiEnglishRow
}
