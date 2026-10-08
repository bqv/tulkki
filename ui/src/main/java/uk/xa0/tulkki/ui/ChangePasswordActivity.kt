package uk.xa0.tulkki.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.google.android.material.R as MaterialR
import uk.xa0.tulkki.data.R as DataR
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import uk.xa0.tulkki.xmpp.services.XmppConnectionService

/**
 * The change-password screen: the account's current and new password, and the button that sets it on
 * the server.
 *
 * <p>**The layout is gone.** `activity_change_password.xml` held a toolbar, two `TextInputLayout`s
 * with a `TextInputEditText` each and a bottom bar with a cancel and a change button - nine ids, the
 * whole screen - and the file is deleted. The bar is the shared chrome
 * ([uk.xa0.tulkki.ui.chrome.TulkkiChrome]) now, the form is [ChangePasswordScreen], and
 * `setSupportActionBar`/`configureActionBar`/`Activities.setStatusAndNavigationBarColors` went with
 * the bar. The screen's title is the Activity's own manifest label
 * (`@string/change_password_on_server`), which is what the action bar drew before the `setTitle` the
 * XML never called.
 *
 * <p>**The state lives here, not in the views.** Every read the old listeners made through
 * `binding` is a read of [state] now, and every write is a `copy` of it, so the branches - the hidden
 * current field for a magic-create account or an unlocked session, the two error branches of the
 * button, the "Updating…" button and the failure that restores it - are the old branches with their
 * fields renamed. `onPasswordChangeSucceeded`/`Failed` still arrive on another thread, so they still
 * hop to the UI thread before touching the state.
 *
 * <p>Two pieces of the view behaviour are carried deliberately. The two fields' selection toolbar is
 * replaced with [NoSelectionToolbar], which is `DisabledActionModeCallback` in Compose; and the two
 * error branches still point the owner at the field that failed, through [ChangePasswordFocus],
 * which is the old `requestFocus()`.
 */
