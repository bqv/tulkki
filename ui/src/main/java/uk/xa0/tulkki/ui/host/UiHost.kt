package uk.xa0.tulkki.ui.host

import android.app.Activity
import android.app.NotificationChannel
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle

import androidx.core.content.pm.ShortcutInfoCompat

import com.google.common.base.Optional

import io.ipfs.cid.Cid
import io.michaelrocks.libphonenumber.android.NumberParseException

import uk.xa0.tulkki.libs.AudioDevice
import uk.xa0.tulkki.libs.Avatarable
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Contact
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.data.model.MucOptions
import uk.xa0.tulkki.data.model.Room
import uk.xa0.tulkki.libs.Transferable
import uk.xa0.tulkki.translation.DisplayedBody

import java.net.URISyntaxException
import java.security.NoSuchAlgorithmException
import java.util.UUID
import java.util.function.Consumer
import java.util.regex.Pattern

/**
 * Everything `:ui` asks of the composition root, declared here and implemented by
 * `uk.xa0.tulkki.app.UiAppHost`.
 *
 * 3.7 pair 3, the direction `:ui` -> `:app` (D14). `:ui` is a View-based module sitting below `:app`
 * in the map's order, so it may name `:libs`, `:xmpp` (declared by pair 11), `:crypto`, `:data` and
 * `:translation` - and the composition root is the one module it may not name. Every site this
 * commit removes was `:ui` naming a class the root owns: the connection service's constants, the
 * utils that query the platform, the navigation `SignupUtils` builds, the notification channels, the
 * backup workers, the call starter. None of that is view code; all of it is the app operating on
 * the view's behalf.
 *
 * **Why one interface and not one port per carrier.** The alternative shape - move the class down,
 * or declare a narrow port next to each caller - was measured against this tree and could not be
 * finished for most of the carriers: the classes with an XMPP island importer (`Compatibility`,
 * `AvatarService`, `NotificationService`, `BobTransfer`, `EasyOnboardingInvite`, the backup
 * workers) cannot be claimed without growing `island-imports-ours`, and a `:ui`-declared port may
 * not name an island type without growing `ui-reaches-island`. The thirteen classes that *could*
 * move down did (see the commit): the pure view helpers are `uk.xa0.tulkki.ui.*` now and do not
 * appear here at all. What is left is genuinely the root's, and one interface keeps it one seam,
 * one install line and one place to see the whole boundary.
 *
 * **The holder fails loudly.** [installed] throws an [IllegalStateException] naming the port and the
 * install point rather than returning a default: a silently-absent host would make a backup that
 * never starts, a permission it never asked for or a sign-up screen that never opens look like
 * ordinary behaviour. The composition root installs the one implementation from
 * `TulkkiApplication.onCreate`, before any activity exists - the same place and the same shape as
 * `ViewPorts` and `EngineHost`.
 *
 * Every method is a forward. Nothing here decides anything: the bodies live in the classes that
 * always had them, and this interface only says who may call them.
 *
 * Ported from Java by `ui3940`, completed by `uihost`. The Java declared no nullability annotations,
 * so every `?` below is read off the one implementor, `uk.xa0.tulkki.app.UiAppHost`, and the nested
 * interfaces off `AvatarService` and `CallIntegration`; that is what keeps the Kotlin
 * interface and its Kotlin implementors in step. `ChannelSearchHook.onChannelSearchResultsFound`
 * needs no `@JvmSuppressWildcards`: `Room` is final, so Kotlin already emits the invariant
 * `List<Room>` (measured - the signature is invariant with and without it, and
 * `ChannelDiscoveryActivity` overrides either way).
 *
 * `recreateIncomingCallChannel` takes its ringtone as `Uri?` on both sides. Its one caller,
 * `NotificationsSettingsFragment`, passes `PickRingtone.noneToNull`'s answer, which is `null` when
 * the owner picks Silent; the Java's parameter was a platform type, and both the implementor and
 * `NotificationService` handed the value straight to `NotificationChannel.setSound(Uri, ...)`,
 * whose own parameter is `@Nullable`. So the value was never dereferenced, null included, and a
 * non-null declaration on either side would narrow a caller the Java accepted or invent a throw the
 * Java never made.
 */
interface UiHost {

    // -- the platform's own answers (uk.xa0.tulkki.app.utils.Compatibility) --------------------------------

