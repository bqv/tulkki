package uk.xa0.tulkki.data.model

import com.google.common.base.Objects
import com.google.common.base.Strings
import com.google.common.collect.ComparisonChain
import uk.xa0.tulkki.libs.Avatarable
import uk.xa0.tulkki.data.utils.DisplayNames
import uk.xa0.tulkki.data.utils.LanguageUtils
import uk.xa0.tulkki.libs.Jid

/**
 * One channel found by a directory search: its address, the name and description the directory
 * published, the language it declared, and how many people are in it.
 *
 * <p>Ported from Java by the `port` stage (port-2). The decisions, recorded rather than inherited:
 *
 * 1. **All five public fields stay Java-visible fields - `@JvmField var`.** `:ui` reads two of them
 *    as *fields*: `ui/.../ui/channel/ChannelDiscoveryScreen.kt:178` (`row.room.address`) and `:192`
 *    (`room.nusers`), as the deleted `ChannelSearchResultAdapter:34`/`:55` once did. The other
 *    three keep the field for Java's own
 *    shape - a Kotlin `var` property would synthesise a setter Java never wrote, where `@JvmField`
 *    generates no accessor at all and the explicit `getName()`/`getDescription()`/`getLanguage()`
 *    below are exactly the three methods Java declared. The fields stay mutable (`var`) as Java's
 *    were, and nullable as Java left them: the no-arg constructor starts them at `null`/`0` and
 *    `Strings.nullToEmpty` guards them. **Interop debt: five `@JvmField`s**, two with a measured
 *    creditor.
 * 2. **The no-arg constructor is a secondary constructor delegating to the primary** with Java's own
 *    defaults (`null, null, null, null, 0`), so `new Room()` and the five-argument form both still
 *    compile from Java.
 * 3. **`getAvatarName()` is the one member that cannot answer Java's own value.** Java returned the
 *    possibly-null `name`; the Kotlin [Avatarable] member's return is non-null, and returning `null`
 *    is not available to it. The null becomes `Strings.nullToEmpty`'s empty string - the same
 *    stand-in this class already uses in [contains] and [compareTo] - and the only caller is an
 *    avatar's content description (`AvatarWorkerTask:132`).
 * 4. **`equals`/`hashCode` keep Guava's `Objects` call for call**, including
 *    `getClass() != o.getClass()` (`javaClass != other.javaClass`) rather than Kotlin's `is`, which
 *    would accept a subclass instance Java rejects, and `Objects.hashCode(Object...)` rather than
 *    Kotlin's `hashCode()`, which is a different number.
 * 5. **[getRoom] keeps Java's `try`/`catch`**: a malformed address is not a room, and the catch is
 *    the `null` return, not a Kotlin `runCatching` that would also swallow anything else. `Jid.of`
 *    is Kotlin now, so the nullable `address` is rejected at the hand-over
 *    (`address ?: throw NullPointerException("address")`): Java's `Jid.of(null)` reached
 *    `JidCreate.from`'s `input.toString()` and threw `NullPointerException`, which this catch does
 *    not take, so a null address still propagates rather than becoming a `null` room.
 * 6. **[getLanguage] keeps `LanguageUtils.convert`'s two-way answer** - `null` in, `null` out - so
 *    its Kotlin return is `String?`, as the Java annotation-free signature already meant.
 *
 * No member is static, so this file adds no `@JvmStatic`. Nothing in the tree extends `Room` -
 * the deleted `ChannelSearchResultAdapter` only parameterised `ListAdapter` with it - so Kotlin's
 * implicit `final` is Java's own shape.
 */
class Room(
    @JvmField var address: String?,
    @JvmField var name: String?,
    @JvmField var description: String?,
    @JvmField var language: String?,
    @JvmField var nusers: Int,
) : Avatarable, Comparable<Room> {

    constructor() : this(null, null, null, null, 0)

    fun getName(): String? = name

    fun getDescription(): String? = description

    fun getRoom(): Jid? =
        try {
            Jid.of(address ?: throw NullPointerException("address"))
        } catch (e: IllegalArgumentException) {
            null
        }

    fun getLanguage(): String? = LanguageUtils.convert(language)

    override fun getAvatarBackgroundColor(): Int {
        val room = getRoom()
        return DisplayNames.getColorForName(if (room != null) room.asBareJid().toString() else name)
    }

    override fun getAvatarName(): String = Strings.nullToEmpty(name)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false

        val room = other as Room

        return Objects.equal(address, room.address) &&
            Objects.equal(name, room.name) &&
            Objects.equal(description, room.description)
    }

    override fun hashCode(): Int = Objects.hashCode(address, name, description)

    fun contains(needle: String): Boolean =
        Strings.nullToEmpty(name).contains(needle) ||
            Strings.nullToEmpty(description).contains(needle) ||
            Strings.nullToEmpty(address).contains(needle)

    override fun compareTo(other: Room): Int =
        ComparisonChain.start()
            .compare(other.nusers, nusers)
            .compare(Strings.nullToEmpty(name), Strings.nullToEmpty(other.name))
            .compare(Strings.nullToEmpty(address), Strings.nullToEmpty(other.address))
            .result()
}
