package uk.xa0.tulkki.xmpp.models.reactions

import uk.xa0.tulkki.annotation.XmlElement
import net.fellbaum.jemoji.EmojiManager
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0444 message reactions: the `<reactions/>` element and the emoji it carries.
 *
 * The Guava `Collections2.transform`/`filter` view becomes `map`/`filter`, with the declared
 * `Collection<String>` signature kept; `Element.getContent()` is non-null in the ported tree, so
 * the upstream `reaction == null ? null : reaction.getContent()` null arm is gone. `Strings.emptyToNull`
 * becomes `takeIf { it.isNotEmpty() }`, and the static `to(String)` keeps its name with `@JvmStatic`
 * because `MessageGenerator` calls it as a static.
 */
@XmlElement(namespace = Namespace.REACTIONS)
class Reactions : Extension(Reactions::class.java) {

    /**
     * The emoji carried by this element, with everything else discarded.
     *
     * XEP-0444 says a reaction should be an emoji, and nothing downstream bounds the string:
     * whatever survives here is stored and then drawn as a reaction chip next to the message. A
     * sender that put arbitrary text in a `<reaction/>` could therefore render text of their
     * choosing into the chat, in a spot the reader takes for a short trusted badge. Anything that
     * is not an emoji is dropped rather than displayed.
     *
     * Tulkki: port-11, upstream `a9658ba076`; `jemoji` is the library upstream uses.
     */
    fun getReactions(): Collection<String> =
        getExtensions(Reaction::class.java)
            .map { it.getContent() }
            .filter { it.isNotEmpty() && EmojiManager.isEmoji(it) }

    fun getId(): String? = getAttribute("id")?.takeIf { it.isNotEmpty() }

    fun setId(id: String) {
        setAttribute("id", id)
    }

    companion object {

        @JvmStatic
        fun to(id: String): Reactions {
            val reactions = Reactions()
            reactions.setId(id)
            return reactions
        }
    }
}
