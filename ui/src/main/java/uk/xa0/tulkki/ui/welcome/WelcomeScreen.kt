package uk.xa0.tulkki.ui.welcome

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.annotation.DrawableRes
import androidx.annotation.RawRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.caverock.androidsvg.SVG
import kotlin.math.ceil
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import uk.xa0.tulkki.ui.R

/**
 * The front door, in Compose: the four slides `activity_welcome.xml` used to draw with a
 * `ViewPager`, the dots and the Next button, and the sign-in page the rest of onboarding hangs off.
 *
 * <p>It is the screen [uk.xa0.tulkki.ui.WelcomeActivity] composes into
 * [uk.xa0.tulkki.ui.chrome.TulkkiChrome]; `activity_welcome.xml` (715 lines, 35 ids) and
 * `welcome_menu.xml` are deleted with the swap, and what was the pager's own machinery - the
 * `PagerAdapter` that pulled the four inflated pages out of the pager and handed them back - is the
 * `HorizontalPager` below.
 *
 * <p>**What changed shape and what did not.** The four pages, the order of the gateway and
 * third-party marks, the preferences table with its help buttons, the bottom bar's 130 dp and the
 * sign-in page's five buttons are the layout's; the pager is Compose's, the dots are four plain
 * circles rather than `DotsIndicator`'s animation, and the two SVG marks (`@raw/jmp`, `@raw/xmpp`)
 * are drawn through `androidsvg` into an [android.graphics.Bitmap] rather than hosted as an
 * `SVGImageView`. Every string and every value read or written is the activity's, unchanged: this
 * file renders [WelcomeSettings] and emits callbacks.
 *
 * <p>**The settings are saved when the pager settles**, which is the closest Compose equivalent of
 * the `OnPageChangeListener` the layout had - `onPageScrollStateChanged` wrote every switch value
 * on each scroll-state change. Leaving the preferences slide is what persisted in practice, and
 * `settledPage` is that event.
 *
 * <p>**The mark.** The 128 dp box and its 15 dp padding are the layout's header, filled from
 * `R.drawable.tulkki_logo` - the regenerated identity icon - and are what the mark-only screenshot
 * cells pin.
 *
 * @param settings the values of the eleven switch rows and nothing else.
 * @param working the onboarding account is connecting: the sign-in button says so and is disabled,
 *     exactly as `onBackendConnected` used to set it.
 * @param databaseEncryptionConfigured the database already has a password, so the row's button is
 *     the enabled summary and cannot be pressed again.
 * @param initialPage the slide to open on; only the screenshot fixtures move it off the intro.
 * @param onSettingsChange a switch moved.
 * @param onInfo a help button was tapped; the value is one of [WelcomeInfo]'s ids.
 * @param onSetupDatabaseEncryption the database-encryption row's button.
 */
@Composable
fun WelcomeScreen(
    settings: WelcomeSettings,
    working: Boolean,
    databaseEncryptionConfigured: Boolean,
    onSettingsChange: (WelcomeSettings) -> Unit,
    onInfo: (Int) -> Unit,
    onSetupDatabaseEncryption: () -> Unit,
    onRegister: () -> Unit,
    onLogIn: () -> Unit,
    onBackup: () -> Unit,
    onSnikket: () -> Unit,
    onCertificate: () -> Unit,
    onSettingsSettled: () -> Unit,
    initialPage: Int = 0,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { PAGE_COUNT })
    val scope = rememberCoroutineScope()

    // The onboarding account connecting is the one thing that moves a slide from outside: the old
    // `setCurrentItem(4)` was clamped by the ViewPager to its last page, which is the sign-in page.
    LaunchedEffect(working) {
        if (working) {
            pagerState.animateScrollToPage(SIGN_IN_PAGE)
        }
    }

    // The layout persisted its switches on every scroll-state change; the settled page is the event
    // that means the same thing here. The first emission is the initial composition and is dropped,
    // so opening the screen does not write preferences nobody touched.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.drop(1).collect { onSettingsSettled() }
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth().weight(1f)) { page ->
                when (page) {
                    0 -> IntroPage()
                    1 -> NetworkPage()
                    2 ->
                        PreferencesPage(
                            settings = settings,
                            databaseEncryptionConfigured = databaseEncryptionConfigured,
                            onSettingsChange = onSettingsChange,
                            onInfo = onInfo,
                            onSetupDatabaseEncryption = onSetupDatabaseEncryption,
                        )
                    else ->
                        SignInPage(
                            working = working,
                            onRegister = onRegister,
                            onLogIn = onLogIn,
                            onBackup = onBackup,
                            onSnikket = onSnikket,
                            onCertificate = onCertificate,
                        )
                }
            }
            BottomBar(
                pageCount = PAGE_COUNT,
                currentPage = pagerState.currentPage,
                onNext = {
                    scope.launch {
                        pagerState.animateScrollToPage(
                            (pagerState.currentPage + 1).coerceAtMost(PAGE_COUNT - 1),
                        )
                    }
                },
            )
        }
    }
}

