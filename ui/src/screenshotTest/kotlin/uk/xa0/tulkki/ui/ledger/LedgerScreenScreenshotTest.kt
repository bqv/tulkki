package uk.xa0.tulkki.ui.ledger

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import uk.xa0.tulkki.data.translation.UsageByOrigin
import uk.xa0.tulkki.data.translation.UsageCounts
import uk.xa0.tulkki.data.translation.UsageOrigin
import uk.xa0.tulkki.data.translation.UsageOriginKind
import uk.xa0.tulkki.translation.BalanceSnapshot
import uk.xa0.tulkki.translation.TokenPrices
import uk.xa0.tulkki.translation.TokenUsage
import uk.xa0.tulkki.translation.UsageLedger
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The ledger's screenshot cells - ui-3's gate ("a JVM screenshot test from this first screen") on the
 * harness ui-11 landed, docs/MIGRATION.md "Design: the Compose UI" §7.1/§7.2.
 *
 * <p>§7.2: "A `@PreviewTest` renders **a state**, not a flow: the composable is fed a literal
 * `UiMessage` / `ConversationListState` / `LedgerState` built by the same pure fixtures the JVM tests
 * use", and "**both themes for every screen** ... because light is the map nobody looks at".
 *
 * <p>The fixture is built through [LedgerAssembly], so a screenshot is a picture of the state the
 * assembly really produces - the same path the screen is handed - rather than of a hand-written state
 * that could drift from it.
 */
@PreviewTest
@Preview(name = "ledger-dark", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 900)
@Composable
fun LedgerDarkScreenshot() = Fixture(darkTheme = true, state = fullState())

@PreviewTest
@Preview(name = "ledger-light", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 420, heightDp = 900)
@Composable
fun LedgerLightScreenshot() = Fixture(darkTheme = false, state = fullState())

/** §3.0's "empty": "no day rows with spend", with the total across what is shown. */
@PreviewTest
@Preview(name = "ledger-empty", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 420)
@Composable
fun LedgerEmptyScreenshot() =
    Fixture(darkTheme = true, state = LedgerAssembly.assemble(balance = null, used = 0, cap = 100_000, days = null, prices = TokenPrices.defaults(), appModel = null, listedIds = null, storedPrices = emptyMap(), tariff = null, failure = null, status = LedgerStatus.IDLE))

/** §3.0's interpreter-off state: one card, and nothing else. */
@PreviewTest
@Preview(name = "ledger-off", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 320)
@Composable
fun LedgerOffScreenshot() = Fixture(darkTheme = true, state = fullState(), enabled = false)

/**
 * The day drill-down, both themes: the owner's `ui-3`, over `:data`'s grouped read. The lines carry
 * an address where the read resolved one and the kind's own label where it did not - the several-rooms
 * batch and the deleted conversation in the middle of this fixture are the two that cannot be named by
 * an address and must not be shown as a stored key.
 */
@PreviewTest
@Preview(name = "ledger-breakdown", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 1100)
@Composable
fun LedgerBreakdownDarkScreenshot() = Fixture(darkTheme = true, state = fullState(), drill = DayDrill("2026-09-30", breakdown()))

@PreviewTest
@Preview(name = "ledger-breakdown-light", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 420, heightDp = 1100)
@Composable
fun LedgerBreakdownLightScreenshot() = Fixture(darkTheme = false, state = fullState(), drill = DayDrill("2026-09-30", breakdown()))

/**
 * The defect path: a read whose parts do not make the whole draws the sentence and no numbers at all,
 * because a breakdown that does not add up is worse than none.
 */
@PreviewTest
@Preview(name = "ledger-breakdown-broken", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 420, heightDp = 1100)
@Composable
fun LedgerBreakdownBrokenScreenshot() =
    Fixture(
        darkTheme = true,
        state = fullState(),
        drill =
            DayDrill(
                "2026-09-30",
                LedgerBreakdown.of(
                    UsageByOrigin(
                        day = "2026-09-30",
                        origins = listOf(UsageOrigin("", UsageOriginKind.NOT_TIED_TO_A_ROOM, null, counts(1, 2, 3))),
                        total = counts(9, 9, 9),
                    ),
                    TokenPrices.defaults(),
                ),
            ),
    )

