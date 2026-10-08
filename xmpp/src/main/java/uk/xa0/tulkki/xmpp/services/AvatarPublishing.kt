package uk.xa0.tulkki.xmpp.services

import android.net.Uri
import android.os.Bundle
import android.util.Log
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.R
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xmpp.pep.Avatar
import uk.xa0.tulkki.xmpp.pep.PublishOptions
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace
import java.util.function.Consumer

/**
 * Tulkki: publishing an avatar - the PEP node, its metadata and the vCard fallback - lifted out of
 * `XmppConnectionService`.
 *
 * The chunk owns no field and widens nothing: every reach outside itself is a **public** service
 * member - `getIqGenerator()`, `getFileBackend()`, `getAvatarService()`, `sendIqPacket`,
 * `pushNodeConfiguration`, `notifyAccountAvatarHasChanged` and the public `databaseBackend` slot.
 * The two `new Thread` bodies keep the Java's shape (decode-and-encode off the caller's thread) and
 * the two private helpers stay private here, so the Java file keeps only the six one-line
 * delegations for its public surface.
 *
 * The nullability is read off the behaviour: `publishMucAvatar` and the `(Uri)` publish dereference
 * their callback on every path, so theirs is non-null; the `(Avatar)` and metadata chains guard it
 * with `callback != null` because `republishAvatarIfNeeded` really does pass `null`. The
 * `preconditionNotMet` retry keeps its single retry and its two `OnConfigurationPushed` arms, and
 * the `Consumer<Iq>` keeps its private helpers and its item-not-found test.
 */
object AvatarPublishing {

    @JvmStatic
    fun publishMucAvatar(
        service: XmppConnectionService,
        conversation: ConversationRef,
        image: Uri,
        callback: AvatarPublicationHook,
    ) {
        Thread {
            val format = Config.AVATAR_FORMAT
            val size = Config.AVATAR_SIZE
            val avatar = service.getFileBackend().getPepAvatar(image, size, format)
            if (avatar != null) {
                if (!service.getFileBackend().save(avatar)) {
                    callback.onAvatarPublicationFailed(R.string.error_saving_avatar)
                    return@Thread
                }
                avatar.owner = (conversation.getJid() ?: throw NullPointerException("conversation has no jid")).asBareJid()
                publishMucAvatarVcard(service, conversation, avatar, callback)
            } else {
                callback.onAvatarPublicationFailed(R.string.error_publish_avatar_converting)
            }
        }.start()
    }

    private fun publishMucAvatarVcard(
        service: XmppConnectionService,
        conversation: ConversationRef,
        avatar: Avatar,
        callback: AvatarPublicationHook,
    ) {
        val account = conversation.getAccount() ?: throw NullPointerException("conversation has no account")
        val retrieve = service.getIqGenerator().retrieveVcardAvatar(avatar)
        service.sendIqPacket(account, retrieve) { response ->
            val responseError = response.findChild("error")
            val itemNotFound =
                response.getType() == Iq.Type.ERROR &&
                    responseError != null &&
                    responseError.hasChild("item-not-found")
            if (response.getType() == Iq.Type.RESULT || itemNotFound) {
                var vcard = response.findChild("vCard", "vcard-temp")
                if (vcard == null) {
                    vcard = Element("vCard", "vcard-temp")
                }
                var photo = vcard.findChild("PHOTO")
                if (photo == null) {
                    photo = vcard.addChild("PHOTO")
                }
                photo.clearChildren()
                photo.addChild("TYPE").setContent(avatar.type)
                photo.addChild("BINVAL").setContent(avatar.image)
                val publication = Iq(Iq.Type.SET)
                publication.setTo((conversation.getJid() ?: throw NullPointerException("conversation has no jid")).asBareJid())
                publication.addChild(vcard)
                service.sendIqPacket(account, publication) { publicationResponse ->
                    if (publicationResponse.getType() == Iq.Type.RESULT) {
                        callback.onAvatarPublicationSucceeded()
                    } else {
                        Log.d(
                            Config.LOGTAG,
                            "failed to publish vcard " + publicationResponse.getErrorCondition(),
                        )
                        callback.onAvatarPublicationFailed(
                            R.string.error_publish_avatar_server_reject,
                        )
                    }
                }
            } else {
                Log.d(Config.LOGTAG, "failed to request vcard " + response)
                callback.onAvatarPublicationFailed(
                    R.string.error_publish_avatar_no_server_support,
                )
            }
        }
    }

