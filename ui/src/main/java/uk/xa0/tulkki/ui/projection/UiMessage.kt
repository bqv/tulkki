package uk.xa0.tulkki.ui.projection

/**
 * One row of a conversation, "Design: the Compose UI" §2.2's declaration, field for field.
 *
 * <p>§2.2's own contract is the field list, and §2.2.1 "The missing definitions" #9 confirms its
 * sources: the row fields are `MessageSnapshot`'s scalars plus the `ui-2` part-1 body types, and the
 * three typed states are the ones §2.2.1 defines. Nothing here re-reads the file - the projector
 * (§2.1's `MessageProjection.of`) is the one place a snapshot becomes this, and the concealment
 * decision is made there, before this type exists (§2.3 invariant 2), which is why `top` and
 * `bottom` are [UiBody] and never a `String`.
 *
 * <p>`top` is **always** the app language's side and `bottom` the conversation's (`BubbleHalves`'
 * invariant, §2.2); `bottom` is `Visible` only where it is the owner's own text.
 *
 * <p>**`gloss` is a field §2.2's declaration predates**, on the same footing as `original`: §2.2 was
 * written before the reading aid's Compose card, and the tappable words of the drawn text have no
 * other carrier - a Composable may not ask `GlossText` itself (§7.4: every rule class stays where it
 * is) and the offsets must belong to the very string drawn. §2.3 invariant 1 is untroubled: the word
 * is text the interface already shows, exactly as [UiReaction.emoji] is, and `ProjectionTypesTest`
 * scans the typed bodies rather than this target list.
 *
 * <p>**The `revision` §2.3 invariant 8 asks for is not a field here, and that is §2.2.1's third
 * recorded self-contradiction**: §2.3(8) says "`UiMessage` carries a `revision`" so a `Flow`
 * emission recomposes only what changed, and §2.2's declaration has no such field. Adding one would
 * be inventing a shape the section that names it does not give, so the row carries the contradiction
 * to the coordinator instead. Until it is settled, equality is §2.2's own field list - which is
 * still structural and still cheap.
 */
data class UiMessage(
    /** The local uuid: row identity, and the `LazyColumn` key (§2.3 invariant 6). */
    val id: MessageId,
    val conversationId: ConversationId,
    val direction: Direction,
    val time: Long,
    val type: MessageType,
    /** Always the app language's side. */
    val top: UiBody,
    /** The conversation's side, `Visible` only when it is the owner's own. */
    val bottom: UiBody?,
    /** `bottom != null`; never drawn otherwise. */
    val divider: Boolean,
    /** The referenced row's id and its own typed bodies. */
    val quote: UiQuote?,
    val english: UiEnglishRow,
    /**
     * The received original item 17's decision five offers, gated by [OriginalReveal] on the row's own
     * facts. [UiOriginalRow.Absent] for every row that is not a received terminal failure, which is
     * almost all of them.
     */
    val original: UiOriginalRow,
    /** Notes and marks into `top`, on own rows only. */
    val review: UiReview?,
    val transfer: UiTransferState,
    /**
     * The attachment cell a file or image row draws, or `null` for every row that is not a transfer.
     * §2.2's declaration predates it: a transfer row is `top = Absent` by construction (its body is an
     * address or a path, never prose), so without this the cell had no field to reach the bubble and a
     * completed transfer drew an empty row. It is not a second `transfer`: the state is one of its
     * fields, and the cell is what the screen draws.
     */
    val attachment: UiAttachment? = null,
    val encryption: UiEncryption,
    val delivery: UiDeliveryState,
    val reactions: List<UiReaction>,
    /**
     * The tappable words of the drawn app-language text, from `GlossText.words` over [top]'s own
     * displayed body; empty for a covered body, for a non-text row and while the interpreter is off.
     * The reading aid's entry point, and the only place its targets exist (§2.1's "the projector is
     * the one place Compose calls the rules").
     */
    val gloss: List<UiGlossWord>,
    /** Computed from the whole list, not from this row. */
    val run: UiRunFlags,
    val selected: Boolean,
    /** A tap is offered; `false` means the cover carries the reason instead. */
    val canTranslateNow: Boolean,
) {

    /**
     * Ids and shapes only, §2.2's declaration and §2.3 invariant 1(b): on a fixture whose original is
     * a sentence, this string contains neither the sentence nor its translation. A generated
     * `toString()` would put both bodies into every log line and crash report, which is the leak the
     * invariant is written against.
     */
    override fun toString(): String =
        "UiMessage(id=$id, direction=$direction, type=$type, top=${top.kindName()}, " +
            "bottom=${bottom?.kindName()}, english=${english.kindName()}, original=${original.kindName()}, " +
            "quote=${quote != null}, " +
            "review=${review != null}, transfer=${transfer::class.simpleName}, " +
            "attachment=${attachment != null}, " +
            "encryption=${encryption::class.simpleName}, delivery=${delivery::class.simpleName}, " +
            "reactions=${reactions.size}, gloss=${gloss.size})"
}
