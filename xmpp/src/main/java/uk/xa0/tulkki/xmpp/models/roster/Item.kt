package uk.xa0.tulkki.xmpp.models.roster

import uk.xa0.tulkki.annotation.XmlElement
import java.util.Locale
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.models.Extension

/** RFC 6121 roster item. Registered in the compiled extension index. */
@XmlElement(namespace = Namespace.ROSTER)
class Item : Extension(Item::class.java) {

    fun getJid(): Jid? = getAttributeAsJid("jid")

    fun getItemName(): String? = getAttribute("name")

    fun isPendingOut(): Boolean = "subscribe".equals(getAttribute("ask"), ignoreCase = true)

    fun getSubscription(): Subscription? {
        val value = getAttribute("subscription") ?: return null
        return try {
            Subscription.valueOf(value.uppercase(Locale.ROOT))
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun getGroups(): Collection<String> =
        getExtensions(Group::class.java).mapNotNull { it.getContent() }

    enum class Subscription {
        NONE,
        TO,
        FROM,
        BOTH,
        REMOVE,
    }

    companion object {

        @JvmField
        val RESULT_SUBSCRIPTIONS: List<Subscription> =
            listOf(Subscription.NONE, Subscription.TO, Subscription.FROM, Subscription.BOTH)
    }
}
