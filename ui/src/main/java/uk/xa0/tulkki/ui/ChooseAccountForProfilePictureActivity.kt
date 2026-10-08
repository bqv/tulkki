package uk.xa0.tulkki.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.ui.accounts.AccountRow
import uk.xa0.tulkki.ui.accounts.ManageAccountsScreen
import uk.xa0.tulkki.ui.accounts.ManageAccountsState
import uk.xa0.tulkki.ui.accounts.accountAvatar
import uk.xa0.tulkki.ui.accounts.accountFrameColor
import uk.xa0.tulkki.ui.accounts.accountRow
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.compose.setTulkkiContent

/**
 * The account picker an `ATTACH_DATA` profile-picture share opens with.
 *
 * <p>**The layout is gone.** `activity_manage_accounts.xml` and the `AccountAdapter` that inflated
 * `item_account.xml` are deleted; the screen is [ManageAccountsScreen] inside the shared chrome
 * ([TulkkiChrome]). Only the enabled accounts are listed, and one account navigates straight on,
 * exactly as before. `configureActionBar(supportActionBar, false)` never drew an arrow, so the
 * chrome has none.
 */
class ChooseAccountForProfilePictureActivity : XmppActivity() {

    protected val accountList: MutableList<Account> = ArrayList()
    private var screen by mutableStateOf(ManageAccountsState())

    override fun refreshUiReal() {
        loadEnabledAccounts()
        rebuildRows()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The chrome draws its own bar behind the system bars; the window must not inset for them.
        enableEdgeToEdge()

        setTulkkiContent(darkTheme = isDark()) {
            TulkkiChrome(
                title = stringResource(R.string.choose_account),
                // `configureActionBar(actionBar, false)`: this screen never drew an arrow.
                onUp = null,
            ) {
                ManageAccountsScreen(
                    state = screen,
                    avatarShape = conversationAvatars().shape(),
                    onRow = { row -> accountFor(row)?.let { goToProfilePictureActivity(it) } },
                    onToggle = { _, _ -> },
                )
            }
        }
    }

    public override fun onStart() {
        super.onStart()
    }

    override fun onBackendConnected() {
        loadEnabledAccounts()
        if (accountList.size == 1) {
            goToProfilePictureActivity(accountList[0])
            return
        }
        rebuildRows()
    }

    private fun loadEnabledAccounts() {
        accountList.clear()
        for (account in AccountRegistry.get().getAccounts()) {
            if (account.isEnabled()) {
                accountList.add(account)
            }
        }
    }

    /** The deleted adapter's row facts, rebuilt whenever the enabled accounts change. */
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

    private fun goToProfilePictureActivity(account: Account) {
        val startIntent = intent
        val uri = startIntent?.data
        if (uri != null) {
            val target = Intent(this, PublishProfilePictureActivity::class.java)
            target.putExtra(XmppActivity.EXTRA_ACCOUNT, account.getJid().asBareJid().toString())
            target.data = uri
            target.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                startActivity(target)
            } catch (e: SecurityException) {
                Toast.makeText(
                    this,
                    R.string.sharing_application_not_grant_permission,
                    Toast.LENGTH_SHORT,
                ).show()
                return
            }
        }
        finish()
    }
}
