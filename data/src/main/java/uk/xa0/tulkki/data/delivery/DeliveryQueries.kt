package uk.xa0.tulkki.data.delivery

import uk.xa0.tulkki.data.model.Message

/**
 * `reliable-delivery/`'s statements (S5-3, the `reliable-delivery/` capability).
 *
 * <p>Six columns of `messages` and no table. `docs/MIGRATION.md`, "Design: the data layer" §2.5
 * calls this package `reliable-delivery/`; the Kotlin package is `delivery` because a package
 * declaration cannot carry a hyphen, and the *decision* the package owns is delivery bookkeeping.
 *
 * <p>**Why these are targeted `UPDATE`s and not whole-row writes.** Every one of them is written by
 * a receipt or an acknowledgement arriving on the socket, minutes after the row was created, from a
 * parser that holds an id and a value - not the row. A whole-row write there would need a read first
 * and would restate the body, the translation state and the transfer columns from a cursor that may
 * be stale; each statement here moves exactly the column the stanza named.
 *
 * <p>**The "held / owed" select.** §2.5 names one; what this schema can answer is the set of
 * outgoing rows that are not yet on the wire - `status` UNSEND (1) or WAITING (5), the two states
 * `Conversation` already treats as unsent - ordered oldest first so a replay owes the earliest
 * first. `timeSent` is nullable and SQLite sorts NULLs first, which is the right end for a row with
 * no timestamp.
 */
internal object DeliveryQueries {

    const val TABLE = Message.TABLENAME
    const val UUID = Message.UUID
    const val STATUS = Message.STATUS
    const val SERVER_MSG_ID = Message.SERVER_MSG_ID
    const val REMOTE_MSG_ID = Message.REMOTE_MSG_ID
    const val CARBON = Message.CARBON
    const val EDITED = Message.EDITED
    const val RETRACT_ID = Message.RETRACT_ID
    const val TIME_SENT = Message.TIME_SENT

    /** `status`'s two not-yet-on-the-wire values, named for the select below. */
    const val STATUS_UNSEND = Message.STATUS_UNSEND
    const val STATUS_WAITING = Message.STATUS_WAITING

    /** The send state: unqueued, waiting or acknowledged, and nothing else. */
    const val SET_STATUS = "UPDATE " + TABLE + " SET " + STATUS + " = :status WHERE " + UUID + " = :uuid"

    /** The id the server gave the stanza, which is what a later receipt will name. */
    const val SET_SERVER_MSG_ID =
        "UPDATE " + TABLE + " SET " + SERVER_MSG_ID + " = :serverMsgId WHERE " + UUID + " = :uuid"

    /** The id the peer's own client gave it: a carbon's or a MAM echo's own key. */
    const val SET_REMOTE_MSG_ID =
        "UPDATE " + TABLE + " SET " + REMOTE_MSG_ID + " = :remoteMsgId WHERE " + UUID + " = :uuid"

    /** Whether the row arrived as a carbon of the owner's own message. */
    const val SET_CARBON = "UPDATE " + TABLE + " SET " + CARBON + " = :carbon WHERE " + UUID + " = :uuid"

    /** The edit's replacing body, or `null` when the row has not been edited. */
    const val SET_EDITED = "UPDATE " + TABLE + " SET " + EDITED + " = :edited WHERE " + UUID + " = :uuid"

    /** The id of the message this row retracts, which is how a retraction is drawn. */
    const val SET_RETRACT_ID =
        "UPDATE " + TABLE + " SET " + RETRACT_ID + " = :retractId WHERE " + UUID + " = :uuid"

    /** The held / owed select: every row that is not yet on the wire, oldest first. */
    const val NOT_YET_ON_THE_WIRE =
        "SELECT " +
            UUID +
            " FROM " +
            TABLE +
            " WHERE " +
            STATUS +
            " IN (:unsend, :waiting) ORDER BY " +
            TIME_SENT +
            " ASC"
}
