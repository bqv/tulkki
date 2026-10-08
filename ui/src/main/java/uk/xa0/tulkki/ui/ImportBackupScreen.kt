package uk.xa0.tulkki.ui

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.DialogProperties
import com.google.android.material.R as MaterialR
import uk.xa0.tulkki.data.R as DataR
import uk.xa0.tulkki.ui.theme.TulkkiSpacing
import uk.xa0.tulkki.ui.utils.UIHelper

/**
 * One row of the backup list, already resolved by the host.
 *
 * <p>The deleted `item_account.xml` was another family's layout, so this screen draws its own row
 * rather than editing that file: [jid] and [status] are exactly what `BackupFileAdapter` bound
 * (`account_jid`, and `account_status` as `"app · date"`), and [key] is the file's URI as a string,
 * which is the list's identity for `LazyColumn` and how the host finds the file again when the row
 * is tapped.
 */
data class ImportBackupRow(val key: String, val jid: String, val status: String)

/**
 * Everything [ImportBackupScreen] draws.
 *
 * @param loading the work manager has an import running: the spinner shows and the list is gone,
 *     which was `binding.inProgress` visible and `binding.coordinator` gone.
 * @param rows the backup files found on the device.
 * @param message a transient message for the snackbar, or `null`. It carries a [ImportBackupMessage.token]
 *     so the same words twice still count as two messages.
 */
data class ImportBackupState(
    val loading: Boolean = false,
    val rows: List<ImportBackupRow> = emptyList(),
    val message: ImportBackupMessage? = null,
)

/** One transient message, with the token that tells two identical ones apart. */
data class ImportBackupMessage(val token: Int, val text: String)

/**
 * The password dialog's face while it is open: whose backup it is asking about, and the validation
 * error the host put under the field. The password itself is the dialog's own `remember`, because it
 * is typed and read on the same visit.
 */
data class ImportBackupPasswordState(val jid: String, val error: String? = null)

/**
 * The restore-backup screen: the backup files this device holds, or the spinner while an import runs.
 *
 * <p>**The layout is gone.** `activity_import_backup.xml` held a toolbar, a `ProgressBar` and a
 * `RecyclerView`, and the file is deleted. The bar is the shared chrome
 * ([uk.xa0.tulkki.ui.chrome.TulkkiChrome]) now, the spinner is the `ProgressBar`'s branch and the
 * list is a `LazyColumn`; `setSupportActionBar`, `setTitle`, `Activities.setStatusAndNavigationBarColors`
 * and `configureActionBar` went with the bar. The screen names no string of its own - the host
 * resolves every one - except the password dialog's, which is part of this family.
 *
 * <p>**The row is this screen's own drawing.** The old list reused `item_account.xml`, the accounts
 * family's row, through `BackupFileAdapter` - both deleted once this screen stopped inflating them:
 * [rowOf] in the Activity is the adapter's binding and [BackupFileRow] is the same avatar, address
 * and `app · date` line. Two views the adapter never touched are deliberately not reproduced - the
 * row's drag handle and its shield icon, which the accounts screen puts behaviour on and this one
 * never did.
 *
 * <p>**The transient messages are the old `Snackbar`s.** Every `Snackbar.make(binding.coordinator, …)`
 * is one [ImportBackupState.message] drawn by [SnackbarHost] at the foot of the content, where the
 * `coordinator` anchored it. A message is shown once and reported back through [onMessageShown], so a
 * recomposition does not repeat it.
 *
 * @param avatar the row's avatar, or `null` while the host has not resolved it; the row then draws
 *     the plate `BackupFileAdapter` coloured with `UIHelper.getColorForName` in the meantime.
 * @param passwordDialog the password dialog to draw over this screen, or `null`.
 * @param onRestore the password dialog's positive button: the typed password and the switch.
 * @param onCancelPassword the password dialog's negative button.
 */
