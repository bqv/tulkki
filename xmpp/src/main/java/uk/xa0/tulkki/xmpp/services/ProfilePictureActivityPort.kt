package uk.xa0.tulkki.xmpp.services

import android.content.Context

/** Tulkki: enabling or disabling the profile-picture activity component. */
interface ProfilePictureActivityPort {

    fun setEnabled(context: Context, enabled: Boolean)
}
