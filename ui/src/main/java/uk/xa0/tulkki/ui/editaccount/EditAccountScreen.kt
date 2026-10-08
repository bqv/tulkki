package uk.xa0.tulkki.ui.editaccount

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import uk.xa0.tulkki.data.R as DataR
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.omemo.ContactKeyRow
import uk.xa0.tulkki.ui.omemo.ContactKeyRowState
import uk.xa0.tulkki.ui.theme.TulkkiSpacing
import uk.xa0.tulkki.ui.widget.AvatarView
import java.util.Locale

/**
 * The account editor, Compose. The screen [uk.xa0.tulkki.ui.EditAccountActivity] draws.
 *
 * <p>**The layout is gone.** `activity_edit_account.xml` was 1 030 lines and 76 ids - the largest
 * single layout in the tree - and it is deleted. Everything it drew is here: the account card with
 * the avatar and the four connection fields, the vCard card with its dynamic rows and their drag
 * reordering, the battery/data-saver card, the server-info card with its two tables and its
 * verification strip, the other-devices card, and the cancel/save bar at the bottom. `R.menu.editaccount`
 * is gone with it: the still-live items moved into the shared chrome
 * ([uk.xa0.tulkki.ui.chrome.TulkkiChrome]) and the three submenu entries of the old `action_share`
 * flattened into its overflow.
 *
 * <p>**The behaviour is the Activity's, not this file's.** This file draws [EditAccountScreenState]
 * and reports every edit back; the Activity holds the mode (`mInitMode`, `mUsernameMode`,
 * `mForceRegister`), the account, and every branch that decided what the old `binding.*` writes
 * meant. Nothing here reads a singleton, a preference or the account.
 *
 * <p>**Where the old screen's `View`s could not be re-expressed and are bridged instead**, each with
 * its reason:
 *
 *  * the avatar, because `AvatarView` is this tree's own `AppCompatImageView` and the loader
 *    (`AvatarWorkerTask.loadAvatar`) takes the `ImageView` itself, keyed on it for its worker.
 *
 * The other-devices key rows are [ContactKeyRow]s now, the shape [OmemoActivity.contactKeyRow]
 * resolves for the shared `contact_key.xml`, which is deleted with its menu.
 *
 * <p>**Two behaviours the deleted layout's `View`s carried are deliberately not carried**, and both
 * are reported rather than hidden. The JID field's hint appeared 200 ms after focus because the old
 * listener posted a delayed `setHint`; the placeholder here is the same text shown immediately. And
 * the drag handle started a drag on `ACTION_DOWN` where `detectDragGestures` needs the ordinary
 * touch slop first, so a drag begins a few pixels later than it did.
 *
 * <p>Sizes are the design system's ([TulkkiSpacing], and the deleted layout's own `dimens.xml`
 * resources); the handful the deleted XML spelled and `theme/Dimens.kt` - frozen for this lane -
 * does not carry live in [EditAccountSize].
 */
