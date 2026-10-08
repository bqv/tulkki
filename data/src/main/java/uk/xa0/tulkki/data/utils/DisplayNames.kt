package uk.xa0.tulkki.data.utils

import android.graphics.Color
import com.google.common.base.Strings
import org.hsluv.HUSLColorConverter
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Conversational
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.data.model.MucOptions
import uk.xa0.tulkki.data.model.Reaction
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.utils.JidHelper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * The names and the nickname colour a conversation is shown by, moved down from
 * `uk.xa0.tulkki.ui.utils.UIHelper` (and the XEP-0392 colour from `uk.xa0.tulkki.ui.utils.XEP0392Helper`) to
 * kill the forbidden `:data` -> `:ui` edge (D9). Those `:ui` classes keep their public methods and now
 * delegate here, so none of the callers moved and the `:data` call sites (the `model` classes, `MessageUtils`)
 * now name this class instead.
 *
 * What is here is only what is genuinely pure: it reads the `:data` entities it is handed and returns a string
 * or an `int`. The colour is XEP-0392's own rule - SHA-1 of the nickname, one byte pair into a hue, HSLuv to
 * RGB - and `android.graphics.Color.rgb` is a pure static conversion, not a resource; nothing here touches `R`,
 * a `Context` or a `View`, which is exactly why it can live below `:ui`.
 *
 * The display-name rules are kept as they were, including the ones that look odd, because the move is
 * behaviour-preserving: a MUC occupant prefers their contact's name, then their nick, then a local part of
 * their real JID; an outgoing message in a room is signed with the account's own nick; and a counterpart with
 * no user record falls back to the JID's resource or bare form.
 */
object DisplayNames {

    private fun angle(nickname: String?): Double {
        if (nickname == null) {
            return 0.0
        }
        return try {
            val sha1 = MessageDigest.getInstance("SHA-1")
            val digest = sha1.digest(nickname.toByteArray(StandardCharsets.UTF_8))
            val angle = (digest[0].toInt() and 0xff) + (digest[1].toInt() and 0xff) * 256
            angle / 65536.0
        } catch (e: Exception) {
            0.0
        }
    }

    /** XEP-0392: the colour a nickname is drawn in. Moved from `XEP0392Helper.rgbFromNick`. */
    @JvmStatic
    fun rgbFromNick(name: String?): Int {
        val hsluv = DoubleArray(3)
        hsluv[0] = angle(name) * 360
        hsluv[1] = 85.0
        hsluv[2] = 58.0
        val rgb = HUSLColorConverter.hsluvToRgb(hsluv)
        return Color.rgb(
            Math.round(rgb[0] * 255).toInt(),
            Math.round(rgb[1] * 255).toInt(),
            Math.round(rgb[2] * 255).toInt(),
        )
    }

    @JvmStatic
    fun getColorForName(name: String?): Int = rgbFromNick(name)

    @JvmStatic
    fun getDisplayName(user: MucOptions.User): String? {
        val contact = user.getContact()
        if (contact != null) {
            return contact.getDisplayName()
        }
        val name = user.getNick()
        if (name != null) {
            return name
        }
        val realJid = user.getRealJid()
        if (realJid != null) {
            return JidHelper.localPartOrFallback(realJid)
        }
        return null
    }

    @JvmStatic
    fun getMessageDisplayName(message: Message): String? {
        if (message.getModerated() != null) return "moderated"

        val conversation = message.getConversation() ?: throw NullPointerException()
        if (message.getStatus() == Message.STATUS_RECEIVED) {
            val contact = message.getContact()
            if (conversation.getMode() == Conversation.MODE_MULTI) {
                if (contact != null) {
                    return contact.getDisplayName()
                } else {
                    if (conversation is Conversation) {
                        val user = conversation.getMucOptions().findUserByFullJid(message.getCounterpart())
                        if (user != null) {
                            val dname = getDisplayName(user)
                            if (dname != null) return dname
                        }
                    }
                    return getDisplayedMucCounterpart(message.getCounterpart())
                }
            } else {
                return contact?.getDisplayName() ?: ""
            }
        } else {
            if (conversation is Conversation && conversation.getMode() == Conversation.MODE_MULTI) {
                return conversation.getMucOptions().getSelf().getNick()
            } else {
                val account: Account = conversation.getAccount() ?: throw NullPointerException()
                val jid: Jid = account.getJid()
                val displayName = account.getDisplayName()
                return if (Strings.isNullOrEmpty(displayName)) {
                    jid.getLocal() ?: jid.getDomain().toString()
                } else {
                    displayName
                }
            }
        }
    }

    @JvmStatic
    fun getDisplayName(conversation: Conversational, reaction: Reaction): String {
        if (conversation.getMode() == Conversation.MODE_MULTI) {
            if (conversation is Conversation) {
                var user = conversation.getMucOptions().findUserByFullJid(reaction.trueJid)
                if (user == null) {
                    user =
                        conversation
                            .getMucOptions()
                            .findUserByOccupantId(reaction.occupantId, reaction.from)
                }
                if (user != null) {
                    val dname = getDisplayName(user)
                    if (dname != null) return dname
                }
            }
            return getDisplayedMucCounterpart(reaction.from)
        }

        val from = reaction.from ?: throw NullPointerException()
        val account = conversation.getAccount() ?: throw NullPointerException()
        return account.getRoster().getContact(from).getDisplayName()
    }

    @JvmStatic
    fun getDisplayedMucCounterpart(counterpart: Jid?): String {
        return if (counterpart == null) {
            ""
        } else if (!counterpart.isBareJid()) {
            (counterpart.getResource() ?: throw NullPointerException()).trim { it <= ' ' }
        } else {
            counterpart.toString().trim { it <= ' ' }
        }
    }
}
