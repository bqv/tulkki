package uk.xa0.tulkki.xmpp.services

/**
 * Tulkki: the checked refusal `saveCid` raises for blocked media, un-nested out of
 * `XmppConnectionService` and converted from the Java.
 *
 * The Java declared `public class BlockedMediaException extends Exception { }`, so the Kotlin says
 * `open` rather than accepting Kotlin's final default - the Java's own modifier, written out. No
 * file extends it today; `data/FileBackend.kt` catches it by name at six sites, `BobTransfer.kt`
 * and `HttpDownloadConnection.kt` at two more, and `xmpp/refs/FileBackendRef.java:106` declares it
 * in a `throws` clause, so the name and the type identity are the whole contract. Kotlin has no
 * checked exceptions, but the Java declarations that carry the clause are unchanged: `@Throws` is
 * only needed where *Kotlin* declares a member Java catches by name, and this class declares none.
 */
open class BlockedMediaException : Exception()
