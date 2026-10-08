package uk.xa0.tulkki.xmpp.pep

import android.os.Bundle
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.stanza.Iq

/**
 * The PEP publish-options bundles and the `precondition-not-met` test (XEP-0060 §7.1.5).
 *
 * Ported from Java by the port-14 `xmppport2` lane. Decisions taken rather than inherited:
 *
 * 1. **The five factories and the test are `@JvmStatic` on the companion**, because Java calls
 *    `PublishOptions.openAccess()` and `PublishOptions.preconditionNotMet(response)`. The class's
 *    private constructor stays private, so nothing but the statics is reachable.
 * 2. **`preconditionNotMet` keeps Java's ternary**: `response.getType() == Iq.Type.ERROR` selects
 *    `findChild("error")`, and a missing child is `null`. No behaviour moved.
 */
class PublishOptions private constructor() {

    companion object {

        @JvmStatic
        fun openAccess(): Bundle {
            val options = Bundle()
            options.putString("pubsub#access_model", "open")
            return options
        }

        @JvmStatic
        fun presenceAccess(): Bundle {
            val options = Bundle()
            options.putString("pubsub#access_model", "presence")
            return options
        }

        @JvmStatic
        fun persistentWhitelistAccess(): Bundle {
            val options = Bundle()
            options.putString("pubsub#persist_items", "true")
            options.putString("pubsub#access_model", "whitelist")
            return options
        }

        @JvmStatic
        fun persistentWhitelistAccessMaxItems(): Bundle {
            val options = Bundle()
            options.putString("pubsub#persist_items", "true")
            options.putString("pubsub#access_model", "whitelist")
            options.putString("pubsub#send_last_published_item", "never")
            options.putString("pubsub#max_items", "max")
            options.putString("pubsub#notify_delete", "true")
            options.putString(
                "pubsub#notify_retract", "true"
            ) // one could also set notify=true on the retract

            return options
        }

        @JvmStatic
        fun preconditionNotMet(response: Iq): Boolean {
            val error: Element? =
                if (response.getType() == Iq.Type.ERROR) response.findChild("error") else null
            return error != null && error.hasChild("precondition-not-met", Namespace.PUBSUB_ERROR)
        }
    }
}
