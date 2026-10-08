package uk.xa0.tulkki.ui.conversation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.xa0.tulkki.data.FileBackend
import uk.xa0.tulkki.data.FileBackends
import uk.xa0.tulkki.data.utils.Attachment
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.ShowLocationActivity
import uk.xa0.tulkki.ui.projection.AttachmentKind

/**
 * Tulkki: the files and images staged for the next send, and the state the Compose composer's strip
 * draws. It is the Java `MediaPreviewAdapter`'s list, moved off a `RecyclerView` adapter and onto the
 * draft surface: the adapter held the list and drew one cell per attachment; the list is what the
 * send path reads (`getAttachments`, `hasAttachments`, `getItemCount`, `clearPreviews`) and the
 * drawing is `ConversationComposer`'s `PendingAttachments`, so only the list belongs here.
 *
 * <p>**The thumbnails are built asynchronously, and this is the whole reason the move is not a
 * rename.** `UiPendingAttachment.thumbnail` is an `ImageBitmap` and the Java adapter loaded its
 * pixels on an `AsyncTask`; building one on the UI thread would drop every image preview the moment
 * the list was re-read. So [strip] answers the pixels it has and [scope] loads the rest in the
 * background, calling [onChanged] once per loaded thumbnail so the host re-reads the strip - the
 * same two-step the adapter's `loadPreview`/`BitmapWorkerTask` pair was.
 *
 * <p>**It decides nothing.** The kind, the pixels and the list's order are the data's; opening a
 * staged file and editing a staged image are the fragment's two answers (`onAttachmentTap`), and
 * this only names the attachment a caller asked for. Nothing here reads a setting, a conversation or
 * a translation.
 *
 * @param previewSize the square the pixels are cropped to, the Java
 *     `R.dimen.media_preview_size` the adapter read
 * @param onChanged the host's re-read, run on the main thread after the list or a thumbnail moves
 * @param scope the scope the thumbnail loads run in; the host builds it with
 *     [ConversationRead.viewScope] and cancels it with [release], and a JVM cell hands its own
 */
