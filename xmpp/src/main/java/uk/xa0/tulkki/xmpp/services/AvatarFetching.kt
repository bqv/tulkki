package uk.xa0.tulkki.xmpp.services

import android.util.Log
import uk.xa0.tulkki.parser.IqParser
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xmpp.pep.Avatar
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ContactRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.ConversationalRef
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace
import java.util.function.BiFunction
import java.util.function.Consumer

/**
 * Tulkki: fetching an avatar - the PEP-first, vCard-fallback fetch - lifted out of
 * `XmppConnectionService`.
 *
 * The chunk's two fields move **whole** here: nothing outside these methods ever read
 * `mInProgressAvatarFetches` or `mOmittedPepAvatarFetches`, so the in-progress suppression lives in
 * this object and the Java keeps only the seven one-line delegations for its public surface. The
 * monitor is the same object the Java synchronized on (the set itself), so a second in-progress fetch
 * still answers nothing rather than queueing.
 *
 * Every other reach is a public service member - `getIqGenerator()`, `getFileBackend()`,
 * `getAvatarService()`, `sendIqPacket`, `syncRoster`, `find`, `presenceToMuc`, `updateConversationUi`,
 * `updateAccountUi`, `updateRosterUi`, `updateMucRosterUi` and the public `databaseBackend` slot.
 * C05's **private static** `generateFetchKey` travels in as a `BiFunction`, so its visibility stays
 * private; the private list `conversationList` (C29) travels in as a `List`, and C54's private
 * `mDefaultIqHandler` as a `Consumer<Iq>` for `deleteContactOnServer`.
 *
 * Nullability is read off the Java: the two-argument `fetchAvatar` really passes `null`, so the
 * callback is nullable everywhere it is guarded; `checkForAvatar` dereferences it, so its callback is
 * non-null; `fetchVcard4` guards it, so it is nullable. `deleteContactOnServer` dereferences the
 * account's connection unchecked, kept as `!!` so the throw happens where Java threw.
 */
object AvatarFetching {

    private val mInProgressAvatarFetches = HashSet<String>()
    private val mOmittedPepAvatarFetches = HashSet<String>()

    @JvmStatic
    fun cancelAvatarFetches(account: AccountRef) {
        synchronized(mInProgressAvatarFetches) {
            val iterator = mInProgressAvatarFetches.iterator()
            while (iterator.hasNext()) {
                val KEY = iterator.next()
                if (KEY.startsWith(account.getJid().asBareJid().toString() + "_")) {
                    iterator.remove()
                }
            }
        }
    }

