package uk.xa0.tulkki.ui.interfaces

/**
 * The search screen's continuation: the terms it asked for and the messages the archive matched.
 *
 * Pair 11 of `docs/MIGRATION.md` "The cycle rules" §3 (D4). The service is an island and may not name
 * `:ui`, so the island declares the continuation as the port
 * `uk.xa0.tulkki.xmpp.services.SearchResultsHook` and this interface is its
 * `:ui` implementation. No method and no call site moved - `SearchActivity` keeps implementing this
 * name, and the service's `search` takes the port.
 *
 * The island's name is written in full rather than imported: an `import` line naming an island class
 * would be a new `ui-reaches-island` site, and pair 11's gate holds that ratchet at 242.
 */
interface OnSearchResultsAvailable :
    uk.xa0.tulkki.xmpp.services.SearchResultsHook {

    /**
     * Tulkki: 3.7 pair 9, part 16 - the parameter is the island's ref now, spelled in full and with no
     * import (rounds 151/161). The island's `SearchResultsHook` declares it, so this interface must
     * match it exactly: an `import` of the island type here would be one more `ui-reaches-island` site.
     */
    @JvmSuppressWildcards
    override fun onSearchResultsAvailable(
        term: List<String>,
        messages: List<uk.xa0.tulkki.xmpp.refs.MessageRef>,
    )
}
