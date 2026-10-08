package uk.xa0.tulkki.xmpp.models.jingle

import com.google.common.base.CaseFormat
import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableMap
import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.jingle.stanzas.Content
import uk.xa0.tulkki.xmpp.jingle.stanzas.Group
import uk.xa0.tulkki.xmpp.jingle.stanzas.Reason
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0166 Jingle signalling: the `jingle` element. The package namespace moves onto the class,
 * because Kotlin cannot annotate a package; the (`jingle`, `urn:xmpp:jingle:1`) pair comes from the
 * `@XmlElement` annotation.
 *
 * `ReasonWrapper`'s two fields stay Java-visible with `@JvmField`, because
 * `JingleRtpConnection`/`JingleFileTransferConnection` read `wrapper.reason` and `wrapper.text`
 * directly.
 */
@XmlElement(namespace = Namespace.JINGLE)
class Jingle : Extension {

    constructor() : super(Jingle::class.java)

    constructor(action: Action, sessionId: String) : super(Jingle::class.java) {
        setAttribute("sid", sessionId)
        setAttribute("action", action.toString())
    }

    fun getSessionId(): String? = getAttribute("sid")

    fun getAction(): Action? = Action.of(getAttribute("action"))

    fun getReason(): ReasonWrapper {
        val reasonElement = findChild("reason")
        if (reasonElement == null) {
            return ReasonWrapper(Reason.UNKNOWN, null)
        }
        var text: String? = null
        var reason = Reason.UNKNOWN
        for (child in reasonElement.getChildren()) {
            if ("text" == child.getName()) {
                text = child.getContent()
            } else {
                reason = Reason.of(child.getName())
            }
        }
        return ReasonWrapper(reason, text)
    }

    fun setReason(reason: Reason, text: String?) {
        val reasonElement = addChild("reason")
        reasonElement.addChild(reason.toString())
        if (!text.isNullOrEmpty()) {
            reasonElement.addChild("text").setContent(text)
        }
    }

    // RECOMMENDED for session-initiate, NOT RECOMMENDED otherwise
    fun setInitiator(initiator: Jid) {
        Preconditions.checkArgument(initiator.isFullJid(), "initiator should be a full JID")
        setAttribute("initiator", initiator)
    }

    // RECOMMENDED for session-accept, NOT RECOMMENDED otherwise
    fun setResponder(responder: Jid) {
        Preconditions.checkArgument(responder.isFullJid(), "responder should be a full JID")
        setAttribute("responder", responder)
    }

    fun getGroup(): Group? {
        val group = findChild("group", Namespace.JINGLE_APPS_GROUPING)
        return if (group == null) null else Group.upgrade(group)
    }

    fun addGroup(group: Group) {
        addChild(group)
    }

    // TODO deprecate this somehow and make file transfer fail if there are multiple (or something)
    fun getJingleContent(): Content? {
        val content = findChild("content")
        return if (content == null) null else Content.upgrade(content)
    }

    fun addJingleContent(content: Content) {
        addChild(content)
    }

    fun getJingleContents(): Map<String, Content> {
        val builder = ImmutableMap.builder<String, Content>()
        for (child in getChildren()) {
            if ("content" == child.getName()) {
                val content = Content.upgrade(child)
                builder.put(content.getContentName()!!, content)
            }
        }
        return builder.build()
    }

    enum class Action {
        CONTENT_ACCEPT,
        CONTENT_ADD,
        CONTENT_MODIFY,
        CONTENT_REJECT,
        CONTENT_REMOVE,
        DESCRIPTION_INFO,
        SECURITY_INFO,
        SESSION_ACCEPT,
        SESSION_INFO,
        SESSION_INITIATE,
        SESSION_TERMINATE,
        TRANSPORT_ACCEPT,
        TRANSPORT_INFO,
        TRANSPORT_REJECT,
        TRANSPORT_REPLACE;

        override fun toString(): String =
                CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_HYPHEN, name)

        companion object {
            @JvmStatic
            fun of(value: String?): Action? {
                if (value.isNullOrEmpty()) {
                    return null
                }
                return try {
                    Action.valueOf(CaseFormat.LOWER_HYPHEN.to(CaseFormat.UPPER_UNDERSCORE, value))
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
        }
    }

    class ReasonWrapper(@JvmField val reason: Reason, @JvmField val text: String?)
}
