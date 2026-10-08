package uk.xa0.tulkki.app.utils

import android.app.Activity
import android.content.Intent
import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.ui.ConversationListActivity
import uk.xa0.tulkki.ui.EditAccountActivity
import uk.xa0.tulkki.ui.MagicCreateActivity
import uk.xa0.tulkki.ui.ManageAccountActivity
import uk.xa0.tulkki.ui.PickServerActivity
import uk.xa0.tulkki.ui.StartConversationActivity
import uk.xa0.tulkki.ui.WelcomeActivity
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.xmpp.utils.AccountUtils

/**
 * The onboarding intents, and the one place that decides where a launch with no conversation lands.
 *
 * <p>An `object` with `@JvmStatic`s; the Java declared no constructor and nothing constructs it.
 *
 * <p><strong>`getSignUpIntent`'s two Java overloads become one function with a default argument and
 * `@JvmOverloads`</strong>, which is the one place in this file where the Kotlin is a different
 * shape from the Java rather than the same shape in another syntax. The generated overloads are
 * `getSignUpIntent(Activity)` and `getSignUpIntent(Activity, boolean)`, and the one-argument form
 * delegates with `false` exactly as the Java's did, so every call site compiles and behaves
 * identically.
 *
 * <p>The `if (jid.isDomainJid())` branches in `getTokenRegistrationIntent` look redundant - both put
 * the domain, and only the second puts the username - and they are kept exactly as they are.
 * Faithfulness first: this is a translation, and a translation that also "tidies" a branch is two
 * commits.
 *
 * <p>Every `SomeClass.class` becomes `SomeClass::class.java`, which is the same `Class` object; the
 * `putExtra` overloads and the flag bitmask are unchanged.
 */
object SignupUtils {

    @JvmStatic
    fun isSupportTokenRegistry(): Boolean = true

    @JvmStatic
    fun getTokenRegistrationIntent(activity: Activity, jid: Jid, preAuth: String?): Intent {
        val intent = Intent(activity, MagicCreateActivity::class.java)
        if (jid.isDomainJid()) {
            intent.putExtra(MagicCreateActivity.EXTRA_DOMAIN, jid.getDomain().toString())
        } else {
            intent.putExtra(MagicCreateActivity.EXTRA_DOMAIN, jid.getDomain().toString())
            intent.putExtra(MagicCreateActivity.EXTRA_USERNAME, jid.getLocal())
        }
        intent.putExtra(MagicCreateActivity.EXTRA_PRE_AUTH, preAuth)
        return intent
    }

    @JvmStatic
    @JvmOverloads
    fun getSignUpIntent(activity: Activity, toServerChooser: Boolean = false): Intent =
            if (toServerChooser) {
                Intent(activity, PickServerActivity::class.java)
            } else {
                Intent(activity, WelcomeActivity::class.java)
            }

    @JvmStatic
    fun getRedirectionIntent(activity: ConversationListActivity): Intent {
        val service: XmppConnectionService = activity.xmppConnectionService
        val pendingAccount: AccountRef? =
                AccountUtils.getPendingAccount(AccountRegistry.get().getAccounts())
        val intent: Intent
        if (pendingAccount != null) {
            // Tulkki: the onboarding domain that used to send a pending account to WelcomeActivity
            // is gone (Config.ONBOARDING_DOMAIN), so a half-created account is finished where every
            // other account is edited.
            intent = Intent(activity, EditAccountActivity::class.java)
            intent.putExtra("jid", pendingAccount.getJid().asBareJid().toString())
            if (!pendingAccount.isOptionSet(AccountRef.OPTION_MAGIC_CREATE)) {
                intent.putExtra(
                        EditAccountActivity.EXTRA_FORCE_REGISTER,
                        pendingAccount.isOptionSet(AccountRef.OPTION_REGISTER))
            }
        } else {
            if (AccountRegistry.get().getAccounts().size == 0) {
                if (Config.X509_VERIFICATION) {
                    intent = Intent(activity, ManageAccountActivity::class.java)
                } else {
                    // Tulkki: no account at all lands on the add-account screen. The magic-create
                    // branch that used to pick MagicCreateActivity/WelcomeActivity is deleted with
                    // Config.MAGIC_CREATE_DOMAIN; this is the one working account-add path.
                    intent = Intent(activity, EditAccountActivity::class.java)
                }
            } else {
                intent = Intent(activity, StartConversationActivity::class.java)
            }
        }
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        return intent
    }
}
