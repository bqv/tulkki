package uk.xa0.tulkki.app.generator

import android.media.MediaMetadata
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Base64OutputStream
import android.util.Log
import com.google.common.base.Strings
import com.google.common.io.ByteStreams
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.cert.CertificateEncodingException
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import org.whispersystems.libsignal.IdentityKey
import org.whispersystems.libsignal.ecc.ECPublicKey
import org.whispersystems.libsignal.state.PreKeyRecord
import org.whispersystems.libsignal.state.SignedPreKeyRecord
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.crypto.OmemoSessionPort
import uk.xa0.tulkki.xmpp.forms.Data
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xmpp.pep.Avatar
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.BookmarkRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.ConversationalRef
import uk.xa0.tulkki.libs.DownloadableFileRef
import uk.xa0.tulkki.xmpp.refs.MessageRef
import uk.xa0.tulkki.xmpp.services.MessageArchiveService
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace

/**
 * Tulkki: every IQ this app builds - disco, avatars, OMEMO bundles, bookmarks, MAM, the social feed
 * and the HTTP upload slots.
 *
 * Ported from `IqGenerator.java`. It is *ours* rather than the island's, so it is converted in
 * place; the method set and the argument nullability are read off the callers, not off the Java
 * declarations.
 *
 * The Java-visible surface:
 *
 *  * every `static` stays reachable **as a static** - `XmppConnection.java:2069` calls
 *    `generateCreateAccountWithCaptcha`, `BindProcessor.java:96` calls `purgeOfflineMessages`, and
 *    `PresenceParser.kt`/`ConversationChannels.kt` call the four `default*` configuration builders -
 *    so the companion carries `@JvmStatic`;
 *  * `publish` is `protected` and the two `retrieve`/`pubsubConfiguration`/`convertFilename` helpers
 *    are private, unchanged;
 *  * `mXmppConnectionService` is the base's `protected @JvmField`, read as a field in
 *    `versionResponse`/`bobResponse` exactly as the Java did;
 *  * the Java's casts and `String.valueOf` concatenations are spelled out: a `Jid` appended to a
 *    `String` becomes `.toString()` (`"xmpp:" + jid`), and `String.valueOf(int|long)` becomes
 *    `.toString()`;
 *  * `getTimestamp` is reached as `AbstractGenerator.getTimestamp(…)`, because Kotlin does not
 *    inherit a base's companion into a subclass's scope.
 *
 * Nullability follows the callers: `publishStory`'s account is nullable (the Java null-checks it),
 * `publishPost`'s optional fields and `publishComment`'s title are nullable, `mdsDisplayed` takes a
 * nullable stanza id (`ReadMarkers.kt:158` passes `getServerMsgId()`), `retrieveAvatarMetaData` and
 * `retrieveDeviceIds` take a nullable jid, `retrievePubsubItems` a nullable server, and
 * `generateSetBlockRequest`'s `serverMsgId`, `generateCreateAccountWithCaptcha`'s `id`, and the two
 * upload-slot calls' `name`/`mime` keep the nullable shape their Kotlin callers pass.
 */
class IqGenerator(service: XmppConnectionService) : AbstractGenerator(service) {

    fun discoResponse(accountRef: AccountRef, request: Iq): Iq {
        val packet = Iq(Iq.Type.RESULT)
        packet.setId(request.getId())
        packet.setTo(request.getFrom())
        val query = packet.addChild("query", "http://jabber.org/protocol/disco#info")
        query.setAttribute("node", request.query().getAttribute("node"))
        val identity = query.addChild("identity")
        identity.setAttribute("category", "client")
        identity.setAttribute("type", getIdentityType())
        identity.setAttribute("name", getIdentityName())
        for (feature in getFeatures(accountRef)) {
            query.addChild("feature").setAttribute("var", feature)
        }
        return packet
    }

    fun versionResponse(request: Iq): Iq {
        val packet = request.generateResponse(Iq.Type.RESULT)
        val query = packet.query("jabber:iq:version")
        query.addChild("name").setContent(getIdentityName())
        query.addChild("version").setContent(getIdentityVersion())
        val os = StringBuilder()
        if ("chromium" == Build.BRAND) {
            os.append("Chrome OS")
        } else {
            os.append("Android")
        }
        os.append(" ")
        os.append(Build.VERSION.RELEASE)
        if (mXmppConnectionService.getContactListSyncService().playStoreFlavor()) {
            os.append(" (")
            os.append(Build.BOARD)
            os.append(", ")
            os.append(Build.FINGERPRINT)
            os.append(")")
            query.addChild("os").setContent(os.toString())
        }
        return packet
    }

    fun entityTimeResponse(request: Iq): Iq {
        val packet = request.generateResponse(Iq.Type.RESULT)
        val time = packet.addChild("time", "urn:xmpp:time")
        val now = System.currentTimeMillis()
        time.addChild("utc").setContent(AbstractGenerator.getTimestamp(now))
        val ourTimezone = TimeZone.getDefault()
        val offsetSeconds = ourTimezone.getOffset(now) / 1000
        val offsetMinutes = Math.abs((offsetSeconds % 3600) / 60)
        val offsetHours = offsetSeconds / 3600
        val hours: String =
            if (offsetHours < 0) {
                String.format(Locale.US, "%03d", offsetHours)
            } else {
                String.format(Locale.US, "%02d", offsetHours)
            }
        val minutes = String.format(Locale.US, "%02d", offsetMinutes)
        time.addChild("tzo").setContent(hours + ":" + minutes)
        return packet
    }

