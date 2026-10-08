package uk.xa0.tulkki.ui.projection

import androidx.compose.ui.graphics.ImageBitmap

/**
 * The attachment cell on a transfer row, and the four states it is honest about: in flight, ready,
 * failed, and no local file yet.
 *
 * <p>**Why it exists.** A file or image row with no cell is an empty bubble by construction: the
 * projection answers `top = Absent` for every `MessageType.TRANSFER` (a file row's body is the address
 * or the path, never prose), and the row therefore drew a timestamp and nothing else. This is the
 * missing half - the row's *content* when the content is a file.
 *
 * <p>**What it is not.** It is not [UiBody]: a file row has no body to conceal, and the provenance of
 * the two texts a text row carries (the app language's side and the conversation's) does not apply.
 * Nothing here is ever the message's body: [name] comes from the `urn:xmpp:sims:1` payload's own
 * `name` element (or is `null`), never from `MessageSnapshot.bodies`, so the raw-text rule is untouched
 * - a file's name is a filename, and a filename is not a message.
 *
 * <p>**The four states are one field, and they are distinguishable on purpose** - the defect this cell
 * answers was a completed transfer and an offer with nothing on the phone drawing the same empty row:
 *
 * <ul>
 *   <li>*in flight* - [state] is [UiTransferState.Offered], [UiTransferState.Downloading],
 *       [UiTransferState.Uploading] or [UiTransferState.Checking]. The row draws the transfer's own
 *       direction and progress, and no local file exists yet;
 *   <li>*ready* - [state] is [UiTransferState.Ready]: the file is on the phone and the row draws it
 *       (an image draws [thumbnail] where the host supplied pixels, otherwise a named plate; a file
 *       draws its name and size);
 *   <li>*failed* - [state] is [UiTransferState.Failed]: the transfer stopped and is not coming back on
 *       its own;
 *   <li>*no local file yet* - [state] is [UiTransferState.None] on a row that *is* a transfer (a received
 *       file whose offer carried no transferable any more, or one whose transfer object is gone): the
 *       row draws the remote attachment's name and size and a download mark.
 * </ul>
 *
 * <p>**`None` means "no local file" inside this cell, and never "not a transfer row".** The cell only
 * exists for `MessageType.TRANSFER`, so the absence of a state is the absence of a local file - which is
 * the fourth state, not a fifth, and the reason it is not a separate case of its own.
 *
 * <p>[Cancelled] is the tree's own fifth outcome and draws as itself; it is a terminal state like
 * [Failed] and is kept apart from it because the tree kept it apart.
 *
 * @param kind which of the three file-bearing kinds the row is, from `Message.TYPE_*`
 * @param name the file's own name from the payload tree, or `null` when no live row could answer it
 * @param sizeBytes the transfer's size as the store serialised it, or `null` when the row carries none
 * @param localUri the local file when it is on the phone: the host's absolute URI from
 *     [UiTransferState.Ready], or the snapshot's stored app-relative path as the fallback. Never drawn
 *     as text
 * @param remoteUri the wire address a not-yet-downloaded file is fetched from, or `null`
 * @param thumbnail the ready image's pixels, which only the host can build, or `null` when it cannot
 * @param state the transfer's own state, [UiTransferState.None] when the row has no local file and no
 *     live transfer
 */
data class UiAttachment(
    val kind: AttachmentKind,
    val name: String?,
    val sizeBytes: Long?,
    val localUri: String?,
    val remoteUri: String?,
    val thumbnail: ImageBitmap?,
    val state: UiTransferState,
) {

    /**
     * Ids and shapes only. A generated `toString()` would put the filename into every log line and
     * crash report - the same leak `UiMessage`'s own override is written against, applied to the one
     * other string a row can carry.
     */
    override fun toString(): String =
        "UiAttachment(kind=$kind, name=${name != null}, sizeBytes=$sizeBytes, " +
            "localUri=${localUri != null}, remoteUri=${remoteUri != null}, " +
            "thumbnail=${thumbnail != null}, state=${state::class.simpleName})"
}

/**
 * Which of the three file-bearing message kinds a transfer row carries.
 *
 * <p>It is the one decision the cell makes from the row that a snapshot *can* make (`Message.TYPE_IMAGE`
 * against the two file types), and it decides one thing only: whether the cell may draw pixels. The
 * MIME's own ~19-word vocabulary (`MessagePreview.fileDescription`) is not reproduced here - the
 * redesign licence applies, and the file's name is the better label where there is one.
 */
enum class AttachmentKind {
    /** `Message.TYPE_IMAGE`: the cell draws [UiAttachment.thumbnail] where the host supplied one. */
    IMAGE,

    /** `TYPE_FILE` / `TYPE_PRIVATE_FILE`: the cell draws a name, a size and a state. */
    FILE,
}
