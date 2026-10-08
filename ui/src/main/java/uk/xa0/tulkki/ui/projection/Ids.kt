package uk.xa0.tulkki.ui.projection

/**
 * Row identity, "Design: the Compose UI" §2.2's wrapper rule: "the wrapper types are `value class`es
 * over the ids already in the schema (local uuid authoritative; `stanzaId`/`originId`/`archivedId`
 * are secondary - `IDEA-SCAN §1.4`)", and §2.3 invariant 6: "the LazyColumn key is the local id".
 *
 * <p>Each wrapper holds exactly one `String` - the local uuid the schema already has - so the two
 * wire ids on a snapshot (and the quote's target id) can never be passed where a row's own identity
 * was meant. They are `value class`es, so the wrapper costs nothing at runtime.
 */
@JvmInline value class MessageId(val uuid: String)

@JvmInline value class ConversationId(val uuid: String)
