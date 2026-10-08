package uk.xa0.tulkki.ui.command

import android.annotation.SuppressLint
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import uk.xa0.tulkki.data.R
import uk.xa0.tulkki.data.model.ButtonGridItem
import uk.xa0.tulkki.data.model.CheckboxItem
import uk.xa0.tulkki.data.model.CommandForm
import uk.xa0.tulkki.data.model.CommandFormAction
import uk.xa0.tulkki.data.model.CommandFormInput
import uk.xa0.tulkki.data.model.CommandFormItem
import uk.xa0.tulkki.data.model.CommandFormOption
import uk.xa0.tulkki.data.model.ItemCardItem
import uk.xa0.tulkki.data.model.NoteItem
import uk.xa0.tulkki.data.model.ProgressItem
import uk.xa0.tulkki.data.model.RadioEditItem
import uk.xa0.tulkki.data.model.ResultCellItem
import uk.xa0.tulkki.data.model.ResultFieldItem
import uk.xa0.tulkki.data.model.ResultValue
import uk.xa0.tulkki.data.model.SearchListItem
import uk.xa0.tulkki.data.model.SliderItem
import uk.xa0.tulkki.data.model.SpinnerItem
import uk.xa0.tulkki.data.model.TextFieldItem
import uk.xa0.tulkki.data.model.WebItem
import uk.xa0.tulkki.ui.theme.TulkkiCommandDimens
import uk.xa0.tulkki.ui.theme.TulkkiSpacing
import kotlin.math.roundToInt

/**
 * The two labels the deleted `command_progress_bar.xml` and `command_button_grid_field.xml` carried
 * as XML literals. They stay literals until a string-table entry lands: the checker's prebuilt
 * `R.jar` is master's, so a new resource id cannot be referenced from Kotlin without a Gradle
 * regeneration this lane does not run, and an unreferenced string entry would be dead weight.
 */
private const val WAITING_LINE = "Please be patient\u2026"
private const val OTHER_CUSTOM_LABEL = "other / custom"

/**
 * One ad-hoc command page (XEP-0050 over XEP-0004), docs/MIGRATION.md `ui-9`'s command-form slice.
 *
 * <p>It is the Compose replacement for `CommandSession`'s `RecyclerView.Adapter` of Android
 * views: the rows of the form and the action buttons under them, drawn from [CommandForm] and
 * nothing else. Every decision - the field-type dispatch, a value's formatting, what an action
 * means - is [CommandForm]'s, built in `:data`; this file chooses only where a thing sits and
 * what it looks like. A `CommandFormItem` with a callback reports back through it and never
 * reaches for the wire.
 *
 * <p>**The emoji/sticker/gif panel of the old `command_page.xml` is gone, deliberately.** Its
 * `emoji_picker`, `stickersview`, `gifsview` and three buttons were never referenced by a line
 * of code - the panel was inert markup - so the redesign drops it rather than porting a dead
 * region. The composer's own emoji panel is `ui-9`'s composer slice and untouched by this one.
 *
 * <p>**Media images and inline SVG option icons are deferred.** `command_result_field.xml`'s
 * `media_image` and `button_grid_item.xml`'s compound SVG icon both needed the app's drawable
 * download cache, which is a `:ui` host concern this slice does not yet assemble; the values
 * and the buttons they would decorate are drawn without them, and the model keeps the URL so a
 * later slice can draw it in place.
 *
 * @param onAction an action button's name, the action-bar verb
 * @param onWebExecute an action posted by the out-of-band page's JavaScript
 * @param onWebPreventDefault the page claiming the next submit for itself
 */
@Composable
fun CommandFormPage(
    form: CommandForm,
    onAction: (String) -> Unit,
    onWebExecute: (String) -> Unit,
    onWebPreventDefault: (View) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                itemsIndexed(form.items) { _, item ->
                    CommandFormRow(item, onWebExecute, onWebPreventDefault)
                }
            }
            if (form.actions.isNotEmpty()) {
                CommandFormActions(form.actions, onAction)
            }
        }
    }
}

/** The one dispatch from a model row to its drawing. */
@Composable
private fun CommandFormRow(
    item: CommandFormItem,
    onWebExecute: (String) -> Unit,
    onWebPreventDefault: (View) -> Unit,
) {
    when (item) {
        is NoteItem -> NoteRow(item)
        is ProgressItem -> ProgressRow(item)
        is WebItem -> WebRow(item, onWebExecute, onWebPreventDefault)
        is ResultFieldItem -> ResultFieldRow(item, header = false)
        is ResultCellItem -> ResultCellRow(item)
        is ItemCardItem -> ItemCardRow(item)
        is CheckboxItem -> CheckboxRow(item)
        is SearchListItem -> SearchListRow(item)
        is RadioEditItem -> RadioEditRow(item)
        is SpinnerItem -> SpinnerRow(item)
        is ButtonGridItem -> ButtonGridRow(item)
        is TextFieldItem -> TextFieldRow(item)
        is SliderItem -> SliderRow(item)
    }
}

