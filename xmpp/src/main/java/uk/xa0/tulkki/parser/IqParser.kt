package uk.xa0.tulkki.parser

import android.util.Log
import android.util.Pair
import com.google.common.base.CharMatcher
import com.google.common.io.BaseEncoding
import java.io.ByteArrayInputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet
import java.util.function.Consumer
import org.whispersystems.libsignal.IdentityKey
import org.whispersystems.libsignal.InvalidKeyException
import org.whispersystems.libsignal.ecc.Curve
import org.whispersystems.libsignal.ecc.ECPublicKey
import org.whispersystems.libsignal.state.PreKeyBundle
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.OnUpdateBlocklist
import uk.xa0.tulkki.xmpp.crypto.OmemoSessionPort
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ContactRef
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace

/**
 * Tulkki: the IQ parser - roster pushes, block lists, pubsub and the OMEMO bundle reads.
 *
 * Ported from `IqParser.java`,
 * island classification: the Java-visible surface survives member for member. It is the third parser
 * to move, so `AbstractParser`'s and `PresenceParser`'s members are already Kotlin.
 *
 * - Every static keeps its `@JvmStatic` bridge: `MessageParser.java` calls `deviceIds`, and the
 *   Kotlin callers (`ChannelDiscoveryService`, `AxolotlService`, `AvatarFetching`) call `items`,
 *   `getItem`, `deviceIds`, `preKeyPublics`, `verification`, `bundle`, `preKeys` and `avatarData`
 *   through the class name.
 * - **`Integer.valueOf` is kept where the Java relied on its `NumberFormatException` for a `null`
 *   attribute** (`deviceIds`, `signedPreKeyId`, `preKeyPublics`). Kotlin's `?.toInt()` would answer
 *   `null` instead of throwing, which is a different outcome for exactly the packets those catches
 *   exist to log.
 * - **`removed |= ...` becomes `removed or ...`, not `||`**: Java's `|=` evaluates the right-hand
 *   side every time, and `removeBlockedConversationEntries` is called for every jid.
 * - The two `catch (A | B e)` multi-catches become two clauses with the same body, so an unrelated
 *   runtime exception still propagates as it did in Java.
 * - Java object-plus-String concatenation became a string template at the one Jid-prefixed log site.
 * - `verification`'s certificate array is built with `arrayOfNulls` and cast to
 *   `Array<X509Certificate>`: the Java declared `X509Certificate[]` while a skipped (`null`) entry
 *   leaves a null hole, and the Kotlin caller `AxolotlService.kt:1020` reads `first[0]` as a
 *   `Certificate`, which is the type the Java gave it.
 */
