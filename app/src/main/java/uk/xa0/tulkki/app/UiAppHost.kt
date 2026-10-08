package uk.xa0.tulkki.app

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
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.google.common.base.Optional
import io.ipfs.cid.Cid
import java.util.UUID
import java.util.function.Consumer
import java.util.regex.Pattern
import uk.xa0.tulkki.app.extras.BobTransfer
import uk.xa0.tulkki.app.extras.FinishOnboarding
import uk.xa0.tulkki.ui.media.MediaViewerActivity
import uk.xa0.tulkki.app.extras.ProviderCatalog
import uk.xa0.tulkki.app.extras.ProviderService
import uk.xa0.tulkki.app.receiver.UnifiedPushDistributor
import uk.xa0.tulkki.app.services.AbstractContactListSyncService
import uk.xa0.tulkki.app.services.AttachFileToConversationRunnable
import uk.xa0.tulkki.app.services.AvatarService
import uk.xa0.tulkki.app.services.BarcodeProvider
import uk.xa0.tulkki.app.services.CallIntegration
import uk.xa0.tulkki.app.services.CallIntegrationConnectionService
import uk.xa0.tulkki.app.services.ChannelDiscoveryService
import uk.xa0.tulkki.app.services.MessageSearchTask
import uk.xa0.tulkki.app.services.NotificationService
import uk.xa0.tulkki.app.services.ShortcutService
import uk.xa0.tulkki.app.utils.Compatibility
import uk.xa0.tulkki.app.utils.ExceptionHelper
import uk.xa0.tulkki.app.utils.PhoneHelper
import uk.xa0.tulkki.app.utils.PhoneNumberUtilWrapper
import uk.xa0.tulkki.app.utils.ProvisioningUtils
import uk.xa0.tulkki.app.utils.SignupUtils
import uk.xa0.tulkki.app.worker.ExportBackupWorker
import uk.xa0.tulkki.app.worker.ImportBackupWorker
import uk.xa0.tulkki.app.worker.RecurringBackup
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.libs.Transferable
import uk.xa0.tulkki.translation.DisplayedBody
import uk.xa0.tulkki.ui.ConversationListActivity
import uk.xa0.tulkki.ui.RtpSessionActivity
import uk.xa0.tulkki.ui.XmppActivity
import uk.xa0.tulkki.ui.host.UiHost
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.services.XmppConnectionService

/**
 * The composition root's answer to [UiHost]: one forward per method, to the class that
 * already owned the behaviour.
 *
 * <p>3.7 pair 3. Nothing here is new logic - every body is the call the `:ui` site used to
 * make directly, and several are one-liners. What changed is only who is allowed to write the class
 * name: `:ui` may not name `:app`, and `:app` may name both, so the composition root is where the
 * two ends meet. It is the same division of labour as
 * `uk.xa0.tulkki.app.XmppTulkkiHost` and `uk.xa0.tulkki.ui.view.UiViewPorts`.
 *
 * <p>Three methods are not pure forwards and each says so at its own body: [refreshProviders]
 * turns the `AsyncTask`'s checked exceptions into a boolean, and the two backup enqueues build the
 * requests the settings screens used to spell out (they were the only reason a `:ui` file named a
 * worker class).
 *
 * <p>The class is stateless, so one instance is the whole of it: `TulkkiApplication` installs
 * it once at process start.
 *
 * <p>Kotlin notes from the port. It is an `object`, which is the Java's hand-written singleton to
 * the letter: the JVM still sees `public static final UiAppHost INSTANCE`, the spelling
 * `TulkkiApplication` already reads, and the Java's private constructor is the rule that an
 * `object` has none. The three contact-list predicates name `AbstractContactListSyncService` here
 * rather than `ContactListSyncService`: Java inherited those statics through the subclass, and a
 * Kotlin companion member is not inherited, so the declaring class is the only name that resolves.
 * `Boolean.TRUE.equals` is `== true`, because Kotlin's `Boolean` has no `TRUE` field. The checked
 * exceptions the interface declares are not repeated: Kotlin has none, and the JVM does not need
 * them on an override. Every parameter the Java tolerated as nullable is still nullable, so no
 * call the Java accepted becomes a `NullPointerException` here.
 */
object UiAppHost : UiHost {

