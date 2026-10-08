package uk.xa0.tulkki.ui.projection

/**
 * One line of a conversation list row, "Design: the Compose UI" §2.2's `preview`, and §2.3 invariant
 * 4's rule that makes it safe: the preview is produced by the projector from the same
 * `MessageSnapshot` the bubble uses, so "concealing the row conceals the preview by construction,
 * because there is no second copy to forget".
 *
 * <p>**The owner's draft is the one line that is not the pointer's message**, and it does not take a case
 * of its own: it is a [Visible] with `draft = true`, decided by the row projection before it asks
 * [MessagePreview] anything, because a conversation the owner has started typing in shows the draft
 * rather than the message it also has. A second text-carrying case would be a second place a `String`
 * reaches a Composable, which is the one thing these types exist to prevent.
 *
 * <p>The branch order that picks a case is `UIHelper.getMessagePreview`'s (§2.2.1 #9). §2.2.1 also
 * records that the tree's two renderers disagree about it - the list tests encryption before
 * file/image, the bubble tests file/image first, so an OMEMO-failed image is `Encryption` in one and
 * a media preview in the other - and leaves that contradiction for the lane rather than inventing
 * around it; this type does not decide it, and the projector's commit will have to.
 *
 * <p>**`File(kind, name)` is not here and is a named hole.** §2.2.1 #6 marks `FileKind` **deferred**:
 * the preview produces one localised `String` and never a kind (its ~19 MIME branches return
 * `getString`), the tree's only kind enum is the attachment-type list used for icons, and the
 * preview never reads a filename either - so the case has neither of its two payloads decided.
 * `Call`'s media (audio/video) is deferred for the same reason but does not take its case with it:
 * `CallKind`'s three kinds are decided.
 */
sealed interface UiPreview {

    /**
     * The drawn line, and the only case any text reaches a Composable through - §2.3 invariant 1's whole
     * defence is that these types declare exactly one `String`-typed property, which
     * `ProjectionTypesTest.theOnlyStringAComposableCanReachIsTheVisibleText` holds.
     *
     * <p>`draft` says **whose** text it is rather than adding a second carrier. `false` is the row the
     * pointer names: the text a received body produced, and what §2.3 invariant 5's `surfaceText` redacts.
     * `true` is the owner's own stored draft - the line a conversation they have started typing in shows
     * while nothing is unread - which is nobody's received original and so is not a concealment question at
     * all. Provenance, not a second concealment decision.
     *
     * <p>`icon` is the 18sp message-type mark the deleted row drew beside this line - a `@DrawableRes` or
     * `null` for a line that is only words. It is an `Int?` and not a `String`, so the invariant above is
     * untouched by it.
     */
    data class Visible(val text: String, val draft: Boolean = false, val icon: Int? = null) : UiPreview

    /** Translation was needed and did not happen: the row says so and shows nothing of the body. */
    data object Covered : UiPreview

    /** An encrypted row's preview, with the kind of encryption showing. */
    data class Encryption(val kind: EncryptionKind) : UiPreview

    /** A call's preview. */
    data class Call(val kind: CallKind) : UiPreview

    /** Nothing to preview. */
    data object Absent : UiPreview
}

/**
 * The five encryption kinds §2.2.1 #7 defines, one per preview branch of `UIHelper`: PGP, OTR, a PGP
 * decryption that failed, OMEMO for another device, and an OMEMO decryption that failed.
 *
 * <p>§2.2.1 records that "the spellings are ours - the design names none", and that all five are
 * reached only when the row has no transferable. (`CryptoHelper.encryptionTypeToText` is the
 * composer's scheme choice, not this vocabulary.)
 */
enum class EncryptionKind {
    PGP,
    OTR,
    PGP_DECRYPTION_FAILED,
    OMEMO_NOT_FOR_THIS_DEVICE,
    OMEMO_DECRYPTION_FAILED,
}

/**
 * The three call kinds §2.2.1 #8 defines from `Message.TYPE_RTP_SESSION`: a missed call, and an
 * incoming or outgoing one. Audio/video is deferred - the preview's only source carries `successful`
 * and `duration` and no media field - and duration is not consulted here either.
 */
enum class CallKind {
    MISSED,
    INCOMING,
    OUTGOING,
}
