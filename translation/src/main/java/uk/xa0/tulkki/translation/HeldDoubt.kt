package uk.xa0.tulkki.translation

/**
 * The doubt a held send is waiting on, and what the owner's tap must do with it - item 16's
 * "the two doubt kinds want opposite taps", as one place rather than a sentence somebody may tidy
 * away.
 *
 * <p>**An echo or an empty answer is nothing translated.** The stored text is the owner's own words,
 * so sending it as "the held answer" would put the original on the wire - the bug the whole check
 * exists to stop - and the tap therefore discards it and buys a fresh answer
 * ([LanguageCheck.Tap.RE_TRANSLATE]). **An answer merely in a doubtful language is a real
 * translation**: no reader could vouch for the language, which is doubt rather than evidence, and the
 * owner's tap is what accepts that risk, so the tap sends the stored answer and buys nothing
 * ([LanguageCheck.Tap.SEND_AS_HELD]).
 *
 * <p>This class is the *stored* half of that rule: the kind is persisted beside the answer, because
 * the tap happens in another process lifetime - the message survives being killed, and so must the
 * knowledge of which tap applies. [KEY_PREFIX] names the store entry (the settings store,
 * whose file outlives the process); [name] and [parse] are its format, pinned by a cell
 * so the two processes agree; [sendsStoredAnswer] is the question the tap's entry asks.
 *
 * <p>It deliberately does **not** touch the received side. A received message keeps drawing a
 * probable translation as the translation it probably is: doubt was never evidence for drawing, so
 * nothing here is consulted while text is drawn (docs/MIGRATION.md item 16 and `AGENTS.md`).
 *
 * <p>Pure Kotlin, no Android types, so it is exercised by JVM unit tests.
 *
 * <p><strong>Package-private members in the Java this replaces become public `@JvmStatic` members of
 * the `object`</strong> (`KEY_PREFIX`/`NOTES_KEY_PREFIX` are `const val`s too), because Kotlin has no
 * package-private and the Java callers and tests reach them across the package. The widening is the
 * one deliberate API change in this file and it changes no behaviour.
 */
object HeldDoubt {

    /**
     * The settings key a held doubt is stored under, one entry per held message.
     *
     * <p>Prefix rather than a second store: the entry is written and dropped with the hold, the
     * message's own uuid is the whole address, and a stale entry for a message that no longer exists
     * is never read.
     */
    const val KEY_PREFIX = "hold-doubt:"

    /**
     * The settings key the notes that came with a held answer are stored under - the same record's
     * other half, one entry per held message.
     *
     * <p>The marks rode the paid call the answer came from, so a message later sent as held would
     * arrive with the owner having paid for notes that never left the device. They are kept here
     * with the answer and the kind, and re-emitted through `ReviewStore` when the held answer
     * is finally sent - the standing rule that a translation which already succeeded is never bought
     * twice.
     */
    const val NOTES_KEY_PREFIX = "hold-notes:"

    /** The store key for one held message. */
    @JvmStatic
    fun key(messageUuid: String?): String = KEY_PREFIX + messageUuid

    /** The store key for one held message's notes. */
    @JvmStatic
    fun notesKey(messageUuid: String?): String = NOTES_KEY_PREFIX + messageUuid

    /** The stored form of a kind - the enum's own name, so the format cannot drift from the type. */
    @JvmStatic
    fun name(doubt: LanguageCheck.Doubt?): String = doubt?.name ?: ""

    /** The kind a stored value names, or `null` when there is none: absent, blank or unknown. */
    @JvmStatic
    fun parse(stored: String?): LanguageCheck.Doubt? {
        if (stored == null) {
            return null
        }
        // Java's own `trim()` (`<= ' '`), as in the Java this replaces.
        val wanted = stored.javaTrim()
        for (doubt in LanguageCheck.Doubt.values()) {
            if (doubt.name == wanted) {
                return doubt
            }
        }
        return null
    }

    /**
     * What the tap does with a held message of this kind - and [LanguageCheck.Tap.RE_TRANSLATE]
     * for `null`, because a message held for any other reason has no stored answer to send: the
     * conservative answer is always "buy a fresh one", never "put the stored text on the wire".
     */
    @JvmStatic
    fun tapFor(doubt: LanguageCheck.Doubt?): LanguageCheck.Tap =
            doubt?.tap ?: LanguageCheck.Tap.RE_TRANSLATE

    /**
     * Whether the tap sends the stored answer rather than buying a new one - the one question the
     * tap's entry asks, so no call site has to compare enum members itself.
     */
    @JvmStatic
    fun sendsStoredAnswer(doubt: LanguageCheck.Doubt?): Boolean =
            tapFor(doubt) == LanguageCheck.Tap.SEND_AS_HELD
}

/**
 * `String.trim()` with Java's own definition: everything at or below U+0020.
 */
private fun String.javaTrim(): String = trim { it <= ' ' }
