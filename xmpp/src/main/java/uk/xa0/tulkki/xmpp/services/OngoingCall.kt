package uk.xa0.tulkki.xmpp.services

import com.google.common.base.Objects
import uk.xa0.tulkki.xmpp.jingle.AbstractJingleConnection
import uk.xa0.tulkki.xmpp.jingle.Media

/**
 * Tulkki: the call in progress, un-nested out of `XmppConnectionService`
 * and converted from the Java.
 *
 * The three members stay **public final fields** - `@JvmField`, not a Kotlin `val` with a generated
 * getter - because the hub mutates nothing here but *reads* them by field name from Kotlin
 * (`NotificationService.kt:661/681/684` reads `ongoingCall.id`, `.media`, `.reconnecting`), and
 * `Teardown.kt:142` constructs the object it stores in the service's `AtomicReference`. The class is
 * `open` because the Java's was: nothing extends it today, but "write out the Java's own modifiers"
 * is the rule that keeps a still-Java file compiling, and a final Kotlin class is narrower than the
 * Java's `public class`.
 *
 * `equals`/`hashCode` are written out rather than inherited from a `data class`, because a data
 * class would change the hash: Guava's `Objects.hashCode(...)` is `Arrays.hashCode`, which starts
 * from 1, while Kotlin's generated hash starts from the first member. The Java used
 * `Objects.equal`/`Objects.hashCode` over `id`, `media`, `reconnecting` in that order and compared
 * classes by identity (`getClass() != o.getClass()`, kept here as `javaClass != other.javaClass`,
 * which `open` makes meaningful). `==` on the three members *is* `Objects.equal` - Kotlin's `==` is
 * exactly the null-safe `equals` Guava's helper implements - so only the hash needs Guava.
 *
 * The one signature reading: Kotlin emits `Set<? extends Media>` for a `Set<Media>` **constructor**
 * parameter (`Media` is an enum, and enum type arguments get a wildcard - measured with kotlinc
 * 2.3.21 against a real Java enum), where the Java constructor declared it invariant. No Java file
 * constructs this class (`git grep "new OngoingCall"` finds only `Teardown.kt`), so the constructor's
 * generic signature has no Java reader; the *field* Kotlin emits is invariant either way, which is
 * the one that does have readers.
 */
open class OngoingCall(
    @JvmField val id: AbstractJingleConnection.Id,
    @JvmField val media: Set<Media>,
    @JvmField val reconnecting: Boolean,
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        val that = other as OngoingCall
        return reconnecting == that.reconnecting &&
            Objects.equal(id, that.id) &&
            Objects.equal(media, that.media)
    }

    override fun hashCode(): Int = Objects.hashCode(id, media, reconnecting)
}
