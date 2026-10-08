package uk.xa0.tulkki.ui

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.ui.accounts.AccountRow
import uk.xa0.tulkki.ui.accounts.ManageAccountsScreen
import uk.xa0.tulkki.ui.accounts.ManageAccountsState
import uk.xa0.tulkki.ui.accounts.accountAvatar
import uk.xa0.tulkki.ui.accounts.accountFrameColor
import uk.xa0.tulkki.ui.accounts.accountRow
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.utils.XmppUri

/**
 * The account picker a share opens with, once more than one account exists.
 *
 * <p>**The layout is gone.** `activity_manage_accounts.xml` and the `AccountAdapter` that inflated
 * `item_account.xml` are deleted; the screen is [ManageAccountsScreen] inside the shared chrome
 * ([TulkkiChrome]), with the same behaviour: tapping a row runs [onAccountClicked], which shares,
 * sends a command or switches to the conversation and finishes. `setSupportActionBar`,
 * `configureActionBar`, `Activities.setStatusAndNavigationBarColors` and the `RecyclerView` went
 * with the views they belonged to.
 */
class ShareViaAccountActivity : XmppActivity() {

    protected val accountList: MutableList<Account> = ArrayList()
    private var screen by mutableStateOf(ManageAccountsState())

    override fun refreshUiReal() {
        synchronized(this.accountList) {
            accountList.clear()
            accountList.addAll(AccountRegistry.get().getAccounts())
        }
        rebuildRows()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The chrome draws its own bar behind the system bars; the window must not inset for them.
        enableEdgeToEdge()

        setTulkkiContent(darkTheme = isDark()) {
            TulkkiChrome(
                title = stringResource(R.string.title_activity_share_via_account),
                // The XML bar's arrow ran `ActionBarActivity.onOptionsItemSelected(android.R.id.home)`
                // and finished.
                onUp = { finish() },
            ) {
                ManageAccountsScreen(
                    state = screen,
                    avatarShape = conversationAvatars().shape(),
                    onRow = { row -> accountFor(row)?.let { onAccountClicked(it) } },
                    onToggle = { _, _ -> },
                )
            }
        }
    }

    /** The deleted adapter's row facts, rebuilt whenever the registry changes. */
    private fun rebuildRows() {
        val rows =
            accountList.map { account ->
                accountRow(
                    this,
                    account,
                    accountAvatar(account),
                    accountFrameColor(account),
                )
            }
        screen = screen.copy(rows = rows)
    }

    private fun accountFor(row: AccountRow): Account? =
        accountList.firstOrNull { it.getJid().asBareJid().toString() == row.jid }

    private fun onAccountClicked(account: Account) {
        val action = getAction()
        if (action != null && action == "command") {
            // The Java handed `getData()` (nullable) straight to `XmppUri`; a null was its NPE.
            val data = intent.data ?: throw NullPointerException()
            startCommand(account, getJid(), XmppUri(data).getParameter("node"))
        } else {
            switchToConversation(
                getConversation(account),
                getBody(),
                false,
                null,
                false,
                false,
                action,
            )
        }
        finish()
    }

    override fun onBackendConnected() {
        val numAccounts = AccountRegistry.get().getAccounts().size

        if (numAccounts == 1) {
            val account = AccountRegistry.get().getAccounts()[0]
            val action = getAction()
            if (action != null && action == "command") {
                // The Java handed `getData()` (nullable) straight to `XmppUri`; a null was its NPE.
                val data = intent.data ?: throw NullPointerException()
                startCommand(account, getJid(), XmppUri(data).getParameter("node"))
            } else {
                switchToConversation(
                    getConversation(account),
                    getBody(),
                    false,
                    null,
                    false,
                    false,
                    action,
                )
            }
            finish()
        } else {
            refreshUiReal()
        }
    }

    protected fun getConversation(account: Account): Conversation? {
        return try {
            xmppConnectionService.findOrCreateConversation(account, getJid() ?: throw NullPointerException(), false, false)
                as Conversation?
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    protected fun getAction(): String? {
        val data = intent.data ?: return null
        val xmppUri = XmppUri(data)
        if (xmppUri.isAction("message")) return "message"
        if (xmppUri.isAction("command")) return "command"
        return null
    }

    protected fun getJid(): Jid? {
        val data = intent.data
        return if (data == null) {
            Jid.of(intent.getStringExtra(EXTRA_CONTACT) ?: throw NullPointerException())
        } else {
            XmppUri(data).getJid()
        }
    }

    protected fun getBody(): String? {
        val data = intent.data
        return if (data == null) {
            intent.getStringExtra(EXTRA_BODY)
        } else {
            XmppUri(data).getBody()
        }
    }

    companion object {
        const val EXTRA_CONTACT = "contact"
        const val EXTRA_BODY = "body"
    }
}