    /** Whether the app may write external storage on this platform version. */
    fun hasStoragePermission(context: Context): Boolean

    /** Whether the device has a camera at all. */
    fun hasFeatureCamera(context: Context): Boolean

    /** The background-restriction state, with the platform bug worked around. */
    fun restrictBackgroundStatus(connectivityManager: ConnectivityManager): Int

    /** The activity-options bundle a PGP intent sender needs, or `null` below Android 14. */
    fun pgpStartIntentSenderOptions(): Bundle?

    /** Whether this platform runs Android 8 or newer. */
    fun runsTwentySix(): Boolean

    /** Whether this platform runs Android 7 or newer. */
    fun runsTwentyFour(): Boolean

    // -- the contact-list integration (uk.xa0.tulkki.app.services.ContactListSyncService) -------------------

    /** `false`, always: this fork has one flavour and is not the Quicksy one. */
    fun quicksy(): Boolean

    /** `false`, always: the play-store distribution is not this flavour. */
    fun playStoreFlavor(): Boolean

    /** Whether this install declared `READ_CONTACTS`, asked once per process. */
    fun contactListIntegration(context: Context): Boolean

    // -- phone numbers (uk.xa0.tulkki.app.utils.PhoneNumberUtilWrapper) -----------------------------------

    /** A JID's local part as an international phone number, or the local part when it cannot be read. */
    fun formattedPhoneNumber(context: Context, jid: CharSequence): String?

    /** Normalise a typed number, preferring the device's own country. */
    @Throws(NumberParseException::class)
    fun normalizePhoneNumber(context: Context, input: String): String

    /** Normalise a typed number, optionally preferring the network's country. */
    @Throws(NumberParseException::class)
    fun normalizePhoneNumber(context: Context, input: String, preferNetwork: Boolean): String

    // -- the theme preferences (uk.xa0.tulkki.app.TulkkiApplication) --------------------------------------

    /** The night mode the stored theme asks for, running the two legacy-theme migrations. */
    fun desiredNightMode(context: Context): Int

    /** Whether the owner asked for Material You colours. */
    fun dynamicColorsDesired(context: Context): Boolean

    /** Whether the owner built a custom colour set. */
    fun customColorsDesired(context: Context): Boolean

    // -- the owner's profile picture (uk.xa0.tulkki.app.utils.PhoneHelper) --------------------------------

    /** The contacts-provider profile picture URI, or `null` when there is none to show. */
    fun profilePictureUri(context: Context): Uri?

    // -- crash reports (uk.xa0.tulkki.app.utils.ExceptionHelper) ------------------------------------------

    /** Offer to send a stored crash report; `true` when a report was found and offered. */
    fun checkForCrash(activity: Activity?): Boolean

    // -- signing up (uk.xa0.tulkki.app.utils.SignupUtils) -------------------------------------------------

    /** Whether this build registers accounts against a token registry. */
    fun tokenRegistrySupported(): Boolean

    /** The screen an account is created on. */
    fun signUpIntent(activity: Activity, toServerChooser: Boolean): Intent

    /** The magic-create screen for one preset JID. */
    fun tokenRegistrationIntent(activity: Activity, jid: String, preAuth: String?): Intent

    /** Where an unfinished onboarding is sent next. */
    fun redirectionIntent(activity: Activity): Intent

    /** Finish an onboarding that the server completed while the app was open. */
    fun finishOnboarding(activity: Activity, onboardingAccount: Account, newAccount: Account)

    // -- provisioning (uk.xa0.tulkki.app.utils.ProvisioningUtils) -----------------------------------------

    /** Apply a provisioning document handed to the app through its own URI scheme. */
    fun provision(activity: Activity, json: String)

    // -- the provider list (uk.xa0.tulkki.app.extras.ProviderService / ProviderCatalog) -------------------

    /** The registration domains the app knows about. */
    fun providers(): List<String>

    /** Re-read the external provider list; `true` when it was refreshed. */
    fun refreshProviders(): Boolean

    /** A domain to create an account on, when the owner asks for one; null when none is known. */
    fun randomProviderDomain(): String?

    // -- screens the root owns (uk.xa0.tulkki.ui.media.MediaViewerActivity) -----------------------------

