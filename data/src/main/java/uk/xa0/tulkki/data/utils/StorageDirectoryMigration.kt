package uk.xa0.tulkki.data.utils

import java.io.File

/**
 * The one-time move of the public storage directory from upstream's name to ours.
 *
 * Tulkki saves received files, recordings and exported backups under `Documents/Tulkki/`. The app it is forked
 * from saved the same files under `Documents/monocles chat/`; where that directory still exists, the first
 * process start after this change renames it, once. (It is not on the owner's phone: measured 2026-10-05,
 * `/sdcard/Documents` holds neither `monocles chat` nor `Tulkki`, so the move is a no-op there -- the earlier
 * claim that this phone still had the legacy directory was stale, not the move wrong.)
 *
 * **`"monocles chat"` here is an old spelling as data, not a name of ours.** It names a directory that exists
 * on disk; renaming the string without moving the directory would strand every file in it. This is the same
 * class of exception the naming rule already records for a persisted preference key or a wire namespace.
 *
 * The decision is a separate static so it can be tested as the truth table it is, without a filesystem: rename
 * if and only if the legacy directory is there and the current one is not. That also makes the move idempotent
 * -- after it the legacy directory is gone (or the current one exists), so a second call does nothing.
 */
object StorageDirectoryMigration {

    /** The directory Tulkki writes under `Documents/`. */
    const val DIRECTORY = "Tulkki"

    /** The directory upstream wrote under `Documents/`, as it is on disk. */
    const val LEGACY_DIRECTORY = "monocles chat"

    /**
     * Whether the legacy directory should be renamed onto the current one.
     *
     * An existing current directory wins: it is not deleted, not merged and not overwritten, because its
     * contents are newer than the legacy ones and a merge is not something this move can do safely.
     */
    @JvmStatic
    fun shouldRename(legacyExists: Boolean, currentExists: Boolean): Boolean = legacyExists && !currentExists

    /**
     * Rename `legacy` to `current` when the decision says so. Returns whether anything was renamed; a missing
     * argument, absent legacy directory, existing current directory or a refused rename are all `false` and
     * all leave the disk alone.
     */
    @JvmStatic
    fun migrate(legacy: File?, current: File?): Boolean {
        if (legacy == null || current == null) {
            return false
        }
        if (!shouldRename(legacy.exists(), current.exists())) {
            return false
        }
        return legacy.renameTo(current)
    }
}
