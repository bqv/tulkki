package uk.xa0.tulkki.xmpp.services

/**
 * Tulkki: whether a message uuid is still queued for translation, asked from the parse layer.
 *
 * <p>3.7 pair 9, ruling 3 of round 161 (commit 167beb22f8). {@code MessageParser}
 * needs exactly one thing from the translation engine - {@code boolean hasQueued(String)} - and
 * the engine's own {@code TranslationStore} may not be named here: an island naming a non-island
 * module is a violation no {@code allow} can legalise ({@code docs/WORKSTREAMS.md} round 151),
 * and {@code :translation.allow} does not name {@code :xmpp}, a map decision that was declined
 * rather than taken. So the island declares the question and the composition root, the one place
 * that names both ends, implements it. Nesting it here costs no import site in either direction.
 *
 * <p>Tulkki: it has since moved to a file of its own in this package with the rest of the ports,
 * so "here" in the paragraph above is the service it was un-nested from.
 *
 * <p>It is deliberately <em>not</em> a {@code TranslationStoreRef} implemented by
 * {@code TranslationStore}: that shape would be a new {@code one-way|:translation|:xmpp} key on
 * the direction pair 10 drove to zero.
 *
 * <p>Both parameters are nullable because {@code MessageParser} passes
 * {@code replacedMessage.getUuid()} (`AbstractEntity.getUuid()` is nullable) and the store behind
 * the composition root already declares `String?` and answers null with "nothing queued" /
 * "nothing to forget".
 */
interface TranslationQueue {

    fun hasQueued(uuid: String?): Boolean

    /**
     * Drop whatever is queued and cached for one uuid.
     *
     * <p>The parse layer needs this as well as the question above: an edit replaces the text a
     * stored translation pair describes, so the pair goes with it. The brief's §3.1 measured the
     * surface as {@code hasQueued} alone, which stopped being true when commit {@code 21086d32f3}
     * ("an edit forgets the translation it replaced") added the two {@code forget} calls; the port
     * carries the real surface rather than a shape the code has outgrown.
     */
    fun forget(uuid: String?)
}
