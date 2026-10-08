package uk.xa0.tulkki.libs

/**
 * Tulkki: the XMPP island's view of `uk.xa0.tulkki.data.AppSettings`.
 *
 * Declared in the island and implemented by `uk.xa0.tulkki.data.AppSettings`, so an island file can
 * read the owner's settings without naming a non-island module (`docs/WORKSTREAMS.md` round 151).
 *
 * `AppSettings` is **both** a static utility and an instance class, and the brief's §2.3 prices only
 * the first: the island builds one with a `Context` at six sites and calls instance methods on it.
 * Those are this ref. The statics - the preference keys, `SECURE_DOMAINS` and
 * `clearSessionPassword()` - are on `XmppConnectionService.DataStatics` instead, because an
 * interface cannot carry a static method and a static entry point has no instance to hang off.
 *
 * **Why `:libs` (2026-10-08, lane `G`).** It was `uk.xa0.tulkki.xmpp.refs.AppSettingsRef` - the
 * island's name for `:data`'s `AppSettings` while `:xmpp` could not name `:data`. `:libs` is the one
 * module in the order that every side may reach, so the interface moves here and the ref file is
 * its `git rm`; the move is **package-only** and every namer changes only its import line. **Only
 * the interface moves**: the `:data` class and everything behind it stay put, so nothing is widened.
 *
 * Its whole member surface is JDK, `:libs`, or another interface that moved with it, which is what
 * makes the move legal: `:libs` may name nothing (`allow = []`).
 *
 * **Respell from Java (2026-10-08, lane `G`).** Twelve members, every one a primitive, `long` or
 * `String`; `AppSettings`'s own overrides declare all twelve non-null (`AppSettings.kt:25`-`:71`),
 * so the Kotlin spellings are `fun isX(): Boolean`, `fun getInstallationId(): Long`,
 * `fun resetInstallationId()` and `fun getCustomResourceName(): String`. The JVM surface is
 * identical: `javap -p` prints the same twelve descriptors, and the Kotlin function spellings keep
 * the `getX`/`isX` names, so no caller loses the method it calls. Nothing here was read as a
 * synthetic property - measured, the tree's two Kotlin call sites already write
 * `appSettings.preferIPv6()` (`Resolver.kt:108`) and `appSettings.isDANEnforced()`
 * (`XmppConnection.kt:2691`).
 */
interface AppSettingsRef {

    fun isDeleteUnusedFiles(): Boolean

    fun isTrustSystemCAStore(): Boolean

    fun isUseTor(): Boolean

    fun isExtendedConnectionOptions(): Boolean

    fun getInstallationId(): Long

    fun resetInstallationId()

    fun isRequireChannelBinding(): Boolean

    fun isRequireTlsV13(): Boolean

    fun isDANEnforced(): Boolean

    fun isUseRelays(): Boolean

    fun preferIPv6(): Boolean

    fun getCustomResourceName(): String
}
