package uk.xa0.tulkki.xmpp.utils

/**
 * Tulkki: 3.7 pair 9, part 15 - the boundary's failure value, moved down out of `:data`.
 *
 * <p>It is thrown by `:data` (`AppSettings`, `DatabaseBackend`, `UnifiedPushDatabase`), caught by the
 * island (`XmppConnectionService.initializeDatabaseInBackground`) and read by `:ui`
 * (`XmppActivity.handleServiceConnected`, the one site that compares the `Reason`; the settings
 * fragment reacts to the failure as a plain `Exception` and never names this type), and it carries a
 * `Reason` the call sites compare by identity. Neither a ref nor a port can express that: the *type
 * itself* has to be nameable by both sides. Both `:data` -> `:xmpp` and `:ui` -> `:xmpp` are declared
 * directions, so the cheapest legal home is `:xmpp`, where the island can finally name it with an
 * import instead of spelling `:data` out.
 *
 * <p>`uk.xa0.tulkki.xmpp.utils` and not `uk.xa0.tulkki.xmpp.refs`: the refs package is interfaces only by design,
 * and this is a concrete 16-line class that carries the boundary's failure identity. `crypto` is the
 * OMEMO wire surface, which is not what this describes.
 *
 * Ported from Java by the `px` lane. Decisions taken rather than inherited:
 *
 * 1. **`reason` stays a Java-readable field**, so it is an `@JvmField val`: `:ui`'s
 *    `XmppActivity:413` compares `err.reason == EncryptionException.Reason.DB_WRONG_KEY` as a
 *    *field* read, and Kotlin would otherwise expose only `getReason()`.
 * 2. **The two-argument constructor forwards to the three-argument one**, exactly as Java's did, so
 *    the default is [Reason.GENERIC] and not a second copy of the body.
 * 3. **`Reason` is a nested `enum class`**, which is a static nested type in the class file: Java's
 *    `EncryptionException.Reason` spellings are unchanged.
 * 4. **`message` and `cause` are nullable**, which is the `RuntimeException(String, Throwable)`
 *    contract Kotlin sees as platform types; declaring them non-null would have thrown on the
 *    Kotlin side of a call `:data` can still make with a null.
 */
class EncryptionException : RuntimeException {
    enum class Reason {
        GENERIC,
        NEEDS_SESSION_PASSWORD,
        DB_WRONG_KEY,
        KEYSTORE_ERROR,
    }

    @JvmField val reason: Reason

    constructor(message: String?, cause: Throwable?) : this(message, cause, Reason.GENERIC)

    constructor(message: String?, cause: Throwable?, reason: Reason) : super(message, cause) {
        this.reason = reason
    }
}
