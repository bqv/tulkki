package uk.xa0.tulkki.libs

import android.net.Uri
import java.util.UUID

/**
 * Tulkki: the XMPP island's view of `uk.xa0.tulkki.data.utils.Attachment`.
 *
 * C5-E3. `XmppConnectionService` declares the media list twice - the `MediaLoadedHook` continuation
 * it hands a screen, and `deleteMedia`, which the media browser calls back - and both spoke the
 * model's name. This ref is what they speak instead, so the island names no `:data` type on the
 * media path while the objects that travel it stay the model's own.
 *
 * Only what the media-viewing chain actually reads is declared here, measured across the seven
 * files this slice retypes; the model keeps everything else (`Type`, `of(...)`,
 * `extractAttachments`, `canBeSendInBand`) on the `:data` side.
 *
 * **The kind is [kind], not `getType()`, and that is a language fact rather than a preference.**
 * `:ui` compares the model enum by identity in a dozen places (`MediaBrowserFragment:97-98`,
 * `MediaAdapter:136-138`) and the model already declares `getType()` returning `Attachment.Type`,
 * so an island member cannot be that method under a second return type: a class may not have two
 * methods differing only in return type. So the island enum is a type of its own, [TypeRef], and
 * `Attachment` answers it with one four-arm switch over its own `getType()` - the device
 * [PresenceRef.StatusRef] uses. The mapping is a compile-time copy, so `AttachmentKindRefTest` pins
 * it name for name.
 *
 * **Why `:libs` (2026-10-08, lane `G`).** It was `uk.xa0.tulkki.xmpp.refs.AttachmentRef`, and it is
 * the only one of the thirteen that stayed whose obstacle was a single `android.*` type:
 * `android.net.Uri` in [getUri]. The owner took the module route rather than the contract route,
 * because the `Uri` is handed straight to a `Uri`-typed port in the same expression at four sites -
 * `Attachments.kt:145` and `MediaBrowserActivity.kt:219`/`:230`/`:268` - so a `String` spelling
 * would insert a `Uri.parse` at each and change what the contract carries. `libs/annotation` may
 * stop being a bare `java-library` (the owner's own note), and it now compiles against a
 * `compileOnly` `android.jar` from the same SDK the Android modules use; there was no need to make
 * the module an Android library, and the reason is in its build file.
 *
 * **`:ui` may import this now.** The earlier note - write it fully qualified in the signature and
 * import nothing - existed only because `ui-reaches-island` counted a `:ui -> :xmpp` import; a
 * `:libs` type is not an island and carries no such key. The four `:ui` signatures are left
 * fully qualified, because a move that changes spelling twice is two sweeps for one file.
 *
 * **Respell from Java (2026-10-08, lane `G`).** Seven members plus the nested enum, with each
 * nullability taken from the model: `Attachment.getMime()` and `getConversationUuid()` answer
 * `String?` (`Attachment.kt:68`, `:74`), `getUri()`, `getUuid()`, `getTimestamp()`, `renderThumbnail()`
 * and `kind()` are non-null. The JVM surface is identical - `Uri getUri()`, `String getMime()`,
 * `UUID getUuid()`, `long getTimestamp()`, `String getConversationUuid()`, `boolean renderThumbnail()`,
 * `AttachmentRef$TypeRef kind()` - and the sweep is seven expressions in `MediaViewerActivity.kt`
 * (`:578`, `:579`, `:610`, `:660`-`:663`), where the Java dereferenced `getMime()`; the guard keeps
 * that dereference rather than widening the tolerance. `MediaBrowserActivity.kt:225`'s
 * `intent.type = attachment.getMime()` and `MediaBrowserFragment.kt:101`'s `?: ""` already read it
 * as nullable, and `MediaBrowserActivity.kt:258`'s `findConversationByUuid` already takes `String?`.
 */
interface AttachmentRef {

    fun getUri(): Uri

    fun getMime(): String?

    fun getUuid(): UUID

    fun getTimestamp(): Long

    fun getConversationUuid(): String?

    /** `Attachment.renderThumbnail()` - already the exact signature, so `:data` needs no edit. */
    fun renderThumbnail(): Boolean

    /**
     * The model's `Attachment.Type`, as an island enum: see the interface comment for why the name
     * is not `getType()`.
     */
    fun kind(): TypeRef

    /** Same four constants, in the model's own order. `Attachment.kind()` is the only producer. */
    enum class TypeRef {
        FILE,
        IMAGE,
        LOCATION,
        RECORDING,
    }
}
