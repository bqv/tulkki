package uk.xa0.tulkki.translation

import uk.xa0.tulkki.data.model.Message

/**
 * The cache identity of one review, and the rule that decides which bubbles may have one.
 *
 * <p>A review is a function of the owner's own words and the language the notes are written in - not
 * of the message they happened to be written in. Two identical drafts get the same notes, which is
 * the honest answer, and the key says so: it is [CacheKey] in its own namespace, so a review
 * can share the cache table with a translation and with a gloss without ever being mistaken for one.
 * The namespace carries the review contract's version, the way [GlossKey] does, because a
 * cached answer is only as good as the prompt that asked for it.
 *
 * <p>There is no outbound translation cache and this is not one: a message is still translated and
 * paid for every time it is sent. The cache row here exists because the notes cannot be bought
 * again - they only ever come back riding on a translation call - so the one chance to keep them is
 * the moment they arrive.
 *
 * <p><strong>The input-side rule lives here, as a rule rather than as a caller's check.</strong>
 * [forBubble] returns a key only for the owner's <em>own</em> message that was actually
 * translated, and `null` for everything else - and a received message is refused before the
 * text is even looked at, so no future caller can make a contact's message show notes. That rule is
 * the one thing this feature must never bend: notes are about what the owner typed.
 *
 * <p><strong>With the interpreter off this class answers `null` for every message.</strong>
 * The guard is the first statement of [forBubble], above the owner's-own test: a plain XMPP
 * client has no notes at all, and it is the rule's own home rather than a caller's check, so no
 * future entry point can read a review back by forgetting to ask. It is also the closest gate to the
 * store that is pure - `ReviewStore` reads a `Context` and a database, so the off state
 * can be handed to <em>this</em> method in a JVM test and to nothing else on the read path.
 * [GlossLookups.mayBegin] exists for the same reason.
 *
 * <p>Pure Kotlin apart from the `Message` constants, so it is exercised by JVM unit tests.
 *
 * <p><strong>Package-private in the Java this replaces; public here, with `@JvmStatic`, because
 * Kotlin has no package-private and the Java `ReviewStore` and the Java tests call `of`, `forBubble`
 * and `NAMESPACE` across the package.</strong> The widening is the one deliberate API change in this
 * file and it changes no behaviour.
 */
object ReviewKey {

    /**
     * The namespace that keeps reviews apart from translations and glosses in the shared cache
     * table.
     *
     * <p>It carries the review contract's version, because a cached answer is only as good as the
     * prompt that asked for it. It went from "review-v1" to "review-v2" when each note started
     * carrying the stretch of the owner's text it is about: an entry bought under the old prompt is a
     * bare remark with no positions in it, and a bubble that read one back would have nothing to
     * underline while the new prompt looked like it had not worked. It moved again, to `review-v3`,
     * when the owner narrowed the notes to wording that is wrong or looks wrong: what comes back is
     * no longer a description of the Finnish but a correction of it, and the old rows say something
     * this feature no longer says. The seam is the one that already exists for this - [CacheKey]
     * hashes the namespace as its own field - so nothing else in the table moves and no translation
     * is invalidated. The cost is stated rather than hidden: the sends already reviewed are reviewed
     * once more, and their old rows stay in the table unread.
     */
    const val NAMESPACE = "review-v3"

    /**
     * @param ownerText the owner's own words - what the translation call was given; may be
     *     `null`
     * @param reviewLanguage the language the notes are in; may be `null`
     * @return 64 lowercase hex characters
     */
    @JvmStatic
    fun of(ownerText: String?, reviewLanguage: String?): String =
            CacheKey.of(
                    NAMESPACE + PromptBook.suffix(PromptBook.Kind.REVIEW),
                    ownerText ?: "",
                    reviewLanguage)

    /**
     * The key a bubble's review was stored under, or `null` when this message can have none.
     *
     * <p>Three ways to have none, and each is a real state rather than a guard:
     *
     * <ul>
     *   <li>the interpreter is off - a plain client has no notes, and this is asked before anything
     *       else, so the off state cannot be reached past by any caller and no cache is read for it;
     *   <li>it is not the owner's own message - a received message has no notes, and its
     *       `translated_body` is the <em>translation</em>, so a key taken from it could even
     *       collide with a review of the owner's words. Refused first on the status alone, after the
     *       mode;
     *   <li>no translation was ever bought for it - a message already in the app language, or one
     *       with no language at all (a link, a number, a bare name, a ping), is never sent to
     *       DeepSeek, so no notes came back and none will: the notes ride on the call, and there was
     *       no call;
     *   <li>the translation did not finish - still owed, or failed - which is the covered bubble,
     *       and there is nothing to have notes about yet.
     * </ul>
     *
     * @param translatedBody the app-language side of the row: the owner's own words on a sent
     *     message, the translation produced on a received one; may be `null`
     * @param translationState one of the `Message.TRANSLATION_*` constants
     * @param messageStatus the row's status, which is what says whose message this is
     * @param reviewLanguage the language the notes are in; may be `null`
     * @param interpreter the interpreter's mode, which is the last argument like every other rule's
     *     (`null` is off: a caller with nothing to say, not a licence)
     */
    @JvmStatic
    fun forBubble(
            translatedBody: String?,
            translationState: Int,
            messageStatus: Int,
            reviewLanguage: String?,
            interpreter: Interpreter?
    ): String? {
        if (interpreter == null || !interpreter.enabled()) {
            return null
        }
        if (messageStatus <= Message.STATUS_RECEIVED) {
            return null
        }
        if (translatedBody == null || translatedBody.javaTrim().isEmpty()) {
            return null
        }
        if (translationState != Message.TRANSLATION_DONE) {
            return null
        }
        return of(translatedBody, reviewLanguage)
    }
}

/**
 * `String.trim()` with Java's own definition: everything at or below U+0020.
 */
private fun String.javaTrim(): String = trim { it <= ' ' }
