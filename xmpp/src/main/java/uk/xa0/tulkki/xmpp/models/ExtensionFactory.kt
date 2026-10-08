package uk.xa0.tulkki.xmpp.models

import com.google.common.base.MoreObjects
import com.google.common.base.Objects
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.lang.reflect.Constructor
import java.nio.charset.StandardCharsets
import java.util.Collections
import uk.xa0.tulkki.xml.Element

/**
 * Turns an element name + namespace into a model class, and back again, out of the extension index
 * `:xmpp`'s `generateExtensionIndex` writes from the compiled `@XmlElement` types.
 *
 * The public surface is the Java one: [create] and [id] are static methods on this class (the
 * companion's `@JvmStatic`), and [Id]'s `name`/`namespace` are `@JvmField` fields, so Java callers
 * spell exactly what they spelled before. The class is a holder with a private constructor, not a
 * Kotlin `object`, so that surface survives.
 */
class ExtensionFactory private constructor() {

    companion object {

        /**
         * The extension index, as a java resource beside this class: the sorted TSV
         * `namespace \t name \t binary-class` that `generateExtensionIndex` scans out of the
         * compiled `@XmlElement` types, with a `# tulkki-extension-index/1` header.
         *
         * Read through `Class.getResourceAsStream`, so no `Context` is needed at this static call
         * site and no post-compile `aapt2` step has to inject it. The class is named in its
         * *binary* spelling (`Outer$Inner`), which is what `Class.forName` wants and what ClassGraph
         * reports - unlike the annotation processor it replaces, whose `getQualifiedName()` spelled
         * the nested types with `.`.
         */
        private const val INDEX_RESOURCE = "extension-index.txt"

        @JvmStatic
        fun create(name: String, namespace: String?): Element {
            val clazz = of(name, namespace)
            if (clazz == null) {
                return Element(name, namespace)
            }
            val constructor: Constructor<out Element>
            try {
                constructor = clazz.getDeclaredConstructor()
            } catch (e: NoSuchMethodException) {
                throw IllegalStateException(
                    String.format("%s has no default constructor", clazz.name),
                    e,
                )
            }
            try {
                return constructor.newInstance()
            } catch (e: ReflectiveOperationException) {
                throw IllegalStateException(
                    String.format("%s has inaccessible default constructor", clazz.name),
                    e,
                )
            }
        }

        private fun of(name: String, namespace: String?): Class<out Extension>? =
            Index.BY_ID[Id(name, namespace)]

        @JvmStatic
        fun id(clazz: Class<out Extension>): Id? = Index.BY_CLASS[clazz]

        private fun readIndex(
            byId: MutableMap<Id, Class<out Extension>>,
            byClass: MutableMap<Class<out Extension>, Id>,
        ) {
            try {
                val stream = ExtensionFactory::class.java.getResourceAsStream(INDEX_RESOURCE)
                    ?: throw IllegalStateException(
                        "the extension index (" + INDEX_RESOURCE + ") is not on the classpath; " +
                            "`generateExtensionIndex` writes it into uk/xa0/tulkki/xmpp/models/",
                    )
                stream.use { input ->
                    BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
                        var line: String? = reader.readLine()
                        while (line != null) {
                            if (line.isNotEmpty() && line[0] != '#') {
                                val fields = line.split('\t')
                                if (fields.size != 3) {
                                    throw IllegalStateException(
                                        "malformed extension index line: $line",
                                    )
                                }
                                val clazz: Class<*>
                                try {
                                    // `initialize = false` is load-bearing, not an optimisation. A
                                    // model class's own static initialiser builds itself
                                    // (`Iq.EMPTY = new Iq()`, whose `Extension(Class)` constructor
                                    // calls `ExtensionFactory.id`), so an initialising
                                    // `Class.forName` here would re-enter the loader *while* `Index`
                                    // is still initialising and read the map before it is assigned.
                                    // The generated map this replaces had the same property for
                                    // free: a `Foo.class` literal does not initialise `Foo`.
                                    clazz = Class.forName(
                                        fields[2],
                                        false,
                                        ExtensionFactory::class.java.classLoader,
                                    )
                                } catch (e: ClassNotFoundException) {
                                    throw IllegalStateException(
                                        "the extension index names a class that is not on the " +
                                            "classpath: " + fields[2],
                                        e,
                                    )
                                }
                                if (!Extension::class.java.isAssignableFrom(clazz)) {
                                    throw IllegalStateException(
                                        fields[2] +
                                            " is in the extension index but is not an Extension",
                                    )
                                }
                                @Suppress("UNCHECKED_CAST")
                                val extension = clazz as Class<out Extension>
                                val id = Id(fields[1], fields[0])
                                byId[id] = extension
                                byClass[extension] = id
                            }
                            line = reader.readLine()
                        }
                    }
                }
            } catch (e: IOException) {
                throw IllegalStateException("could not read $INDEX_RESOURCE", e)
            }
        }
    }

    class Id(@JvmField val name: String, @JvmField val namespace: String?) {

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || javaClass != other.javaClass) return false
            val id = other as Id
            return Objects.equal(name, id.name) && Objects.equal(namespace, id.namespace)
        }

        override fun hashCode(): Int = Objects.hashCode(name, namespace)

        override fun toString(): String =
            MoreObjects.toStringHelper(this)
                .add("name", name)
                .add("namespace", namespace)
                .toString()
    }

    /**
     * The index, loaded once on the first [ExtensionFactory] call: one resource read and one
     * `Class.forName` per entry, where the generated map it replaces was a static field of 218
     * `Foo.class` literals with no I/O at all. Two directions out of two maps, because the `BiMap`
     * the processor emitted has no caller that needs a `BiMap`.
     */
    private object Index {

        val BY_ID: Map<Id, Class<out Extension>>
        val BY_CLASS: Map<Class<out Extension>, Id>

        init {
            val byId = HashMap<Id, Class<out Extension>>()
            val byClass = HashMap<Class<out Extension>, Id>()
            readIndex(byId, byClass)
            BY_ID = Collections.unmodifiableMap(byId)
            BY_CLASS = Collections.unmodifiableMap(byClass)
        }
    }
}