class ChangePasswordActivity : XmppActivity(),
    uk.xa0.tulkki.xmpp.services.OnAccountPasswordChanged {

    /** Everything the screen draws, held by the Activity and handed to [ChangePasswordScreen]. */
    private var state by mutableStateOf(ChangePasswordScreenState())

    private var mAccount: Account? = null
    private var didUnlock = false

    override fun onBackendConnected() {
        this.mAccount = extractAccount(intent)
        val account = this.mAccount
        val showCurrentPassword =
            !(account != null && (account.isOptionSet(Account.OPTION_MAGIC_CREATE) || didUnlock))
        state = state.copy(showCurrentPassword = showCurrentPassword)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The chrome draws its bar behind the system bars and hands the content the space they
        // leave, so the window must not inset itself for them first - on every API level, which is
        // what enableEdgeToEdge settles. See TulkkiChrome for the whole pattern.
        enableEdgeToEdge()

        setTulkkiContent(darkTheme = isDark()) {
            TulkkiChrome(
                title = stringResource(R.string.change_password_on_server),
                // The arrow `configureActionBar(supportActionBar)` switched on, which ran `finish()`.
                onUp = { finish() },
            ) {
                ChangePasswordScreen(
                    state = state,
                    onCurrentPasswordChange = { value ->
                        state = state.copy(currentPassword = value)
                    },
                    onNewPasswordChange = { value -> state = state.copy(newPassword = value) },
                    onCancel = { finish() },
                    onChangePassword = { changePassword() },
                )
            }
        }
    }

    public override fun onStart() {
        super.onStart()
        val activityIntent = intent
        this.didUnlock = activityIntent.getBooleanExtra("did_unlock", false)
        val password = activityIntent.getStringExtra("password")
        if (password != null) {
            state = state.copy(newPassword = password)
        }
    }

    private fun changePassword() {
        val account = mAccount
        if (account == null) {
            return
        }
        val current = state
        val currentPassword = current.currentPassword
        val newPassword = current.newPassword
        if (!account.isOptionSet(Account.OPTION_MAGIC_CREATE) && !didUnlock &&
            currentPassword != account.getPassword()
        ) {
            state =
                current.copy(
                    currentError = getString(DataR.string.account_status_unauthorized),
                    newError = null,
                    focus = ChangePasswordFocus.CurrentPassword,
                )
        } else if (newPassword.trim { it <= ' ' }.isEmpty()) {
            state =
                current.copy(
                    currentError = null,
                    newError = getString(R.string.password_should_not_be_empty),
                    focus = ChangePasswordFocus.NewPassword,
                )
        } else {
            state = current.copy(currentError = null, newError = null, updating = true, focus = null)
            xmppConnectionService.updateAccountPasswordOnServer(account, newPassword, this)
        }
    }

    override fun onPasswordChangeSucceeded() {
        runOnUiThread {
            Toast.makeText(this, R.string.password_changed, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onPasswordChangeFailed() {
        runOnUiThread {
            state =
                state.copy(
                    newError = getString(R.string.could_not_change_password),
                    updating = false,
                )
        }
    }

    public override fun refreshUiReal() {}
}

/**
 * Everything [ChangePasswordScreen] draws: what the owner has typed, which errors stand, whether the
 * current field is on screen at all, whether a change is in flight, and which field a failed attempt
 * pointed them at.
 *
 * <p>An empty password is the initial value, which is what an empty `EditText` was; `showCurrentPassword`
 * starts true exactly as the layout's `current_password_layout` started `VISIBLE`.
 */
data class ChangePasswordScreenState(
    val currentPassword: String = "",
    val newPassword: String = "",
    val showCurrentPassword: Boolean = true,
    val currentError: String? = null,
    val newError: String? = null,
    val updating: Boolean = false,
    val focus: ChangePasswordFocus? = null,
)

/** Which field the last failed attempt sent focus to, exactly as the old `requestFocus()` did. */
enum class ChangePasswordFocus {
    CurrentPassword,
    NewPassword,
}

/**
 * The change-password form: the two password fields in the scrollable body, the cancel and change
 * buttons in the bar under it.
 *
 * <p>Every size is the deleted layout's own: the outer margins are `activity_horizontal_margin` /
 * `activity_vertical_margin`, the form's padding is `card_padding_regular`, and the button bar's
 * 16 dp by 8 dp is the `paddingHorizontal`/`paddingVertical` the `button_bar` carried. The fields are
 * the Material 3 filled `TextField` (the `TextInputLayout` default), the trailing eye is the
 * `endIconMode="password_toggle"` and takes the same content description the platform's toggle
 * announced, and `supportingText` is where the two `setError` calls land. The cancel button is a
 * `TextButton` as its `Widget.Material3.Button.TextButton` style said.
 */
@Composable
fun ChangePasswordScreen(
    state: ChangePasswordScreenState,
    onCurrentPasswordChange: (String) -> Unit,
    onNewPasswordChange: (String) -> Unit,
    onCancel: () -> Unit,
    onChangePassword: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentFocus = remember { FocusRequester() }
    val newFocus = remember { FocusRequester() }
    LaunchedEffect(state.focus) {
        when (state.focus) {
            ChangePasswordFocus.CurrentPassword -> currentFocus.requestFocus()
            ChangePasswordFocus.NewPassword -> newFocus.requestFocus()
            null -> Unit
        }
    }
    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = dimensionResource(R.dimen.activity_horizontal_margin),
                        vertical = dimensionResource(R.dimen.activity_vertical_margin),
                    ),
        ) {
            Column(
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(dimensionResource(R.dimen.card_padding_regular)),
            ) {
                if (state.showCurrentPassword) {
                    PasswordField(
                        value = state.currentPassword,
                        onValueChange = onCurrentPasswordChange,
                        label = stringResource(R.string.current_password),
                        error = state.currentError,
                        modifier = Modifier.fillMaxWidth().focusRequester(currentFocus),
                    )
                }
                PasswordField(
                    value = state.newPassword,
                    onValueChange = onNewPasswordChange,
                    label = stringResource(R.string.new_password),
                    error = state.newError,
                    modifier = Modifier.fillMaxWidth().focusRequester(newFocus),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onCancel) { Text(stringResource(DataR.string.cancel)) }
            Button(onClick = onChangePassword, enabled = !state.updating) {
                Text(
                    stringResource(
                        if (state.updating) R.string.updating else R.string.change_password
                    )
                )
            }
        }
    }
}

/**
 * One password field: the `TextInputLayout` and its `TextInputEditText` as one Material 3
 * `TextField`, with the eye that reveals it.
 */
@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
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
    // `setCustomSelectionActionModeCallback(DisabledActionModeCallback())`, in Compose.
    CompositionLocalProvider(LocalTextToolbar provides NoSelectionToolbar) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            label = { Text(label) },
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
}

/**
 * `DisabledActionModeCallback`, in Compose: the platform's selection toolbar is replaced with one
 * that draws nothing, so a long press on either password field cannot open cut, copy or select-all.
 */
private object NoSelectionToolbar : TextToolbar {

    override val status: TextToolbarStatus
        get() = TextToolbarStatus.Hidden

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) = Unit

    override fun hide() = Unit
}
