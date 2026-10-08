package uk.xa0.tulkki.data.model

import java.util.Locale
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.libs.PresenceRef
import uk.xa0.tulkki.libs.ServiceDiscoveryResultRef

/**
 * One resource's presence: its [Status], the capabilities node/hash/version it advertised, its
 * status message, and the disco result fetched for those caps.
 *
 * <p>Ported from Java by the `port` stage (port-2). The decisions, recorded rather than inherited:
 *
 * 1. **Six members are `override`s because [PresenceRef] declares them** - [hasCaps],
 *    [getServiceDiscoveryResult], [getHash], [getVer], [getNode] and [setServiceDiscoveryResult] -
 *    plus `Comparable`'s [compareTo]. Java carried `@Override` on one of them. The ref's KDoc is why
 *    the status enum is *not* one of them: `PresenceParser` compares
 *    `contact.getShownStatus() == Presence.Status.OFFLINE` by identity, so the island keeps its own
 *    [PresenceRef.StatusRef] and the two enums meet in [Status.toRef]/[Status.fromRef] instead.
 * 2. **Three statics are `@JvmStatic`s, and that is the file's whole interop debt.** The Java callers
 *    are `DataStaticsHost:342` ([parse]), `Account:347` and `PresenceTemplate:53`
 *    (`Status.fromShowString`), `Account:700` and `PresenceStatusRefTest:26`, `:33`
 *    (`Status.fromRef`). `Status.values()`/`valueOf` are the enum's own static members and need
 *    nothing. **Interop debt: three `@JvmStatic`s.**
 * 3. **All six fields stay private and keep Java's names.** A private Kotlin `val`/`var` generates
 *    the backing field and no accessor (measured with `javap`), so `status`/`ver`/`hash`/`node`/
 *    `message` cannot collide with the explicit `getStatus()`/`getVer()`/`getHash()`/`getNode()`/
 *    `getMessage()`. No Java caller reads them as fields, so no `@JvmField` has a creditor.
 * 4. **[getServiceDiscoveryResult] answers `ServiceDiscoveryResult?`.** Java's field starts `null`
 *    and the setter is the only writer; every caller already null-checks it (`Presences.kt:139`,
 *    `:152`, `:166`, `:182`, `:199`, `Account:942`, `ContactDetailsActivity:941`). The Kotlin return
 *    states the contract Java hid behind a platform type.
 * 5. **[setServiceDiscoveryResult] is an overload pair.** [PresenceRef]'s member takes the *interface*,
 *    and a parameter type is not covariant, so the model-typed overload is Java's own declaration and
 *    the override casts once: `discoRef as ServiceDiscoveryResult?` is Java's
 *    `(ServiceDiscoveryResult) discoRef` exactly - `null` in, `null` stored, a wrong type a
 *    `ClassCastException`.
 * 6. **[parse] keeps Java's null test** as `caps?.getAttribute(...)`, which is the same single
 *    null check Java wrote three times, and the four nullable parameters stay nullable: `hasCaps()`
 *    is the proof that Java left `ver`/`hash`/`node` null, and `DataStaticsHost:353` passes `null`
 *    for all three.
 * 7. **The enum keeps Java's behaviour at every branch.** [Status.toShowString] answers `null` for
 *    `ONLINE`/`OFFLINE` (Java's fall-through) and [Status.fromShowString] folds a `null` or unknown
 *    `show` to `ONLINE`; the fold is `lowercase(Locale.US)`, which delegates to Java's
 *    `String.toLowerCase(Locale)` exactly, so the locale-sensitive trap does not apply. [Status.fromRef]
 *    takes a non-null ref because Java's `switch (ref)` throws on `null`, and both exhaustive `when`s
 *    keep Java's `default` throw for the constant that cannot be unmapped.
 *
 * Nothing in the tree extends `Presence` - it is a container, not a base - so Kotlin's implicit
 * `final` is Java's own shape.
 */
class Presence(
    private val status: Status,
    private val ver: String?,
    private val hash: String?,
    private val node: String?,
    private val message: String?,
) : Comparable<Presence>, PresenceRef {

    private var disco: ServiceDiscoveryResult? = null

    override fun compareTo(other: Presence): Int = status.compareTo(other.status)

    fun getStatus(): Status = status

    override fun hasCaps(): Boolean = ver != null && hash != null

    override fun getVer(): String? = ver

    override fun getNode(): String? = node

    override fun getHash(): String? = hash

    fun getMessage(): String? = message

    fun setServiceDiscoveryResult(disco: ServiceDiscoveryResult) {
        this.disco = disco
    }

    override fun setServiceDiscoveryResult(discoRef: ServiceDiscoveryResultRef?) {
        this.disco = discoRef as ServiceDiscoveryResult?
    }

    override fun getServiceDiscoveryResult(): ServiceDiscoveryResult? = disco

    enum class Status {
        CHAT,
        ONLINE,
        AWAY,
        XA,
        DND,
        OFFLINE;

        fun toShowString(): String? =
            when (this) {
                CHAT -> "chat"
                AWAY -> "away"
                XA -> "xa"
                DND -> "dnd"
                else -> null
            }

        /**
         * Tulkki: 3.7 C5-D - the exhaustive mapper to the island's [PresenceRef.StatusRef], beside
         * the one in `Contact.shownStatus()` and for the same reason: an island member cannot be a
         * same-named override of this one (a class may not have two methods differing only in return
         * type), so the island accessor is distinctly named and the enums meet here.
         * `PresenceStatusRefTest` pins the round trip and `PresenceRef.StatusRef.toShowString()`.
         */
        fun toRef(): PresenceRef.StatusRef =
            when (this) {
                CHAT -> PresenceRef.StatusRef.CHAT
                ONLINE -> PresenceRef.StatusRef.ONLINE
                AWAY -> PresenceRef.StatusRef.AWAY
                XA -> PresenceRef.StatusRef.XA
                DND -> PresenceRef.StatusRef.DND
                OFFLINE -> PresenceRef.StatusRef.OFFLINE
                else -> throw IllegalStateException("unmapped Presence.Status: " + this)
            }

        companion object {

            @JvmStatic
            fun fromShowString(show: String?): Status {
                if (show == null) {
                    return ONLINE
                }
                return when (show.lowercase(Locale.US)) {
                    "away" -> AWAY
                    "xa" -> XA
                    "dnd" -> DND
                    "chat" -> CHAT
                    else -> ONLINE
                }
            }

            /** Tulkki: the other half of [toRef], exhaustive in the same way. */
            @JvmStatic
            fun fromRef(ref: PresenceRef.StatusRef): Status =
                when (ref) {
                    PresenceRef.StatusRef.CHAT -> CHAT
                    PresenceRef.StatusRef.ONLINE -> ONLINE
                    PresenceRef.StatusRef.AWAY -> AWAY
                    PresenceRef.StatusRef.XA -> XA
                    PresenceRef.StatusRef.DND -> DND
                    PresenceRef.StatusRef.OFFLINE -> OFFLINE
                    else -> throw IllegalStateException("unmapped PresenceRef.StatusRef: " + ref)
                }
        }
    }

    companion object {

        @JvmStatic
        fun parse(show: String?, caps: Element?, message: String?): Presence {
            val hash = caps?.getAttribute("hash")
            val ver = caps?.getAttribute("ver")
            val node = caps?.getAttribute("node")
            return Presence(Status.fromShowString(show), ver, hash, node, message)
        }
    }
}