class StagedAttachments(
    private val previewSize: Int,
    private val onChanged: Runnable,
    private val scope: CoroutineScope,
) {

    private val staged = ArrayList<Attachment>()

    /** Loaded pixels by the attachment's own uuid; absent means "not built yet", never "no image". */
    private val thumbnails = HashMap<String, ImageBitmap>()

    /** The uuids a load is already in flight for, so a re-read does not queue a second one. */
    private val loading = HashSet<String>()

    /** Stage [attachments] after the ones already there, and start any thumbnail they need. */
    fun add(attachments: List<Attachment>) {
        if (attachments.isEmpty()) {
            return
        }
        staged.addAll(attachments)
        loadThumbnails()
        onChanged.run()
    }

    /**
     * The image editor's answer: the edited file replaces the staged attachment whose uri was
     * handed to the editor, or is staged as a new one when nothing was. It is the Java
     * `replaceOrAddMediaPreview`'s two branches, unchanged.
     *
     * [originalUri] is nullable because the Java's was: `ConversationFragment` hands it
     * `Intent.getData()`, and the Java compared that with `equals` rather than dereferencing it, so a
     * data-less result answered "nothing was replaced" instead of crashing.
     */
    fun replaceOrAdd(
        context: Context,
        originalUri: Uri?,
        editedUri: Uri,
        type: Attachment.Type,
    ) {
        var replaced = false
        for (position in staged.indices) {
            val current = staged[position]
            if (current.getUri() == originalUri) {
                val replacement = Attachment.of(context, editedUri, current.getType()).firstOrNull()
                if (replacement != null) {
                    staged[position] = replacement
                    thumbnails.remove(current.getUuid().toString())
                }
                replaced = true
            }
        }
        if (!replaced) {
            staged.addAll(Attachment.of(context, editedUri, type))
        }
        loadThumbnails()
        onChanged.run()
    }

    /** Drop the attachment [id] names; an id nothing answers is no change at all. */
    fun remove(id: String) {
        val uuid = uuid(id) ?: return
        val removed = staged.removeAll { it.getUuid() == uuid }
        if (removed) {
            thumbnails.remove(id)
            loading.remove(id)
            onChanged.run()
        }
    }

    /** The attachment [id] names, or `null` - the fragment resolves a row's tap with this. */
    fun byId(id: String): Attachment? {
        val uuid = uuid(id) ?: return null
        return staged.firstOrNull { it.getUuid() == uuid }
    }

    /**
     * The staged list as the send path reads it: a copy, because the Java
     * `MediaPreviewAdapter.getAttachments()` handed out its own list and `commitAttachments`
     * iterates it while it removes. A copy keeps that iteration safe.
     */
    fun attachments(): ArrayList<Attachment> = ArrayList(staged)

    fun hasAttachments(): Boolean = staged.isNotEmpty()

    /** How many are staged, which the Java caption row's one-attachment case reads. */
    fun count(): Int = staged.size

    /** Empty the strip. The Java `clearPreviews()` cleared the list and left the pixels cached. */
    fun clear() {
        if (staged.isEmpty()) {
            return
        }
        staged.clear()
        thumbnails.clear()
        loading.clear()
        onChanged.run()
    }

    /** Cancel every in-flight load: the view that owned the strip is going. */
    fun release() {
        ConversationRead.releaseScope(scope)
    }

    /**
     * The strip's state: one [UiPendingAttachment] per staged file, in the send's own order, with
     * the pixels the background load has built so far or `null` while it has not. The `kind` is the
     * attachment's own type, so the plate a pixel-less row draws is the data's answer rather than a
     * guess made here.
     */
    fun strip(): List<UiPendingAttachment> =
        staged.map { attachment ->
            val id = attachment.getUuid().toString()
            UiPendingAttachment(
                id = id,
                kind = kindOf(attachment),
                thumbnail = thumbnails[id],
            )
        }

    /**
     * Open a staged attachment: the image editor is the fragment's answer for an image, and this is
     * the Java `MediaPreviewAdapter.view`'s for everything else - a location on the map screen, a
     * file through whatever application will take it.
     */
    fun open(context: Context, attachment: Attachment) {
        val view = Intent(Intent.ACTION_VIEW)
        if (attachment.getType() == Attachment.Type.LOCATION) {
            view.setClass(context, ShowLocationActivity::class.java)
            view.setData(attachment.getUri())
        } else {
            val uri = FileBackend.getUriForUri(context, attachment.getUri())
            view.setDataAndType(uri, attachment.getMime())
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(view)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.no_application_found_to_open_file, Toast.LENGTH_SHORT)
                .show()
        } catch (e: SecurityException) {
            Toast.makeText(
                context,
                R.string.sharing_application_not_grant_permission,
                Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun loadThumbnails() {
        for (attachment in staged) {
            if (!attachment.renderThumbnail()) {
                continue
            }
            val id = attachment.getUuid().toString()
            if (thumbnails.containsKey(id) || !loading.add(id)) {
                continue
            }
            scope.launch {
                val bitmap =
                    withContext(Dispatchers.IO) {
                        runCatching {
                                FileBackends
                                    .get()
                                    .getPreviewForUri(attachment, previewSize, false)
                            }
                            .getOrNull()
                    }
                loading.remove(id)
                if (bitmap != null) {
                    thumbnails[id] = bitmap.asImageBitmap()
                    onChanged.run()
                }
            }
        }
    }

    private fun kindOf(attachment: Attachment): AttachmentKind =
        if (attachment.getType() == Attachment.Type.IMAGE) {
            AttachmentKind.IMAGE
        } else {
            AttachmentKind.FILE
        }

    private fun uuid(id: String): UUID? = runCatching { UUID.fromString(id) }.getOrNull()
}
