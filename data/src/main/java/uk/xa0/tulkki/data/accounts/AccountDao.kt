package uk.xa0.tulkki.data.accounts

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * `accounts/`'s own DAO (S5-3): the account CRUD by uuid, which is the whole of the surface §2.5
 * gives this package.
 *
 * <p>It is deliberately thin, and the reason is the table rather than the design: `accounts` is the
 * foreign key's target for almost everything and the roster's version string lives on it (that pair
 * is `roster/`'s, `RosterDao`), so what is left here is one row by key, the ordered list, and the
 * write trio. The SQL is spelled here and again in [AccountQueries]; `AccountDaoTest` keeps the two
 * equal, because naming the constant from the annotation would hide the statement Room runs from a
 * later reader.
 */
@Dao
internal interface AccountDao {

    /** One account by its own key, or `null`. */
    @Query("SELECT * FROM accounts WHERE uuid = :uuid")
    fun byUuid(uuid: String): AccountEntity?

    /** Every account, in the order the owner arranged them. */
    @Query("SELECT * FROM accounts ORDER BY ordering ASC")
    fun all(): List<AccountEntity>

    /** A new row. `REPLACE`, which is what every existing writer does through `ContentValues`. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(account: AccountEntity): Long

    /** The whole row again. */
    @Update fun update(account: AccountEntity)

    /** One account gone, and with it every row the schema's cascades name it in. */
    @Query("DELETE FROM accounts WHERE uuid = :uuid")
    fun deleteByUuid(uuid: String): Int
}
