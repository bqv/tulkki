package uk.xa0.tulkki.ui.util

import android.app.Activity
import android.content.Context
import android.util.Pair
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import uk.xa0.tulkki.data.model.Contact
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.utils.Counterparts
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.jingle.RtpCapability
import uk.xa0.tulkki.xmpp.utils.CryptoHelper

object PresenceSelector {

    @JvmStatic
    fun showPresenceSelectionDialog(activity: Activity, conversation: Conversation, listener: OnPresenceSelected) {
        val contact = conversation.getContact()
        val resourceArray = contact.getPresences().toResourceArray()
        showPresenceSelectionDialog(activity, contact, resourceArray) { fullJid ->
            conversation.setNextCounterpart(fullJid)
            listener.onPresenceSelected()
        }
    }

    @JvmStatic
    fun selectFullJidForDirectRtpConnection(
        activity: Activity,
        contact: Contact,
        required: RtpCapability.Capability,
        onFullJidSelected: OnFullJidSelected,
    ) {
        val resources = RtpCapability.filterPresences(contact, required)
        if (resources.size < 1) {
            Toast.makeText(activity, R.string.rtp_state_contact_offline, Toast.LENGTH_LONG).show()
        } else if (resources.size == 1) {
            onFullJidSelected.onFullJidSelected(contact.getJid().withResource(resources[0]))
        } else {
            showPresenceSelectionDialog(activity, contact, resources, onFullJidSelected)
        }
    }

    private fun showPresenceSelectionDialog(
        activity: Activity,
        contact: Contact,
        resourceArray: Array<String>,
        onFullJidSelected: OnFullJidSelected,
    ) {
        val presences = contact.getPresences()
        val builder = MaterialAlertDialogBuilder(activity)
        builder.setTitle(activity.getString(R.string.choose_presence))
        val typeAndName: Pair<Map<String, String>, Map<String, String>> = presences.toTypeAndNameMap()
        val resourceTypeMap = typeAndName.first
        val resourceNameMap = typeAndName.second
        val readableIdentities = Array(resourceArray.size) { "" }
        val selectedResource = AtomicInteger(0)
        for (i in resourceArray.indices) {
            val resource = resourceArray[i]
            if (resource == contact.getLastResource()) {
                selectedResource.set(i)
            }
            val type = resourceTypeMap[resource]
            val name = resourceNameMap[resource]
            if (type != null) {
                if (Collections.frequency(resourceTypeMap.values, type) == 1) {
                    readableIdentities[i] = translateType(activity, type)
                } else if (name != null) {
                    if (Collections.frequency(resourceNameMap.values, name) == 1 ||
                        CryptoHelper.UUID_PATTERN.matcher(resource).matches()
                    ) {
                        readableIdentities[i] = translateType(activity, type) + "  (" + name + ")"
                    } else {
                        readableIdentities[i] = translateType(activity, type) + " (" + name + " / " + resource + ")"
                    }
                } else {
                    readableIdentities[i] = translateType(activity, type) + " (" + resource + ")"
                }
            } else {
                readableIdentities[i] = resource
            }
        }
        builder.setSingleChoiceItems(readableIdentities, selectedResource.get()) { dialog, which ->
            selectedResource.set(which)
        }
        builder.setNegativeButton(uk.xa0.tulkki.data.R.string.cancel, null)
        builder.setPositiveButton(R.string.ok) { dialog, which ->
            onFullJidSelected.onFullJidSelected(
                getNextCounterpart(contact, resourceArray[selectedResource.get()]),
            )
        }
        builder.create().show()
    }

    /** Kept as a `:ui` entry point; the rule lives in `uk.xa0.tulkki.data.utils.Counterparts`. */
    @JvmStatic
    fun getNextCounterpart(contact: Contact, resource: String): Jid {
        return Counterparts.getNextCounterpart(contact, resource)
    }

    /** Kept as a `:ui` entry point; the rule lives in `uk.xa0.tulkki.data.utils.Counterparts`. */
    @JvmStatic
    fun getNextCounterpart(jid: Jid, resource: String): Jid {
        return Counterparts.getNextCounterpart(jid, resource)
    }

    @JvmStatic
    fun warnMutualPresenceSubscription(activity: Activity, conversation: Conversation, listener: OnPresenceSelected?) {
        val builder = MaterialAlertDialogBuilder(activity)
        builder.setTitle(conversation.getContact().getJid().toString())
        builder.setMessage(R.string.without_mutual_presence_updates)
        builder.setNegativeButton(uk.xa0.tulkki.data.R.string.cancel, null)
        builder.setPositiveButton(R.string.ignore) { dialog, which ->
            conversation.setNextCounterpart(null)
            if (listener != null) {
                listener.onPresenceSelected()
            }
        }
        builder.create().show()
    }

    private fun translateType(context: Context, type: String): String {
        return when (type.lowercase(Locale.getDefault())) {
            "pc" -> context.getString(R.string.type_pc)
            "phone" -> context.getString(R.string.type_phone)
            "tablet" -> context.getString(R.string.type_tablet)
            "web" -> context.getString(R.string.type_web)
            "console" -> context.getString(R.string.type_console)
            else -> type
        }
    }

    fun interface OnPresenceSelected {
        fun onPresenceSelected()
    }

    fun interface OnFullJidSelected {
        fun onFullJidSelected(jid: Jid)
    }
}
