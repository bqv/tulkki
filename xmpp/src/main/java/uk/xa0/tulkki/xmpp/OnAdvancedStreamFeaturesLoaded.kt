package uk.xa0.tulkki.xmpp

import uk.xa0.tulkki.xmpp.refs.AccountRef

/**
 * Tulkki: the two stream-feature hooks, split so an island listener never names a data type.
 *
 * Ported from `OnAdvancedStreamFeaturesLoaded.java`.
 * Both members are `default` in Java and stay default here: Kotlin 2.2+ emits real JVM default
 * methods, so the Java subinterface `uk.xa0.tulkki.xmpp.services.AvatarPort` and the Kotlin
 * implementations (`OmemoSessionPort`, `MessageArchiveService`, `AvatarService`, `AxolotlService`)
 * keep resolving the other hook through the interface, exactly as before.
 *
 * The account-carrying form is kept for the listeners that live above the islands and may name an
 * application type ({@code AvatarService}, {@code MessageArchiveService}).
 *
 * The island-owned form is the one a listener declared inside an island implements, because
 * `tools/check-modules` rejects `island-imports-ours` before it consults `allow`: `AxolotlService`
 * therefore implements this hook and the account parameter is dropped on the way in.
 */
interface OnAdvancedStreamFeaturesLoaded {

    /**
     * The account-carrying form, kept for the listeners that live above the islands and may name an
     * application type.
     */
    fun onAdvancedStreamFeaturesAvailable(account: AccountRef) {
        onStreamFeaturesAvailable()
    }

    /** The island-owned form, the hook an island listener implements. */
    fun onStreamFeaturesAvailable() {
    }
}
