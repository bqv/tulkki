package uk.xa0.tulkki.xmpp.services

import android.os.SystemClock
import android.util.Log
import com.google.common.collect.ImmutableMap
import com.google.common.collect.Maps
import uk.xa0.tulkki.app.generator.AbstractGenerator
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.MessageRef
import uk.xa0.tulkki.libs.StoryRef
import java.util.Comparator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor

/**
 * Tulkki: restoring conversations from the database, lifted out of `XmppConnectionService`
 *.
 *
 * The live conversation list and the stories list travel in as the same instances the service
 * holds (`===` and `synchronized(it)` kept), and so do the database-reader executor and the latch
 * that releases the restored-from-database gate. The notification port is reached through the
 * service's public getter. `restoreMessages` had no caller outside the chunk and moved whole. The
 * immutability of Guava's `ImmutableMap`/`Maps.uniqueIndex` result is part of the contract.
 */
object ConversationReload {

    @JvmStatic
    fun restoreFromDatabase(
        service: XmppConnectionService,
        conversationList: MutableList<ConversationRef>,
        databaseReaderExecutor: Executor,
        restoredFromDatabaseLatch: CountDownLatch,
        stories: MutableList<StoryRef>,
    ) {
        synchronized(conversationList) {
            val accountLookupTable =
                ImmutableMap.copyOf(
                    Maps.uniqueIndex(
                        XmppConnectionService.dataStatics().accounts().getAccounts(),
                    ) { account: AccountRef -> account.getUuid() },
                )
            Log.d(Config.LOGTAG, "restoring the conversation list...")
            val conversationRestoreStartTime = SystemClock.elapsedRealtime()
            conversationList.addAll(
                (service.databaseBackend ?: throw NullPointerException("database backend is not open")).getConversationList(ConversationRef.STATUS_AVAILABLE),
            )
            val iterator = conversationList.listIterator()
            while (iterator.hasNext()) {
                val conversation = iterator.next()
                val account = accountLookupTable.get(conversation.getAccountUuid())
                if (account != null) {
                    conversation.setAccount(account)
                } else {
                    Log.e(Config.LOGTAG, "unable to restore conversation with " + conversation.getJid())
                    conversationList.remove(conversation)
                }
            }
            val conversationRestoreDuration = SystemClock.elapsedRealtime() - conversationRestoreStartTime
            Log.d(Config.LOGTAG, "finished restoring the conversation list in " + conversationRestoreDuration + "ms")
            databaseReaderExecutor.execute {
                if (XmppConnectionService.dataStatics().requiresMessageIndexRebuild() ||
                    (service.databaseBackend ?: throw NullPointerException("database backend is not open")).isFtsIndexFragmented()
                ) {
                    (service.databaseBackend ?: throw NullPointerException("database backend is not open")).rebuildMessagesIndex()
                }
                // S5-12: the mute list is per account, and the cache's key carries the owner; the
                // reload moved to `MucMuting` (chunk C06), which keeps the map private.
                MucMuting.reloadMutedMucUsers(service)
                val deletionDate = service.getAutomaticMessageDeletionDate()
                MessageExpiry.noteExpiryRun()
                if (deletionDate > 0) {
                    Log.d(
                        Config.LOGTAG,
                        "deleting messages that are older than "
                            + AbstractGenerator.getTimestamp(deletionDate),
                    )
                    (service.databaseBackend ?: throw NullPointerException("database backend is not open")).expireOldMessages(deletionDate)
                }
                Log.d(Config.LOGTAG, "restoring roster...")
                for (account in XmppConnectionService.dataStatics().accounts().getAccounts()) {
                    (service.databaseBackend ?: throw NullPointerException("database backend is not open")).readRoster(account.getRoster())
                    // roster needs to be loaded at this stage
                    account.initAccountServices(service)
                }
                service.getDrawableCache().evictAll()
                service.loadPhoneContacts()
                Log.d(Config.LOGTAG, "restoring messages...")
                val startMessageRestore = SystemClock.elapsedRealtime()
                // Tulkki: the port takes `List<? extends ConversationalRef>` because generics are
                // invariant and the island's own list is `List<ConversationRef>`; the cast back is
                // identity-safe - the object returned is one of that list's elements, so it is a
                // `ConversationRef`.
                val quickLoad =
                    XmppConnectionService.dataStatics().quickLoad(conversationList) as ConversationRef?
                if (quickLoad != null) {
                    restoreMessages(service, quickLoad)
                    service.updateConversationUi()
                    val diffMessageRestore = SystemClock.elapsedRealtime() - startMessageRestore
                    Log.d(
                        Config.LOGTAG,
                        "quickly restored " + quickLoad.getName() + " after " + diffMessageRestore + "ms",
                    )
                }
                for (conversation in conversationList) {
                    if (quickLoad !== conversation) {
                        restoreMessages(service, conversation)
                    }
                }
                service.getNotificationService().finishBacklog()
                restoredFromDatabaseLatch.countDown()
                val diffMessageRestore = SystemClock.elapsedRealtime() - startMessageRestore
                Log.d(Config.LOGTAG, "finished restoring messages in " + diffMessageRestore + "ms")
                service.updateConversationUi()
                Log.d(Config.LOGTAG, "restoring stories...")
                // Tulkki: 3.7 pair 9, part 15 - the database answers the model's `List<Story>`, so
                // the island's local takes the wildcard (generics are invariant) exactly as
                // `getWithSystemAccounts` does above.
                val persistedStories = (service.databaseBackend ?: throw NullPointerException("database backend is not open")).getStoriesFromDatabase()
                for (story in persistedStories) {
                    var found = false
                    for (i in stories.indices) {
                        if (stories[i].getUuid() == story.getUuid()) {
                            found = true
                            break
                        }
                    }
                    if (!found) {
                        stories.add(story)
                    }
                }
                java.util.Collections.sort(
                    stories,
                    Comparator { a: StoryRef, b: StoryRef ->
                        java.lang.Long.compare(b.getPublished(), a.getPublished())
                    },
                )
                Log.d(
                    Config.LOGTAG,
                    "restored " + persistedStories.size + " stories from database",
                )
                service.updateStoriesUi()
            }
        }
    }

    private fun restoreMessages(service: XmppConnectionService, conversation: ConversationRef) {
        conversation.addAll(
            0,
            (service.databaseBackend ?: throw NullPointerException("database backend is not open")).getMessages(conversation, Config.PAGE_SIZE),
            false,
        )
        conversation.findUnsentTextMessages(
            object : ConversationRef.OnMessageFound {
                override fun onMessageFound(message: MessageRef) {
                    service.markMessage(message, MessageRef.STATUS_WAITING)
                }
            },
        )
        conversation.findMessagesAndCallsToNotify(
            object : ConversationRef.OnMessageFound {
                override fun onMessageFound(message: MessageRef) {
                    service.getNotificationService().pushFromBacklog(message)
                }
            },
        )
    }
}
