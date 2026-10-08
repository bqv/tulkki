package uk.xa0.tulkki.ui.searchable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import uk.xa0.tulkki.ui.R

/**
 * The body the three searchable list pickers share: a search field that appears and disappears, the
 * list itself, and the floating action button.
 *
 * <p>**The screen takes its list as a slot.** The list is a
 * [uk.xa0.tulkki.ui.list.PickerList] now - `ListView`, `ListItemAdapter` and `item_contact.xml` are
 * deleted - and the slot is kept so a screenshot cell can compose the shell without the rows, and so
 * the pickers' host owns the filtering and the gestures. The screen owns everything the old
 * `activity_choose_contact.xml` shell drew around it: the search field, its show/hide, and the FAB's
 * position.
 *
 * <p>**The search field replaces the bar's `action_search` action view.** That was an `EditText`
 * inflated into the toolbar's collapsed menu item; here it is a Compose `TextField` shown in the
 * content while [searchOpen], with the same hint (`R.string.search_contacts`), the same search IME
 * action, and the same clear affordance. The caller owns the text and filters on every change, so
 * the pickers' `filterContacts` is reached exactly as the old `TextWatcher` reached it.
 *
 * @param list the list body, a `PickerList` in production and empty in the screenshot cells.
 * @param searchOpen whether the search field is drawn and focused.
 * @param query the search field's text, owned by the caller.
 * @param onQueryChange called on every edit, so the caller can filter.
 * @param onSearchClose called by the field's clear button: the old action view's collapse, which
 *     cleared the text and reset the filter.
 * @param onSearchSubmit the IME's search action, the old `OnEditorActionListener` hook.
 * @param fabIcon the FAB's drawable.
 * @param fabVisible whether the FAB is drawn at all.
 * @param fabDescription the FAB's accessible name.
 * @param onFab the FAB's action.
 */
@Composable
fun SearchableListScreen(
    list: @Composable () -> Unit,
    searchOpen: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchClose: () -> Unit,
    onSearchSubmit: () -> Boolean,
    fabIcon: Int,
    fabVisible: Boolean,
    fabDescription: String,
    onFab: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (searchOpen) {
                SearchField(
                    query = query,
                    onQueryChange = onQueryChange,
                    onSearchClose = onSearchClose,
                    onSearchSubmit = onSearchSubmit,
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) { list() }
        }
        if (fabVisible) {
            FloatingActionButton(
                onClick = onFab,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) {
                Icon(painter = painterResource(fabIcon), contentDescription = fabDescription)
            }
        }
    }
}

/** The search field, focused and with the IME shown as soon as it is drawn. */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchClose: () -> Unit,
    onSearchSubmit: () -> Boolean,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = 12.dp)
                .focusRequester(focusRequester),
        placeholder = { Text(stringResource(R.string.search_contacts)) },
        leadingIcon = {
            Icon(
                painter = painterResource(R.drawable.ic_search_24dp),
                contentDescription = null,
            )
        },
        trailingIcon = {
            IconButton(
                onClick = {
                    keyboard?.hide()
                    onSearchClose()
                }
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_clear_24dp),
                    contentDescription = stringResource(R.string.clear_search),
                )
            }
        },
        singleLine = true,
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Search,
            ),
        keyboardActions = KeyboardActions(onSearch = { onSearchSubmit() }),
    )
}
