package uk.xa0.tulkki.ui.extras

import android.widget.ArrayAdapter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.google.android.material.color.MaterialColors
import java.util.Locale
import uk.xa0.tulkki.data.model.ListItem
import uk.xa0.tulkki.ui.details.TagChip
import uk.xa0.tulkki.ui.details.TagPill
import uk.xa0.tulkki.ui.utils.XEP0392Helper

/**
 * The tag editor `activity_contact_details.xml`'s `edit_tags` and the group screen's editor held:
 * `TagEditorView`, a `TokenCompleteTextView`, is deleted and its tokens are Compose pills now.
 *
 * <p>**The state answers the four calls the two hosts made.** `clearSync`, `addObjectSync`,
 * `getObjects` and `setAdapter` keep their names and their meanings, so `ContactDetailsActivity`
 * and `ConferenceDetailsActivity` still seed the editor with the stored groups, still read them back
 * on save, and still hand it the tag suggestions their own counting produced. The rest is the
 * view's own behaviour: a tag is added once (`shouldIgnoreToken` compared case-insensitively, as
 * `ListItem.Tag.equals` does), a blank entry is not a tag, and [TagEditorField] draws a typed word
 * as a pill on the IME's done action or on a comma, exactly the characters the tokenizer split on.
 *
 * <p>**What is not carried over.** The typed text sat inside the same `EditText` as the tokens and
 * the completions appeared in a dropdown under it; here the pills are a [FlowRow] above the field
 * and the matching suggestions are the same pills under it. Recorded as a look change, not a
 * behaviour one - the same words become the same tags on the same gestures.
 */
class TagEditorState {

    /** The field's hint, `details_tags_hint` in both hosts. */
    var hint by mutableStateOf("")

    /** The tags the editor holds, in the order they were added. */
    var tags by mutableStateOf<List<ListItem.Tag>>(emptyList())
        private set

    /** The field's own text, not yet a tag. */
    var query by mutableStateOf("")

    /**
     * The old `TextWatcher.afterTextChanged`: the group screen recalculates its editor button on
     * every edit of the tags, so the composable calls this back.
     */
    var onChanged: (() -> Unit)? = null

    private var suggestions by mutableStateOf<List<ListItem.Tag>>(emptyList())

    /** The deleted `TagEditorView.clearSync`: every tag goes. */
    fun clearSync() {
        tags = emptyList()
        onChanged?.invoke()
    }

    /** The deleted `addObjectSync`: one tag joins the editor, and a duplicate does not. */
    fun addObjectSync(tag: ListItem.Tag) {
        if (!tags.contains(tag)) {
            tags = tags + tag
            onChanged?.invoke()
        }
    }

    /** The deleted `getObjects`, which the hosts push to the server on save. */
    fun getObjects(): List<ListItem.Tag> = tags

    /** The deleted `setAdapter`: the suggestions the completion list would have offered. */
    fun setAdapter(adapter: ArrayAdapter<ListItem.Tag>) {
        suggestions = (0 until adapter.count).mapNotNull { adapter.getItem(it) }
    }

    /** A pill's click: the token click style was `Delete`. */
    fun remove(tag: ListItem.Tag) {
        tags = tags - tag
        onChanged?.invoke()
    }

    /** A typed word becomes a tag, unless it is blank or already held. */
    fun commit(text: String) {
        val name = text.trim()
        query = ""
        if (name.isEmpty()) {
            return
        }
        addObjectSync(ListItem.Tag(name))
    }

    /**
     * The suggestions the typed text narrows to: `ArrayAdapter`'s own default filter is a
     * case-insensitive prefix match on the item, and a tag already held is dropped by
     * `shouldIgnoreToken`'s rule.
     */
    fun matching(): List<ListItem.Tag> {
        val needle = query.trim().lowercase(Locale.US)
        if (needle.isEmpty()) {
            return emptyList()
        }
        return suggestions.filter {
            it.name.lowercase(Locale.US).startsWith(needle) && !tags.contains(it)
        }
    }
}

/**
 * The tag editor's body: the held tags as removable pills over a field, and the suggestions the
 * typed text matches under it. Every pill is the tree's [TagPill] on the same
 * `XEP0392Helper.rgbFromNick` tint the deleted token views carried, and each is white
 * `labelMedium` on the harmonised colour.
 *
 * <p>The tint is the deleted view's harmonisation with the theme's primary, but the primary is read
 * from the Compose theme this field is drawn in, not from the hosting Android theme, because
 * `MaterialColors.harmonizeWithPrimary` reads `colorPrimary` out of the `Context`'s theme and
 * throws where there is none - a Composable cannot know whether it has an application theme, and a
 * preview renders under none at all. The two are the same value: `TulkkiColors`' `primary` mirrors
 * the `md_theme_*_primary` that `Theme.Tulkki` sets, and `TokenParityTest` keeps them equal.
 *
 * @param state the editor's tags, its typed text and its suggestions.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagEditorField(state: TagEditorState, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary.toArgb()
    Column(modifier) {
        if (state.tags.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            ) {
                for (tag in state.tags) {
                    TagPill(
                        chip =
                            TagChip(
                                tag.name,
                                MaterialColors.harmonize(
                                    XEP0392Helper.rgbFromNick(tag.name),
                                    primary,
                                ),
                            ),
                        modifier = Modifier.clickable { state.remove(tag) },
                    )
                }
            }
        }
        OutlinedTextField(
            value = state.query,
            onValueChange = { value ->
                if (value.endsWith(",")) {
                    state.commit(value.dropLast(1))
                } else {
                    state.query = value
                    state.onChanged?.invoke()
                }
            },
            placeholder = { Text(state.hint) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { state.commit(state.query) }),
            modifier = Modifier.fillMaxWidth(),
        )
        val matches = state.matching()
        if (matches.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                for (tag in matches) {
                    TagPill(
                        chip =
                            TagChip(
                                tag.name,
                                MaterialColors.harmonize(
                                    XEP0392Helper.rgbFromNick(tag.name),
                                    primary,
                                ),
                            ),
                        modifier = Modifier.clickable { state.commit(tag.name) },
                    )
                }
            }
        }
    }
}
