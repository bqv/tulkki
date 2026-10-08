package uk.xa0.tulkki.translation

import java.time.ZoneId

/**
 * One DeepSeek call's tokens, charged in one place.
 *
 * <p>Every request the app makes is one call and every call is charged twice from the same response:
 * the response's own `total_tokens` to the daily cap ([DailyTokenCounter]), whose behaviour is the
 * guard rail the owner asked for, and its split to the per-day ledger ([UsageLedger]) under the
 * tariff of the call's own instant. The two are one fact about one response and must be filed
 * together - a call counted against the cap but not in the ledger (or the other way round) is a
 * spend figure that cannot be reconciled.
 *
 * <p>This class exists because that pair was written five times: three private `countUsage` helpers,
 * byte for byte identical, in [OutgoingTranslation], [GlossLookup] and [EnglishLookup], and two
 * inline pairs in [TranslationService.pump]. Only the instant differed, and the difference is deliberate:
 * the pump files a call under the pass's own `now`, taken before the request was built, so a slow
 * call near a tariff window edge is priced by when it started rather than by when it finished; the
 * three helpers filed under the answer's arrival. That difference is kept here by taking the instant
 * as a parameter rather than reading the clock - this class never calls `System.currentTimeMillis()`.
 *
 * <p>The zone is a parameter for the same reason: the day a call lands on is the local calendar day
 * of its instant ([DailyTokenCounter.dayOf]), and [TranslationService] threads the zone it read the
 * day with, so a pass reads and writes the same day. Everything else in the app has no seam here and
 * passes [ZoneId.systemDefault].
 *
 * <p>What is deliberately <em>not</em> here: "messages translated today"
 * (`TranslationActivityPort.addTranslated`). That figure counts messages, and a glance, an English row
 * and a pre-send suggestion are not messages - they are extras beside a message's own translation -
 * so the call stays at the one place that really did translate a message.
 *
 * <p>Pure Kotlin, no Android types, so it is exercised by JVM unit tests.
 *
 * <p><strong>Java callers remain</strong>, so the entry points are `@JvmStatic`: the five call
 * sites above are Java until this package's port finishes, and Java reads a static method here
 * exactly as it did.
 */
object Spend {

    /**
     * The same, for a call that belongs to no conversation: a gloss, a note, the silent language
     * sample, a pre-send suggestion, or the English row - all of which are keyed by their text rather
     * than by a message, so two rooms can share one purchase. See the five-argument overload.
     */
    @JvmStatic
    fun record(
            settings: TranslationSettings,
            usage: DeepSeekClient.Usage?,
            atMillis: Long,
            zone: ZoneId
    ) {
        record(settings, usage, atMillis, zone, null)
    }

    /**
     * Files one call's tokens: the response's own total to the daily cap, and its split to the
     * per-day ledger under the tariff of `atMillis`.
     *
     * @param settings the settings that own the counter and the ledger
     * @param usage the response's usage, or `null` for a caller that has none
     * @param atMillis the call's own instant - when the request was built, not when the answer arrived
     * @param zone the zone whose calendar day the cap is denominated in, which is the same zone the
     *   caller read the day with
     * @param origin the conversation the call belonged to - its uuid - or `null` for a call that
     *   belongs to no conversation. It is the ledger's own grouping key, resolved to a name when the
     *   ledger is read; nothing about the conversation is stored with it.
     */
    @JvmStatic
    fun record(
            settings: TranslationSettings,
            usage: DeepSeekClient.Usage?,
            atMillis: Long,
            zone: ZoneId,
            origin: String?
    ) {
        if (usage == null) {
            return
        }
        if (usage.totalTokens > 0) {
            settings.tokenCounter().add(DailyTokenCounter.dayOf(atMillis, zone), usage.totalTokens)
        }
        settings.recordUsage(atMillis, usage, origin)
    }
}
