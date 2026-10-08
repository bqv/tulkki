package uk.xa0.tulkki.data.model

import android.util.Log
import com.google.common.base.MoreObjects
import com.google.common.base.Strings
import com.google.common.collect.Collections2
import com.google.common.collect.ImmutableSet
import com.google.common.collect.Multimaps
import com.google.common.collect.Ordering
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.google.gson.TypeAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import io.ipfs.cid.Cid
import java.io.IOException
import java.util.Arrays
import java.util.Collections
import java.util.Comparator
import java.util.function.Function
import uk.xa0.tulkki.data.utils.EmoticonText
import uk.xa0.tulkki.data.utils.GetThumbnailForCid
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.libs.ReactionRef

/**
 * One reaction: the emoji text or the custom-emoji CID that arrived, who left it, and the envelope
 * it answered. [ReactionRef] is the island's name for the class, so [reaction], [from] and
 * [occupantId] carry the three one-line bodies an interface cannot.
 *
 * <p>Ported from Java by the `port` stage (port-2). The decisions, recorded rather than inherited:
 *
 * 1. **Five of the seven public fields stay Java-visible fields - `@JvmField` - and the other two
 *    take Kotlin's getter.** `:ui` and `:data` read `reaction.received`, `.from`, `.trueJid`,
 *    `.occupantId` and `.envelopeId` as *fields*: `BindingAdapters:91` (`!r.received`),
 *    `MucOptions:455-462` and `DisplayNames:139-155` (`trueJid`/`from`/`occupantId`),
 *    `MessageAdapter:3585-3592` (`envelopeId`) and `MessageAdapter:4448` (`from`). Kotlin allows a
 *    property and a same-named function - a measured fact, not an assumption, and `from`/`from()`,
 *    `occupantId`/`occupantId()` and `reaction`/`reaction()` are exactly that pair - so those five
 *    keep Java's field and [ReactionRef]'s member side by side. **`reaction` and `cid` are read as
 *    fields by no Java caller** (measured: the only `r.reaction`/`r.cid` sites in Java are this
 *    file's own), so they stay plain `val`s and generate `getReaction()`/`getCid()`; the two new
 *    getters break nothing, and Gson is unaffected because it binds *fields*, not accessors.
 *    **Interop debt: five `@JvmField`s here**, plus the two [Aggregated]'s fields carry -
 *    `aggregated.reactions` is read as a field at `BindingAdapters:66` and `aggregated.ourReactions`
 *    at `BindingAdapters:116`, `:125`, `AddReactionActivity:48-52`, `ConversationFragment:1480`,
 *    `:3484`, `:3516-3521`, `XmppActivity:512-517` and `Message:2225`.
 * 2. **`SUGGESTIONS` is a companion `@JvmField`.** `XmppActivity:502` iterates
 *    `Reaction.SUGGESTIONS`, and `@JvmField` on a companion `val` is the `public static final List`
 *    Java declared. The initialiser stays `Arrays.asList(...)`, so the constant is the same
 *    fixed-size `java.util.Arrays$ArrayList`. **Debt: one more `@JvmField`.**
 * 3. **Six statics are companion `@JvmStatic`s.** The Java callers, measured: `Message:573`
 *    (`toString(Collection)`), `IndividualMessage:123`, `:186` and `Message:439` (`fromString`),
 *    `DataStaticsHost:511` (`withOccupantId`), `DataStaticsHost:529` (`withFrom`),
 *    `Conversation:948` (`aggregated(Collection, Function)`) and `Message:1329`
 *    (`aggregated(Collection)`). `withMine` has **no caller at all** in the tree, so it carries no
 *    annotation and no debt - the same reading `ReadByMarker` took of its four uncalled statics.
 *    **Interop debt: six `@JvmStatic`s.** Java's one-arg [aggregated] delegates with `(r) -> null`,
 *    which Kotlin cannot write: `java.util.function.Function.apply` is *non-null* to Kotlin (measured:
 *    a nullable override is rejected for `Function`, where a hand-written Java interface's is
 *    accepted), so both overloads call a private body that takes the thumbnailer as nullable and
 *    dereferences it in exactly the branch where Java dereferenced the lambda's null result.
 * 4. **The two Java predicates written as `x.equals(y)` keep Java's dereference.** `withOccupantId`
 *    filters on `!occupantId.equals(e.occupantId)` and `withFrom` on
 *    `!from.asBareJid().equals(e.from.asBareJid())`; both dereference a nullable parameter (and
 *    `withFrom` a nullable field), which Kotlin's null-safe `!=` would answer instead of throwing.
 *    Each dereference is the explicit `?: throw NullPointerException()` - this row's `DownloadableFile`
 *    shape - at the three sites Java dereferenced, so the trigger and the thrown type are unchanged.
 * 5. **`normalizedReaction()` declares a non-null `String`.** `EmoticonText.normalizeToVS16` is Java,
 *    so Kotlin inserts its null check on the platform return; Java could hand back `null` for a null
 *    `reaction`, but its only caller - [aggregated], via `Reaction::normalizedReaction` - feeds the
 *    result to `ImmutableSet.copyOf`, which refuses a null element with the same
 *    `NullPointerException`. Only the frame that throws moves.
 * 6. **`fromString` keeps Java's two exception branches as two `catch` clauses.** Kotlin has no
 *    multi-catch, so `catch (IllegalArgumentException | JsonSyntaxException e)` becomes two branches
 *    with the same `Log.e` and the same empty list.
 * 7. **The two private `TypeAdapter`s drop Java's `throws IOException`.** Kotlin has no checked
 *    exceptions; no Java caller invokes them (they are private, and Gson reaches them through the
 *    base type, whose own declaration still carries `throws IOException`). Java's parameter named
 *    `in` is renamed `input` - a Kotlin keyword, and a parameter name is not part of the JVM
 *    signature.
 * 8. **`Aggregated`'s constructor is `internal`, not Java's `private`.** Measured with Kotlin
 *    2.3.21: a nested class's `private constructor` is unreachable from the outer class's companion,
 *    so `Reaction.aggregated` could not build one. Java's constructor was private because
 *    [aggregated] is the only builder, and `internal` keeps that module-private; `javap` shows the
 *    JVM constructor public because Kotlin cannot mangle constructor names, and no Java code
 *    constructs one.
 * 9. **`equals`/`hashCode` keep `toString()` as their subject**, i.e. the `MoreObjects` rendering in
 *    Java's field order, and the class stays a plain `class`, final - `grep` finds no
 *    `extends Reaction` anywhere in the tree, so Java's implicit openness was never used.
 * 10. **The properties follow Java's *constructor* order, `cid` second.** Java's field declaration
 *    order differs (`reaction, received, from, trueJid, occupantId, cid, envelopeId`), and the
 *    constructor order is load-bearing for `Conversation:908`/`:930`'s positional `new Reaction(...)`;
 *    Gson's reflective field order therefore changes while its keys and values do not, and nothing in
 *    the tree compares two serialised reaction strings.
 */
