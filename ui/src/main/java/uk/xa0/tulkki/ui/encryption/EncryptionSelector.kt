package uk.xa0.tulkki.ui.encryption

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.theme.TulkkiSpacing
import uk.xa0.tulkki.xmpp.R as XmppR

/**
 * Tulkki: the conversation's encryption selector, in Compose - the surface
 * `ConversationFragment.handleEncryptionSelection` (`ConversationFragment.java:3006-3053`) and the
 * `action_security` submenu `ConversationMenuConfigurator.configureEncryptionMenu`
 * (`ui/util/ConversationMenuConfigurator.kt:46-114`, built at `ConversationFragment.java:1970`)
 * carry in Java/Kotlin-today. It is wired now: `ConversationComposer` draws it from
 * `UiComposer.encryption`, `ConversationHost.encryption` is the one place the offered set is
 * resolved, and the Java submenu, `handleEncryptionSelection` and `configureEncryptionMenu` are
 * deleted, so this file is the only implementation of the selector rather than a new surface beside
 * one.
 *
 * <p>**What the choices are.** Four, and each is a value `Conversation.setNextEncryption` accepts,
 * carried by [EncryptionChoice.nextEncryption]: [EncryptionChoice.NONE] (`Message.ENCRYPTION_NONE`,
 * the menu's "None"), [EncryptionChoice.OTR], [EncryptionChoice.OMEMO] (`Message.ENCRYPTION_AXOLOTL`
 * - the wire identifier upstream and this tree spell as OMEMO in the UI) and [EncryptionChoice.OPENPGP].
 *
 * <p>**What decides the current one.** `conversation.getNextEncryption()` alone; the caller resolves
 * it to an [EncryptionChoice] with [EncryptionChoice.ofNextEncryption] and passes the result in.
 * The trigger draws the locked/open lock icon the configurator did - `lock_icon` for anything but
 * `NONE`, `outline_lock_open_24` for `NONE` - and names the choice with its own sentence
 * ([EncryptionChoice.state], the strings `configureEncryptionMenu` set as the item title:
 * "Not encrypted", "Encrypted with OMEMO" and so on).
 *
 * <p>**What is offered** is not decided here: the caller hands in the [EncryptionOption] list, and
 * [encryptionSelectionState] is the executable reading of `configureEncryptionMenu`'s visibility
 * chain for a caller that wants it. That chain is the configurator's, not a second opinion: a
 * non-participating room and `OmemoSetting.isAlways()` hide the whole selector; in single mode the
 * selector is drawn only when `Config.multipleEncryptionChoices()`; in a room it is drawn only when
 * `Config.supportOpenPgp()` or `Config.supportOmemo()` also hold and the room is private-and-non-anonymous
 * (now or formerly) unless the current choice is not `NONE`; `None` is offered when
 * `Config.supportUnencrypted()` or the conversation is a room; `OpenPGP` when `Config.supportOpenPgp()`;
 * `OMEMO` when `Config.supportOmemo()`; `OTR` when `Config.supportOtr()` **and** the
 * `enable_otr_encryption` boolean preference **and** the conversation is not a room. An empty option
 * list is the configurator's `menuSecure.setVisible(false)` and the selector draws nothing at all,
 * which is the shape the tree already had.
 *
 * <p>**What changes when it is picked** is the host's, and only the host's: this composable emits
 * [onSelect] with the [EncryptionChoice], and the caller does what the Java did - `setNextEncryption`
 * and, when the attribute moved, `xmppConnectionService.updateConversation`, followed by the shell's
 * own refresh. Nothing durable is stored here.
 *
 * <p>**What the user is told when a choice is unavailable.** Only one choice can be *offered yet not
 * pickable*: OpenPGP. `activity.hasPgp()` false means no provider app is installed, and
 * `account.getPgpSignature()` null means the account has published no key - the two branches the Java
 * took instead of setting the encryption. This surface draws no dialog (it has no activity), so it
 * carries the difference as [EncryptionBlock]: the row stays drawn and tappable, it explains itself
 * with [EncryptionBlock.explanation] beneath the label, and picking it emits [onBlocked] so the host
 * runs the Java's own effect - `activity.showInstallPgpDialog()` for [EncryptionBlock.OPENPGP_PROVIDER_MISSING],
 * `activity.announcePgp(account, conversation, null, activity.onOpenPGPKeyPublished)` for
 * [EncryptionBlock.OPENPGP_KEY_UNPUBLISHED]. A choice the config does not support is not told about,
 * it is simply not offered - exactly as the Java menu hid it.
 *
 * <p>**It is entangled with no product setting.** The app language, the study language, the daily cap
 * and the retry wording are the owner's and none of them decides an encryption choice; the
 * conversation's own language is a send target rather than a UI preference and is likewise absent.
 * The only preference this surface's vocabulary touches is `enable_otr_encryption`, an ordinary
 * device boolean about protocol support, not a translation setting.
 *
 * <p>**The exact signature**, so the wiring lane reconciles by reading:
 *
 * ```
 * @Composable
 * fun EncryptionSelector(
 *     state: EncryptionSelectionState,
 *     modifier: Modifier = Modifier,
 *     onSelect: (EncryptionChoice) -> Unit,
 *     onBlocked: (EncryptionChoice, EncryptionBlock) -> Unit,
 * )
 * ```
 *
 * <p>It is stateless: it holds no `View`, no fragment and no activity, reads no singleton, and the
 * only state it keeps is the transient `expanded` flag in `remember`. Everything durable - which
 * conversation, which choice is current, which options are offered, and what a pick does - belongs
 * to the caller and arrives as [state] and the two callbacks.
 */
