package uk.xa0.tulkki.data

import androidx.room.RoomDatabase
import androidx.room.RoomOpenDelegate
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * KSP's own schema validator, reached reflectively.
 *
 * <p>Room's generated `…Database_Impl` declares `createOpenDelegate()` **twice**: the real
 * `protected override fun` (`ACC_PROTECTED`), and the covariant bridge the JVM needs for the
 * narrowed return type — `public RoomOpenDelegateMarker createOpenDelegate()`, marked
 * `ACC_BRIDGE|ACC_SYNTHETIC`, which Kotlin's resolution hides. `protected` in Kotlin is
 * subclass-only where Java's also reaches the package, and the generated class is `final`, so no
 * subclass can widen it: every ordinary spelling fails with
 * `cannot access 'fun createOpenDelegate(): RoomOpenDelegate': it is protected in
 * '…Database_Impl'`. The one spelling that compiles is `@Suppress("INVISIBLE_REFERENCE")`, and the
 * compiler itself warns that its behaviour is *UNSPECIFIED and WILL NOT BE PRESERVED* — so this
 * helper does not use it.
 *
 * <p>Instead the member is reached through `java.lang.reflect`, which is the owner's ruling: a Java
 * file going away is worth a run-time-checked access. The price is this class, and it is paid
 * here rather than at each call site —
 *
 * <ul>
 *   <li>the lookup is **cached per generated class**, so reflection runs once, not once per test;
 *   <li>a missing member fails **loudly and names itself**: the thrown message carries both the
 *       method and the class it was looked for on, never a bare `NoSuchMethodException` and never a
 *       silent null.
 * </ul>
 *
 * <p>What it does *not* change: the delegate is still KSP's own, so callers keep running the
 * generated `onValidateSchema`/`createAllTables` rather than a re-spelling.
 *
 * <p>`RoomOnTheHost.open(driver, name)` is **not** this problem and does not come through here: the
 * `setDriver` half of the recorded refusal was a cascade of a null `Context` that appears nowhere in
 * the tree, and that path is a direct, compile-checked call.
 */
internal object GeneratedOpenDelegate {

    /** The member Room generates for its open delegate, under both its spellings. */
    private const val MEMBER = "createOpenDelegate"

    /** One lookup per generated database class; the reflection runs once. */
    private val lookups = ConcurrentHashMap<Class<out RoomDatabase>, Method>()

    /** Room's own generated validator for [database]'s class. */
    fun of(database: RoomDatabase): RoomOpenDelegate {
        val type = database.javaClass
        val method = lookups.getOrPut(type) { lookUp(type) }
        return try {
            // Two candidates share the no-arg signature and the JDK's most-specific-return rule
            // picks the real `protected` override; either candidate's runtime value is a
            // `RoomOpenDelegate`, so the cast cannot be handed the marker instead.
            method.invoke(database) as RoomOpenDelegate
        } catch (failure: ReflectiveOperationException) {
            throw IllegalStateException(
                "$MEMBER() on ${type.name} failed; the host bridge cannot reach KSP's own schema " +
                    "validator. Fix uk.xa0.tulkki.data.GeneratedOpenDelegate.",
                failure,
            )
        }
    }

    private fun lookUp(type: Class<out RoomDatabase>): Method {
        val method = try {
            type.getDeclaredMethod(MEMBER)
        } catch (missing: NoSuchMethodException) {
            throw IllegalStateException(
                "Room's generated ${type.name} no longer declares $MEMBER(): a KSP rename broke " +
                    "the host bridge. The reflection lookup in " +
                    "uk.xa0.tulkki.data.GeneratedOpenDelegate expects " +
                    "'${type.name}.$MEMBER()'.",
                missing,
            )
        }
        // `protected` in the generated Kotlin, and the JDK grants that only to a same-package
        // caller — which the updb bridge is not, since it lives in `…data.updb`. These are
        // classpath classes in the unnamed module, so the flag is always ours to set.
        method.isAccessible = true
        return method
    }
}
