package uk.xa0.tulkki.xmpp.services

import android.util.Log
import uk.xa0.tulkki.parser.AbstractParser
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.R
import uk.xa0.tulkki.xmpp.forms.Data
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.BookmarkRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.ConversationalRef
import uk.xa0.tulkki.xmpp.refs.MessageRef
import uk.xa0.tulkki.xmpp.refs.MucOptionsRef
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace

/**
 * Tulkki: onboarding, displayed-message synchronisation and the bookmarks that arrive at connect -
 * `requestEasyOnboardingInvite`, `fetchBookmarks`, `fetchBookmarks2`,
 * `fetchMessageDisplayedSynchronization`, the MDS and bookmark processing and the read-up-to pair -
 * lifted out of `XmppConnectionService`.
 *
 * The chunk owns no field and widens no visibility: every coupling is a **public** service member
 * (`getIqGenerator`, `sendIqPacket`, `find`, `markRead`, `joinMuc`, `getString`, the public static
 * `dataStatics()`), C32's moved `ConversationArchival.archiveConversation` is reused rather than the
 * service's private two-argument one, and C39's private `checkMucRequiresRename(ConversationRef)` is
 * reached through the `MucMembership` home the same way. The Java's two private helpers move whole:
 * `isDismissNotification` keeps its odd **original-message** status read, and
 * `processModifiedBookmark(BookmarkRef, boolean)` stays a private Java delegation because the public
 * one-argument entry point (chunk `C72`) still calls it.
 */
object MdsBookmarks {

    @JvmStatic
    fun requestEasyOnboardingInvite(
        service: XmppConnectionService,
        account: AccountRef,
        callback: OnboardingInviteHook,
    ) {
        val connection = account.getXmppConnection()
        val jid: Jid? =
            connection?.getJidForCommand(Namespace.EASY_ONBOARDING_INVITE)
        if (jid == null) {
            callback.inviteRequestFailed(
                service.getString(R.string.server_does_not_support_easy_onboarding_invites),
            )
            return
        }
        val request = Iq(Iq.Type.SET)
        request.setTo(jid)
        val command = request.addChild("command", Namespace.COMMANDS)
        command.setAttribute("node", Namespace.EASY_ONBOARDING_INVITE)
        command.setAttribute("action", "execute")
        service.sendIqPacket(account, request) { response ->
            if (response.getType() == Iq.Type.RESULT) {
                val resultCommand = response.findChild("command", Namespace.COMMANDS)
                val x = resultCommand?.findChild("x", Namespace.DATA)
                if (x != null) {
                    val data =
                        Data.parse(x) ?: throw NullPointerException("Data.parse returned null")
                    val uri = data.getValue("uri")
                    val landingUrl = data.getValue("landing-url")
                    if (uri != null) {
                        callback.inviteRequested(jid.getDomain().toString(), uri, landingUrl)
                        return@sendIqPacket
                    }
                }
                callback.inviteRequestFailed(service.getString(R.string.unable_to_parse_invite))
                Log.d(Config.LOGTAG, response.toString())
            } else if (response.getType() == Iq.Type.ERROR) {
                callback.inviteRequestFailed(AbstractParser.errorMessage(response))
            } else {
                callback.inviteRequestFailed(service.getString(R.string.remote_server_timeout))
            }
        }
    }