class IqParser(
    service: XmppConnectionService,
    account: AccountRef,
) : AbstractParser(service, account),
    Consumer<Iq> {

    companion object {

        @JvmStatic
        fun items(packet: Iq): MutableList<Jid> {
            val items = ArrayList<Jid>()
            val query = packet.findChild("query", Namespace.DISCO_ITEMS)
            if (query == null) {
                return items
            }
            for (child in query.getChildren()) {
                if ("item" == child.getName()) {
                    val jid = child.getAttributeAsJid("jid")
                    if (jid != null) {
                        items.add(jid)
                    }
                }
            }
            return items
        }

        @JvmStatic
        fun avatarData(packet: Iq): String? {
            val pubsub = packet.findChild("pubsub", Namespace.PUBSUB)
            if (pubsub == null) {
                return null
            }
            val items = pubsub.findChild("items")
            if (items == null) {
                return null
            }
            return avatarData(items)
        }

        @JvmStatic
        fun getItem(packet: Iq): Element? {
            val pubsub = packet.findChild("pubsub", Namespace.PUBSUB)
            if (pubsub == null) {
                return null
            }
            val items = pubsub.findChild("items")
            if (items == null) {
                return null
            }
            return items.findChild("item")
        }

        // The Java declared Set<Integer> and its Kotlin callers (AxolotlService, MessageParser)
        // hand the answer straight to OmemoSessionPort.registerDevices, whose port parameter is a
        // Kotlin MutableSet; a read-only Set would compile here and redden :crypto instead.
        @JvmStatic
        fun deviceIds(item: Element?): MutableSet<Int> {
            val deviceIds = HashSet<Int>()
            if (item != null) {
                val list = item.findChild("list")
                if (list != null) {
                    for (device in list.getChildren()) {
                        if (device.getName() != "device") {
                            continue
                        }
                        try {
                            // Java's Integer.valueOf answers a NumberFormatException for a null
                            // attribute; ?.toInt() would answer null and add nothing to the set.
                            val id = Integer.valueOf(device.getAttribute("id"))
                            deviceIds.add(id)
                        } catch (e: NumberFormatException) {
                            Log.e(
                                Config.LOGTAG,
                                OmemoSessionPort.LOGPREFIX +
                                    " : " +
                                    "Encountered invalid <device> node in PEP (" +
                                    e.message +
                                    "):" +
                                    device.toString() +
                                    ", skipping...",
                            )
                        }
                    }
                }
            }
            return deviceIds
        }

        private fun signedPreKeyId(bundle: Element): Int? {
            val signedPreKeyPublic = bundle.findChild("signedPreKeyPublic")
            if (signedPreKeyPublic == null) {
                return null
            }
            return try {
                Integer.valueOf(signedPreKeyPublic.getAttribute("signedPreKeyId"))
            } catch (e: NumberFormatException) {
                null
            }
        }

        private fun signedPreKeyPublic(bundle: Element): ECPublicKey? {
            var publicKey: ECPublicKey? = null
            val signedPreKeyPublic = bundle.findChildContent("signedPreKeyPublic")
            if (signedPreKeyPublic == null) {
                return null
            }
            try {
                publicKey = Curve.decodePoint(base64decode(signedPreKeyPublic), 0)
            } catch (e: IllegalArgumentException) {
                Log.e(
                    Config.LOGTAG,
                    OmemoSessionPort.LOGPREFIX +
                        " : " +
                        "Invalid signedPreKeyPublic in PEP: " +
                        e.message,
                )
            } catch (e: InvalidKeyException) {
                Log.e(
                    Config.LOGTAG,
                    OmemoSessionPort.LOGPREFIX +
                        " : " +
                        "Invalid signedPreKeyPublic in PEP: " +
                        e.message,
                )
            }
            return publicKey
        }

        private fun signedPreKeySignature(bundle: Element): ByteArray? {
            val signedPreKeySignature = bundle.findChildContent("signedPreKeySignature")
            if (signedPreKeySignature == null) {
                return null
            }
            return try {
                base64decode(signedPreKeySignature)
            } catch (e: IllegalArgumentException) {
                Log.e(
                    Config.LOGTAG,
                    OmemoSessionPort.LOGPREFIX + " : Invalid base64 in signedPreKeySignature",
                )
                null
            }
        }

        private fun identityKey(bundle: Element): IdentityKey? {
            val identityKey = bundle.findChildContent("identityKey")
            if (identityKey == null) {
                return null
            }
            return try {
                IdentityKey(base64decode(identityKey), 0)
            } catch (e: IllegalArgumentException) {
                Log.e(
                    Config.LOGTAG,
                    OmemoSessionPort.LOGPREFIX +
                        " : " +
                        "Invalid identityKey in PEP: " +
                        e.message,
                )
                null
            } catch (e: InvalidKeyException) {
                Log.e(
                    Config.LOGTAG,
                    OmemoSessionPort.LOGPREFIX +
                        " : " +
                        "Invalid identityKey in PEP: " +
                        e.message,
                )
                null
            }
        }

        @JvmStatic
        fun preKeyPublics(packet: Iq): MutableMap<Int, ECPublicKey>? {
            val preKeyRecords = HashMap<Int, ECPublicKey>()
            val item = getItem(packet)
            if (item == null) {
                Log.d(
                    Config.LOGTAG,
                    OmemoSessionPort.LOGPREFIX +
                        " : " +
                        "Couldn't find <item> in bundle IQ packet: " +
                        packet,
                )
                return null
            }
            val bundleElement = item.findChild("bundle")
            if (bundleElement == null) {
                return null
            }
            val prekeysElement = bundleElement.findChild("prekeys")
            if (prekeysElement == null) {
                Log.d(
                    Config.LOGTAG,
                    OmemoSessionPort.LOGPREFIX +
                        " : " +
                        "Couldn't find <prekeys> in bundle IQ packet: " +
                        packet,
                )
                return null
            }
            for (preKeyPublicElement in prekeysElement.getChildren()) {
                if (preKeyPublicElement.getName() != "preKeyPublic") {
                    Log.d(
                        Config.LOGTAG,
                        OmemoSessionPort.LOGPREFIX +
                            " : " +
                            "Encountered unexpected tag in prekeys list: " +
                            preKeyPublicElement,
                    )
                    continue
                }
                val preKey = preKeyPublicElement.getContent()
                if (preKey == null) {
                    continue
                }
                var preKeyId: Int? = null
                try {
                    preKeyId = Integer.valueOf(preKeyPublicElement.getAttribute("preKeyId"))
                    val preKeyPublic = Curve.decodePoint(base64decode(preKey), 0)
                    preKeyRecords.put(preKeyId, preKeyPublic)
                } catch (e: NumberFormatException) {
                    Log.e(
                        Config.LOGTAG,
                        OmemoSessionPort.LOGPREFIX +
                            " : " +
                            "could not parse preKeyId from preKey " +
                            preKeyPublicElement.toString(),
                    )
                } catch (e: Throwable) {
                    Log.e(
                        Config.LOGTAG,
                        OmemoSessionPort.LOGPREFIX +
                            " : " +
                            "Invalid preKeyPublic (ID=" +
                            preKeyId +
                            ") in PEP: " +
                            e.message +
                            ", skipping...",
                    )
                }
            }
            return preKeyRecords
        }

        private fun base64decode(input: String): ByteArray =
            BaseEncoding.base64().decode(CharMatcher.whitespace().removeFrom(input))

        @JvmStatic
        fun verification(packet: Iq): Pair<Array<X509Certificate>, ByteArray>? {
            val item = getItem(packet)
            val verification =
                if (item != null) item.findChild("verification", OmemoSessionPort.PEP_PREFIX) else null
            val chain = if (verification != null) verification.findChild("chain") else null
            val signature = if (verification != null) verification.findChildContent("signature") else null
            if (chain != null && signature != null) {
                val certElements = chain.getChildren()
                // The Java declared X509Certificate[] and a skipped (null) content leaves a null
                // hole; the array type is the one the Java exposed, so the cast states that.
                @Suppress("UNCHECKED_CAST")
                val certificates =
                    arrayOfNulls<X509Certificate>(certElements.size) as Array<X509Certificate>
                try {
                    val certificateFactory = CertificateFactory.getInstance("X.509")
                    var i = 0
                    for (certElement in certElements) {
                        val cert = certElement.getContent()
                        if (cert == null) {
                            continue
                        }
                        certificates[i] =
                            certificateFactory.generateCertificate(
                                ByteArrayInputStream(BaseEncoding.base64().decode(cert)),
                            ) as X509Certificate
                        ++i
                    }
                    return Pair(certificates, BaseEncoding.base64().decode(signature))
                } catch (e: CertificateException) {
                    return null
                }
            } else {
                return null
            }
        }

        @JvmStatic
        fun bundle(bundle: Iq): PreKeyBundle? {
            val bundleItem = getItem(bundle)
            if (bundleItem == null) {
                return null
            }
            val bundleElement = bundleItem.findChild("bundle")
            if (bundleElement == null) {
                return null
            }
            val signedPreKeyPublic = signedPreKeyPublic(bundleElement)
            val signedPreKeyId = signedPreKeyId(bundleElement)
            val signedPreKeySignature = signedPreKeySignature(bundleElement)
            val identityKey = identityKey(bundleElement)
            if (signedPreKeyId == null ||
                signedPreKeyPublic == null ||
                identityKey == null ||
                signedPreKeySignature == null ||
                signedPreKeySignature.isEmpty()
            ) {
                return null
            }
            return PreKeyBundle(
                0,
                0,
                0,
                null,
                signedPreKeyId,
                signedPreKeyPublic,
                signedPreKeySignature,
                identityKey,
            )
        }

        @JvmStatic
        fun preKeys(preKeys: Iq): MutableList<PreKeyBundle> {
            val bundles = ArrayList<PreKeyBundle>()
            val preKeyPublics = preKeyPublics(preKeys)
            if (preKeyPublics != null) {
                for (preKeyId in preKeyPublics.keys) {
                    val preKeyPublic = preKeyPublics[preKeyId]
                    bundles.add(PreKeyBundle(0, 0, preKeyId, preKeyPublic, 0, null, null, null))
                }
            }

            return bundles
        }
    }

    private fun rosterItems(account: AccountRef, query: Element) {
        val version = query.getAttribute("ver")
        if (version != null) {
            account.getRoster().setVersion(version)
        }
        for (item in query.getChildren()) {
            if (item.getName() == "item") {
                val jid = Jid.Invalid.getNullForInvalid(item.getAttributeAsJid("jid"))
                if (jid == null) {
                    continue
                }
                val name = item.getAttribute("name")
                val subscription = item.getAttribute("subscription")
                val contact = account.getRoster().getContact(jid)
                val bothPre =
                    contact.getOption(ContactRef.OptionsRef.TO) &&
                        contact.getOption(ContactRef.OptionsRef.FROM)
                if (!contact.getOption(ContactRef.OptionsRef.DIRTY_PUSH)) {
                    contact.setServerName(name)
                    contact.parseGroupsFromElement(item)
                }
                if ("remove" == subscription) {
                    contact.resetOption(ContactRef.OptionsRef.IN_ROSTER)
                    contact.resetOption(ContactRef.OptionsRef.DIRTY_DELETE)
                    contact.resetOption(ContactRef.OptionsRef.PREEMPTIVE_GRANT)
                } else {
                    contact.setOption(ContactRef.OptionsRef.IN_ROSTER)
                    contact.resetOption(ContactRef.OptionsRef.DIRTY_PUSH)
                    contact.parseSubscriptionFromElement(item)
                }
                val both =
                    contact.getOption(ContactRef.OptionsRef.TO) &&
                        contact.getOption(ContactRef.OptionsRef.FROM)
                if ((both != bothPre) && both) {
                    Log.d(
                        Config.LOGTAG,
                        "${account.getJid().asBareJid()}: gained mutual presence subscription with" +
                            " ${contact.getJid()}",
                    )
                    val axolotlService = account.getOmemoSession()
                    if (axolotlService != null) {
                        axolotlService.clearErrorsInFetchStatusMap(contact.getJid())
                    }
                }
                mXmppConnectionService.getAvatarService().clear(contact)
            }
        }
        mXmppConnectionService.updateConversationUi()
        mXmppConnectionService.updateRosterUi(uk.xa0.tulkki.xmpp.services.UpdateRosterReason.PUSH)
        mXmppConnectionService.getShortcutService().refresh()
        mXmppConnectionService.syncRoster(account)
    }

    override fun accept(packet: Iq) {
        val isGet = packet.getType() == Iq.Type.GET
        if (packet.getType() == Iq.Type.ERROR || packet.getType() == Iq.Type.TIMEOUT) {
            return
        }
        if (packet.hasChild("query", Namespace.ROSTER) && packet.fromServer(account)) {
            val query = packet.findChild("query")
            // If this is in response to a query for the whole roster:
            if (packet.getType() == Iq.Type.RESULT) {
                account.getRoster().markAllAsNotInRoster()
            }
            this.rosterItems(account, query ?: throw NullPointerException())
        } else if (packet.hasChild("pubsub", Namespace.PUBSUB)) {
            val pubsub = packet.findChild("pubsub", Namespace.PUBSUB) ?: throw NullPointerException()
            val items = pubsub.findChild("items")
            if (items != null) {
                val node = items.getAttribute("node")
                if (Namespace.PUBSUB_STORIES == node) {
                    val from = packet.getFrom()
                    if (from != null) {
                        for (item in items.getChildren()) {
                            if (item.getName() == "item") {
                                val story = XmppConnectionService.dataStatics().newStory(item, from)
                                if (story != null) {
                                    mXmppConnectionService.onStoryReceived(story)
                                }
                            }
                        }
                    }
                } else if (node != null &&
                    (node == Namespace.ATOM ||
                        node.startsWith("urn:xmpp:microblog:0") ||
                        node.startsWith(Namespace.PUBSUB_SOCIAL_FEED))
                ) {
                    for (child in items.getChildren()) {
                        if ("item" == child.getName()) {
                            val entry = child.findChild("entry", Namespace.ATOM)
                            if (entry != null) {
                                try {
                                    val inReplyTo =
                                        entry.findChild(
                                            "in-reply-to",
                                            "http://purl.org/syndication/thread/1.0",
                                        )
                                    if (inReplyTo != null) {
                                        val comment =
                                            XmppConnectionService.dataStatics().newComment(entry)
                                        var originalPostUuid = inReplyTo.getAttribute("ref")
                                        if (originalPostUuid != null &&
                                            originalPostUuid.startsWith("urn:uuid:")
                                        ) {
                                            originalPostUuid = originalPostUuid.substring(9)
                                        }
                                        mXmppConnectionService.notifyOnCommentReceived(
                                            originalPostUuid,
                                            comment,
                                        )
                                    } else {
                                        // Handle items that are not comments as new posts.
                                        val post = XmppConnectionService.dataStatics().newPost(child)
                                        if (post != null) {
                                            mXmppConnectionService.onPostReceived(post, account)
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.d(
                                        Config.LOGTAG,
                                        "error creating post/comment from pubsub item in message",
                                        e,
                                    )
                                }
                            }
                        } else if ("retract" == child.getName()) {
                            val postId = child.getAttribute("id")
                            if (postId != null) {
                                mXmppConnectionService.onPostRetracted(postId)
                            }
                        }
                    }
                }
            }
        } else if ((packet.hasChild("block", Namespace.BLOCKING) ||
                packet.hasChild("blocklist", Namespace.BLOCKING)) &&
            packet.fromServer(account)
        ) {
            // Block list or block push.
            Log.d(Config.LOGTAG, "Received blocklist update from server")
            val blocklist = packet.findChild("blocklist", Namespace.BLOCKING)
            val block = packet.findChild("block", Namespace.BLOCKING)
            val items: Collection<Element>? =
                if (blocklist != null) {
                    blocklist.getChildren()
                } else {
                    if (block != null) block.getChildren() else null
                }
            // If this is a response to a blocklist query, clear the block list and replace with the
            // new one.
            // Otherwise, just update the existing blocklist.
            if (packet.getType() == Iq.Type.RESULT) {
                account.clearBlocklist()
                (account.getXmppConnection() ?: throw NullPointerException("account has no connection"))
                    .getFeatures()
                    .setBlockListRequested(true)
            }
            if (items != null) {
                val jids: MutableCollection<Jid> = ArrayList(items.size)
                // Create a collection of Jids from the packet
                for (item in items) {
                    if (item.getName() == "item") {
                        val jid = Jid.Invalid.getNullForInvalid(item.getAttributeAsJid("jid"))
                        if (jid != null) {
                            jids.add(jid)
                        }
                    }
                }
                account.getBlocklist().addAll(jids)
                if (packet.getType() == Iq.Type.SET) {
                    var removed = false
                    for (jid in jids) {
                        // Java's |= is not || - the call happens for every jid.
                        removed = removed or
                            mXmppConnectionService.removeBlockedConversationEntries(account, jid)
                    }
                    if (removed) {
                        mXmppConnectionService.updateConversationUi()
                    }
                }
            }
            // Update the UI
            mXmppConnectionService.updateBlocklistUi(OnUpdateBlocklist.Status.BLOCKED)
            if (packet.getType() == Iq.Type.SET) {
                val response = packet.generateResponse(Iq.Type.RESULT)
                mXmppConnectionService.sendIqPacket(account, response, null)
            }
        } else if (packet.hasChild("unblock", Namespace.BLOCKING) &&
            packet.fromServer(account) &&
            packet.getType() == Iq.Type.SET
        ) {
            Log.d(Config.LOGTAG, "Received unblock update from server")
            val items: Collection<Element> =
                (packet.findChild("unblock", Namespace.BLOCKING) ?: throw NullPointerException())
                    .getChildren()
            if (items.isEmpty()) {
                // No children to unblock == unblock all
                account.clearBlocklist()
            } else {
                val jids: MutableCollection<Jid> = ArrayList(items.size)
                for (item in items) {
                    if (item.getName() == "item") {
                        val jid = Jid.Invalid.getNullForInvalid(item.getAttributeAsJid("jid"))
                        if (jid != null) {
                            jids.add(jid)
                        }
                    }
                }
                account.getBlocklist().removeAll(jids)
            }
            mXmppConnectionService.updateBlocklistUi(OnUpdateBlocklist.Status.UNBLOCKED)
            val response = packet.generateResponse(Iq.Type.RESULT)
            mXmppConnectionService.sendIqPacket(account, response, null)
        } else if (packet.hasChild("open", "http://jabber.org/protocol/ibb") ||
            packet.hasChild("data", "http://jabber.org/protocol/ibb") ||
            packet.hasChild("close", "http://jabber.org/protocol/ibb")
        ) {
            mXmppConnectionService.getJingleConnectionManager().deliverIbbPacket(account, packet)
        } else if (packet.hasChild("query", "http://jabber.org/protocol/disco#info")) {
            val response =
                mXmppConnectionService.getIqGenerator().discoResponse(account, packet)
            mXmppConnectionService.sendIqPacket(account, response, null)
        } else if (packet.hasChild("query", "jabber:iq:version") && isGet) {
            val response = mXmppConnectionService.getIqGenerator().versionResponse(packet)
            mXmppConnectionService.sendIqPacket(account, response, null)
        } else if (packet.hasChild("ping", "urn:xmpp:ping") && isGet) {
            val response = packet.generateResponse(Iq.Type.RESULT)
            mXmppConnectionService.sendIqPacket(account, response, null)
        } else if (packet.hasChild("time", "urn:xmpp:time") && isGet) {
            val response: Iq
            if (mXmppConnectionService.useTorToConnect() || account.isOnion()) {
                response = packet.generateResponse(Iq.Type.ERROR)
                val error = response.addChild("error")
                error.setAttribute("type", "cancel")
                error.addChild("not-allowed", "urn:ietf:params:xml:ns:xmpp-stanzas")
            } else {
                response = mXmppConnectionService.getIqGenerator().entityTimeResponse(packet)
            }
            mXmppConnectionService.sendIqPacket(account, response, null)
        } else if (packet.hasChild("push", Namespace.UNIFIED_PUSH) &&
            packet.getType() == Iq.Type.SET
        ) {
            val transport = packet.getFrom()
            val push = packet.findChild("push", Namespace.UNIFIED_PUSH)
            val success =
                push != null &&
                    mXmppConnectionService.processUnifiedPushMessage(account, transport, push)
            val response: Iq
            if (success) {
                response = packet.generateResponse(Iq.Type.RESULT)
            } else {
                response = packet.generateResponse(Iq.Type.ERROR)
                val error = response.addChild("error")
                error.setAttribute("type", "cancel")
                error.setAttribute("code", "404")
                error.addChild("item-not-found", "urn:ietf:params:xml:ns:xmpp-stanzas")
            }
            mXmppConnectionService.sendIqPacket(account, response, null)
        } else if (packet.getFrom() != null) {
            val packetFrom = packet.getFrom() ?: throw NullPointerException("from")
            val contact = account.getRoster().getContact(packetFrom)
            val conversation = mXmppConnectionService.find(account, packetFrom)
            if (packet.hasChild("data", "urn:xmpp:bob") &&
                isGet &&
                (if (conversation == null) {
                    contact != null && contact.canInferPresence()
                } else {
                    conversation.canInferPresence()
                })
            ) {
                mXmppConnectionService.sendIqPacket(
                    account,
                    mXmppConnectionService.getIqGenerator().bobResponse(packet),
                    null,
                )
            } else if (packet.getType() == Iq.Type.GET || packet.getType() == Iq.Type.SET) {
                val response = packet.generateResponse(Iq.Type.ERROR)
                val error = response.addChild("error")
                error.setAttribute("type", "cancel")
                error.addChild("feature-not-implemented", "urn:ietf:params:xml:ns:xmpp-stanzas")
                (account.getXmppConnection() ?: throw NullPointerException("account has no connection"))
                    .sendIqPacket(response, null)
            }
        }
    }
}
