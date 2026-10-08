package uk.xa0.tulkki.data

import androidx.room.Room
import androidx.room.RoomOpenDelegate
import androidx.sqlite.SQLiteDriver

/**
 * Builds the module's `RoomDatabase` on the host, over a [SQLiteDriver].
 *
 * <p>**The `setDriver` half is a direct call, and the record that said otherwise was wrong.** The
 * pinned Room is **2.7.0** (`data/build.gradle`), and `javap` on the AAR's `classes.jar` shows
 * `public final androidx.room.RoomDatabase$Builder<T> setDriver(androidx.sqlite.SQLiteDriver)`,
 * compiled from `RoomDatabase.android.kt` — a **Kotlin** declaration, so both its `SQLiteDriver`
 * and `databaseBuilder`'s `Context` are strictly non-null. A chain with a non-null `Context`
 * compiles clean and `javap -c` shows the `Builder.setDriver` `invokevirtual`. The recorded
 * "cannot resolve `setDriver` at all" is the **second** error of a chain opened by a null
 * `Context` (`null cannot be a value of a non-null type 'Context'.` then
 * `unresolved reference 'setDriver'.`, the receiver having become an error type) — and that null
 * appears nowhere: this file has always passed a [HostContext], and `git log -S
 * 'databaseBuilder(null'` is empty.
 *
 * <p>**The `openDelegate` half is real**, and is reached reflectively by [GeneratedOpenDelegate];
 * see there for the reasoning and the price.
 */
internal object RoomOnTheHost {

    /** The one file, opened through the driver rather than through Android's open-helper path. */
    fun open(driver: SQLiteDriver, name: String): HistoryDatabase =
        Room.databaseBuilder(HostContext(), HistoryDatabase::class.java, name)
            .setDriver(driver)
            .allowMainThreadQueries()
            .build()

    /**
     * Room's own generated validator for the history file. KSP's `HistoryDatabase_Impl` is `final`
     * and its `createOpenDelegate()` is `protected`, so a Kotlin test in this package cannot name
     * the member; [GeneratedOpenDelegate] looks it up reflectively and fails loudly, naming the
     * class and the member, if a KSP rename ever breaks the bridge.
     */
    fun openDelegate(): RoomOpenDelegate = GeneratedOpenDelegate.of(HistoryDatabase_Impl())
}
