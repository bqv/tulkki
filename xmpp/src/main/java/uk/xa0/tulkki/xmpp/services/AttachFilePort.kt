package uk.xa0.tulkki.xmpp.services

import android.content.Context
import android.net.Uri
import uk.xa0.tulkki.xmpp.refs.MessageRef
import uk.xa0.tulkki.xmpp.services.UiCallbackPort

/**
 * Tulkki: one file attachment's worker, and the video-quality decision that precedes it.
 *
 * Un-nested out of `XmppConnectionService` by the `unblock` lane: a type written
 * `XmppConnectionService.AttachFilePort` cannot leave its outer class while files outside spell it
 * that way, and the one outside speller is `:app`'s `XmppTulkkiHost`, whose adapter implements this
 * port. The declarations are byte for byte the nested ones, now in Kotlin.
 */
interface AttachFilePort {

    /** The island runs it; [AttachFileTask.isVideoMessage] picks the executor. */
    fun create(
        message: MessageRef,
        uri: Uri,
        type: String,
        callback: UiCallbackPort<MessageRef>,
    ): AttachFileTask

    fun videoCompression(context: Context): String?
}
