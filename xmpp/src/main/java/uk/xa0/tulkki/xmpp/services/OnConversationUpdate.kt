package uk.xa0.tulkki.xmpp.services

/**
 * Tulkki: the conversation list's refresh trigger, un-nested out of `XmppConnectionService`
 *.
 *
 * Converted from the Java. **No parameter, and therefore no nullability decision** - which is why
 * the two `default` bodies are the whole of this file's question, and they are answered by
 * measurement rather than by a flag:
 *
 * Kotlin 2.3.21, with no `-jvm-default` / `-Xjvm-default` setting anywhere in the tree, already
 * emits an interface member with a body as a **real JVM `default` method**
 * (`javap -v`: `flags: ACC_PUBLIC`, a `Code` attribute, no `ACC_ABSTRACT`) *and* a `DefaultImpls`
 * for binary compatibility. `-Xjvm-default=all` would change exactly one thing - it deletes that
 * `DefaultImpls` (and the `access$…$jd` synthetics); it does not touch the dispatch shape. Measured
 * with kotlinc 2.3.21 on this exact pair of methods, plus a `javac`/`java` round trip: a **Java**
 * class overriding only `onConversationUpdate(boolean)` compiles against the Kotlin interface and
 * `onConversationUpdate()` dispatches through the Kotlin default into the Java override - the
 * Java `default` shape, byte for byte. `git grep DefaultImpls` across the tree: 0 hits, so the
 * compatibility class the flag would remove is referenced nowhere. The measurement is clean, and
 * the flag is also unnecessary: **this file converts with no build-wide flag.**
 *
 * `:ui`'s `ConversationFragment.java:3565` is that Java overrider; the mutual recursion between the
 * two defaults is the Java's own and is preserved as written (the Java would not terminate either
 * if a class overrode neither).
 */
interface OnConversationUpdate {
    fun onConversationUpdate() {
        onConversationUpdate(false)
    }

    fun onConversationUpdate(newCaps: Boolean) {
        onConversationUpdate()
    }
}
