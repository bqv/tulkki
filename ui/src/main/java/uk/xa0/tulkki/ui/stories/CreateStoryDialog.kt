package uk.xa0.tulkki.ui.stories

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import uk.xa0.tulkki.ui.R

/**
 * What the add-story dialog needs to open: the picked media, its type, and the number of contacts
 * the deletion's `publish_info_text` counted.
 */
data class CreateStoryRequest(
    val uri: Uri,
    val mimeType: String,
    val subscriberCount: Int,
)

/**
 * The add-story dialog, where `dialog_create_story.xml` + `MaterialAlertDialogBuilder` were.
 *
 * <p>**The layout is gone.** `dialog_create_story.xml` held the 250 dp preview pair
 * (`story_preview_image`/`story_preview_video`), the `publish_info_text` line and the
 * `TextInputLayout`/`TextInputEditText` for the title; the file is deleted. The preview is a slot,
 * because the two views are the platform's and the image library's - the same split
 * `AddReactionScreen` makes about the emoji picker - and the dialog owns the 250 dp the deleted
 * layout gave them. The `app:counterEnabled`/`counterMaxLength="500"` counter is the one attribute
 * not carried over: the field still caps at 500 characters, and the deleted `TextWatcher`'s 20-line
 * ceiling is the second cap.
 *
 * <p>Every string is the deleted layout's own: `add_story_title`, `title_optional`, `publish`, the
 * `publishing_to_x_contacts` plural and `:data`'s `cancel`.
 */
@Composable
fun CreateStoryDialog(
    request: CreateStoryRequest,
    image: @Composable (Uri) -> Unit,
    video: @Composable (Uri) -> Unit,
    onPublish: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_story_title)) },
        text = {
            Column {
                Box(modifier = Modifier.fillMaxWidth().height(PREVIEW_HEIGHT)) {
                    if (request.mimeType.startsWith("video/")) {
                        video(request.uri)
                    } else {
                        image(request.uri)
                    }
                }
                Text(
                    text =
                        pluralStringResource(
                            R.plurals.publishing_to_x_contacts,
                            request.subscriberCount,
                            request.subscriberCount,
                        ),
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { text ->
                        // `android:maxLength="500"`, and the deleted TextWatcher's ceiling: a change
                        // that would make the field longer than 20 lines is not taken at all.
                        if (text.length <= TITLE_MAX_CHARS &&
                            text.count { it == '\n' } < TITLE_MAX_LINES
                        ) {
                            title = text
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    placeholder = { Text(stringResource(R.string.title_optional)) },
                    minLines = 3,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPublish(title) }) { Text(stringResource(R.string.publish)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(uk.xa0.tulkki.data.R.string.cancel))
            }
        },
    )
}

/** The deleted layout's `android:layout_height="250dp"` on both previews. */
private val PREVIEW_HEIGHT = 250.dp

/** The deleted `android:maxLength` and `app:counterMaxLength`. */
private const val TITLE_MAX_CHARS = 500

/** The deleted `TextWatcher`'s `lineCount > 20` refusal. */
private const val TITLE_MAX_LINES = 20
