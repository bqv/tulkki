package uk.xa0.tulkki.xmpp.services

import android.util.Log
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.R
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ContactRef
import uk.xa0.tulkki.libs.StoryRef
import uk.xa0.tulkki.xml.Namespace
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/**
 * Tulkki: the in-memory story cache, its listener registry and the pubsub fetch/retract/cleanup
 * bodies, lifted out of `XmppConnectionService`.
 *
 * **What stayed on the service.** The two scheduled executors the chunk's row names,
 * `storyRetractionExecutor` and `storyCacheExecutor`, are the *schedulers*: C20's
 * `StartupScheduling` schedules the two cleanup passes on them and C22's `Teardown` shuts them
 * down, and neither pass reads its own executor. They stay with C74's fields, so this home names
 * no executor but the database writer it is handed.
 *
 * **The state moved whole, and the singleton assumption is the same one C06 and C65 already make.**
 * The Java held the cache in a per-instance `CopyOnWriteArrayList` and the listeners in a
 * per-instance weak set; both live on this object now, exactly as `MucMuting`'s `mutedMucUsers` and
 * `CommentNotifications`' list do. `getStories()` still answers the **live** list, not a copy — the
 * Java handed out the field itself and `:ui`'s stream reads rely on that.
 *
 * **The monitor is still the service's.** The Java guarded the story set with `synchronized
 * (LISTENER_LOCK)` in all three members and took the fan-out snapshot through `threadSafeList`,
 * which locks that same object; `LISTENER_LOCK` therefore arrives here **by value** and the
 * Kotlin locks the identical monitor, so `checkListeners()`'s read of [listeners] is still excluded
 * from a registration exactly as it was. The snapshot is taken inside the lock and the listeners
 * are called outside it, which is the Java's `for (… : threadSafeList(…))` shape.
 *
 * **Nullability is read off the behaviour, not off the types.** `onStoryReceived`'s and
 * `onStoryRetracted`'s arguments were null-tested by the Java, so they stay nullable and the guard
 * is kept; `getStories()`, the two `fetchStories` entry points, `retractStory` and
 * `cleanupStoryCache` take or answer non-null values the Java dereferenced. `retractStory`'s
 * callback was null-tested on both answers — and `:ui` really does pass nothing on some paths — so
 * it stays nullable, and its type argument is `Void?` because that is how `UiCallbackPort` spells a
 * `null` answer (see [UiCallbackPort]).
 *
 * **One Java idiom is rewritten rather than transcribed.** The cache update's sort was
 * `Collections.sort(stories, (a, b) -> Long.compare(b.getPublished(), a.getPublished()))`; the
 * Kotlin `sortWith(compareByDescending { … })` is the same stable descending sort over the same
 * `long` reads, and nothing else about the update order moved.
 */
object StoryCache {

    /** The Java's `stories` field: a `CopyOnWriteArrayList`, so the sweep removes safely. */
    private val cache = CopyOnWriteArrayList<StoryRef>()

    /** The Java's `mOnStoriesUpdates`: a weak set, so a registration never keeps an activity alive. */
    private val registered: MutableSet<OnStoriesUpdate> =
        Collections.newSetFromMap(WeakHashMap<OnStoriesUpdate, Boolean>())

    /**
     * The live cache, exactly the object the Java field handed out - and **mutable**, because the
     * Java's `List` was. The service's `getStories()` answers it as a `List`, so no reader moved;
     * the one caller that needs the mutable spelling is C27's restore, which merges each persisted
     * story into this same instance and re-sorts it (`ConversationReload.restoreFromDatabase`'s
     * parameter is a `MutableList<StoryRef>` for that reason). Declaring this read-only would have
     * hidden the mutation behind a `List` the Kotlin API still hands to a mutator.
     */
    @JvmStatic
    fun stories(): MutableList<StoryRef> = cache

    /**
     * The weak listener set `checkListeners()` reads. It is the Java's `mOnStoriesUpdates`, so a
     * listener that is only registered here is still weakly held and does not keep an activity
     * alive; the service passes it into `ListenerRegistration` unchanged.
     */
    @JvmStatic
    fun listeners(): MutableSet<OnStoriesUpdate> = registered

    @JvmStatic
    fun onStoryReceived(
        service: XmppConnectionService,
        writer: Executor,
        storyRef: StoryRef?,
        lock: Any,
    ) {
        val story = storyRef ?: return
        writer.execute { (service.databaseBackend ?: throw NullPointerException("database backend is not open")).upsertStory(story) }
        for (i in cache.indices) {
            if (cache[i].getUuid().equals(story.getUuid())) {
                cache[i] = story
                notifyUi(lock)
                return
            }
        }
        cache.add(story)
        cache.sortWith(compareByDescending<StoryRef> { it.getPublished() })
        notifyUi(lock)
    }

    @JvmStatic
    fun setListener(listener: OnStoriesUpdate, lock: Any) {
        synchronized(lock) {
            registered.add(listener)
        }
    }

    @JvmStatic
    fun removeListener(listener: OnStoriesUpdate, lock: Any) {
        synchronized(lock) {
            registered.remove(listener)
        }
    }

    @JvmStatic
    fun updateStoriesUi(lock: Any) {
        notifyUi(lock)
    }

