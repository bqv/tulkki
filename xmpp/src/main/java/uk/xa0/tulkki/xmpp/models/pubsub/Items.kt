package uk.xa0.tulkki.xmpp.models.pubsub

import uk.xa0.tulkki.xmpp.models.Extension
import uk.xa0.tulkki.xmpp.models.pubsub.event.Retract

/**
 * XEP-0060 publish-subscribe: the contract an `<items/>` list implements, with the four lookup
 * helpers as interface defaults. `getItemMap` keeps the Java's last-wins on a duplicate id and
 * returns a plain [MutableMap] where Guava's `ImmutableMap` was; `getFirstItem` answers null and
 * `getOnlyItem` refuses an empty or multiple map exactly as `Iterables` did (only the exception
 * message moves, since Kotlin's `single()` writes its own).
 */
interface Items {

    fun getItems(): Collection<Item>

    fun getNode(): String?

    fun getRetractions(): Collection<Retract>

    fun <T : Extension> getItemMap(clazz: Class<T>): MutableMap<String, T> {
        val map = LinkedHashMap<String, T>()
        for (item in getItems()) {
            val id = item.getId()
            val extension = item.getExtension(clazz)
            if (extension == null || id.isNullOrEmpty()) {
                continue
            }
            map[id] = extension
        }
        return map
    }

    fun <T : Extension> getItemOrThrow(id: String, clazz: Class<T>): T =
        getItemMap(clazz)[id] ?: throw NoSuchElementException("An item with id $id does not exist")

    fun <T : Extension> getFirstItem(clazz: Class<T>): T? =
        getItemMap(clazz).values.firstOrNull()

    fun <T : Extension> getOnlyItem(clazz: Class<T>): T = getItemMap(clazz).values.single()
}