    // -- the platform's own answers ----------------------------------------------------------------

    override fun hasStoragePermission(context: Context): Boolean =
            Compatibility.hasStoragePermission(context)

    override fun hasFeatureCamera(context: Context): Boolean =
            Compatibility.hasFeatureCamera(context)

    override fun restrictBackgroundStatus(connectivityManager: ConnectivityManager): Int =
            Compatibility.getRestrictBackgroundStatus(connectivityManager)

    override fun pgpStartIntentSenderOptions(): Bundle? =
            Compatibility.pgpStartIntentSenderOptions()

    override fun runsTwentySix(): Boolean = Compatibility.runsTwentySix()

    override fun runsTwentyFour(): Boolean = Compatibility.runsTwentyFour()

    // -- the contact-list integration ---------------------------------------------------------------

    override fun quicksy(): Boolean = AbstractContactListSyncService.isQuicksy()

    override fun playStoreFlavor(): Boolean = AbstractContactListSyncService.isPlayStoreFlavor()

    override fun contactListIntegration(context: Context): Boolean =
            AbstractContactListSyncService.isContactListIntegration(context)

    // -- phone numbers ------------------------------------------------------------------------------

    override fun formattedPhoneNumber(context: Context, jid: CharSequence): String? =
            PhoneNumberUtilWrapper.toFormattedPhoneNumber(context, Jid.of(jid.toString()))

    override fun normalizePhoneNumber(context: Context, input: String): String =
            PhoneNumberUtilWrapper.normalize(context, input)

    override fun normalizePhoneNumber(
            context: Context,
            input: String,
            preferNetwork: Boolean): String =
            PhoneNumberUtilWrapper.normalize(context, input, preferNetwork)

    // -- the theme preferences ----------------------------------------------------------------------

    override fun desiredNightMode(context: Context): Int =
            TulkkiApplication.getDesiredNightMode(context)

    override fun dynamicColorsDesired(context: Context): Boolean =
            TulkkiApplication.isDynamicColorsDesired(context)

    override fun customColorsDesired(context: Context): Boolean =
            TulkkiApplication.isCustomColorsDesired(context)

    // -- the owner's profile picture -----------------------------------------------------------------

    override fun profilePictureUri(context: Context): Uri? = PhoneHelper.getProfilePictureUri(context)

    // -- crash reports -------------------------------------------------------------------------------

    override fun checkForCrash(activity: Activity?): Boolean =
            ExceptionHelper.checkForCrash(activity as XmppActivity?)

    // -- signing up ----------------------------------------------------------------------------------

    override fun tokenRegistrySupported(): Boolean = SignupUtils.isSupportTokenRegistry()

    override fun signUpIntent(activity: Activity, toServerChooser: Boolean): Intent =
            SignupUtils.getSignUpIntent(activity, toServerChooser)

    override fun tokenRegistrationIntent(
            activity: Activity,
            jid: String,
            preAuth: String?): Intent =
            SignupUtils.getTokenRegistrationIntent(activity, Jid.of(jid), preAuth)

    override fun redirectionIntent(activity: Activity): Intent =
            SignupUtils.getRedirectionIntent(activity as ConversationListActivity)

    override fun finishOnboarding(
            activity: Activity,
            onboardingAccount: Account,
            newAccount: Account) {
        val xmppActivity = activity as XmppActivity
        FinishOnboarding.finish(
                xmppActivity.xmppConnectionService, xmppActivity, onboardingAccount, newAccount)
    }

    // -- provisioning --------------------------------------------------------------------------------

    override fun provision(activity: Activity, json: String) {
        ProvisioningUtils.provision(activity, json)
    }

    // -- the provider list ---------------------------------------------------------------------------

    override fun providers(): List<String> = ProviderService.getProviders()

    override fun refreshProviders(): Boolean {
        // The AsyncTask's own two failure modes - a refused execution and a throwing body - were a
        // `catch (Throwable)` at the one call site; they are a `false` here, which is what that site
        // did with them.
        return try {
            ProviderService().execute().get() == true
        } catch (e: Exception) {
            false
        }
    }

    override fun randomProviderDomain(): String? = ProviderCatalog.DOMAIN.getRandomServer()

    // -- screens the root owns -----------------------------------------------------------------------

