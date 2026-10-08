package uk.xa0.tulkki.xmpp.services

import android.util.Log
import uk.xa0.tulkki.app.generator.AbstractGenerator
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.crypto.OmemoSessionPort
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.parser.AbstractParser
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.BookmarkRef
import uk.xa0.tulkki.xmpp.refs.ContactRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.ConversationalRef
import uk.xa0.tulkki.xmpp.refs.MessageRef
import uk.xa0.tulkki.xmpp.refs.MucOptionsRef
import uk.xa0.tulkki.libs.PresenceRef
import uk.xa0.tulkki.xml.Element
import java.util.function.Consumer

/**
 * Tulkki: the real MUC join, its member fetch and the password, lifted out of
 * `XmppConnectionService`.
 *
 * The chunk owns no field. Everything it reaches is a public service member -
 * `getPresenceGenerator()`, `getIqGenerator()`, `getAvatarService()`, `sendPresencePacket`,
 * `sendIqPacket`, `getMessageArchiveService()`, `createBookmark`, `saveConversationAsBookmark`,
 * `sendUnsentMessages`, `maybeRegisterWithMuc`, `fetchConferenceConfiguration`, `updateConversation`,
 * `updateMucRosterUi`, `updateConversationUi` and the public `databaseBackend` slot - so nothing
 * travels in and no visibility was widened. C36a's `MucJoinEntry` still supplies the service's own
 * `this::joinMuc`, which is why the Java keeps the three-argument form as its one-line delegation.
 *
 * The Java's order is kept: the online/offline split, the leave-before-join presence, the
 * flag-and-reset sequence, the archived-before-IQ-result guard in both callbacks, the
 * `remote-server-not-found` special case, the crypto-target prune through the list iterator, and
 * `providePasswordForMuc`'s bookmark branch. `onConferenceJoined` and `password` stay nullable where
 * the Java tested them; the member fetch keeps the Java's captured `i`/`success` counters, whose
 * `++i` runs on the failing branch too.
 */
object MucJoin {