@Composable
fun EditAccountScreen(
    state: EditAccountScreenState,
    modifier: Modifier = Modifier,
    onJidChange: (String) -> Unit = {},
    onPasswordChange: (String) -> Unit = {},
    onHostnameChange: (String) -> Unit = {},
    onPortChange: (String) -> Unit = {},
    onRegisterNewChange: (Boolean) -> Unit = {},
    onAvatarClick: () -> Unit = {},
    onBindAvatar: (AvatarView) -> Unit = {},
    onEditDisplayName: () -> Unit = {},
    onAccountColorClick: () -> Unit = {},
    onQuietHoursEnabledChange: (Boolean) -> Unit = {},
    onQuietHoursStartClick: () -> Unit = {},
    onQuietHoursEndClick: () -> Unit = {},
    onAddVCardRow: () -> Unit = {},
    onVCardTypeChange: (Long, String) -> Unit = { _, _ -> },
    onVCardValueChange: (Long, String) -> Unit = { _, _ -> },
    onVCardRemove: (Long) -> Unit = {},
    onVCardMove: (Int, Int) -> Unit = { _, _ -> },
    onShowVCardQr: () -> Unit = {},
    onOsOptimizationAction: () -> Unit = {},
    onPgpClick: () -> Unit = {},
    onDeletePgp: () -> Unit = {},
    onCopyOmemoFingerprint: () -> Unit = {},
    onOmemoQr: () -> Unit = {},
    onClearDevices: () -> Unit = {},
    onScan: () -> Unit = {},
    onCancel: () -> Unit = {},
    onSave: () -> Unit = {},
) {
    val jidFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val hostnameFocus = remember { FocusRequester() }
    val portFocus = remember { FocusRequester() }
    // Every `requestFocus()` the old error branches made, on one key. The nonce is what makes the
    // same field in error twice in a row fire twice: `LaunchedEffect` alone would not.
    LaunchedEffect(state.focusNonce) {
        when (state.focusField) {
            EditAccountField.Jid -> if (state.jidEnabled) jidFocus.requestFocus()
            EditAccountField.Password -> if (state.passwordEditable) passwordFocus.requestFocus()
            EditAccountField.Hostname -> if (state.showNamePort) hostnameFocus.requestFocus()
            EditAccountField.Port -> if (state.showNamePort) portFocus.requestFocus()
            null -> Unit
        }
    }
    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = TulkkiSpacing.sm),
        ) {
            AccountCard(
                state = state,
                jidFocus = jidFocus,
                passwordFocus = passwordFocus,
                hostnameFocus = hostnameFocus,
                portFocus = portFocus,
                onJidChange = onJidChange,
                onPasswordChange = onPasswordChange,
                onHostnameChange = onHostnameChange,
                onPortChange = onPortChange,
                onRegisterNewChange = onRegisterNewChange,
                onAvatarClick = onAvatarClick,
                onBindAvatar = onBindAvatar,
            )
            if (state.showVCard) {
                VCardCard(
                    state = state,
                    onAddVCardRow = onAddVCardRow,
                    onVCardTypeChange = onVCardTypeChange,
                    onVCardValueChange = onVCardValueChange,
                    onVCardRemove = onVCardRemove,
                    onVCardMove = onVCardMove,
                    onShowVCardQr = onShowVCardQr,
                )
            }
            if (state.osOptimizationVisible) {
                OsOptimizationCard(
                    headline = state.osOptimizationHeadline,
                    body = state.osOptimizationBody,
                    action = state.osOptimizationAction,
                    onAction = onOsOptimizationAction,
                )
            }
            if (state.showStats) {
                StatsCard(
                    state = state,
                    onEditDisplayName = onEditDisplayName,
                    onAccountColorClick = onAccountColorClick,
                    onQuietHoursEnabledChange = onQuietHoursEnabledChange,
                    onQuietHoursStartClick = onQuietHoursStartClick,
                    onQuietHoursEndClick = onQuietHoursEndClick,
                    onPgpClick = onPgpClick,
                    onDeletePgp = onDeletePgp,
                    onCopyOmemoFingerprint = onCopyOmemoFingerprint,
                    onOmemoQr = onOmemoQr,
                    onClearDevices = onClearDevices,
                    onScan = onScan,
                )
            }
        }
        ButtonBar(
            saveLabel = state.saveLabel,
            saveEnabled = state.saveEnabled,
            onCancel = onCancel,
            onSave = onSave,
        )
    }
}

/**
 * Everything [EditAccountScreen] draws, in the shape the old `ActivityEditAccountBinding` had: one
 * field per id the layout carried that the host wrote to.
 *
 * <p>`@StringRes` for the fixed copy (so a locale change still re-resolves it) and `String` only
 * where the host has already built the text - an error from `getString`, a count, a formatted
 * sentence with the app name in it.
 */
data class EditAccountScreenState(
    // --- the account card ---
    val jid: String = "",
    @StringRes val jidHint: Int = R.string.account_settings_jabber_id,
    /**
     * The example the old `mEditTextFocusListener` swapped in on focus (the layout's own hint came
     * back only for the username mode, which set one in `onBackendConnected`). A Compose `TextField`
     * shows its placeholder exactly while the label floats, which is while it is focused or filled,
     * so this is the same moment without the old 200 ms post.
     */
    @StringRes val jidPlaceholder: Int = R.string.account_settings_example_jabber_id,
    val jidSuggestions: List<String> = emptyList(),
    val jidEnabled: Boolean = true,
    val jidError: String? = null,
    val password: String = "",
    val passwordEditable: Boolean = true,
    val passwordToggleEnabled: Boolean = true,
    val passwordError: String? = null,
    val showNamePort: Boolean = false,
    val hostname: String = "",
    @StringRes val hostnamePlaceholder: Int = R.string.hostname_example,
    val hostnameError: String? = null,
    val port: String = "5222",
    val portEnabled: Boolean = false,
    val portError: String? = null,
    val registerNew: Boolean = false,
    val registerNewVisible: Boolean = true,
    val showAvatar: Boolean = false,
    /**
     * What the avatar bridge is keyed on: the account, so the loader runs once per account rather
     * than once per recomposition. The screen never reads it.
     */
    val avatarKey: Any? = null,
    // --- the vCard card ---
    val showVCard: Boolean = false,
    val displayName: String? = null,
    val vcardRows: List<VCardRowState> = emptyList(),
    val accountColorVisible: Boolean = false,
    val accountColor: Int = 0,
    val quietHoursVisible: Boolean = false,
    val quietHoursEnabled: Boolean = false,
    val quietHoursStart: String = "",
    val quietHoursEnd: String = "",
    val pgpVisible: Boolean = false,
    val pgpFingerprint: String = "",
    val pgpHighlighted: Boolean = false,
    val omemoVisible: Boolean = false,
    val omemoFingerprint: String = "",
    @StringRes val omemoDesc: Int = R.string.omemo_fingerprint,
    val omemoHighlighted: Boolean = false,
    val otherDevicesVisible: Boolean = false,
    val otherDeviceKeys: List<ContactKeyRowState> = emptyList(),
    val clearDevicesVisible: Boolean = false,
    val unverifiedWarningVisible: Boolean = false,
    val scanVisible: Boolean = false,
    // --- the server-info card ---
    //
    // Every one of these was a `setText(int)` in the old host, so it is resolved by the host and
    // arrives as text: `httpUpload` is the one field whose value could be a file size rather than a
    // resource, and the two are the same `CharSequence` by the time a `TextView` had it.
    val showStats: Boolean = false,
    val sessionEstablished: String = "",
    val loginMechanism: String = "",
    val showMore: Boolean = false,
    val rosterVersion: String = "",
    val carbons: String = "",
    val mam: String = "",
    val csi: String = "",
    val blocking: String = "",
    val sm: String = "",
    val externalService: String = "",
    val bind2: String = "",
    val sasl2: String = "",
    val pep: String = "",
    val httpUpload: String = "",
    val pushVisible: Boolean = false,
    val push: String = "",
    val verificationVisible: Boolean = false,
    val verificationMessage: String = "",
    @DrawableRes val verificationIndicator: Int = R.drawable.shield_question,
    // --- the battery / data-saver card ---
    val osOptimizationVisible: Boolean = false,
    @StringRes val osOptimizationHeadline: Int = R.string.battery_optimizations_enabled,
    val osOptimizationBody: String = "",
    @StringRes val osOptimizationAction: Int = R.string.disable,
    // --- the button bar ---
    @StringRes val saveLabel: Int = R.string.save,
    val saveEnabled: Boolean = false,
    // --- focus ---
    val focusField: EditAccountField? = null,
    val focusNonce: Int = 0,
)

