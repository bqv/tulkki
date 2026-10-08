package uk.xa0.tulkki.crypto.axolotl

import uk.xa0.tulkki.xmpp.crypto.OmemoFailure

class OutdatedSenderException(msg: String) : OmemoFailure.OutdatedSender(msg)
