package uk.xa0.tulkki.xmpp.services

import android.content.Context

/** Tulkki: the two device questions the island asks of {@code uk.xa0.tulkki.app.utils.PhoneHelper}. */
interface PhoneHelperPort {

    fun getAndroidId(context: Context): String?

    fun isEmulator(): Boolean
}
