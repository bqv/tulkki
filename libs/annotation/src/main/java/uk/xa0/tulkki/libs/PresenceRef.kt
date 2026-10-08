package uk.xa0.tulkki.libs

/**
 * Tulkki: the XMPP island's view of `uk.xa0.tulkki.data.model.Presence`.
 *
 * Declared in the island and implemented by the model class in `:data`
 * (`docs/WORKSTREAMS.md` round 151). Its first consumer is `PresenceParser`, which builds one from a
 * wire element and hands it back to `Contact.updatePresence`.
 *
 * **The status enum is the one place this pair's enum ruling cannot be followed verbatim.**
 * `PresenceParser` does `contact.getShownStatus() == Presence.Status.OFFLINE` — an *identity*
 * comparison — and the model's own accessor returns the model enum. An island `getShownStatus()`
 * returning an island enum could therefore not be the same method (a class may not have two methods
 * differing only in return type), so the island member is **distinctly named**: [StatusRef] travels
 * on `ContactRef.shownStatus()`, which `Contact` answers with a switch over its own
 * `getShownStatus()`. Identity stays true inside the island, the model enum keeps serving
 * `:ui`/`:app`/`:translation`, and no behaviour changes. This is not the stop condition firing — no
 * site makes an island enum and a model enum meet — it is the same class of language fact as
 * `AccountRef.setBookmarks`'s erasure clash: the name adapts, the identity does not.
 *
 * **Why `:libs` (2026-10-08, lane `G`).** It was `uk.xa0.tulkki.xmpp.refs.PresenceRef` - the
 * island's name for `:data`'s `Presence` while `:xmpp` could not name `:data`. `:libs` is the one
 * module in the order that every side may reach, so the interface moves here and the ref file is
 * its `git rm`; the move is **package-only** and every namer changes only its import line.
 *
 * **Respell from Java (2026-10-08, lane `G`).** Six members plus the nested enum. The nullabilities
 * are `Presence`'s own: `hasCaps()` is non-null, `getServiceDiscoveryResult()` answers
 * `ServiceDiscoveryResult?` (`Presence.kt:84`), `getHash()`/`getVer()`/`getNode()` answer `String?`
 * (`:68`-`:72`), and `setServiceDiscoveryResult` takes the nullable ref (`:80`). `StatusRef` is a
 * Kotlin `enum class` with the same six constants and the same `toShowString()` table, including
 * Java's `null` for `ONLINE` and `OFFLINE`; `PresenceStatusRefTest` pins the round trip and the
 * table, and it compiles unchanged because Kotlin enums keep `values()`, `name` and `valueOf`.
 */
interface PresenceRef {

    /**
     * Tulkki: the island's view of `uk.xa0.tulkki.data.model.Presence.Status`.
     *
     * Same six constants, in the model's own order, but a type of its own: the island compares these
     * with `==` (see the interface comment), so they must be the values the island actually holds.
     * `Contact.shownStatus()` is the only producer and the mapping is name for name.
     */
    enum class StatusRef {
        CHAT,
        ONLINE,
        AWAY,
        XA,
        DND,
        OFFLINE;

        /**
         * Tulkki: 3.7 C5-D - the `show` string, copied from `Presence.Status.toShowString()` because
         * `PresenceGenerator` asks the status *it holds* for it and a ref is not the model enum. It
         * is a pure function of the constant - a compile-time table, the same kind of copy as the
         * `OPTION_*` flags - and `PresenceStatusRefTest` pins it equal to the model's for all six
         * constants, so the two cannot drift silently.
         */
        fun toShowString(): String? = when (this) {
            CHAT -> "chat"
            AWAY -> "away"
            XA -> "xa"
            DND -> "dnd"
            else -> null
        }
    }

    fun hasCaps(): Boolean

    /**
     * Tulkki: part 12's return-position drag. `MessageParser.clientMightSendHtml` walks a contact's
     * presences to the one resource a JID names and asks it what clients it advertises; the model
     * answers its own `ServiceDiscoveryResult`, which implements the ref. Nullable, because the
     * model's own accessor is.
     */
    fun getServiceDiscoveryResult(): ServiceDiscoveryResultRef?

    // -- part 14: the caps fields `injectServiceDiscoveryResult` matches on and writes -------------
    //
    // The island compares `hash`/`ver` on every presence of every contact against the pair it was
    // handed and stores the fetched result on the ones that match. All three are the model's own
    // declarations; `setServiceDiscoveryResult` takes the ref, so `Presence` gains a one-line
    // overload that casts once - the model's own `setServiceDiscoveryResult(ServiceDiscoveryResult)`
    // cannot be an override of a member whose parameter is the interface.

    fun getHash(): String?

    fun getVer(): String?

    /**
     * Tulkki: 3.7 C5-D - the caps node `XmppConnectionService.fetchCaps` puts in the disco request.
     * A declaration `Presence` already carries, so `:data` pays no body for it.
     */
    fun getNode(): String?

    fun setServiceDiscoveryResult(disco: ServiceDiscoveryResultRef?)
}
