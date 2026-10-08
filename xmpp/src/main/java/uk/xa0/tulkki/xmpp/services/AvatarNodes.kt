package uk.xa0.tulkki.xmpp.services

import android.util.Log
import uk.xa0.tulkki.app.generator.IqGenerator
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xmpp.refs.AccountRef
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tulkki: avatar deletion - the PEP nodes and the vCard half - lifted out of `XmppConnectionService`
 *.
 *
 * The coupling was the service's **private** `mIqGenerator` (chunk `C55`), which does not move with
 * this chunk, so it travels in as an [IqGenerator]; it is `final` and built in place, so the value is
 * the one the Java read at the top of each body. Everything else is already public on the service -
 * `databaseBackend`, `getAvatarService()`, `sendIqPacket`, `updateAccountUi` - so no visibility was
 * widened.
 *
 * [deleteAvatar]'s one-shot [AtomicBoolean] is the Java's: whichever of the two callbacks answers
 * first performs the account write, clears the avatar and refreshes the UI, and the other is a
 * no-op. The three private helpers of the Java (`deletePepNode` in its three-argument form and
 * `deleteVcardAvatar`) had no caller outside this chunk, so they moved here whole and the service
 * keeps only the two public entry points as one-line delegations.
 */
object AvatarNodes {

    @JvmStatic
    fun deleteAvatar(service: XmppConnectionService, account: AccountRef, iqGenerator: IqGenerator) {
        val executed = AtomicBoolean(false)
        val onDeleted = Runnable {
            if (executed.compareAndSet(false, true)) {
                account.setAvatar(null)
                (service.databaseBackend ?: throw NullPointerException("database backend is not open")).updateAccount(account)
                service.getAvatarService().clear(account)
                service.updateAccountUi()
            }
        }
        deleteVcardAvatar(service, account, onDeleted, iqGenerator)
        deletePepNode(service, account, Namespace.AVATAR_DATA, null, iqGenerator)
        deletePepNode(service, account, Namespace.AVATAR_METADATA, onDeleted, iqGenerator)
    }

    @JvmStatic
    fun deletePepNode(
        service: XmppConnectionService,
        account: AccountRef,
        node: String,
        iqGenerator: IqGenerator,
    ) {
        deletePepNode(service, account, node, null, iqGenerator)
    }

    @JvmStatic
    fun deletePepNode(
        service: XmppConnectionService,
        account: AccountRef,
        node: String,
        runnable: Runnable?,
        iqGenerator: IqGenerator,
    ) {
        val request = iqGenerator.deleteNode(node)
        service.sendIqPacket(account, request) { packet ->
            if (packet.getType() == Iq.Type.RESULT) {
                Log.d(
                    Config.LOGTAG,
                    "${account.getJid().asBareJid()}: successfully deleted pep node $node",
                )
                if (runnable != null) {
                    runnable.run()
                }
            } else {
                Log.d(
                    Config.LOGTAG,
                    "${account.getJid().asBareJid()}: failed to delete $packet",
                )
            }
        }
    }

    @JvmStatic
    fun deleteVcardAvatar(
        service: XmppConnectionService,
        account: AccountRef,
        runnable: Runnable,
        iqGenerator: IqGenerator,
    ) {
        val retrieveVcard = iqGenerator.retrieveVcardAvatar(account.getJid().asBareJid())
        service.sendIqPacket(account, retrieveVcard) { response ->
            if (response.getType() != Iq.Type.RESULT) {
                Log.d(
                    Config.LOGTAG,
                    "${account.getJid().asBareJid()}: no vCard set. nothing to do",
                )
                return@sendIqPacket
            }
            val vcard: Element = response.findChild("vCard", "vcard-temp")
                ?: run {
                    Log.d(
                        Config.LOGTAG,
                        "${account.getJid().asBareJid()}: no vCard set. nothing to do",
                    )
                    return@sendIqPacket
                }
            val photo = vcard.findChild("PHOTO") ?: vcard.addChild("PHOTO")
            photo.clearChildren()
            val publication = Iq(Iq.Type.SET)
            publication.setTo(account.getJid().asBareJid())
            publication.addChild(vcard)
            service.sendIqPacket(account, publication) { publicationResponse ->
                if (publicationResponse.getType() == Iq.Type.RESULT) {
                    Log.d(
                        Config.LOGTAG,
                        "${account.getJid().asBareJid()}: successfully deleted vcard avatar",
                    )
                    runnable.run()
                } else {
                    Log.d(
                        Config.LOGTAG,
                        "failed to publish vcard ${publicationResponse.getErrorCondition()}",
                    )
                }
            }
        }
    }
}