    @JvmStatic
    fun publishAvatarAsync(
        service: XmppConnectionService,
        account: AccountRef,
        image: Uri,
        open: Boolean,
        callback: AvatarPublicationHook,
    ) {
        Thread { publishAvatarImage(service, account, image, open, callback) }.start()
    }

    private fun publishAvatarImage(
        service: XmppConnectionService,
        account: AccountRef,
        image: Uri,
        open: Boolean,
        callback: AvatarPublicationHook,
    ) {
        val format = Config.AVATAR_FORMAT
        val size = Config.AVATAR_SIZE
        val avatar = service.getFileBackend().getPepAvatar(image, size, format)
        if (avatar != null) {
            if (!service.getFileBackend().save(avatar)) {
                Log.d(Config.LOGTAG, "unable to save vcard")
                callback.onAvatarPublicationFailed(R.string.error_saving_avatar)
                return
            }
            publishAvatar(service, account, avatar, open, callback)
        } else {
            callback.onAvatarPublicationFailed(R.string.error_publish_avatar_converting)
        }
    }

    @JvmStatic
    fun publishAvatar(
        service: XmppConnectionService,
        account: AccountRef,
        avatar: Avatar,
        open: Boolean,
        callback: AvatarPublicationHook?,
    ) {
        val options: Bundle?
        if ((account.getXmppConnection() ?: throw NullPointerException("account has no xmpp connection")).getFeatures().pepPublishOptions()) {
            options = if (open) PublishOptions.openAccess() else PublishOptions.presenceAccess()
        } else {
            options = null
        }
        publishAvatar(service, account, avatar, options, true, callback)
    }

