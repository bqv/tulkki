package uk.xa0.tulkki.ui.dialogs

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.XmppActivity

/**
 * The CAPTCHA dialog, as the Compose dialog [showTulkkiDialog] hosts.
 *
 * <p>The layout it replaces, `captcha.xml`, was an `ImageView` and an `EditText` in a vertical
 * `LinearLayout` with the dialog's own 24 dp padding; the title, the OK and the Cancel were
 * `MaterialAlertDialogBuilder`'s. It is one [AlertDialog] whose title, text and buttons are the
 * builder's three calls, and whose text is [CaptchaDialogBody] - the image and the field the layout
 * held, in the same order, with the same `@string/captcha_hint` on the field and the same
 * `requestFocus()` on open.
 *
 * <p>**The call answers with the dismissal**, because the screen that shows this one - an account
 * creation that asks for a fresh CAPTCHA - closes a dialog that is still up before it shows the
 * next, and a Compose dialog cannot be closed without its host's handle.
 *
 * <p>This file is only half of the conversion: the layout itself is deleted when its host swaps its
 * `layoutInflater.inflate(R.layout.captcha, …)` for this call, and that host sits in another lane's
 * family.
 */
fun XmppActivity.showCaptchaDialog(
    captcha: Bitmap,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
): () -> Unit =
    showTulkkiDialog { dismiss ->
        CaptchaDialogBody(
            captcha = captcha.asImageBitmap(),
            onDismiss = {
                dismiss()
                onCancel()
            },
            onConfirm = { input ->
                dismiss()
                onConfirm(input)
            },
        )
    }

/**
 * The CAPTCHA dialog's face: the code's image and the field to type it into.
 *
 * @param captcha the image the server drew. Null is the cells' case, which have no server and pass
 *     nothing; the running dialog always has one.
 * @param onDismiss the dialog was dismissed - the outside tap or the back gesture, which the old
 *     `setOnCancelListener` handled - and the Cancel button, which is the same branch.
 * @param onConfirm the OK button, with what the owner typed.
 */
@Composable
fun CaptchaDialogBody(
    captcha: ImageBitmap?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    // `input.requestFocus()` when the dialog was shown.
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.captcha_required)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (captcha != null) {
                    Image(bitmap = captcha, contentDescription = null)
                }
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(R.string.captcha_hint)) },
                    singleLine = true,
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(top = 8.dp)
                            .focusRequester(focus),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(input) }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(uk.xa0.tulkki.data.R.string.cancel))
            }
        },
    )
}
