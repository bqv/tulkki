package uk.xa0.tulkki.xmpp.refs

import uk.xa0.tulkki.libs.Transferable
import uk.xa0.tulkki.libs.ReactionRef
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.libs.Jid
import io.ipfs.cid.Cid
import java.net.URI

/**
 * Tulkki: the XMPP island's view of `uk.xa0.tulkki.data.model.Message`.
 *
 * Declared in the island and implemented by the model class in `:data`
 * (`docs/WORKSTREAMS.md` round 151). **Not a marker on purpose** - and it is grown the one way
 * the pair's ruling allows: every member here is read or written by a call site in the file being
 * retyped, never ahead of one (ruling 3, `docs/WORKSTREAMS.md` round 190).
 *
 * Its first consumer was `XmppConnectionService.DataStatics.prepareQuote`, which is why it
 * exists before the parser cluster. `MessageParser` is the second and by far the largest: it is
 * the only island file that *constructs* a `Message` (twelve sites), reads almost every field of
 * one, edits one in place, and hands one to half a dozen island services. Everything below was forced
 * by that file.
 *
 * **The constants are copied, not mapped**, for the reason `ContactRef.OptionsRef`'s bits
 * and `MucOptionsRef.STATUS_CODE_*`'s strings are: they are compile-time `int`s with no
 * identity, so a copy cannot drift, and importing the model type to read fifteen literals would be
 * fifteen more `island-imports-ours` sites. The values are the model's own, including the four
 * that are themselves copies of `:crypto`'s `OmemoMessage`.
 *
 * **Two members are deliberately not spelled the way the call site reads them**, and
 * both are hard language facts rather than style, exactly like `AccountRef`'s missing
 * `setBookmarks`:
 *
 * 
 *   - `setReactions(Collection<Reaction>)` on the model erases to
 *       `setReactions(Collection)`, so *any* `setReactions` this ref declared - wildcard
 *       or not - would be a name clash. The member is therefore [replaceReactions], and
 *       `Message` supplies the one-line adapter.
 *   - `markable` is a public mutable **field**, and an interface has no fields. The read side
 *       needs nothing (the island only ever assigns it), so the ref carries the setter alone.
 * 
 *
 * Where a `:ui` file has to name this type it must write it **fully qualified in the
 * signature and import nothing** (rounds 151/161, pair 11's four `extends` clauses): an import
 * of an island type in a `:ui` file is one more `ui-reaches-island` site, and that rule is
 * decided before `allow` is consulted.
 */
interface MessageRef {

    /**
     * Tulkki: the island's view of `uk.xa0.tulkki.data.model.Message.FileParams`, the download
     * description a message carries.
     *
     * Nested, like `MucOptionsRef.UserRef` and for the same reason: the island used to spell
     * the model's own nesting (`Message.FileParams`), and flattening it would invent a name the
     * model does not have. The three members are exactly what `MessageParser` reads - the URL it
     * tests for PGP encryption, the `cid:` list it resolves files for, and the thumbnail
     * elements of a retracted message - and the model keeps its public fields, gaining only the
     * accessors, because `:data`'s own serialisation reads them directly.
     */
    interface FileParamsRef {

        /** The model's public field `url`, as a member: an interface has no fields. */
        fun url(): String?

        /**
         * Part 16: the same field, written. `XmppConnectionService`'s link-preview path resolves a
         * URL and fills the file params in place, and both fields it assigns (`url`, `size`) are
         * public fields of the model, so an interface needs the setters. `size` is the model's boxed
         * `Long`, mirrored exactly.
         */
        fun setUrl(url: String?)

        fun setSize(size: Long?)

        fun setName(name: String?)

        fun getName(): String?

        fun getMediaType(): String?

        /** The model's own `long getSize()`; `setSize` takes the boxed `Long` the field holds. */
        fun getSize(): Long

        fun getCids(): List<@JvmSuppressWildcards Cid>

        fun getThumbnails(): List<@JvmSuppressWildcards Element>


    }

    // -- reading ------------------------------------------------------------------------------------
    fun getBody(): String

    fun getRawBody(): String?

    fun getConversation(): ConversationalRef?

    fun getCounterpart(): Jid?

    fun getTrueCounterpart(): Jid?

    fun getEncryption(): Int

    fun getEphemeralTimer(): Int

    fun getFileParams(): FileParamsRef

    fun getFingerprint(): String?

    fun getInReplyTo(): MessageRef?

    fun getOccupantId(): String?

    fun getOob(): URI?

    fun getPayloads(): List<@JvmSuppressWildcards Element>

