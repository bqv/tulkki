package uk.xa0.tulkki.xmpp.services

/**
 * Tulkki: one error resource id handed to the UI, un-nested out of `XmppConnectionService`
 *.
 *
 * Converted from the Java. The single parameter is a Java `int`, so it is a non-null Kotlin `Int`
 * and no reference nullability had to be decided. The three `:ui` implementers already spell
 * `onShowErrorToast(resId: Int)`.
 */
interface OnShowErrorToast {
    fun onShowErrorToast(resId: Int)
}
