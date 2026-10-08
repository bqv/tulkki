package uk.xa0.tulkki.xmpp.services

import android.content.Context
import android.net.Uri
import uk.xa0.tulkki.libs.AudioDevice
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.jingle.AbstractJingleConnection
import uk.xa0.tulkki.xmpp.jingle.Media
import uk.xa0.tulkki.xmpp.refs.AccountRef

/**
 * Tulkki: what the island asks of the call integration without holding an instance.
 *
 * <p>{@link #create} is the constructor the two jingle classes call, and the rest are the statics
 * {@code CallIntegration} and {@code CallIntegrationConnectionService} publish.
 */
interface CallIntegrationFactory {

    fun create(context: Context): CallIntegrationPort

    @JvmSuppressWildcards
    fun initialAudioDevice(media: Set<Media>): AudioDevice

    fun address(contact: Jid): Uri

    fun addNewIncomingCall(context: Context, id: AbstractJingleConnection.Id): Boolean

    fun hasSystemFeature(context: Context): Boolean

    fun togglePhoneAccountsAsync(context: Context, accounts: Collection<AccountRef>)

    fun togglePhoneAccountAsync(context: Context, account: AccountRef)

    fun unregisterPhoneAccount(context: Context, account: AccountRef)
}
