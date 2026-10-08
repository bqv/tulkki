package uk.xa0.tulkki.xmpp.refs

import com.google.common.collect.Multimap
import uk.xa0.tulkki.libs.FilePathInfoRef
import uk.xa0.tulkki.libs.StoryRef
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.mam.MamReference
import io.ipfs.cid.Cid
import java.io.File
import uk.xa0.tulkki.xmpp.utils.Resolver
import uk.xa0.tulkki.libs.DownloadableFileRef
import uk.xa0.tulkki.libs.ServiceDiscoveryResultRef
import uk.xa0.tulkki.libs.PresenceTemplateRef
import uk.xa0.tulkki.libs.PostRef

/**
 * Tulkki: C5-E5 - the XMPP island's view of `uk.xa0.tulkki.data.DatabaseBackend`, and the last
 * import of C5.
 *
 * `XmppConnectionService` owns a public `databaseBackend` field, and until this slice its
 * declared type was `:data`'s model. That declaration **is** the `:xmpp -> :data` import, so the
 * field becomes this interface and `DatabaseBackend implements DatabaseBackendRef` makes every
 * member below a javac check rather than a grep. The model is the ref: the two `:ui` assignments of
 * the field (`DatabaseBackend.getInstance(...)`, the fresh backend after a key migration) keep
 * compiling unchanged, and the island reads the same object through the narrower name.
 *
 * **Fifty-nine declarations over fifty-five names**, and the audience split does not shrink them:
 * they
 * are dictated by the island's *own bodies*, not by the 68 first-party readers. Nine island files
 * read the field from 121 call sites; XCS alone calls fifty distinct members and the other eight add
 * six. Four names carry two declarations each (`findConversation`, `getMessages`,
 * `markFileAsDeleted`, `updateMessage`).
 *
 * What makes the count affordable is that they are not new: C5-D, E1, E2, E3 and R1 built the
 * ref-typed overloads on the model, so 58 of the 59 are that method already. The one exception is
 * `markFilesAsChanged`, whose parameter is widened **in place** on the model (same erasure). Four
 * returns go covariant over a model return for free (group C); the wildcards are there for the same
 * reason the earlier refs use them - generics are invariant.
 *
 * Three statics the island used to reach by class name (`getInstance`, `closeInstance`,
 * `requiresMessageIndexRebuild`) cannot live here - an interface carries no body and this one may
 * not name the model - so they are `DataStatics` members, added in the same commit that removes the
 * field. The model keeps all three; only the island's *name* for it changed.
 *
 * Nothing is persisted by this file and nothing casts: `DatabaseBackend` is the persistence, and
 * no `ContentValues`, `Cursor` or DTO path moves with the field's type.
 *
 * No two members share a name and an arity with a ref-typed parameter, so the registry slice's
 * ambiguity hazard is not reachable here. If a second ref-typed overload of one of these names is
 * ever added - `getMessages(ConversationalRef, int)` beside `getMessages(ConversationRef, int)`,
 * say - it becomes reachable, because `Conversation` is both. Do not add one.
 */
interface DatabaseBackendRef {

    // -- A. thirty-one declarations the earlier slices already built ref-typed ---------------------
    //
    // The island's readers and writers of accounts, conversations, messages, the roster, the parsers'
    // own results and the posts/stories feed. Every one of these is an existing `DatabaseBackend`
    // method with this signature, or a model return that is covariant for the ref it answers.
    fun updateAccount(account: AccountRef): Boolean

    fun updateMessage(message: MessageRef, includeBody: Boolean): Boolean

    fun updateMessage(message: MessageRef, uuid: String): Boolean

    fun updateConversation(conversation: ConversationRef)

    fun createMessage(message: MessageRef)

    fun createConversation(conversation: ConversationRef)

    fun createAccount(account: AccountRef)

    fun deleteAccount(account: AccountRef): Boolean

    fun createPost(post: PostRef, account: AccountRef)

    fun updateContact(contact: ContactRef): Boolean

    /**
     * S5-12: the owner is a parameter, not something the row's key can imply - the mute is the
     * account's, and the caller that has the participant knows which account it came from.
     */
    fun muteMucUser(user: MucOptionsRef.UserRef, accountUuid: String?): Boolean

    fun unmuteMucUser(user: MucOptionsRef.UserRef, accountUuid: String): Boolean

    /**
     * S5-12: the account a room JID belongs to, as the file records it - the owner of a MUC
     * conversation with that JID. Read by the mute cache when the interface hands it a participant
     * built from a JID alone, which carries no conversation to take the account from.
     */
    fun accountOfMuc(mucJid: String): String?

