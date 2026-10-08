package uk.xa0.tulkki.ui.projection

/**
 * One reaction chip's content, "Design: the Compose UI" §2.2's `reactions` element, defined by
 * §2.2.1 "The missing definitions" #4 from the document's writer and the chip's own reader.
 *
 * <p>[emoji] is the JSON's `reaction` key - the aggregate key `Emoji.unicode`, which is also what the
 * chip draws - and **not a count**: §2.2.1 is explicit that the group's size comes from
 * `Reaction.aggregated`, not from a key. [mine] is the JSON's `received` negated, which is how the
 * chip decides to fill itself in the owner's colour. The projector decodes the document, not
 * `:data`: the snapshot carries the column raw by rule and `:data`'s `reactions/` publishes only the
 * two statements over the String, so a decoder there would be a second place the column's meaning is
 * decided.
 *
 * <p>**A reactor's identity is not here and is a named hole.** §2.2.1 gives `who` as the document's
 * `from` / `trueJid` / `occupantId`, and says in the same breath that `who` *as a display name* is
 * deferred because it is not in the JSON (a room resolves it through `MucOptions.findUsers`, and the
 * projector has no input for it). What is not settled is the holder: two of the three keys are wire
 * `Jid`s and one is a room occupant id, and the design names no type for the three. The chip never
 * read them - it draws the emoji and the count and highlights one of ours - so the field arrives
 * with the shape that decides it rather than with a plausible one.
 */
data class UiReaction(
    val emoji: String,
    val count: Int,
    val mine: Boolean,
)
