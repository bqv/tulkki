package uk.xa0.tulkki.data.model

import android.util.Pair
import java.util.ArrayList
import java.util.HashMap
import java.util.Hashtable
import java.util.regex.Pattern
import uk.xa0.tulkki.libs.PresencesRef

/**
 * The presence set a [Contact] holds: one [Presence] per resource, keyed by resource string.
 *
 * <p>Ported from Java by the `port` stage (port-2). The decisions, recorded rather than inherited:
 *
 * 1. **Nine members are `override`s because [PresencesRef] declares them.** Kotlin's `override` is
 *    mandatory where Java's `@Override` was advisory; the other ten members are the model's own and
 *    stay plain. The ref's two `List<? extends X>`/`Map<String, ? extends PresenceRef>` returns are
 *    satisfied without a body change: Kotlin's `List`/`Map` are covariant in their element type, so
 *    `List<Presence>`/`Map<String, Presence>` are subtypes of the wildcard types - the same reason
 *    the Java model paid nothing.
 * 2. **[get] returns `Presence?`, and that is the ref's own contract.** `Hashtable.get` answers
 *    `null` for a resource with no presence, and the island's one caller null-checks it
 *    (`AbstractJingleConnection:340-343`: `presence == null ? null : ...`). Java's declaration hid
 *    that behind a platform type; the Kotlin return type states it.
 * 3. **`getMessage().trim()` keeps Java's `trim()`, through a private helper, at both sites.**
 *    Kotlin's `trim()` removes by `Char.isWhitespace`, which also matches `isSpaceChar`, where Java's
 *    removes every char `<= ' '`; the two disagree on the non-breaking-space family, and a status
 *    message is arbitrary user text. The helper is the same one `:translation` declares per file
 *    (`private fun String.javaTrim(): String = trim { it <= ' ' }`), redeclared here because `:data`
 *    may not name `:translation`.
 * 4. **`name.split(" ")` becomes `Pattern.compile(" ").split(name, 0)`, because *neither* Kotlin
 *    `split` overload is Java's `String.split(String)`.** Java's is
 *    `Pattern.compile(regex).split(input, 0)`: a regex split that **drops trailing empty fields**.
 *    Kotlin's single-`String` overload is a **literal** split (delimiters are not patterns) and, with
 *    `limit = 0`, keeps them; and `Regex.split(input, 0)` keeps them too. Measured with a JVM probe
 *    against kotlin-stdlib 2.3.21: `"a:".split(":")` is `[a]` in Java but `[a, ""]` through
 *    `new Regex(":").split("a:", 0)`; `"Foo 1.2 ".split(" ")` is `[Foo, 1.2]` in Java but
 *    `[Foo, 1.2, ""]` through either Kotlin form. That last case is this file's input - a gateway
 *    name ending in a space - and `parts[parts.size - 1][0]` below would throw
 *    `StringIndexOutOfBoundsException` where Java answered the name unchanged. An earlier draft of
 *    this port wrote `" ".toRegex()`, which fixes the delimiter but not the trailing field; the probe
 *    is why it did not stand. `Pattern.compile(...).split(input, 0)` *is* Java's `split`, call for
 *    call.
 * 5. **The two private fields keep Java's names.** `javap` on a compiled private Kotlin property
 *    (`SecurePasswordStorage.appContext`, `TransferablePlaceholder.statusValue`) shows the field and
 *    **no** getter, so `private val presences`/`statusMessages` cannot collide with the explicit
 *    `getPresences()`/`getStatusMessages()` - the rename the row uses elsewhere is defensive, not
 *    required, and faithfulness wins where the clash cannot happen.
 * 6. **`keySet().toArray(String[])` becomes `keys.toTypedArray()`.** The two-step fill needs a
 *    pre-sized array whose element type Kotlin cannot name without a nullability cast; the same keys
 *    are copied in the same iteration order into a `String[]`, and the JVM signature is unchanged.
 * 7. **`android.util.Pair` is imported explicitly**, which shadows Kotlin's own `kotlin.Pair` - Java
 *    returned the Android pair and `:ui`'s callers consume `.first`/`.second` as Java fields, which a
 *    Kotlin `Pair` would answer with `getFirst()`/`getSecond()`.
 * 8. **`updateStatusMessages` keeps the null tolerance**: Java's `if (newMessages != null)` is
 *    `List<String>?` and one branch, not a Kotlin non-null claim.

 * Nothing here is static and no Java caller reads a field as a field, so the file adds **zero**
 * interop debt.
 */
class Presences : PresencesRef {

    private val presences = Hashtable<String, Presence>()

    private val statusMessages = ArrayList<String>()

    override fun getPresences(): List<Presence> =
        synchronized(presences) { ArrayList(presences.values) }

    override fun getPresencesMap(): Map<String, Presence> =
        synchronized(presences) { HashMap(presences) }

    override fun get(resource: String): Presence? = synchronized(presences) { presences[resource] }

    fun updatePresence(resource: String, presence: Presence) {
        synchronized(presences) { presences[resource] = presence }
    }

    fun removePresence(resource: String) {
        synchronized(presences) { presences.remove(resource) }
    }

    fun clearPresences() {
        synchronized(presences) { presences.clear() }
    }

