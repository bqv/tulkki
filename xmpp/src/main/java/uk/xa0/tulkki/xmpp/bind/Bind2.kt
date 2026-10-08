package uk.xa0.tulkki.xmpp.bind

import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace

/**
 * XEP-0386 Bind 2: the advertised quick-start features and the `inline` feature set.
 *
 * Ported from Java by the port-14 `xmppport2` lane. Decisions taken rather than inherited:
 *
 * 1. **The class keeps its implicit public constructor** (Java declared none), so `new Bind2()` still
 *    compiles; the statics are `@JvmStatic` (`XmppConnection` calls `Bind2.features(inline)`).
 * 2. **`QUICKSTART_FEATURES` stays a Java-visible static field** (`@JvmField val`, type
 *    `Collection<String>`), because `XmppConnection:1773/1887/2658` reads it as a field.
 * 3. **`features` rewrites Guava's chain as Kotlin's**: Java composed
 *    `Collections2.filter(Collections2.transform(Collections2.filter(children, …), …),
 *    Predicates.notNull())`, a lazy view, and Kotlin answers an eager `List<String>` instead. The
 *    contents are identical because `Element.getChildren()` already answers an `ImmutableList`
 *    snapshot, so the view had nothing left to observe. The null return (`inlineBind2 == null`) and
 *    the empty-list return (`inlineBind2Inline == null`) are unchanged.
 * 4. `inline` is nullable, as Java's first test required; `findChild` answers nullable.
 */
class Bind2 {

    companion object {

        @JvmField
        val QUICKSTART_FEATURES: Collection<String> =
            listOf(
                Namespace.CARBONS,
                Namespace.STREAM_MANAGEMENT,
            )

        @JvmStatic
        fun features(inline: Element?): Collection<String>? {
            val inlineBind2 = inline?.findChild("bind", Namespace.BIND2) ?: return null
            val inlineBind2Inline =
                inlineBind2.findChild("inline", Namespace.BIND2) ?: return emptyList()
            return inlineBind2Inline.getChildren()
                .filter { "feature" == it.getName() }
                .map { it.getAttribute("var") }
                .filterNotNull()
        }
    }
}
