package uk.xa0.tulkki.ui.welcome

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import uk.xa0.tulkki.ui.R

/**
 * "Pick your XMPP service", in Compose: the mark centred over two ways on, exactly the two buttons
 * `activity_pick_server.xml` carried. It is the screen
 * [uk.xa0.tulkki.ui.PickServerActivity] composes into
 * [uk.xa0.tulkki.ui.chrome.TulkkiChrome], and the 94-line layout is deleted with the swap.
 *
 * <p>Each button is a callback and nothing else: the activity decides where "Create a new XMPP
 * address" and "Use my own provider" go, including the invite URI they carry.
 */
@Composable
fun PickServerScreen(
    onUseDefault: () -> Unit,
    onUseOwnProvider: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.fillMaxSize(),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(R.drawable.tulkki_logo),
                contentDescription = null,
                modifier = Modifier.align(Alignment.Center).padding(8.dp).size(128.dp),
            )
            Column(
                modifier =
                    Modifier.align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
            ) {
                Text(
                    text = stringResource(R.string.pick_a_server),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(R.string.server_select_text),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                )
                Button(
                    onClick = onUseDefault,
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(R.string.use_default_provider))
                }
                TextButton(
                    onClick = onUseOwnProvider,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(R.string.use_own_provider))
                }
            }
        }
    }
}
