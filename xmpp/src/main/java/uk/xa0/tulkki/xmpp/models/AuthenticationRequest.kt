package uk.xa0.tulkki.xmpp.models

import uk.xa0.tulkki.crypto.sasl.SaslMechanism

/**
 * A SASL authentication request (`auth` and SASL2 `authenticate`). Converted from the Java abstract
 * class; only Kotlin subclasses extend it, the constructor keeps its `protected` visibility, and
 * `setMechanism` stays abstract.
 */
abstract class AuthenticationRequest protected constructor(
    clazz: Class<out AuthenticationRequest>,
) : StreamElement(clazz) {

    abstract fun setMechanism(mechanism: SaslMechanism)
}
