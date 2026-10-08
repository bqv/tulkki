package uk.xa0.tulkki.ui

import android.os.Bundle
import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.model.Contact
import java.util.Collections

class ShortcutActivity : AbstractSearchableListItemActivity() {

    override fun refreshUiReal() {}

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setRowClickListener { position ->
            val caller = callingActivity
            hideKeyboard()

            val listItem = listItems[position]
            val legacy = BLACKLISTED_ACTIVITIES.contains(caller?.className)
            val shortcut =
                xmppConnectionService.getShortcutService()
                    .createShortcut(listItem as Contact, legacy)
            setResult(RESULT_OK, shortcut)
            finish()
        }
    }

    /** `onStart`'s title, which overrode the manifest's `@string/contact` label. */
    override fun titleRes(): Int = R.string.create_shortcut

    // The base's no-arg `filterContacts()` passes null in, so the needle is nullable here: a
    // non-null Kotlin parameter would add an intrinsic check the Java never had.
    override fun filterContacts(needle: String?) {
        listItems.clear()
        if (xmppConnectionService == null) {
            refreshList()
            return
        }
        for (account in AccountRegistry.get().getAccounts()) {
            if (account.isEnabled()) {
                for (contact in account.getRoster().getContacts()) {
                    if (contact.showInContactList() && contact.match(this, needle)) {
                        listItems.add(contact)
                    }
                }
            }
        }
        Collections.sort(listItems)
        refreshList()
    }

    companion object {
        private val BLACKLISTED_ACTIVITIES: List<String> =
            listOf("com.teslacoilsw.launcher.ChooseActionIntentActivity")
    }
}