    @JvmStatic
    fun joinMuc(
        service: XmppConnectionService,
        conversation: ConversationRef,
        onConferenceJoined: OnConferenceJoined?,
        followedInvite: Boolean,
        sender: OutgoingStanzaSender,
    ) {
        val account =
            conversation.getAccount() ?: throw NullPointerException("conversation has no account")
        account.removeConferenceJoinPending(conversation)
        account.removeConferenceLeavePending(conversation)
        if (account.getStatusRef() == AccountRef.StateRef.ONLINE) {
            account.addConferenceJoinInProgress(conversation)
            if (Config.MUC_LEAVE_BEFORE_JOIN) {
                service.sendPresencePacket(
                    account,
                    service.getPresenceGenerator().leave(conversation.getMucOptions()),
                )
            }
            conversation.resetMucOptions()
            if (onConferenceJoined != null) {
                conversation.getMucOptions().flagNoAutoPushConfiguration()
            }
            conversation.setHasMessagesLeftOnServer(false)
            service.fetchConferenceConfiguration(
                conversation,
                object : OnConferenceConfigurationFetched {

                    private fun join(conversation: ConversationRef) {
                        val account =
                            conversation.getAccount()
                                ?: throw NullPointerException("conversation has no account")
                        val mucOptions = conversation.getMucOptions()

                        if (mucOptions.nonanonymous() &&
                            !mucOptions.membersOnly() &&
                            !conversation.getBooleanAttribute("accept_non_anonymous", false)
                        ) {
                            account.removeConferenceJoinInProgress(conversation)
                            mucOptions.setError(MucOptionsRef.ErrorRef.NON_ANONYMOUS)
                            service.updateConversationUi()
                            if (onConferenceJoined != null) {
                                onConferenceJoined.onConferenceJoined(conversation)
                            }
                            return
                        }

                        val joinJid =
                            mucOptions.getSelf().getFullJid()
                                ?: throw NullPointerException("muc self has no full jid")
                        Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid().toString() +
                                ": joining conversation " +
                                joinJid.toString(),
                        )
                        val packet =
                            service
                                .getPresenceGenerator()
                                .selfPresence(
                                    account,
                                    PresenceRef.StatusRef.ONLINE,
                                    mucOptions.nonanonymous() || onConferenceJoined != null,
                                    mucOptions.getSelf().getNick(),
                                )
                        packet.setTo(joinJid)
                        val x = packet.addChild("x", "http://jabber.org/protocol/muc")
                        if (conversation.getMucOptions().getPassword() != null) {
                            x.addChild("password").setContent(mucOptions.getPassword())
                        }

                        if (mucOptions.mamSupport()) {
                            // Use MAM instead of the limited muc history to get history
                            x.addChild("history").setAttribute("maxchars", "0")
                        } else {
                            // Fallback to muc history
                            x.addChild("history")
                                .setAttribute(
                                    "since",
                                    AbstractGenerator.getTimestamp(
                                        conversation
                                            .getLastMessageTransmitted()
                                            .getTimestamp(),
                                    ),
                                )
                        }
                        service.sendPresencePacket(account, packet)
                        if (onConferenceJoined != null) {
                            onConferenceJoined.onConferenceJoined(conversation)
                        }
                        if (joinJid != conversation.getJid()) {
                            conversation.setContactJid(joinJid)
                            (service.databaseBackend ?: throw NullPointerException("database backend is not open")).updateConversation(conversation)
                        }

                        service.maybeRegisterWithMuc(conversation, null)

                        if (mucOptions.mamSupport()) {
                            service.getMessageArchiveService().catchupMUC(conversation)
                        }
                        fetchConferenceMembers(service, conversation)
                        if (mucOptions.isPrivateAndNonAnonymous()) {
                            if (followedInvite) {
                                val bookmark = conversation.getBookmark()
                                if (bookmark != null) {
                                    if (!bookmark.autojoin()) {
                                        bookmark.setAutojoin(true)
                                        service.createBookmark(account, bookmark)
                                    }
                                } else {
                                    service.saveConversationAsBookmark(conversation, null)
                                }
                            }
                        }
                        account.removeConferenceJoinInProgress(conversation)
                        ResendPlumbing.sendUnsentMessages(conversation, sender)
                    }

                    override fun onConferenceConfigurationFetched(conversation: ConversationRef) {
                        if (conversation.getStatus() == ConversationRef.STATUS_ARCHIVED) {
                            Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid().toString() +
                                    ": conversation (" +
                                    conversation.getJid() +
                                    ") got archived before IQ result",
                            )
                            return
                        }
                        join(conversation)
                    }

                    override fun onFetchFailed(
                        conversation: ConversationRef,
                        errorCondition: String?,
                    ) {
                        if (conversation.getStatus() == ConversationRef.STATUS_ARCHIVED) {
                            Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid().toString() +
                                    ": conversation (" +
                                    conversation.getJid() +
                                    ") got archived before IQ result",
                            )
                            return
                        }
                        if ("remote-server-not-found" == errorCondition) {
                            account.removeConferenceJoinInProgress(conversation)
                            conversation
                                .getMucOptions()
                                .setError(MucOptionsRef.ErrorRef.SERVER_NOT_FOUND)
                            service.updateConversationUi()
                        } else {
                            join(conversation)
                            service.fetchConferenceConfiguration(conversation)
                        }
                    }
                },
            )
            service.updateConversationUi()
        } else {
            account.addConferenceJoinPending(conversation)
            conversation.resetMucOptions()
            conversation.setHasMessagesLeftOnServer(false)
            service.updateConversationUi()
        }
    }

    @JvmStatic
    fun fetchConferenceMembers(service: XmppConnectionService, conversation: ConversationRef) {
        val account =
            conversation.getAccount() ?: throw NullPointerException("conversation has no account")
        val axolotlService: OmemoSessionPort =
            account.getOmemoSession() ?: throw NullPointerException("account has no omemo session")
        val affiliations = ArrayList<String>()
        affiliations.add("outcast")
        if (conversation.getMucOptions().isPrivateAndNonAnonymous()) {
            affiliations.addAll(listOf("member", "admin", "owner"))
        }
        val callback =
            object : Consumer<Iq> {

                private var i = 0
                private var success = true

                override fun accept(response: Iq) {
                    val omemoEnabled =
                        conversation.getNextEncryption() == MessageRef.ENCRYPTION_AXOLOTL
                    val query = response.query("http://jabber.org/protocol/muc#admin")
                    if (response.getType() == Iq.Type.RESULT && query != null) {
                        for (child in query.getChildren()) {
                            if ("item" == child.getName()) {
                                // Tulkki: 3.7 C5-E2 - the parse layer hands back the island's ref
                                // and the local is that ref now; setOnline, realJidMatchesAccount,
                                // updateUser and getContact are all on it.
                                val user: MucOptionsRef.UserRef =
                                    AbstractParser.parseItem(conversation, child)
                                user.setOnline(false)
                                if (!user.realJidMatchesAccount()) {
                                    val isNew = conversation.getMucOptions().updateUser(user)
                                    val contact: ContactRef? = user.getContact()
                                    val realJid = user.getRealJid()
                                    if (omemoEnabled &&
                                        isNew &&
                                        realJid != null &&
                                        (contact == null ||
                                            !contact.mutualPresenceSubscription()) &&
                                        axolotlService.hasEmptyDeviceList(realJid)
                                    ) {
                                        axolotlService.fetchDeviceIds(realJid)
                                    }
                                }
                            }
                        }
                    } else {
                        success = false
                        Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid().toString() +
                                ": could not request affiliation " +
                                affiliations.get(i) +
                                " in " +
                                (conversation.getJid()
                                    ?: throw NullPointerException("conversation has no jid"))
                                    .asBareJid(),
                        )
                    }
                    ++i
                    if (i >= affiliations.size) {
                        val mucOptions = conversation.getMucOptions()
                        val members: List<Jid> = mucOptions.getMembers(true)
                        if (success) {
                            // Tulkki: the declaration is still the read-only `List<Jid>` (lane G
                            // widened the other three collection returns, not this one), so the
                            // prune takes its own mutable copy before the iterator removes from it.
                            val cryptoTargets = conversation.getAcceptedCryptoTargets().toMutableList()
                            var changed = false
                            val iterator = cryptoTargets.listIterator()
                            while (iterator.hasNext()) {
                                val jid = iterator.next()
                                if (!members.contains(jid) &&
                                    !members.contains(jid.getDomain())
                                ) {
                                    iterator.remove()
                                    Log.d(
                                        Config.LOGTAG,
                                        account.getJid().asBareJid().toString() +
                                            ": removed " +
                                            jid +
                                            " from crypto targets of " +
                                            conversation.getName(),
                                    )
                                    changed = true
                                }
                            }
                            if (changed) {
                                conversation.setAcceptedCryptoTargets(cryptoTargets)
                                service.updateConversation(conversation)
                            }
                        }
                        service.getAvatarService().clear(mucOptions)
                        service.updateMucRosterUi()
                        service.updateConversationUi()
                    }
                }
            }
        for (affiliation in affiliations) {
            service.sendIqPacket(
                account,
                service.getIqGenerator().queryAffiliation(conversation, affiliation),
                callback,
            )
        }
        Log.d(
            Config.LOGTAG,
            account.getJid().asBareJid().toString() +
                ": fetching members for " +
                conversation.getName(),
        )
    }

    @JvmStatic
    fun providePasswordForMuc(
        service: XmppConnectionService,
        conversation: ConversationRef,
        password: String?,
    ) {
        if (conversation.getMode() == ConversationalRef.MODE_MULTI) {
            conversation.getMucOptions().setPassword(password)
            val bookmark: BookmarkRef? = conversation.getBookmark()
            if (bookmark != null) {
                bookmark.setAutojoin(true)
                service.createBookmark(
                    conversation.getAccount()
                        ?: throw NullPointerException("conversation has no account"),
                    bookmark,
                )
            }
            service.updateConversation(conversation)
            service.joinMuc(conversation)
        }
    }
}
