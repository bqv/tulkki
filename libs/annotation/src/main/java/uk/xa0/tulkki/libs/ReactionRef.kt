package uk.xa0.tulkki.libs

/**
 * Tulkki: the XMPP island's view of `uk.xa0.tulkki.data.model.Reaction`.
 *
 * Declared in the island and implemented by the model class in `:data`. Its consumers read the
 * model's public fields on a reaction they are aggregating or dropping:
 *
 * ```
 * newReactions.removeAll(message.getReactions().stream()
 *     .filter { occupantId == it.occupantId() }.map { it.reaction() }...)
 * ```
 *
 * Three public fields become three accessors, for the reason `MucOptions.onRenameListener` became a
 * pair of members: an interface has no fields. The model keeps its fields exactly as they are -
 * `:data`'s own code, its JSON adapters and its equality all read them directly - and `Reaction`
 * gains the three one-line bodies that satisfy this ref.
 *
 * The two static factories the same callers use (`Reaction.withOccupantId` and `Reaction.withFrom`)
 * are **not** here: a static cannot live on an interface, so they are construction and belong to
 * `XmppConnectionService.DataStatics`, exactly as `newMessage` does. The collection they return is a
 * `Collection<Reaction>`, which is why the port's parameters and `MessageRef.getReactions()` both
 * use the wildcard - generics are invariant and a bare `Collection<ReactionRef>` would refuse it.
 *
 * **Why `:libs` (2026-10-08, lane `G`).** It was `uk.xa0.tulkki.xmpp.refs.ReactionRef`. Its whole
 * member surface is JDK plus `uk.xa0.tulkki.libs.Jid`, once `Jid` left the island for the same
 * module, so the interface is `:libs`-expressible - and it has to be, because `:libs` may name
 * nothing (`allow = []`). The move is **package-only** and every namer changes only its import
 * line. **Only the interface moves** - `:data`'s `Reaction` and `MessageParser`'s reaction handling
 * stay byte-for-byte.
 *
 * **Respell from Java (2026-10-08, lane `G`).** Three members, and all three are the model's
 * nullable fields: `Reaction.kt:111`/`:113`/`:115` declare `String? reaction()`, `Jid? from()` and
 * `String? occupantId()`. The Kotlin spellings take exactly those, so nothing is narrowed and no
 * reading gains a check the Java did not have. The JVM surface is identical:
 * `String reaction()`, `Jid from()`, `String occupantId()`. **The sweep is real** - the Java
 * declarations were platform types, so four Kotlin call sites passed `reaction()`'s answer where a
 * non-null `String` was wanted (`MessageParser.kt:2556`, `:2650` feed a `HashSet<String>`,
 * `ReactionPublisher.kt:65`, `:143` feed a `Set<String>`); all four drop the null, which is the
 * Java's own behaviour, because a null reaction matched no variant and no set element.
 */
interface ReactionRef {

    fun reaction(): String?

    fun from(): Jid?

    fun occupantId(): String?
}