/** The pager's slide count; the `PagerAdapter`'s own `getCount()`. */
private const val PAGE_COUNT = 4

/** The sign-in slide: the last page, and the one `onBackendConnected` walks to while working. */
private const val SIGN_IN_PAGE = 3

/** The eleven switches, as the activity reads and writes them. */
data class WelcomeSettings(
    val loadProvidersExternal: Boolean = false,
    val allowScreenshots: Boolean = false,
    val showLinks: Boolean = false,
    val chatStates: Boolean = false,
    val confirmMessages: Boolean = false,
    val lastSeen: Boolean = false,
    val blindTrust: Boolean = false,
    val dane: Boolean = false,
    val useSecureTls: Boolean = false,
    val storeSecurely: Boolean = false,
    val sendCrashReports: Boolean = false,
)

/** The `showInfo` ids the help buttons carry; the same values the host's companion had. */
object WelcomeInfo {
    const val LOAD_PROVIDERS_EXTERNAL = 0
    const val ALLOW_SCREENSHOTS = 1
    const val SHOW_WEBLINKS = 2
    const val CHAT_STATES = 5
    const val CONFIRM_MESSAGES = 6
    const val LAST_SEEN = 7
    const val BLIND_TRUST = 8
    const val ENFORCE_DANE = 9
    const val USE_SECURE_TLS_CIPHERS = 10
    const val USE_SECURE_STORAGE = 11
    const val SEND_CRASH_REPORTS = 12
    const val DATABASE_ENCRYPTION = 13
}

/** Slide one: the mark, the two lines about the network, and the gateway and third-party marks. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IntroPage() {
    Column(
        modifier =
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(15.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.tulkki_logo),
            contentDescription = null,
            modifier = Modifier.padding(15.dp).size(128.dp),
        )
        SectionTitle(R.string.welcome_to_tulkki)
        Text(
            text = stringResource(R.string.intro_description),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 15.dp),
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalArrangement = Arrangement.Center,
        ) {
            SvgMark(R.raw.jmp)
            GatewayMark(R.drawable.android_messages)
            GatewayMark(R.drawable.ic_call_24dp, monochrome = true)
            GatewayMark(R.drawable.ic_email_48dp, monochrome = true)
            GatewayMark(R.drawable.irc, monochrome = true)
            GatewayMark(R.drawable.matrix, monochrome = true)
            GatewayMark(R.drawable.xmpp_logo)
            GatewayMark(R.drawable.snikket)
            SvgMark(R.raw.xmpp)
        }
    }
}

/** Slide two: how federation works, and the diagram that says it. */
@Composable
private fun NetworkPage() {
    Column(
        modifier =
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(15.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.xmpp_logo),
            contentDescription = null,
            modifier = Modifier.padding(15.dp).size(128.dp),
        )
        SectionTitle(R.string.how_the_xmpp_network_works)
        Text(
            text = stringResource(R.string.xmpp_network_description),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 15.dp),
        )
        SectionTitle(R.string.example_jid, bottom = 30.dp)
        SvgMark(R.raw.federation_diagram, Modifier.fillMaxWidth().height(240.dp))
    }
}

