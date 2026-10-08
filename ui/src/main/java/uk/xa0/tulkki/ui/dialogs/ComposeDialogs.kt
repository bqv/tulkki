package uk.xa0.tulkki.ui.dialogs

import android.content.Context
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.XmppActivity
import uk.xa0.tulkki.ui.compose.tulkkiComposeView
import uk.xa0.tulkki.xmpp.Config

import java.util.Locale
import java.util.regex.Pattern

/**
 * The Compose dialogs' one host: a view-based screen shows a Compose dialog through this.
 *
 * <p>`SearchActivity`, `ContactDetailsActivity` and the rest still draw their own screen with
 * views, and a screen that is still views cannot `setContent` - that would replace the screen the
 * dialog belongs to. The composition therefore lives in a zero-sized [ComposeView] added to the
 * activity's own content frame, and the dialog draws in its own window above it, exactly as the
 * `MaterialAlertDialogBuilder` dialog it replaces did. `dismiss` removes the view, and with it the
 * composition and the window.
 *
 * <p>The view is given `DisposeOnDetachedFromWindowOrReleasedFromPool` rather than the host's own
 * strategy: a `ComposeView` that is added to the content frame and then removed again has no view
 * lifecycle of its own to wait for, and a dialog that outlives its dismissal is a leaked window.
 * The document already accepts what this costs - a dialog open across a rotation is dismissed,
 * as the settings screens' editors are, because the state it was opened with is the screen's.
 *
 * <p>**The call answers with the same dismissal.** A host that has to replace one dialog with the
 * next - the captcha dialog does, because a second captcha request arrives while the first is up -
 * needs to close the open one without a tap, and this is that handle. It is the identical removal
 * the `dismiss` slot runs, so a caller that ignores the result behaves exactly as before.
 */
fun XmppActivity.showTulkkiDialog(
    content: @Composable (dismiss: () -> Unit) -> Unit,
): () -> Unit {
    val container = findViewById<ViewGroup>(android.R.id.content)
    lateinit var view: ComposeView
    val dismiss: () -> Unit = {
        // The removal disposes the composition the click is being dispatched through, so it waits
        // for the next frame; the dialog is already dismissing its own window.
        container.post { container.removeView(view) }
    }
    view =
        tulkkiComposeView(this, darkTheme = isDark()) {
            content(dismiss)
        }
    view.setViewCompositionStrategy(
        ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
    container.addView(view, 0, 0)
    return dismiss
}

/**
 * The JID inside a sentence, monospaced - `JidDialog.style` for Compose.
 *
 * <p>The old dialogs made the address a `TypefaceSpan("monospace")` inside a `SpannableString`
 * (`JidDialog`), and the text itself is a string resource with a `%s`. This is the same split with
 * Compose's own tools: the first occurrence of the value is what the span lands on, and a value
 * that is not in the sentence leaves the sentence as plain text.
 */
internal fun styledJid(text: String, value: String): AnnotatedString = buildAnnotatedString {
    val start = text.indexOf(value)
    if (start < 0) {
        append(text)
        return@buildAnnotatedString
    }
    append(text, 0, start)
    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
        append(text, start, start + value.length)
    }
    append(text, start + value.length, text.length)
}

/**
 * The account the JID names, or `null` when there is no such account - the body of
 * `StartConversationActivity.getSelectedAccount`, which read it off an `AutoCompleteTextView`.
 * That view is gone with the dialogs' layouts, so the read is here, on the value the dropdown
 * holds.
 */
internal fun selectedAccount(context: Context, accountJid: String?): Account? {
    if (accountJid == null || context !is XmppActivity) {
        return null
    }
    val jid = try {
        Jid.of(accountJid)
    } catch (e: IllegalArgumentException) {
        return null
    }
    return AccountRegistry.get().findAccountByJid(jid)
}

/**
 * The account dropdown every create dialog had: a "Your account" exposed-menu field with the
 * activated accounts in it, showing "No accounts" and refusing the tap when there are none -
 * the deleted `StartConversationActivity.populateAccountSpinner`, in Compose.
 *
 * <p>The read-only field takes no input of its own, so the tap that opens the menu is an overlay
 * over it rather than the field itself; the empty list is what `spinner.setEnabled(false)` was.
 */
