package uk.xa0.tulkki.xmpp.services

import uk.xa0.tulkki.libs.AttachmentRef

/**
 * Tulkki: the media list's continuation, implemented by `:ui`'s `OnMediaLoaded`.
 *
 * Un-nested out of `XmppConnectionService`; converted from the
 * Java. The list is **non-null** and **empty-able** rather than null: the island's call site
 * (`Attachments.kt`) hands over `fileBackend.convertToAttachments(...)`, whose declared type is
 * `List<Attachment>`, and every `:ui` implementer iterates it unguarded.
 *
 * **The wildcard is deliberate and needs no suppression.** The Java declared
 * `List<? extends AttachmentRef>`; Kotlin emits exactly `List<? extends AttachmentRef>` for a
 * `List<AttachmentRef>` parameter because `AttachmentRef` is a non-final (interface) type argument
 * and `kotlin.collections.List` is declaration-site covariant - measured with kotlinc 2.3.21. So
 * the descriptor *and* the generic signature are the Java's, and `:ui`'s
 * `OnMediaLoaded.onMediaLoaded(attachments: List<AttachmentRef>)` overrides it unchanged.
 */
interface MediaLoadedHook {

    fun onMediaLoaded(attachments: List<AttachmentRef>)
}