    protected fun publish(node: String, item: Element, options: Bundle?): Iq {
        val packet = Iq(Iq.Type.SET)
        val pubsub = packet.addChild("pubsub", Namespace.PUBSUB)
        val publish = pubsub.addChild("publish")
        publish.setAttribute("node", node)
        publish.addChild(item)
        if (options != null) {
            val publishOptions = pubsub.addChild("publish-options")
            publishOptions.addChild(Data.create(Namespace.PUBSUB_PUBLISH_OPTIONS, options))
        }
        return packet
    }

    protected fun publish(node: String, item: Element): Iq = publish(node, item, null)

    private fun retrieve(node: String, item: Element?): Iq {
        val packet = Iq(Iq.Type.GET)
        val pubsub = packet.addChild("pubsub", Namespace.PUBSUB)
        val items = pubsub.addChild("items")
        items.setAttribute("node", node)
        if (item != null) {
            items.addChild(item)
        }
        return packet
    }

    fun retrieveVcard4(jid: Jid): Iq {
        val packet = retrieve("urn:xmpp:vcard4", null)
        packet.setTo(jid)
        return packet
    }

    fun retrieveBookmarks(): Iq = retrieve(Namespace.BOOKMARKS2, null)

    fun retrieveMds(): Iq = retrieve(Namespace.MDS_DISPLAYED, null)

    fun publishNick(nick: String): Iq {
        val item = Element("item")
        item.setAttribute("id", "current")
        item.addChild("nick", Namespace.NICK).setContent(nick)
        return publish(Namespace.NICK, item)
    }

    fun deleteNode(node: String): Iq {
        val packet = Iq(Iq.Type.SET)
        val pubsub = packet.addChild("pubsub", Namespace.PUBSUB_OWNER)
        pubsub.addChild("delete").setAttribute("node", node)
        return packet
    }

    fun deleteItem(node: String, id: String): Iq {
        val packet = Iq(Iq.Type.SET)
        val pubsub = packet.addChild("pubsub", Namespace.PUBSUB)
        val retract = pubsub.addChild("retract")
        retract.setAttribute("node", node)
        retract.setAttribute("notify", "true")
        retract.addChild("item").setAttribute("id", id)
        return packet
    }

    fun publishAvatar(avatar: Avatar, options: Bundle?): Iq {
        val item = Element("item")
        item.setAttribute("id", avatar.sha1sum)
        val data = item.addChild("data", Namespace.AVATAR_DATA)
        data.setContent(avatar.image)
        return publish(Namespace.AVATAR_DATA, item, options)
    }

    fun publishUserTune(): Iq {
        val item = Element("item")
        // Tulkki: the previous build published this item under upstream's own id. It is an item id
        // inside the node, so an old item stays under the old id and simply stops being updated;
        // nothing in the tree reads the id back.
        item.setAttribute("id", "tulkkiUserTune")
        item.addChild("tune", Namespace.USER_TUNE)
        return publish(Namespace.USER_TUNE, item, null)
    }

    fun publishUserTune(metadata: MediaMetadata, options: Bundle?): Iq {
        val item = Element("item")
        item.setAttribute("id", "tulkkiUserTune")

        val tuneElement = item.addChild("tune", Namespace.USER_TUNE)

        val artist = metadata.getText(MediaMetadata.METADATA_KEY_ARTIST)
        if (artist != null) {
            tuneElement.addChild("artist").setContent(artist.toString())
        }

        val title = metadata.getText(MediaMetadata.METADATA_KEY_TITLE)
        if (title != null) {
            tuneElement.addChild("title").setContent(title.toString())
        }

        val album = metadata.getText(MediaMetadata.METADATA_KEY_ALBUM)
        if (album != null) {
            tuneElement.addChild("source").setContent(album.toString())
        }

        val trackNumber = metadata.getLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER)
        if (trackNumber > 0) {
            tuneElement.addChild("track").setContent(trackNumber.toString())
        }

        val durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        if (durationMs > 0) {
            tuneElement.addChild("length").setContent((durationMs / 1000).toString())
        }