    /** Open the internal media viewer on one file, with the conversation it belongs to. */
    fun openMediaViewer(
        context: Context,
        type: String,
        uri: Uri,
        conversationUuid: String?,
        messageUuid: String?,
        accountUuid: String?,
        jid: String?
    )

    // -- search (uk.xa0.tulkki.app.services.MessageSearchTask) --------------------------------------------

    /** Stop whatever message search is running. */
    fun cancelRunningSearches()

    // -- translation (uk.xa0.tulkki.app.TranslationHooks, TranslationWork, MamLanguageSampler) ------------

    /** How many messages the translation queue still owes. */
    fun pendingTranslationCount(context: Context): Int

    /** Translate one tapped message now; `listener` hears the refusal, or `null`. */
    fun requestTranslation(
        service: Context?,
        message: Message?,
        listener: Consumer<DisplayedBody.Cover>
    )

    /** Wake the translation queue. */
    fun kickTranslation(context: Context)

    /**
     * Stop the translation queue: cancel every WorkManager record this app holds, so nothing is left
     * scheduled to wake the process on its own. The mirror of [kickTranslation], and what the
     * settings screen calls when a language write switches the interpreter off.
     */
    fun cancelTranslation(context: Context)

    /** Read one bounded language sample for a conversation; `onLanguageLanded` runs after. */
    fun considerLanguageSample(
        service: Context?,
        conversation: Conversation?,
        onLanguageLanded: Runnable?
    )

    // -- avatars (uk.xa0.tulkki.app.services.AvatarService) -----------------------------------------------

    /** The letter tile for a bare JID, used where no avatar cache is available. */
    fun textAvatar(bareJid: CharSequence, size: Int): Drawable

    // -- video compression (uk.xa0.tulkki.app.services.AttachFileToConversationRunnable) ------------------

    /** The compression the owner configured for outgoing video. */
    fun videoCompression(context: Context): String?

    // -- barcodes (uk.xa0.tulkki.app.services.BarcodeProvider) --------------------------------------------

    /** A square QR bitmap for `input`. */
    fun barcode(input: String, size: Int): Bitmap?

    /** A QR bitmap with explicit colours. */
    fun barcode(input: String, size: Int, black: Int, white: Int): Bitmap?

    /** The content URI that draws one account's XMPP address as a barcode. */
    fun barcodeUri(context: Context, account: Account): Uri

    // -- notifications (uk.xa0.tulkki.app.services.NotificationService) -----------------------------------

    /** The id of the channel ordinary messages arrive on. */
    fun messagesNotificationChannel(): String

    /** Create one conversation's own channel, for a long-lived shortcut. */
    fun createConversationChannel(context: Context, shortcut: ShortcutInfoCompat)

    /** The pattern that bolds a nick inside a group message. */
    fun nickHighlightPattern(nick: String): Pattern

    /** Rebuild the incoming-call channel for a new ringtone, or for none (a silent channel). */
    fun recreateIncomingCallChannel(context: Context, ringtone: Uri?)

    /** The incoming-call channel in force, or absent when the platform has none. */
    fun currentIncomingCallChannel(context: Context): Optional<NotificationChannel>

    // -- backup (uk.xa0.tulkki.app.worker.ExportBackupWorker / ImportBackupWorker / RecurringBackup) ------

    /** Start a one-off export and enqueue it under `uniqueName`. */
    fun startOneOffExport(context: Context, uniqueName: String)

    /** Enqueue one import and hand back the id the screen monitors. */
    fun startImport(context: Context, password: String?, uri: Uri, includeOmemo: Boolean): UUID

    /** The tag every running import carries. */
    fun importTag(): String

    /** Why an import failed, from the worker's own reason string. */
    fun importFailure(value: String?): BackupFailure

    /** The preference key, unique work name and input flag the recurring backup is stored under. */
    fun recurringBackupName(): String

    /** (Re)schedule the recurring backup at `intervalSeconds`. */
    fun scheduleRecurringBackup(context: Context, intervalSeconds: Long)

    /** Cancel the recurring backup. */
    fun cancelRecurringBackup(context: Context)

    // -- calls (uk.xa0.tulkki.app.services.CallIntegrationConnectionService, CallIntegration) -------------

    /** Place a call through the platform's telecom stack, if this device can. */
    fun placeCall(service: Context, account: Account, with: CharSequence, action: String)

