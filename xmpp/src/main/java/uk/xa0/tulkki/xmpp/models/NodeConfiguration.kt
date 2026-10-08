package uk.xa0.tulkki.xmpp.models

import com.google.common.collect.ImmutableMap

/**
 * A XEP-0060 node configuration, a `Map<String, Object>` decorator with the three canned
 * configurations pubsub publishing uses. Converted from the Java.
 *
 * The Java `implements Map<String, Object>` is Kotlin's `MutableMap<String, Any?>`: the same
 * `java.util.Map<java.lang.String, java.lang.Object>` descriptor, so `Data.of`/`PublishOptions.of`
 * keep taking it as a `Map`. The delegate stays declared mutable, which is what the Java's own
 * `put`/`remove`/`clear` did - on the `ImmutableMap` the constants are built from they threw
 * `UnsupportedOperationException` before and still do. The three constants stay `@JvmField` statics.
 */
class NodeConfiguration private constructor(
    private val delegate: Map<String, Any?>,
) : MutableMap<String, Any?> {

    override val size: Int
        get() = this.delegate.size

    override fun isEmpty(): Boolean = this.delegate.isEmpty()

    override fun containsKey(key: String): Boolean = this.delegate.containsKey(key)

    override fun containsValue(value: Any?): Boolean = this.delegate.containsValue(value)

    override fun get(key: String): Any? = this.delegate[key]

    @Suppress("UNCHECKED_CAST")
    private val mutableDelegate: MutableMap<String, Any?>
        get() = this.delegate as MutableMap<String, Any?>

    override fun put(key: String, value: Any?): Any? = this.mutableDelegate.put(key, value)

    override fun remove(key: String): Any? = this.mutableDelegate.remove(key)

    override fun putAll(from: Map<out String, Any?>) {
        this.mutableDelegate.putAll(from)
    }

    override fun clear() {
        this.mutableDelegate.clear()
    }

    override val keys: MutableSet<String>
        get() = this.mutableDelegate.keys

    override val values: MutableCollection<Any?>
        get() = this.mutableDelegate.values

    override val entries: MutableSet<MutableMap.MutableEntry<String, Any?>>
        get() = this.mutableDelegate.entries

    companion object {

        private const val PERSIST_ITEMS = "pubsub#persist_items"
        private const val ACCESS_MODEL = "pubsub#access_model"
        private const val SEND_LAST_PUBLISHED_ITEM = "pubsub#send_last_published_item"
        private const val MAX_ITEMS = "pubsub#max_items"
        private const val NOTIFY_DELETE = "pubsub#notify_delete"
        private const val NOTIFY_RETRACT = "pubsub#notify_retract"

        @JvmField
        val OPEN: NodeConfiguration =
            NodeConfiguration(
                ImmutableMap.builder<String, Any>()
                    .put(PERSIST_ITEMS, true)
                    .put(ACCESS_MODEL, "open")
                    .build(),
            )

        @JvmField
        val PRESENCE: NodeConfiguration =
            NodeConfiguration(
                ImmutableMap.builder<String, Any>()
                    .put(PERSIST_ITEMS, true)
                    .put(ACCESS_MODEL, "presence")
                    .build(),
            )

        @JvmField
        val WHITELIST_MAX_ITEMS: NodeConfiguration =
            NodeConfiguration(
                ImmutableMap.builder<String, Any>()
                    .put(PERSIST_ITEMS, true)
                    .put(ACCESS_MODEL, "whitelist")
                    .put(SEND_LAST_PUBLISHED_ITEM, "never")
                    .put(MAX_ITEMS, "max")
                    .put(NOTIFY_DELETE, true)
                    .put(NOTIFY_RETRACT, true)
                    .build(),
            )
    }
}