    @JvmStatic
    fun fetchBookmarks(service: XmppConnectionService, account: AccountRef) {
        val iqPacket = Iq(Iq.Type.GET)
        val query = iqPacket.query("jabber:iq:private")
        query.addChild("storage", Namespace.BOOKMARKS)
        service.sendIqPacket(account, iqPacket) { response ->
            if (response.getType() == Iq.Type.RESULT) {
                val query1 = response.query()
                val storage = query1.findChild("storage", "storage:bookmarks")
                val bookmarks =
                    XmppConnectionService.dataStatics()
                        .parseBookmarksFromStorage(
                            storage ?: throw NullPointerException("no bookmark storage"),
                            account)
                processBookmarksInitial(service, account, bookmarks, false)
            } else {
                Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid().toString() + ": could not fetch bookmarks",
                )
            }
        }
    }

    @JvmStatic
    fun fetchBookmarks2(service: XmppConnectionService, account: AccountRef) {
        val retrieve = service.getIqGenerator().retrieveBookmarks()
        service.sendIqPacket(account, retrieve) { response ->
            if (response.getType() == Iq.Type.RESULT) {
                val pubsub = response.findChild("pubsub", Namespace.PUBSUB)
                val bookmarks =
                    XmppConnectionService.dataStatics()
                        .parseBookmarksFromPubSub(
                            pubsub ?: throw NullPointerException("no bookmark pubsub result"),
                            account)
                processBookmarksInitial(service, account, bookmarks, true)
            }
        }
    }

    @JvmStatic
    fun fetchMessageDisplayedSynchronization(service: XmppConnectionService, account: AccountRef) {
        Log.d(Config.LOGTAG, account.getJid().toString() + ": retrieve mds")
        val retrieve = service.getIqGenerator().retrieveMds()
        service.sendIqPacket(account, retrieve) { response ->
            if (response.getType() != Iq.Type.RESULT) {
                return@sendIqPacket
            }
            val pubSub = response.findChild("pubsub", Namespace.PUBSUB)
            val items = pubSub?.findChild("items")
            if (items == null ||
                Namespace.MDS_DISPLAYED != items.getAttribute("node")
            ) {
                return@sendIqPacket
            }
            for (child in items.getChildren()) {
                if ("item" == child.getName()) {
                    processMdsItem(service, account, child)
                }
            }
        }
    }

    @JvmStatic
    fun processMdsItem(service: XmppConnectionService, accountRef: AccountRef, item: Element?) {
        if (item == null) {
            return
        }
        val jid = Jid.Invalid.getNullForInvalid(item.getAttributeAsJid("id"))
        if (jid == null) {
            return
        }
        val displayed = item.findChild("displayed", Namespace.MDS_DISPLAYED)
        val stanzaId = displayed?.findChild("stanza-id", Namespace.STANZA_IDS)
        val id = stanzaId?.getAttribute("id")
        val conversation = service.find(accountRef, jid)
        if (id != null && conversation != null) {
            conversation.setDisplayState(id)
            markReadUpToStanzaId(service, conversation, id)
        }
    }

    @JvmStatic
    fun markReadUpToStanzaId(
        service: XmppConnectionService,
        conversation: ConversationRef,
        stanzaId: String,
    ) {
        val message = conversation.findMessageWithServerMsgId(stanzaId)
        if (message == null) { // do we want to check if isRead?
            return
        }
        markReadUpTo(service, conversation, message)
    }

    @JvmStatic
    fun markReadUpTo(
        service: XmppConnectionService,
        conversation: ConversationRef,
        message: MessageRef,
    ) {
        val isDismissNotification = isDismissNotification(message)
        val uuid = message.getUuid()
        Log.d(
            Config.LOGTAG,
            (conversation.getAccount()
                    ?: throw NullPointerException("conversation has no account"))
                .getJid().asBareJid().toString() +
                ": mark " +
                (conversation.getJid() ?: throw NullPointerException("conversation has no jid"))
                    .asBareJid() +
                " as read up to " +
                uuid,
        )
        service.markRead(conversation, uuid, isDismissNotification)
    }

    private fun isDismissNotification(message: MessageRef): Boolean {
        var next = message.next()
        while (next != null) {
            if (message.getStatus() == MessageRef.STATUS_RECEIVED) {
                return false
            }
            next = next.next()
        }
        return true
    }

    @JvmStatic
    fun processBookmarksInitial(
        service: XmppConnectionService,
        accountRef: AccountRef,
        bookmarks: Map<Jid, BookmarkRef>,
        pep: Boolean,
    ) {
        val previousBookmarks = accountRef.getBookmarkedJids()
        for (bookmark in bookmarks.values) {
            previousBookmarks.remove(bookmark.getJid().asBareJid())
            processModifiedBookmark(service, bookmark, pep)
        }
        if (pep) {
            processDeletedBookmarks(service, accountRef, previousBookmarks)
        }
        accountRef.replaceBookmarks(bookmarks)
    }

    @JvmStatic
    fun processDeletedBookmarks(
        service: XmppConnectionService,
        accountRef: AccountRef,
        bookmarks: Collection<Jid>,
    ) {
        Log.d(
            Config.LOGTAG,
            accountRef.getJid().asBareJid().toString() +
                ": " +
                bookmarks.size +
                " bookmarks have been removed",
        )
        for (bookmark in bookmarks) {
            processDeletedBookmark(service, accountRef, bookmark)
        }
    }

    @JvmStatic
    fun processDeletedBookmark(
        service: XmppConnectionService,
        accountRef: AccountRef,
        jid: Jid,
    ) {
        val conversation = service.find(accountRef, jid)
        if (conversation != null &&
            conversation.getMucOptions().error() == MucOptionsRef.ErrorRef.DESTROYED
        ) {
            Log.d(
                Config.LOGTAG,
                accountRef.getJid().asBareJid().toString() +
                    ": archiving destroyed conference (" +
                    conversation.getJid() +
                    ") after receiving pep",
            )
            ConversationArchival.archiveConversation(service, conversation, false)
        }
    }

    @JvmStatic
    fun processModifiedBookmark(
        service: XmppConnectionService,
        bookmark: BookmarkRef,
        pep: Boolean,
    ) {
        val account = bookmark.getAccount()
        var conversation = service.find(bookmark)
        if (conversation != null) {
            if (conversation.getMode() != ConversationalRef.MODE_MULTI) {
                return
            }
            bookmark.setConversation(conversation)
            if (pep && !bookmark.autojoin()) {
                Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid().toString() +
                        ": archiving conference (" +
                        conversation.getJid() +
                        ") after receiving pep",
                )
                ConversationArchival.archiveConversation(service, conversation, false)
            } else {
                val mucOptions = conversation.getMucOptions()
                if (mucOptions.error() == MucOptionsRef.ErrorRef.NICK_IN_USE) {
                    val current = mucOptions.getActualNick()
                    val proposed = mucOptions.getProposedNickPure()
                    if (current != null && !current.equals(proposed)) {
                        Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid().toString() +
                                ": proposed nick changed after bookmark push " +
                                current +
                                "->" +
                                proposed,
                        )
                        service.joinMuc(conversation)
                    }
                } else {
                    MucMembership.checkMucRequiresRename(
                        service,
                        conversation,
                        service.getPresenceGenerator(),
                    )
                }
            }
        } else if (bookmark.autojoin()) {
            conversation =
                service.findOrCreateConversation(
                    account,
                    bookmark.getFullJid()
                        ?: throw NullPointerException("bookmark has no full jid"),
                    true,
                    true,
                    false,
                )
            bookmark.setConversation(conversation)
        }
    }
}
