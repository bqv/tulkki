package uk.xa0.tulkki.ui.projection

/**
 * An attachment's transfer, "Design: the Compose UI" §2.2's cases, mapped to `Transferable.STATUS_*`
 * by §2.2.1 "The missing definitions" #9 - with the two corrections that section makes to §2.2's own
 * comment: `None` is **not** a status, it is the absence of a transferable; and `Ready` is not a
 * status either, it is the downloaded local file the bubble draws directly (over the snapshot's
 * `relativeFilePath` / `fileDeleted`), which is why it carries a URI and not a progress.
 *
 * <p>**`Failed` carries no reason, and `TransferFailure` stays deferred.** §2.2.1 #5 marks the typed
 * `TransferFailure` **deferred**: `Transferable.STATUS_*` is a status int with no failure names,
 * no failure type exists in the tree, and the only reason carrier is the free-form `errorMsg` String.
 * The tree tells apart exactly two outcomes - `FAILED` and `CANCELLED` - while the richer Jingle
 * `Reason` names are collapsed before they reach a row and survive only as initiator-side error text.
 * The *outcome* is expressible without the vocabulary that would name it, and the attachment cell needs
 * it: a failed transfer, a cancelled one and a not-yet-downloaded one are three different rows, and a
 * reasonless [Failed] is the honest shape until a reason can be derived.
 */
sealed interface UiTransferState {

    /** No transferable on the row at all. */
    data object None : UiTransferState

    /** `STATUS_OFFER` / `STATUS_OFFER_CHECK_FILESIZE`: offered, size unknown until measured. */
    data class Offered(val sizeBytes: Long?) : UiTransferState

    /** `STATUS_DOWNLOADING`. */
    data class Downloading(val progress: Int, val sizeBytes: Long?) : UiTransferState

    /** `STATUS_UPLOADING`. */
    data class Uploading(val progress: Int) : UiTransferState

    /** `STATUS_CHECKING`. */
    data object Checking : UiTransferState

    /** `STATUS_CANCELLED`. */
    data object Cancelled : UiTransferState

    /**
     * `STATUS_FAILED`. Reasonless on purpose: `TransferFailure` is deferred (see the type's KDoc), and a
     * reason this vocabulary cannot derive must not be invented. The row draws the failure, not a cause.
     */
    data object Failed : UiTransferState

    /** The downloaded file the bubble draws: a local URI, never a status. */
    data class Ready(val localUri: String) : UiTransferState
}
