package uk.xa0.tulkki.ui.projection

/**
 * A reply's quote, "Design: the Compose UI" §2.2, with both halves typed.
 *
 * <p>§2.2 gives all four fields, and the rule they obey is AGENTS.md's: "a reply conceals the quoted
 * original either way, and the composer's always-outgoing reply preview conceals it too", decided in
 * `ReplyQuote` before the UI sees it - so this type can hold a concealed quote without knowing why it
 * is concealed. [messageId] is the referenced row's local uuid, never its text (§2.3 invariant 4's
 * pointer rule, applied to the quote), and [divider] is `bottom != null`, exactly as §2.2 gives
 * `UiMessage.divider`.
 *
 * <p>[messageId] is `null` for the one quote that has no referenced row at all: a reply whose target
 * cannot be resolved locally draws `ReplyQuote.unresolved`'s answer - the reply's own fallback copy,
 * covered or drawn by the interpreter's switch - and there is no row to name. The screen then has no
 * row to act on, which is why the field is nullable rather than pointed at the reply itself: the
 * missing target is the fact, and a tap that translated the reply would be a different purchase.
 */
data class UiQuote(
    val messageId: MessageId?,
    val top: UiBody,
    val bottom: UiBody?,
    val divider: Boolean,
)
