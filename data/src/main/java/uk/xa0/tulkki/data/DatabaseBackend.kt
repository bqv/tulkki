package uk.xa0.tulkki.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseUtils
import android.util.Log
import androidx.annotation.Nullable
import androidx.preference.PreferenceManager
import com.google.common.collect.Multimap
import io.ipfs.cid.Cid
import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import uk.xa0.tulkki.crypto.axolotl.FingerprintStatus
import uk.xa0.tulkki.crypto.axolotl.SQLiteAxolotlStore
import uk.xa0.tulkki.data.accounts.AccountStore
import uk.xa0.tulkki.data.discovery.DiscoveryStore
import uk.xa0.tulkki.data.expiry.MessageExpiryStore
import uk.xa0.tulkki.data.filepaths.FilePath
import uk.xa0.tulkki.data.filepaths.FilePathInfo
import uk.xa0.tulkki.data.filepaths.FilePathStore
import uk.xa0.tulkki.data.frequent.FrequentContactStore
import uk.xa0.tulkki.data.messages.ConversationStore
import uk.xa0.tulkki.data.messages.MessageIndexStore
import uk.xa0.tulkki.data.messages.MessageStore
import uk.xa0.tulkki.data.messages.SearchQuery
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Contact
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.DownloadableFile
import uk.xa0.tulkki.data.model.FrequentContact
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.data.model.PresenceTemplate
import uk.xa0.tulkki.data.model.Post
import uk.xa0.tulkki.data.model.Roster
import uk.xa0.tulkki.data.model.ServiceDiscoveryResult
import uk.xa0.tulkki.data.model.Story
import uk.xa0.tulkki.data.muted.MutedParticipantStore
import uk.xa0.tulkki.data.omemo.OmemoStore
import uk.xa0.tulkki.data.pinned.PinnedMessageStore
import uk.xa0.tulkki.data.posts.PostStore
import uk.xa0.tulkki.data.posts.StoryStore
import uk.xa0.tulkki.data.presence.PresenceTemplateStore
import uk.xa0.tulkki.data.roster.RosterStore
import uk.xa0.tulkki.data.schema.FreshInstallRepair
import uk.xa0.tulkki.data.schema.InstanceCloser
import uk.xa0.tulkki.data.schema.RekeyMigration
import uk.xa0.tulkki.data.schema.Schema80
import uk.xa0.tulkki.data.schema.SchemaExec
import uk.xa0.tulkki.data.schema.SchemaExecs
import uk.xa0.tulkki.data.statistics.MessageStatisticsStore
import uk.xa0.tulkki.data.sync.MamWatermarkStore
import uk.xa0.tulkki.data.updb.UnifiedPushDatabase
import uk.xa0.tulkki.data.upload.UploadStore
import uk.xa0.tulkki.data.utils.FileHelper
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.mam.MamReference
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ContactRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.DatabaseBackendRef
import uk.xa0.tulkki.libs.FilePathInfoRef
import uk.xa0.tulkki.xmpp.refs.MessageRef
import uk.xa0.tulkki.xmpp.refs.MucOptionsRef
import uk.xa0.tulkki.libs.PostRef
import uk.xa0.tulkki.libs.PresenceTemplateRef
import uk.xa0.tulkki.xmpp.refs.RosterRef
import uk.xa0.tulkki.libs.ServiceDiscoveryResultRef
import uk.xa0.tulkki.libs.StoryRef
import uk.xa0.tulkki.xmpp.utils.EncryptionException
import uk.xa0.tulkki.xmpp.utils.Resolver
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.cert.X509Certificate
import java.util.Arrays
import java.util.concurrent.CopyOnWriteArrayList
import org.whispersystems.libsignal.IdentityKey
import org.whispersystems.libsignal.IdentityKeyPair
import org.whispersystems.libsignal.SignalProtocolAddress
import org.whispersystems.libsignal.state.PreKeyRecord
import org.whispersystems.libsignal.state.SessionRecord
import org.whispersystems.libsignal.state.SignedPreKeyRecord

/**
 * The connection, and the readers and writers over it - and, since S5-5, nothing else.
 *
 * <p>It no longer opens the file and no longer declares its schema. S5-1 took the opener away (the
 * handle is handed in by [getInstance], which asks `HistoryDatabase` for the
 * one Room-owned connection), and S5-5 took the schema half: `createSchema`, `onCreate`,
 * `onUpgrade`, `onConfigure`, the 74 version guards and every `CREATE` they
 * carried are gone, and the statements a fresh install still needs live in
 * `uk.xa0.tulkki.data.schema.RawTables`. What is left here is what the name says: the
 * connection, the ~3,200 lines of statement-building and cursor-reading over it, and the key
 * derivation the opener is handed.
 *
 * <p>It keeps [columnExists] because live readers use it, and it keeps
 * `requiresMessageIndexRebuild`/`rebuildMessagesIndex` as one-line delegations to
 * `MessageIndexStore` because `ImportBackupWorker` calls the rebuild after an import.
 * The FTS index's own machinery and the search statement live in `messages/` now. The class
 * itself goes when the last reader does; until then it has exactly one owner of the file and it is
 * not this one.
 *
 * <p><b>Ported from Java here, and the decisions are recorded rather than inherited:</b>
 *
 * 1. **The statics are a `companion object`, every one of them `@JvmStatic`, and the name is the
 *    Java's.** A helper `object` or a top-level function would rename them to `DatabaseBackendKt`
 *    and break the 39 code callers. The seven fields are `@JvmField`/`const val`/private `var`
 *    members of the companion, which is the only shape that emits a static field on *this* class -
 *    `javap` shows `ARGON2_DATABASE_HOOK`, `DATABASE_NAME`, `DATABASE_VERSION`,
 *    `REKEY_MIGRATION_IN_PROGRESS` and `instance` where the Java had them.
 * 2. **The five package-private statics are `internal` plus `@JvmName`.** `internal` alone would
 *    mangle the JVM name (`getKeyBytes$data`) and Java could not see it; the `@JvmName` restores
 *    the plain name, which is the one thing a Java caller resolves. `FreshInstallRepairTest` and
 *    `MessageIndexRebuildTest` are Java and call four of them, `HistoryDatabase.kt` calls
 *    `getKeyBytes`. **The two package-private fields (`DATABASE_NAME`, `DATABASE_VERSION`) cannot
 *    be kept package-private at all** - Kotlin has no package-private field and `@JvmField` cannot
 *    be non-public - so they are public; the Java callers keep resolving, and that is the one
 *    visibility widening this port pays.
 * 3. **`get()` stays declared non-null and returns the field unchecked** ([asJavaReturn]). The Java
 *    returned `null` until `getInstance` had run, and every caller compiled against that platform
 *    type: ~35 dereference it immediately (`DatabaseBackend.get().updateAccount(...)`) and
 *    `ConversationListActivity` null-checks it. A nullable declaration would break all of them, and
 *    `!!`/`?: throw` would replace the Java's `null` with an NPE the Java never threw.
 * 4. **Every return follows the destination store's own nullability, and every Java collection
 *    return is the mutable Kotlin view.** Where the store answers `X?` (`MessageStore.getMessage`,
 *    `UploadStore.fileForCid`, the OMEMO readers, ...) this answers `X?`, which is what the Java's
 *    platform return meant and what the concrete callers already accept (`CryptoStore` declares
 *    `SessionRecord?`, `PinnedMessageRepository` passes the uuid on as nullable). A Java
 *    `List`/`Set`/`Map` return was a `(Mutable)List<...>!` to Kotlin callers, and one of them uses
 *    it: `CallsActivity`/`PostsActivity`/`StoriesActivity` call `.stream()` on `getPosts()`, which a
 *    read-only `List` does not have - so the collection returns are `MutableList`/`MutableSet`/
 *    `MutableMap`, with an unchecked cast where the store hands back a read-only type. Two generic
 *    returns also keep the Java's own element type - [getKnownSignalAddresses] and
 *    [getRecentOutgoingLiveLocationMessages] - because the store's element type is nullable
 *    (`String?`) where the Java promised `String`.
 * 5. **A `static synchronized` becomes an explicit `synchronized(DatabaseBackend::class.java)`
 *    block**, not `@Synchronized`: Java's static lock is the *Class* object, and `@Synchronized`
 *    on a companion member locks the Companion. The instance-level `synchronized` on
 *    [findResolverResult] stays `@Synchronized`, which locks `this` exactly as Java did.
 * 6. **The class and its members are Kotlin's `final`** where the Java's were not. Nothing in the
 *    tree extends or overrides `DatabaseBackend` (`git grep 'extends DatabaseBackend'` is empty),
 *    which is the check the `FilePath`/`FilePathInfo` episode taught; `javap` shows the added
 *    `ACC_FINAL` and no caller can see it.
 */