/** Which field the old `requestFocus()` answered for. */
enum class EditAccountField {
    Jid,
    Password,
    Hostname,
    Port,
}

/**
 * One dynamic vCard row: the type the spinner held, the value the field held, and an id so the
 * reorder and the removal can name it without a `View` to point at.
 */
data class VCardRowState(val id: Long, val type: String, val value: String)

/**
 * The sizes the deleted `activity_edit_account.xml` and `item_edit_vcard_entry.xml` spelled, which
 * `theme/Dimens.kt` - frozen for this lane - does not carry. Each is the XML's own number, so the
 * screen keeps the old geometry; they belong in `theme/Dimens.kt` in the wave that unfreezes it.
 */
private object EditAccountSize {
    /** `account_color_thumbnail`'s 48 dp box. */
    val colorThumbnail = 48.dp

    /** The colour swatch's own 1 dp inset inside `thumbnail_border`. */
    val colorInset = 1.dp

    /** `vcard_type_spinner`'s 109 dp column. */
    val vcardType = 109.dp
}

/** The account card: the avatar, the JID, the password, the optional host/port pair and register. */
@Composable
private fun AccountCard(
    state: EditAccountScreenState,
    jidFocus: FocusRequester,
    passwordFocus: FocusRequester,
    hostnameFocus: FocusRequester,
    portFocus: FocusRequester,
    onJidChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onHostnameChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onRegisterNewChange: (Boolean) -> Unit,
    onAvatarClick: () -> Unit,
    onBindAvatar: (AvatarView) -> Unit,
) {
    Card(
        modifier =
            Modifier.fillMaxWidth()
                .padding(
                    horizontal = dimensionResource(R.dimen.activity_horizontal_margin),
                    vertical = dimensionResource(R.dimen.activity_vertical_margin),
                )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(dimensionResource(R.dimen.card_padding_regular)),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(TulkkiSpacing.lg),
        ) {
            if (state.showAvatar) {
                AvatarBridge(
                    avatarKey = state.avatarKey,
                    onBindAvatar = onBindAvatar,
                    onClick = onAvatarClick,
                    modifier = Modifier.size(dimensionResource(R.dimen.avatar_on_details_screen_size)),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                JidField(
                    state = state,
                    focus = jidFocus,
                    onJidChange = onJidChange,
                )
                PasswordField(
                    state = state,
                    focus = passwordFocus,
                    onPasswordChange = onPasswordChange,
                )
                if (state.showNamePort) {
                    Row(modifier = Modifier.fillMaxWidth().padding(top = TulkkiSpacing.sm)) {
                        OutlinedTextField(
                            value = state.hostname,
                            onValueChange = onHostnameChange,
                            label = { Text(stringResource(R.string.account_settings_hostname)) },
                            placeholder = { Text(stringResource(state.hostnamePlaceholder)) },
                            isError = state.hostnameError != null,
                            supportingText = supporting(state.hostnameError),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            modifier =
                                Modifier.weight(0.7f)
                                    .padding(end = TulkkiSpacing.xs)
                                    .focusRequester(hostnameFocus),
                        )
                        OutlinedTextField(
                            value = state.port,
                            onValueChange = { value ->
                                // The layout's `android:maxLength="5"`.
                                if (value.length <= PORT_MAX_LENGTH && value.all { it.isDigit() }) {
                                    onPortChange(value)
                                }
                            },
                            label = { Text(stringResource(R.string.account_settings_port)) },
                            enabled = state.portEnabled,
                            isError = state.portError != null,
                            supportingText = supporting(state.portError),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier =
                                Modifier.weight(0.3f)
                                    .padding(start = TulkkiSpacing.xs)
                                    .focusRequester(portFocus),
                        )
                    }
                }
                if (state.registerNewVisible) {
                    Row(
                        modifier =
                            Modifier.fillMaxWidth()
                                .padding(top = TulkkiSpacing.sm)
                                .clickable { onRegisterNewChange(!state.registerNew) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = state.registerNew,
                            onCheckedChange = onRegisterNewChange,
                        )
                        Text(stringResource(R.string.register_account))
                    }
                }
            }
        }
    }
}

