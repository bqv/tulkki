package uk.xa0.tulkki.xmpp.models

import uk.xa0.tulkki.xmpp.models.sasl.SaslError

/**
 * A SASL failure element (`failure` and SASL2 `failure`). Converted from the Java abstract class;
 * only Kotlin subclasses extend it, the constructor keeps its `protected` visibility, and both reads
 * answer null when the server sent neither: `getErrorCondition` is the one [SaslError] child and
 * `getText` is the optional `<text/>`.
 */
abstract class AuthenticationFailure protected constructor(
    clazz: Class<out AuthenticationFailure>,
) : StreamElement(clazz) {

    fun getErrorCondition(): SaslError? = getExtension(SaslError::class.java)

    fun getText(): String? = findChildContent("text")
}
