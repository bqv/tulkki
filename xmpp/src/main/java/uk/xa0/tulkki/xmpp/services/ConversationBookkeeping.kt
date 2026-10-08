package uk.xa0.tulkki.xmpp.services

import android.util.Log
import java.io.File
import java.util.Comparator
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.ConversationalRef
import uk.xa0.tulkki.libs.FilePathInfoRef

/**
 * Tulkki: conversation ordering and the file-deletion bookkeeping, lifted out of
 * `XmppConnectionService`.
 *
 * The live `conversationList` **stays on the service**: it is the `CopyOnWriteArrayList` that
 * `deleteAccount`, the scans and the archive restore lock and mutate, so it arrives here by value
 * through `getConversationList()` and the Kotlin walks exactly the list the Java walked. The two
 * members another chunk still binds stay as one-line Java delegations - `markFileDeleted` is the
 * file observer's `Consumer<File>` and `markChangedFiles` is `DeletedFileCheck`'s - while
 * `markUuidsAsDeletedFiles` had no caller outside this chunk and moves whole.
 *
 * The null and lock contracts are the Java's: `markFileDeleted` still takes the **same**
 * `FILENAMES_TO_IGNORE_DELETION` monitor (the field stays on the service, shared with chunk `C21`)
 * and removes the absolute path before the database is asked; the three `populate` overloads take
 * the caller's list as their own, clear it, fill it, and swallow the `IllegalArgumentException` the
 * sort could raise. `MODE_SINGLE` is qualified because Kotlin inherits no constant from an
 * interface.
 */
object ConversationBookkeeping {

    @JvmStatic
    fun markFileDeleted(service: XmppConnectionService, file: File) {
        synchronized(service.FILENAMES_TO_IGNORE_DELETION) {
            if (service.FILENAMES_TO_IGNORE_DELETION.remove(file.getAbsolutePath())) {
                Log.d(Config.LOGTAG, "ignored deletion of " + file.getAbsolutePath())
                return
            }
        }
        val isInternalFile = service.getFileBackend().isInternalFile(file)
        val uuids = (service.databaseBackend ?: throw NullPointerException("database backend is not open")).markFileAsDeleted(file, isInternalFile)
        Log.d(
            Config.LOGTAG,
            "deleted file " +
                file.getAbsolutePath() +
                " internal=" +
                isInternalFile +
                ", database hits=" +
                uuids.size,
        )
        markUuidsAsDeletedFiles(service, uuids)
    }

    @JvmStatic
    fun markChangedFiles(service: XmppConnectionService, infos: List<FilePathInfoRef>) {
        var changed = false
        for (conversation in service.getConversationList()) {
            changed = conversation.markAsChanged(infos) or changed
        }
        if (changed) {
            service.updateConversationUi()
        }
    }

    @JvmStatic
    fun populateWithOrderedConversationList(
        service: XmppConnectionService,
        list: MutableList<ConversationRef>,
    ) {
        populateWithOrderedConversationList(service, list, true, true)
    }

    @JvmStatic
    fun populateWithOrderedConversationList(
        service: XmppConnectionService,
        list: MutableList<ConversationRef>,
        includeNoFileUpload: Boolean,
    ) {
        populateWithOrderedConversationList(service, list, includeNoFileUpload, true)
    }

    @JvmStatic
    fun populateWithOrderedConversationList(
        service: XmppConnectionService,
        list: MutableList<ConversationRef>,
        includeNoFileUpload: Boolean,
        sort: Boolean,
    ) {
        val orderedUuids: MutableList<String>?
        if (sort) {
            orderedUuids = null
        } else {
            orderedUuids = ArrayList()
            for (conversation in list) {
                orderedUuids.add(
                    conversation.getUuid()
                        ?: throw NullPointerException("conversation has no uuid"),
                )
            }
        }
        list.clear()
        if (includeNoFileUpload) {
            list.addAll(service.getConversationList())
        } else {
            for (conversation in service.getConversationList()) {
                if (conversation.getMode() == ConversationalRef.MODE_SINGLE ||
                    ((conversation.getAccount()
                        ?: throw NullPointerException("conversation has no account"))
                        .httpUploadAvailable() &&
                        conversation.getMucOptions().participating())
                ) {
                    list.add(conversation)
                }
            }
        }
        try {
            if (orderedUuids == null) {
                java.util.Collections.sort(
                    list,
                    Comparator { a: ConversationRef, b: ConversationRef -> a.compareTo(b) },
                )
            } else {
                val uuids = orderedUuids
                java.util.Collections.sort(
                    list,
                    Comparator { a: ConversationRef, b: ConversationRef ->
                        val indexA = uuids.indexOf(a.getUuid())
                        val indexB = uuids.indexOf(b.getUuid())
                        if (indexA == -1 || indexB == -1 || indexA == indexB) {
                            a.compareTo(b)
                        } else {
                            indexA - indexB
                        }
                    },
                )
            }
        } catch (e: IllegalArgumentException) {
            // ignore
        }
    }

    private fun markUuidsAsDeletedFiles(service: XmppConnectionService, uuids: List<String>) {
        var deleted = false
        for (conversation in service.getConversationList()) {
            deleted = conversation.markAsDeleted(uuids) or deleted
        }
        for (uuid in uuids) {
            service.evictPreview(uuid)
        }
        if (deleted) {
            service.updateConversationUi()
        }
    }
}