/**
 * The JID field with the known-hosts drop-down the old `MaterialAutoCompleteTextView` and
 * `KnownHostsAdapter` showed.
 *
 * <p>The suggestions arrive already filtered - the Activity owns what a suggestion is - so this is
 * only the drawing, shown while the field has focus and there is at least one.
 */
@Composable
private fun JidField(
    state: EditAccountScreenState,
    focus: FocusRequester,
    onJidChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var hasFocus by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = state.jid,
            onValueChange = onJidChange,
            label = { Text(stringResource(state.jidHint)) },
            placeholder = { Text(stringResource(state.jidPlaceholder)) },
            enabled = state.jidEnabled,
            isError = state.jidError != null,
            supportingText = supporting(state.jidError),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier =
                Modifier.fillMaxWidth()
                    .focusRequester(focus)
                    .onFocusChanged {
                        hasFocus = it.isFocused
                        expanded = it.isFocused && state.jidSuggestions.isNotEmpty()
                    },
        )
        DropdownMenu(
            expanded = expanded && hasFocus && state.jidSuggestions.isNotEmpty(),
            onDismissRequest = { expanded = false },
        ) {
            for (suggestion in state.jidSuggestions) {
                DropdownMenuItem(
                    text = { Text(suggestion) },
                    onClick = {
                        expanded = false
                        onJidChange(suggestion)
                    },
                )
            }
        }
    }
}

/** The password field, with the layout's `endIconMode="password_toggle"` when it is offered. */
@Composable
private fun PasswordField(
    state: EditAccountScreenState,
    focus: FocusRequester,
    onPasswordChange: (String) -> Unit,
) {
    var revealed by remember { mutableStateOf(false) }
    TextField(
        value = state.password,
        onValueChange = onPasswordChange,
        label = { Text(stringResource(R.string.password)) },
        enabled = state.passwordEditable,
        isError = state.passwordError != null,
        supportingText = supporting(state.passwordError),
        trailingIcon =
            if (state.passwordToggleEnabled) {
                {
                    IconButton(onClick = { revealed = !revealed }) {
                        Icon(
                            painter =
                                painterResource(
                                    if (revealed) R.drawable.ic_visibility_off
                                    else R.drawable.ic_visibility_24dp
                                ),
                            contentDescription =
                                stringResource(
                                    com.google.android.material.R.string
                                        .password_toggle_content_description
                                ),
                        )
                    }
                }
            } else {
                null
            },
        visualTransformation =
            if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true,
        modifier =
            Modifier.fillMaxWidth().padding(top = TulkkiSpacing.xs).focusRequester(focus),
    )
}

/**
 * The avatar: `AvatarView` under an [AndroidView], because the loader this screen still uses
 * (`AvatarWorkerTask.loadAvatar`) takes the `ImageView` itself and keys its worker on it.
 *
 * <p>The bind runs when [avatarKey] changes rather than on every recomposition, which is what the
 * old `AvatarWorkerTask.loadAvatar` call in `updateAccountInformation` did.
 */
@Composable
private fun AvatarBridge(
    avatarKey: Any?,
    onBindAvatar: (AvatarView) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var bound by remember { mutableStateOf<Any?>(null) }
    AndroidView(
        modifier = modifier.clickable(onClick = onClick),
        factory = { context ->
            AvatarView(context).apply {
                contentDescription = context.getString(R.string.account_image_description)
            }
        },
        update = { view ->
            if (bound !== avatarKey) {
                bound = avatarKey
                onBindAvatar(view)
            }
        },
    )
}

/** The vCard card: `vcard_details`, the dynamic rows and the two buttons under them. */
@Composable
private fun VCardCard(
    state: EditAccountScreenState,
    onAddVCardRow: () -> Unit,
    onVCardTypeChange: (Long, String) -> Unit,
    onVCardValueChange: (Long, String) -> Unit,
    onVCardRemove: (Long) -> Unit,
    onVCardMove: (Int, Int) -> Unit,
    onShowVCardQr: () -> Unit,
) {
    Card(
        modifier =
            Modifier.fillMaxWidth()
                .padding(
                    horizontal = dimensionResource(R.dimen.activity_horizontal_margin),
                    vertical = dimensionResource(R.dimen.activity_vertical_margin),
                )
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(dimensionResource(R.dimen.card_padding_list))
        ) {
            Text(
                text = stringResource(R.string.vcard_details),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(TulkkiSpacing.sm),
            )
            VCardRows(
                rows = state.vcardRows,
                onTypeChange = onVCardTypeChange,
                onValueChange = onVCardValueChange,
                onRemove = onVCardRemove,
                onMove = onVCardMove,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onAddVCardRow) {
                    Text(stringResource(R.string.add_profile_field))
                }
                TextButton(
                    onClick = onShowVCardQr,
                    modifier = Modifier.padding(start = TulkkiSpacing.sm),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_qr_code_24dp),
                        contentDescription = null,
                        modifier = Modifier.padding(end = TulkkiSpacing.xs),
                    )
                    Text(stringResource(R.string.vcard_qr_code))
                }
            }
        }
    }
}

