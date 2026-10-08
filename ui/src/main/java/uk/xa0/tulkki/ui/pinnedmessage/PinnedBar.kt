package uk.xa0.tulkki.ui.pinnedmessage

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.widget.Toast

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

import com.bumptech.glide.Glide

import io.ipfs.cid.Cid

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import uk.xa0.tulkki.data.utils.MimeUtils
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import uk.xa0.tulkki.ui.pinnedmessage.PinnedMessageRepository.DecryptedPinnedMessageData

import java.io.File
import java.util.function.Consumer

/**
 * Tulkki: the pinned-message bar, in Compose.
 *
 * <p>It replaces the Java bar the conversation shell drew: the `LinearLayout` in
 * `fragment_conversation.xml` and the eight Java methods that filled it in
 * (`loadAndDisplayLatestPinnedMessage`, `hidePinnedMessageView`, `handlePinnedMessagesLoaded`,
 * `showPinnedMessagesPopup`, `onPinnedMessageItemClick`, `onUnpinClick`,
 * `unpinCurrentDisplayedMessage`, `isDisplayableMediaCid`, `isAudioCid`) are gone, and so is
 * `PinnedMessageAdapter` and its row layout `item_pinned_message.xml`.
 *
 * <p>**The reading is here, not in the fragment.** The old bar was a view the fragment had to fill
 * from a background thread and then hide again; the bar now reads
 * [PinnedMessageRepository.getAllDecryptedPinnedMessagesForConversation] itself, decides its own
 * content, and owns its own list. The fragment supplies only the two facts only it has: which
 * conversation is on screen, and what "jump to this message" means. Everything else - the media
 * kind, the thumbnail, the popup, the unpin - is below this line.
 *
 * <p>**It is a redesign, not a reproduction.** The old surface was a bar whose long press opened a
 * `ListPopupWindow` of the pins and whose tap jumped; that contract is kept (tap jumps, long press
 * opens the list when there is more than one pin), but the content rule is the shell's own
 * [PinnedKind] decision rather than the Java `if` chain's, a video pin draws the file line instead
 * of an image thumbnail that Glide could not load, and no view ever has to be hidden.
 */
object PinnedBarHost {

    /**
     * Sets the bar's content once, over the live [PinnedBarController], and answers the controller
     * the caller keeps so it can name the conversation and ask for a re-read.
     *
     * @param media the island's `getFileForCid`, wrapped by the fragment as a path - the bar never
     *     names the island's own type, so this file adds no boundary reach of its own
     * @param jumpToMessage what a tap means - the fragment's own `updateSelection`
     * @param darkTheme the shell's theme, from `ConversationFragment.darkTheme()`
     */
    @JvmStatic
    fun install(
        view: ComposeView,
        repository: PinnedMessageRepository,
        media: PinnedMediaResolver,
        jumpToMessage: Consumer<String>,
        darkTheme: Boolean,
    ): PinnedBarController {
        val controller = PinnedBarController()
        view.setTulkkiContent(darkTheme) {
            PinnedBarContent(controller, repository, media, jumpToMessage)
        }
        return controller
    }
}

/**
 * The bar's content, for a host that composes it directly rather than setting it on a `ComposeView`
 * of its own: [PinnedBarHost.install]'s body without the view.
 *
 * <p>It exists because a screen that has become one composition cannot give this surface a view to
 * hang from - `ConversationFragment`'s shell draws the pinned bar, the emoji panel and the correction
 * bar inside its own content - and the two doors must draw the same bar, so `install` calls this and
 * so does the shell. The controller is the caller's own, because it is the caller that names the
 * conversation and asks for a re-read.
 */
@Composable
fun PinnedBarContent(
    controller: PinnedBarController,
    repository: PinnedMessageRepository,
    media: PinnedMediaResolver,
    jumpToMessage: Consumer<String>,
) {
    PinnedBar(controller, repository, media, jumpToMessage)
}

/**
 * The one thing the bar cannot read for itself: a `cid:` attachment's local file. The fragment
 * resolves the cid against the XMPP service - which is the island's business, and why this answers a
 * plain path rather than the island's own `DownloadableFileRef`: a `:ui` Kotlin file that imported
 * that interface would add a `ui-reaches-island` boundary violation of its own.
 */
fun interface PinnedMediaResolver {
    /** The local file path behind a cid, or `null` when the service holds no file for it. */
    fun path(cid: Cid): String?
}

/**
 * The bar's two live inputs, held as Compose state so the Java fragment can move them without
 * rebuilding the view.
 *
 * <p>[setConversation] is a no-op when the value is unchanged, so a fragment refresh that lands in
 * the same conversation does not recompose the bar; [reload] always does, because a refresh is the
 * one moment a pin may have been added or removed elsewhere.
 */
class PinnedBarController internal constructor() {

    internal val conversationUuid: MutableState<String?> = mutableStateOf(null)
    internal val revision: MutableState<Int> = mutableStateOf(0)

    fun setConversation(uuid: String?) {
        conversationUuid.value = uuid
    }

    fun reload() {
        revision.value = revision.value + 1
    }
}

/** What the bar's leading content is drawn from, decided once per pin. */
private enum class PinnedKind {
    IMAGE,
    AUDIO,
    FILE,
    TEXT,
}

