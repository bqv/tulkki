package uk.xa0.tulkki.app.extras

import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.ui.XmppActivity
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.forms.Data
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace

/**
 * Finishes an onboarding by moving the account's Jabber ID through the domain's own
 * `change jabber id` command, four round trips deep.
 *
 * <p>An `object` with one `fun finish` (its only caller is `UiAppHost`, Kotlin since `port-6`) and
 * two private statics that were `private static` in the Java too: the `WORKING` latch and the
 * one-thread scheduler the retry is parked on.
 *
 * <p>Interop tax: 0. `port-18` dropped the `@JvmStatic` this function carried while its caller was
 * Java: no `.java` file names `FinishOnboarding`, so §5.5's audit leaves it owed to nobody.
 *
 * <p>The retry keeps its shape: `WORKING` is cleared *before* the three-second schedule, and the
 * final success deliberately leaves the latch set (the Java's own comment says so), so this runs
 * once per success and retries itself on every failure path.
 *
 * <p>The four nested listeners become four nested trailing lambdas, and each Java `return;` becomes
 * `return@sendIqPacket` - the innermost of the four same-named lambdas, which is exactly the
 * listener the Java's bare `return` belonged to.
 */
object FinishOnboarding {

    private val WORKING = AtomicBoolean(false)

    private var SCHEDULER: ScheduledExecutorService = Executors.newScheduledThreadPool(1)

    private fun retry(
            xmppConnectionService: XmppConnectionService,
            activity: XmppActivity,
            onboardAccount: Account,
            newAccount: Account
    ) {
        WORKING.set(false)
        SCHEDULER.schedule(
                { finish(xmppConnectionService, activity, onboardAccount, newAccount) },
                3,
                TimeUnit.SECONDS)
    }

    fun finish(
            xmppConnectionService: XmppConnectionService,
            activity: XmppActivity,
            onboardAccount: Account,
            newAccount: Account
    ) {
        if (!WORKING.compareAndSet(false, true)) return

        val packet = Iq(Iq.Type.SET)
        packet.setTo(Jid.of("cheogram.com"))
        val c = packet.addChild("command", Namespace.COMMANDS)
        c.setAttribute("node", "change jabber id")
        c.setAttribute("action", "execute")

        Log.d(Config.LOGTAG, "" + packet)
        xmppConnectionService.sendIqPacket(onboardAccount, packet) { iq ->
            val command = iq.findChild("command", "http://jabber.org/protocol/commands")
            if (command == null) {
                Log.e(Config.LOGTAG, "Did not get expected data form from cheogram, got: " + iq)
                retry(xmppConnectionService, activity, onboardAccount, newAccount)
                return@sendIqPacket
            }

            val form = command.findChild("x", "jabber:x:data")
            val dataForm = if (form == null) null else Data.parse(form)
            if (dataForm == null || dataForm.getFieldByName("new-jid") == null) {
                Log.e(Config.LOGTAG, "Did not get expected data form from cheogram, got: " + iq)
                retry(xmppConnectionService, activity, onboardAccount, newAccount)
                return@sendIqPacket
            }

            dataForm.put("new-jid", newAccount.getJid().toString())
            dataForm.submit()
            command.setAttribute("action", "execute")
            iq.setTo(iq.getFrom())
            iq.setAttribute("type", "set")
            iq.removeAttribute("from")
            iq.removeAttribute("id")
            xmppConnectionService.sendIqPacket(onboardAccount, iq) { iq2 ->
                val command2 = iq2.findChild("command", "http://jabber.org/protocol/commands")
                if (command2 != null &&
                        command2.getAttribute("status") != null &&
                        command2.getAttribute("status").equals("completed")) {
                    val regPacket = Iq(Iq.Type.SET)
                    regPacket.setTo(Jid.of("cheogram.com/CHEOGRAM%jabber:iq:register"))
                    val c2 = regPacket.addChild("command", Namespace.COMMANDS)
                    c2.setAttribute("node", "jabber:iq:register")
                    c2.setAttribute("action", "execute")
                    xmppConnectionService.sendIqPacket(newAccount, regPacket) { iq3 ->
                        val command3 =
                                iq3.findChild("command", "http://jabber.org/protocol/commands")
                        if (command3 == null) {
                            Log.e(
                                    Config.LOGTAG,
                                    "Did not get expected data form from cheogram, got: " + iq3)
                            retry(xmppConnectionService, activity, onboardAccount, newAccount)
                            return@sendIqPacket
                        }

                        val form3 = command3.findChild("x", "jabber:x:data")
                        val dataForm3 = if (form3 == null) null else Data.parse(form3)
                        if (dataForm3 == null || dataForm3.getFieldByName("confirm") == null) {
                            Log.e(
                                    Config.LOGTAG,
                                    "Did not get expected data form from cheogram, got: " + iq3)
                            retry(xmppConnectionService, activity, onboardAccount, newAccount)
                            return@sendIqPacket
                        }

                        dataForm3.put("confirm", "true")
                        dataForm3.submit()
                        command3.setAttribute("action", "execute")
                        iq3.setTo(iq3.getFrom())
                        iq3.setAttribute("type", "set")
                        iq3.removeAttribute("from")
                        iq3.removeAttribute("id")
                        xmppConnectionService.sendIqPacket(newAccount, iq3) { iq4 ->
                            val command4 =
                                    iq4.findChild(
                                            "command", "http://jabber.org/protocol/commands")
                            if (command4 != null &&
                                    command4.getAttribute("status") != null &&
                                    command4.getAttribute("status").equals("completed")) {
                                xmppConnectionService.createContact(
                                        newAccount.getRoster().getContact((iq4.getFrom() ?: throw NullPointerException()).asBareJid()),
                                        true)
                                val onboardingConversation =
                                        xmppConnectionService.findOrCreateConversation(
                                                newAccount,
                                                (iq4.getFrom() ?: throw NullPointerException()).asBareJid(),
                                                true,
                                                true,
                                                true) as Conversation
                                xmppConnectionService.markRead(onboardingConversation)
                                xmppConnectionService.clearConversationHistory(
                                        onboardingConversation)
                                xmppConnectionService.deleteAccount(onboardAccount)
                                activity.switchToConversation(
                                        onboardingConversation,
                                        null,
                                        false,
                                        null,
                                        false,
                                        false,
                                        "command")
                                // We don't set WORKING back to false because we suceeded so it should never run again anyway
                            } else {
                                Log.e(Config.LOGTAG, "Error confirming jid switch, got: " + iq4)
                                retry(xmppConnectionService, activity, onboardAccount, newAccount)
                            }
                        }
                    }
                } else {
                    Log.e(Config.LOGTAG, "Error during jid switch, got: " + iq2)
                    retry(xmppConnectionService, activity, onboardAccount, newAccount)
                }
            }
        }
    }
}
