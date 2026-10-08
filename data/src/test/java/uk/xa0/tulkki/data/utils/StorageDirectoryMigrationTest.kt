package uk.xa0.tulkki.data.utils

import java.io.File
import java.nio.file.Files
import org.junit.Assert
import org.junit.Test

/**
 * The storage directory move: the decision table, and the real rename on a real filesystem.
 *
 * It is a pure JVM test on purpose. [StorageDirectoryMigration] names no Android type, so the order
 * the owner's phone depends on -- rename exactly when the old directory is there and the new one is
 * not, and never a second time -- is pinned here rather than on a device.
 *
 * What the move cannot do is also pinned: when `Documents/Tulkki/` already exists (a backup export
 * creates `Documents/Tulkki/Backup`), the legacy directory is left where it is. The legacy files are
 * not lost, but they are not found at the new path either -- that is the decision, and this test is
 * where it is written down.
 */
class StorageDirectoryMigrationTest {

    private fun temporaryDirectory(name: String): File {
        return Files.createTempDirectory(name).toFile()
    }

    @Test
    fun renamesTheLegacyDirectoryWhenTheCurrentOneIsAbsent() {
        val parent = temporaryDirectory("tulkki-storage-rename")
        val legacy = File(parent, StorageDirectoryMigration.LEGACY_DIRECTORY)
        val current = File(parent, StorageDirectoryMigration.DIRECTORY)
        Assert.assertTrue(File(legacy, "pictures").mkdirs())
        Assert.assertTrue(File(legacy, "pictures/photo.jpg").createNewFile())

        Assert.assertTrue(StorageDirectoryMigration.migrate(legacy, current))

        Assert.assertFalse(legacy.exists())
        Assert.assertTrue(File(current, "pictures/photo.jpg").isFile())
    }

    @Test
    fun leavesTheLegacyDirectoryWhenTheCurrentOneExists() {
        val parent = temporaryDirectory("tulkki-storage-both")
        val legacy = File(parent, StorageDirectoryMigration.LEGACY_DIRECTORY)
        val current = File(parent, StorageDirectoryMigration.DIRECTORY)
        Assert.assertTrue(File(legacy, "pictures").mkdirs())
        Assert.assertTrue(File(current, "Backup").mkdirs())

        Assert.assertFalse(StorageDirectoryMigration.migrate(legacy, current))

        Assert.assertTrue(File(legacy, "pictures").isDirectory())
        Assert.assertTrue(File(current, "Backup").isDirectory())
    }

    @Test
    fun doesNothingWhenThereIsNoLegacyDirectory() {
        val parent = temporaryDirectory("tulkki-storage-fresh")
        val legacy = File(parent, StorageDirectoryMigration.LEGACY_DIRECTORY)
        val current = File(parent, StorageDirectoryMigration.DIRECTORY)

        Assert.assertFalse(StorageDirectoryMigration.migrate(legacy, current))

        Assert.assertFalse(current.exists())
    }

    @Test
    fun aSecondRunIsANoOp() {
        val parent = temporaryDirectory("tulkki-storage-twice")
        val legacy = File(parent, StorageDirectoryMigration.LEGACY_DIRECTORY)
        val current = File(parent, StorageDirectoryMigration.DIRECTORY)
        Assert.assertTrue(legacy.mkdirs())

        Assert.assertTrue(StorageDirectoryMigration.migrate(legacy, current))
        Assert.assertFalse(StorageDirectoryMigration.migrate(legacy, current))

        Assert.assertTrue(current.isDirectory())
    }

    @Test
    fun theDecisionIsTheTruthTable() {
        Assert.assertFalse(StorageDirectoryMigration.shouldRename(false, false))
        Assert.assertTrue(StorageDirectoryMigration.shouldRename(true, false))
        Assert.assertFalse(StorageDirectoryMigration.shouldRename(false, true))
        Assert.assertFalse(StorageDirectoryMigration.shouldRename(true, true))
        Assert.assertFalse(StorageDirectoryMigration.migrate(null, null))
    }
}
