package uk.xa0.tulkki.data.model

import android.content.ContentValues

/**
 * The uuid every model entity carries, and the `ContentValues` row it writes.
 *
 * <p>Ported from Java by the `port` stage (`port-5`, the `:data.model` row; the same model file set
 * `port-2`'s "31 of 78" carries). The decisions, recorded rather than inherited:
 *
 * 1. **`uuid` stays a Java-visible `@JvmField protected` field, with `getUuid()` written beside it.**
 *    `Message`, `Conversation` and `Account` assign `this.uuid` from their constructors and read the
 *    field directly; a Kotlin `var` would generate a `setUuid()` that clashes with `Message`'s own
 *    `override fun setUuid(uuid: String?)` from `MessageRef`. **Interop debt: one `@JvmField`.**
 * 2. **`getUuid()` is nullable, because the field is**, and `:app` now handles that null. Java
 *    declared a bare `String`, which promised Kotlin nothing; `MessageRef`/`AccountRef`/`StoryRef`/
 *    `ConversationalRef` (Java) still declare the same platform type, so no Java caller moves.
 * 3. **[equals] answers `false` for a uuid-less entity** instead of Java's dereference. The overload
 *    has no caller anywhere in the tree (measured: `equals(` over `data/model` finds only its own
 *    declaration), so its shape is kept and its throw is not.
 * 4. **`UUID` is a companion `const val`**, so `AbstractEntity.UUID` is a Kotlin compile-time
 *    constant. The subclasses that restate it (`Message.kt:2103`, `Conversation.kt:1628`,
 *    `Story.kt`'s own) keep their restatements, and Java reads `PresenceTemplate.UUID` through
 *    static inheritance.
 */
abstract class AbstractEntity {

    @JvmField
    protected var uuid: String? = null

    open fun getUuid(): String? = uuid

    abstract fun getContentValues(): ContentValues

    fun equals(entity: AbstractEntity): Boolean {
        val mine = getUuid() ?: return false
        return mine == entity.getUuid()
    }

    companion object {
        const val UUID = "uuid"
    }
}
