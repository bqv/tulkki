package uk.xa0.tulkki.xmpp.services

/**
 * Tulkki: the two outcomes of a password change, un-nested out of `XmppConnectionService`
 *.
 *
 * Converted from the Java. Neither method takes a parameter, so the nullability question that kept
 * this family Java does not arise. The one implementer is Kotlin
 * (`ui/ChangePasswordActivity.kt:15`) and spells the member `object :`, not a SAM lambda - two
 * methods would not fit one anyway - so the interface stays plain.
 */
interface OnAccountPasswordChanged {
    fun onPasswordChangeSucceeded()

    fun onPasswordChangeFailed()
}
