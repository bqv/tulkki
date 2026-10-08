package uk.xa0.tulkki.ui.welcome

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import uk.xa0.tulkki.ui.R

/**
 * The magic-create form, in Compose: pick a username, pick a provider or type your own, read the
 * full address you are about to get, and create the account. It is the screen
 * [uk.xa0.tulkki.ui.MagicCreateActivity] composes into
 * [uk.xa0.tulkki.ui.chrome.TulkkiChrome], and `activity_magic_create.xml` is deleted with the swap.
 *
 * <p>Every visible fact is a field of [MagicCreateState] and every interaction a callback, so the
 * screen reads no preference and decides no validation: the domain preview, the error each field
 * carries and the branch that hides the provider list are all the activity's, exactly as the
 * data-binding layout's fields were.
 *
 * <p>**Two shapes are the layout's and one is not.** The form sits at the bottom with the mark
 * centred above it, the fields are in the layout's order, and the create button is the tonal
 * `@string/next` at the end. The provider `Spinner` (`spinnerMode="dialog"`) is a Compose dropdown
 * menu, which is a popup rather than a dialog - the one deliberate difference in how a choice is
 * made.
 *
 * @param state everything the form shows.
 * @param onUsernameChange the username field's text changed.
 * @param onOwnServerChange the "use my own provider" server field changed.
 * @param onProviderSelected a provider was picked from the list.
 * @param onUseOwnChange the "use my own provider" switch moved.
 * @param onCreate the `@string/next` button, which is the activity's own validation and signup.
 */
@Composable
fun MagicCreateScreen(
    state: MagicCreateState,
    onUsernameChange: (String) -> Unit,
    onOwnServerChange: (String) -> Unit,
    onProviderSelected: (String) -> Unit,
    onUseOwnChange: (Boolean) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val usernameFocus = remember { FocusRequester() }
    val ownServerFocus = remember { FocusRequester() }

    // The layout's `setError` also asked the field for focus, so the keyboard followed the report.
    // A field that is not on screen has no `FocusRequester` node to ask - the own-server error can
    // be reported while the switch is off - so asking an absent one is skipped rather than thrown.
    LaunchedEffect(state.focus) {
        when (state.focus) {
            MagicCreateField.USERNAME -> {
                usernameFocus.requestFocus()
            }
            MagicCreateField.OWN_SERVER -> {
                if (state.ownServerVisible) {
                    ownServerFocus.requestFocus()
                }
            }
            null -> Unit
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.fillMaxSize(),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(R.drawable.tulkki_logo),
                contentDescription = null,
                modifier = Modifier.align(Alignment.Center).padding(8.dp).size(128.dp),
            )
            Column(
                modifier =
                    Modifier.align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            ) {
                Text(
                    text = state.instructions,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = stringResource(state.title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = state.username,
                    onValueChange = onUsernameChange,
                    label = { Text(stringResource(R.string.username_hint)) },
                    enabled = state.usernameEnabled,
                    isError = state.usernameError != null,
                    supportingText = state.usernameError?.let { { Text(it) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(usernameFocus),
                )
                if (state.serverTitleVisible) {
                    Text(
                        text = stringResource(state.serverTitle),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (state.serverVisible) {
                    ProviderField(
                        providers = state.providers,
                        selected = state.selectedProvider,
                        enabled = state.serverEnabled,
                        onSelect = onProviderSelected,
                    )
                }
                if (state.providersLabelVisible) {
                    Text(
                        text = stringResource(state.providersLabel),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (state.fixedServerVisible) {
                    OutlinedTextField(
                        value = state.fixedServer.orEmpty(),
                        onValueChange = {},
                        label = { Text(stringResource(R.string.your_server)) },
                        enabled = false,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (state.useOwnVisible) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.use_own_provider),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = state.useOwn, onCheckedChange = onUseOwnChange)
                    }
                }
                if (state.ownServerVisible) {
                    OutlinedTextField(
                        value = state.ownServer,
                        onValueChange = onOwnServerChange,
                        label = { Text(stringResource(R.string.enter_domain)) },
                        isError = state.ownServerError != null,
                        supportingText = state.ownServerError?.let { { Text(it) } },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(ownServerFocus),
                    )
                }
                if (state.fullJidVisible) {
                    Text(
                        text = state.fullJid.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Button(
                    onClick = onCreate,
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    modifier = Modifier.align(Alignment.End).padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.next))
                }
            }
        }
    }
}

/** The provider list, which the layout drew as a `Spinner` in dialog mode. */
@Composable
private fun ProviderField(
    providers: List<String>,
    selected: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val hint = stringResource(R.string.server_hint)
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = selected ?: hint, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (provider in providers) {
                DropdownMenuItem(
                    text = { Text(provider) },
                    onClick = {
                        expanded = false
                        onSelect(provider)
                    },
                )
            }
        }
    }
}

/** Which field the activity's validation put the focus on. */
enum class MagicCreateField {
    USERNAME,
    OWN_SERVER,
}

/**
 * Everything `activity_magic_create.xml` bound, as plain values.
 *
 * @param instructions `magic_create_text`, or the invitation's own wording once a domain is known.
 * @param title `pick_your_username`, or `your_server_invitation` for a fixed invitation.
 * @param providers the provider catalogue, sorted by the activity.
 * @param providersLabel which of the layout's provider-list notices is showing.
 * @param serverTitle `choose_your_server`, or `your_server` for a fixed invitation.
 * @param fixedServer the disabled field the layout called `yourserver`, shown for an invitation.
 * @param fullJid the `your_full_jid_will_be` preview, when there is one.
 * @param focus the field validation asked for.
 */
data class MagicCreateState(
    val instructions: String = "",
    @StringRes val title: Int = R.string.pick_your_username,
    val username: String = "",
    val usernameEnabled: Boolean = true,
    val usernameError: String? = null,
    val providers: List<String> = emptyList(),
    val selectedProvider: String? = null,
    val serverEnabled: Boolean = true,
    val serverVisible: Boolean = true,
    @StringRes val providersLabel: Int = R.string.error_loading_chat_providers,
    val providersLabelVisible: Boolean = true,
    @StringRes val serverTitle: Int = R.string.choose_your_server,
    val serverTitleVisible: Boolean = true,
    val fixedServer: String? = null,
    val fixedServerVisible: Boolean = false,
    val useOwnVisible: Boolean = true,
    val useOwn: Boolean = false,
    val ownServerVisible: Boolean = false,
    val ownServer: String = "",
    val ownServerError: String? = null,
    val fullJid: String? = null,
    val fullJidVisible: Boolean = false,
    val focus: MagicCreateField? = null,
)