    fun getStatus(): Int

    fun getSubject(): String?

    fun getThread(): Element?

    fun getTimeSent(): Long

    fun getUuid(): String?

    fun getRemoteMsgId(): String?

    fun getServerMsgId(): String?

    /**
     * Covariant, and the wildcard is forced rather than decorative: the model answers
     * `Collection<Reaction>` and generics are invariant, so the island's own locals adopt the
     * wildcard - the same fact that first bit `QuickLoader`.
     */
    fun getReactions(): Collection<ReactionRef>

    fun getReadyByTrue(): Set<@JvmSuppressWildcards Jid>

    fun isDeleted(): Boolean

    fun isEphemeral(): Boolean

    fun isGeoUri(): Boolean

    fun isPrivateMessage(): Boolean

    fun isRead(): Boolean

    fun treatAsDownloadable(): Boolean

    fun trusted(): Boolean

    fun prev(): MessageRef?

    /** The overload the island calls; the model adapts it to `sameMucUser(Message)` with one cast. */
    fun sameMucUser(otherMessage: MessageRef?): Boolean

    // -- writing ------------------------------------------------------------------------------------
    fun addPayload(element: Element?)

    fun clearPayloads()

    /**
     * `boolean`, not `void`: the model's own declaration, mirrored here.
     *
     * port-13: `ReadByMarkerRef` retired - the island built one of those only to hand it straight
     * back, so the pair crosses as the two `Jid`s it is made of and `:data`'s
     * `Message` builds the model value itself. `MessageParser`'s one call site already
     * had both in hand.
     */
    fun addReadByMarker(fullJid: Jid, realJid: Jid?): Boolean

    fun markRead()

    fun markUnread()

    /**
     * **Distinctly named**, because the model's own `setReactions(Collection&lt;Reaction&gt;)` erases
     * to the same `setReactions(Collection)` and javac refuses the pair as a name clash - the same
     * language fact that keeps `AccountRef.setBookmarks` absent. `Message.replaceReactions`
     * casts once and delegates.
     */
    fun replaceReactions(reactions: Collection<out ReactionRef>?)

    fun setBody(body: String?)

    fun setBodyLanguage(language: String?)

    fun setCarbon(carbon: Boolean)

    fun setCounterpart(counterpart: Jid?)

    fun setDeleted(deleted: Boolean)

    fun setEncryption(encryption: Int)

    fun setEphemeralTimer(timer: Int)

    fun setExpireAt(expireAt: Long)

    /** The overload the island calls; the two file-params types do not erase alike. */
    fun setFileParams(fileParams: MessageRef.FileParamsRef?)

    fun setFingerprint(fingerprint: String?)

    /** The overload the island calls; the two message types do not erase alike. */
    fun setInReplyTo(message: MessageRef?)

    /** The public field `markable`, as a member; see the class comment for why only the setter. */
    fun setMarkable(markable: Boolean)

    /** The overload the island calls; the two participant types do not erase alike. */
    fun setMucUser(user: MucOptionsRef.UserRef?)

    fun setOccupantId(occupantId: String?)

    fun setRelativeFilePath(path: String?)

    fun setRemoteMsgId(remoteMsgId: String?)

    fun setRetractId(retractId: String?)

    fun setServerMsgId(serverMsgId: String?)

    fun setStatus(status: Int)

    fun setSubject(subject: String?)

    fun setThread(thread: Element?)

    fun setTime(time: Long)

    fun setTranslatedBody(translatedBody: String?)

    fun setTranslationLang(translationLang: String?)

    fun setTranslationState(translationState: Int)

    fun setTrueCounterpart(trueCounterpart: Jid?)

    fun setType(type: Int)

    fun setUuid(uuid: String?)

    fun putEdited(edited: String?, serverMsgId: String?)

    // -- part 16: what `XmppConnectionService` reads and writes ------------------------------------
    //
    // The send path, the read-marker path and the notification path. Every one of these was a bare
    // `message.method()` on a local that the part-16 rename turned into this ref, so the list is the
    // compiler's and not a scroll (see the commit body). They are ordinary overrides except the two
    // called out below.

    /** The model answers `Contact`, which implements this ref - a covariant override. */
    fun getContact(): ContactRef?

    fun getType(): Int

    fun getExpireAt(): Long

    fun getEncryptedBody(): String?

    fun isCarbon(): Boolean

    fun edited(): Boolean

    fun getEditedId(): String

    fun fixCounterpart(): Boolean

    fun needsUploading(): Boolean