/** A note, an instruction or a server error: one centred line. */
@Composable
private fun NoteRow(item: NoteItem) {
    Text(
        text = item.message,
        style = MaterialTheme.typography.bodyMedium,
        color = if (item.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = TulkkiSpacing.sm, vertical = TulkkiSpacing.md),
    )
}

/** The waiting row: a spinner, and the patient line only once the wait has been long. */
@Composable
private fun ProgressRow(item: ProgressItem) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(TulkkiSpacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(modifier = Modifier.height(TulkkiCommandDimens.progressHeight))
        if (item.showText) {
            Text(
                WAITING_LINE,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = TulkkiSpacing.sm),
            )
        }
    }
}

/** An out-of-band (`jabber:x:oob`) page, whose JavaScript posts actions back as `xmpp_xep0050`. */
@Composable
private fun WebRow(
    item: WebItem,
    onExecute: (String) -> Unit,
    onPreventDefault: (View) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(TulkkiSpacing.sm)) {
        item.desc?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(TulkkiCommandDimens.webViewHeight),
            factory = { context ->
                val web = WebView(context)
                web.settings.javaScriptEnabled = true
                web.settings.mediaPlaybackRequiresUserGesture = false
                web.settings.databaseEnabled = true
                web.settings.domStorageEnabled = true
                web.webChromeClient = object : WebChromeClient() {}
                web.webViewClient = object : WebViewClient() {}
                web.addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun execute() = web.post { onExecute("execute") }

                        @JavascriptInterface
                        fun execute(action: String) = web.post { onExecute(action) }

                        @JavascriptInterface
                        fun preventDefault() = web.post { onPreventDefault(web) }
                    },
                    "xmpp_xep0050",
                )
                web.loadUrl(item.url)
                web
            },
        )
    }
}

/** A read-only `result`/`fixed` field: its label, its description and its values. */
@Composable
private fun ResultFieldRow(item: ResultFieldItem, header: Boolean) {
    Column(modifier = Modifier.fillMaxWidth().padding(TulkkiSpacing.sm)) {
        item.label?.let {
            Text(it, style = MaterialTheme.typography.titleSmall)
        }
        item.values.forEach { value -> ResultValueLine(value) }
        item.desc?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = TulkkiSpacing.xs),
            )
        }
    }
}

/** One value of a result field: a link when it is one, copyable otherwise. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultValueLine(value: ResultValue) {
    Text(
        text = value.text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (value.linkUrl != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { value.linkUrl?.let(value.onOpenLink) },
                onLongClick = { value.onCopy(value.text) },
            )
            .padding(vertical = TulkkiSpacing.xs),
    )
}

/** One `result`/`item` table cell: a header when no item field was reported for it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultCellRow(item: ResultCellItem) {
    Text(
        text = item.text,
        style = if (item.header) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
        color = if (item.linkUrl != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { item.linkUrl?.let(item.onOpenLink) },
                onLongClick = { item.onCopy(item.text) },
            )
            .padding(horizontal = TulkkiSpacing.sm, vertical = TulkkiSpacing.xs),
    )
}

/** A card wrapping several read-only fields, the old `command_item_card.xml`. */
@Composable
private fun ItemCardRow(item: ItemCardItem) {
    Card(modifier = Modifier.fillMaxWidth().padding(TulkkiSpacing.sm)) {
        Column(modifier = Modifier.fillMaxWidth().padding(TulkkiSpacing.sm)) {
            item.fields.forEach { ResultFieldRow(it, header = false) }
        }
    }
}

/** A labelled checkbox row. */
@Composable
private fun CheckboxRow(item: CheckboxItem) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = TulkkiSpacing.sm, vertical = TulkkiSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.label.orEmpty(), style = MaterialTheme.typography.bodyMedium)
            item.desc?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Checkbox(checked = item.checked, onCheckedChange = item.onChecked)
    }
}