/**
 * The server-info card: the session row, the two tables the overflow's "show more" opens, the
 * verification strip and the account's own rows (display name, colour, quiet hours, both key
 * strips, and the other devices).
 *
 * <p>The layout kept all of these children of one `stats` card, which is why they are one Composable
 * here and not four.
 */
@Composable
private fun StatsCard(
    state: EditAccountScreenState,
    onEditDisplayName: () -> Unit,
    onAccountColorClick: () -> Unit,
    onQuietHoursEnabledChange: (Boolean) -> Unit,
    onQuietHoursStartClick: () -> Unit,
    onQuietHoursEndClick: () -> Unit,
    onPgpClick: () -> Unit,
    onDeletePgp: () -> Unit,
    onCopyOmemoFingerprint: () -> Unit,
    onOmemoQr: () -> Unit,
    onClearDevices: () -> Unit,
    onScan: () -> Unit,
) {
    Card(
        modifier =
            Modifier.fillMaxWidth()
                .padding(
                    horizontal = dimensionResource(R.dimen.activity_horizontal_margin),
                    vertical = dimensionResource(R.dimen.activity_vertical_margin),
                )
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(dimensionResource(R.dimen.card_padding_regular))
        ) {
            StatRow(
                label = stringResource(R.string.server_info_session_established),
                value = state.sessionEstablished,
            )
            if (state.showMore) {
                StatRow(
                    label = stringResource(R.string.server_info_login_mechanism),
                    value = state.loginMechanism,
                )
                StatRow(
                    label = stringResource(R.string.server_info_pep),
                    value = state.pep,
                )
                StatRow(
                    label = stringResource(R.string.server_info_blocking),
                    value = state.blocking,
                )
                StatRow(
                    label = stringResource(R.string.server_info_stream_management),
                    value = state.sm,
                )
                StatRow(
                    label = stringResource(R.string.server_info_external_service_discovery),
                    value = state.externalService,
                )
                StatRow(
                    label = stringResource(R.string.server_info_roster_version),
                    value = state.rosterVersion,
                )
                StatRow(
                    label = stringResource(R.string.server_info_carbon_messages),
                    value = state.carbons,
                )
                StatRow(label = stringResource(R.string.server_info_mam), value = state.mam)
                StatRow(label = stringResource(R.string.server_info_csi), value = state.csi)
                if (state.pushVisible) {
                    StatRow(
                        label = stringResource(R.string.server_info_push),
                        value = state.push,
                    )
                }
                StatRow(
                    label = stringResource(R.string.server_info_http_upload),
                    value = state.httpUpload,
                )
                StatRow(
                    label = stringResource(R.string.server_info_bind2),
                    value = state.bind2,
                )
                StatRow(
                    label = stringResource(R.string.server_info_sasl2),
                    value = state.sasl2,
                )
            }
            if (state.verificationVisible) {
                Row(
                    modifier =
                        Modifier.fillMaxWidth().padding(top = TulkkiSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = state.verificationMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        painter = painterResource(state.verificationIndicator),
                        contentDescription = null,
                    )
                }
            }
            DisplayNameRow(
                displayName = state.displayName,
                onEdit = onEditDisplayName,
            )
            if (state.accountColorVisible) {
                AccountColorRow(
                    color = state.accountColor,
                    onClick = onAccountColorClick,
                    modifier = Modifier.padding(top = TulkkiSpacing.md),
                )
            }
            if (state.quietHoursVisible) {
                QuietHoursRow(
                    enabled = state.quietHoursEnabled,
                    onEnabledChange = onQuietHoursEnabledChange,
                    modifier = Modifier.padding(top = TulkkiSpacing.md),
                )
                if (state.quietHoursEnabled) {
                    LabelledValueRow(
                        label = stringResource(R.string.title_pref_quiet_hours_start_time),
                        value = state.quietHoursStart,
                        onClick = onQuietHoursStartClick,
                        modifier = Modifier.padding(top = TulkkiSpacing.md),
                    )
                    LabelledValueRow(
                        label = stringResource(R.string.title_pref_quiet_hours_end_time),
                        value = state.quietHoursEnd,
                        onClick = onQuietHoursEndClick,
                        modifier = Modifier.padding(top = TulkkiSpacing.md),
                    )
                }
            }
            if (state.pgpVisible) {
                FingerprintRow(
                    fingerprint = state.pgpFingerprint,
                    description = stringResource(R.string.openpgp_key_id),
                    highlighted = state.pgpHighlighted,
                    onClick = onPgpClick,
                    action = {
                        IconButton(onClick = onDeletePgp) {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete_24dp),
                                contentDescription = stringResource(R.string.delete_pgp_key),
                            )
                        }
                    },
                    modifier = Modifier.padding(top = TulkkiSpacing.md),
                )
            }
            if (state.omemoVisible) {
                FingerprintRow(
                    fingerprint = state.omemoFingerprint,
                    description = stringResource(state.omemoDesc),
                    highlighted = state.omemoHighlighted,
                    onClick = onCopyOmemoFingerprint,
                    action = {
                        IconButton(onClick = onOmemoQr) {
                            Icon(
                                painter = painterResource(R.drawable.ic_qr_code_24dp),
                                contentDescription = stringResource(R.string.show_qr_code),
                            )
                        }
                    },
                    modifier = Modifier.padding(top = TulkkiSpacing.md),
                )
            }
            if (state.otherDevicesVisible) {
                OtherDevicesCard(
                    rows = state.otherDeviceKeys,
                    clearVisible = state.clearDevicesVisible,
                    unverifiedWarningVisible = state.unverifiedWarningVisible,
                    scanVisible = state.scanVisible,
                    onClearDevices = onClearDevices,
                    onScan = onScan,
                )
            }
        }
    }
}

