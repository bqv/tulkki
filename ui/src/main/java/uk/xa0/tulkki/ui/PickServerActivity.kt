package uk.xa0.tulkki.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import uk.xa0.tulkki.ui.welcome.PickServerScreen

/**
 * The "pick your XMPP service" fork: create a new address, or bring your own provider.
 *
 * <p>The screen is [PickServerScreen], composed straight into
 * [uk.xa0.tulkki.ui.chrome.TulkkiChrome]; `activity_pick_server.xml` is deleted with the swap. The
 * two buttons keep their own destinations and their invite URI, and the up arrow is the action bar's
 * own "back to the welcome screen" - the system back button still goes to
 * [WelcomeActivity] through [onBackPressed].
 */
class PickServerActivity : XmppActivity() {

    override fun refreshUiReal() {}

    override fun onBackendConnected() {}

    public override fun onStart() {
        super.onStart()
        /* final int theme = findTheme();
        if (this.mTheme != theme) {
            recreate();
        }*/
    }

    public override fun onBackPressed() {
        startActivity(Intent(this, WelcomeActivity::class.java))
        super.onBackPressed()
    }

    // The Java widened `Activity.onNewIntent` from protected to public and null-checked the
    // argument; the explicit public and the nullable parameter keep both halves.
    public override fun onNewIntent(intent: Intent) {
        if (intent != null) {
            setIntent(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (resources.getBoolean(R.bool.portrait_only)) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setTulkkiContent(darkTheme = isDark()) {
            TulkkiChrome(
                // `@string/get_jabber_id`, from the manifest, as the XML toolbar's own label was.
                title = getTitle().toString(),
                onUp = { goWelcome() },
            ) {
                PickServerScreen(
                    onUseDefault = {
                        val intent =
                            Intent(this@PickServerActivity, MagicCreateActivity::class.java)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        addInviteUri(intent)
                        startActivity(intent)
                    },
                    onUseOwnProvider = {
                        val accounts = AccountRegistry.get().getAccounts()
                        var target =
                            Intent(this@PickServerActivity, EditAccountActivity::class.java)
                        target.putExtra(EditAccountActivity.EXTRA_FORCE_REGISTER, true)
                        if (accounts.size == 1) {
                            target.putExtra("jid", accounts[0].getJid().asBareJid().toString())
                            target.putExtra("init", true)
                        } else if (accounts.size >= 1) {
                            target =
                                Intent(this@PickServerActivity, ManageAccountActivity::class.java)
                        }
                        addInviteUri(target)
                        startActivity(target)
                    },
                )
            }
        }
    }

    /** The action bar's up item: back to the screen that opened this one. */
    private fun goWelcome() {
        startActivity(Intent(this, WelcomeActivity::class.java))
        finish()
    }

    fun addInviteUri(intent: Intent) {
        StartConversationActivity.addInviteUri(intent, getIntent())
    }

    companion object {
        @JvmStatic
        fun launch(activity: AppCompatActivity) {
            val intent = Intent(activity, PickServerActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            activity.startActivity(intent)
            activity.overridePendingTransition(0, 0)
        }
    }
}