    @JvmStatic
    fun publishAvatar(
        service: XmppConnectionService,
        account: AccountRef,
        avatar: Avatar,
        options: Bundle?,
        retry: Boolean,
        callback: AvatarPublicationHook?,
    ) {
        Log.d(
            Config.LOGTAG,
            account.getJid().asBareJid().toString() + ": publishing avatar. options=" + options,
        )
        val packet = service.getIqGenerator().publishAvatar(avatar, options)
        service.sendIqPacket(account, packet) { result ->
            if (result.getType() == Iq.Type.RESULT) {
                publishAvatarMetadata(service, account, avatar, options, true, callback)
            } else if (retry && PublishOptions.preconditionNotMet(result)) {
                service.pushNodeConfiguration(
                    account,
                    Namespace.AVATAR_DATA,
                    options,
                    object : OnConfigurationPushed {
                        override fun onPushSucceeded() {
                            Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid().toString() +
                                    ": changed node configuration for avatar" +
                                    " node",
                            )
                            publishAvatar(service, account, avatar, options, false, callback)
                        }

                        override fun onPushFailed() {
                            Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid().toString() +
                                    ": unable to change node configuration" +
                                    " for avatar node",
                            )
                            publishAvatar(service, account, avatar, null, false, callback)
                        }
                    },
                )
            } else {
                val error = result.findChild("error")
                Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid().toString() +
                        ": server rejected avatar " +
                        (avatar.size / 1024) +
                        "KiB " +
                        (error?.toString() ?: ""),
                )
                if (callback != null) {
                    callback.onAvatarPublicationFailed(
                        R.string.error_publish_avatar_server_reject,
                    )
                }
            }
        }
    }

    @JvmStatic
    fun publishAvatarMetadata(
        service: XmppConnectionService,
        account: AccountRef,
        avatar: Avatar,
        options: Bundle?,
        retry: Boolean,
        callback: AvatarPublicationHook?,
    ) {
        val packet = service.getIqGenerator().publishAvatarMetadata(avatar, options)
        service.sendIqPacket(account, packet) { result ->
            if (result.getType() == Iq.Type.RESULT) {
                if (account.setAvatar(avatar.getFilename())) {
                    service.getAvatarService().clear(account)
                    (service.databaseBackend ?: throw NullPointerException("database backend is not open")).updateAccount(account)
                    service.notifyAccountAvatarHasChanged(account)
                }
                Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid().toString() +
                        ": published avatar " +
                        (avatar.size / 1024) +
                        "KiB",
                )
                if (callback != null) {
                    callback.onAvatarPublicationSucceeded()
                }
            } else if (retry && PublishOptions.preconditionNotMet(result)) {
                service.pushNodeConfiguration(
                    account,
                    Namespace.AVATAR_METADATA,
                    options,
                    object : OnConfigurationPushed {
                        override fun onPushSucceeded() {
                            Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid().toString() +
                                    ": changed node configuration for avatar" +
                                    " meta data node",
                            )
                            publishAvatarMetadata(service, account, avatar, options, false, callback)
                        }

                        override fun onPushFailed() {
                            Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid().toString() +
                                    ": unable to change node configuration" +
                                    " for avatar meta data node",
                            )
                            publishAvatarMetadata(service, account, avatar, null, false, callback)
                        }
                    },
                )
            } else {
                if (callback != null) {
                    callback.onAvatarPublicationFailed(
                        R.string.error_publish_avatar_server_reject,
                    )
                }
            }
        }
    }

    @JvmStatic
    fun republishAvatarIfNeeded(service: XmppConnectionService, account: AccountRef) {
        if ((account.getOmemoSession() ?: throw NullPointerException("no omemo session")).isPepBroken()) {
            Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid().toString() +
                    ": skipping republication of avatar because pep is broken",
            )
            return
        }
        val packet = service.getIqGenerator().retrieveAvatarMetaData(null)
        service.sendIqPacket(
            account,
            packet,
            object : Consumer<Iq> {

                private fun parseAvatar(packet: Iq): Avatar? {
                    val pubsub =
                        packet.findChild("pubsub", "http://jabber.org/protocol/pubsub")
                    if (pubsub != null) {
                        val items = pubsub.findChild("items")
                        if (items != null) {
                            return Avatar.parseMetadata(items)
                        }
                    }
                    return null
                }

                private fun errorIsItemNotFound(packet: Iq): Boolean {
                    val error = packet.findChild("error")
                    return packet.getType() == Iq.Type.ERROR &&
                        error != null &&
                        error.hasChild("item-not-found")
                }

                override fun accept(packet: Iq) {
                    if (packet.getType() == Iq.Type.RESULT || errorIsItemNotFound(packet)) {
                        val serverAvatar = parseAvatar(packet)
                        if (serverAvatar == null && account.getAvatar() != null) {
                            val avatar =
                                service
                                    .getFileBackend()
                                    .getStoredPepAvatar(account.getAvatar())
                            if (avatar != null) {
                                Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid().toString() +
                                        ": avatar on server was null. republishing",
                                )
                                // publishing as 'open' - old server (that requires
                                // republication) likely doesn't support access models anyway
                                publishAvatar(
                                    service,
                                    account,
                                    service
                                        .getFileBackend()
                                        .getStoredPepAvatar(account.getAvatar())
                                        ?: throw NullPointerException("no stored avatar"),
                                    true,
                                    null,
                                )
                            } else {
                                Log.e(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid().toString() +
                                        ": error rereading avatar",
                                )
                            }
                        }
                    }
                }
            },
        )
    }
}
