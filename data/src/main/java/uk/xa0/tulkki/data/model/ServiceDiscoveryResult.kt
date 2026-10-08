package uk.xa0.tulkki.data.model

import android.content.ContentValues
import android.database.Cursor
import android.util.Base64
import com.google.common.base.Strings
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Collections
import java.util.Comparator
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.forms.Data
import uk.xa0.tulkki.xmpp.forms.Field
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.libs.ServiceDiscoveryResultRef

/** Java's `clean`: escape the one character the concatenated string is allowed to carry. */
private fun clean(s: String): String = s.replace("<", "&lt;")

/** Java's `blankNull`: a null component of the capabilities hash is the empty string. */
private fun blankNull(s: String?): String = if (s == null) "" else clean(s)

/**
 * The capabilities hash, Java's `mkCapHash` made pure. It sorts the three lists **in place**, and
 * those are the lists the one shape that calls it stores - which is why the sort is load-bearing and
 * why the digest and the parse cannot be separated (see the class KDoc, decision 1).
 */
private fun mkCapHash(
    identities: MutableList<ServiceDiscoveryResult.Identity>,
    features: MutableList<String>,
    forms: MutableList<Data>,
): ByteArray? {
    val s = StringBuilder()

    Collections.sort(identities)
    for (id in identities) {
        s.append(blankNull(id.getCategory()))
            .append("/")
            .append(blankNull(id.getType()))
            .append("/")
            .append(blankNull(id.getLang()))
            .append("/")
            .append(blankNull(id.getName()))
            .append("<")
    }

    Collections.sort(features)
    for (feature in features) {
        s.append(clean(feature)).append("<")
    }

    Collections.sort(forms, Comparator.comparing<Data, String> { form -> form.getFormType() })
    for (form in forms) {
        s.append(clean(form.getFormType())).append("<")
        val fields = form.getFields()
        Collections.sort(
            fields,
            Comparator.comparing<Field, String> { field -> Strings.nullToEmpty(field.getFieldName()) },
        )
        for (field in fields) {
            s.append(Strings.nullToEmpty(field.getFieldName())).append("<")
            val values = field.getValues()
            Collections.sort(values, Comparator.comparing<String, String> { value -> blankNull(value) })
            for (value in values) {
                s.append(blankNull(value)).append("<")
            }
        }
    }

    val md: MessageDigest =
        try {
            MessageDigest.getInstance("SHA-1")
        } catch (e: NoSuchAlgorithmException) {
            return null
        }

    return md.digest(s.toString().toByteArray(StandardCharsets.UTF_8))
}

