package uk.xa0.tulkki.xmpp.services

import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import uk.xa0.tulkki.xmpp.refs.ContactRef
import uk.xa0.tulkki.xmpp.refs.MucOptionsRef

/** Tulkki: the launcher shortcuts, in island vocabulary. */
interface ShortcutPort {

    fun refresh()

    fun refresh(forceUpdate: Boolean)

    fun report(contact: ContactRef)

    fun getShortcutInfo(contact: ContactRef): ShortcutInfoCompat

    fun getShortcutInfo(contact: ContactRef, conversation: String?): ShortcutInfoCompat

    fun getShortcutInfo(mucOptions: MucOptionsRef): ShortcutInfoCompat

    fun createShortcut(contact: ContactRef, legacy: Boolean): Intent
}
