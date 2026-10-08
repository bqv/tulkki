package uk.xa0.tulkki.xmpp.services

import java.io.File
import java.util.function.Consumer

/**
 * Tulkki: the recursive file observer `onCreate` starts and `onDestroy` stops.
 *
 * Un-nested out of `XmppConnectionService` by the `unblock` lane, the same move as
 * [AttachFilePort]: `:app`'s `XmppTulkkiHost` spells the port and its nested [Watcher], and both
 * spellings were retyped in the same commit. `Watcher` stays a nested type of this interface,
 * exactly as it was nested in the service, so `FileObserverPort.Watcher` still resolves.
 */
interface FileObserverPort {

    interface Watcher {
        fun startWatching()

        fun stopWatching()

        fun restartWatching()
    }

    fun create(path: String, onDeleted: Consumer<File>): Watcher
}
