package uk.xa0.tulkki.xmpp.services

import uk.xa0.tulkki.xmpp.refs.MessageRef

/**
 * Tulkki: the search's continuation, implemented by `:ui`'s `OnSearchResultsAvailable`.
 *
 * Un-nested out of `XmppConnectionService`; converted from the
 * Java. Both lists are **non-null**: `MessageSearchTask.kt:131/134` passes its `private val term:
 * List<String>` and a `result`/`ArrayList()` it always has, and the matched messages are never null.
 *
 * **`@JvmSuppressWildcards` is load-bearing here, and its absence next door is equally
 * deliberate.** The Java declared the **invariant** `List<MessageRef>`; Kotlin emits
 * `List<? extends MessageRef>` for a parameter whose type argument is a non-final interface -
 * measured with kotlinc 2.3.21. `MessageRef` is such a type, so without the annotation the generic
 * signature would differ from the Java's. `List<String>` needs no help: `String` is final, and
 * Kotlin omits the wildcard for a final type argument. `:ui`'s `OnSearchResultsAvailable.kt:23`
 * already carries the same annotation for the same reason, so with it the two declarations agree
 * exactly.
 */
interface SearchResultsHook {

    @JvmSuppressWildcards
    fun onSearchResultsAvailable(term: List<String>, messages: List<MessageRef>)
}
