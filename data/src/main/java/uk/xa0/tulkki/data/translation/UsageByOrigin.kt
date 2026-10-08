package uk.xa0.tulkki.data.translation

/**
 * The day's spend split by where it went (schema 78): the read model behind tapping a day on the
 * ledger.
 *
 * <p>**Every bucket that holds tokens appears**, and that is the point rather than a nicety: the
 * breakdown is drawn beside the day's own figure, so a bucket that was silently dropped would make
 * the parts stop adding up to the whole - and a breakdown that does not add up is worse than none.
 * [sumOfOrigins] and `UsageByOrigin.addsUp` exist so a test can say that in one line instead of six.
 *
 * <p>[total] is read from the day's own row (`translation_usage`), not summed from [origins]: the two
 * are separate statements over separate tables, and comparing them is what makes a write that reached
 * one table and not the other a red test rather than a drifting screen.
 */
data class UsageByOrigin(
    /** The local calendar day, as `DailyTokenCounter.dayOf` spells it. */
    val day: String,
    /** The buckets, `origin`-ordered so a screen and a test see the same order. */
    val origins: List<UsageOrigin>,
    /** The day's own figure, from its row in the day table. */
    val total: UsageCounts,
) {

    /** The buckets added up: what must equal [total]. */
    fun sumOfOrigins(): UsageCounts =
        origins.fold(UsageCounts.zero()) { running, origin -> running + origin.usage }

    /** Whether the parts still make the whole. */
    fun addsUp(): Boolean = sumOfOrigins() == total
}

/**
 * One bucket of a day: who the call belonged to, and what it cost.
 *
 * <p>[origin] is the key the write path recorded - **a conversation's uuid, and nothing else**. No
 * address and no display name is stored, because this table is global and not scoped to an account,
 * and a snapshot of a contact address in it would build a per-address spend profile that also
 * conflated one JID across two accounts. The name is resolved at read time, into [address], by
 * joining `conversations`: an address appears on the owner's own screen and nowhere on disk.
 */
data class UsageOrigin(
    /** The recorded key; `''` is "this call was not tied to a room". */
    val origin: String,
    /** What kind of place it was, which is what the screen groups by. */
    val kind: UsageOriginKind,
    /**
     * The conversation's own address as the file holds it, resolved at read time, or null when there
     * is nothing to resolve - the `''` bucket, the several-rooms bucket, or a conversation that is no
     * longer in the file.
     */
    val address: String?,
    /** The bucket's tokens. */
    val usage: UsageCounts,
) {

    companion object {
        /**
         * The reserved origin for **one request that covered several rooms**: a catch-up batch is up
         * to eight messages sharing a target language, and it is billed as one figure, so its tokens
         * are not attributable to any single conversation without inventing a split.
         *
         * <p>It is a marker in the origin column rather than a second column, and the leading `*` is
         * what makes that safe: a conversation uuid is hex and dashes, so no uuid can spell this, and
         * it is not the empty string either. The read checks for it **before** it decides whether the
         * join resolved anything, or the marker would be classified as a deleted conversation.
         *
         * <p>It exists because folding several rooms' traffic into the not-tied bucket would read as a
         * false statement: the owner would see small per-room figures beside one large "not tied to a
         * room" number that is in fact mostly room traffic.
         */
        const val MULTI_ROOM_ORIGIN = "*multi-room"
    }
}

/**
 * What a bucket is, in the five cases the owner's screen has to tell apart.
 *
 * <p>The first four are the owner's own grouping - group chat, one-to-one contact, one request across
 * several rooms, and the calls that belong to no conversation at all - and the fifth is the case an
 * honest breakdown cannot hide: a conversation the owner has since deleted. Its origin no longer
 * resolves, and it is still shown, as itself, because its tokens were still spent.
 */
enum class UsageOriginKind {
    /** A call tied to no conversation: a gloss, a note, the silent language sample, a suggestion. */
    NOT_TIED_TO_A_ROOM,

    /** A group chat (a MUC). */
    GROUP_CHAT,

    /** A one-to-one conversation. */
    ONE_TO_ONE,

    /** One request covering several rooms: a batch, whose one figure is split across none of them. */
    MULTI_ROOM,

    /** An origin whose conversation is no longer in the file: a room the owner has deleted. */
    DELETED_CONVERSATION,
}