    override fun openMediaViewer(
            context: Context,
            type: String,
            uri: Uri,
            conversationUuid: String?,
            messageUuid: String?,
            accountUuid: String?,
            jid: String?) {
        val intent = Intent(context, MediaViewerActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.putExtra(type, uri)
        if (conversationUuid != null) intent.putExtra("conversation_uuid", conversationUuid)
        if (messageUuid != null) intent.putExtra("message_uuid", messageUuid)
        if (accountUuid != null) intent.putExtra("account", accountUuid)
        if (jid != null) intent.putExtra("jid", jid)
        context.startActivity(intent)
    }

    // -- search --------------------------------------------------------------------------------------

    override fun cancelRunningSearches() {
        MessageSearchTask.cancelRunningTasks()
    }

    // -- translation ---------------------------------------------------------------------------------

    override fun pendingTranslationCount(context: Context): Int =
            TranslationHooks.pendingCount(context)

    override fun requestTranslation(
            service: Context?,
            message: Message?,
            listener: Consumer<DisplayedBody.Cover>) {
        // Kotlin sees `Consumer`'s type argument as the non-null `Cover`, so `accept(it)` cannot take
        // the refusal `TranslationHooks` posts as `null` - and it does, for every attempted tap and
        // for the interpreter-off answer. `accept(null)` is what the Java did, and the cast erases to
        // the same `Consumer`, so it is a statement about Java's nullability rather than a check.
        @Suppress("UNCHECKED_CAST")
        val nullable = listener as Consumer<DisplayedBody.Cover?>
        TranslationHooks.requestOne(
                service as XmppConnectionService?,
                message,
                TranslationHooks.Listener { nullable.accept(it) })
    }

    override fun kickTranslation(context: Context) {
        TranslationWork.kick(context)
    }

    override fun cancelTranslation(context: Context) {
        TranslationWork.cancel(context)
    }

    override fun considerLanguageSample(
            service: Context?,
            conversation: Conversation?,
            onLanguageLanded: Runnable?) {
        MamLanguageSampler.consider(
                service as XmppConnectionService?, conversation, onLanguageLanded)
    }

    // -- avatars -------------------------------------------------------------------------------------

    override fun textAvatar(bareJid: CharSequence, size: Int): Drawable =
            AvatarService.get(Jid.of(bareJid.toString()), size)

    // -- video compression ---------------------------------------------------------------------------

    override fun videoCompression(context: Context): String? =
            AttachFileToConversationRunnable.getVideoCompression(context)

    // -- barcodes ------------------------------------------------------------------------------------

    override fun barcode(input: String, size: Int): Bitmap? =
            BarcodeProvider.create2dBarcodeBitmap(input, size)

    override fun barcode(input: String, size: Int, black: Int, white: Int): Bitmap? =
            BarcodeProvider.create2dBarcodeBitmap(input, size, black, white)

    override fun barcodeUri(context: Context, account: Account): Uri =
            BarcodeProvider.getUriForAccount(context, account)

    // -- notifications -------------------------------------------------------------------------------

    override fun messagesNotificationChannel(): String =
            NotificationService.MESSAGES_NOTIFICATION_CHANNEL

    override fun createConversationChannel(context: Context, shortcut: ShortcutInfoCompat) {
        NotificationService.createConversationChannel(context, shortcut)
    }

    override fun nickHighlightPattern(nick: String): Pattern =
            NotificationService.generateNickHighlightPattern(nick)

    override fun recreateIncomingCallChannel(context: Context, ringtone: Uri?) {
        NotificationService.recreateIncomingCallChannel(context, ringtone)
    }

    override fun currentIncomingCallChannel(context: Context): Optional<NotificationChannel> =
            NotificationService.getCurrentIncomingCallChannel(context)

    // -- backup --------------------------------------------------------------------------------------

    override fun startOneOffExport(context: Context, uniqueName: String) {
        val request =
                OneTimeWorkRequest.Builder(ExportBackupWorker::class.java)
                        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        .build()
        WorkManager.getInstance(context)
                .enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, request)
    }