@Composable
fun EncryptionSelector(
    state: EncryptionSelectionState,
    modifier: Modifier = Modifier,
    onSelect: (EncryptionChoice) -> Unit,
    onBlocked: (EncryptionChoice, EncryptionBlock) -> Unit,
) {
    if (!state.visible) {
        return
    }
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painter = painterResource(
                    if (state.current == EncryptionChoice.NONE) {
                        R.drawable.outline_lock_open_24
                    } else {
                        R.drawable.lock_icon
                    },
                ),
                contentDescription = stringResource(state.current.state),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (option in state.options) {
                EncryptionChoiceRow(
                    option = option,
                    checked = option.choice == state.current,
                    onPick = {
                        expanded = false
                        val block = option.block
                        if (block == null) {
                            onSelect(option.choice)
                        } else {
                            onBlocked(option.choice, block)
                        }
                    },
                )
            }
        }
    }
}

/**
 * One row of the selector: the choice's own name, a check when it is the current one, and the
 * sentence for a choice that is offered but not pickable yet.
 *
 * <p>A blocked row is deliberately still enabled: the Java let the PGP item be picked and answered
 * with the install dialog or the announce flow, so disabling it would remove the only affordance the
 * owner has. The explanation is drawn rather than kept for the host, so the telling does not depend
 * on a dialog being reached.
 */
@Composable
private fun EncryptionChoiceRow(
    option: EncryptionOption,
    checked: Boolean,
    onPick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Column {
                Text(
                    text = stringResource(option.choice.label),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val block = option.block
                if (block != null) {
                    Text(
                        text = stringResource(block.explanation),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = TulkkiSpacing.xxs),
                    )
                }
            }
        },
        leadingIcon = {
            if (checked) {
                Icon(
                    painter = painterResource(R.drawable.ic_check_24dp),
                    contentDescription = null,
                )
            }
        },
        onClick = onPick,
    )
}

/**
 * The four values `Conversation.setNextEncryption` accepts, named for what they hold.
 *
 * <p>[nextEncryption] is the `Message.ENCRYPTION_*` value itself, so the host writes a pick back
 * without a second mapping. [label] is the short menu name - the `encryption_choice_*` strings,
 * which live in `:xmpp`'s resources and are named here explicitly because `:ui`'s own R does not
 * carry them. [state] is the configurator's item title, the "Encrypted with ..." sentence.
 */
enum class EncryptionChoice(
    val nextEncryption: Int,
    @StringRes val label: Int,
    @StringRes val state: Int,
) {
    NONE(Message.ENCRYPTION_NONE, XmppR.string.encryption_choice_unencrypted, R.string.not_encrypted),
    OTR(Message.ENCRYPTION_OTR, XmppR.string.encryption_choice_otr, R.string.encrypted_with_otr),
    OMEMO(Message.ENCRYPTION_AXOLOTL, XmppR.string.encryption_choice_omemo, R.string.encrypted_with_omemo),
    OPENPGP(Message.ENCRYPTION_PGP, XmppR.string.encryption_choice_pgp, R.string.encrypted_with_openpgp),
    ;

    companion object {
        /** The choice `conversation.getNextEncryption()` names; anything unknown is [NONE]. */
        fun ofNextEncryption(next: Int): EncryptionChoice =
            EncryptionChoice.entries.firstOrNull { it.nextEncryption == next } ?: NONE
    }
}

/**
 * Why an offered choice cannot become the current one yet - the two branches the Java took instead
 * of calling `setNextEncryption`.
 *
 * <p>[explanation] is the sentence the row draws, and it reuses the strings the Java's own flows
 * already use: `openkeychain_not_installed` ("Please install OpenKeychain") for a missing provider
 * app, `mgmt_account_publish_pgp` ("Publish OpenPGP public key") for an account that has published
 * none. The host still owns the effect; the enum only names it.
 */
enum class EncryptionBlock(@StringRes val explanation: Int) {
    /** No OpenPGP provider app: `activity.hasPgp()` was false, and the Java ran `showInstallPgpDialog()`. */
    OPENPGP_PROVIDER_MISSING(R.string.openkeychain_not_installed),