        return publish(Namespace.USER_TUNE, item, options)
    }

    fun publishElement(
        namespace: String,
        element: Element,
        id: String,
        options: Bundle?,
    ): Iq {
        val item = Element("item")
        item.setAttribute("id", id)
        item.addChild(element)
        return publish(namespace, item, options)
    }

    fun publishStory(
        account: AccountRef?,
        url: String,
        type: String,
        title: String,
        options: Bundle?,
    ): Iq {
        val item = Element("item")
        val storyId = UUID.randomUUID().toString()
        item.setAttribute("id", storyId)
        val entry = item.addChild("entry", Namespace.ATOM)

        entry.addChild("id").setContent("urn:uuid:" + storyId)

        var effectiveTitle = title
        if (Strings.isNullOrEmpty(effectiveTitle)) {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            effectiveTitle = "Story " + sdf.format(Date())
        }
        entry.addChild("title").setContent(effectiveTitle)

        val timestamp = AbstractGenerator.getTimestamp(System.currentTimeMillis())
        entry.addChild("updated").setContent(timestamp)
        entry.addChild("published").setContent(timestamp)

        if (account != null) {
            entry.addChild("author")
                .addChild("uri")
                .setContent("xmpp:" + account.getJid().asBareJid().toString())
        }

        val link = entry.addChild("link")
        link.setAttribute("rel", "enclosure")
        link.setAttribute("href", url)
        link.setAttribute("type", type)
        link.setAttribute("title", effectiveTitle)

        return publish(Namespace.PUBSUB_STORIES, item, options)
    }

    fun retrieveStories(jid: Jid): Iq {
        val iq = Iq(Iq.Type.GET)
        iq.setTo(jid)
        val pubsub = iq.addChild("pubsub", Namespace.PUBSUB)
        val items = pubsub.addChild("items")
        items.setAttribute("node", Namespace.PUBSUB_STORIES)
        return iq
    }

    fun publishAvatarMetadata(avatar: Avatar, options: Bundle?): Iq {
        val item = Element("item")
        item.setAttribute("id", avatar.sha1sum)
        val metadata = item.addChild("metadata", Namespace.AVATAR_METADATA)
        val info = metadata.addChild("info")
        info.setAttribute("bytes", avatar.size)
        info.setAttribute("id", avatar.sha1sum)
        info.setAttribute("height", avatar.height)
        info.setAttribute("width", avatar.height)
        info.setAttribute("type", avatar.type)
        return publish(Namespace.AVATAR_METADATA, item, options)
    }

    fun retrievePepAvatar(avatar: Avatar): Iq {
        val item = Element("item")
        item.setAttribute("id", avatar.sha1sum)
        val packet = retrieve(Namespace.AVATAR_DATA, item)
        packet.setTo(avatar.owner)
        return packet
    }

    fun retrieveVcardAvatar(avatar: Avatar): Iq {
        val packet = Iq(Iq.Type.GET)
        packet.setTo(avatar.owner)
        packet.addChild("vCard", "vcard-temp")
        return packet
    }

    fun retrieveVcardAvatar(to: Jid): Iq {
        val packet = Iq(Iq.Type.GET)
        packet.setTo(to)
        packet.addChild("vCard", "vcard-temp")
        return packet
    }

    fun retrieveAvatarMetaData(to: Jid?): Iq {
        val packet = retrieve("urn:xmpp:avatar:metadata", null)
        if (to != null) {
            packet.setTo(to)
        }
        return packet
    }

    fun retrieveDeviceIds(to: Jid?): Iq {
        val packet = retrieve(OmemoSessionPort.PEP_DEVICE_LIST, null)
        if (to != null) {
            packet.setTo(to)
        }
        return packet
    }

    fun retrieveBundlesForDevice(to: Jid, deviceid: Int): Iq {
        val packet = retrieve(OmemoSessionPort.PEP_BUNDLES + ":" + deviceid, null)
        packet.setTo(to)
        return packet
    }

    fun retrieveVerificationForDevice(to: Jid, deviceid: Int): Iq {
        val packet = retrieve(OmemoSessionPort.PEP_VERIFICATION + ":" + deviceid, null)
        packet.setTo(to)
        return packet
    }

    fun publishDeviceIds(ids: Set<Int>, publishOptions: Bundle?): Iq {
        val item = Element("item")
        item.setAttribute("id", "current")
        val list = item.addChild("list", OmemoSessionPort.PEP_PREFIX)
        for (id in ids) {
            val device = Element("device")
            device.setAttribute("id", id)
            list.addChild(device)
        }
        return publish(OmemoSessionPort.PEP_DEVICE_LIST, item, publishOptions)
    }

    fun publishBookmarkItem(bookmark: BookmarkRef): Element {
        val name = bookmark.getBookmarkName()
        val nick = bookmark.getNick()
        val password = bookmark.getPassword()
        val autojoin = bookmark.autojoin()
        val conference = Element("conference", Namespace.BOOKMARKS2)
        if (!Strings.isNullOrEmpty(name)) {
            conference.setAttribute("name", name)
        }
        if (!Strings.isNullOrEmpty(nick)) {
            conference.addChild("nick").setContent(nick)
        }
        if (password != null) {
            conference.addChild("password").setContent(password)
        }
        conference.setAttribute("autojoin", autojoin.toString())
        conference.addChild(bookmark.getExtensions())
        return conference
    }

    fun mdsDisplayed(stanzaId: String?, conversation: ConversationRef): Element {
        val by: Jid =
            if (conversation.getMode() == ConversationalRef.MODE_MULTI) {
                (conversation.getJid() ?: throw NullPointerException("conversation has no jid")).asBareJid()
            } else {
                (conversation.getAccount() ?: throw NullPointerException("conversation has no account"))
                    .getJid()
                    .asBareJid()
            }
        return mdsDisplayed(stanzaId, by)
    }

    private fun mdsDisplayed(stanzaId: String?, by: Jid): Element {
        val displayed = Element("displayed", Namespace.MDS_DISPLAYED)
        val stanzaIdElement = displayed.addChild("stanza-id", Namespace.STANZA_IDS)
        stanzaIdElement.setAttribute("id", stanzaId)
        stanzaIdElement.setAttribute("by", by)
        return displayed
    }

    fun publishBundles(
        signedPreKeyRecord: SignedPreKeyRecord,
        identityKey: IdentityKey,
        preKeyRecords: Set<PreKeyRecord>,
        deviceId: Int,
        publishOptions: Bundle?,
    ): Iq {
        val item = Element("item")
        item.setAttribute("id", "current")
        val bundle = item.addChild("bundle", OmemoSessionPort.PEP_PREFIX)
        val signedPreKeyPublic = bundle.addChild("signedPreKeyPublic")
        signedPreKeyPublic.setAttribute("signedPreKeyId", signedPreKeyRecord.getId())
        val publicKey: ECPublicKey = signedPreKeyRecord.getKeyPair().getPublicKey()
        signedPreKeyPublic.setContent(
            Base64.encodeToString(publicKey.serialize(), Base64.NO_WRAP)
        )
        val signedPreKeySignature = bundle.addChild("signedPreKeySignature")
        signedPreKeySignature.setContent(
            Base64.encodeToString(signedPreKeyRecord.getSignature(), Base64.NO_WRAP)
        )
        val identityKeyElement = bundle.addChild("identityKey")
        identityKeyElement.setContent(
            Base64.encodeToString(identityKey.serialize(), Base64.NO_WRAP)
        )

        val prekeys = bundle.addChild("prekeys", OmemoSessionPort.PEP_PREFIX)
        for (preKeyRecord in preKeyRecords) {
            val prekey = prekeys.addChild("preKeyPublic")
            prekey.setAttribute("preKeyId", preKeyRecord.getId())
            prekey.setContent(
                Base64.encodeToString(
                    preKeyRecord.getKeyPair().getPublicKey().serialize(),
                    Base64.NO_WRAP,
                )
            )
        }

        return publish(OmemoSessionPort.PEP_BUNDLES + ":" + deviceId, item, publishOptions)
    }

    fun publishVerification(
        signature: ByteArray,
        certificates: Array<X509Certificate>,
        deviceId: Int,
    ): Iq {
        val item = Element("item")
        item.setAttribute("id", "current")
        val verification = item.addChild("verification", OmemoSessionPort.PEP_PREFIX)
        val chain = verification.addChild("chain")
        for (i in certificates.indices) {
            try {
                val certificate = chain.addChild("certificate")
                certificate.setContent(
                    Base64.encodeToString(certificates[i].getEncoded(), Base64.NO_WRAP)
                )
                certificate.setAttribute("index", i)
            } catch (e: CertificateEncodingException) {
                Log.d(Config.LOGTAG, "could not encode certificate")
            }
        }
        verification
            .addChild("signature")
            .setContent(Base64.encodeToString(signature, Base64.NO_WRAP))
        return publish(OmemoSessionPort.PEP_VERIFICATION + ":" + deviceId, item)
    }

    fun queryMessageArchiveManagement(mam: MessageArchiveService.Query): Iq {
        val packet = Iq(Iq.Type.SET)
        val query = packet.query(mam.version.namespace)
        query.setAttribute("queryid", mam.getQueryId())
        val data = Data()
        data.setFormType(mam.version.namespace)
        if (mam.muc()) {
            packet.setTo(mam.getWith())
        } else {
            val with = mam.getWith()
            if (with != null) {
                data.put("with", with.toString())
            }
        }
        val start = mam.getStart()
        val end = mam.getEnd()
        if (start != 0L) {
            data.put("start", AbstractGenerator.getTimestamp(start))
        }
        if (end != 0L) {
            data.put("end", AbstractGenerator.getTimestamp(end))
        }
        data.submit()
        query.addChild(data)
        val set = query.addChild("set", "http://jabber.org/protocol/rsm")
        if (mam.getPagingOrder() == MessageArchiveService.PagingOrder.REVERSE) {
            set.addChild("before").setContent(mam.getReference())
        } else {
            val reference = mam.getReference()
            if (reference != null) {
                set.addChild("after").setContent(reference)
            }
        }
        set.addChild("max").setContent(Config.PAGE_SIZE.toString())
        return packet
    }

    fun generateGetBlockList(): Iq {
        val iq = Iq(Iq.Type.GET)
        iq.addChild("blocklist", Namespace.BLOCKING)
        return iq
    }

    fun generateSetBlockRequest(jid: Jid, reportSpam: Boolean, serverMsgId: String?): Iq {
        val iq = Iq(Iq.Type.SET)
        val block = iq.addChild("block", Namespace.BLOCKING)
        val item = block.addChild("item").setAttribute("jid", jid)
        if (reportSpam) {
            val report = item.addChild("report", Namespace.REPORTING)
            report.setAttribute("reason", Namespace.REPORTING_REASON_SPAM)
            if (serverMsgId != null) {
                val stanzaId = report.addChild("stanza-id", Namespace.STANZA_IDS)
                stanzaId.setAttribute("by", jid)
                stanzaId.setAttribute("id", serverMsgId)
            }
        }
        Log.d(Config.LOGTAG, iq.toString())
        return iq
    }

    fun generateSetUnblockRequest(jid: Jid): Iq {
        val iq = Iq(Iq.Type.SET)
        val block = iq.addChild("unblock", Namespace.BLOCKING)
        block.addChild("item").setAttribute("jid", jid)
        return iq
    }

    fun generateSetPassword(account: AccountRef, newPassword: String): Iq {
        val packet = Iq(Iq.Type.SET)
        packet.setTo(account.getDomain())
        val query = packet.addChild("query", Namespace.REGISTER)
        val jid = account.getJid()
        query.addChild("username").setContent(jid.getLocal())
        query.addChild("password").setContent(newPassword)
        return packet
    }

    fun changeAffiliation(conference: ConversationRef, jid: Jid, affiliation: String): Iq {
        val jids = ArrayList<Jid>()
        jids.add(jid)
        return changeAffiliation(conference, jids, affiliation)
    }

    fun changeAffiliation(
        conference: ConversationRef,
        jids: List<Jid>,
        affiliation: String,
    ): Iq {
        val packet = Iq(Iq.Type.SET)
        packet.setTo((conference.getJid() ?: throw NullPointerException("conference has no jid")).asBareJid())
        packet.setFrom((conference.getAccount() ?: throw NullPointerException("conference has no account")).getJid())
        val query = packet.query("http://jabber.org/protocol/muc#admin")
        for (jid in jids) {
            val item = query.addChild("item")
            item.setAttribute("jid", jid)
            item.setAttribute("affiliation", affiliation)
        }
        return packet
    }

    fun changeRole(conference: ConversationRef, nick: String, role: String): Iq {
        val packet = Iq(Iq.Type.SET)
        packet.setTo((conference.getJid() ?: throw NullPointerException("conference has no jid")).asBareJid())
        packet.setFrom((conference.getAccount() ?: throw NullPointerException("conference has no account")).getJid())
        val item = packet.query("http://jabber.org/protocol/muc#admin").addChild("item")
        item.setAttribute("nick", nick)
        item.setAttribute("role", role)
        return packet
    }

    /**
     * Tulkki: 3.7 pair 9, part 16 - `XmppConnectionService.moderateMessage` holds a ref now, and the
     * body only ever read three ref members (`getConversation().getJid()`, `getServerMsgId()`), so
     * the parameter moves and the model overload this part declared in commit 1 is gone with it.
     */
    fun moderateMessage(account: AccountRef, m: MessageRef, reason: String?): Iq {
        val packet = Iq(Iq.Type.SET)
        val ref = m.getConversation() ?: throw NullPointerException("message has no conversation")
        packet.setTo((ref.getJid() ?: throw NullPointerException("conversation has no jid")).asBareJid())
        packet.setFrom(account.getJid())
        val moderate =
            packet
                .addChild("apply-to", "urn:xmpp:fasten:0")
                .setAttribute("id", m.getServerMsgId())
                .addChild("moderate", "urn:xmpp:message-moderate:0")
        moderate.addChild("retract", "urn:xmpp:message-retract:0")
        moderate.addChild("reason", "urn:xmpp:message-moderate:0").setContent(reason)
        return packet
    }

    fun requestHttpUploadSlot(
        host: Jid?,
        file: DownloadableFileRef,
        name: String?,
        mime: String?,
    ): Iq {
        val packet = Iq(Iq.Type.GET)
        packet.setTo(host)
        val request = packet.addChild("request", Namespace.HTTP_UPLOAD)
        request.setAttribute("filename", if (name == null) convertFilename(file.getName()) else name)
        request.setAttribute("size", file.getExpectedSize())
        request.setAttribute("content-type", mime)
        return packet
    }

    fun requestHttpUploadLegacySlot(
        host: Jid?,
        file: DownloadableFileRef,
        mime: String?,
    ): Iq {
        val packet = Iq(Iq.Type.GET)
        packet.setTo(host)
        val request = packet.addChild("request", Namespace.HTTP_UPLOAD_LEGACY)
        request.addChild("filename").setContent(convertFilename(file.getName()))
        request.addChild("size").setContent(file.getExpectedSize().toString())
        request.addChild("content-type").setContent(mime)
        return packet
    }

    fun pushTokenToAppServer(appServer: Jid, token: String, deviceId: String): Iq =
        pushTokenToAppServer(appServer, token, deviceId, null)

    fun pushTokenToAppServer(appServer: Jid, token: String, deviceId: String, muc: Jid?): Iq {
        val packet = Iq(Iq.Type.SET)
        packet.setTo(appServer)
        val command = packet.addChild("command", Namespace.COMMANDS)
        command.setAttribute("node", "register-push-fcm")
        command.setAttribute("action", "execute")
        val data = Data()
        data.put("token", token)
        data.put("android-id", deviceId)
        if (muc != null) {
            data.put("muc", muc.toString())
        }
        data.submit()
        command.addChild(data)
        return packet
    }

    fun unregisterChannelOnAppServer(appServer: Jid, deviceId: String, channel: String): Iq {
        val packet = Iq(Iq.Type.SET)
        packet.setTo(appServer)
        val command = packet.addChild("command", Namespace.COMMANDS)
        command.setAttribute("node", "unregister-push-fcm")
        command.setAttribute("action", "execute")
        val data = Data()
        data.put("channel", channel)
        data.put("android-id", deviceId)
        data.submit()
        command.addChild(data)
        return packet
    }

    fun enablePush(jid: Jid, node: String, secret: String?): Iq {
        val packet = Iq(Iq.Type.SET)
        val enable = packet.addChild("enable", Namespace.PUSH)
        enable.setAttribute("jid", jid)
        enable.setAttribute("node", node)
        if (secret != null) {
            val data = Data()
            data.setFormType(Namespace.PUBSUB_PUBLISH_OPTIONS)
            data.put("secret", secret)
            data.submit()
            enable.addChild(data)
        }
        return packet
    }

    fun disablePush(jid: Jid, node: String): Iq {
        val packet = Iq(Iq.Type.SET)
        val disable = packet.addChild("disable", Namespace.PUSH)
        disable.setAttribute("jid", jid)
        disable.setAttribute("node", node)
        return packet
    }

    fun queryAffiliation(conversation: ConversationRef, affiliation: String): Iq {
        val packet = Iq(Iq.Type.GET)
        packet.setTo((conversation.getJid() ?: throw NullPointerException("conversation has no jid")).asBareJid())
        packet
            .query("http://jabber.org/protocol/muc#admin")
            .addChild("item")
            .setAttribute("affiliation", affiliation)
        return packet
    }

    fun createStoriesNode(): Iq {
        val iq = Iq(Iq.Type.SET)
        val pubsub = iq.addChild("pubsub", Namespace.PUBSUB)
        pubsub.addChild("create").setAttribute("node", Namespace.PUBSUB_STORIES)
        val configure = pubsub.addChild("configure")
        // Correctly use the 'pubsub#node_config' namespace
        val data =
            Data.create("http://jabber.org/protocol/pubsub#node_config", defaultStoriesConfiguration())
        configure.addChild(data)
        return iq
    }

    fun requestPubsubConfiguration(jid: Jid, node: String): Iq =
        pubsubConfiguration(jid, node, null)

    fun publishPubsubConfiguration(jid: Jid, node: String, data: Data): Iq =
        pubsubConfiguration(jid, node, data)

    private fun pubsubConfiguration(jid: Jid, node: String, data: Data?): Iq {
        val packet = Iq(if (data == null) Iq.Type.GET else Iq.Type.SET)
        packet.setTo(jid)
        val pubsub = packet.addChild("pubsub", "http://jabber.org/protocol/pubsub#owner")
        val configure = pubsub.addChild("configure").setAttribute("node", node)
        if (data != null) {
            configure.addChild(data)
        }
        return packet
    }

    fun queryDiscoItems(jid: Jid): Iq {
        val packet = Iq(Iq.Type.GET)
        packet.setTo(jid)
        packet.query(Namespace.DISCO_ITEMS)
        return packet
    }

    fun queryDiscoItems(jid: Jid, node: String): Iq {
        val packet = queryDiscoItems(jid)
        val query = packet.query(Namespace.DISCO_ITEMS)
        query.setAttribute("node", node)
        return packet
    }

    fun queryDiscoInfo(jid: Jid): Iq {
        val packet = Iq(Iq.Type.GET)
        packet.setTo(jid)
        packet.addChild("query", Namespace.DISCO_INFO)
        return packet
    }

    fun bobResponse(request: Iq): Iq {
        try {
            val dataChild =
                request.findChild("data", "urn:xmpp:bob")
                    ?: throw NullPointerException("no bob data child")
            val bobCid =
                dataChild.getAttribute("cid") ?: throw NullPointerException("no cid attribute")
            val cid = mXmppConnectionService.bobTransfer().cid(bobCid)
            val f = mXmppConnectionService.getFileForCid(cid)
            if (f == null || !f.canRead()) {
                throw IOException("No such file")
            } else if (f.getSize() > 129000) {
                val response = request.generateResponse(Iq.Type.ERROR)
                val error = response.addChild("error")
                error.setAttribute("type", "cancel")
                error.addChild("policy-violation", "urn:ietf:params:xml:ns:xmpp-stanzas")
                return response
            } else {
                val response = request.generateResponse(Iq.Type.RESULT)
                val data = response.addChild("data", "urn:xmpp:bob")
                data.setAttribute("cid", bobCid)
                data.setAttribute("type", f.getMimeType())
                val b64 = ByteArrayOutputStream(f.getSize().toInt() * 2)
                val b64wrap = Base64OutputStream(b64, Base64.NO_WRAP)
                // Tulkki: 3.7 C5-E3 - `f` is a ref now; the JDK stream constructor wants a `File`,
                // which is the one thing the ref does not inherit - `asFile()` is the bridge.
                ByteStreams.copy(FileInputStream(f.asFile()), b64wrap)
                b64wrap.flush()
                b64wrap.close()
                data.setContent(b64.toString("utf-8"))
                return response
            }
        } catch (e: IOException) {
            return bobError(request)
        } catch (e: IllegalStateException) {
            return bobError(request)
        }
    }

    private fun bobError(request: Iq): Iq {
        val response = request.generateResponse(Iq.Type.ERROR)
        val error = response.addChild("error")
        error.setAttribute("type", "cancel")
        error.addChild("item-not-found", "urn:ietf:params:xml:ns:xmpp-stanzas")
        return response
    }

    fun retrievePubsubItems(server: Jid?, node: String): Iq {
        val iq = Iq(Iq.Type.GET)
        if (server != null) {
            iq.setTo(server)
        }
        val pubsub = iq.addChild("pubsub", Namespace.PUBSUB)
        val items = pubsub.addChild("items")
        items.setAttribute("node", node)
        return iq
    }

    fun publishPost(
        account: AccountRef,
        title: String?,
        content: String?,
        attachmentUrl: String?,
        attachmentType: String?,
        postId: String,
        linkUrl: String?,
    ): Iq {
        val item = Element("item")
        item.setAttribute("id", postId)
        val entry = item.addChild("entry", Namespace.ATOM)

        val now = AbstractGenerator.getTimestamp(System.currentTimeMillis())
        val date = now.substring(0, 10)
        entry.addChild("id").setContent("tag:" + account.getServer() + "," + date + ":" + postId)

        entry
            .addChild("link")
            .setAttribute("rel", "replies")
            .setAttribute("title", "comments")
            .setAttribute(
                "href",
                "xmpp:" +
                    account.getJid().asBareJid().toString() +
                    "?;node=urn:xmpp:microblog:0:comments/" +
                    postId,
            )

        if (title != null) {
            entry.addChild("title").setAttribute("type", "text").setContent(title)
        }
        if (content != null) {
            entry.addChild("content").setAttribute("type", "text").setContent(content)
        }
        if (attachmentUrl != null && attachmentType != null) {
            entry
                .addChild("link")
                .setAttribute("rel", "enclosure")
                .setAttribute("href", attachmentUrl)
                .setAttribute("type", attachmentType)
        }
        if (linkUrl != null && !linkUrl.isEmpty()) {
            entry
                .addChild("link")
                .setAttribute("rel", "related")
                .setAttribute("href", linkUrl)
        }
        val author = entry.addChild("author")
        var name = account.getDisplayName()
        if (name == null || name.trim().isEmpty()) {
            name = account.getJid().asBareJid().toString()
        }
        author.addChild("name").setContent(name)
        author.addChild("uri").setContent("xmpp:" + account.getJid().asBareJid().toString())
        entry.addChild("published").setContent(now)
        entry.addChild("updated").setContent(now)

        entry
            .addChild("generator")
            // Tulkki identifies itself and names no project it does not belong to: the generator's
            // uri is optional, so it is simply left out.
            .setAttribute("version", uk.xa0.tulkki.xmpp.BuildConfig.VERSION_NAME)
            .setContent(uk.xa0.tulkki.xmpp.BuildConfig.APP_NAME)

        return publish(Namespace.MICROBLOG, item, null)
    }

    private fun createNode(node: String, options: Bundle): Iq {
        val iq = Iq(Iq.Type.SET)
        val pubsub = iq.addChild("pubsub", Namespace.PUBSUB)
        pubsub.addChild("create").setAttribute("node", node)
        val configure = pubsub.addChild("configure")
        val data = Data.create("http://jabber.org/protocol/pubsub#node_config", options)
        configure.addChild(data)
        return iq
    }

    fun createSocialFeedNode(): Iq {
        val iq = Iq(Iq.Type.SET)
        val pubsub = iq.addChild("pubsub", Namespace.PUBSUB)
        pubsub.addChild("create").setAttribute("node", Namespace.MICROBLOG)
        val configure = pubsub.addChild("configure")
        val data =
            Data.create("http://jabber.org/protocol/pubsub#node_config", defaultPostConfiguration())
        configure.addChild(data)
        return iq
    }

    fun publishComment(account: AccountRef, node: String, title: String?): Iq {
        val iq = Iq(Iq.Type.SET)
        iq.setTo(account.getJid().asBareJid())
        val pubsub = iq.addChild("pubsub", Namespace.PUBSUB)
        val publish = pubsub.addChild("publish")
        publish.setAttribute("node", node)
        val item = publish.addChild("item")
        val entry = item.addChild("entry", Namespace.ATOM)
        entry.addChild("title").setContent(title)
        val author = entry.addChild("author")
        var name = account.getDisplayName()
        if (name == null || name.trim().isEmpty()) {
            name = account.getJid().asBareJid().toString()
        }
        author.addChild("name").setContent(name)
        author.addChild("uri").setContent("xmpp:" + account.getJid().asBareJid().toString())
        val id =
            "tag:" +
                account.getServer() +
                "," +
                AbstractGenerator.getTimestamp(System.currentTimeMillis()) +
                ":" +
                UUID.randomUUID().toString()
        entry.addChild("id").setContent(id)
        val now = AbstractGenerator.getTimestamp(System.currentTimeMillis())
        entry.addChild("published").setContent(now)
        entry.addChild("updated").setContent(now)
        return iq
    }

    fun createCommentsNode(postId: String): Iq =
        createNode("urn:xmpp:microblog:0:comments/" + postId, defaultCommentsConfiguration())

    fun retractPost(node: String, id: String): Iq {
        val packet = Iq(Iq.Type.SET)
        val pubsub = packet.addChild("pubsub", Namespace.PUBSUB)
        val retract = pubsub.addChild("retract")
        retract.setAttribute("node", node)
        retract.setAttribute("notify", "true")
        retract.addChild("item").setAttribute("id", id)
        return packet
    }

    fun generateSubscriptionIq(to: Jid, node: String, from: Jid): Iq {
        val iq = Iq(Iq.Type.SET)
        iq.setTo(to)
        val pubsub = iq.addChild("pubsub", Namespace.PUBSUB)
        val subscribe = pubsub.addChild("subscribe")
        subscribe.setAttribute("node", node)
        subscribe.setAttribute("jid", from.asBareJid().toString())
        return iq
    }

    fun generateUnsubscriptionIq(to: Jid, node: String, from: Jid): Iq {
        val iq = Iq(Iq.Type.SET)
        iq.setTo(to)
        val pubsub = iq.addChild("pubsub", Namespace.PUBSUB)
        val unsubscribe = pubsub.addChild("unsubscribe")
        unsubscribe.setAttribute("node", node)
        unsubscribe.setAttribute("jid", from.asBareJid().toString())
        return iq
    }

    companion object {

        @JvmStatic
        fun purgeOfflineMessages(): Iq {
            val packet = Iq(Iq.Type.SET)
            packet
                .addChild("offline", Namespace.FLEXIBLE_OFFLINE_MESSAGE_RETRIEVAL)
                .addChild("purge")
            return packet
        }

        @JvmStatic
        fun generateCreateAccountWithCaptcha(account: AccountRef, id: String?, data: Data?): Iq {
            val register = Iq(Iq.Type.SET)
            register.setFrom(account.getJid().asBareJid())
            register.setTo(account.getDomain())
            register.setId(id)
            val query = register.query(Namespace.REGISTER)
            if (data != null) {
                query.addChild(data)
            }
            return register
        }

        @JvmStatic
        fun defaultGroupChatConfiguration(): Bundle {
            val options = Bundle()
            options.putString("muc#roomconfig_persistentroom", "1")
            options.putString("muc#roomconfig_membersonly", "1")
            options.putString("muc#roomconfig_publicroom", "0")
            options.putString("muc#roomconfig_whois", "anyone")
            options.putString("muc#roomconfig_changesubject", "0")
            options.putString("muc#roomconfig_allowinvites", "0")
            options.putString("muc#roomconfig_enablearchiving", "1") // prosody
            options.putString("mam", "1") // ejabberd community
            options.putString("muc#roomconfig_mam", "1") // ejabberd saas
            return options
        }

        @JvmStatic
        fun defaultChannelConfiguration(): Bundle {
            val options = Bundle()
            options.putString("muc#roomconfig_persistentroom", "1")
            options.putString("muc#roomconfig_membersonly", "0")
            options.putString("muc#roomconfig_publicroom", "1")
            options.putString("muc#roomconfig_whois", "moderators")
            options.putString("muc#roomconfig_changesubject", "0")
            options.putString("muc#roomconfig_enablearchiving", "1") // prosody
            options.putString("mam", "1") // ejabberd community
            options.putString("muc#roomconfig_mam", "1") // ejabberd saas
            return options
        }

        @JvmStatic
        fun defaultStoriesConfiguration(): Bundle {
            val options = Bundle()
            options.putString("pubsub#node_type", "leaf")
            options.putString("pubsub#type", Namespace.PUBSUB_STORIES)
            options.putString("pubsub#access_model", "presence")
            options.putString("pubsub#item_expire", "86400")
            options.putString("pubsub#persist_items", "1")
            options.putString("pubsub#max_items", "120")
            options.putString("pubsub#notify_retract", "1")
            options.putString("pubsub#send_last_published_item", "on_sub_and_presence")
            options.putString("pubsub#publish_model", "publishers")
            return options
        }

        @JvmStatic
        fun defaultPostConfiguration(): Bundle {
            val options = Bundle()
            options.putString("pubsub#node_type", "leaf")
            options.putString("pubsub#type", Namespace.PUBSUB_SOCIAL_FEED)
            options.putString("pubsub#access_model", "presence")
            options.putString("pubsub#persist_items", "1")
            options.putString("pubsub#deliver_payloads", "0")
            options.putString("pubsub#send_last_published_item", "on_sub")
            options.putString("pubsub#max_items", "max")
            options.putString("pubsub#notify_retract", "1")
            options.putString("pubsub#deliver_notifications", "1")
            options.putString("pubsub#publish_model", "publishers")
            return options
        }

        @JvmStatic
        fun defaultCommentsConfiguration(): Bundle {
            val options = Bundle()
            options.putString("pubsub#node_type", "leaf")
            options.putString("pubsub#type", "urn:xmpp:microblog:0:comments")
            options.putString("pubsub#access_model", "open")
            options.putString("pubsub#persist_items", "1")
            options.putString("pubsub#max_items", "max")
            options.putString("pubsub#notify_retract", "1")
            options.putString("pubsub#deliver_notifications", "1")
            options.putString("pubsub#deliver_payloads", "1")
            options.putString("pubsub#send_last_published_item", "on_sub")
            options.putString("pubsub#publish_model", "open")
            options.putString("pubsub#itemreply", "publisher")
            return options
        }

        private fun convertFilename(name: String): String {
            val pos = name.indexOf('.')
            if (pos != -1) {
                try {
                    val uuid = UUID.fromString(name.substring(0, pos))
                    val bb = ByteBuffer.wrap(ByteArray(16))
                    bb.putLong(uuid.mostSignificantBits)
                    bb.putLong(uuid.leastSignificantBits)
                    return Base64.encodeToString(
                        bb.array(),
                        Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
                    ) + name.substring(pos)
                } catch (e: Exception) {
                    return name
                }
            } else {
                return name
            }
        }
    }
}