    fun insertPresenceTemplate(template: PresenceTemplateRef)

    fun insertDiscoveryResult(result: ServiceDiscoveryResultRef)

    fun upsertStory(story: StoryRef)

    fun deleteStory(uuid: String)

    fun readRoster(roster: RosterRef)

    fun writeRoster(roster: RosterRef)

    fun findConversation(account: AccountRef, contactJid: Jid): ConversationRef?

    fun getMessage(conversation: ConversationRef, uuid: String?): MessageRef?

    fun getMessageWithServerMsgId(conversation: ConversationRef, messageId: String): MessageRef?

    fun getMessageWithUuidOrRemoteId(conversation: ConversationRef, messageId: String): MessageRef?

    fun getMessageFuzzyIds(conversation: ConversationRef, ids: Collection<String>): Map<String, MessageRef>

    fun getMessages(conversation: ConversationRef, limit: Int): List<MessageRef>

    fun getMessages(conversation: ConversationRef, limit: Int, timestamp: Long, isForward: Boolean): List<MessageRef>

    fun getMessagesNearUuid(conversation: ConversationRef, limit: Int, uuid: String?): List<MessageRef>?

    fun getExclusiveFilePaths(conversation: ConversationRef): List<String>

    fun getExclusiveFilePath(message: MessageRef): String?

    fun deleteMessagesInConversation(conversation: ConversationRef)

    fun getLastMessageReceived(account: AccountRef): MamReference?

    fun getLastClearDate(account: AccountRef): MamReference

    // -- B. twenty-three declarations already in island or plain JDK vocabulary --------------------
    //
    // `Cid` is external, and `Resolver`/`Resolver.Result` and `MamReference` are the island's own, so
    // these need no ref at all and cost `:data` nothing. Two of them are ref-typed (`findConversation`
    // by uuid) and two return a ref through a covariant model return.
    fun blockMedia(cid: Cid)

    fun clearBlockedMedia()

    fun deleteExpiredStories()

    fun deleteMessage(uuid: String?): Boolean

    fun deletePost(uuid: String)

    fun expireOldMessages(timestamp: Long)

    fun findConversation(uuid: String): ConversationRef?

    fun findDiscoveryResult(hash: String, ver: String): ServiceDiscoveryResultRef?

    fun findResolverResult(domain: String): Resolver.Result?

    fun saveResolverResult(domain: String, result: Resolver.Result)

    fun getConversationList(status: Int): List<ConversationRef>

    fun getExclusiveFilePathsExpiring(historyTimestamp: Long): List<String>

    fun getUrlForCid(cid: Cid): String?

    fun getMessagesCountGroupByDay(conversationUuid: String, year: Int, month: Int): Map<Int, Int>

    fun getNextExpiration(): Long

    fun getRecentOutgoingLiveLocationMessages(accountUuid: String?): List<Array<String>>

    fun isBlockedMedia(cid: Cid): Boolean

    fun isFtsIndexFragmented(): Boolean

    fun loadMutedMucUsers(accountUuid: String): Multimap<String, String>

    fun markFileAsDeleted(file: File, `internal`: Boolean): List<String>

    fun markFileAsDeleted(uuids: List<String>)

    fun rebuildMessagesIndex()

    fun saveCid(cid: Cid, file: File?, url: String?)

    // -- C. four covariant returns over a model return ----------------------------------------------
    //
    // Java's covariant return makes the model's existing method the override, so `:data` pays nothing
    // for every one of these. The same device is what makes group A's `getMessage`/`getMessages`/
    // `getMessageFuzzyIds`/`findConversation` and group B's `findDiscoveryResult`/`getConversationList`
    // free. `getRelativeFilePaths` used to be a fifth; port-13 removed it, because its only caller is
    // `:app`'s `DataStaticsHost.loadAttachments`, which asks the model directly now - and
    // `FilePathRef` went with it.
    fun getFilePathInfo(): List<FilePathInfoRef>

    fun getPresenceTemplates(): List<PresenceTemplateRef>

    fun getStoriesFromDatabase(): List<StoryRef>

    fun getFileForCid(cid: Cid?): DownloadableFileRef?

    // -- D. the one parameter the model had not already made ref-compatible -------------------------
    //
    // `DatabaseBackend` declares `markFilesAsChanged(List<FilePathInfo>)`. The parameter is widened in
    // place on the model - a ref-typed overload would be `name clash ... neither overrides the other`,
    // because the two erase alike.
    fun markFilesAsChanged(files: List<FilePathInfoRef>)
}