    /**
     * A provider exists but this account has published no key: `account.getPgpSignature()` was null,
     * and the Java ran `announcePgp(...)`, which sets the encryption itself when it succeeds.
     */
    OPENPGP_KEY_UNPUBLISHED(R.string.mgmt_account_publish_pgp),
}

/** One offered choice, and the [EncryptionBlock] when picking it cannot change the state yet. */
data class EncryptionOption(
    val choice: EncryptionChoice,
    val block: EncryptionBlock? = null,
)

/**
 * What the selector draws, the whole of its input: the current choice and everything the caller
 * decided is offered.
 *
 * <p>[options] empty is the drawn-nothing shape (`menuSecure.setVisible(false)`), so [visible]
 * answers the one question the composable asks before it draws anything.
 */
data class EncryptionSelectionState(
    val current: EncryptionChoice,
    val options: List<EncryptionOption>,
) {
    val visible: Boolean get() = options.isNotEmpty()
}

/**
 * The offered set, as a pure reading of `ConversationMenuConfigurator.configureEncryptionMenu`'s
 * visibility chain - so the wiring lane hands in facts instead of re-deriving the rules, and a JVM
 * test can pin them without an Android menu.
 *
 * <p>Every parameter is a fact the caller already holds; none is read from a singleton here. In the
 * order the Java read them:
 *
 * * [nextEncryption] - `conversation.getNextEncryption()`.
 * * [multi] - `conversation.getMode() == Conversational.MODE_MULTI`.
 * * [participating] - the Java's own `participating`: `mode == MODE_SINGLE || mucOptions.participating()`.
 * * [privateAndNonAnonymous] - `conversation.isPrivateAndNonAnonymous()`.
 * * [formerlyPrivateNonAnonymous] -
 *   `conversation.getBooleanAttribute(ATTRIBUTE_FORMERLY_PRIVATE_NON_ANONYMOUS, false)`.
 * * [unencryptedSupported], [openPgpSupported], [omemoSupported], [otrSupported] -
 *   `Config.supportUnencrypted()` / `supportOpenPgp()` / `supportOmemo()` / `supportOtr()`.
 *   `multipleChoices` is **derived** from these four, exactly as
 *   `Config.multipleEncryptionChoices()` reads its own four-bit mask, so the two cannot drift.
 * * [omemoAlways] - `OmemoSetting.isAlways()`.
 * * [otrEnabled] - the `enable_otr_encryption` boolean preference.
 * * [openPgpProviderInstalled] - `activity.hasPgp()`.
 * * [openPgpKeyPublished] - `account.getPgpSignature() != null`.
 */
@Suppress("LongParameterList")
fun encryptionSelectionState(
    nextEncryption: Int,
    multi: Boolean,
    participating: Boolean,
    privateAndNonAnonymous: Boolean,
    formerlyPrivateNonAnonymous: Boolean,
    unencryptedSupported: Boolean,
    openPgpSupported: Boolean,
    omemoSupported: Boolean,
    otrSupported: Boolean,
    omemoAlways: Boolean,
    otrEnabled: Boolean,
    openPgpProviderInstalled: Boolean,
    openPgpKeyPublished: Boolean,
): EncryptionSelectionState {
    val current = EncryptionChoice.ofNextEncryption(nextEncryption)
    val multipleChoices =
        listOf(unencryptedSupported, openPgpSupported, omemoSupported, otrSupported).count { it } > 1
    val visible = when {
        !participating -> false
        omemoAlways -> false
        multi ->
            (current != EncryptionChoice.NONE || privateAndNonAnonymous || formerlyPrivateNonAnonymous) &&
                (openPgpSupported || omemoSupported) &&
                multipleChoices
        else -> multipleChoices
    }
    if (!visible) {
        return EncryptionSelectionState(current, emptyList())
    }
    val options = buildList {
        if (unencryptedSupported || multi) {
            add(EncryptionOption(EncryptionChoice.NONE))
        }
        if (otrSupported && otrEnabled && !multi) {
            add(EncryptionOption(EncryptionChoice.OTR))
        }
        if (omemoSupported) {
            add(EncryptionOption(EncryptionChoice.OMEMO))
        }
        if (openPgpSupported) {
            add(EncryptionOption(EncryptionChoice.OPENPGP, openPgpBlock(openPgpProviderInstalled, openPgpKeyPublished)))
        }
    }
    return EncryptionSelectionState(current, options)
}

/** The Java's two OpenPGP checks in their order: no provider first, then no published key. */
private fun openPgpBlock(providerInstalled: Boolean, keyPublished: Boolean): EncryptionBlock? =
    when {
        !providerInstalled -> EncryptionBlock.OPENPGP_PROVIDER_MISSING
        !keyPublished -> EncryptionBlock.OPENPGP_KEY_UNPUBLISHED
        else -> null
    }