class DatabaseBackend private constructor(
    context: Context,
    private val database: SQLiteDatabase,
) : DatabaseBackendRef {

    @JvmField
    protected var context: Context? = context

    /** The connection Room owns. Every one of the ~100 readers below funnels through here. */
    fun getWritableDatabase(): SQLiteDatabase = database

    fun getReadableDatabase(): SQLiteDatabase = database

    fun close() {
        HistoryDatabase.closeInstance()
    }

    /**
     * Checks if a specific column exists in the given table.
     *
     * @param db        The SQLiteDatabase instance.
     * @param tableName The name of the table to check.
     * @param columnName The name of the column to check.
     * @return true if the column exists, false otherwise.
     */
    private fun columnExists(db: SQLiteDatabase, tableName: String, columnName: String): Boolean {
        var cursor: Cursor? = null
        try {
            // Querying with a limit of 0 is an efficient way to get column metadata
            cursor = db.query(tableName, null, null, null, null, null, null, "0")
            if (cursor != null) {
                return cursor.getColumnIndex(columnName) != -1
            }
        } catch (e: Exception) {
            // Log the exception if necessary
            Log.e("DBHelper", "Error checking if column exists: " + e.message)
        } finally {
            cursor?.close()
        }
        return false
    }

    // The instance half of the FTS delegation pair the class comment names: `ImportBackupWorker`
    // and `SecuritySettingsFragment` call the rebuild through the concrete backend, the island
    // through the ref. Byte for byte the Java's.
    override fun rebuildMessagesIndex() {
        MessageIndexStore.rebuildMessagesIndex(getWritableDatabase())
    }

    override fun isFtsIndexFragmented(): Boolean =
        MessageIndexStore.isFtsIndexFragmented(getReadableDatabase())

    // Tulkki (port-32): the media/CID cache and the blocked-media set are `upload/`'s now
    // (`UploadStore`), the third capability taken out of this class. These seven are the one
    // delegating step that keeps the surface `XmppConnectionService` calls - `getFileForCid`,
    // `getUrlForCid`, both `saveCid` overloads, `blockMedia`, `isBlockedMedia`,
    // `clearBlockedMedia` - byte for byte where it was; they go, and their call sites move,
    // when that service is retyped.
    override fun getFileForCid(cid: Cid?): DownloadableFile? =
        UploadStore.fileForCid(getReadableDatabase(), cid)

    override fun getUrlForCid(cid: Cid): String? = UploadStore.urlForCid(getReadableDatabase(), cid)

    fun saveCid(cid: Cid, file: File?) {
        saveCid(cid, file, null)
    }

    override fun saveCid(cid: Cid, file: File?, url: String?) {
        UploadStore.saveCid(getWritableDatabase(), cid, file, url)
    }

    override fun blockMedia(cid: Cid) {
        UploadStore.blockMedia(getWritableDatabase(), cid)
    }

    override fun isBlockedMedia(cid: Cid): Boolean =
        UploadStore.isBlockedMedia(getReadableDatabase(), cid)

    override fun clearBlockedMedia() {
        UploadStore.clearBlockedMedia(getWritableDatabase())
    }

    // Tulkki (port-45): the mute list is `muted/`'s now (`MutedParticipantStore`), the next
    // capability taken out of this class. These four are the one delegating step that keeps
    // the surface `XmppConnectionService` calls - `loadMutedMucUsers`, `muteMucUser`,
    // `accountOfMuc`, `unmuteMucUser` - byte for byte where it was; they go, and their
    // call sites move, when the mute cache is retyped.
    override fun loadMutedMucUsers(accountUuid: String): Multimap<String, String> =
        MutedParticipantStore.forAccount(getReadableDatabase(), accountUuid)

    override fun muteMucUser(user: MucOptionsRef.UserRef, accountUuid: String?): Boolean =
        MutedParticipantStore.mute(getWritableDatabase(), user, accountUuid)

    override fun accountOfMuc(mucJid: String): String? =
        MutedParticipantStore.accountOf(getReadableDatabase(), mucJid)

    override fun unmuteMucUser(user: MucOptionsRef.UserRef, accountUuid: String): Boolean =
        MutedParticipantStore.unmute(getWritableDatabase(), user, accountUuid)

    // Tulkki (port-45): the conversation row's writer and readers are `messages/`'s now
    // (`ConversationStore`). These eight are the one delegating step that keeps
    // `XmppConnectionService`, `MessageParser`, `PresenceParser`, `ExportBackupWorker`,
    // `ShareWithActivity`, the `:ui` screens' `DatabaseBackend.get()` calls and
    // `PinnedMessageRepository` byte for byte where they were; they go, and their call sites
    // move, when those callers are retyped. The seven `ConversationRef` adapters that cast and
    // delegate stay below, because they are glue rather than capability.
    fun createConversation(conversation: Conversation) {
        ConversationStore.create(getWritableDatabase(), conversation)
    }

    // Tulkki (port-45): the message row's own writer and readers are `messages/`'s now
    // (`MessageStore`), the next capability taken out of this class. These sixteen are the one
    // delegating step that keeps `XmppConnectionService`, `MessageParser`,
    // `ConversationCalendarActivity.kt`, `CallsFragment`, `HttpDownloadConnection`,
    // `ExportBackupWorker`, both jingle classes, `MessageContacts`, `ConversationPaging` and
    // `ConversationHistory` byte for byte where they were; they go, and their call sites move,
    // when those callers are retyped. The eleven `MessageRef`/`ConversationRef` adapters that cast
    // and delegate stay below, because they are glue rather than capability.
    fun createMessage(message: Message) {
        MessageStore.createMessage(getWritableDatabase(), message)
    }

    /**
     * Tulkki: C5-E1 - the ref-typed adapter. The body reads `Account.getContentValues()`, the
     * model's own table writer, which is not on the ref and should not be (it is a `Cursor`/storage
     * concern), so this is an overload rather than a widening - the same device as
     * [writeRoster]. The object really is the model: the only producer of a value
     * the island passes here is `AccountRegistry.create`.
     */
    override fun createAccount(account: AccountRef) {
        createAccount(account as Account)
    }

    // Tulkki (port-45): the account row's write trio, the ordered read, the enabled JIDs and the
    // deletion are `accounts/`'s now (`AccountStore`). These are the one delegating step that keeps
    // the surface `AccountRegistry`, `XmppConnectionService`, both backup workers and the `:ui`
    // account screens call byte for byte where it was; they go, and their call sites move, when
    // those callers are retyped.
    fun createAccount(account: Account) {
        AccountStore.create(getWritableDatabase(), account)
    }

    // Tulkki (port-45): the discovery cache and the resolver's own results are `discovery/`'s now
    // (`DiscoveryStore`), the next capability taken out of this class. These five are the one
    // delegating step that keeps the surface `XmppConnection` and `XmppConnectionService` call -
    // both `insertDiscoveryResult` overloads, `findDiscoveryResult`, `saveResolverResult`,
    // `findResolverResult` - byte for byte where it was; they go, and their call sites move,
    // when those callers are retyped.
    fun insertDiscoveryResult(result: ServiceDiscoveryResult) {
        DiscoveryStore.save(getWritableDatabase(), result)
    }

    /**
     * Tulkki: 3.7 pair 9, part 14. The island builds a disco result through
     * `XmppConnectionService.DataStatics.newServiceDiscoveryResult` and only ever holds the
     * ref, so the one cast lives here rather than in the island. Object-for-object this is the model:
     * the only producer of a value the island passes here is that factory. `getContentValues()`
     * stays the model's own method and is not part of the ref - this is the model's own table.
     */
    override fun insertDiscoveryResult(result: ServiceDiscoveryResultRef) {
        DiscoveryStore.save(getWritableDatabase(), result as ServiceDiscoveryResult)
    }

    override fun findDiscoveryResult(hash: String, ver: String): ServiceDiscoveryResult? =
        DiscoveryStore.find(getReadableDatabase(), hash, ver)

    override fun saveResolverResult(domain: String, result: Resolver.Result) {
        DiscoveryStore.saveResolver(getWritableDatabase(), domain, result)
    }

    @Synchronized
    override fun findResolverResult(domain: String): Resolver.Result? =
        DiscoveryStore.findResolver(getReadableDatabase(), domain)

    /**
     * Tulkki: 3.7 C5-E2 - the parameter is the island ref; the body works in the model type and casts
     * once, at the top. The cast rests on the declared types rather than on a grep: `:data` owns both
     * the interface and its only implementation, and the value's *source* is `:ui`'s own
     * `new PresenceTemplate(...)` handed to `XmppConnectionService.changeStatus`. The model type is
     * needed for `getContentValues()`, which is a persistence detail and deliberately not on the ref.
     */
    // Tulkki (port-32): the presence-template store is `presence/`'s now
    // (`PresenceTemplateStore`), the second capability taken out of this class. These two
    // are the one delegating step that keeps the surface `XmppConnectionService` calls -
    // `insertPresenceTemplate`, `getPresenceTemplates` - byte for byte where it was; they
    // go, and their call sites move, when that service is retyped.
    override fun insertPresenceTemplate(templateRef: PresenceTemplateRef) {
        PresenceTemplateStore.insert(getWritableDatabase(), templateRef as PresenceTemplate)
    }

    @Suppress("UNCHECKED_CAST")
    override fun getPresenceTemplates(): MutableList<PresenceTemplate> =
        PresenceTemplateStore.all(getReadableDatabase()) as MutableList<PresenceTemplate>

    override fun getConversationList(status: Int): CopyOnWriteArrayList<Conversation> =
        ConversationStore.list(getReadableDatabase(), status)

    fun getMessage(conversation: Conversation, uuid: String?): Message? =
        MessageStore.getMessage(getReadableDatabase(), conversation, uuid)

    fun getMessages(conversationList: Conversation, limit: Int): ArrayList<Message> =
        MessageStore.getMessages(getReadableDatabase(), conversationList, limit)

    @Nullable
    fun getMessagesNearUuid(conversation: Conversation, limit: Int, uuid: String?): ArrayList<Message>? =
        MessageStore.getMessagesNearUuid(getReadableDatabase(), conversation, limit, uuid)

    fun getMessageFuzzyIds(
        conversation: Conversation,
        ids: Collection<String>,
    ): MutableMap<String, Message> =
        MessageStore.getMessageFuzzyIds(getReadableDatabase(), conversation, ids)

    fun getMessages(
        conversation: Conversation,
        limit: Int,
        timestamp: Long,
        uuid: String,
        isForward: Boolean,
    ): ArrayList<Message> =
        MessageStore.getMessages(getReadableDatabase(), conversation, limit, timestamp, uuid, isForward)

    fun getMessages(
        conversation: Conversation,
        limit: Int,
        timestamp: Long,
        isForward: Boolean,
    ): ArrayList<Message> =
        MessageStore.getMessages(getReadableDatabase(), conversation, limit, timestamp, "", isForward)

    // Tulkki (port-45): the file sweep's reads and writes, and the nested FilePath/FilePathInfo
    // pair they answer, are `filepaths/`'s now (`FilePathStore`). The pair moved out of this class
    // in the same commit because `:data`'s FileBackend.convertToAttachments spelled it
    // `DatabaseBackend.FilePath`; its two signatures were retyped to `data/filepaths/FilePath` in
    // the same commit, the alternative the un-nesting rule allows. These are the one delegating
    // step that keeps `XmppConnectionService`, `MessageExpiry`, `ConversationCalendarActivity` and
    // `DataStaticsHost` byte for byte where they were; they go, and their call sites move, when
    // those callers are retyped.
    @Suppress("UNCHECKED_CAST")
    override fun markFileAsDeleted(file: File, internalFile: Boolean): MutableList<String> =
        FilePathStore.markFileAsDeleted(getReadableDatabase(), file, internalFile) as MutableList<String>

    override fun markFileAsDeleted(uuids: List<String>) {
        FilePathStore.markFileAsDeleted(getReadableDatabase(), uuids)
    }

    override fun markFilesAsChanged(files: List<@JvmWildcard FilePathInfoRef>) {
        FilePathStore.markFilesAsChanged(getReadableDatabase(), files)
    }

    @Suppress("UNCHECKED_CAST")
    override fun getFilePathInfo(): MutableList<FilePathInfo> =
        FilePathStore.getFilePathInfo(getReadableDatabase()) as MutableList<FilePathInfo>

    @Suppress("UNCHECKED_CAST")
    fun getRelativeFilePaths(
        account: String?,
        jid: Jid?,
        query: String?,
        limit: Int,
    ): MutableList<FilePath> =
        FilePathStore.getRelativeFilePaths(
            getReadableDatabase(),
            account,
            jid,
            query,
            limit,
        ) as MutableList<FilePath>

    @Suppress("UNCHECKED_CAST")
    fun getRelativeFilePathsForConversationForMonth(
        conversationUuid: String,
        year: Int,
        month: Int,
    ): MutableMap<Int, FilePath> =
        FilePathStore.getRelativeFilePathsForConversationForMonth(
            getReadableDatabase(),
            conversationUuid,
            year,
            month,
        ) as MutableMap<Int, FilePath>

    fun getMessageWithServerMsgId(conversation: Conversation, messageId: String): Message? =
        MessageStore.getMessageWithServerMsgId(getReadableDatabase(), conversation, messageId)

    fun getMessageWithUuidOrRemoteId(conversation: Conversation, messageId: String): Message? =
        MessageStore.getMessageWithUuidOrRemoteId(getReadableDatabase(), conversation, messageId)

    // Tulkki (port-45): the single-contact update is `roster/`'s now (`RosterStore`).
    /**
     * Tulkki: 3.7 pair 9, part 15 - the parameter is the island's ref, because the island's one
     * caller (`XmppConnectionService.updateContact`) holds a `ContactRef` now. The body works in the
     * model type and casts once: `getContentValues()` is storage detail the island has no business
     * naming, and this is `:data`, so the cast costs nothing.
     */
    override fun updateContact(contactRef: ContactRef): Boolean =
        RosterStore.updateContact(getWritableDatabase(), contactRef as Contact)

    override fun findConversation(uuid: String): Conversation? =
        ConversationStore.find(getReadableDatabase(), uuid)

    /**
     * Tulkki: C5-E1 - the parameter is the island's ref now. The body only ever reads
     * `getUuid()`, so this is a plain widening: every existing caller passes a model
     * `Account`, which the ref accepts, and the one new caller
     * (`XmppConnectionService.findOrCreateConversation`) holds a ref.
     */
    override fun findConversation(account: AccountRef, contactJid: Jid): Conversation? =
        ConversationStore.find(getReadableDatabase(), account, contactJid)

    fun findConversationUuid(account: Jid, jid: Jid): String? =
        ConversationStore.findUuid(getReadableDatabase(), account, jid)

    fun updateConversation(conversation: Conversation) {
        ConversationStore.update(getWritableDatabase(), conversation)
    }

    /**
     * Tulkki: the island's overload.
     *
     * <p>3.7 pair 9, part 11. `PresenceParser` reaches the backend through the service's public field
     * and passes the `ConversationRef` it holds; a parameter type is not covariant, so the overload is
     * what keeps the call site compiling without the file naming `:data`. The object really is a
     * `Conversation` (`XmppConnectionService.findOrCreateConversation` returns one), so the cast is
     * identity-safe. The other writers `MessageParser` needs arrive with it in part 12.
     */
    override fun updateConversation(conversation: ConversationRef) {
        updateConversation(conversation as Conversation)
    }

    // ---------------------------------------------------------------------------------------------
    // 3.7 pair 9, part 17: the rest of the conversation readers and writers the island calls with the
    // ref. The same shape and the same reason as the part-11 overload above - a parameter type is not
    // covariant in Java, the object really is a `Conversation`, so each body casts once and delegates,
    // and the model-typed method keeps serving every other caller unchanged.
    // ---------------------------------------------------------------------------------------------

    override fun createConversation(conversation: ConversationRef) {
        createConversation(conversation as Conversation)
    }

    override fun getMessage(conversation: ConversationRef, uuid: String?): Message? =
        getMessage(conversation as Conversation, uuid)

    override fun getMessages(conversationList: ConversationRef, limit: Int): ArrayList<Message> =
        getMessages(conversationList as Conversation, limit)

    override fun getMessagesNearUuid(
        conversation: ConversationRef,
        limit: Int,
        uuid: String?,
    ): ArrayList<Message>? = getMessagesNearUuid(conversation as Conversation, limit, uuid)

    override fun getMessageFuzzyIds(
        conversation: ConversationRef,
        ids: Collection<String>,
    ): MutableMap<String, Message> = getMessageFuzzyIds(conversation as Conversation, ids)

    override fun getMessages(
        conversation: ConversationRef,
        limit: Int,
        timestamp: Long,
        isForward: Boolean,
    ): ArrayList<Message> = getMessages(conversation as Conversation, limit, timestamp, isForward)

    override fun getExclusiveFilePaths(conversation: ConversationRef): MutableList<String> =
        getExclusiveFilePaths(conversation as Conversation)

    override fun deleteMessagesInConversation(conversation: ConversationRef) {
        deleteMessagesInConversation(conversation as Conversation)
    }

    fun getAccountUuidForConversation(conversationUuid: String): String? =
        ConversationStore.accountUuidFor(getReadableDatabase(), conversationUuid)

    @Suppress("UNCHECKED_CAST")
    fun getAccounts(): MutableList<Account> =
        try {
            AccountStore.all(getReadableDatabase()) as MutableList<Account>
        } catch (e: Exception) {
            ArrayList()
        }

    @Suppress("UNCHECKED_CAST")
    fun getAccountJids(enabledOnly: Boolean): MutableList<Jid> =
        AccountStore.jids(getReadableDatabase(), enabledOnly) as MutableList<Jid>

    /**
     * Tulkki: the island's overload for `updateAccount`.
     *
     * <p>3.7 pair 9, the parameter half of cluster (f). The parsers hold an `AccountRef` and write the
     * account back; a ref cannot be an `Account`, so the port signature is the ref and the model
     * adapts here - the brief's §2.2 shape for a parameter-position drag. The erasures differ
     * (`Account` vs `AccountRef`), so unlike `setBookmarks` this overload is legal.
     */
    override fun updateAccount(account: AccountRef): Boolean = updateAccount(account as Account)

    fun updateAccount(account: Account): Boolean = AccountStore.update(getWritableDatabase(), account)

    /**
     * Tulkki: C5-E1 - the parameter is the island's ref now. The body reads only
     * `getUuid()`, so this is a plain widening: the model still satisfies it, and
     * `XmppConnectionService.deleteAccount` holds a ref.
     *
     * <p>S5-12: the `history` file's own rows go with the account through the foreign keys this
     * commit completes (`conversations` -> `messages` -> `translation_queue`, and the account's own
     * `contacts`, OMEMO, sync, `blocked_jids`, `pinned_messages`, `muted_participants`,
     * `webxdc_updates`). One table cannot, and it is not a missing cascade: `updb`'s `push` is a
     * *second SQLCipher file* with its own key material, and SQLite has no cross-file cascade at
     * all. Its registrations are therefore removed by the cleanup handed to `AccountStore.delete`,
     * in the one place that knows the account is going, so nothing is left pointing a broker at an
     * account that no longer exists.
     */
    override fun deleteAccount(account: AccountRef): Boolean =
        AccountStore.delete(
            getWritableDatabase(),
            // The Java handed `account.getUuid()` to what is now a Kotlin `String` parameter, so a
            // null uuid already reached `AccountStore.delete`'s own check and crashed there; the guard
            // names the same crash at the read.
            account.getUuid() ?: throw NullPointerException("account has no uuid"),
            this::clearDeletedAccountPushRegistrations,
        )

    /**
     * Tulkki (port-45): `deleteAccount`'s one out-of-file side effect, handed to
     * `AccountStore.delete` as its `PushRegistrationCleanup`. It lives here rather than in the store
     * because it needs this class's `context`, and the `updb` file is a second SQLCipher database
     * with no cross-file cascade.
     */
    private fun clearDeletedAccountPushRegistrations(accountUuid: String) {
        // Java read the field for the null test and again at the call; the local is the same two
        // reads with the compiler able to see the non-null one.
        val context = this.context ?: return
        try {
            UnifiedPushDatabase.getInstance(context).deleteByAccount(accountUuid)
        } catch (e: RuntimeException) {
            // The history row is already gone and the account's conversations with it. A
            // distributor file that cannot be opened (its key not available in this process)
            // must not turn a completed deletion into a failure: its registrations expire on
            // their own within UnifiedPushDatabase.TIME_TO_RENEW, and the next launch of that
            // account's distributor path is gone with the account.
            Log.w(Config.LOGTAG, "could not clear the deleted account's push registrations", e)
        }
    }

    // -------------------------------------------------------------------------------------------
    // Tulkki: 3.7 pair 9, part 12. `MessageParser` reaches its storage through
    // `XmppConnectionService.databaseBackend` - a public `DatabaseBackend` field - and it holds refs
    // now, so the four methods it calls answer for both static types. Four overloads, each casting
    // once and delegating, because `:data` may name both ends and no caller has to move. A `Message`
    // argument still selects the model overload: a class is strictly more specific than the interface
    // it implements.

    override fun createMessage(message: MessageRef) {
        createMessage(message as Message)
    }

    override fun updateMessage(message: MessageRef, includeBody: Boolean): Boolean =
        updateMessage(message as Message, includeBody)

    override fun getMessageWithServerMsgId(
        conversation: ConversationRef,
        messageId: String,
    ): Message? = getMessageWithServerMsgId(conversation as Conversation, messageId)

    override fun getMessageWithUuidOrRemoteId(
        conversation: ConversationRef,
        messageId: String,
    ): Message? = getMessageWithUuidOrRemoteId(conversation as Conversation, messageId)

    fun updateMessage(message: Message, includeBody: Boolean): Boolean =
        MessageStore.updateMessage(getWritableDatabase(), message, includeBody)

    fun updateMessage(message: Message, uuid: String): Boolean =
        MessageStore.updateMessage(getWritableDatabase(), message, uuid)

    /**
     * Tulkki: 3.7 pair 9, part 16 - the edit path's own two, beside part 12's. `XmppConnectionService`
     * holds a `MessageRef` in `updateMessage(message, uuid)` and in `deleteFileIfUnused`, so both
     * answer for that static type as well and each casts once on its first line. Parameter types
     * `Message` and `MessageRef` do not erase alike, so these are overloads and not clashes.
     */
    override fun updateMessage(message: MessageRef, uuid: String): Boolean =
        updateMessage(message as Message, uuid)

    override fun getExclusiveFilePath(message: MessageRef): String? =
        getExclusiveFilePath(message as Message)

    override fun deleteMessage(uuid: String?): Boolean =
        MessageStore.deleteMessage(getWritableDatabase(), uuid)

    /**
     * Returns a list of relative file paths for messages in the given conversation
     * that are not referenced by any other conversation's messages.
     */
    @Suppress("UNCHECKED_CAST")
    fun getExclusiveFilePaths(conversation: Conversation): MutableList<String> =
        FilePathStore.getExclusiveFilePaths(
            getReadableDatabase(),
            conversation,
        ) as MutableList<String>

    fun getExclusiveFilePath(message: Message): String? =
        FilePathStore.getExclusiveFilePath(getReadableDatabase(), message)

    /**
     * Tulkki: C5-E1 - the ref-typed adapter, and deliberately an **overload** rather than a
     * widening: the body reads `Roster.initContact` and, in [writeRoster], the whole
     * model `Contact` surface, so widening this signature would drag a dozen members onto
     * `RosterRef` for no island caller. An `AccountRef`-holding `XmppConnectionService` reaches it
     * through the same "ref-typed overload that adapts" device as
     * `Account.putBookmark(BookmarkRef)`: `:data` is the one module that may name both ends, so the
     * single cast lives here. A model `Roster` still selects the model overload - it is the more
     * specific of the two.
     */
    override fun readRoster(roster: RosterRef) {
        readRoster(roster as Roster)
    }

    // Tulkki (port-45): the roster's read and write are `roster/`'s now (`RosterStore`), and the
    // write's closing account update is handed in as the store's `AccountWriter`. These are the
    // one delegating step that keeps `XmppConnectionService`'s call sites and the
    // `DatabaseBackendRef` signatures byte for byte where they were.
    fun readRoster(roster: Roster) {
        RosterStore.read(getReadableDatabase(), roster)
    }

    /** Tulkki: C5-E1 - the same ref-typed adapter as [readRoster]. */
    override fun writeRoster(roster: RosterRef) {
        writeRoster(roster as Roster)
    }

    fun writeRoster(roster: Roster) {
        RosterStore.write(getWritableDatabase(), roster, this::updateAccount)
    }

    fun deleteMessagesInConversation(conversation: Conversation) {
        MessageStore.deleteMessagesInConversation(getWritableDatabase(), conversation)
    }

    // Tulkki (port-32): the pinned-message store is `pinned/`'s now
    // (`PinnedMessageStore`), the first capability taken out of this class. These four
    // are the one delegating step that keeps the surface `:ui`'s
    // `PinnedMessageRepository` calls - `pinMessage`, `unpinMessage`,
    // `getPinnedMessages`, `deletePinnedMessage` - byte for byte where it was; they
    // go, and their call sites move, when the repository is retyped.
    fun pinMessage(
        messageUuid: String,
        conversationUuid: String,
        accountUuid: String?,
        body: String?,
        cid: String?,
        timestamp: Long,
    ) {
        PinnedMessageStore.pin(
            getWritableDatabase(),
            messageUuid,
            conversationUuid,
            accountUuid,
            body,
            cid,
            timestamp,
        )
    }

    fun unpinMessage(messageUuid: String) {
        PinnedMessageStore.unpin(getWritableDatabase(), messageUuid)
    }

    fun getPinnedMessages(conversationUuid: String): Cursor =
        PinnedMessageStore.forConversation(getReadableDatabase(), conversationUuid)

    fun deletePinnedMessage(conversationUuid: String, messageUuid: String) {
        PinnedMessageStore.delete(getWritableDatabase(), conversationUuid, messageUuid)
    }

    // Tulkki (port-45): message expiry is `expiry/`'s now (`MessageExpiryStore`).
    // `expireOldMessages` reads the connection's private `columnExists` for the `file_deleted`
    // column, so rather than widen that visibility the store takes the probe as a parameter and
    // this class hands its own method in. These two are the one delegating step that keeps the
    // surface `XmppConnectionService` calls byte for byte where it was; they go, and their call
    // sites move, with the reader.
    override fun expireOldMessages(timestamp: Long) {
        MessageExpiryStore.expire(getWritableDatabase(), timestamp, this::columnExists)
    }

    override fun getNextExpiration(): Long = MessageExpiryStore.nextExpiration(getReadableDatabase())

    @Suppress("UNCHECKED_CAST")
    override fun getExclusiveFilePathsExpiring(historyTimestamp: Long): MutableList<String> =
        FilePathStore.getExclusiveFilePathsExpiring(
            getReadableDatabase(),
            historyTimestamp,
        ) as MutableList<String>

    /**
     * Tulkki: 3.7 C5-D - the parameter is the island's ref, not an adaptor.
     *
     * <p>`MessageArchiveService.catchup` is ref-typed now and asks for this to bound the gap. Its
     * only caller is `MessageArchiveService:138`, which already holds an `AccountRef`; the body reads
     * only `getUuid()`, which the ref declares. So the receiver is widened and there is no casting
     * overload to go ambiguous on a null argument.
     */
    // Tulkki (port-45): the account's history watermark is `sync/`'s now
    // (`MamWatermarkStore`), the package that owns the cursor tables and the seed this value
    // feeds. These three are the one delegating step that keeps the surface `XmppTulkkiHost`,
    // `CryptoStore` and `SyncAnchors`' javadoc call byte for byte where it was; they go, and
    // their call sites move, when those callers are retyped.
    override fun getLastMessageReceived(account: AccountRef): MamReference? =
        MamWatermarkStore.lastMessageReceived(getReadableDatabase(), account)

    fun getLastTimeFingerprintUsed(account: Account, fingerprint: String): Long =
        MamWatermarkStore.lastTimeFingerprintUsed(getReadableDatabase(), account, fingerprint)

    override fun getLastClearDate(account: AccountRef): MamReference =
        MamWatermarkStore.lastClearDate(getReadableDatabase(), account)

    // Tulkki (port-45): the OMEMO/Signal store's four tables are `omemo/`'s now (`OmemoStore`),
    // the biggest of this class's remaining capability groups: forty-three methods over `sessions`,
    // `prekeys`, `signed_prekeys` and `identities`, of which these twenty-nine are the public
    // surface. They are the one delegating step that keeps `:data`'s `CryptoStore`, `:crypto`'s
    // `SQLiteAxolotlStore` and every `PgpStore` caller byte for byte where they were; they go, and
    // their call sites move, when those callers are retyped. The fourteen private helpers the group
    // counted - the three cursors, the five `getIdentityKeyCursor` overloads, the db-taking
    // `getSubDeviceSessions`/`deleteSession`/`loadOwnIdentityKeyPair`/`setIdentityKeyTrust`, the
    // seven-argument `storeIdentityKey` and the uncalled `recreateAxolotlDb` - moved with the
    // bodies they served and are not kept here as second spellings.
    fun loadSession(account: Account, contact: SignalProtocolAddress): SessionRecord? =
        OmemoStore.loadSession(getReadableDatabase(), account, contact)

    @Suppress("UNCHECKED_CAST")
    fun getSubDeviceSessions(account: Account, contact: SignalProtocolAddress): MutableList<Int> =
        OmemoStore.getSubDeviceSessions(
            getReadableDatabase(),
            account,
            contact,
        ) as MutableList<Int>

    // The store answers `MutableList<String?>` (the row's own column is nullable) where the Java
    // promised `List<String>`; the cast is the Java's own unchecked widening, kept so the declared
    // element type does not move under `CryptoStore`.
    @Suppress("UNCHECKED_CAST")
    fun getKnownSignalAddresses(account: Account): MutableList<String> =
        OmemoStore.getKnownSignalAddresses(getReadableDatabase(), account) as MutableList<String>

    fun containsSession(account: Account, contact: SignalProtocolAddress): Boolean =
        OmemoStore.containsSession(getReadableDatabase(), account, contact)

    fun storeSession(account: Account, contact: SignalProtocolAddress, session: SessionRecord) {
        OmemoStore.storeSession(getWritableDatabase(), account, contact, session)
    }

    fun deleteSession(account: Account, contact: SignalProtocolAddress) {
        OmemoStore.deleteSession(getWritableDatabase(), account, contact)
    }

    fun deleteAllSessions(account: Account, contact: SignalProtocolAddress) {
        OmemoStore.deleteAllSessions(getWritableDatabase(), account, contact)
    }

    fun loadPreKey(account: Account, preKeyId: Int): PreKeyRecord? =
        OmemoStore.loadPreKey(getReadableDatabase(), account, preKeyId)

    fun containsPreKey(account: Account, preKeyId: Int): Boolean =
        OmemoStore.containsPreKey(getReadableDatabase(), account, preKeyId)

    fun storePreKey(account: Account, record: PreKeyRecord) {
        OmemoStore.storePreKey(getWritableDatabase(), account, record)
    }

    fun deletePreKey(account: Account, preKeyId: Int): Int =
        OmemoStore.deletePreKey(getWritableDatabase(), account, preKeyId)

    fun loadSignedPreKey(account: Account, signedPreKeyId: Int): SignedPreKeyRecord? =
        OmemoStore.loadSignedPreKey(getReadableDatabase(), account, signedPreKeyId)

    fun loadSignedPreKeys(account: Account): List<SignedPreKeyRecord> =
        OmemoStore.loadSignedPreKeys(getReadableDatabase(), account)

    fun getSignedPreKeysCount(account: Account): Int =
        OmemoStore.getSignedPreKeysCount(getReadableDatabase(), account)

    fun containsSignedPreKey(account: Account, signedPreKeyId: Int): Boolean =
        OmemoStore.containsSignedPreKey(getReadableDatabase(), account, signedPreKeyId)

    fun storeSignedPreKey(account: Account, record: SignedPreKeyRecord) {
        OmemoStore.storeSignedPreKey(getWritableDatabase(), account, record)
    }

    fun deleteSignedPreKey(account: Account, signedPreKeyId: Int) {
        OmemoStore.deleteSignedPreKey(getWritableDatabase(), account, signedPreKeyId)
    }

    fun loadOwnIdentityKeyPair(account: Account): IdentityKeyPair? =
        OmemoStore.loadOwnIdentityKeyPair(getReadableDatabase(), account)

    @Suppress("UNCHECKED_CAST")
    fun loadIdentityKeys(account: Account, name: String): MutableSet<IdentityKey> =
        OmemoStore.loadIdentityKeys(getReadableDatabase(), account, name) as MutableSet<IdentityKey>

    @Suppress("UNCHECKED_CAST")
    fun loadIdentityKeys(
        account: Account,
        name: String,
        status: FingerprintStatus?,
    ): MutableSet<IdentityKey> =
        OmemoStore.loadIdentityKeys(
            getReadableDatabase(),
            account,
            name,
            status,
        ) as MutableSet<IdentityKey>

    fun numTrustedKeys(account: Account, name: String): Long =
        OmemoStore.numTrustedKeys(getReadableDatabase(), account, name)

    fun storePreVerification(
        account: Account,
        name: String,
        fingerprint: String,
        status: FingerprintStatus,
    ) {
        OmemoStore.storePreVerification(
            getWritableDatabase(),
            account,
            name,
            fingerprint,
            status,
        )
    }

    fun getFingerprintStatus(account: Account, fingerprint: String): FingerprintStatus? =
        OmemoStore.getFingerprintStatus(getReadableDatabase(), account, fingerprint)

    fun setIdentityKeyTrust(
        account: Account,
        fingerprint: String,
        fingerprintStatus: FingerprintStatus,
    ): Boolean =
        OmemoStore.setIdentityKeyTrust(
            getWritableDatabase(),
            account,
            fingerprint,
            fingerprintStatus,
        )

    fun setIdentityKeyCertificate(
        account: Account,
        fingerprint: String,
        x509Certificate: X509Certificate,
    ): Boolean =
        OmemoStore.setIdentityKeyCertificate(
            getWritableDatabase(),
            account,
            fingerprint,
            x509Certificate,
        )

    fun getIdentityKeyCertifcate(account: Account, fingerprint: String): X509Certificate? =
        OmemoStore.getIdentityKeyCertifcate(getReadableDatabase(), account, fingerprint)

    fun storeIdentityKey(
        account: Account,
        name: String,
        identityKey: IdentityKey,
        status: FingerprintStatus,
    ) {
        OmemoStore.storeIdentityKey(getWritableDatabase(), account, name, identityKey, status)
    }

    fun storeOwnIdentityKeyPair(account: Account, identityKeyPair: IdentityKeyPair) {
        OmemoStore.storeOwnIdentityKeyPair(getWritableDatabase(), account, identityKeyPair)
    }

    fun wipeAxolotlDb(account: Account) {
        OmemoStore.wipeAxolotlDb(getWritableDatabase(), account)
    }

    // Tulkki (port-45): the frequent-contacts read is `frequent/`'s now
    // (`FrequentContactStore`). This is the one delegating step that keeps the surface `:app`'s
    // `ShortcutService` calls - and the `DatabaseBackend.get()` static it reaches - byte for byte
    // where it was; it goes, and its call site moves, when that service is retyped.
    @Suppress("UNCHECKED_CAST")
    fun getFrequentContacts(days: Int): MutableList<FrequentContact> =
        FrequentContactStore.list(getReadableDatabase(), days) as MutableList<FrequentContact>

    // Tulkki (port-45): the calendar's per-day counts are `statistics/`'s now
    // (`MessageStatisticsStore`). This is the one delegating step that keeps the surface
    // `XmppConnectionService` calls - and `ConversationCalendarActivity`'s call through it -
    // byte for byte where it was; it goes, and its call site moves, when that service is retyped.
    @Suppress("UNCHECKED_CAST")
    override fun getMessagesCountGroupByDay(
        conversationUuid: String,
        year: Int,
        month: Int,
    ): MutableMap<Int, Int> =
        MessageStatisticsStore.byDay(
            getReadableDatabase(),
            conversationUuid,
            year,
            month,
        ) as MutableMap<Int, Int>

    fun getMessagesIterable(conversation: Conversation): Iterable<Message> =
        MessageStore.getMessagesIterable(getReadableDatabase(), conversation)

    // New helper method that accepts an existing database connection
    fun findConversationByUuid(uuid: String, db: SQLiteDatabase): Conversation? =
        ConversationStore.findByUuid(db, uuid)

    fun getMessages(conversation: Conversation, type: Int, limit: Int): ArrayList<Message> =
        MessageStore.getMessages(getReadableDatabase(), conversation, type, limit)

    /**
     * Tulkki: 3.7 C5-D - both parameters are the island's refs, because the island's
     * `XmppConnectionService.onPostReceived` holds them and may not name the model. The body needs
     * the model for `Post.getContentValues(Account)`, so it casts once here - the one place the two
     * meet, and in the module where the concrete type lives. Both callers go through this method:
     * `XmppConnectionService` with refs, `PostsActivity` with the model `Post`/`Account`, which are
     * the refs' sole implementors.
     */
    // Tulkki (port-45): the posts and stories tables are `posts/`'s now (`PostStore`,
    // `StoryStore`). These eight are the one delegating step that keeps the surface
    // `XmppConnectionService` and `:ui`'s posts screens call - `createPost`, `getPosts`,
    // `deletePost`, `clearPosts`, `upsertStory`, `getStoriesFromDatabase`, `deleteStory`,
    // `deleteExpiredStories` - byte for byte where it was; they go, and their call sites move,
    // when those callers are retyped.
    override fun createPost(postRef: PostRef, accountRef: AccountRef) {
        PostStore.create(getWritableDatabase(), postRef as Post, accountRef as Account)
    }

    @Suppress("UNCHECKED_CAST")
    fun getPosts(): MutableList<Post> = PostStore.all(getReadableDatabase()) as MutableList<Post>

    override fun deletePost(uuid: String) {
        PostStore.delete(getWritableDatabase(), uuid)
    }

    fun clearPosts() {
        PostStore.clear(getWritableDatabase())
    }

    /**
     * Tulkki: 3.7 pair 9, part 15 - the parameter is the island's ref, because the island's one
     * caller holds a `StoryRef` now. The body works in the model type and casts once:
     * `getContentValues()` is storage detail, and this is `:data`.
     */
    override fun upsertStory(storyRef: StoryRef) {
        StoryStore.upsert(getWritableDatabase(), storyRef as Story)
    }

    @Suppress("UNCHECKED_CAST")
    override fun getStoriesFromDatabase(): MutableList<Story> =
        StoryStore.all(getReadableDatabase()) as MutableList<Story>

    override fun deleteStory(uuid: String) {
        StoryStore.delete(getWritableDatabase(), uuid)
    }

    override fun deleteExpiredStories() {
        StoryStore.deleteExpired(getWritableDatabase())
    }

    // Returns [conversationUuid, rawPayloads] for sent messages with live-location payloads
    // sent within the last 8 hours for the given account.
    // The store answers `MutableList<Array<String?>>`; the cast is the Java's own unchecked
    // widening to `List<String[]>`, kept so the declared element type does not move under the
    // island's `getRecentOutgoingLiveLocationMessages` callers.
    @Suppress("UNCHECKED_CAST")
    override fun getRecentOutgoingLiveLocationMessages(
        accountUuid: String?,
    ): MutableList<Array<String>> =
        MessageStore.getRecentOutgoingLiveLocationMessages(
            getReadableDatabase(),
            accountUuid,
        ) as MutableList<Array<String>>

    companion object {

        // Hook for all encrypted databases. The key is always a pre-derived raw 256-bit key
        // formatted as x'<64 hex chars>'. SQLCipher recognises the x'...' prefix and skips all
        // KDF processing. preKey is a no-op; postKey applies security-hardening PRAGMAs.
        // `SQLiteDatabaseHook` is an interface, so this is an object *implementing* it (the Java
        // wrote `new SQLiteDatabaseHook() { ... }`, an anonymous implementation).
        @JvmField
        val ARGON2_DATABASE_HOOK: SQLiteDatabaseHook = object : SQLiteDatabaseHook {
            override fun preKey(connection: SQLiteConnection) {
                // Raw key x'...' bypasses KDF - no cipher_kdf_algorithm setting needed.
            }

            override fun postKey(connection: SQLiteConnection) {
                connection.executeRaw("PRAGMA cipher_memory_security = ON;", null, null)
                connection.executeRaw("PRAGMA secure_delete = ON;", null, null)
            }
        }

        /**
         * The one file. Its name is on the never-rename list: a different path is a different app, and
         * the owner's years of conversations are in this one.
         *
         * <p>Package-private in the Java; Kotlin has no package-private field, so this is the one
         * visibility widening the port pays (see the class comment).
         */
        const val DATABASE_NAME = "history"

        /**
         * The schema version of the file. 78 since the `data` stage: 75 was the FTS rebuild over
         * `translated_body` and is already spent, 76 is the sync cursor's tables and the three
         * entity-table rebuilds, 77 is `blocking/`'s own table, and 78 is the day's spend split by
         * origin plus the S5-12 shapes moved out of `onOpen` and into a migration. The Room opener in
         * `HistoryDatabase` declares this same number, and the number itself lives in
         * `Schema80.VERSION` so there is one home for it. Nothing in this class writes it: Room
         * owns the version, and every statement that used to move it moved or went with the schema half
         * in S5-5.
         *
         * <p>`const`, not `@JvmField`: `HistoryDatabase`'s `@Database(version = ...)` needs it as a
         * compile-time constant (KSP reads the annotation argument), and `Schema80.VERSION` is one,
         * so the Java's `static final int` becomes a Kotlin `const val` - the same static field,
         * now carrying the `ConstantValue` the Java field already resolved to.
         */
        const val DATABASE_VERSION = Schema80.VERSION

        private const val REKEY_MIGRATION_IN_PROGRESS = RekeyMigration.REKEY_MIGRATION_IN_PROGRESS

        private var instance: DatabaseBackend? = null

        /**
         * The Java's `null` return, kept as a `null` return. `get()` answered `null` until
         * `getInstance` had run and every caller compiled against that platform type; a non-null
         * declaration keeps those callers compiling, and this unchecked read keeps the value -
         * rather than turning the Java's `null` into an NPE inside the getter. **Interop debt: the
         * one nullability contract this file does not check.**
         */
        @Suppress("UNCHECKED_CAST")
        private fun <T> asJavaReturn(value: Any?): T = value as T

        /**
         * Derives the key bytes that SQLCipher should receive. The DB is always encrypted.
         *
         * <ul>
         *   <li>Argon2id mode (user password): derives via Argon2id + KeyStore HMAC, formats as
         *       x'&lt;64 hex chars&gt;' so SQLCipher uses it as a raw key (no internal KDF).
         *   <li>Auto mode (no user password): reads or generates a 32-byte random key from
         *       hardware-backed DataStore, derives via KeyStore HMAC, formats the same way.
         * </ul>
         */
        @JvmStatic
        @JvmName("getKeyBytes")
        internal fun getKeyBytes(context: Context): ByteArray {
            val appSettings = AppSettings(context)
            if (appSettings.isArgon2idKdf()) {
                // The Java dereferenced this without a check (both here and in the `finally`);
                // Argon2id mode means the password is there, and the explicit throw is the check
                // Kotlin must write rather than a new failure path.
                val password = appSettings.getDatabasePasswordChars()
                    ?: throw NullPointerException(
                        "Argon2id KDF active but the database password is missing",
                    )
                try {
                    val salt = appSettings.getArgon2Salt()
                    if (salt == null) {
                        throw EncryptionException(
                            "Argon2id KDF active but salt is missing — database cannot be opened",
                            null,
                            EncryptionException.Reason.KEYSTORE_ERROR,
                        )
                    }
                    return Argon2KeyDerivation.deriveRawKeyBytes(password, salt)
                } finally {
                    Arrays.fill(password, '\u0000')
                }
            }
            // Auto mode: use or generate a hardware-bound random key.
            val rawAutoKey = appSettings.getOrCreateAutoKey()
            try {
                return Argon2KeyDerivation.deriveAutoRawKeyBytes(rawAutoKey)
            } finally {
                Arrays.fill(rawAutoKey, 0.toByte())
            }
        }

        private fun createFingerprintStatusContentValues(
            trust: FingerprintStatus.Trust,
            active: Boolean,
        ): ContentValues {
            val values = ContentValues()
            values.put(SQLiteAxolotlStore.TRUST, trust.toString())
            values.put(SQLiteAxolotlStore.ACTIVE, if (active) 1 else 0)
            return values
        }

        // Tulkki (port-45): the FTS message index is `messages/`'s now (`MessageIndexStore`). These six
        // are the one delegating step that keeps `XmppConnectionService`, `ImportBackupWorker`,
        // `SecuritySettingsFragment`, `DataStaticsHost`, `SnapshotRepositories` and
        // `MessageIndexRebuildTest` byte for byte where they were; they go, and their call sites move,
        // when those callers are retyped.
        @JvmStatic
        fun requiresMessageIndexRebuild(): Boolean = MessageIndexStore.requiresMessageIndexRebuild()

        @JvmStatic
        @JvmName("rebuildMessagesIndex")
        internal fun rebuildMessagesIndex(exec: SchemaExec) {
            MessageIndexStore.rebuildMessagesIndex(exec)
        }

        @JvmStatic
        @JvmName("indexMustBeRebuilt")
        internal fun indexMustBeRebuilt(exec: SchemaExec): Boolean =
            MessageIndexStore.indexMustBeRebuilt(exec)

        @JvmStatic
        fun getInstance(context: Context): DatabaseBackend =
            synchronized(DatabaseBackend::class.java) {
                if (instance == null) {
                    System.loadLibrary("sqlcipher")
                    recoverFromInterruptedMigration(context)
                    encryptLegacyPlaintextDatabase(context)
                    val handle = HistoryDatabase.get(context)
                    // Before the first reader: a file Room created while the fresh-install callback was not
                    // wired has none of RawTables' tables (see repairMissingFreshInstallSchema). Running it
                    // here is what makes "install over the owner's file" a repair rather than a crash loop.
                    val exec = SchemaExecs.of(handle)
                    if (needsFreshInstallRepair(exec)) {
                        Log.d(
                            Config.LOGTAG,
                            "a file without the fresh-install schema: repairing it before any reader",
                        )
                        repairMissingFreshInstallSchema(exec)
                    }
                    instance = DatabaseBackend(context, handle)
                }
                asJavaReturn(instance)
            }

        // Tulkki (port-45): the fresh-install repair is `schema/`'s now (`FreshInstallRepair`). These
        // two are the one delegating step that keeps `getInstance`'s own body and
        // `FreshInstallRepairTest`'s four cells byte for byte where they were; they go, and their call
        // sites move, when the opener is retyped.
        @JvmStatic
        @JvmName("needsFreshInstallRepair")
        internal fun needsFreshInstallRepair(exec: SchemaExec): Boolean =
            FreshInstallRepair.needsRepair(exec)

        @JvmStatic
        @JvmName("repairMissingFreshInstallSchema")
        internal fun repairMissingFreshInstallSchema(exec: SchemaExec) {
            FreshInstallRepair.repair(exec)
        }

        /**
         * Tulkki: C5-E5 - the model-typed view first-party callers keep, while the island holds
         * [uk.xa0.tulkki.xmpp.refs.DatabaseBackendRef]. This is the one `instance` the class has
         * always had (never a second one), and it is **not** [getInstance]: it does not
         * open, does not derive a key, and answers `null` only until `getInstance` has run.
         *
         * <p>The field the island used to read answers `null` for one main-thread hop longer (it is
         * assigned in `continueAfterDbInit`, after the opening thread posts back), so a first-party
         * reader that ran inside that hop would now see the open handle where it used to see nothing. The
         * affected readers are activities, `CryptoStore` and the message-search task, all of which
         * run long after restore; the receiver's declared type is the only other difference, exactly as
         * `AccountRegistry.get()` is for the account list.
         */
        @JvmStatic
        fun get(): DatabaseBackend = asJavaReturn(instance)

        private fun recoverFromInterruptedMigration(context: Context) {
            if (!PreferenceManager.getDefaultSharedPreferences(context)
                    .getBoolean(REKEY_MIGRATION_IN_PROGRESS, false)
            ) {
                return
            }

            val dbFile = context.getDatabasePath(DATABASE_NAME)
            val backupFile = context.getDatabasePath(DATABASE_NAME + ".bak")
            val tempFile = context.getDatabasePath(DATABASE_NAME + ".tmp")

            Log.w(Config.LOGTAG, "rekey: sentinel set — checking interrupted migration state")

            // State A: no .bak — migration hadn't started or sentinel wasn't cleared after success
            if (!backupFile.exists()) {
                Log.w(Config.LOGTAG, "rekey: no backup found, treating as clean")
                if (tempFile.exists()) tempFile.delete()
                PreferenceManager.getDefaultSharedPreferences(context)
                    .edit().remove(REKEY_MIGRATION_IN_PROGRESS).apply()
                return
            }

            // State B: .bak exists but dbFile is missing - killed between step 1 and step 2
            // Prefs still hold old key (setDatabasePassword hadn't run yet).
            if (!dbFile.exists()) {
                Log.w(Config.LOGTAG, "rekey: dbFile missing, restoring backup")
                if (!backupFile.renameTo(dbFile)) {
                    Log.e(Config.LOGTAG, "rekey: CRITICAL — could not restore backup file")
                }
                if (tempFile.exists()) tempFile.delete()
                PreferenceManager.getDefaultSharedPreferences(context)
                    .edit().remove(REKEY_MIGRATION_IN_PROGRESS).apply()
                return
            }

            // States C/D: both files exist - test dbFile with the current key (Argon2id or auto).
            // getKeyBytes() picks the right derivation based on AppSettings.isArgon2idKdf().
            val currentKeyBytes = try {
                getKeyBytes(context)
            } catch (e: EncryptionException) {
                if (e.reason == EncryptionException.Reason.NEEDS_SESSION_PASSWORD) {
                    Log.w(Config.LOGTAG, "rekey: session password not available, deferring recovery")
                    return
                }
                Log.e(Config.LOGTAG, "rekey: cannot obtain key for recovery", e)
                return
            }

            var dbFileValid = false
            try {
                val test = SQLiteDatabase.openDatabase(
                    dbFile.absolutePath,
                    currentKeyBytes,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                    ARGON2_DATABASE_HOOK,
                )
                test.close()
                dbFileValid = true
            } catch (ignored: Exception) {
                // dbFile not openable with current key
            } finally {
                Arrays.fill(currentKeyBytes, 0.toByte())
            }

            if (dbFileValid) {
                // State D: dbFile valid with current key - migration completed; just clean up .bak
                Log.w(Config.LOGTAG, "rekey: dbFile valid, cleaning up orphaned backup")
                FileHelper.secureDelete(backupFile)
                FileHelper.secureDelete(File(backupFile.absolutePath + "-wal"))
                FileHelper.secureDelete(File(backupFile.absolutePath + "-shm"))
            } else {
                // State C: dbFile has new key but prefs still hold old key (setDatabasePassword
                // hadn't run yet). Restore .bak to undo the migration; data is recoverable.
                Log.w(Config.LOGTAG, "rekey: dbFile invalid with current key, restoring backup")
                FileHelper.secureDelete(dbFile)
                if (!backupFile.renameTo(dbFile)) {
                    Log.e(Config.LOGTAG, "rekey: CRITICAL — could not restore backup file")
                }
            }

            if (tempFile.exists()) tempFile.delete()
            PreferenceManager.getDefaultSharedPreferences(context)
                .edit().remove(REKEY_MIGRATION_IN_PROGRESS).apply()
        }

        /**
         * Called once on first open after an upgrade from a pre-encryption release.
         * If the database file has the SQLite plaintext magic header, exports its content
         * into a fresh SQLCipher-encrypted copy using the same crash-safe rename sequence
         * as migrate(), then stores the new auto key in DataStore.
         *
         * If the process is killed between the file rename and the DataStore write,
         * recoverFromInterruptedMigration() (which runs first on the next start) will find
         * the encrypted dbFile unreadable with the current key, restore the plaintext .bak,
         * and this method will run again on that start - converging safely.
         */
        private fun encryptLegacyPlaintextDatabase(context: Context) {
            val dbFile = context.getDatabasePath(DATABASE_NAME)
            if (!dbFile.exists() || !looksLikePlainSqlite(dbFile)) return

            Log.i(Config.LOGTAG, "rekey: plaintext database from pre-encryption release — encrypting")

            val settings = AppSettings(context)
            val newAutoKey = Argon2KeyDerivation.generateRandomKey()
            val newRawKey = Argon2KeyDerivation.deriveAutoRawKeyBytes(newAutoKey)
            try {
                val tempFile = context.getDatabasePath(DATABASE_NAME + ".tmp")
                if (tempFile.exists() && !tempFile.delete()) {
                    throw IOException("Failed to delete existing temp file")
                }
                if (!tempFile.createNewFile()) {
                    throw IOException("Failed to create temp file")
                }

                // Open with null key - SQLCipher accepts plaintext databases this way.
                val db = SQLiteDatabase.openDatabase(
                    dbFile.absolutePath,
                    null as ByteArray?,
                    null,
                    SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING,
                    null,
                )
                try {
                    val version = db.version
                    val keyStr = String(newRawKey, StandardCharsets.UTF_8)
                    val attachKeySql = "'" + keyStr.replace("'", "''") + "'"
                    db.rawExecSQL(
                        "ATTACH DATABASE " +
                            DatabaseUtils.sqlEscapeString(tempFile.absolutePath) +
                            " AS encrypted KEY " + attachKeySql,
                    )
                    db.rawExecSQL("SELECT sqlcipher_export('encrypted');")
                    db.rawExecSQL("PRAGMA encrypted.user_version = $version")
                    db.rawExecSQL("DETACH DATABASE encrypted;")
                } finally {
                    db.close()
                }

                val backupFile = context.getDatabasePath(DATABASE_NAME + ".bak")
                if (backupFile.exists()) backupFile.delete()

                PreferenceManager.getDefaultSharedPreferences(context)
                    .edit().putBoolean(REKEY_MIGRATION_IN_PROGRESS, true).commit()

                var prefsUpdated = false
                try {
                    if (!dbFile.renameTo(backupFile)) {
                        throw IOException("Failed to rename DB to backup")
                    }
                    if (!tempFile.renameTo(dbFile)) {
                        if (!backupFile.renameTo(dbFile)) {
                            Log.e(
                                Config.LOGTAG,
                                "rekey: CRITICAL — could not roll back legacy encryption",
                            )
                        }
                        throw IOException("Failed to rename temp to DB")
                    }
                    // Key written AFTER rename: crash before here leaves .bak (plaintext) recoverable.
                    settings.writeAutoKey(newAutoKey)
                    settings.setAutoKeyMode()
                    prefsUpdated = true
                    PreferenceManager.getDefaultSharedPreferences(context)
                        .edit().remove(REKEY_MIGRATION_IN_PROGRESS).commit()
                    FileHelper.secureDelete(backupFile)
                    FileHelper.secureDelete(File(backupFile.absolutePath + "-wal"))
                    FileHelper.secureDelete(File(backupFile.absolutePath + "-shm"))
                    FileHelper.secureDelete(File(dbFile.absolutePath + "-wal"))
                    FileHelper.secureDelete(File(dbFile.absolutePath + "-shm"))
                    Log.i(Config.LOGTAG, "rekey: legacy database successfully encrypted")
                } catch (e: Exception) {
                    if (!prefsUpdated) {
                        PreferenceManager.getDefaultSharedPreferences(context)
                            .edit().remove(REKEY_MIGRATION_IN_PROGRESS).apply()
                    }
                    throw e
                }
            } catch (e: Exception) {
                Log.e(Config.LOGTAG, "rekey: failed to encrypt legacy plaintext database", e)
            } finally {
                Arrays.fill(newRawKey, 0.toByte())
                Arrays.fill(newAutoKey, 0.toByte())
            }
        }

        /**
         * True if the file starts with the 16-byte SQLite magic header (i.e. it is not encrypted).
         *
         * <p>Public since S5-3: `updb/`'s `UnifiedPushDatabase` moved out of this package and runs the
         * same pre-encryption check on its own file, and the header test is one spelling rather than two.
         */
        @JvmStatic
        fun looksLikePlainSqlite(file: File): Boolean {
            // "SQLite format 3\0" - the invariant first 16 bytes of every plain SQLite database.
            // SQLCipher-encrypted pages have random-looking bytes here instead.
            val magic = byteArrayOf(
                0x53, 0x51, 0x4c, 0x69, 0x74, 0x65, 0x20, 0x66,
                0x6f, 0x72, 0x6d, 0x61, 0x74, 0x20, 0x33, 0x00,
            )
            try {
                FileInputStream(file).use { fis ->
                    val header = ByteArray(16)
                    return fis.read(header) == 16 && Arrays.equals(header, magic)
                }
            } catch (e: IOException) {
                return false
            }
        }

        @JvmStatic
        fun closeInstance() {
            synchronized(DatabaseBackend::class.java) {
                instance?.let {
                    it.close()
                    instance = null
                }
            }
        }

        // Tulkki (port-45): the one search statement, and the `SearchQuery` it answers, are
        // `messages/`'s now (`MessageIndexStore`, `SearchQuery`). This is the one delegating step that
        // keeps `SnapshotRepositories`, `MessagesDao`, `MessageSearchTask` and the two search tests
        // byte for byte where they were; it goes, and its call sites move, when those callers are
        // retyped.
        @JvmStatic
        fun buildMessageSearchQuery(term: List<String>, uuid: String?): SearchQuery =
            MessageIndexStore.buildMessageSearchQuery(term, uuid)

        // Tulkki (port-45): the re-encryption is `schema/`'s now (`RekeyMigration`). This is the one
        // delegating step that keeps `WelcomeActivity`'s and `SecuritySettingsFragment`'s calls byte for
        // byte where they were; `DatabaseBackend::closeInstance` is the one seam the store cannot take
        // from the class it is leaving, handed in rather than reached for. The `synchronized` stays on
        // this delegation, so the lock still covers the whole swap.
        /**
         * Re-encrypts the database with a new password (Argon2id mode) or reverts to auto-encryption
         * with a fresh random key (auto mode when `newPassword` is null).
         *
         * <ul>
         *   <li>`oldPassword null`: old DB is auto-encrypted - auto key is read from storage.
         *   <li>`newPassword null`: new DB uses auto-encryption - a fresh random key is generated.
         *   <li>Both non-null: password change (Argon2id to Argon2id).
         * </ul>
         *
         * <p>File operations are crash-safe: a sentinel flag is set before any file is moved, and
         * [recoverFromInterruptedMigration] restores a consistent state on the next launch.
         * New key material is kept in memory and written to persistent storage only AFTER the file
         * rename succeeds, ensuring the stored key always matches the DB file on disk.
         */
        @JvmStatic
        @Throws(Exception::class)
        fun migrate(context: Context, oldPassword: CharArray?, newPassword: CharArray?) {
            synchronized(DatabaseBackend::class.java) {
                RekeyMigration.migrate(
                    context,
                    oldPassword,
                    newPassword,
                    DATABASE_NAME,
                    ARGON2_DATABASE_HOOK,
                    InstanceCloser { closeInstance() },
                )
            }
        }
    }
}
