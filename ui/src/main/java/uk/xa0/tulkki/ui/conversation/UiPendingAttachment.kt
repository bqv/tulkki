package uk.xa0.tulkki.ui.conversation

import androidx.compose.ui.graphics.ImageBitmap
import uk.xa0.tulkki.ui.projection.AttachmentKind

/**
 * One file or image staged for the next send: the composer's own pending-attachment row, and the
 * state the preview strip draws.
 *
 * <p>**It is deliberately not [uk.xa0.tulkki.ui.projection.UiAttachment].** That type is the *row's*
 * cell, and its `state` is a transfer's four honest outcomes (`Offered`/`Downloading`/`Uploading`/
 * `Checking`, `Ready`, `Failed`/`Cancelled`, and `None` meaning "a transfer row with nothing on the
 * phone"). A staged attachment has not been offered, uploaded or downloaded at all - it is a local
 * file the owner has just picked - so it has no transfer state to be honest about, and copying the
 * cell's fields here would be the duplication the lane boundary forbids. The cell draws a transfer;
 * this draws a draft.
 *
 * <p>**The strip carries no name, and the raw-text rule is why it needs none.** A file row's name
 * comes from `row.getFileParams()?.getName()`; a staged attachment is not a row, and the `:data`
 * [uk.xa0.tulkki.data.utils.Attachment] a picker hands back carries a URI, a MIME and a type but no
 * name at all. The one string that *could* stand in for it is the message body - the address or the
 * path - and drawing that is the leak the repo forbids absolutely. So the strip draws the pixels
 * where the host built them and a kind's plate otherwise, and never words about the file.
 *
 * @param id the attachment's own identity from the host, so a strip row can be tapped or removed by
 *     name without the row being re-derived: the attachment's uuid, never a filename and never a body
 * @param kind which of the two file-bearing kinds it is, borrowed from the projection's own
 *     [AttachmentKind] so the strip and the cell agree on what "the pixels decide" means
 * @param thumbnail the ready image's pixels, which only the host can build, or `null` when it cannot
 *     or the kind is [AttachmentKind.FILE]
 */
data class UiPendingAttachment(
    val id: String,
    val kind: AttachmentKind,
    val thumbnail: ImageBitmap? = null,
) {

    /**
     * Shapes and a count, never the id's text: a host that passed something other than an opaque id
     * must not find it in a log line or a crash report. [UiAttachment]'s own override is written
     * against the same leak.
     */
    override fun toString(): String =
        "UiPendingAttachment(id=${id.isNotEmpty()}, kind=$kind, thumbnail=${thumbnail != null})"
}