/** One pin, already resolved: what to draw, and the text to draw beside it. */
private class PinnedView(
    val messageUuid: String,
    val text: String,
    val kind: PinnedKind,
    val thumbnail: Bitmap?,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PinnedBar(
    controller: PinnedBarController,
    repository: PinnedMessageRepository,
    media: PinnedMediaResolver,
    jumpToMessage: Consumer<String>,
) {
    val context = LocalContext.current
    val conversationUuid = controller.conversationUuid.value
    val revision = controller.revision.value

    var pins by remember { mutableStateOf<List<DecryptedPinnedMessageData>>(emptyList()) }
    var shown by remember { mutableStateOf<PinnedView?>(null) }
    var listOpen by remember { mutableStateOf(false) }

    LaunchedEffect(conversationUuid, revision) {
        val loaded: List<DecryptedPinnedMessageData> = if (conversationUuid == null) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) {
                repository.getAllDecryptedPinnedMessagesForConversation(conversationUuid)
            }
        }
        val latest = loaded.firstOrNull()
        pins = loaded
        // The media lookup reads the file system, and reading the image is a Glide fetch, so both
        // halves are off the main thread - the same rule the Java bar followed.
        shown = if (latest == null) null else withContext(Dispatchers.IO) { resolve(context, latest, media) }
    }

    val current = shown ?: return

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .combinedClickable(
                    onClick = { jumpToMessage.accept(current.messageUuid) },
                    onLongClick = { if (pins.size > 1) listOpen = true },
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.outline_push_pin_24),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(8.dp))
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                PinnedContent(current)
            }
            IconButton(
                onClick = { unpin(context, repository, controller, current.messageUuid) },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.rounded_close_24),
                    contentDescription = stringResource(R.string.unpin_message),
                )
            }
        }

        DropdownMenu(expanded = listOpen, onDismissRequest = { listOpen = false }) {
            pins.forEach { pin ->
                DropdownMenuItem(
                    text = {
                        Text(labelOf(pin), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    onClick = {
                        listOpen = false
                        jumpToMessage.accept(pin.messageUuid)
                    },
                    trailingIcon = {
                        IconButton(onClick = { unpin(context, repository, controller, pin.messageUuid) }) {
                            Icon(
                                painter = painterResource(R.drawable.rounded_close_24),
                                contentDescription = stringResource(R.string.unpin_message),
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun labelOf(pin: DecryptedPinnedMessageData): String =
    pin.plaintextBody?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.pinned_media)

@Composable
private fun PinnedContent(view: PinnedView) {
    when (view.kind) {
        PinnedKind.IMAGE -> {
            val thumbnail = view.thumbnail
            if (thumbnail == null) {
                PinnedFileLine(R.drawable.ic_description_24dp, view.text)
            } else {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = stringResource(R.string.pinned_image_preview),
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
                )
            }
        }
        PinnedKind.AUDIO -> PinnedFileLine(R.drawable.audio_file_24dp, view.text)
        PinnedKind.FILE -> PinnedFileLine(R.drawable.ic_description_24dp, view.text)
        PinnedKind.TEXT -> Text(
            text = view.text,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun PinnedFileLine(icon: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(32.dp))
        if (text.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * The Java bar's content rule, with its two holes closed: `plaintextBody` may be null on a media
 * pin, and a body-less pin is not drawn at all. An image whose file is missing or whose fetch
 * fails keeps a file line rather than the empty `ImageView` the Java left behind.
 */
private fun resolve(
    context: Context,
    pin: DecryptedPinnedMessageData,
    media: PinnedMediaResolver,
): PinnedView? {
    val body = pin.plaintextBody
    val cid = pin.cid
    if (cid == null) {
        if (body.isNullOrEmpty()) {
            return null
        }
        return PinnedView(pin.messageUuid, body, PinnedKind.TEXT, null)
    }

    val path = media.path(cid) ?: return PinnedView(pin.messageUuid, body.orEmpty(), PinnedKind.FILE, null)
    val mime = MimeUtils.guessFromPath(path)
    if (mime != null && mime.startsWith("image/")) {
        val bitmap = bitmapOf(context, path)
        if (bitmap != null) {
            return PinnedView(pin.messageUuid, body.orEmpty(), PinnedKind.IMAGE, bitmap)
        }
    }
    val kind = if (mime != null && mime.startsWith("audio/")) PinnedKind.AUDIO else PinnedKind.FILE
    return PinnedView(pin.messageUuid, body.orEmpty(), kind, null)
}

private fun bitmapOf(context: Context, path: String): Bitmap? =
    try {
        // The application context on purpose: this runs off the main thread, and Glide's Activity
        // path must not be entered from a background fetch on a view that may be detaching.
        Glide.with(context.applicationContext).asBitmap().load(File(path)).submit().get()
    } catch (e: Exception) {
        null
    }

private fun unpin(
    context: Context,
    repository: PinnedMessageRepository,
    controller: PinnedBarController,
    messageUuid: String,
) {
    val main = Handler(Looper.getMainLooper())
    repository.unpinMessage(
        messageUuid,
        object : PinnedMessageRepository.OnUnpinCompleteListener {
            override fun onUnpinComplete(success: Boolean) {
                main.post {
                    Toast.makeText(
                        context,
                        if (success) R.string.message_unpinned else R.string.error_unpinning_message,
                        Toast.LENGTH_SHORT,
                    ).show()
                    if (success) {
                        controller.reload()
                    }
                }
            }
        },
    )
}