/**
 * One line of the server-info table: the label shrinking on the start, the value stretched on the
 * end, exactly the deleted `TableLayout`'s `shrinkColumns="0" stretchColumns="1"`.
 */
@Composable
private fun StatRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The dynamic vCard rows, with the drag handle's reordering.
 *
 * <p>The old list was a `LinearLayout` of inflated `item_edit_vcard_entry` rows reordered by
 * `View.OnDragListener`: whichever child the dragged point was inside got the dragged view moved
 * before it. In Compose a non-lazy `Column` cannot be reordered by moving a node, so the same effect
 * is reached from the other side - the drag accumulates past half a row's height and the list
 * reorders itself through [onMove], with the dragged row drawn at its own offset while it moves.
 */
@Composable
private fun VCardRows(
    rows: List<VCardRowState>,
    onTypeChange: (Long, String) -> Unit,
    onValueChange: (Long, String) -> Unit,
    onRemove: (Long) -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    val heights = remember { mutableStateMapOf<Long, Float>() }
    var draggedId by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    Column(modifier = Modifier.fillMaxWidth()) {
        for (row in rows) {
            VCardRow(
                row = row,
                dragOffset = if (draggedId == row.id) dragOffset else 0f,
                onHeight = { heights[row.id] = it },
                onTypeChange = { onTypeChange(row.id, it) },
                onValueChange = { onValueChange(row.id, it) },
                onRemove = { onRemove(row.id) },
                onDragStart = {
                    draggedId = row.id
                    dragOffset = 0f
                },
                onDrag = { dy ->
                    val id = draggedId
                    if (id != null) {
                        dragOffset += dy
                        val index = rows.indexOfFirst { it.id == id }
                        val height = heights[id] ?: 1f
                        if (dragOffset > height / 2f && index >= 0 && index < rows.lastIndex) {
                            onMove(index, index + 1)
                            dragOffset -= height
                        } else if (dragOffset < -height / 2f && index > 0) {
                            onMove(index, index - 1)
                            dragOffset += height
                        }
                    }
                },
                onDragEnd = {
                    draggedId = null
                    dragOffset = 0f
                },
            )
        }
    }
}

/** One vCard row: the type selector, the value field, the delete and the drag handle. */
@Composable
private fun VCardRow(
    row: VCardRowState,
    dragOffset: Float,
    onHeight: (Float) -> Unit,
    onTypeChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onRemove: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(bottom = TulkkiSpacing.sm)
                .onGloballyPositioned { onHeight(it.size.height.toFloat()) }
                .graphicsLayer { translationY = dragOffset },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VCardTypeSelector(
            type = row.type,
            onTypeChange = onTypeChange,
            modifier = Modifier.width(EditAccountSize.vcardType),
        )
        OutlinedTextField(
            value = row.value,
            onValueChange = onValueChange,
            placeholder = { Text(stringResource(R.string.value)) },
            maxLines = 3,
            modifier = Modifier.weight(1f).padding(horizontal = TulkkiSpacing.xs),
        )
        Column {
            IconButton(onClick = onRemove) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete_24dp),
                    contentDescription = stringResource(R.string.remove_entry),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
            IconButton(
                onClick = {},
                modifier =
                    Modifier.pointerInput(row.id) {
                        detectDragGestures(
                            onDragStart = { onDragStart() },
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragEnd() },
                            onDrag = { change, amount ->
                                change.consume()
                                onDrag(amount.y)
                            },
                        )
                    },
            ) {
                Icon(
                    painter = painterResource(R.drawable.rounded_drag_handle_24),
                    contentDescription = stringResource(R.string.drag_handle),
                )
            }
        }
    }
}

/**
 * The row's type, the `Spinner` the deleted `item_edit_vcard_entry.xml` held: the same eleven
 * `VCARD_TYPES` the host owns, in the same order, with the current one shown.
 */