    /**
     * The Java's `for (OnStoriesUpdate listener : threadSafeList(this.mOnStoriesUpdates))`: the
     * copy is taken under the lock, the calls happen outside it, and an empty set answers the
     * `Collections.emptyList()` the Java's `threadSafeList` answered.
     */
    private fun notifyUi(lock: Any) {
        val snapshot: List<OnStoriesUpdate> = synchronized(lock) {
            if (registered.isEmpty()) emptyList() else ArrayList(registered)
        }
        for (listener in snapshot) {
            listener.onStoriesUpdate()
        }
    }

    @JvmStatic
    fun fetchStories(
        service: XmppConnectionService,
        account: AccountRef,
        contact: ContactRef,
        writer: Executor,
        lock: Any,
    ) {
        fetchStoriesForJid(service, account, contact.getJid(), writer, lock)
    }

    @JvmStatic
    fun fetchOwnStories(
        service: XmppConnectionService,
        account: AccountRef,
        writer: Executor,
        lock: Any,
    ) {
        fetchStoriesForJid(service, account, account.getJid().asBareJid(), writer, lock)
    }

    /** The Java's private body; the two public entry points above are all that reaches it. */
    private fun fetchStoriesForJid(
        service: XmppConnectionService,
        account: AccountRef,
        jid: Jid,
        writer: Executor,
        lock: Any,
    ) {
        if (account.getStatusRef() != AccountRef.StateRef.ONLINE) {
            return
        }
        Log.d(Config.LOGTAG, "fetching stories for " + jid)
        val request = service.getIqGenerator().retrieveStories(jid)
        service.sendIqPacket(account, request) { response ->
            if (response.getType() == Iq.Type.RESULT) {
                val pubsub = response.findChild("pubsub", Namespace.PUBSUB)
                if (pubsub != null) {
                    val fetched = XmppConnectionService.dataStatics().parseStories(pubsub, jid)
                    for (story in fetched) {
                        onStoryReceived(service, writer, story, lock)
                    }
                }
            }
        }
    }

    @JvmStatic
    fun retractStory(
        service: XmppConnectionService,
        account: AccountRef,
        storyId: String,
        callback: UiCallbackPort<Void?>?,
        writer: Executor,
        lock: Any,
    ) {
        val iq = service.getIqGenerator().deleteItem(Namespace.PUBSUB_STORIES, storyId)
        iq.setTo(account.getJid().asBareJid())
        service.sendIqPacket(account, iq) { response ->
            if (response.getType() == Iq.Type.RESULT) {
                // The list is a `CopyOnWriteArrayList`, so removing through its own iterator is
                // safe and keeps the Java's "drop every match" behaviour.
                for (s in cache) {
                    if (s.getUuid().equals(storyId)) {
                        cache.remove(s)
                    }
                }
                writer.execute { (service.databaseBackend ?: throw NullPointerException("database backend is not open")).deleteStory(storyId) }
                notifyUi(lock)
                callback?.success(null)
            } else {
                callback?.error(R.string.error_deleting_story, null)
            }
        }
    }

    @JvmStatic
    fun onStoryRetracted(
        service: XmppConnectionService,
        storyId: String?,
        writer: Executor,
        lock: Any,
    ) {
        if (storyId == null) {
            return
        }
        var removed = false
        for (s in cache) {
            if (s.getUuid().equals(storyId)) {
                if (cache.remove(s)) {
                    removed = true
                    break
                }
            }
        }
        writer.execute { (service.databaseBackend ?: throw NullPointerException("database backend is not open")).deleteStory(storyId) }
        if (removed) {
            notifyUi(lock)
            Log.d(Config.LOGTAG, "Retracted story with id: " + storyId)
        }
    }

    @JvmStatic
    fun retractOldStories(
        service: XmppConnectionService,
        writer: Executor,
        lock: Any,
    ) {
        val twentyFourHoursAgo = System.currentTimeMillis() - 86400000
        writer.execute { (service.databaseBackend ?: throw NullPointerException("database backend is not open")).deleteExpiredStories() }
        val onlineAccounts = HashMap<Jid, AccountRef>()
        for (account in XmppConnectionService.dataStatics().accounts().getAccounts()) {
            if (account.isOnlineAndConnected()) {
                onlineAccounts[account.getJid().asBareJid()] = account
            }
        }
        for (story in cache) {
            if (story.getPublished() < twentyFourHoursAgo) {
                val owner = onlineAccounts[story.getContact().asBareJid()]
                if (owner != null) {
                    Log.d(
                        Config.LOGTAG,
                        "Retracting old story from account: " + owner.getJid().asBareJid(),
                    )
                    retractStory(service, owner, story.getUuid() ?: throw NullPointerException(), null, writer, lock)
                } else {
                    // Not own story - just remove from memory; DB already cleaned up above
                    cache.remove(story)
                }
            }
        }
    }

    @JvmStatic
    fun cleanupStoryCache(service: XmppConnectionService) {
        Log.d(Config.LOGTAG, "Cleaning up story cache")
        val storyCacheDir = service.getFileBackend().getStoryCacheDirectory()
        if (storyCacheDir.exists()) {
            val twentyFourHoursAgo = System.currentTimeMillis() - 86400000
            val files = storyCacheDir.listFiles()
            if (files != null) {
                for (file in files) {
                    if (file.lastModified() < twentyFourHoursAgo) {
                        if (file.delete()) {
                            Log.d(Config.LOGTAG, "Deleted old story cache file: " + file.getName())
                        }
                    }
                }
            }
        }
    }
}
