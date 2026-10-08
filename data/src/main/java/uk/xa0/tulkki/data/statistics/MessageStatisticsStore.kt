package uk.xa0.tulkki.data.statistics

import java.util.Calendar
import java.util.HashMap
import java.util.TimeZone
import net.zetetic.database.sqlcipher.SQLiteDatabase
import uk.xa0.tulkki.data.model.Message

/**
 * `statistics/`'s capability: the calendar's per-day message counts for one conversation and month.
 *
 * <p>**The next capability out of `DatabaseBackend`'s grab-bag, and one of the three smallest that
 * need nothing but the opener.** One read: every untranslated-timestamp boundary is computed in
 * Java's own `Calendar`/`TimeZone` terms, then the `messages` table is grouped by day-of-month. One
 * table (`messages`, no table of its own), one caller class (`:xmpp`'s `XmppConnectionService`,
 * which the calendar screen reaches through). `docs/MIGRATION.md`'s `port-45` row is the inventory
 * this comes out of.
 *
 * <p>**The home is an `object` that takes the `SQLiteDatabase` rather than a store that owns it.**
 * `DatabaseBackend` is the one owner of the file (`HistoryDatabase`'s Room connection, handed in by
 * `getInstance`), so a package that opened its own would be the second owner the design forbids;
 * the seam is the same one `schema/RawTables.applySchema(exec)` uses. There is no `Context` here and
 * no `get()` to add.
 *
 * <p>**The Java-visible surface is unchanged: `@JvmStatic`.** `DatabaseBackend.getMessagesCountGroupByDay`
 * is now a one-line delegation (the "one delegating step"), so `XmppConnectionService`'s call site -
 * and `ConversationCalendarActivity`'s call through it - compiles against byte for byte what it did
 * before. A `@JvmStatic` member of an `object` is the only shape whose static bridge carries the
 * un-mangled name - a Kotlin `internal` member would be emitted as `byDay$data` and Java could not
 * see it (the `ScriptReading`/`TranslationLanguages` precedent).
 *
 * <p>**The null contracts are read off the caller and off the Java's own body.** The three
 * parameters are the caller's own non-null `String`/`int`s, and the return is the real, mutable
 * `HashMap` the Java handed back (its one caller copies out of it). The month is the caller's own
 * 1-based number, so the Java's `month - 1` is carried verbatim - `Calendar` is 0-indexed.
 *
 * <p>**No `@Throws`: nothing on this path throws a checked exception the Java catches.** The
 * original declared none and `XmppConnectionService` catches nothing around it.
 */
object MessageStatisticsStore {

    /**
     * One conversation's live messages in one month, counted per day of that month.
     *
     * @param conversationUuid the conversation whose rows are counted
     * @param year the calendar year the caller asked for
     * @param month the 1-based month the caller asked for, as `ConversationCalendarActivity`'s
     *     `YearMonth.monthValue` supplies it
     */
    @JvmStatic
    fun byDay(
        db: SQLiteDatabase,
        conversationUuid: String,
        year: Int,
        month: Int,
    ): Map<Int, Int> {
        val messagesPerDay = HashMap<Int, Int>()

        // Calculate the start and end timestamps for the given month
        val calendar = Calendar.getInstance()
        calendar.set(year, month - 1, 1, 0, 0, 0) // Month is 0-indexed in Calendar
        calendar.set(Calendar.MILLISECOND, 0)
        val startTimeMillis = calendar.getTimeInMillis()

        calendar.add(Calendar.MONTH, 1)
        calendar.add(Calendar.MILLISECOND, -1)
        val endTimeMillis = calendar.getTimeInMillis()

        val offset = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000

        val sql =
            "SELECT " +
                "CAST(strftime('%d', " +
                Message.TIME_SENT +
                " / 1000 + " +
                offset +
                ", 'unixepoch') AS INTEGER) AS day_of_month, " +
                "COUNT(" +
                Message.UUID +
                ") AS message_count " +
                "FROM " +
                Message.TABLENAME +
                " " +
                "WHERE " +
                Message.CONVERSATION +
                " = ? " +
                "AND " +
                Message.TIME_SENT +
                " >= ? " +
                "AND " +
                Message.TIME_SENT +
                " <= ? " +
                "AND " +
                Message.DELETED +
                " = 0 " +
                "GROUP BY day_of_month " +
                "ORDER BY day_of_month ASC;"

        val selectionArgs =
            arrayOf(conversationUuid, startTimeMillis.toString(), endTimeMillis.toString())

        db.rawQuery(sql, selectionArgs).use { cursor ->
            val dayOfMonthIndex = cursor.getColumnIndex("day_of_month")
            val messageCountIndex = cursor.getColumnIndex("message_count")

            if (dayOfMonthIndex != -1 && messageCountIndex != -1) {
                while (cursor.moveToNext()) {
                    messagesPerDay[cursor.getInt(dayOfMonthIndex)] = cursor.getInt(messageCountIndex)
                }
            }
        }
        return messagesPerDay
    }
}
