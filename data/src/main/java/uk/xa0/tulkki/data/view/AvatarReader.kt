package uk.xa0.tulkki.data.view

import android.content.Context
import android.graphics.drawable.Drawable
import uk.xa0.tulkki.libs.Avatarable

/**
 * The one avatar *action* `:data` performs, declared here and implemented by `:app`.
 *
 * 3.7 pair 2. Seven of the eight `:data` sites that named
 * `uk.xa0.tulkki.app.services.AvatarService` were an `implements` clause on its nested `Avatarable`, and
 * an interface cannot be expressed by a port - so that marker moved down to
 * `uk.xa0.tulkki.data.model.Avatarable` and the clauses followed it. **2026-10-08: that type moved
 * again, to `uk.xa0.tulkki.libs.Avatarable`**, where the island may name it too, so the
 * `uk.xa0.tulkki.xmpp.refs.AvatarableRef` that stood between them was deleted; the import below is
 * the only line this port changed. What was left is one real call,
 * `Contact.registerAsPhoneAccount`'s icon:
 *
 * ```
 * FileBackend.drawDrawable(ctx.getAvatarService().get(this, AvatarService.getSystemUiAvatarSize(ctx) / 2, false))
 * ```
 *
 * which draws a contact's avatar at half the system-UI size. That is the service's cache and the service's
 * drawable, so it stays there and `:data` asks for it through this port.
 *
 * **The holder fails loudly.** [get] throws an [IllegalStateException] naming the port and the install
 * point rather than returning a placeholder drawable: a phone account registered with a wrong icon is
 * exactly the kind of silently-bad outcome round 155 ruled out. The implementation is
 * `uk.xa0.tulkki.app.services.AvatarService`, which installs itself from its own constructor - the service
 * is the only thing that can build one, and `XmppConnectionService` builds exactly one per process, so a
 * single slot is the whole lookup. It is deliberately not an install line in `TulkkiApplication.onCreate`:
 * the avatar cache cannot exist before the service that owns it does.
 */
object AvatarReader {

    /** What the service can do: draw an avatar, and know how big the system UI wants one. */
    interface Port {

        /** The avatar of `avatarable` at `size`, or from the cache when asked. */
        fun get(avatarable: Avatarable, size: Int, cachedOnly: Boolean): Drawable?

        /** The pixel size the system UI's own avatar is drawn at, for this display. */
        fun systemUiAvatarSize(context: Context): Int
    }

    @Volatile
    private var installed: Port? = null

    /** Called by the implementation's constructor; there is one implementation and one instance. */
    @JvmStatic
    fun install(port: Port) {
        installed = port
    }

    /** @throws IllegalStateException when nothing was installed - a build fault, not a device state */
    @JvmStatic
    fun get(avatarable: Avatarable, size: Int, cachedOnly: Boolean): Drawable? =
        require().get(avatarable, size, cachedOnly)

    /** @throws IllegalStateException when nothing was installed - a build fault, not a device state */
    @JvmStatic
    fun systemUiAvatarSize(context: Context): Int = require().systemUiAvatarSize(context)

    private fun require(): Port {
        val port = installed
        if (port == null) {
            throw IllegalStateException(
                "uk.xa0.tulkki.data.view.AvatarReader has no implementation: uk.xa0.tulkki.app.services." +
                    "AvatarService installs itself when XmppConnectionService builds it," +
                    " and it has not been built",
            )
        }
        return port
    }
}
