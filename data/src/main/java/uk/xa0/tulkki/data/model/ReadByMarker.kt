package uk.xa0.tulkki.data.model

import java.util.concurrent.CopyOnWriteArraySet
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import uk.xa0.tulkki.libs.Jid

/**
 * One "displayed" marker: the resource that read a message, and the real JID behind it when the room
 * discloses one.
 *
 * <p>Ported from Java by the `port` stage (port-2). The decisions, recorded rather than inherited:
 *
 * 1. **The constructor takes the two values and the fields are named `fullJidValue`/`realJidValue`.**
 *    Java's constructor was `private` and every factory built an empty object and then assigned the
 *    private fields; a Kotlin `private var fullJid` would generate a private `getFullJid()` beside
 *    the public one. Passing the two values in is the same object with the same public surface
 *    (`getFullJid()`/`getRealJid()` and nothing else), and the constructor was private, so no caller
 *    can see the shape change.
 * 2. **Seven statics are companion `@JvmStatic`s, and that is the file's whole interop debt.** The
 *    Java callers are `DataStaticsHost:458` (`from(Jid, Jid)`), `ConversationFragment:6533`, `:6543`,
 *    `:6553`, `:6581` (`contains`, `from(Message)`, both `allUsersRepresented` overloads),
 *    `IndividualMessage:118`, `:181` and `Message:434` (`fromJsonString`) and `Message:565`
 *    (`toJson(Set)`). **Interop debt: seven annotations.**
 * 3. **[fromJsonString] catches Java's two exception types as two `catch` clauses.** Kotlin has no
 *    multi-catch, so `catch (JSONException | NullPointerException e)` becomes two branches returning
 *    the same empty `CopyOnWriteArraySet`; [fromJson] does the same for
 *    `JSONException | IllegalArgumentException`, where the two branches both leave the field `null`.
 * 4. **`equals` keeps Java's `getClass() != o.getClass()`** as `javaClass != other.javaClass` rather
 *    than Kotlin's `other !is ReadByMarker`, which would accept a subclass instance Java rejects, and
 *    `hashCode` keeps Java's `31 *` arithmetic written out rather than `Objects.hash`, which is a
 *    different number.
 * 5. **The three `CopyOnWriteArraySet`s stay `java.util.concurrent.CopyOnWriteArraySet`.** The public
 *    statics return `Set<ReadByMarker>` whose runtime type is not the file's business, but the
 *    identity Java chose is what `Message` copies and mutates, so the concrete type is kept where
 *    Java constructed it.
 * 6. **The island's ref is gone** (port-13 deleted `ReadByMarkerRef`: the island only ever *built*
 *    one of these and handed it straight back), so nothing in the file is an `override` except the
 *    two `Object` members Java declared.
 * 7. The JSON keys `fullJid`/`realJid` are untouched, so rows written by the Java version still read.

 * The class stays a plain `class`, not a `data class`: Java had null-tolerant identity equality over
 * the same two fields, and a `data class` would add `componentN`/`copy` and a `toString` that prints
 * a real JID - which the island's old ref KDoc and `Message`'s marker handling never asked for.
 */
class ReadByMarker private constructor(
    private val fullJidValue: Jid?,
    private val realJidValue: Jid?,
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false

        val marker = other as ReadByMarker

        if (fullJidValue != null) {
            if (fullJidValue != marker.fullJidValue) return false
        } else if (marker.fullJidValue != null) {
            return false
        }
        return if (realJidValue != null) realJidValue == marker.realJidValue else marker.realJidValue == null
    }

    override fun hashCode(): Int {
        var result = fullJidValue?.hashCode() ?: 0
        result = 31 * result + (realJidValue?.hashCode() ?: 0)
        return result
    }

    fun getFullJid(): Jid? = fullJidValue

    fun getRealJid(): Jid? = realJidValue

    fun toJson(): JSONObject {
        val jsonObject = JSONObject()
        if (fullJidValue != null) {
            try {
                jsonObject.put("fullJid", fullJidValue.toString())
            } catch (e: JSONException) {
                // ignore
            }
        }
        if (realJidValue != null) {
            try {
                jsonObject.put("realJid", realJidValue.toString())
            } catch (e: JSONException) {
                // ignore
            }
        }
        return jsonObject
    }

    companion object {

        fun fromJson(jsonArray: JSONArray): Set<ReadByMarker> {
            val readByMarkers = CopyOnWriteArraySet<ReadByMarker>()
            for (i in 0 until jsonArray.length()) {
                try {
                    readByMarkers.add(fromJson(jsonArray.getJSONObject(i)))
                } catch (e: JSONException) {
                    // ignored
                }
            }
            return readByMarkers
        }

        @JvmStatic
        fun from(fullJid: Jid, realJid: Jid?): ReadByMarker =
            ReadByMarker(fullJid, realJid?.asBareJid())

        @JvmStatic
        fun from(message: Message): ReadByMarker =
            ReadByMarker(message.getCounterpart(), message.getTrueCounterpart())

        fun from(user: MucOptions.User): ReadByMarker =
            ReadByMarker(user.getFullJid(), user.getRealJid())

        fun from(users: Collection<MucOptions.User>): Set<ReadByMarker> {
            val markers = CopyOnWriteArraySet<ReadByMarker>()
            for (user in users) {
                markers.add(from(user))
            }
            return markers
        }

        fun fromJson(jsonObject: JSONObject): ReadByMarker {
            var fullJid: Jid? = null
            try {
                fullJid = Jid.of(jsonObject.getString("fullJid"))
            } catch (e: JSONException) {
                fullJid = null
            } catch (e: IllegalArgumentException) {
                fullJid = null
            }
            var realJid: Jid? = null
            try {
                realJid = Jid.of(jsonObject.getString("realJid"))
            } catch (e: JSONException) {
                realJid = null
            } catch (e: IllegalArgumentException) {
                realJid = null
            }
            return ReadByMarker(fullJid, realJid)
        }

        @JvmStatic
        fun fromJsonString(json: String?): Set<ReadByMarker> {
            try {
                return fromJson(JSONArray(json))
            } catch (e: JSONException) {
                return CopyOnWriteArraySet()
            } catch (e: NullPointerException) {
                return CopyOnWriteArraySet()
            }
        }

        @JvmStatic
        fun toJson(readByMarkers: Set<ReadByMarker>): JSONArray {
            val jsonArray = JSONArray()
            for (marker in readByMarkers) {
                jsonArray.put(marker.toJson())
            }
            return jsonArray
        }

        @JvmStatic
        fun contains(needle: ReadByMarker, readByMarkers: Set<ReadByMarker>): Boolean {
            for (marker in readByMarkers) {
                if (marker.realJidValue != null && needle.realJidValue != null) {
                    if (marker.realJidValue.asBareJid() == needle.realJidValue.asBareJid()) {
                        return true
                    }
                } else if (marker.fullJidValue != null && needle.fullJidValue != null) {
                    if (marker.fullJidValue == needle.fullJidValue) {
                        return true
                    }
                }
            }
            return false
        }

        @JvmStatic
        fun allUsersRepresented(users: Collection<MucOptions.User>, markers: Set<ReadByMarker>): Boolean {
            for (user in users) {
                if (!contains(from(user), markers)) {
                    return false
                }
            }
            return true
        }

        @JvmStatic
        fun allUsersRepresented(
            users: Collection<MucOptions.User>,
            markers: Set<ReadByMarker>,
            marker: ReadByMarker,
        ): Boolean {
            val markersCopy = CopyOnWriteArraySet(markers)
            markersCopy.add(marker)
            return allUsersRepresented(users, markersCopy)
        }
    }
}
