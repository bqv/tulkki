package uk.xa0.tulkki.xmpp.services

import android.os.Bundle
import android.util.Log
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xmpp.pep.PublishOptions
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.BookmarkRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace
import java.util.function.Consumer

/**
 * Tulkki: bookmark mutation and publication, lifted out of `XmppConnectionService`
 *.
 *
 * The private helpers had no caller outside the chunk and moved whole, so only the three public
 * entry points stay on the service as delegations. The IQ generator is reached through the
 * service's public getter, and the one-shot notification handler, which has no accessor, arrives by
 * hand. The upstream literal `support@conference.monocles.eu` survives byte for byte.
 */
object BookmarkPublication {

    @JvmStatic
    fun ensureBookmarkIsAutoJoin(
        service: XmppConnectionService,
        conversation: ConversationRef,
        defaultIqHandler: Consumer<Iq>,
    ) {
        val account =
            conversation.getAccount() ?: throw NullPointerException("conversation has no account")
        val existingBookmark = conversation.getBookmark()
        if (existingBookmark == null) {
            val bookmark =
                XmppConnectionService.dataStatics()
                    .newBookmark(
                        account,
                        (conversation.getJid()
                            ?: throw NullPointerException("conversation has no jid"))
                            .asBareJid(),
                    )
            bookmark.setAutojoin(true)
            createBookmark(service, account, bookmark, defaultIqHandler)
        } else {
            if (existingBookmark.autojoin()) {
                return
            }
            existingBookmark.setAutojoin(true)
            createBookmark(service, account, existingBookmark, defaultIqHandler)
        }
    }

    @JvmStatic
    fun createBookmark(
        service: XmppConnectionService,
        account: AccountRef,
        bookmark: BookmarkRef,
        defaultIqHandler: Consumer<Iq>,
    ) {
        account.putBookmark(bookmark)
        val connection = account.getXmppConnection()
        if (connection == null) {
            Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid().toString() + ": no connection. ignoring bookmark creation",
            )
        } else if (connection.getFeatures().bookmarks2()) {
            Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid().toString() + ": pushing bookmark via Bookmarks 2",
            )
            val item = service.getIqGenerator().publishBookmarkItem(bookmark)
            pushNodeAndEnforcePublishOptions(
                service,
                account,
                Namespace.BOOKMARKS2,
                item,
                bookmark.getJid().asBareJid().toString(),
                PublishOptions.persistentWhitelistAccessMaxItems(),
            )
        } else if (connection.getFeatures().bookmarksConversion()) {
            pushBookmarksPep(service, account)
        } else {
            pushBookmarksPrivateXml(service, account, defaultIqHandler)
        }
    }

    @JvmStatic
    fun deleteBookmark(
        service: XmppConnectionService,
        account: AccountRef,
        bookmark: BookmarkRef,
        defaultIqHandler: Consumer<Iq>,
    ) {
        if (bookmark.getJid().toString() == "support@conference.monocles.eu") {
            service.getPreferences().edit().putBoolean("monocles_support_bookmark_deleted", true).apply()
        }
        account.removeBookmark(bookmark)
        val connection = account.getXmppConnection()
        if (connection == null) return

        if (connection.getFeatures().bookmarks2()) {
            val request =
                service.getIqGenerator()
                    .deleteItem(Namespace.BOOKMARKS2, bookmark.getJid().asBareJid().toString())
            Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid().toString() + ": removing bookmark via Bookmarks 2",
            )
            service.sendIqPacket(account, request) { response ->
                if (response.getType() == Iq.Type.ERROR) {
                    Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid().toString()
                            + ": unable to delete bookmark "
                            + response.getErrorCondition(),
                    )
                }
            }
        } else if (connection.getFeatures().bookmarksConversion()) {
            pushBookmarksPep(service, account)
        } else {
            pushBookmarksPrivateXml(service, account, defaultIqHandler)
        }
    }

    private fun pushBookmarksPrivateXml(
        service: XmppConnectionService,
        account: AccountRef,
        defaultIqHandler: Consumer<Iq>,
    ) {
        if (!account.areBookmarksLoaded()) return

        Log.d(Config.LOGTAG, account.getJid().asBareJid().toString() + ": pushing bookmarks via private xml")
        val iqPacket = Iq(Iq.Type.SET)
        val query = iqPacket.query("jabber:iq:private")
        val storage = query.addChild("storage", "storage:bookmarks")
        account.addBookmarksTo(storage)
        service.sendIqPacket(account, iqPacket, defaultIqHandler)
    }

    private fun pushBookmarksPep(service: XmppConnectionService, account: AccountRef) {
        if (!account.areBookmarksLoaded()) return

        Log.d(Config.LOGTAG, account.getJid().asBareJid().toString() + ": pushing bookmarks via pep")
        val storage = Element("storage", "storage:bookmarks")
        account.addBookmarksTo(storage)
        pushNodeAndEnforcePublishOptions(
            service,
            account,
            Namespace.BOOKMARKS,
            storage,
            "current",
            PublishOptions.persistentWhitelistAccess(),
        )
    }

    @JvmStatic
    fun pushNodeAndEnforcePublishOptions(
        service: XmppConnectionService,
        account: AccountRef,
        node: String,
        element: Element,
        id: String,
        options: Bundle,
    ) {
        pushNodeAndEnforcePublishOptions(service, account, node, element, id, options, true)
    }

    private fun pushNodeAndEnforcePublishOptions(
        service: XmppConnectionService,
        account: AccountRef,
        node: String,
        element: Element,
        id: String,
        options: Bundle,
        retry: Boolean,
    ) {
        val packet = service.getIqGenerator().publishElement(node, element, id, options)
        service.sendIqPacket(account, packet) { response ->
            if (response.getType() == Iq.Type.RESULT) {
                return@sendIqPacket
            }
            if (retry && PublishOptions.preconditionNotMet(response)) {
                service.pushNodeConfiguration(
                    account,
                    node,
                    options,
                    object : OnConfigurationPushed {
                        override fun onPushSucceeded() {
                            pushNodeAndEnforcePublishOptions(
                                service,
                                account,
                                node,
                                element,
                                id,
                                options,
                                false,
                            )
                        }

                        override fun onPushFailed() {
                            Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid().toString()
                                    + ": unable to push node configuration ("
                                    + node
                                    + ")",
                            )
                        }
                    },
                )
            } else {
                Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid().toString()
                        + ": error publishing "
                        + node
                        + " (retry="
                        + retry
                        + ") "
                        + response,
                )
            }
        }
    }
}
