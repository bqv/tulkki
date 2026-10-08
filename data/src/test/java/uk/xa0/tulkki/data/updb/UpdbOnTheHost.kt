package uk.xa0.tulkki.data.updb

import androidx.room.RoomOpenDelegate
import uk.xa0.tulkki.data.GeneratedOpenDelegate

/**
 * The `updb` file's generated validator.
 *
 * <p>KSP's `UnifiedPushDatabase_Impl` declares `createOpenDelegate()` **twice** — the real
 * `protected override fun` and the synthetic covariant bridge — and Kotlin's `protected` is
 * subclass-only where Java's also reaches the package, so a converted Kotlin test in this package
 * cannot name the member (the bridge is `ACC_BRIDGE|ACC_SYNTHETIC` and resolution hides it). It is
 * reached through [GeneratedOpenDelegate], which caches the lookup and names the class and the
 * member if a KSP rename breaks it.
 *
 * <p>What has *not* changed: this is still KSP's own delegate, so callers keep running the
 * generated `onValidateSchema`/`createAllTables` rather than a re-spelling.
 */
internal object UpdbOnTheHost {

    /** Room's own generated validator for the UnifiedPush file. */
    fun openDelegate(): RoomOpenDelegate = GeneratedOpenDelegate.of(UnifiedPushDatabase_Impl())
}
