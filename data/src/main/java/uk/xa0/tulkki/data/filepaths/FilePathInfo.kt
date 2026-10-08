package uk.xa0.tulkki.data.filepaths

import uk.xa0.tulkki.libs.FilePathInfoRef

/**
 * Tulkki (port-45): [FilePath] plus the deleted flag, un-nested out of `DatabaseBackend` beside it.
 *
 * <p>It is what `getFilePathInfo()` answers and what the file sweep writes back through
 * `markFilesAsChanged`. It implements [FilePathInfoRef], which moved out of
 * `uk.xa0.tulkki.xmpp.refs` into `uk.xa0.tulkki.libs` (2026-10-08, lane `G`) - a package-only move,
 * so this `implements` clause and every member are unchanged and only the import line differs. The
 * pair itself stays here because [FilePathStore] builds it through its `internal` constructor.
 *
 * <p>**Ported from Java here, and the shape is [FilePath]'s, its sibling in this package:**
 *
 * 1. **`deleted` stays a field, never a property: `@JvmField`.** Java declared it `public`, and the
 *    file sweep reads the model's own flag (`markFilesAsChanged` puts it back into the
 *    `messages.deleted` column). A `var deleted` property would move the name to a generated
 *    getter/setter pair and change the JVM surface. **Interop debt: one `@JvmField`.**
 * 2. **The accessors stay written functions.** [FilePathInfoRef] declares `getUuid()`, `deleted()`
 *    and `setDeleted(boolean)`, and this class implements them; `getPath()` is satisfied by the
 *    inherited [FilePath.getPath]. `getUuid()` is written out even though [FilePath.getUuid]
 *    answers the same string, because the Java declared it here and the declared member is part of
 *    the surface being preserved.
 * 3. **The constructor keeps Java's package-private shape as `internal`.** Kotlin has no
 *    package-private, and [FilePathStore], in this package and this module, is its only caller.
 * 4. **The super call keeps `0` and `null`** for the inherited `timestamp`/`conversationUuid`,
 *    exactly as the Java did: a `FilePathInfo` is `getFilePathInfo()`'s row, not
 *    `convertToAttachments`' file, and it never carries either.
 */
class FilePathInfo internal constructor(
    uuid: String,
    path: String,
    deleted: Boolean,
) : FilePath(uuid, path, 0L, null), FilePathInfoRef {

    @JvmField
    var deleted: Boolean = deleted

    /** Tulkki: part 17 - the island's view of the inherited `uuid` field. */
    override fun getUuid(): String = uuid.toString()

    /** Tulkki: part 17 - the island's view of the public `deleted` field. */
    override fun deleted(): Boolean = deleted

    /** Tulkki: C5-E5 - the file sweep's writer, which [FilePathInfoRef] declares. */
    override fun setDeleted(deleted: Boolean): Boolean {
        val changed = deleted != this.deleted
        this.deleted = deleted
        return changed
    }
}
