package uk.xa0.tulkki.xmpp.models

import com.google.common.base.Preconditions
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xmpp.models.ExtensionFactory

/**
 * The base of every `@XmlElement` model: an [Element] that knows the registry id its class carries.
 * Converted from the Java. The `public` modifiers are written out as `open` rather than taken from
 * Kotlin's default, because `capabilties.EntityCapabilities` declares `getExtension` and the Java
 * method is what satisfies it through `stanza.Presence`.
 *
 * The collection returns are `MutableCollection`, exactly what a Java `Collection` return is to a
 * Kotlin caller; the Java's lazy Guava `Collections2` views become eager lists.
 */
open class Extension private constructor(id: ExtensionFactory.Id) : Element(id.name, id.namespace) {

    constructor(clazz: Class<out Extension>) :
        this(
            Preconditions.checkNotNull(
                ExtensionFactory.id(clazz),
                String.format("%s does not seem to be annotated with @XmlElement", clazz.getName()),
            ),
        ) {
        Preconditions.checkArgument(
            javaClass == clazz,
            "clazz passed in constructor must match class",
        )
    }

    open fun <E : Extension> hasExtension(clazz: Class<E>): Boolean =
        getChildren().any { clazz.isInstance(it) }

    open fun <E : Extension> getExtension(clazz: Class<E>): E? {
        val extension = getChildren().firstOrNull { clazz.isInstance(it) } ?: return null
        return clazz.cast(extension)
    }

    open fun <E : Extension> getOnlyExtension(clazz: Class<E>): E? {
        val extensions = getExtensions(clazz)
        if (extensions.size == 1) {
            return extensions.first()
        }
        return null
    }

    open fun <E : Extension> getExtensions(clazz: Class<E>): MutableCollection<E> =
        getChildren().filter { clazz.isInstance(it) }.map { clazz.cast(it) }.toMutableList()

    open fun getExtensionIds(): MutableCollection<ExtensionFactory.Id> =
        getChildren().map { ExtensionFactory.Id(it.getName(), it.getNamespace()) }.toMutableList()

    open fun <T : Extension> addExtension(child: T): T {
        this.addChild(child)
        return child
    }

    open fun addExtensions(extensions: Collection<Extension>) {
        for (extension in extensions) {
            addExtension(extension)
        }
    }
}