/** Slide three: the preferences table, its help buttons and the database-encryption row. */
@Composable
private fun PreferencesPage(
    settings: WelcomeSettings,
    databaseEncryptionConfigured: Boolean,
    onSettingsChange: (WelcomeSettings) -> Unit,
    onInfo: (Int) -> Unit,
    onSetupDatabaseEncryption: () -> Unit,
) {
    Column(
        modifier =
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(15.dp),
    ) {
        SectionTitle(
            R.string.set_preferences_title,
            center = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.set_preferences_summary),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 15.dp),
        )
        SwitchRow(
            label = R.string.pref_load_providers_list_external,
            info = WelcomeInfo.LOAD_PROVIDERS_EXTERNAL,
            checked = settings.loadProvidersExternal,
            onCheckedChange = { onSettingsChange(settings.copy(loadProvidersExternal = it)) },
            onInfo = onInfo,
        )
        SwitchRow(
            label = R.string.pref_allow_screenshots,
            info = WelcomeInfo.ALLOW_SCREENSHOTS,
            checked = settings.allowScreenshots,
            onCheckedChange = { onSettingsChange(settings.copy(allowScreenshots = it)) },
            onInfo = onInfo,
        )
        SwitchRow(
            label = R.string.show_link_previews,
            info = WelcomeInfo.SHOW_WEBLINKS,
            checked = settings.showLinks,
            onCheckedChange = { onSettingsChange(settings.copy(showLinks = it)) },
            onInfo = onInfo,
        )
        SwitchRow(
            label = R.string.pref_chat_states,
            info = WelcomeInfo.CHAT_STATES,
            checked = settings.chatStates,
            onCheckedChange = { onSettingsChange(settings.copy(chatStates = it)) },
            onInfo = onInfo,
        )
        SwitchRow(
            label = R.string.pref_confirm_messages,
            info = WelcomeInfo.CONFIRM_MESSAGES,
            checked = settings.confirmMessages,
            onCheckedChange = { onSettingsChange(settings.copy(confirmMessages = it)) },
            onInfo = onInfo,
        )
        SwitchRow(
            label = R.string.pref_broadcast_last_activity,
            info = WelcomeInfo.LAST_SEEN,
            checked = settings.lastSeen,
            onCheckedChange = { onSettingsChange(settings.copy(lastSeen = it)) },
            onInfo = onInfo,
        )
        SwitchRow(
            label = R.string.pref_blind_trust_before_verification,
            info = WelcomeInfo.BLIND_TRUST,
            checked = settings.blindTrust,
            onCheckedChange = { onSettingsChange(settings.copy(blindTrust = it)) },
            onInfo = onInfo,
        )
        SwitchRow(
            label = R.string.pref_enforce_dane,
            info = WelcomeInfo.ENFORCE_DANE,
            checked = settings.dane,
            onCheckedChange = { onSettingsChange(settings.copy(dane = it)) },
            onInfo = onInfo,
        )
        SwitchRow(
            label = R.string.pref_secure_tls,
            info = WelcomeInfo.USE_SECURE_TLS_CIPHERS,
            checked = settings.useSecureTls,
            onCheckedChange = { onSettingsChange(settings.copy(useSecureTls = it)) },
            onInfo = onInfo,
        )
        SwitchRow(
            label = R.string.store_media_only_in_cache,
            info = WelcomeInfo.USE_SECURE_STORAGE,
            checked = settings.storeSecurely,
            onCheckedChange = { onSettingsChange(settings.copy(storeSecurely = it)) },
            onInfo = onInfo,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.pref_database_encryption),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            InfoButton(WelcomeInfo.DATABASE_ENCRYPTION, onInfo)
            TextButton(
                onClick = onSetupDatabaseEncryption,
                enabled = !databaseEncryptionConfigured,
            ) {
                Text(
                    text =
                        stringResource(
                            if (databaseEncryptionConfigured) {
                                R.string.pref_database_encryption_summary_enabled
                            } else {
                                R.string.setup
                            },
                        ),
                )
            }
        }
        SwitchRow(
            label = R.string.pref_send_crash_reports,
            info = WelcomeInfo.SEND_CRASH_REPORTS,
            checked = settings.sendCrashReports,
            onCheckedChange = { onSettingsChange(settings.copy(sendCrashReports = it)) },
            onInfo = onInfo,
        )
    }
}

/** Slide four: the five ways in, in the layout's order and at the layout's sizes. */
@Composable
private fun SignInPage(
    working: Boolean,
    onRegister: () -> Unit,
    onLogIn: () -> Unit,
    onBackup: () -> Unit,
    onSnikket: () -> Unit,
    onCertificate: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.tulkki_logo),
                contentDescription = null,
                modifier = Modifier.padding(8.dp).size(128.dp),
            )
        }
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .weight(1f)
                    .padding(start = 50.dp, end = 50.dp, bottom = 10.dp),
        ) {
            Button(
                onClick = onRegister,
                enabled = !working,
                colors = ButtonDefaults.filledTonalButtonColors(),
                modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp).height(58.dp),
            ) {
                Text(
                    text = if (working) "Working..." else stringResource(R.string.create_new_account),
                    fontSize = 17.sp,
                )
            }
            OutlinedButton(
                onClick = onLogIn,
                modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp).height(60.dp),
            ) {
                Text(stringResource(R.string.log_in), fontSize = 17.sp)
            }
            OutlinedButton(
                onClick = onBackup,
                modifier = Modifier.fillMaxWidth().padding(bottom = 40.dp).height(58.dp),
            ) {
                Text(stringResource(R.string.restore_backup), fontSize = 17.sp)
            }
            OutlinedButton(
                onClick = onSnikket,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).height(55.dp),
            ) {
                Text(stringResource(R.string.i_am_snikket_user), fontSize = 15.sp)
            }
            OutlinedButton(
                onClick = onCertificate,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).height(55.dp),
            ) {
                Text(stringResource(R.string.action_add_account_with_certificate), fontSize = 15.sp)
            }
        }
    }
}

