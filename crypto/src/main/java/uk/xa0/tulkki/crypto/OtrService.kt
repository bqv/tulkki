package uk.xa0.tulkki.crypto

import android.util.Log

import net.java.otr4j.OtrEngineHost
import net.java.otr4j.OtrException
import net.java.otr4j.OtrPolicy
import net.java.otr4j.OtrPolicyImpl
import net.java.otr4j.crypto.OtrCryptoEngineImpl
import net.java.otr4j.crypto.OtrCryptoException
import net.java.otr4j.session.FragmenterInstructions
import net.java.otr4j.session.InstanceTag
import net.java.otr4j.session.SessionID

import org.json.JSONException
import org.json.JSONObject

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.NoSuchAlgorithmException
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.DSAPrivateKeySpec
import java.security.spec.DSAPublicKeySpec
import java.security.spec.InvalidKeySpecException

import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.app.generator.MessageGenerator
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.chatstate.ChatState
import uk.xa0.tulkki.xmpp.jid.OtrJidHelper
import uk.xa0.tulkki.xmpp.models.stanza.Message

class OtrService(
    service: XmppConnectionService,
    account: OmemoAccount
) : OtrCryptoEngineImpl(), OtrEngineHost, uk.xa0.tulkki.xmpp.services.OtrPeerPort {

    private var account: OmemoAccount = account
    private var otrPolicy: OtrPolicy = OtrPolicyImpl()
    private var keyPair: KeyPair? = null
    private val mXmppConnectionService: XmppConnectionService = service

    init {
        this.otrPolicy.setAllowV1(false)
        this.otrPolicy.setAllowV2(true)
        this.otrPolicy.setAllowV3(true)
        this.keyPair = loadKey(this.account.getKeys())
    }

    private fun loadKey(keys: JSONObject?): KeyPair? {
        if (keys == null) {
            return null
        }
        synchronized(keys) {
            try {
                val x = BigInteger(keys.getString("otr_x"), 16)
                val y = BigInteger(keys.getString("otr_y"), 16)
                val p = BigInteger(keys.getString("otr_p"), 16)
                val q = BigInteger(keys.getString("otr_q"), 16)
                val g = BigInteger(keys.getString("otr_g"), 16)
                val keyFactory = KeyFactory.getInstance("DSA")
                val pubKeySpec = DSAPublicKeySpec(y, p, q, g)
                val privateKeySpec = DSAPrivateKeySpec(x, p, q, g)
                val publicKey = keyFactory.generatePublic(pubKeySpec)
                val privateKey = keyFactory.generatePrivate(privateKeySpec)
                return KeyPair(publicKey, privateKey)
            } catch (e: JSONException) {
                return null
            } catch (e: NoSuchAlgorithmException) {
                return null
            } catch (e: InvalidKeySpecException) {
                return null
            }
        }
    }

    private fun store(): PgpStore =
        this.account.getPgpStore() ?: throw NullPointerException("pgpStore")

    private fun saveKey() {
        val keyPair = this.keyPair ?: throw NullPointerException("keyPair")
        val publicKey = keyPair.getPublic()
        val privateKey = keyPair.getPrivate()
        try {
            val keyFactory = KeyFactory.getInstance("DSA")
            val privateKeySpec =
                keyFactory.getKeySpec(privateKey, DSAPrivateKeySpec::class.java)
            val publicKeySpec = keyFactory.getKeySpec(publicKey, DSAPublicKeySpec::class.java)
            this.account.setKey("otr_x", privateKeySpec.getX().toString(16))
            this.account.setKey("otr_g", privateKeySpec.getG().toString(16))
            this.account.setKey("otr_p", privateKeySpec.getP().toString(16))
            this.account.setKey("otr_q", privateKeySpec.getQ().toString(16))
            this.account.setKey("otr_y", publicKeySpec.getY().toString(16))
        } catch (e: NoSuchAlgorithmException) {
            e.printStackTrace()
        } catch (e: InvalidKeySpecException) {
            e.printStackTrace()
        }
    }

    override fun askForSecret(id: SessionID, instanceTag: InstanceTag, question: String) {
        try {
            val jid = OtrJidHelper.fromSessionID(id)
            val conversation = store().findConversation(this.account, jid)
            if (conversation != null) {
                conversation.setSmpHint(question)
                conversation.setSmpStatus(OtrPeer.SMP_STATUS_CONTACT_REQUESTED)
                mXmppConnectionService.updateConversationUi()
            }
        } catch (e: IllegalArgumentException) {
            Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid().toString() + ": smp in invalid session " + id.toString()
            )
        }
    }

    @Throws(OtrException::class)
    override fun finishedSessionMessage(arg0: SessionID, arg1: String) {
    }

    override fun getFallbackMessage(arg0: SessionID): String =
        MessageGenerator.OTR_FALLBACK_MESSAGE

    override fun getLocalFingerprintRaw(arg0: SessionID): ByteArray? {
        return try {
            getFingerprintRaw(getPublicKey())
        } catch (e: OtrCryptoException) {
            null
        }
    }

    fun getPublicKey(): PublicKey? {
        val keyPair = this.keyPair ?: return null
        return keyPair.getPublic()
    }

    @Throws(OtrException::class)
    override fun getLocalKeyPair(arg0: SessionID): KeyPair? {
        if (this.keyPair == null) {
            try {
                val kg = KeyPairGenerator.getInstance("DSA")
                this.keyPair = kg.genKeyPair()
                this.saveKey()
                store().updateAccount()
            } catch (e: NoSuchAlgorithmException) {
                Log.d(Config.LOGTAG, "error generating key pair " + e.message)
            }
        }
        return this.keyPair
    }

    override fun getReplyForUnreadableMessage(arg0: SessionID): String? {
        // TODO Auto-generated method stub
        return null
    }

    override fun getSessionPolicy(arg0: SessionID): OtrPolicy = otrPolicy

    @Throws(OtrException::class)
    override fun injectMessage(session: SessionID, body: String) {
        val packet = Message()
        packet.setFrom(account.getJid())
        if (session.getUserID().isEmpty()) {
            packet.setAttribute("to", session.getAccountID())
        } else {
            packet.setAttribute("to", session.getAccountID() + "/" + session.getUserID())
        }
        packet.setBody(body)
        MessageGenerator.addMessageHints(packet)
        try {
            val jid = OtrJidHelper.fromSessionID(session)
            val conversation = store().findConversation(account, jid)
            if (conversation != null &&
                conversation.setOutgoingChatState(Config.DEFAULT_CHAT_STATE)
            ) {
                if (mXmppConnectionService.sendChatStates()) {
                    packet.addChild(ChatState.toElement(conversation.getOutgoingChatState()))
                }
            }
        } catch (e: IllegalArgumentException) {
        }

        packet.setType(Message.Type.CHAT)
        packet.addChild("encryption", "urn:xmpp:eme:0")
            .setAttribute("namespace", "urn:xmpp:otr:0")
        val connection =
            account.getXmppConnection() ?: throw NullPointerException("xmppConnection")
        connection.sendMessagePacket(packet)
    }

    override fun messageFromAnotherInstanceReceived(session: SessionID) {
        sendOtrErrorMessage(session, "Message from another OTR-instance received")
    }

    override fun multipleInstancesDetected(arg0: SessionID) {
        // TODO Auto-generated method stub
    }

    @Throws(OtrException::class)
    override fun requireEncryptedMessage(arg0: SessionID, arg1: String) {
        // TODO Auto-generated method stub
    }

    @Throws(OtrException::class)
    override fun showError(arg0: SessionID, arg1: String) {
        Log.d(Config.LOGTAG, "show error")
    }

    @Throws(OtrException::class)
    override fun smpAborted(id: SessionID) {
        setSmpStatus(id, OtrPeer.SMP_STATUS_NONE)
    }

    private fun setSmpStatus(id: SessionID, status: Int) {
        try {
            val jid = OtrJidHelper.fromSessionID(id)
            val conversation = store().findConversation(this.account, jid)
            if (conversation != null) {
                conversation.setSmpStatus(status)
                mXmppConnectionService.updateConversationUi()
            }
        } catch (e: IllegalArgumentException) {
        }
    }

    @Throws(OtrException::class)
    override fun smpError(id: SessionID, arg1: Int, arg2: Boolean) {
        setSmpStatus(id, OtrPeer.SMP_STATUS_NONE)
    }

    @Throws(OtrException::class)
    override fun unencryptedMessageReceived(arg0: SessionID, arg1: String) {
        throw OtrException(Exception("unencrypted message received"))
    }

    @Throws(OtrException::class)
    override fun unreadableMessageReceived(session: SessionID) {
        Log.d(Config.LOGTAG, "unreadable message received")
        sendOtrErrorMessage(session, "You sent me an unreadable OTR-encrypted message")
    }

    fun sendOtrErrorMessage(session: SessionID, errorText: String) {
        try {
            val jid = OtrJidHelper.fromSessionID(session)
            val conversation = store().findConversation(account, jid)
            val id = conversation?.getLastReceivedOtrMessageId()
            if (id != null && conversation != null) {
                val packet = mXmppConnectionService.getMessageGenerator()
                    .generateOtrError(jid, id, errorText)
                packet.setFrom(account.getJid())
                store().sendMessagePacket(packet)
                Log.d(Config.LOGTAG, packet.toString())
                Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid().toString() +
                        ": unreadable OTR message in " + conversation.getName()
                )
            }
        } catch (e: IllegalArgumentException) {
            return
        }
    }

    override fun unverify(id: SessionID, arg1: String) {
        setSmpStatus(id, OtrPeer.SMP_STATUS_FAILED)
    }

    override fun verify(id: SessionID, fingerprint: String, approved: Boolean) {
        Log.d(
            Config.LOGTAG,
            "OtrService.verify(" + id.toString() + "," + fingerprint + "," +
                approved.toString() + ")"
        )
        try {
            val jid = OtrJidHelper.fromSessionID(id)
            val conversation = store().findConversation(this.account, jid)
            if (conversation != null) {
                if (approved) {
                    conversation.getContact().addOtrFingerprint(fingerprint)
                }
                conversation.setSmpHint(null)
                conversation.setSmpStatus(OtrPeer.SMP_STATUS_VERIFIED)
                mXmppConnectionService.updateConversationUi()
                store().syncRosterToDisk(
                    conversation.getAccount() ?: throw NullPointerException("account")
                )
            }
        } catch (e: IllegalArgumentException) {
        }
    }

    override fun getFragmenterInstructions(sessionID: SessionID): FragmenterInstructions? {
        return null
    }
}
