package uk.xa0.tulkki.ui.posts

import android.net.Uri
import android.widget.ImageView
import android.widget.VideoView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bumptech.glide.Glide
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.ui.R

/**
 * The post composer: a new post, an edit of one, or the answer the feed's comment button opens.
 *
 * <p>**What it replaces.** `activity_create_post.xml` is deleted: the toolbar is the shared chrome,
 * the three `TextInputLayout`s are three [OutlinedTextField]s with the same hints, the spinner is
 * [AccountPicker], the two attachment previews are the same `ImageView`/`VideoView` the XML held
 * (through [AndroidView], because this module has no Compose image loader), the reply preview is
 * [PreviewCard], and the three attach buttons and the publish button are the bottom row. The
 * Activity keeps every launcher, permission, validation and intent it had.
 *
 * <p>**The fields keep their own text.** The XML `EditText`s saved and restored their own state;
 * [rememberSaveable] is that, and the host only hands over the initial values and reads the three
 * strings back out of the publish event - which is what `publishPost` did when it read the views.
 *
 * <p>**What is hidden in which mode is the XML's own code**: replying hides the title, the link and
 * the three attach buttons and shows the original post's preview; editing hides the account picker;
 * everything else is always there.
 *
 * @param state the form's initial values and what the host decided is visible
 * @param events what a gesture means; the same six things the Activity's listeners did
 */
@Composable
fun CreatePostScreen(
    state: CreatePostState,
    events: CreatePostEvents,
    modifier: Modifier = Modifier,
) {
    var title by rememberSaveable { mutableStateOf(state.initialTitle) }
    var content by rememberSaveable { mutableStateOf(state.initialContent) }
    var link by rememberSaveable { mutableStateOf(state.initialLink) }
    Column(modifier = modifier.fillMaxSize().imePadding()) {
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
        ) {
            if (state.replyMode && (state.previewTitle != null || state.previewContent != null)) {
                PreviewCard(state.previewTitle, state.previewContent)
            }
            val attachmentUri = state.attachmentUri
            if (attachmentUri != null) {
                AttachmentPreview(attachmentUri, state.attachmentType)
            }
            if (state.accountVisible) {
                AccountPicker(state, events)
            }
            if (!state.replyMode) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    label = { Text(stringResource(R.string.post_title)) },
                    singleLine = true,
                )
            }
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                label = {
                    Text(
                        stringResource(
                            if (state.replyMode) R.string.comment else R.string.post_content,
                        ),
                    )
                },
                minLines = 5,
            )
            if (!state.replyMode) {
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    label = { Text(stringResource(R.string.post_link)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!state.replyMode) {
                IconButton(onClick = { events.onAttachFile() }) {
                    Icon(painterResource(R.drawable.ic_attach_file_24dp), contentDescription = null)
                }
                IconButton(onClick = { events.onAttachImage() }) {
                    Icon(painterResource(R.drawable.ic_camera_alt_24dp), contentDescription = null)
                }
                IconButton(onClick = { events.onAttachVideo() }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_videocam_24dp),
                        contentDescription = stringResource(R.string.attach_record_video),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { events.onPublish(title, content, link) },
                enabled = state.publishEnabled,
            ) {
                Text(stringResource(R.string.publish))
            }
        }
    }
}

/** Everything the composer draws, assembled by the host off its intent and its service. */
data class CreatePostState(
    /** Whether the composer answers a post: hides the title, the link and the attach buttons. */
    val replyMode: Boolean = false,
    /** Whether the account picker is drawn at all. */
    val accountVisible: Boolean = true,
    /** The connected accounts the post may be written from. */
    val accounts: List<Account> = emptyList(),
    /** The persisted position in [accounts]. */
    val selectedAccount: Int = 0,
    val initialTitle: String = "",
    val initialContent: String = "",
    val initialLink: String = "",
    /** The post being answered, when [replyMode]. */
    val previewTitle: String? = null,
    val previewContent: String? = null,
    /** The attachment already chosen or handed in by the edit intent. */
    val attachmentUri: Uri? = null,
    val attachmentType: String? = null,
    /** Whether publish may be pressed; `false` while a publish is in flight. */
    val publishEnabled: Boolean = true,
)

/**
 * What the composer's gestures mean. The host is the only implementor and every method there is one
 * of the Activity's own listeners.
 */
interface CreatePostEvents {

    /** One of the connected accounts was chosen; the position is persisted. */
    fun onAccountSelected(position: Int)

    /** Attach any file. */
    fun onAttachFile()

    /** Take a photo. */
    fun onAttachImage()

    /** Record a video. */
    fun onAttachVideo()

    /** Publish, with the three field values as they stand. */
    fun onPublish(title: String, content: String, linkUrl: String)
}

/** The reply's original post: the XML `post_preview` card. */
@Composable
private fun PreviewCard(title: String?, content: String?) {
    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
            if (content != null) {
                Text(
                    text = content,
                    modifier = Modifier.padding(top = 4.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The attachment the form carries: the same 150 dp `ImageView` (Glide-loaded) or `VideoView`
 * (looping, self-starting) the XML held.
 */
@Composable
private fun AttachmentPreview(uri: Uri, type: String?) {
    val video = type != null && type.startsWith("video/")
    Box(
        modifier = Modifier.fillMaxWidth().height(150.dp),
        contentAlignment = Alignment.Center,
    ) {
        key(uri, video) {
            if (video) {
                AndroidView(
                    factory = { context ->
                        VideoView(context).apply {
                            setVideoURI(uri)
                            setOnPreparedListener { mp ->
                                mp.isLooping = true
                                mp.start()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                AndroidView(
                    factory = { context ->
                        ImageView(context).apply {
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            Glide.with(this).load(uri).into(this)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** The account spinner: the same list of bare JIDs, the same persisted selection. */
@Composable
private fun AccountPicker(state: CreatePostState, events: CreatePostEvents) {
    var open by remember { mutableStateOf(false) }
    val selected = state.accounts.getOrNull(state.selectedAccount)
    Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected?.getJid()?.asBareJid()?.toString().orEmpty())
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.accounts.forEachIndexed { index, account ->
                DropdownMenuItem(
                    text = { Text(account.getJid().asBareJid().toString()) },
                    onClick = {
                        open = false
                        events.onAccountSelected(index)
                    },
                )
            }
        }
    }
}