    @JvmStatic
    fun fetchAvatar(
        service: XmppConnectionService,
        account: AccountRef,
        avatar: Avatar,
        callback: UiCallbackPort<Avatar>?,
        fetchKey: BiFunction<AccountRef, Avatar, String>,
    ) {
        if ((service.databaseBackend ?: throw NullPointerException("database backend is not open")).isBlockedMedia(avatar.cid() ?: throw NullPointerException("avatar has no cid"))) {
            if (callback != null) callback.error(0, null)
            return
        }

        val KEY = fetchKey.apply(account, avatar)
        synchronized(mInProgressAvatarFetches) {
            if (mInProgressAvatarFetches.add(KEY)) {
                when (avatar.origin) {
                    Avatar.Origin.PEP -> {
                        mInProgressAvatarFetches.add(KEY)
                        fetchAvatarPep(service, account, avatar, callback, fetchKey)
                    }
                    Avatar.Origin.VCARD -> {
                        mInProgressAvatarFetches.add(KEY)
                        fetchAvatarVcard(service, account, avatar, callback, fetchKey)
                    }
                }
            } else if (avatar.origin == Avatar.Origin.PEP) {
                mOmittedPepAvatarFetches.add(KEY)
            } else {
                Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid().toString()
                        + ": already fetching "
                        + avatar.origin
                        + " avatar for "
                        + avatar.owner,
                )
            }
        }
    }

    private fun fetchAvatarPep(
        service: XmppConnectionService,
        account: AccountRef,
        avatar: Avatar,
        callback: UiCallbackPort<Avatar>?,
        fetchKey: BiFunction<AccountRef, Avatar, String>,
    ) {
        val packet = service.getIqGenerator().retrievePepAvatar(avatar)
        service.sendIqPacket(
            account,
            packet,
        ) { result ->
            synchronized(mInProgressAvatarFetches) {
                mInProgressAvatarFetches.remove(fetchKey.apply(account, avatar))
            }
            val ERROR =
                "${account.getJid().asBareJid()}: fetching avatar for ${avatar.owner} failed "
            if (result.getType() == Iq.Type.RESULT) {
                avatar.image = IqParser.avatarData(result)
                if (avatar.image != null) {
                    if (service.getFileBackend().save(avatar)) {
                        if (account.getJid().asBareJid() == avatar.owner) {
                            if (account.setAvatar(avatar.getFilename())) {
                                (service.databaseBackend ?: throw NullPointerException("database backend is not open")).updateAccount(account)
                            }
                            service.getAvatarService().clear(account)
                            service.updateConversationUi()
                            service.updateAccountUi()
                        } else {
                            val contact =
                                account.getRoster().getContact(
                                    avatar.owner
                                        ?: throw NullPointerException("avatar has no owner"),
                                )
                            contact.setAvatar(avatar)
                            service.syncRoster(account)
                            service.getAvatarService().clear(contact)
                            service.updateConversationUi()
                            service.updateRosterUi(UpdateRosterReason.AVATAR)
                        }
                        if (callback != null) {
                            callback.success(avatar)
                        }
                        Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid().toString()
                                + ": successfully fetched pep avatar for "
                                + avatar.owner,
                        )
                        return@sendIqPacket
                    }
                } else {
                    Log.d(Config.LOGTAG, ERROR + "(parsing error)")
                }
            } else {
                val error = result.findChild("error")
                if (error == null) {
                    Log.d(Config.LOGTAG, ERROR + "(server error)")
                } else {
                    Log.d(Config.LOGTAG, ERROR + error.toString())
                }
            }
            if (callback != null) {
                callback.error(0, null)
            }
        }
    }

    private fun fetchAvatarVcard(
        service: XmppConnectionService,
        account: AccountRef,
        avatar: Avatar,
        callback: UiCallbackPort<Avatar>?,
        fetchKey: BiFunction<AccountRef, Avatar, String>,
    ) {
        val packet = service.getIqGenerator().retrieveVcardAvatar(avatar)
        service.sendIqPacket(
            account,
            packet,
        ) { response ->
            val previouslyOmittedPepFetch: Boolean
            synchronized(mInProgressAvatarFetches) {
                val KEY = fetchKey.apply(account, avatar)
                mInProgressAvatarFetches.remove(KEY)
                previouslyOmittedPepFetch = mOmittedPepAvatarFetches.remove(KEY)
            }
            if (response.getType() == Iq.Type.RESULT) {
                val vCard = response.findChild("vCard", "vcard-temp")
                val photo = if (vCard != null) vCard.findChild("PHOTO") else null
                val image = if (photo != null) photo.findChildContent("BINVAL") else null
                if (image != null) {
                    avatar.image = image
                    if (service.getFileBackend().save(avatar)) {
                        Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid().toString()
                                + ": successfully fetched vCard avatar for "
                                + avatar.owner
                                + " omittedPep="
                                + previouslyOmittedPepFetch,
                        )
                        if (avatar.owner!!.isBareJid()) {
                            if (account.getJid().asBareJid() == avatar.owner
                                && account.getAvatar() == null
                            ) {
                                Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid().toString()
                                        + ": had no avatar. replacing with vcard",
                                )
                                account.setAvatar(avatar.getFilename())
                                (service.databaseBackend ?: throw NullPointerException("database backend is not open")).updateAccount(account)
                                service.getAvatarService().clear(account)
                                service.updateAccountUi()
                            } else {
                                val contact =
                                    account.getRoster().getContact(
                                        avatar.owner
                                            ?: throw NullPointerException("avatar has no owner"),
                                    )
                                contact.setAvatar(avatar, previouslyOmittedPepFetch)
                                service.syncRoster(account)
                                service.getAvatarService().clear(contact)
                                service.updateRosterUi(UpdateRosterReason.AVATAR)
                            }
                            service.updateConversationUi()
                        } else {
                            val conversation = service.find(account, avatar.owner!!.asBareJid())
                            if (conversation != null
                                && conversation.getMode() == ConversationalRef.MODE_MULTI
                            ) {
                                val user =
                                    conversation.getMucOptions().findUserByFullJid(avatar.owner!!)
                                if (user != null) {
                                    if (user.setAvatar(avatar)) {
                                        service.getAvatarService().clear(user)
                                        service.updateConversationUi()
                                        service.updateMucRosterUi()
                                    }
                                    val realJid = user.getRealJid()
                                    if (realJid != null) {
                                        val contact = account.getRoster().getContact(realJid)
                                        contact.setAvatar(avatar)
                                        service.syncRoster(account)
                                        service.getAvatarService().clear(contact)
                                        service.updateRosterUi(
                                            UpdateRosterReason.AVATAR,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @JvmStatic
    fun checkForAvatar(
        service: XmppConnectionService,
        account: AccountRef,
        callback: UiCallbackPort<Avatar>,
        fetchKey: BiFunction<AccountRef, Avatar, String>,
    ) {
        val packet = service.getIqGenerator().retrieveAvatarMetaData(null)
        service.sendIqPacket(
            account,
            packet,
        ) { response ->
            if (response.getType() == Iq.Type.RESULT) {
                val pubsub =
                    response.findChild("pubsub", "http://jabber.org/protocol/pubsub")
                if (pubsub != null) {
                    val items = pubsub.findChild("items")
                    if (items != null) {
                        val avatar = Avatar.parseMetadata(items)
                        if (avatar != null) {
                            avatar.owner = account.getJid().asBareJid()
                            if (service.getFileBackend().isAvatarCached(avatar)) {
                                if (account.setAvatar(avatar.getFilename())) {
                                    (service.databaseBackend ?: throw NullPointerException("database backend is not open")).updateAccount(account)
                                }
                                service.getAvatarService().clear(account)
                                callback.success(avatar)
                            } else {
                                fetchAvatarPep(service, account, avatar, callback, fetchKey)
                            }
                            return@sendIqPacket
                        }
                    }
                }
            }
            callback.error(0, null)
        }
    }

    @JvmStatic
    @JvmSuppressWildcards
    fun notifyAccountAvatarHasChanged(
        service: XmppConnectionService,
        account: AccountRef,
        conversationList: List<ConversationRef>,
    ) {
        val connection = account.getXmppConnection()
        if (connection != null && connection.getFeatures().bookmarksConversion()) {
            Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid().toString()
                    + ": avatar changed. resending presence to online group chats",
            )
            for (conversation in conversationList) {
                if (conversation.getAccount() === account
                    && conversation.getMode() == ConversationalRef.MODE_MULTI
                ) {
                    service.presenceToMuc(conversation)
                }
            }
        }
    }

    @JvmStatic
    fun fetchVcard4(
        service: XmppConnectionService,
        account: AccountRef,
        contact: ContactRef,
        callback: Consumer<Element?>?,
    ) {
        val packet = service.getIqGenerator().retrieveVcard4(contact.getJid())
        service.sendIqPacket(
            account,
            packet,
        ) { result ->
            if (result.getType() == Iq.Type.RESULT) {
                val item = IqParser.getItem(result)
                if (item != null) {
                    val vcard4 = item.findChild("vcard", Namespace.VCARD4)
                    if (vcard4 != null) {
                        callback?.accept(vcard4)
                        return@sendIqPacket
                    }
                }
            } else {
                val error = result.findChild("error")
                if (error == null) {
                    Log.d(Config.LOGTAG, "fetchVcard4 (server error)")
                } else {
                    Log.d(Config.LOGTAG, "fetchVcard4 " + error.toString())
                }
            }
            callback?.accept(null)
        }
    }

    @JvmStatic
    fun deleteContactOnServer(contact: ContactRef, defaultIqHandler: Consumer<Iq>) {
        contact.resetOption(ContactRef.OptionsRef.PREEMPTIVE_GRANT)
        contact.resetOption(ContactRef.OptionsRef.DIRTY_PUSH)
        contact.setOption(ContactRef.OptionsRef.DIRTY_DELETE)
        val account = contact.getAccount()
        if (account.getStatusRef() == AccountRef.StateRef.ONLINE) {
            val iq = Iq(Iq.Type.SET)
            val item = iq.query(Namespace.ROSTER).addChild("item")
            item.setAttribute("jid", contact.getJid())
            item.setAttribute("subscription", "remove")
            account.getXmppConnection()!!.sendIqPacket(iq, defaultIqHandler)
        }
    }
}
