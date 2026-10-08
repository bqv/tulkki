package uk.xa0.tulkki.xmpp.utils

import android.database.AbstractWindowedCursor
import android.database.Cursor
import android.database.CursorWindow
import android.database.sqlite.SQLiteCursor
import android.os.Build

/**
 * Grows a database cursor's window before a large query walks it.
 *
 * Ported from Java by the `px` lane. Decisions taken rather than inherited:
 *
 * 1. **A `class` with a companion, not a Kotlin `object`.** Java's implicit constructor was public
 *    and stays public, so the Java-visible surface is unchanged; the static method is `@JvmStatic`,
 *    which is what `:data`'s `DatabaseBackend` reads (`CursorUtils.upgradeCursorWindowSize(cursor)`
 *    at `:1046` and `:1151`).
 * 2. **`cursor` is non-null.** Java dereferenced it inside the `instanceof` checks only, but every
 *    caller passes the cursor it is about to walk and a null would have fallen through to a no-op
 *    rather than a crash; the non-null parameter is the contract those callers keep.
 * 3. **The two `instanceof` branches are written as safe casts**, which is the same test and the same
 *    call order Java made: `AbstractWindowedCursor` first, then `SQLiteCursor`.
 */
class CursorUtils {

    companion object {

        @JvmStatic
        fun upgradeCursorWindowSize(cursor: Cursor) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                (cursor as? AbstractWindowedCursor)?.setWindow(
                    CursorWindow("4M", 4 * 1024 * 1024)
                )
                (cursor as? SQLiteCursor)?.setFillWindowForwardOnly(true)
            }
        }
    }
}
