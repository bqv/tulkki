package uk.xa0.tulkki.data.model

import com.google.common.base.Preconditions
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import uk.xa0.tulkki.libs.Jid

/**
 * The JSON a provisioning link carries, parsed into the three facts an account needs.
 *
 * <p>Ported from Java by the `port` stage (port-2). The decisions, recorded rather than inherited:
 *
 * 1. **The three fields are nullable Kotlin properties.** Gson fills them by reflective field
 *    assignment and writes `null` for every key the JSON omits; Java then rejected exactly those
 *    nulls with `Preconditions`. Declaring `address`/`password` a non-null `String` would be a
 *    nullability claim the Gson parser does not honour, and the checks that guard it would become
 *    dead text. `protocol` is `Protocol?` for the same reason.
 * 2. **[password] is a `@JvmField`, and it is the file's only field that needs one.** `:app`'s
 *    `ProvisioningUtils.provision` reads it as a **field** (`accountConfiguration.password`), so a
 *    private backing field with a generated getter would break that caller. `address` and `protocol`
 *    have no reader outside this class and stay ordinary properties - **interop debt: one
 *    annotation**, and it goes to zero when `ProvisioningUtils` is ported.
 * 3. **[parse] is a companion `@JvmStatic`.** Its one caller is Java
 *    (`ProvisioningUtils.provision` calls `AccountConfiguration.parse(json)`); without the annotation
 *    the method would only exist as `Companion.parse`. **Debt: one more annotation.**
 * 4. **`Preconditions.checkArgument` is kept, call for call.** Kotlin's `require` answers the same
 *    `IllegalArgumentException`, but `ProvisioningUtils` catches that type by name and the Java call
 *    is the thing being preserved, so there is nothing to decide and nothing to rewrite. Java's
 *    `c.password != null && c.password.length() > 0` is `!c.password.isNullOrEmpty()`, which agrees
 *    on `null`, `""` and every non-empty string.
 * 5. **[getJid] stays a function, and the null address is rejected where Java rejected it.**
 *    `Jid.of` is Kotlin now, so its `CharSequence` parameter is non-null and the nullable field
 *    cannot be handed over silently. `Jid.of(null)` reached `JidCreate.from`'s `input.toString()`
 *    and threw `NullPointerException` in Java, so the hand-over is
 *    `address ?: throw NullPointerException("address")` - the same throw at the same moment, not a
 *    `!!` and not a widened parameter. [parse]'s address check is what makes it unreachable there,
 *    just as before.
 * 6. **A plain `class`, not a `data class`**, and final. Java had the implicit no-arg constructor Gson
 *    needs, identity equality that nothing compares, and no subclass in the tree; a `data class` would
 *    synthesise an equality and a `copy` this type never had.
 *
 * The Java-caller audit is the two annotations above and nothing else: no `@JvmOverloads` (there are
 * no default arguments), no `internal`, no `@JvmName`, no `!!`.
 */
class AccountConfiguration {

    var protocol: Protocol? = null
    var address: String? = null
    @JvmField var password: String? = null

    /** The JID the address names; a null address throws exactly where Java's `Jid.of` did. */
    fun getJid(): Jid = Jid.of(address ?: throw NullPointerException("address"))

    companion object {

        private val GSON: Gson = GsonBuilder().create()

        /** Parses a provisioning JSON string, refusing exactly what Java refused. */
        @JvmStatic
        fun parse(input: String): AccountConfiguration {
            val c = try {
                GSON.fromJson(input, AccountConfiguration::class.java)
            } catch (e: JsonSyntaxException) {
                throw IllegalArgumentException("Not a valid JSON string", e)
            }
            Preconditions.checkArgument(
                c.protocol == Protocol.XMPP,
                "Protocol must be XMPP"
            )
            Preconditions.checkArgument(
                c.address != null && c.getJid().isBareJid() && !c.getJid().isDomainJid(),
                "Invalid XMPP address"
            )
            Preconditions.checkArgument(
                !c.password.isNullOrEmpty(),
                "No password specified"
            )
            return c
        }
    }

    enum class Protocol {
        @SerializedName("xmpp") XMPP,
    }
}
