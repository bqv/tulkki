package uk.xa0.tulkki.xmpp.services

/**
 * Tulkki: the MUC participant-list refresh, un-nested out of `XmppConnectionService`
 *.
 *
 * Converted from the Java. The single member takes nothing, so no nullability reading was needed.
 * `UiUpdateDispatch` calls it and every implementer is Kotlin or Java, so the plain interface is
 * the whole of the change.
 */
interface OnMucRosterUpdate {
    fun onMucRosterUpdate()
}
