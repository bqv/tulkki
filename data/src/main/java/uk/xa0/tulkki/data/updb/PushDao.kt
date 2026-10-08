package uk.xa0.tulkki.data.updb

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query

/** `push`'s `application`/`instance` pair, as the reads that answer `PushTarget`s select it. */
internal data class TargetRow(
    @ColumnInfo(name = UpdbQueries.APPLICATION) val application: String,
    @ColumnInfo(name = UpdbQueries.INSTANCE) val instance: String,
)

/** `push`'s `application`/`endpoint` pair, as `getEndpoint` selects it. */
internal data class EndpointRow(
    @ColumnInfo(name = UpdbQueries.APPLICATION) val application: String,
    @ColumnInfo(name = UpdbQueries.ENDPOINT) val endpoint: String,
)

/**
 * The push registrations' reads and writes (S5-3, the `updb/` capability).
 *
 * <p>**The SQL is spelled here and nowhere else, and the test reads it back off these annotations.**
 * The tidy alternative - naming a `const val` from [UpdbQueries] inside the annotation - compiles to
 * the same SQL but is not what is written, because a later reader must be able to see the query
 * Room will run without opening another file. `UpdbDaoTest` asserts by reflection that each
 * annotation equals its [UpdbQueries] constant and then executes the annotation's own SQL over the
 * JDBC fixture, so a drift between the two is a red test rather than a surprise.
 *
 * <p>**These statements are the conversion, not a parallel copy of it.** Every public method on
 * [UnifiedPushDatabase] runs one of them, so `register`, `unregister` and the distributor's reads
 * keep the SQL - and therefore the behaviour - they had when they ran the same statements through
 * `ContentValues` and the `query`/`insert`/`update`/`delete` builders. Two of them are not the
 * obvious statement on purpose: `ALL_TARGETS` has no `ORDER BY`, because the legacy read took
 * whichever row SQLite handed it first, and `TARGETS_BY_ACCOUNT` binds the account alone, because
 * the legacy `getPushTargets` accepted a transport and never used it. Both are named where they are
 * declared.
 *
 * <p>Room accepts `void` or `long` for an INSERT `@Query` and rejects anything else, measured in
 * batch 1; a DELETE or UPDATE may answer the changed-row count, which is how `register`'s insert and
 * `delete*` reproduce the legacy answers.
 *
 * <p>`internal`, like every DAO: the module's public surface is what `:app` already calls on
 * [UnifiedPushDatabase] (`docs/MIGRATION.md`, "Design: the data layer" §2.5).
 */
@Dao
internal interface PushDao {

    /** `register`'s existence check: the instance is unique, so this is at most one row. */
    @Query("SELECT application FROM push WHERE instance = :instance")
    fun applicationByInstance(instance: String): String?

    /**
     * The rowid of the inserted row. A constraint violation is *not* swallowed here: the caller
     * catches it, mirroring the legacy `SQLiteDatabase.insert`, which answered `-1` for a
     * duplicate instance instead of throwing out of a `BroadcastReceiver`.
     */
    @Query("INSERT INTO push (application, instance, expiration) VALUES (:application, :instance, 0)")
    fun insert(application: String, instance: String): Long

    /** Everything that is not this account's and transport's current, unexpired registration. */
    @Query(
        "SELECT application, instance FROM push " +
            "WHERE account <> :account OR transport <> :transport OR expiration < :expiration"
    )
    fun renewals(account: String, transport: String, expiration: Long): List<TargetRow>

    /** This exact registration, and only while its endpoint is still inside the renewal window. */
    @Query(
        "SELECT application, endpoint FROM push " +
            "WHERE account = :account AND transport = :transport AND instance = :instance " +
            "AND endpoint IS NOT NULL AND expiration >= :expiration"
    )
    fun endpoint(
        account: String,
        transport: String,
        instance: String,
        expiration: Long,
    ): EndpointRow?

    /** The first row the file hands back - the legacy `deletePushTargets` read exactly one. */
    @Query("SELECT application, instance FROM push")
    fun allTargets(): List<TargetRow>

    /** Every registration, removed: the legacy bulk unregister. */
    @Query("DELETE FROM push")
    fun deleteAll(): Int

    /** 1 when this account and transport have any endpoint at all, 0 otherwise. */
    @Query("SELECT EXISTS(SELECT endpoint FROM push WHERE account = :account AND transport = :transport)")
    fun hasEndpoints(account: String, transport: String): Int

    /** The registration's endpoint as it stands, for `updateEndpoint`'s answer. */
    @Query("SELECT endpoint FROM push WHERE instance = :instance")
    fun endpointByInstance(instance: String): String?

    /** The registration's own row, by instance. */
    @Query(
        "UPDATE push SET account = :account, transport = :transport, endpoint = :endpoint, " +
            "expiration = :expiration WHERE instance = :instance"
    )
    fun updateEndpoint(
        instance: String,
        account: String,
        transport: String,
        endpoint: String,
        expiration: Long,
    ): Int

    /** This account's registrations. It takes the transport the caller passes and ignores it. */
    @Query("SELECT application, instance FROM push WHERE account = :account")
    fun targetsByAccount(account: String): List<TargetRow>

    /** The one registration for an instance: 1 when a row went, 0 when there was none. */
    @Query("DELETE FROM push WHERE instance = :instance")
    fun deleteInstance(instance: String): Int

    /**
     * S5-12: every registration this account owns, removed when the account is deleted.
     *
     * <p>`push` lives in a *second* SQLCipher file, so no `FOREIGN KEY … ON DELETE CASCADE` from
     * `accounts` can reach it: a cross-file cascade does not exist in SQLite, and the two files are
     * separate on purpose (`docs/MIGRATION.md`, "Design: the data layer" §2.6). The account's
     * registrations are therefore removed in the deletion path, which is the one place that knows
     * the account is going, and they are removed at the same moment the `accounts` row is.
     */
    @Query("DELETE FROM push WHERE account = :account")
    fun deleteByAccount(account: String): Int

    /** Every registration an application owns: 1 or more when rows went, 0 when there were none. */
    @Query("DELETE FROM push WHERE application = :application")
    fun deleteApplication(application: String): Int
}
