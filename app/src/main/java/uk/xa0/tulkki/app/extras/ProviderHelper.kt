package uk.xa0.tulkki.app.extras

import com.google.gson.annotations.SerializedName

/**
 * One entry of the public provider list, as Gson deserialises it.
 *
 * <p>The field stays a **public JVM field**: Gson binds by reflecting over fields, so `jid` must be
 * a field of that name - a Kotlin `val` with a getter would leave Gson binding a private field whose
 * annotation had landed somewhere else. `@JvmField` is what keeps the annotation on the field and
 * the field public.
 *
 * <p>This is the one file of `port-18`'s four that **keeps** its annotation. No `.java` file names
 * `ProviderHelper` either - `ProviderService`, its only reader, is Kotlin and reaches it through
 * `Gson().fromJson` - but §5.5's audit asks whether a *caller* owes the annotation, and this one is
 * owed to Gson's own reflection over the field, which no Kotlin spelling replaces: dropping it
 * would leave the deserialiser binding nothing. The count is still debt; this debt is Gson's.
 *
 * <p>`get_jid()` is deliberately *not* a Kotlin property: the Java spelled it with an underscore and
 * the callers read the Java spelling, so it stays a method of that exact name.
 */
class ProviderHelper {

    @SerializedName("jid")
    @JvmField
    var jid: String? = null

    fun get_jid(): String? = jid
}
