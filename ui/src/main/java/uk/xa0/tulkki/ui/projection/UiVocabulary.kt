package uk.xa0.tulkki.ui.projection

/**
 * The small closed vocabularies "Design: the Compose UI" §2.2 gives beside its two aggregate rows.
 *
 * <p>Each is determined by the design and by nothing else: §2.2 names every case, and none of these
 * carries a field a decision is needed for. The spellings are the module's own `UPPER_SNAKE`, as
 * `:translation`'s Kotlin enums already use (`EnglishRow.Kind`, `LanguageCheck.Outcome`); the
 * vocabulary is §2.2's.
 *
 * <p>Which case a row takes is **not** decided here: the `:data` snapshots carry the file's raw
 * scalars and their own comment says why ("nothing here reinterprets a stored `NUMBER` into a
 * `Boolean` or an enum, because the meaning of those columns is `Message`'s and
 * `MessageProjection`'s, and a snapshot that decided one would be a second place for the decision to
 * drift"), so mapping them is the projector's (`MessageProjection.of`, §2.1).
 */

/** Which way a message travelled. §2.2: `Incoming | Outgoing`. */
enum class Direction {
    INCOMING,
    OUTGOING,
}

/**
 * What a row is, before any body is considered. §2.2: `Text | System | Transfer | Call`, and §2.2's
 * own note - "(never a raw body for `System`)" - is the rule that a system row's text never becomes a
 * bubble body.
 */
enum class MessageType {
    TEXT,
    SYSTEM,
    TRANSFER,
    CALL,
}

/**
 * Whether the interpreter is on for one conversation. §2.2: `On | Off | LanguageUnknown`.
 *
 * <p>`LanguageUnknown` is a state of a screen, never a second screen (AGENTS.md: "Do not build the
 * off state as a second code path"), and it is the case a conversation with no detected language
 * takes rather than an invented default.
 */
enum class UiTranslationMode {
    ON,
    OFF,
    LANGUAGE_UNKNOWN,
}

/**
 * §2.2's bubble-run flags: "computed from the whole list, not from this row", which is why they are
 * their own type and not five fields sprinkled through the projection.
 */
data class UiRunFlags(
    val firstOfRun: Boolean,
    val lastOfRun: Boolean,
    val firstOfDay: Boolean,
    val showAvatar: Boolean,
    val showName: Boolean,
)

/**
 * §2.2's language pair, shown by every surface that names a language: the conversation's own
 * detected-or-overridden language, the app language, and which of the two is in force.
 *
 * <p>`conversationLanguage` is nullable because "no detected language says 'not known yet' in the
 * conversation's own slot" (AGENTS.md); the slot is not filled with the app language or `en`.
 * `appLanguage` is not nullable: there is always one app language.
 */
data class UiLanguagePair(
    val conversationLanguage: String?,
    val appLanguage: String,
    val overridden: Boolean,
)
