package uk.xa0.tulkki.xmpp.models

/**
 * A stream feature that advertises SASL mechanisms (`mechanisms` and SASL2 `authentication`).
 * Converted from the Java abstract class; only Kotlin subclasses extend it, the constructor was
 * `public` and stays so, and `getMechanismNames` stays abstract over a read-only [Collection].
 */
abstract class AuthenticationStreamFeature(clazz: Class<out AuthenticationStreamFeature>) :
    StreamFeature(clazz) {

    abstract fun getMechanismNames(): Collection<String>
}