    override fun startImport(
            context: Context,
            password: String?,
            uri: Uri,
            includeOmemo: Boolean): UUID {
        val request =
                OneTimeWorkRequest.Builder(ImportBackupWorker::class.java)
                        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        .setInputData(ImportBackupWorker.data(password, uri, includeOmemo))
                        .addTag(ImportBackupWorker.TAG_IMPORT_BACKUP)
                        .build()
        WorkManager.getInstance(context).enqueue(request)
        return request.id
    }

    override fun importTag(): String = ImportBackupWorker.TAG_IMPORT_BACKUP

    override fun importFailure(value: String?): UiHost.BackupFailure =
            when (ImportBackupWorker.Reason.valueOfOrGeneric(value)) {
                ImportBackupWorker.Reason.DECRYPTION_FAILED -> UiHost.BackupFailure.DECRYPTION_FAILED
                ImportBackupWorker.Reason.ACCOUNT_ALREADY_EXISTS ->
                        UiHost.BackupFailure.ACCOUNT_ALREADY_EXISTS
                else -> UiHost.BackupFailure.OTHER
            }

    override fun recurringBackupName(): String = RecurringBackup.NAME

    override fun scheduleRecurringBackup(context: Context, intervalSeconds: Long) {
        WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                        RecurringBackup.NAME,
                        ExistingPeriodicWorkPolicy.UPDATE,
                        RecurringBackup.request(intervalSeconds))
    }

    override fun cancelRecurringBackup(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(RecurringBackup.NAME)
    }

    // -- calls ---------------------------------------------------------------------------------------

    override fun placeCall(
            service: Context,
            account: Account,
            with: CharSequence,
            action: String) {
        CallIntegrationConnectionService.placeCall(
                service as XmppConnectionService,
                account,
                Jid.of(with.toString()),
                RtpSessionActivity.actionToMedia(action))
    }

    override fun selfManagedCallAvailable(context: Context): Boolean =
            CallIntegration.selfManagedAvailable(context)

    // -- easy onboarding ------------------------------------------------------------------------------

    // The invite screen is gone: there is no Tulkki service or web page to invite anyone to, so the
    // root no longer answers for one. `XmppConnectionService`'s own invite request stays where it
    // is, in the island, with nothing in the app calling it.

    // -- BOB transfers --------------------------------------------------------------------------------

    override fun startBobTransfer(
            service: Context,
            cid: Cid,
            account: Account,
            counterpart: CharSequence) {
        BobTransfer(
                        BobTransfer.uri(cid),
                        account,
                        Jid.of(counterpart.toString()),
                        service as XmppConnectionService)
                .start()
    }

    override fun bobTransferForMessage(service: Context, message: Message): Transferable =
            BobTransfer.ForMessage(message, service as XmppConnectionService)

    // -- channel discovery -----------------------------------------------------------------------------

    override fun discoverChannels(
            service: Context,
            query: String?,
            method: UiHost.ChannelMethod,
            mucServices: Map<String, Account>?,
            hook: UiHost.ChannelSearchHook) {
        val services: MutableMap<Jid, Account>?
        if (mucServices == null) {
            services = null
        } else {
            services = HashMap()
            for ((key, value) in mucServices.entries) {
                services[Jid.of(key)] = value
            }
        }
        // Tulkki: 3.7 pair 9, part 15 - the island's port carries only the two lifecycle answers
        // now; the search is `ChannelDiscoveryService`'s own method and this root, which built it, is
        // what names it. The results arrive as the model's `List<Room>` on both sides, so the cast the
        // port's `RoomRef` continuation used to need is gone.
        val discovery =
                (service as XmppConnectionService).channelDiscovery() as ChannelDiscoveryService
        discovery.discover(
                query,
                method == UiHost.ChannelMethod.LOCAL_SERVER,
                services,
                { rooms -> hook.onChannelSearchResultsFound(rooms) })
    }

    // -- unified push ---------------------------------------------------------------------------------

    override fun unifiedPushAccountPreference(): String =
            UnifiedPushDistributor.PREFERENCE_ACCOUNT

    override fun unifiedPushServerPreference(): String =
            UnifiedPushDistributor.PREFERENCE_PUSH_SERVER

    override fun unifiedPushPreferences(): List<String> = UnifiedPushDistributor.PREFERENCES

    // -- shortcuts ------------------------------------------------------------------------------------

    override fun shortcutIdSeparator(): Char = ShortcutService.ID_SEPARATOR
}
