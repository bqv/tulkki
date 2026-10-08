package uk.xa0.tulkki.data.delivery

import androidx.room.Dao
import androidx.room.Query

/**
 * `reliable-delivery/`'s own DAO (S5-3): the six receipt-bookkeeping columns of `messages` and the
 * select that answers which rows are still owed a send.
 *
 * <p>No `@Insert` and no `@Update`: a row's existence is `messages/`'s, and every statement here is
 * one column of one row. [DeliveryQueries] says why, and what the held / owed select is.
 *
 * <p>The SQL is spelled here and again in [DeliveryQueries]; `DeliveryDaoTest` keeps the two equal.
 */
@Dao
internal interface DeliveryDao {

    /** The send state: unqueued, waiting or acknowledged. */
    @Query("UPDATE messages SET status = :status WHERE uuid = :uuid")
    fun setStatus(uuid: String, status: Long): Int

    /** The id the server gave the stanza. */
    @Query("UPDATE messages SET serverMsgId = :serverMsgId WHERE uuid = :uuid")
    fun setServerMsgId(uuid: String, serverMsgId: String?): Int

    /** The id the peer's own client gave it. */
    @Query("UPDATE messages SET remoteMsgId = :remoteMsgId WHERE uuid = :uuid")
    fun setRemoteMsgId(uuid: String, remoteMsgId: String?): Int

    /** Whether the row arrived as a carbon of the owner's own message. */
    @Query("UPDATE messages SET carbon = :carbon WHERE uuid = :uuid")
    fun setCarbon(uuid: String, carbon: Long): Int

    /** The edit's replacing body, or `null` when the row has not been edited. */
    @Query("UPDATE messages SET edited = :edited WHERE uuid = :uuid")
    fun setEdited(uuid: String, edited: String?): Int

    /** The id of the message this row retracts. */
    @Query("UPDATE messages SET retractId = :retractId WHERE uuid = :uuid")
    fun setRetractId(uuid: String, retractId: String?): Int

    /** The held / owed select: every row that is not yet on the wire, oldest first. */
    @Query(
        "SELECT uuid FROM messages WHERE status IN (:unsend, :waiting) " +
            "ORDER BY timeSent ASC"
    )
    fun notYetOnTheWire(unsend: Long, waiting: Long): List<String>
}