/** The bar the layout pinned 130 dp high under the pager: the dots, and Next until the last slide. */
@Composable
private fun BottomBar(pageCount: Int, currentPage: Int, onNext: () -> Unit) {
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .height(130.dp)
                .background(MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (page in 0 until pageCount) {
                Box(
                    modifier =
                        Modifier.size(8.dp)
                            .background(
                                color =
                                    if (page == currentPage) {
                                        Color.White
                                    } else {
                                        MaterialTheme.colorScheme.tertiary
                                    },
                                shape = CircleShape,
                            ),
                )
            }
        }
        if (currentPage <= SIGN_IN_PAGE - 1) {
            Button(
                onClick = onNext,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary,
                    ),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 32.dp, bottom = 32.dp),
            ) {
                Text(stringResource(R.string.next))
            }
        }
    }
}

/** One preference row: its label, the help button, and the switch. */
@Composable
private fun SwitchRow(
    label: Int,
    info: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onInfo: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        InfoButton(info, onInfo)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** The `ic_help_24dp` button every row carries; it opens the host's own info dialog. */
@Composable
private fun InfoButton(info: Int, onInfo: (Int) -> Unit) {
    IconButton(onClick = { onInfo(info) }) {
        Icon(
            painter = painterResource(R.drawable.ic_help_24dp),
            contentDescription = null,
        )
    }
}

/** The layout's 18 sp `noto_sans_bold` heading, in the scheme's own secondary-container colour. */
@Composable
private fun SectionTitle(
    label: Int,
    bottom: Dp = 15.dp,
    center: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Text(
        text = stringResource(label),
        style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        textAlign = if (center) TextAlign.Center else null,
        modifier = modifier.padding(bottom = bottom),
    )
}

/**
 * One 60 dp gateway or third-party mark with the layout's 10 dp margin around it.
 *
 * <p>[monochrome] is for the four marks that carry no colour of their own - `ic_call_24dp`,
 * `ic_email_48dp`, `irc`, `matrix`. Each fills white and declares
 * `android:tint="?colorControlNormal"`, which Compose's vector parser does not resolve, so on the
 * light theme they were white on a near-white background and read as nothing. On a light surface
 * such a mark is drawn in the scheme's own ink; the dark surface already wants the white the
 * drawable fills, so nothing is filtered there and the dark drawing is unchanged.
 */
@Composable
private fun GatewayMark(@DrawableRes res: Int, monochrome: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Image(
        painter = painterResource(res),
        contentDescription = null,
        colorFilter =
            if (monochrome && scheme.background.luminance() > 0.5f) {
                ColorFilter.tint(scheme.onSurface)
            } else {
                null
            },
        modifier = Modifier.padding(10.dp).size(60.dp),
    )
}

/**
 * One SVG mark (`@raw/jmp`, `@raw/xmpp`, the federation diagram). `androidsvg` has no Compose
 * painter, so the document is rendered once into a bitmap at its own size and drawn as an ordinary
 * [Image]; the layout's `SVGImageView` did the same drawing through `ImageView`.
 */
@Composable
private fun SvgMark(@RawRes res: Int, modifier: Modifier = Modifier.padding(10.dp).size(60.dp)) {
    val context = LocalContext.current
    val image =
        remember(res) {
            runCatching {
                val svg = SVG.getFromResource(context.resources, res)
                val width = svg.documentWidth
                val height = svg.documentHeight
                if (width <= 0f || height <= 0f) {
                    null
                } else {
                    val bitmap =
                        Bitmap.createBitmap(
                            ceil(width).toInt(),
                            ceil(height).toInt(),
                            Bitmap.Config.ARGB_8888,
                        )
                    svg.renderToCanvas(Canvas(bitmap))
                    bitmap.asImageBitmap()
                }
            }.getOrNull()
        }
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier,
        )
    } else {
        Box(modifier = modifier)
    }
}
