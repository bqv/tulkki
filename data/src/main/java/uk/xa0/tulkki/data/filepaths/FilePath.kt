package uk.xa0.tulkki.data.filepaths

import java.util.UUID

/**
 * Tulkki (port-45): the file-path row's own value type, un-nested out of
 * `DatabaseBackend` as the precondition of the `filepaths/` capability move.
 *
 * <p>It was `DatabaseBackend.FilePath`, a public nested class of the backend. An island-port
 * structural rule is what forced the move: a type written `Outer.Inner` cannot leave its outer
 * class while files outside spell it that way, and the one outside speller is `:data`'s own
 * `FileBackend.convertToAttachments`, whose two signatures named `DatabaseBackend.FilePath`. The
 * two signatures were retyped to this package in the same commit, which is the alternative the rule
 * allows; no other file spelled either nested name.
 *
 * <p>The four accessors are unchanged and are still the model's own: they were the island's
 * `FilePathRef` surface, which port-13 retired (its only caller outside the backend now asks the
 * model directly), so they override nothing and stay because `convertToAttachments` and the file
 * sweep read them. The fields stay exactly as they were and are still filled from the `Cursor` by
 * [FilePathStore].
 *
 * <p>**Ported from Java here, and the decisions are recorded rather than inherited:**
 *
 * 1. **The four fields stay fields, never properties: `@JvmField`.** Java declared them
 *    `public final`, and both spellings are read - `FileBackend.convertToAttachments`' `Map`
 *    overload reads `value.uuid` and `value.path` as fields, while its `List` overload reads
 *    `getPath()`/`getUuid()`/`getTimestamp()`/`getConversationUuid()` as accessors. The fields must
 *    stay readable from Java and Kotlin as fields; a bare Kotlin `val` would move the name to a
 *    generated getter and break the field reads. **Interop debt: four `@JvmField`s.**
 * 2. **`getUuid()` stays a function beside the `uuid` field, and answers `String`.** The field is
 *    the `UUID` the constructor parses; the accessor is `uuid.toString()`, exactly the Java's. It
 *    coexists with the field because `@JvmField` suppresses the generated getter, so the written
 *    `getUuid()` is the only one - the `AbstractEntity.uuid`/`getUuid()` precedent.
 * 3. **The constructor keeps Java's package-private shape as `internal`.** Kotlin has no
 *    package-private, and [FilePathStore], in this package and this module, is its only caller.
 * 4. **The class and `getUuid()` are `open`, and `conversationUuid` is nullable, because the Java
 *    was.** [FilePathInfo], this package's other value type, extends this class and overrides
 *    `getUuid()`, and its super call hands `null` as the conversation uuid - the Java declared the
 *    class and the accessor non-final (`public class FilePath`, `public String getUuid()`) and its
 *    `conversationUuid` field could already hold that `null`. Kotlin's final and non-null defaults
 *    would have narrowed that surface and left `FilePathInfo` uncompilable, so the Java's shape is
 *    written out rather than inherited.
 */
open class FilePath internal constructor(
    uuid: String,
    path: String,
    timestamp: Long,
    conversationUuid: String?,
) {
    @JvmField
    val uuid: UUID = UUID.fromString(uuid)

    @JvmField
    val path: String = path

    @JvmField
    val timestamp: Long = timestamp

    @JvmField
    val conversationUuid: String? = conversationUuid

    open fun getUuid(): String = uuid.toString()

    fun getPath(): String = path

    fun getTimestamp(): Long = timestamp

    fun getConversationUuid(): String? = conversationUuid
}