@Composable
fun ImportBackupScreen(
    state: ImportBackupState,
    onOpen: (ImportBackupRow) -> Unit,
    onMessageShown: (ImportBackupMessage) -> Unit,
    passwordDialog: ImportBackupPasswordState? = null,
    onRestore: (password: String, includeKeys: Boolean) -> Unit = { _, _ -> },
    onCancelPassword: () -> Unit = {},
    avatar: (String) -> Drawable? = { null },
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val message = state.message
    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message.text, duration = SnackbarDuration.Long)
            onMessageShown(message)
        }
    }
    Box(modifier = modifier.fillMaxSize()) {
        if (state.loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.rows, key = { it.key }) { row ->
                    BackupFileRow(row = row, avatar = avatar(row.jid)) { onOpen(row) }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
    if (passwordDialog != null) {
        ImportBackupPasswordDialog(
            state = passwordDialog,
            onRestore = onRestore,
            onCancel = onCancelPassword,
        )
    }
}

/**
 * One backup file: the avatar, the account's address and the `app · date` line under it.
 *
 * <p>`item_account.xml`'s shape as `BackupFileAdapter` bound it - the 48 dp avatar at the start, the
 * `paddingStart`/`paddingTop`/`paddingBottom` of 8 dp, the `avatar_item_distance` between the avatar
 * and the text column, `account_jid` in `bodyLarge` (single line) and `account_status` in
 * `bodyMedium`, with `?selectableItemBackground` as the row's [clickable].
 */
@Composable
private fun BackupFileRow(row: ImportBackupRow, avatar: Drawable?, onClick: () -> Unit) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(
                    start = TulkkiSpacing.sm,
                    top = TulkkiSpacing.sm,
                    bottom = TulkkiSpacing.sm,
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackupAvatar(jid = row.jid, avatar = avatar)
        Column(
            modifier = Modifier.padding(start = dimensionResource(R.dimen.avatar_item_distance)),
        ) {
            Text(
                text = row.jid,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = row.status, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * The row's 48 dp avatar: the text avatar once the host has it, over the colour
 * `UIHelper.getColorForName` gave the address - which is the background `BackupFileAdapter` set
 * before its worker landed, so the plate and the image are the same two layers in the same order.
 */
@Composable
private fun BackupAvatar(jid: String, avatar: Drawable?) {
    Box(
        modifier =
            Modifier.size(dimensionResource(R.dimen.avatar))
                .background(Color(UIHelper.getColorForName(jid))),
    ) {
        if (avatar != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawIntoCanvas { canvas ->
                    avatar.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                    avatar.draw(canvas.nativeCanvas)
                }
            }
        }
    }
}

/**
 * The password dialog: the account's address, the OMEMO switch and its warnings, the password field
 * and the two buttons.
 *
 * <p>`dialog_enter_password.xml` is deleted with it. The three pieces the file drew are here: the
 * `explain` `TextView` is `enter_password_to_restore` with the JID, the filled
 * `materialCardViewFilledStyle` card is [Card] (Material 3's filled card is the same
 * `surfaceContainerHighest`) holding the `MaterialSwitch` and `restore_warning`, and the
 * `TextInputLayout` with `endIconMode="password_toggle"` is [TextField] with the eye and
 * `supportingText` for the `setError` of the old `account_password_layout`.
 *
 * <p>**The dialog does not close on a failed validation.** The host's [onRestore] answers by setting
 * [ImportBackupPasswordState.error] and leaves the dialog where it is, exactly as the old positive
 * button's own listener returned without dismissing; the successful click is what closes it, as
 * `d.dismiss()` did.
 *
 * <p>The dialog is not cancelable: `setCancelable(false)` is
 * [DialogProperties.dismissOnBackPress] and [DialogProperties.dismissOnClickOutside] both off.
 */
@Composable
fun ImportBackupPasswordDialog(
    state: ImportBackupPasswordState,
    onRestore: (password: String, includeKeys: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var includeKeys by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = {},
        properties =
            DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.enter_password)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.enter_password_to_restore, state.jid))
                Card(modifier = Modifier.fillMaxWidth().padding(top = TulkkiSpacing.lg)) {
                    Column(modifier = Modifier.padding(TulkkiSpacing.lg)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { includeKeys = !includeKeys },
                        ) {
                            Switch(checked = includeKeys, onCheckedChange = { includeKeys = it })
                            Text(stringResource(R.string.restore_omemo_key))
                        }
                        Text(
                            text = stringResource(R.string.restore_warning),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                BackupPasswordField(
                    value = password,
                    onValueChange = { password = it },
                    error = state.error,
                    modifier = Modifier.fillMaxWidth().padding(top = TulkkiSpacing.lg),
                )
                Text(
                    text = stringResource(R.string.restore_warning_continued),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = TulkkiSpacing.lg),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onRestore(password, includeKeys) }) {
                Text(stringResource(R.string.restore))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(DataR.string.cancel)) }
        },
    )
}

/**
 * The dialog's password field: the `TextInputLayout` and its `TextInputEditText` as one Material 3
 * filled [TextField], with the same trailing eye `endIconMode="password_toggle"` drew and the same
 * hint `@string/password`.
 *
 * <p>It is [ChangePasswordActivity]'s private field, which is the shape this tree gives that pair;
 * the error is where `account_password_layout.setError(...)` landed.
 */
@Composable
private fun BackupPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    error: String?,
    modifier: Modifier = Modifier,
) {
    var revealed by remember { mutableStateOf(false) }
    val supporting: (@Composable () -> Unit)? =
        if (error != null) {
            { Text(error) }
        } else {
            null
        }
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(stringResource(R.string.password)) },
        trailingIcon = {
            IconButton(onClick = { revealed = !revealed }) {
                Icon(
                    painter =
                        painterResource(
                            if (revealed) R.drawable.ic_visibility_off
                            else R.drawable.ic_visibility_24dp
                        ),
                    contentDescription =
                        stringResource(MaterialR.string.password_toggle_content_description),
                )
            }
        },
        supportingText = supporting,
        isError = error != null,
        visualTransformation =
            if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true,
    )
}
