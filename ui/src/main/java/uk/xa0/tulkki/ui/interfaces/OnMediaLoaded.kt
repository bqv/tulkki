package uk.xa0.tulkki.ui.interfaces

/**
 * The media browser's continuation: what the service hands a screen once it has read a
 * conversation's media list out of the archive.
 *
 * Pair 11 of `docs/MIGRATION.md` "The cycle rules" §3 (D4). The service is an island and may not name
 * `:ui`, so the island declares the continuation as the port
 * `uk.xa0.tulkki.xmpp.services.MediaLoadedHook` and this interface is its `:ui`
 * implementation - the same twin pair 6 made of `UiCallback` and `PgpCallback`. No method and no call
 * site moved: every screen that implements this interface keeps compiling, and the service takes the
 * port.
 *
 * The island's name is written in full rather than imported on purpose. An `import` line naming an
 * island class is a `ui-reaches-island` site, and a fully-qualified name in a code body is what
 * section 7 of the pair's constraints calls naming it in the body. The same technique is used by
 * `UiCallback`, `OnAvatarPublication` and `OnSearchResultsAvailable`.
 *
 * C5-E3 retyped the element: the list is the island's `AttachmentRef` now, and the wildcard is forced
 * rather than decorative - the island's own call site hands over a `List<Attachment>`, which is not a
 * `List<AttachmentRef>`. This file's `:data` import is gone with it, and every `:ui` file that names
 * the ref writes it in full and imports nothing; those references are reported honestly by
 * `tools/fqn-refs` as `:ui -> :xmpp`, a direction the map declares, instead of as a
 * `ui-reaches-island` import.
 */
interface OnMediaLoaded : uk.xa0.tulkki.xmpp.services.MediaLoadedHook {

    override fun onMediaLoaded(attachments: List<uk.xa0.tulkki.libs.AttachmentRef>)
}
