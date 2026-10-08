package uk.xa0.tulkki.ui.projection

/**
 * A row's encryption state, "Design: the Compose UI" §2.2's cases, each decided by
 * `Message.ENCRYPTION_*` as §2.2.1 "The missing definitions" #1 tabulates.
 *
 * <p>The scheme that failed, and nothing finer: §2.2.1 records that no finer reason survives the
 * tree - `ENCRYPTION_AXOLOTL_FAILED` covers a broken session and an outdated sender alike, and
 * `ENCRYPTION_DECRYPTION_FAILED` covers cancel, API error, IO and file failure.
 *
 * <p>Two cases cannot be produced from a snapshot alone, and neither is guessed at here:
 * **[Pending] has no discriminator yet** (§2.2.1 #1: "it is account/service state, and the snapshot
 * carries only the int"; `MessageBubble` cannot draw it until the decryption service's flag is
 * carried in), and `NotForThisDevice` comes from the parser's own constant rather than the row. The
 * cases exist because §2.2 names them; the mapping is the projector's and it is deferred where the
 * design says so.
 */
sealed interface UiEncryption {

    /** Nothing was encrypted: `ENCRYPTION_NONE`. */
    data object None : UiEncryption

    /** PGP while the decryption service still holds it: the `message_decrypting` cell. */
    data object Pending : UiEncryption

    /** `ENCRYPTION_PGP`: the armour is the stored body and is drawn as a PGP note. */
    data object Encrypted : UiEncryption

    /** Readable: `ENCRYPTION_DECRYPTED`, and `ENCRYPTION_OTR` / `ENCRYPTION_AXOLOTL`, which arrive in the clear. */
    data object Decrypted : UiEncryption

    /** The ciphertext could not be read, and [kind] says which scheme's was lost. */
    data class Failed(val kind: EncryptionFailure) : UiEncryption

    /** `ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE`: OMEMO for another device. */
    data object NotForThisDevice : UiEncryption
}

/**
 * The scheme whose message could not be read - §2.2.1 #1's `enum EncryptionFailure { PGP, OMEMO }`.
 *
 * <p>Two members and no more, because the tree keeps no finer failure than that: the two constants
 * that reach this state are `ENCRYPTION_DECRYPTION_FAILED` (PGP) and `ENCRYPTION_AXOLOTL_FAILED`
 * (OMEMO).
 */
enum class EncryptionFailure {
    PGP,
    OMEMO,
}
