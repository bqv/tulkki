package uk.xa0.tulkki.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.dialogs.CaptchaDialogBody
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The small screens' screenshot cells, in both themes (docs/MIGRATION.md "Design: the Compose UI"
 * §7.1/§7.2).
 *
 * <p>**What each pair pins.** The whole visible screen the deleted layout drew, where the screen
 * owns it: the uri handler's primary-container surface with its spinner; the share-location and
 * show-location bodies with their chrome, their button bar and their fix/navigate buttons; the
 * publish-avatar frame, hints, switch and bar; the group-chat avatar, the same body with its switch
 * and its long-press hint gone; and the captcha dialog's title, field and buttons.
 *
 * <p>**The platform surfaces are empty slots.** Both location screens are an OpenStreetMap
 * `MapView` behind those controls, the publish screen's picture is a `Drawable` in an `ImageView`,
 * and the captcha dialog's image is a server-drawn `Bitmap`; a cell cannot draw any of them, so each
 * passes an empty slot (or a null image) and pins everything around it - the split the add-reaction
 * and viewer cells make for their own platform views.
 *
 * <p>**The scanner has no cells at all.** `ScanActivity` is a camera `TextureView` with the
 * `ScannerView` mask over it and nothing else, so a cell would pin the chrome and nothing of the
 * screen; it is the one screen in this family with no face layoutlib can draw.
 *
 * <p>**Why the chrome is in most of them.** These screens draw no background of their own: the
 * `Scaffold` inside [TulkkiChrome] paints it, so a body alone would render transparent. The uri
 * handler is the exception - its layout had no toolbar, and its surface paints its own colour.
 */
@PreviewTest
@Preview(
    name = "uri-handler-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun UriHandlerDarkScreenshot() = UriHandlerFixture(darkTheme = true)

@PreviewTest
@Preview(
    name = "uri-handler-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun UriHandlerLightScreenshot() = UriHandlerFixture(darkTheme = false)

@PreviewTest
@Preview(
    name = "share-location-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun ShareLocationDarkScreenshot() = ShareLocationFixture(darkTheme = true)

@PreviewTest
@Preview(
    name = "share-location-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun ShareLocationLightScreenshot() = ShareLocationFixture(darkTheme = false)

@PreviewTest
@Preview(
    name = "show-location-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun ShowLocationDarkScreenshot() = ShowLocationFixture(darkTheme = true)

@PreviewTest
@Preview(
    name = "show-location-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun ShowLocationLightScreenshot() = ShowLocationFixture(darkTheme = false)

@PreviewTest
@Preview(
    name = "publish-avatar-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun PublishAvatarDarkScreenshot() = PublishAvatarFixture(darkTheme = true)

@PreviewTest
@Preview(
    name = "publish-avatar-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun PublishAvatarLightScreenshot() = PublishAvatarFixture(darkTheme = false)

@PreviewTest
@Preview(
    name = "publish-group-avatar-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun PublishGroupAvatarDarkScreenshot() = PublishGroupAvatarFixture(darkTheme = true)

@PreviewTest
@Preview(
    name = "publish-group-avatar-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun PublishGroupAvatarLightScreenshot() = PublishGroupAvatarFixture(darkTheme = false)

@PreviewTest
@Preview(
    name = "captcha-dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun CaptchaDarkScreenshot() = CaptchaFixture(darkTheme = true)

@PreviewTest
@Preview(
    name = "captcha-light",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    widthDp = 420,
    heightDp = 900,
)
@Composable
fun CaptchaLightScreenshot() = CaptchaFixture(darkTheme = false)

@Composable
private fun UriHandlerFixture(darkTheme: Boolean) {
    TulkkiTheme(darkTheme = darkTheme) { UriHandlerScreen(state = UriHandlerScreenState()) }
}

@Composable
private fun ShareLocationFixture(darkTheme: Boolean) {
    TulkkiTheme(darkTheme = darkTheme) {
        TulkkiChrome(
            title = stringResource(R.string.title_activity_share_location),
            onUp = {},
        ) {
            ShareLocationScreen(
                state = ShareLocationScreenState(fabVisible = true, fabFixed = true),
                map = {},
                onCancel = {},
                onShare = {},
                onFab = {},
                onEnableLocation = {},
            )
        }
    }
}

@Composable
private fun ShowLocationFixture(darkTheme: Boolean) {
    TulkkiTheme(darkTheme = darkTheme) {
        TulkkiChrome(
            title = stringResource(R.string.title_activity_show_location),
            onUp = {},
        ) {
            ShowLocationScreen(navigationAvailable = true, map = {}, onNavigate = {})
        }
    }
}

@Composable
private fun PublishAvatarFixture(darkTheme: Boolean) {
    TulkkiTheme(darkTheme = darkTheme) {
        TulkkiChrome(
            title = stringResource(R.string.mgmt_account_publish_avatar),
            onUp = {},
        ) {
            PublishProfilePictureScreen(
                state = PublishProfilePictureScreenState(),
                avatar = {},
                onContactOnlyChange = {},
                onCancel = {},
                onPublish = {},
            )
        }
    }
}

@Composable
private fun PublishGroupAvatarFixture(darkTheme: Boolean) {
    TulkkiTheme(darkTheme = darkTheme) {
        TulkkiChrome(
            title = stringResource(R.string.group_chat_avatar),
            onUp = {},
        ) {
            PublishProfilePictureScreen(
                state =
                    PublishProfilePictureScreenState(
                        secondaryHint = SecondaryHintVisibility.GONE,
                        contactOnlyVisible = false,
                    ),
                avatar = {},
                onContactOnlyChange = {},
                onCancel = {},
                onPublish = {},
            )
        }
    }
}

@Composable
private fun CaptchaFixture(darkTheme: Boolean) {
    TulkkiTheme(darkTheme = darkTheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CaptchaDialogBody(captcha = null, onDismiss = {}, onConfirm = {})
            }
        }
    }
}