@Composable
private fun Fixture(
    darkTheme: Boolean,
    state: LedgerState,
    enabled: Boolean = true,
    drill: DayDrill? = null,
) {
    TulkkiTheme(darkTheme = darkTheme) {
        LedgerScreen(
            state = state,
            prices = TokenPrices.defaults(),
            enabled = enabled,
            hasKey = true,
            notices = LedgerNotices(),
            drill = drill,
            onOpenDay = {},
            onEditPrice = {},
            onRefresh = {},
            onOpenSettings = {},
        )
    }
}

/**
 * A state with something in every section: a reading, a cap part-way used, three days, the key's model
 * listed, and the build's own tariff.
 */
private fun fullState(): LedgerState {
    val prices = TokenPrices.defaults()
    val days =
        listOf(
            UsageLedger.Day("2026-09-30", TokenUsage.forCall(12_000, 3_400, 900, true)),
            UsageLedger.Day("2026-09-29", TokenUsage.forCall(8_100, 2_200, 640, false)),
            UsageLedger.Day("2026-09-28", TokenUsage.forCall(5_000, 1_100, 300, true)),
        )
    val balance =
        BalanceSnapshot.fromJson(
            """
            {"is_available":true,"currency":"CNY","total_balance":"42.50","fetched_at":1789000000000}
            """
                .trimIndent()
        )
    return LedgerAssembly.assemble(
        balance = balance,
        used = 23_540,
        cap = 100_000,
        days = days,
        prices = prices,
        appModel = TokenPrices.defaultModel().id,
        listedIds = listOf(TokenPrices.defaultModel().id, "deepseek-chat"),
        storedPrices = mapOf(LedgerAssembly.PRICE_FIELDS[1] to "0.5"),
        tariff = null,
        failure = null,
        status = LedgerStatus.IDLE,
    )
}

/**
 * The first day's split, with one bucket of every kind: a call tied to nothing, a group chat and a
 * one-to-one contact (both with an address), a catch-up batch that covered several rooms, and a
 * conversation the owner has since deleted. The parts sum to the day's own row, so the screen draws
 * them rather than the defect sentence.
 */
private fun breakdown(): DayBreakdown {
    val tiedToNothing = counts(1_500, 400, 120)
    val groupChat = counts(2_000, 600, 200)
    val oneToOne = counts(800, 200, 90)
    val severalRooms = counts(4_000, 900, 300)
    val deleted = counts(300, 80, 20)
    return LedgerBreakdown.of(
        UsageByOrigin(
            day = "2026-09-30",
            origins =
                listOf(
                    UsageOrigin("", UsageOriginKind.NOT_TIED_TO_A_ROOM, null, tiedToNothing),
                    UsageOrigin("0a1b", UsageOriginKind.GROUP_CHAT, "study@rooms.example", groupChat),
                    UsageOrigin("2c3d", UsageOriginKind.ONE_TO_ONE, "mikko@example.org", oneToOne),
                    UsageOrigin(UsageOrigin.MULTI_ROOM_ORIGIN, UsageOriginKind.MULTI_ROOM, null, severalRooms),
                    UsageOrigin("4e5f", UsageOriginKind.DELETED_CONVERSATION, null, deleted),
                ),
            total = tiedToNothing + groupChat + oneToOne + severalRooms + deleted,
        ),
        TokenPrices.defaults(),
    )
}

/** One bucket's six counts, filed at peak: a fixture's shape, not a rule. */
private fun counts(hit: Int, miss: Int, output: Int): UsageCounts =
    UsageCounts(hit, miss, output, 0, 0, 0)