@Composable
private fun VCardTypeSelector(
    type: String,
    onTypeChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedTextField(
            value = type,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            trailingIcon = {
                Icon(
                    painter = painterResource(R.drawable.outline_keyboard_arrow_down_24),
                    contentDescription = stringResource(R.string.editaccount_vcard_type),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        // The field swallows its own clicks, so the row's own hit area is this overlay: the whole
        // cell opens the menu, exactly as a `Spinner` did.
        Box(
            modifier =
                Modifier.matchParentSize().clickable(
                    onClickLabel = stringResource(R.string.editaccount_vcard_type)
                ) {
                    expanded = true
                }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (option in VCARD_TYPES) {
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        expanded = false
                        onTypeChange(option)
                    },
                )
            }
        }
    }
}

/** The display name, with the edit pencil the deleted `action_edit_your_name` carried. */
@Composable
private fun DisplayNameRow(
    displayName: String?,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text =
                    if (displayName.isNullOrEmpty()) {
                        stringResource(R.string.no_name_set_instructions)
                    } else {
                        displayName
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.your_name),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onEdit) {
            Icon(
                painter = painterResource(R.drawable.ic_edit_24dp),
                contentDescription = stringResource(R.string.edit_nick),
            )
        }
    }
}

/** The account colour swatch, `account_color_thumbnail` with its `thumbnail_border`. */
@Composable
private fun AccountColorRow(color: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.account_color),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.account_color_used_on),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Box(
            modifier =
                Modifier.size(EditAccountSize.colorThumbnail)
                    .border(
                        width = EditAccountSize.colorInset,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    .padding(EditAccountSize.colorInset)
        ) {
            Box(
                modifier =
                    Modifier.fillMaxSize().background(Color(color))
            )
        }
    }
}

/** Quiet hours: the switch and its two lines, exactly the deleted `quiet_hours_box`. */
@Composable
private fun QuietHoursRow(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.title_pref_enable_quiet_hours),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.pref_quiet_hours_summary),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Switch(checked = enabled, onCheckedChange = onEnabledChange)
    }
}

/** A label over a value, both pressable: the two quiet-hours time boxes. */
@Composable
private fun LabelledValueRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * A key strip: the monospaced fingerprint over its label, with the strip's own action on the end.
 *
 * <p>The PGP strip's fingerprint and label are both clickable in the old screen and open the same
 * thing, so the whole strip is. The OMEMO strip's long press is its copy, which is the same
 * [onClick] here: the old screen's long-click listener returned true, so its context menu - whose
 * only remaining item was that copy - never opened.
 */
@Composable
private fun FingerprintRow(
    fingerprint: String,
    description: String,
    highlighted: Boolean,
    onClick: () -> Unit,
    action: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fingerprint,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.labelMedium,
                color =
                    if (highlighted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
        action()
    }
}

/**
 * The other-devices strip: its title, the key rows, the unverified warning and the two actions.
 *
 * <p>The rows are [ContactKeyRow]s now, the Compose shape `OmemoActivity.contactKeyRow` resolves:
 * `contact_key.xml` and its `omemo_key_context.xml` menu are deleted, and the host hands the rows in
 * as state rather than filling a `LinearLayout`.
 */
@Composable
private fun OtherDevicesCard(
    rows: List<ContactKeyRowState>,
    clearVisible: Boolean,
    unverifiedWarningVisible: Boolean,
    scanVisible: Boolean,
    onClearDevices: () -> Unit,
    onScan: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.other_devices),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(TulkkiSpacing.sm),
        )
        for (row in rows) {
            ContactKeyRow(row, modifier = Modifier.fillMaxWidth())
        }
        if (unverifiedWarningVisible) {
            Text(
                text = stringResource(R.string.unverified_devices),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier.padding(
                        horizontal = dimensionResource(R.dimen.card_padding_list) +
                            TulkkiSpacing.sm
                    ),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = TulkkiSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (scanVisible) {
                TextButton(onClick = onScan) { Text(stringResource(R.string.scan_qr_code)) }
            }
            if (clearVisible) {
                TextButton(onClick = onClearDevices) {
                    Text(stringResource(R.string.clear_other_devices))
                }
            }
        }
    }
}

/** The battery / data-saver card: the headline, the explanation and the one action. */
@Composable
private fun OsOptimizationCard(
    @StringRes headline: Int,
    body: String,
    @StringRes action: Int,
    onAction: () -> Unit,
) {
    Card(
        modifier =
            Modifier.fillMaxWidth()
                .padding(
                    horizontal = dimensionResource(R.dimen.activity_horizontal_margin),
                    vertical = dimensionResource(R.dimen.activity_vertical_margin),
                )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(dimensionResource(R.dimen.card_padding_regular))
            ) {
                Text(text = stringResource(headline), style = MaterialTheme.typography.titleLarge)
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = TulkkiSpacing.sm),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = TulkkiSpacing.lg),
                horizontalArrangement = Arrangement.End,
            ) {
                FilledTonalButton(onClick = onAction, modifier = Modifier.padding(bottom = TulkkiSpacing.lg)) {
                    Text(stringResource(action))
                }
            }
        }
    }
}

/** The cancel and save bar, pinned under the scrolling body exactly as `button_bar` was. */
@Composable
private fun ButtonBar(
    @StringRes saveLabel: Int,
    saveEnabled: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = TulkkiSpacing.lg, vertical = TulkkiSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextButton(onClick = onCancel) { Text(stringResource(DataR.string.cancel)) }
        Button(onClick = onSave, enabled = saveEnabled) { Text(stringResource(saveLabel)) }
    }
}