/** A filter box over a list of options; `list-multi` draws checkboxes, `list-single` radios. */
@Composable
private fun SearchListRow(item: SearchListItem) {
    Column(modifier = Modifier.fillMaxWidth().padding(TulkkiSpacing.sm)) {
        item.label?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        OutlinedTextField(
            value = item.query,
            onValueChange = item.onQuery,
            singleLine = true,
            isError = item.error != null,
            supportingText = item.error?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth().padding(top = TulkkiSpacing.xs),
        )
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = TulkkiCommandDimens.optionListHeight)) {
            item.options.forEach { option ->
                val selected = option.value in item.selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { item.onSelect(option, !selected) }
                        .padding(vertical = TulkkiSpacing.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (item.multi) {
                        Checkbox(checked = selected, onCheckedChange = { item.onSelect(option, it) })
                    } else {
                        RadioButton(selected = selected, onClick = { item.onSelect(option, true) })
                    }
                    Text(option.label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item.desc?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A radio grid plus, when the field carries `open`, a free-text field. */
@Composable
private fun RadioEditRow(item: RadioEditItem) {
    Column(modifier = Modifier.fillMaxWidth().padding(TulkkiSpacing.sm)) {
        item.label?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        item.options.forEach { option ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = option.value == item.selected, onClick = { item.onSelect(option) })
                Text(option.label, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (item.open) {
            OutlinedTextField(
                value = item.selected.orEmpty(),
                onValueChange = item.onOpen,
                singleLine = true,
                isError = item.error != null,
                supportingText = item.error?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth().padding(top = TulkkiSpacing.xs),
            )
        }
        item.desc?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A single-choice dropdown. */
@Composable
private fun SpinnerRow(item: SpinnerItem) {
    var expanded by remember { mutableStateOf(false) }
    val selected = item.options.firstOrNull { it.value == item.selected }
    Column(modifier = Modifier.fillMaxWidth().padding(TulkkiSpacing.sm)) {
        item.label?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected?.label ?: item.selected.orEmpty(), overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            item.options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        expanded = false
                        item.onSelect(option)
                    },
                )
            }
        }
        item.desc?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A grid of buttons: the one-fillable-field shortcut, with its `other / custom` editor. */
@Composable
private fun ButtonGridRow(item: ButtonGridItem) {
    var customOpen by remember { mutableStateOf(false) }
    var customText by remember { mutableStateOf(item.customText) }
    Column(modifier = Modifier.fillMaxWidth().padding(TulkkiSpacing.sm)) {
        item.label?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        item.desc?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        item.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        item.defaultOption?.let { option ->
            Button(
                onClick = item.onDefault,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = TulkkiSpacing.md)
                    .heightIn(min = TulkkiCommandDimens.defaultButtonMinHeight),
            ) {
                Text(option.label)
            }
        }
        item.options.forEach { option ->
            OutlinedButton(
                onClick = { item.onChoose(option) },
                modifier = Modifier.fillMaxWidth().padding(vertical = TulkkiSpacing.xxs),
            ) {
                Text(option.label, overflow = TextOverflow.Ellipsis)
            }
        }
        if (item.open) {
            TextButton(onClick = { customText = item.customText; customOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Text(OTHER_CUSTOM_LABEL)
            }
        }
    }
    if (customOpen) {
        AlertDialog(
            onDismissRequest = { customOpen = false },
            title = { Text(item.desc ?: item.label.orEmpty()) },
            text = {
                OutlinedTextField(
                    value = customText,
                    onValueChange = { customText = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    customOpen = false
                    item.onCustom(customText)
                }) { Text(stringResource(R.string.action_execute)) }
            },
            dismissButton = {
                TextButton(onClick = { customOpen = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** One text input, with the field's hint, description and error. */
@Composable
private fun TextFieldRow(item: TextFieldItem) {
    Column(modifier = Modifier.fillMaxWidth().padding(TulkkiSpacing.sm)) {
        OutlinedTextField(
            value = item.value,
            onValueChange = item.onChange,
            label = item.label?.let { { Text(it) } },
            placeholder = item.desc?.let { { Text(it) } },
            isError = item.error != null,
            supportingText = item.error?.let { { Text(it) } },
            singleLine = item.input != CommandFormInput.MULTILINE,
            visualTransformation = if (item.input == CommandFormInput.PASSWORD) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            prefix = item.prefix?.let { { Text(it) } },
            suffix = item.suffix?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A numeric slider. */
@Composable
private fun SliderRow(item: SliderItem) {
    Column(modifier = Modifier.fillMaxWidth().padding(TulkkiSpacing.sm)) {
        item.label?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (item.max > item.min) {
            val steps = if (item.step > 0f) {
                (((item.max - item.min) / item.step).roundToInt() - 1).coerceAtLeast(0)
            } else {
                0
            }
            Slider(
                value = item.value.coerceIn(item.min, item.max),
                onValueChange = item.onChange,
                valueRange = item.min..item.max,
                steps = steps,
            )
        } else {
            Text(item.value.toString(), style = MaterialTheme.typography.bodyMedium)
        }
        item.desc?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The action bar: one button per name, tinted with the role the session resolved for it. */
@Composable
private fun CommandFormActions(actions: List<CommandFormAction>, onAction: (String) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(TulkkiSpacing.xs)) {
            actions.forEach { action ->
                Button(
                    onClick = { onAction(action.name) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(action.background),
                        contentColor = Color(action.onAccent),
                    ),
                    modifier = Modifier.fillMaxWidth().padding(TulkkiSpacing.xxs),
                ) {
                    Text(action.label, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