    /** Whether the platform will let the app place self-managed calls at all. */
    fun selfManagedCallAvailable(context: Context): Boolean

    // -- channel discovery (uk.xa0.tulkki.app.services.ChannelDiscoveryService) ---------------------------

    /**
     * Run one channel search. `mucServices` maps a service's JID, as text, to the account it
     * belongs to; it is text rather than a `Jid` because a port declared in this module may not
     * name an island type, and the implementation rebuilds the island value.
     */
    fun discoverChannels(
        service: Context,
        query: String?,
        method: ChannelMethod,
        mucServices: Map<String, Account>?,
        hook: ChannelSearchHook
    )

    // -- BOB transfers (uk.xa0.tulkki.app.extras.BobTransfer) ---------------------------------------------

    /** Fetch a cid-addressed object and offer it to the conversation. */
    @Throws(NoSuchAlgorithmException::class, URISyntaxException::class)
    fun startBobTransfer(service: Context, cid: Cid, account: Account, counterpart: CharSequence)

    /** The transferable that fetches the object one message refers to. */
    @Throws(URISyntaxException::class)
    fun bobTransferForMessage(service: Context, message: Message): Transferable

    // -- unified push (uk.xa0.tulkki.app.receiver.UnifiedPushDistributor) ---------------------------------

    /** The preference one account's push endpoint is stored under. */
    fun unifiedPushAccountPreference(): String

    /** The preference the push server is stored under. */
    fun unifiedPushServerPreference(): String

    /** Every preference the push settings screen owns. */
    fun unifiedPushPreferences(): List<String>

    // -- shortcuts (uk.xa0.tulkki.app.services.ShortcutService) -------------------------------------------

    /** The character a shortcut id joins its parts with. */
    fun shortcutIdSeparator(): Char

    // ---------------------------------------------------------------------------------------------
    // Types that cross the boundary as values rather than as calls.
    // ---------------------------------------------------------------------------------------------

    /** The audio half of a running call, as the in-call screen sees it. */
    interface CallAudio {

        /** The route in use. */
        fun getSelectedAudioDevice(): AudioDevice

        /** Every route this device offers for the call. */
        fun getAudioDevices(): Set<AudioDevice>

        /** Switch the call to `device`. */
        fun setAudioDevice(device: AudioDevice)
    }

    /** The avatar operations the screens perform; implemented by `AvatarService`. */
    interface AvatarSource {

        /** The avatar of `avatarable` at `size`, or from the cache when asked. */
        fun get(avatarable: Avatarable, size: Int, cachedOnly: Boolean): Drawable?

        /** An account's avatar at `size`. */
        fun get(account: Account, size: Int): Drawable?

        /** Drop a conversation's cached avatar. */
        fun clear(conversation: Conversation)

        /** Drop a contact's cached avatar. */
        fun clear(contact: Contact)

        /** Drop one group member's cached avatar. */
        fun clear(user: MucOptions.User)
    }

    /** The two search scopes the channel screen offers. */
    enum class ChannelMethod {
        LOCAL_SERVER,
        JABBER_NETWORK
    }

    /** Where a channel search's results land. */
    interface ChannelSearchHook {

        /** The rooms this search found. */
        fun onChannelSearchResultsFound(results: List<Room>)
    }

    /** Why an import failed, as the import screen needs to tell it apart. */
    enum class BackupFailure {
        DECRYPTION_FAILED,
        ACCOUNT_ALREADY_EXISTS,
        OTHER
    }

    /** The composition root's one line: install the host, at process start, before any screen. */
    companion object {

        @JvmStatic
        fun install(host: UiHost) {
            Holder.HOST = host
        }

        /** @throws IllegalStateException when nothing was installed - a build fault, not a device state */
        @JvmStatic
        fun installed(): UiHost {
            val host = Holder.HOST
            if (host == null) {
                throw IllegalStateException(
                    "uk.xa0.tulkki.ui.host.UiHost was never installed: uk.xa0.tulkki.app.TulkkiApplication.onCreate" +
                        " must call UiHost.install, and it has not")
            }
            return host
        }
    }

    /** One slot: an Android process has exactly one Application, installed once. */
    class Holder private constructor() {

        companion object {
            @JvmField
            @Volatile
            var HOST: UiHost? = null
        }
    }
}