/**
 * `TextInputLayout.setError`'s `supportingText`: absent when there is no error, so the field keeps
 * its plain height in the common case exactly as the old empty `TextInputLayout` did.
 */
@Composable
private fun supporting(error: String?): (@Composable () -> Unit)? =
    if (error == null) {
        null
    } else {
        { Text(error) }
    }

/**
 * The presence dialog, Compose.
 *
 * <p>`dialog_presence.xml` held a `RadioGroup` of the four availabilities and a
 * `TextInputLayout`-wrapped `ImmediateAutoCompleteTextView` of status-message templates; it was
 * inflated only by `EditAccountActivity`, so it is deleted with the screen. This is its content:
 * the four radios under the group's own `show` gate (the app's "manually change presence" setting),
 * and the message field with the template drop-down.
 *
 * <p>The template filter is the deleted `PresenceTemplateAdapter`'s: a case-insensitive
 * `contains` on the lower-cased message, an empty needle matching everything.
 */
@Composable
fun PresenceStatusDialog(
    state: PresenceStatusDialogState,
    onStatusChange: (PresenceStatusChoice) -> Unit,
    onMessageChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val needle = state.message.trim().lowercase(Locale.getDefault())
    val suggestions =
        state.templates.filter {
            needle.isEmpty() || it.message.lowercase(Locale.getDefault()).contains(needle)
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_status_message_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (state.showStatuses) {
                    for (choice in PresenceStatusChoice.entries) {
                        Row(
                            modifier =
                                Modifier.fillMaxWidth().clickable { onStatusChange(choice) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = state.status == choice,
                                onClick = { onStatusChange(choice) },
                            )
                            Text(stringResource(choice.label))
                        }
                    }
                }
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = state.message,
                        onValueChange = onMessageChange,
                        label = { Text(stringResource(R.string.status_message)) },
                        singleLine = true,
                        modifier =
                            Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
                    )
                    DropdownMenu(
                        expanded = focused && suggestions.isNotEmpty(),
                        onDismissRequest = {},
                    ) {
                        for (option in suggestions) {
                            DropdownMenuItem(
                                text = { Text(option.message) },
                                onClick = {
                                    onMessageChange(option.message)
                                    onStatusChange(option.status)
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(DataR.string.cancel)) }
        },
    )
}

/**
 * What [PresenceStatusDialog] draws: the message being written, which availability is picked,
 * whether the group is shown at all, and the templates the message may be filled from.
 */
data class PresenceStatusDialogState(
    val message: String = "",
    val status: PresenceStatusChoice = PresenceStatusChoice.ONLINE,
    val showStatuses: Boolean = true,
    val templates: List<PresenceTemplateOption> = emptyList(),
)

/** One status-message template: its message and the availability that goes with it. */
data class PresenceTemplateOption(val message: String, val status: PresenceStatusChoice)

/** The four availabilities the old `RadioGroup` offered, in its order. */
enum class PresenceStatusChoice(@StringRes val label: Int) {
    ONLINE(R.string.presence_online),
    AWAY(R.string.presence_away),
    XA(R.string.presence_xa),
    DND(R.string.presence_dnd),
}

/**
 * The old `action_share` action view: the share icon with its three submenu entries as a drop-down
 * of its own.
 *
 * <p>The chrome's overflow is a flat list of [uk.xa0.tulkki.ui.chrome.ChromeMenuItem] and carries no
 * submenu, and the parent `action_share` item had no action - it only opened those three - so this is
 * the one live item that gets its own popup rather than an overflow row. It is drawn in the chrome's
 * `actions` slot because the old item was `showAsAction="always"`; a screen with no chrome would have
 * nowhere to put it, which is why the file lives beside [EditAccountScreen] rather than in the chrome.
 */
@Composable
fun RowScope.EditAccountShareAction(
    visible: Boolean,
    onShareUri: () -> Unit,
    onShareBarcode: () -> Unit,
    onShowQrCode: () -> Unit,
) {
    if (!visible) {
        return
    }
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                painter = painterResource(R.drawable.ic_share_24dp),
                contentDescription = stringResource(R.string.share_uri_with),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.share_as_uri)) },
                onClick = {
                    open = false
                    onShareUri()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.share_as_barcode)) },
                onClick = {
                    open = false
                    onShareBarcode()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.show_qr_code)) },
                onClick = {
                    open = false
                    onShowQrCode()
                },
            )
        }
    }
}

/**
 * The supported vCard4 fields, in the order the host's `VCARD_TYPES` had them. A copy rather than a
 * reference because the host's is private and this is the drawn form; the host keeps its own for
 * what it writes and reads, and the two must stay in step.
 */
private val VCARD_TYPES =
    listOf(
        "fn",
        "xmpp",
        "org",
        "title",
        "role",
        "url",
        "note",
        "tel",
        "email",
        "taler",
        "other",
    )

/** `android:maxLength="5"` on the port field. */
private const val PORT_MAX_LENGTH = 5
