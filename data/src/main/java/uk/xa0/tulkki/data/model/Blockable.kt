package uk.xa0.tulkki.data.model

import uk.xa0.tulkki.libs.Jid

/**
 * Tulkki: 3.7 pair 9, part 14. "A thing that can be blocked", as `:ui`'s block dialogs and the
 * blocklist and conversation lists name it.
 *
 * **The island's `BlockableRef` is gone (2026-10-08, lane `G`).** It existed so
 * `XmppConnectionService.sendBlockRequest`/`sendUnblockRequest` could take one of these without
 * naming `:data`. Those two entry points now take the three values they actually read
 * (`AccountRef?`, `Jid?` and `Jid?`), which is what let the ref be **deleted rather than grown**:
 * its whole member surface was exactly those three reads, and every one of its five callers holds a
 * model object and can hand the parts over itself. This interface therefore declares its own five
 * members and no longer inherits a ref; `Contact`, `Conversation` and `RawBlockable` implement it
 * unchanged, and the three getters keep their exact names and nullabilities. `getJid()` and
 * `getAccount()` are `ListItem`'s own declarations too, and one `override` satisfies both - measured,
 * not assumed: it is why `Contact`'s non-null `getJid(): Jid` and `getAccount(): Account` compile
 * against this interface's nullable spellings.
 *
 * Ported from Java by the `port` stage (port-2). `getAccount()` keeps its covariant `Account`
 * return, which `ListItem` already declares.
 *
 * Nothing here is static or a field, so a Java caller needs no `@JvmStatic`/`@JvmField` and this file
 * adds **zero** interop debt. The five members stay **functions** rather than properties, matching the
 * house style for an interface whose implementors are still Java (`UsageLedger.Store` is the
 * precedent): a Kotlin `val` would generate the same getters, but the JVM names the Java implementors
 * already spell are then only implied rather than written down.
 */
interface Blockable {

    /** Whether this thing is blocked by its account. */
    fun isBlocked(): Boolean

    /** Whether the block is on the whole domain rather than on the JID. */
    fun isDomainBlocked(): Boolean

    fun getBlockedJid(): Jid

    fun getJid(): Jid?

    fun getAccount(): Account?
}