@Composable
internal fun AccountDropdown(
    accounts: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = selected ?: stringResource(R.string.no_accounts),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            enabled = accounts.isNotEmpty(),
            label = { Text(stringResource(R.string.your_account)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(enabled = accounts.isNotEmpty()) { open = true }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (account in accounts) {
                DropdownMenuItem(
                    text = { Text(account) },
                    onClick = {
                        open = false
                        onSelect(account)
                    },
                )
            }
        }
    }
}

/**
 * The JID field the channel dialogs had, with the known-host suggestions `KnownHostsAdapter`
 * filtered into its dropdown.
 *
 * <p>The filter is the adapter's own `performFiltering`: one `@` or none, the first part lowercased
 * and completed with every known domain (or the Quicksy domain for a phone number); two parts, only
 * the domains containing the typed one, and nothing when the typed domain is already known. The
 * suggestions are shown while the field has focus and the typed text has not already landed on one
 * of them, which is what the dropdown did.
 *
 * <p>`keyboardType` and `readOnly` are the two things the JID dialog's field needed beyond the
 * channel dialogs': its gateway selection switches the field to a phone or an email keyboard, and a
 * prefilled invite that may not be edited is read-only. Both default to what the other dialogs
 * already got.
 */
@Composable
internal fun JidField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String?,
    domains: List<String>,
    isError: Boolean,
    supportingText: String?,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    keyboardType: KeyboardType = KeyboardType.Email,
    readOnly: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    // The menu takes the focus while it is open, so the suggestions cannot be gated on the field's
    // own focus - a dismissed menu is remembered instead and cleared by the next keystroke.
    var dismissed by remember { mutableStateOf(false) }
    val suggestions = hostSuggestions(domains, value)
    Box(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                dismissed = false
            },
            singleLine = true,
            readOnly = readOnly,
            label = { Text(label) },
            // `DelayedHintHelper` put the hint in only while the field had focus; Compose already
            // hides it once there is text.
            placeholder = if (placeholder == null || !focused) null else { { Text(placeholder) } },
            isError = isError,
            supportingText = if (supportingText == null) null else {
                { Text(supportingText) }
            },
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = imeAction,
                ),
            keyboardActions = KeyboardActions(onDone = { onImeAction() }),
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (focusRequester == null) Modifier
                    else Modifier.focusRequester(focusRequester))
                .onFocusChanged { focused = it.isFocused },
        )
        DropdownMenu(
            expanded = suggestions.isNotEmpty() && !dismissed,
            onDismissRequest = { dismissed = true },
        ) {
            for (suggestion in suggestions) {
                DropdownMenuItem(
                    text = { Text(suggestion) },
                    onClick = { onValueChange(suggestion) },
                )
            }
        }
    }
}

/** `KnownHostsAdapter`'s filter, without the view: the completions a partly typed JID has. */
internal fun hostSuggestions(domains: List<String>, constraint: String): List<String> {
    // Java's `Pattern.split` drops trailing empty strings, which Kotlin's own `split` does not.
    val split: Array<String> = AT_PATTERN.split(constraint)
    val suggestions = mutableListOf<String>()
    when (split.size) {
        1 -> {
            val local = split[0].lowercase(Locale.ENGLISH)
            if (Config.QUICKSY_DOMAIN != null && E164_PATTERN.matcher(local).matches()) {
                suggestions.add(local + '@' + Config.QUICKSY_DOMAIN.toString())
            } else if (local.isNotEmpty()) {
                for (domain in domains) {
                    suggestions.add(local + '@' + domain)
                }
            }
        }
        2 -> {
            val localPart = split[0].lowercase(Locale.ENGLISH)
            val domainPart = split[1].lowercase(Locale.ENGLISH)
            if (!domains.contains(domainPart)) {
                for (domain in domains) {
                    if (domain.contains(domainPart)) {
                        suggestions.add(localPart + "@" + domain)
                    }
                }
            }
        }
    }
    return suggestions
}

private val AT_PATTERN = Pattern.compile("@")

private val E164_PATTERN = Pattern.compile("^\\+[1-9]\\d{1,14}$")
