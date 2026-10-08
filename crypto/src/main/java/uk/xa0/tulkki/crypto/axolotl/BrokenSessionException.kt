package uk.xa0.tulkki.crypto.axolotl

import org.whispersystems.libsignal.SignalProtocolAddress

import uk.xa0.tulkki.xmpp.crypto.OmemoFailure

class BrokenSessionException(address: SignalProtocolAddress, e: Exception) :
    OmemoFailure.BrokenSession(address, e)
