package uk.xa0.tulkki.xmpp.pep

import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace

/**
 * A PEP user-tune (XEP-0118) and the sentinel that says the user stopped listening.
 *
 * Ported from Java by the port-14 `xmppport2` lane. Decisions taken rather than inherited:
 *
 * 1. **`STOP` stays a Java-visible static field** (`@JvmField val` on the companion), because
 *    `ConversationFragment.java:6714` compares identity with `tune != UserTune.STOP`. A Kotlin
 *    property without `@JvmField` would have become a `getSTOP()` accessor.
 * 2. **`title`/`artist` stay public fields** (`@JvmField var`), as Java declared them.
 * 3. **`parse` is `@JvmStatic`** and takes a nullable element, Java's first statement being a null
 *    test; the empty-`<tune/>` check is Kotlin's `isEmpty()`.
 * 4. **`equals` keeps Java's `Objects.equals` comparisons**, spelled `title != other.title` /
 *    `artist != other.artist`, which are the same null-safe identity-then-equality test.
 */
class UserTune {

    @JvmField
    var title: String? = null

    @JvmField
    var artist: String? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }

        if (other !is UserTune) {
            return false
        }

        if (title != other.title) {
            return false
        }

        if (artist != other.artist) {
            return false
        }

        return true
    }

    companion object {

        // A sentinel object to indicate that the user has stopped listening to a tune.
        @JvmField
        val STOP: UserTune = UserTune()

        @JvmStatic
        fun parse(items: Element?): UserTune? {
            val item = items?.findChild("item") ?: return null

            val tuneElement = item.findChild("tune", Namespace.USER_TUNE) ?: return null

            val tune = UserTune()

            tune.title = tuneElement.findChildContent("title")
            tune.artist = tuneElement.findChildContent("artist")

            // According to XEP-0118, an empty <tune/> element indicates the user is no longer listening.
            if (tuneElement.getChildren().isEmpty()) {
                return STOP
            }

            if (tune.title.isNullOrEmpty()) {
                return null
            }

            if (tune.artist.isNullOrEmpty()) {
                return null
            }

            return tune
        }
    }
}
