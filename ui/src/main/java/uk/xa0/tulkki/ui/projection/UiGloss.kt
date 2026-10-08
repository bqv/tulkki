package uk.xa0.tulkki.ui.projection

/**
 * One tappable word of a row's drawn app-language text: the reading aid's own target, projected so a
 * Composable never has to ask the rule itself.
 *
 * <p>`MessageAdapter.addGlossSpans` built one invisible `ClickableSpan` per token
 * `GlossText.words(...)` answered, and `GlossPopup` derived the sentence from the offsets; the Compose
 * card needs the same three facts, and they are a **projection** because "the words worth glossing"
 * is `GlossText`'s rule and the projector is the one place Compose calls those rules
 * (`docs/MIGRATION.md` "Design: the Compose UI" §2.1). The screen draws them and emits the tap; it
 * does not decide which words exist.
 *
 * <p>[start] and [end] are offsets into the drawn text ([UiBody.Visible.text], which is the same
 * string `GlossText.words` was handed), so the screen can cut the sentence the word was tapped in
 * with `GlossText.sentence(text, start, end)` - and nothing here carries that sentence, because it
 * is only known once the word is tapped.
 *
 * <p>**It is the drawn text and never a concealed body.** The projector asks
 * `GlossText.words(displayed, …)`, whose first rule answers an empty list for a blurred body, so a
 * covered row has no targets and the bubble's own tap keeps its meaning ("translate this one, now").
 * With the interpreter off the same call answers empty for every word, which is what removes the
 * reading aid from a plain client - no second guard is needed anywhere.
 *
 * <p>The word is a `String` here in the same way [UiReaction.emoji] is: it is text the interface is
 * already showing, not somebody's original. §2.3 invariant 1's reflection scan covers the typed
 * bodies ([UiBody], [UiEnglishRow], [UiPreview], [UiQuote], [UiMessage]) and does not read this file,
 * deliberately - a tappable word is a target, not a body.
 */
data class UiGlossWord(
    /** The surface form as it was drawn, which is what the lookup is asked about. */
    val word: String,
    /** The word's first character, as an offset into the drawn text. */
    val start: Int,
    /** The word's last character plus one. */
    val end: Int,
)
