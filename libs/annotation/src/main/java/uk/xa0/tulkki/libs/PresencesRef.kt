package uk.xa0.tulkki.libs

/**
 * Tulkki: the XMPP island's view of `uk.xa0.tulkki.data.model.Presences`, the per-resource presence
 * set a `Contact` holds.
 *
 * Declared in the island and implemented by the model class in `:data`
 * (`docs/WORKSTREAMS.md` round 151). It exists because `ContactRef.getPresences()` is a
 * return-position drag: the island only ever asks `size()` of it here, and the model's own
 * `Presences` is a class, so the ref is what makes the return covariant. Members arrive with their
 * call sites (ruling 3), so it starts with the one `PresenceParser` reads.
 *
 * **Why `:libs` (2026-10-08, lane `G`).** It was `uk.xa0.tulkki.xmpp.refs.PresencesRef` - the
 * island's name for `:data`'s `Presences` while `:xmpp` could not name `:data`. `:libs` is the one
 * module in the order that every side may reach, so the interface moves here and the ref file is
 * its `git rm`; the move is **package-only** and every namer changes only its import line.
 *
 * **Respell from Java (2026-10-08, lane `G`).** Nine members, all JDK plus the three interfaces
 * that moved with it. The nullabilities are `Presences`'s own: `get(resource)` answers the model's
 * nullable map hit (`Presences.kt:73`) and `anyIdentity` takes the model's two nullable parts
 * (`:190`); the rest are non-null. The two Java wildcards (`Map<String, ? extends PresenceRef>`,
 * `List<? extends PresenceRef>`, `List<? extends PresenceTemplateRef>`) are Kotlin's
 * declaration-site variance - `kotlin.collections.Map`/`List` are `out V`/`out E` - so
 * `Map<String, PresenceRef>` emits the identical `java.util.Map<String, ? extends PresenceRef>`
 * descriptor and `Presences`'s narrower overrides still satisfy it.
 */
interface PresencesRef {

    fun size(): Int

    /**
     * Tulkki: the per-resource map, as a return-position drag - the model answers
     * `Map<String, Presence>` and generics are invariant, so the wildcard is forced and the island's
     * own local adopts it. `MessageParser.clientMightSendHtml` is the call site: it looks one
     * resource up and asks that presence for its service-discovery result.
     */
    fun getPresencesMap(): Map<String, PresenceRef>

    /**
     * Tulkki: part 12. `MessageParser`'s `ofrom` address check asks whether any of a contact's
     * resources advertises a feature; the model's own single-argument predicate, unchanged.
     */
    fun anySupport(namespace: String): Boolean

    // -- part 14: the two reads `injectServiceDiscoveryResult` makes -------------------------------
    //
    // It walks every contact and asks each one's presence set for one resource and then for the whole
    // list, so the ref needs both. `get` is preferred over `getPresencesMap().get(...)` deliberately:
    // the map accessor copies the map, and this call site runs once per contact in the roster.

    /** The model's own O(1) lookup, made covariant by `Presence implementing PresenceRef`. */
    fun get(resource: String): PresenceRef?

    /**
     * Tulkki: return-position drag, the same shape as `getPresencesMap()` - the model answers
     * `List<Presence>` and generics are invariant, so the wildcard is forced and the island's loop
     * variable is a [PresenceRef].
     */
    fun getPresences(): List<PresenceRef>

    /**
     * Tulkki: part 15. `OnContactStatusChanged` asks whether a contact still has a resource online,
     * which the model answers with a bare array; the island wraps it in `Arrays.asList`. The
     * model's own method, unchanged.
     */
    fun toResourceArray(): Array<String>

    // -- 3.7 C5-A: the jingle cluster's two reads --------------------------------------------------
    //
    // `RtpCapability.check(Contact, boolean)` asks whether the contact has any presence at all
    // before falling back to its roster entry, and asks a would-be gateway's resource set whether
    // any of its resources advertises the gateway/pstn identity. Both are the model's own
    // declarations, unchanged.

    /** The model's own `isEmpty()`; `RtpCapability`'s fallback gate. */
    fun isEmpty(): Boolean

    /** The model's own two-argument predicate; the PSTN-gateway test. Its parts are nullable. */
    fun anyIdentity(category: String?, type: String?): Boolean

    /**
     * Tulkki: C5-E1. `Presences.asTemplates()` answers the model's `List<PresenceTemplate>`, which
     * is a subtype of this return type, so `Presences` pays no body. Read by
     * `XmppConnectionService.getPresenceTemplates`, whose only other input is the database's own
     * template list.
     */
    fun asTemplates(): List<PresenceTemplateRef>
}