    fun getShownStatus(): Presence.Status {
        var status = Presence.Status.OFFLINE
        synchronized(presences) {
            for (p in presences.values) {
                if (p.getStatus() == Presence.Status.DND) {
                    return p.getStatus()
                } else if (p.getStatus().compareTo(status) < 0) {
                    status = p.getStatus()
                }
            }
        }
        return status
    }

    override fun size(): Int = synchronized(presences) { presences.size }

    override fun isEmpty(): Boolean = synchronized(presences) { presences.isEmpty() }

    override fun toResourceArray(): Array<String> =
        synchronized(presences) { presences.keys.toTypedArray() }

    override fun asTemplates(): List<PresenceTemplate> =
        synchronized(presences) {
            val templates = ArrayList<PresenceTemplate>(presences.size)
            for (p in presences.values) {
                val message = p.getMessage()
                if (message != null && !message.javaTrim().isEmpty()) {
                    templates.add(PresenceTemplate(p.getStatus(), message))
                }
            }
            templates
        }

    fun has(presence: String): Boolean = synchronized(presences) { presences.containsKey(presence) }

    fun getStatusMessages(): List<String> {
        val messages = ArrayList<String>()
        synchronized(presences) {
            for (presence in presences.values) {
                val raw = presence.getMessage()
                val message = if (raw == null) null else raw.javaTrim()
                if (message != null && message.isNotEmpty() && !messages.contains(message)) {
                    messages.add(message)
                }
            }
        }
        return messages
    }

    fun allOrNonSupport(namespace: String): Boolean {
        synchronized(presences) {
            for (presence in presences.values) {
                val disco = presence.getServiceDiscoveryResult()
                if (disco == null || !disco.getFeatures().contains(namespace)) {
                    return false
                }
            }
        }
        return true
    }

    override fun anySupport(namespace: String): Boolean {
        synchronized(presences) {
            if (presences.size == 0) {
                return true
            }
            for (presence in presences.values) {
                val disco = presence.getServiceDiscoveryResult()
                if (disco != null && disco.getFeatures().contains(namespace)) {
                    return true
                }
            }
        }
        return false
    }

    fun firstWhichSupport(namespace: String): String? {
        synchronized(presences) {
            for (entry in presences.entries) {
                val resource = entry.key
                val presence = entry.value
                val disco = presence.getServiceDiscoveryResult()
                if (disco != null && disco.getFeatures().contains(namespace)) {
                    return resource
                }
            }
        }
        return null
    }

    /**
     * Tulkki: FIXED - both parameters are nullable again, because Java's were bare
     * (`checkpoint-pre-kotlin`'s `Presences.java:172`:
     * `public boolean anyIdentity(final String category, final String type)`) and `:ui` calls it
     * with a literal `null` type. `EnterJidDialog.populateGateways` - reached by every
     * `onCreateDialog` for a roster contact with a presence - asks
     * `contact.getPresences().anyIdentity("gateway", null)` at `:226` and `:228`, and the non-null
     * parameters threw at Kotlin's parameter check before the body could run. A null `type` means
     * "any type", which is exactly what the delegated
     * [uk.xa0.tulkki.data.model.ServiceDiscoveryResult.hasIdentity] already spells out
     * (`type == null || ...` at `ServiceDiscoveryResult.kt:296`); the two halves of the same call now
     * agree. The PSTN callers keep passing a real type and are unchanged.
     */
    override fun anyIdentity(category: String?, type: String?): Boolean {
        synchronized(presences) {
            if (presences.size == 0) {
                // https://github.com/iNPUTmice/Conversations/issues/4230
                return false
            }
            for (presence in presences.values) {
                val disco = presence.getServiceDiscoveryResult()
                if (disco != null && disco.hasIdentity(category, type)) {
                    return true
                }
            }
        }
        return false
    }

    fun toTypeAndNameMap(): Pair<Map<String, String>, Map<String, String>> {
        val typeMap = HashMap<String, String>()
        val nameMap = HashMap<String, String>()
        synchronized(presences) {
            for (presenceEntry in presences.entries) {
                val resource = presenceEntry.key
                val presence = presenceEntry.value
                val serviceDiscoveryResult =
                    if (presence == null) null else presence.getServiceDiscoveryResult()
                if (serviceDiscoveryResult != null && serviceDiscoveryResult.getIdentities().size > 0) {
                    val identity = serviceDiscoveryResult.getIdentities()[0]
                    val type = identity.getType()
                    val name = identity.getName()
                    if (type != null) {
                        typeMap[resource] = type
                    }
                    if (name != null) {
                        nameMap[resource] = nameWithoutVersion(name)
                    }
                }
            }
        }
        return Pair(typeMap, nameMap)
    }

    fun updateStatusMessages(newMessages: List<String>?) {
        statusMessages.clear()
        if (newMessages != null) {
            statusMessages.addAll(newMessages)
        }
    }

    companion object {

        private fun nameWithoutVersion(name: String): String {
            val parts = Pattern.compile(" ").split(name, 0)
            return if (parts.size > 1 && Character.isDigit(parts[parts.size - 1][0])) {
                val output = StringBuilder()
                for (i in 0 until parts.size - 1) {
                    if (output.length != 0) {
                        output.append(' ')
                    }
                    output.append(parts[i])
                }
                output.toString()
            } else {
                name
            }
        }
    }
}

/** Java's `String.trim()`: every char `<= ' '`, not Kotlin's whitespace set. */
private fun String.javaTrim(): String = trim { it <= ' ' }