    fun isFileOrImage(): Boolean

    fun hasFileOnRemoteHost(): Boolean

    fun isOOb(): Boolean

    fun getRetractId(): String?

    fun isEphemeralIWantOut(): Boolean

    fun getEditedIdWireFormat(): String

    /** The model's own varargs signature, like [clearFallbacks]. */
    fun getFallbacks(vararg includeFor: String): List<@JvmSuppressWildcards Element>

    fun getErrorMessage(): String?

    fun getMimeType(): String?

    /**
     * The transferable a message carries. Covariant: the model answers the same type and admits the
     * null its Java field always could.
     */
    fun getTransferable(): Transferable?

    /**
     * The one setter. It used to be a pair - a ref-typed member here and a model-typed one on the
     * class, which narrowed with a cast - and 2026-10-08 left a single type, so there is no overload
     * to pick between and nothing left that can throw.
     */
    fun setTransferable(transferable: Transferable?)

    fun hasCustomEmoji(): Boolean

    fun getLinks(): List<@JvmSuppressWildcards URI>

    /** Return-position drag: the model answers `Message`, which implements this ref. */
    fun reply(): MessageRef

    /** Return-position drag, same as [reply]. */
    fun next(): MessageRef?

    /**
     * The read side of the model's public `markable` field, added by part 16 because the send
     * path reads it before deciding whether to ask for a displayed marker. The model still keeps the
     * field public and mutable; this is the island's accessor and [setMarkable] its setter.
     */
    fun isMarkable(): Boolean

    /**
     * **Distinctly named on purpose.** The model's accessor is `getAggregatedReactions()`,
     * which answers `Reaction.Aggregated` - a `:data` type with two public collection fields -
     * and the island's one site reads a single field of it (`ourReactions`) and nothing else.
     * A member spelled `getAggregatedReactions()` returning `Set<String>` would be an
     * override with an incompatible return type, so the accessor is derived and named for what it
     * answers. `Message` implements it as `getAggregatedReactions().ourReactions`.
     */
    fun getAggregatedOurReactions(): Set<String>

    /** `boolean`, not `void`: the model's own declaration, mirrored here. */
    fun setErrorMessage(errorMessage: String?): Boolean

    fun appendBody(append: String)

    /** The model's own varargs signature. */
    fun clearFallbacks(vararg includeFor: String)

    fun clearLinkDescriptions()

    fun setHtml(html: Element?)

    fun resetFileParams()

    fun markNotificationDismissed()

    companion object {
        // -- the constants `MessageParser` names --------------------------------------------------------
        //
        // The statuses, the encryptions and the two types the file branches on. Same values as
        // `uk.xa0.tulkki.data.model.Message`, which copies four of them from `:crypto`'s `OmemoMessage`.
        @JvmField
        val STATUS_RECEIVED: Int = 0

        @JvmField
        val STATUS_UNSEND: Int = 1

        @JvmField
        val STATUS_SEND: Int = 2

        @JvmField
        val STATUS_SEND_FAILED: Int = 3

        @JvmField
        val STATUS_WAITING: Int = 5

        @JvmField
        val STATUS_OFFERED: Int = 6

        @JvmField
        val STATUS_SEND_RECEIVED: Int = 7

        @JvmField
        val STATUS_SEND_DISPLAYED: Int = 8

        @JvmField
        val ENCRYPTION_NONE: Int = 0

        @JvmField
        val ENCRYPTION_PGP: Int = 1

        @JvmField
        val ENCRYPTION_OTR: Int = 2

        @JvmField
        val ENCRYPTION_DECRYPTED: Int = 3

        @JvmField
        val ENCRYPTION_AXOLOTL: Int = 5

        @JvmField
        val ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE: Int = 6

        @JvmField
        val ENCRYPTION_AXOLOTL_FAILED: Int = 7

        @JvmField
        val TYPE_TEXT: Int = 0

        @JvmField
        val TYPE_IMAGE: Int = 1

        @JvmField
        val TYPE_FILE: Int = 2

        @JvmField
        val TYPE_PRIVATE_FILE: Int = 5

        @JvmField
        val TYPE_PRIVATE: Int = 4

        @JvmField
        val TYPE_RTP_SESSION: Int = 6

        @JvmField
        val TRANSLATION_NONE: Int = 0

        /** `Message.ERROR_MESSAGE_CANCELLED`, the marker text a cancelled transfer carries. */
        @JvmField
        val ERROR_MESSAGE_CANCELLED: String = "eu.siacs.conversations.cancelled"

    }
}
