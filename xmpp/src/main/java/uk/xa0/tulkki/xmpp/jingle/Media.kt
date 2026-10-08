package uk.xa0.tulkki.xmpp.jingle

import com.google.common.collect.ImmutableSet
import java.util.Locale

/**
 * The three Jingle media kinds.
 *
 * Ported from Java by lane `C`. Decisions taken rather than inherited:
 *
 * 1. **The statics are `@JvmStatic` on the companion**: `Media.of`, `audioOnly` and `videoOnly` are
 *    called from both Java (`ConversationFragment`) and Kotlin (`Contact`, the call services).
 * 2. **`of` takes `String?`** because Java's answered `UNKNOWN` for a null value
 *    (`Contact.kt:998` passes a cursor column straight through).
 * 3. **`toString` reads `name`**, which is Java's `super.toString()` on an enum.
 * 4. `audioOnly`/`videoOnly` keep Java's `Set` parameter as Kotlin's read-only `Set`, which every
 *    caller satisfies and which keeps the descriptor `java.util.Set`.
 */
enum class Media {
    VIDEO,
    AUDIO,
    UNKNOWN;

    override fun toString(): String = name.lowercase(Locale.ROOT)

    companion object {
        @JvmStatic
        fun of(value: String?): Media =
            try {
                if (value == null) UNKNOWN else Media.valueOf(value.uppercase(Locale.ROOT))
            } catch (e: IllegalArgumentException) {
                UNKNOWN
            }

        @JvmStatic
        fun audioOnly(media: Set<Media>): Boolean = ImmutableSet.of(AUDIO) == media

        @JvmStatic
        fun videoOnly(media: Set<Media>): Boolean = ImmutableSet.of(VIDEO) == media
    }
}