/** Java's `createFormFromJSONObject`. */
private fun createFormFromJSONObject(o: JSONObject): Data {
    val data = Data()
    val names = o.names() ?: throw NullPointerException()
    for (i in 0 until names.length()) {
        try {
            val name = names.getString(i)
            val jsonValues = o.getJSONArray(name)
            val values = ArrayList<String>(jsonValues.length())
            for (j in 0 until jsonValues.length()) {
                values.add(jsonValues.getString(j))
            }
            data.put(name, values)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    return data
}

/** Java's `createJSONFromForm`. */
private fun createJSONFromForm(data: Data): JSONObject {
    val obj = JSONObject()
    for (field in data.getFields()) {
        try {
            val jsonValues = JSONArray()
            for (value in field.getValues()) {
                jsonValues.put(value)
            }
            obj.put(field.getFieldName(), jsonValues)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    try {
        val jsonValues = JSONArray()
        jsonValues.put(data.getFormType())
        obj.put(Data.FORM_TYPE, jsonValues)
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return obj
}

/**
 * What a service discovery answer said: its identities, its features, its XEP-0004 forms, and the
 * capabilities hash those three fold into. [ServiceDiscoveryResultRef] is the island's name for it.
 *
 * <p>Ported from Java by the `port` stage (port-2). The decisions, recorded rather than inherited:
 *
 * 1. **The primary constructor holds the five values and the three Java-visible constructors
 *    delegate to it** - via one private secondary constructor that unpacks a [State]. Kotlin allows
 *    no local before `this(...)`, and the one body that needs one is the `Iq` shape: its digest is
 *    computed over the *same* list objects the fields must store (the sort inside `mkCapHash` is
 *    observable through `getIdentities()`, `getFeatures()` and `toJSON()`), so the parse and the
 *    digest have to happen once, before delegation. [State] is that one evaluation; nothing is
 *    loosened - the fields stay `val`, so Java's `final` survives.
 * 2. **`hash`, `ver` and `features` are `@JvmField protected`, and `forms` is `@JvmField internal`** -
 *    Java declared all four `protected final`. `features` and `forms` are read as *fields* by
 *    `MucOptions:195`, `:204`; `hash` and `ver` keep the field because Java's surface was a field (and
 *    a plain `protected val ver` would synthesise `getVer()`, clashing with the explicit `getVer()`).
 *    `forms` is `internal` because the `MucOptions` port cannot read a `protected` field at all -
 *    Kotlin has no package-private - and `@JvmField internal` still emits a **public** JVM field
 *    (measured), so `MucOptions.java` compiles unchanged in the same commit and the next one reads
 *    `forms` directly. `identities` is a private `val` (Java's was private), so it generates no
 *    accessor beside `getIdentities()`; `features` stays `protected` because Kotlin reads it through
 *    the public `getFeatures()` override. **Interop debt: four `@JvmField`s.**
 * 3. **The four table constants are companion `const val`s**, which compile to static fields *on the
 *    class* - what `RosterQueries.kt:34-36`, `:98`, `:142` and `DatabaseBackend:738`, `:757` already
 *    write (`Post`'s precedent).
 * 4. **[empty] has no caller anywhere.** The island's only mention is the comment at
 *    `XmppConnection:2390`, which records that the existing factory is used instead. It is ported
 *    as-is and carries no annotation; deleting dead code is its own decision and its own commit, not
 *    a port's.
 * 5. **`@Throws(JSONException::class)` is on the Cursor constructor**, because
 *    `DatabaseBackend.findDiscoveryResult` catches `JSONException` around `new
 *    ServiceDiscoveryResult(cursor)`. Java declared it on the constructor; Kotlin needs the
 *    annotation for javac to keep allowing the catch.
 * 6. **`Identity`'s three package-private constructors and its package-private `toJSON()` become
 *    `internal`.** A nested class's `private` is unreachable from the outer class's companion
 *    (measured when porting `Reaction.Aggregated`), and no caller outside this file exists; the
 *    constructors' `internal` is JVM-public, which `javap` shows, and no Java code constructs one.
 *    Its protected/package-private fields become private `val`s behind the explicit
 *    `getCategory()`/`getType()`/`getLang()`/`getName()` Java already had - no Java caller reads
 *    them as fields.
 * 7. **`names()` keeps Java's `NullPointerException`** (`o.names() ?: throw NullPointerException()`):
 *    `JSONObject.names()` answers `null` for an object with no keys, and Java dereferenced it.
 * 8. **[getIdentity] keeps Java's dereference of an identity's nullable `category`/`type`** with
 *    `?: throw NullPointerException()`. `Identity(...)` from JSON (`optString(..., null)`) can carry
 *    neither, so the branch Java threw on is the branch this throws on.
 * 9. **The private statics `clean`/`blankNull`/`mkCapHash`/`createFormFromJSONObject`/
 *    `createJSONFromForm` become private *top-level* functions in this file.** Both the class and its
 *    nested `Identity` call `blankNull`, and only a file-private function needs no assumption about
 *    Kotlin's nested-to-outer private visibility. No Java caller named any of them.
 * 10. **The rest is Java's, call for call**: `Base64.encodeToString(ver, Base64.NO_WRAP)`,
 *     `StandardCharsets.UTF_8` through `toByteArray`, `Strings.nullToEmpty`, the three
 *     `optJSONArray` guards, and the two `catch (Exception)` blocks that print and carry on.
 *
 * Nothing in the tree extends `ServiceDiscoveryResult`, so Kotlin's implicit `final` is Java's shape.
 */
class ServiceDiscoveryResult private constructor(
    @JvmField protected val hash: String,
    @JvmField protected val ver: ByteArray?,
    private val identities: List<Identity>,
    @JvmField protected val features: List<String>,
    @JvmField internal val forms: List<Data>,
) : ServiceDiscoveryResultRef {

    /**
     * The one evaluation the `Iq` shape needs before it can delegate: the parsed lists *and* the
     * digest taken over them, so the lists the fields keep are the lists the hash was taken from.
     */
    private class State(
        val hash: String,
        val ver: ByteArray?,
        val identities: List<Identity>,
        val features: List<String>,
        val forms: List<Data>,
    ) {

        companion object {

            fun from(packet: Iq): State {
                val identities = ArrayList<Identity>()
                val features = ArrayList<String>()
                val forms = ArrayList<Data>()

                for (element in packet.query().getChildren()) {
                    if ("identity" == element.getName()) {
                        val id = Identity(element)
                        if (id.getType() != null && id.getCategory() != null) {
                            identities.add(id)
                        }
                    } else if ("feature" == element.getName()) {
                        val varName = element.getAttribute("var")
                        if (varName != null) {
                            features.add(varName)
                        }
                    } else if (
                        "x" == element.getName() &&
                            Namespace.DATA ==
                            (element.getAttribute("xmlns") ?: throw NullPointerException())
                    ) {
                        forms.add(Data.parse(element) ?: throw NullPointerException())
                    }
                }

                return State("sha-1", mkCapHash(identities, features, forms), identities, features, forms)
            }

            @Throws(JSONException::class)
            fun from(cursor: Cursor): State {
                val hash = cursor.getString(cursor.getColumnIndexOrThrow(HASH))
                val ver = Base64.decode(cursor.getString(cursor.getColumnIndexOrThrow(VER)), Base64.DEFAULT)
                val o = JSONObject(cursor.getString(cursor.getColumnIndexOrThrow(RESULT)))

                val identities = ArrayList<Identity>()
                val features = ArrayList<String>()
                val forms = ArrayList<Data>()

                val jsonIdentities = o.optJSONArray("identities")
                if (jsonIdentities != null) {
                    for (i in 0 until jsonIdentities.length()) {
                        identities.add(Identity(jsonIdentities.getJSONObject(i)))
                    }
                }
                val jsonFeatures = o.optJSONArray("features")
                if (jsonFeatures != null) {
                    for (i in 0 until jsonFeatures.length()) {
                        features.add(jsonFeatures.getString(i))
                    }
                }
                val jsonForms = o.optJSONArray("forms")
                if (jsonForms != null) {
                    for (i in 0 until jsonForms.length()) {
                        forms.add(createFormFromJSONObject(jsonForms.getJSONObject(i)))
                    }
                }

                return State(hash, ver, identities, features, forms)
            }

            fun empty(): State =
                State(
                    "sha-1",
                    null,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                )
        }
    }

    private constructor(state: State) :
        this(state.hash, state.ver, state.identities, state.features, state.forms)

    constructor(packet: Iq) : this(State.from(packet))

    @Throws(JSONException::class)
    constructor(cursor: Cursor) : this(State.from(cursor))

    private constructor() : this(State.empty())

    override fun getVer(): String = Base64.encodeToString(ver, Base64.NO_WRAP)

    override fun getIdentities(): List<Identity> = identities

    override fun getFeatures(): List<String> = features

    fun getIdentity(category: String?, type: String?): Identity? {
        for (id in getIdentities()) {
            if (
                (category == null ||
                    (id.getCategory() ?: throw NullPointerException()) == category) &&
                    (type == null || (id.getType() ?: throw NullPointerException()) == type)
            ) {
                return id
            }
        }

        return null
    }

    override fun hasIdentity(category: String?, type: String?): Boolean =
        getIdentity(category, type) != null

    override fun getExtendedDiscoInformation(formType: String, name: String): String? {
        for (form in forms) {
            if (formType == form.getFormType()) {
                for (field in form.getFields()) {
                    if (name == field.getFieldName()) {
                        return field.getValue()
                    }
                }
            }
        }
        return null
    }

    private fun toJSON(): JSONObject? {
        try {
            val o = JSONObject()

            val ids = JSONArray()
            for (id in getIdentities()) {
                ids.put(id.toJSON())
            }
            o.put("identities", ids)

            o.put("features", JSONArray(getFeatures()))

            val jsonForms = JSONArray()
            for (data in forms) {
                jsonForms.put(createJSONFromForm(data))
            }
            o.put("forms", jsonForms)

            return o
        } catch (e: JSONException) {
            return null
        }
    }

    fun getContentValues(): ContentValues {
        val values = ContentValues()
        values.put(HASH, hash)
        values.put(VER, getVer())
        val jsonObject = toJSON()
        values.put(RESULT, if (jsonObject == null) "" else jsonObject.toString())
        return values
    }

    /** One advertised identity: its category, type, language and name, any of which may be absent. */
    class Identity internal constructor(
        private val category: String?,
        private val type: String?,
        private val lang: String?,
        private val name: String?,
    ) : Comparable<Identity>, ServiceDiscoveryResultRef.IdentityRef {

        internal constructor(el: Element) :
            this(
                el.getAttribute("category"),
                el.getAttribute("type"),
                el.getAttribute("xml:lang"),
                el.getAttribute("name"),
            )

        internal constructor(o: JSONObject) :
            this(
                o.optString("category", null),
                o.optString("type", null),
                o.optString("lang", null),
                o.optString("name", null),
            )

        fun getCategory(): String? = category

        fun getType(): String? = type

        fun getLang(): String? = lang

        override fun getName(): String? = name

        internal fun toJSON(): JSONObject? {
            try {
                val o = JSONObject()
                o.put("category", getCategory())
                o.put("type", getType())
                o.put("lang", getLang())
                o.put("name", getName())
                return o
            } catch (e: JSONException) {
                return null
            }
        }

        override fun compareTo(other: Identity): Int {
            var r = blankNull(getCategory()).compareTo(blankNull(other.getCategory()))
            if (r == 0) {
                r = blankNull(getType()).compareTo(blankNull(other.getType()))
            }
            if (r == 0) {
                r = blankNull(getLang()).compareTo(blankNull(other.getLang()))
            }
            if (r == 0) {
                r = blankNull(getName()).compareTo(blankNull(other.getName()))
            }

            return r
        }
    }

    companion object {

        const val TABLENAME = "discovery_results"
        const val HASH = "hash"
        const val VER = "ver"
        const val RESULT = "result"

        /** Dead: no caller in the tree names it (see decision 4 in the class KDoc). */
        fun empty(): ServiceDiscoveryResult = ServiceDiscoveryResult()
    }
}
