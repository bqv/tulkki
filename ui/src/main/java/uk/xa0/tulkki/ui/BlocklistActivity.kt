package uk.xa0.tulkki.ui

import android.os.Bundle
import android.widget.Toast
import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Blockable
import uk.xa0.tulkki.data.model.ListItem
import uk.xa0.tulkki.data.model.RawBlockable
import uk.xa0.tulkki.xmpp.OnUpdateBlocklist
import java.util.Collections

class BlocklistActivity : AbstractSearchableListItemActivity(), OnUpdateBlocklist {

    private var account: Account? = null

    /** The JID dialog this screen launched, so a backend connect can refresh its known hosts. */
    private var enterJidSession: EnterJidDialog.Session? = null

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setRowLongClickListener { position ->
            BlockContactDialog.show(
                this,
                listItems[position] as Blockable,
            )
        }
        showFab()
        setFabClickListener { showEnterJidDialog() }
    }

    /** The manifest label the XML bar drew, now read by the chrome. */
    override fun titleRes(): Int = R.string.title_activity_block_list

    public override fun onBackendConnected() {
        for (candidate in AccountRegistry.get().getAccounts()) {
            if (candidate.getJid().toString() == intent.getStringExtra(XmppActivity.EXTRA_ACCOUNT)) {
                this.account = candidate
                break
            }
        }
        filterContacts()
        enterJidSession?.onBackendConnected()
    }

    override fun filterContacts(needle: String?) {
        listItems.clear()
        if (account != null) {
            val currentAccount = account ?: throw NullPointerException()
            for (jid in currentAccount.getBlocklist()) {
                val item: ListItem
                if (jid.isFullJid()) {
                    item = RawBlockable(currentAccount, jid)
                } else {
                    item = currentAccount.getRoster().getContact(jid)
                }
                if (item.match(this, needle)) {
                    listItems.add(item)
                }
            }
            Collections.sort(listItems)
        }
        refreshList()
    }

    protected fun showEnterJidDialog() {
        val currentAccount = account ?: throw NullPointerException()
        enterJidSession =
            EnterJidDialog.show(
                activity = this,
                activatedAccounts = emptyList(),
                title = getString(R.string.block_jabber_id),
                positiveButton = getString(R.string.block),
                secondaryButton = null,
                prefilledJid = null,
                account = currentAccount.getJid().asBareJid().toString(),
                allowEditJid = true,
                showBookmarkCheckbox = false,
                sanityCheck = EnterJidDialog.SanityCheck.NO,
            ) { _, contactJid, _, _ ->
                val blockable: Blockable = RawBlockable(currentAccount, contactJid)
                if (xmppConnectionService.sendBlockRequest(
                        blockable.getAccount(), blockable.getBlockedJid(), false, null)) {
                    Toast.makeText(
                        this,
                        R.string.corresponding_chats_closed,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                true
            }
    }

    override fun refreshUiReal() {
        // The old field's text is always a String here; an empty query means "everything", exactly
        // as `Contact.match` read the empty EditText.
        filterContacts(searchQueryText.ifEmpty { null })
    }

    override fun OnUpdateBlocklist(status: OnUpdateBlocklist.Status) {
        refreshUi()
    }
}