class Reaction(
    val reaction: String?,
    val cid: Cid?,
    @JvmField val received: Boolean,
    @JvmField val from: Jid?,
    @JvmField val trueJid: Jid?,
    @JvmField val occupantId: String?,
    @JvmField val envelopeId: String?,
) : ReactionRef {

    override fun reaction(): String? = reaction

    override fun from(): Jid? = from

    override fun occupantId(): String? = occupantId

    fun normalizedReaction(): String = EmoticonText.normalizeToVS16(reaction)

    override fun toString(): String =
        MoreObjects.toStringHelper(this)
            .add("reaction", if (cid == null) reaction else null)
            .add("cid", cid)
            .add("received", received)
            .add("from", from)
            .add("trueJid", trueJid)
            .add("occupantId", occupantId)
            .toString()

    override fun hashCode(): Int = toString().hashCode()

    override fun equals(other: Any?): Boolean {
        if (other == null) return false
        if (other !is Reaction) return false
        return toString() == other.toString()
    }

    /** One emoji and the reactions under it, most-reacted first; `ourReactions` are the sent ones. */
    class Aggregated internal constructor(
        @JvmField val reactions: List<Map.Entry<Emoji, Collection<Reaction>>>,
        @JvmField val ourReactions: Set<String>,
    )

    private class JidTypeAdapter : TypeAdapter<Jid>() {

        override fun write(out: JsonWriter, value: Jid?) {
            if (value == null) {
                out.nullValue()
            } else {
                out.value(value.toString())
            }
        }

        override fun read(input: JsonReader): Jid? {
            if (input.peek() == JsonToken.NULL) {
                input.nextNull()
                return null
            } else if (input.peek() == JsonToken.STRING) {
                val value = input.nextString()
                return Jid.of(value)
            }
            throw IOException("Unexpected token")
        }
    }

    private class CidTypeAdapter : TypeAdapter<Cid>() {

        override fun write(out: JsonWriter, value: Cid?) {
            if (value == null) {
                out.nullValue()
            } else {
                out.value(value.toString())
            }
        }

        override fun read(input: JsonReader): Cid? {
            if (input.peek() == JsonToken.NULL) {
                input.nextNull()
                return null
            } else if (input.peek() == JsonToken.STRING) {
                val value = input.nextString()
                return Cid.decode(value)
            }
            throw IOException("Unexpected token")
        }
    }

    companion object {

        @JvmField
        val SUGGESTIONS: List<String> =
            Arrays.asList(
                "\u2764\uFE0F",
                "\uD83D\uDC4D",
                "\uD83D\uDC4E",
                "\uD83D\uDE02",
                "\uD83D\uDE2E",
                "\uD83D\uDE22",
            )

        private val GSON: Gson =
            GsonBuilder()
                .registerTypeAdapter(Jid::class.java, JidTypeAdapter())
                .registerTypeAdapter(Cid::class.java, CidTypeAdapter())
                .create()

        @JvmStatic
        fun toString(reactions: Collection<Reaction>?): String? =
            if (reactions == null || reactions.isEmpty()) null else GSON.toJson(reactions)

        @JvmStatic
        fun fromString(asString: String?): Collection<Reaction> {
            if (Strings.isNullOrEmpty(asString)) {
                return Collections.emptyList()
            }
            try {
                return GSON.fromJson(asString, object : TypeToken<List<Reaction>>() {}.type)
            } catch (e: IllegalArgumentException) {
                Log.e(Config.LOGTAG, "could not restore reactions", e)
                return Collections.emptyList()
            } catch (e: JsonSyntaxException) {
                Log.e(Config.LOGTAG, "could not restore reactions", e)
                return Collections.emptyList()
            }
        }

        fun withMine(
            existing: Collection<Reaction>,
            reactions: Collection<String>,
            received: Boolean,
            from: Jid?,
            trueJid: Jid?,
            occupantId: String?,
            envelopeId: String?,
        ): Collection<Reaction> {
            val builder = ImmutableSet.builder<Reaction>()
            builder.addAll(Collections2.filter(existing) { e -> e.received })
            builder.addAll(
                Collections2.transform(reactions) { r ->
                    Reaction(r, null, received, from, trueJid, occupantId, envelopeId)
                },
            )
            return builder.build()
        }

        @JvmStatic
        fun withOccupantId(
            existing: Collection<Reaction>,
            reactions: Collection<String>,
            received: Boolean,
            from: Jid?,
            trueJid: Jid?,
            occupantId: String?,
            envelopeId: String?,
        ): Collection<Reaction> {
            val builder = ImmutableSet.builder<Reaction>()
            builder.addAll(
                Collections2.filter(existing) { e ->
                    (occupantId ?: throw NullPointerException()) != e.occupantId
                },
            )
            builder.addAll(
                Collections2.transform(reactions) { r ->
                    Reaction(r, null, received, from, trueJid, occupantId, envelopeId)
                },
            )
            return builder.build()
        }

        @JvmStatic
        fun withFrom(
            existing: Collection<Reaction>,
            reactions: Collection<String>,
            received: Boolean,
            from: Jid?,
            envelopeId: String?,
        ): Collection<Reaction> {
            val builder = ImmutableSet.builder<Reaction>()
            builder.addAll(
                Collections2.filter(existing) { e ->
                    (from ?: throw NullPointerException()).asBareJid() !=
                        (e.from ?: throw NullPointerException()).asBareJid()
                },
            )
            builder.addAll(
                Collections2.transform(reactions) { r ->
                    Reaction(r, null, received, from, null, null, envelopeId)
                },
            )
            return builder.build()
        }

        @JvmStatic
        fun aggregated(reactions: Collection<Reaction>): Aggregated = aggregatedInternal(reactions, null)

        @JvmStatic
        fun aggregated(
            reactions: Collection<Reaction>,
            thumbnailer: Function<Reaction, GetThumbnailForCid>,
        ): Aggregated = aggregatedInternal(reactions, thumbnailer)

        /**
         * Java's `(r) -> null` cannot be a Kotlin lambda: `java.util.function.Function.apply` is
         * non-null to Kotlin, so the one-arg overload passes `null` here and the dereference throws
         * the same `NullPointerException` Java's null-returning lambda threw at `.getThumbnail`.
         */
        private fun aggregatedInternal(
            reactions: Collection<Reaction>,
            thumbnailer: Function<Reaction, GetThumbnailForCid>?,
        ): Aggregated {
            val aggregatedReactions: Map<Emoji, Collection<Reaction>> =
                Multimaps.index(reactions) { r ->
                    val cid = r.cid
                    if (cid == null) {
                        Emoji(r.reaction, 0)
                    } else {
                        CustomEmoji(
                            r.reaction,
                            cid.toString(),
                            (thumbnailer ?: throw NullPointerException()).apply(r).getThumbnail(cid),
                            null,
                        )
                    }
                }.asMap()
            val comparator: Comparator<Map.Entry<Emoji, Collection<Reaction>>> =
                Comparator.comparingInt { entry -> entry.value.size }
            val sortedList: List<Map.Entry<Emoji, Collection<Reaction>>> =
                Ordering.from(comparator)
                    .reverse<Map.Entry<Emoji, Collection<Reaction>>>()
                    .immutableSortedCopy(aggregatedReactions.entries)
            val ourReactions: Set<String> =
                ImmutableSet.copyOf(
                    Collections2.transform(
                        Collections2.filter(reactions) { r -> r.cid == null && !r.received },
                        Reaction::normalizedReaction,
                    ),
                )
            return Aggregated(sortedList, ourReactions)
        }
    }
}
