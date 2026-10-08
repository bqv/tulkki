package uk.xa0.tulkki.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableSet
import com.google.common.collect.Iterables
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import java.io.IOException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import uk.xa0.tulkki.data.utils.BackupFile
import uk.xa0.tulkki.ui.chrome.ChromeMenuItem
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import uk.xa0.tulkki.ui.host.UiHost
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.utils.BackupFileHeader

/**
 * The restore-backup screen: the backup files this device holds, the file picker that opens one, and
 * the password dialog that starts the import.
 *
 * <p>**Both layouts and the menu are gone.** `activity_import_backup.xml` and
 * `dialog_enter_password.xml` are deleted, and so is `menu/import_backup.xml`: its one live item
 * (`action_open_backup_file`, "Open backup") is the chrome's overflow entry now. The bar is the
 * shared chrome ([uk.xa0.tulkki.ui.chrome.TulkkiChrome]), the body is [ImportBackupScreen], and the
 * password dialog is [ImportBackupPasswordDialog] launched from [showEnterPasswordDialog] - the same
 * host method, the same three side effects: it remembers the URI, reads the file's account and
 * starts the import on Restore. `setSupportActionBar`, `setTitle`,
 * `Activities.setStatusAndNavigationBarColors` and `configureActionBar` went with the bar; the
 * chrome owns the title, the up arrow, the system-bar colours and, through the `menu` argument, the
 * item `invalidateOptionsMenu` used to hide while an import ran.
 *
 * <p>**The behaviour is the old Activity's, field for field.** The permission request, the
 * `ACTION_VIEW` intake, `BackupFile.readAsync`/`listAsync`, the quicksy check, the three failure
 * branches, the work-request monitor and the `onSaveInstanceState` pair are unchanged; the
 * `RecyclerView` and its adapter are the [ImportBackupState.rows] the screen draws, and each
 * `Snackbar.make(binding.coordinator, …)` is one [showMessage]. The seven strings those snackbars
 * showed are the same resources.
 *
 * <p>The list's row layout, `item_account.xml`, has since been deleted with its last inflater,
 * `BackupFileAdapter`, which this screen had already left unreferenced: this screen draws its own row
 * ([ImportBackupRow]), built by [rowOf] from the bindings that adapter made.
 */
class ImportBackupActivity : ActionBarActivity() {

    /** Everything the screen draws. The Activity owns it, the composition reads it. */
    private var state by mutableStateOf(ImportBackupState())

    /** The password dialog currently open, or `null`. */
    private var dialog by mutableStateOf<ImportBackupPasswordState?>(null)

    /** The file the open dialog belongs to; the old `currentRestoreDialog` is its URI. */
    private var dialogFile: BackupFile? = null

    /** Whether cancelling the open dialog also leaves the screen - the old `finishOnCancel`. */
    private var dialogFinishOnCancel = false

    /** The listed backup files, in the screen's own order. */
    private var backupFiles: List<BackupFile> = emptyList()

    /** The avatars the host has resolved, by bare JID. */
    private var avatars by mutableStateOf<Map<String, Drawable>>(emptyMap())

    /** Names one transient message; the screen never sees two messages as the same one. */
    private var messageToken = 0

    private var inProgressImport: LiveData<Boolean>? = null
    private var currentRestoreDialog: Uri? = null
    private var currentWorkRequest: UUID? = null

    private val requestPermissions: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { results ->
            if (results.containsValue(true)) {
                loadBackupFiles()
            }
        }

    private val openBackup: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            openBackupFileFromUri(uri ?: throw NullPointerException(), false)
        }

    protected override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The chrome draws its bar behind the system bars and hands the content the space they
        // leave, so the window must not inset itself for them first - on every API level, which is
        // what enableEdgeToEdge settles. See TulkkiChrome for the whole pattern.
        enableEdgeToEdge()

        setTulkkiContent(darkTheme = darkTheme()) {
            TulkkiChrome(
                // `setLoadingState`'s `setTitle` and its `invalidateOptionsMenu` are state now: the
                // title flips with the import, and the bar's arrow and the item come and go with it.
                title =
                    stringResource(
                        if (state.loading) R.string.restoring_backup else R.string.restore_backup
                    ),
                onUp = if (state.loading) null else ({ finish() }),
                menu =
                    if (state.loading) {
                        emptyList()
                    } else {
                        listOf(
                            ChromeMenuItem(stringResource(R.string.open_backup)) {
                                openBackup.launch("*/*")
                            }
                        )
                    },
            ) {
                ImportBackupScreen(
                    state = state,
                    onOpen = { row -> onBackupFileClicked(row) },
                    onMessageShown = { message ->
                        if (state.message === message) {
                            state = state.copy(message = null)
                        }
                    },
                    passwordDialog = dialog,
                    onRestore = { password, includeKeys -> restore(password, includeKeys) },
                    onCancelPassword = { cancelPassword() },
                    avatar = { jid -> avatars[jid] },
                )
            }
        }

        val workManager = WorkManager.getInstance(this)
        val imports = workManager.getWorkInfosByTagLiveData(UiHost.installed().importTag())
        // The Java used `Transformations.map`; the checker's classpath has no
        // `androidx.lifecycle.Transformations`, so the same MediatorLiveData is built here directly.
        val mapped = MediatorLiveData<Boolean>()
        mapped.addSource(imports) { infos ->
            mapped.value = Iterables.any(infos) { i -> !i.state.isFinished }
        }
        this.inProgressImport = mapped

        this.inProgressImport?.observe(this) { inProgress ->
            val loading = inProgress == true
            Log.d(Config.LOGTAG, "setLoadingState(" + loading + ")")
            state = state.copy(loading = loading)
        }

        if (savedInstanceState != null) {
            val currentWorkRequest = savedInstanceState.getString("current-work-request")
            if (currentWorkRequest != null) {
                this.currentWorkRequest = UUID.fromString(currentWorkRequest)
            }
            val currentRestoreDialog = savedInstanceState.getString("current-restore-dialog")
            if (currentRestoreDialog != null) {
                this.currentRestoreDialog = Uri.parse(currentRestoreDialog)
            }
        }
        monitorWorkRequest(this.currentWorkRequest)

        val currentRestoreDialog = this.currentRestoreDialog
        if (currentRestoreDialog != null) {
            openBackupFileFromUri(currentRestoreDialog, false)
        }
    }

    public override fun onSaveInstanceState(bundle: Bundle) {
        val currentWorkRequest = this.currentWorkRequest
        if (currentWorkRequest != null) {
            bundle.putString("current-work-request", currentWorkRequest.toString())
        }
        val currentRestoreDialog = this.currentRestoreDialog
        if (currentRestoreDialog != null) {
            bundle.putString("current-restore-dialog", currentRestoreDialog.toString())
        }
        super.onSaveInstanceState(bundle)
    }

    public override fun onStart() {
        super.onStart()

        val intent = getIntent()
        val action = intent?.getAction()
        val data = intent?.getData()
        if (Intent.ACTION_VIEW == action && data != null) {
            openBackupFileFromUri(data, true)
            setIntent(Intent(Intent.ACTION_MAIN))
            return
        }

        val desiredPermission: List<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            desiredPermission = ImmutableList.of(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
        } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.TIRAMISU) {
            desiredPermission = ImmutableList.of(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
            )
        } else {
            desiredPermission = ImmutableList.of(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val declaredPermission = getDeclaredPermission()
        if (declaredPermission.containsAll(desiredPermission)) {
            requestPermissions.launch(desiredPermission.toTypedArray())
        } else {
            Log.d(Config.LOGTAG, "Manifest is lacking some desired permission. not requesting")
        }
    }

    private fun getDeclaredPermission(): Set<String> {
        val permissions: Array<String>?
        try {
            permissions =
                packageManager
                    .getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
                    .requestedPermissions
        } catch (e: PackageManager.NameNotFoundException) {
            return Collections.emptySet()
        }
        return ImmutableSet.copyOf(permissions ?: throw NullPointerException())
    }

    private fun loadBackupFiles() {
        val future = BackupFile.listAsync(applicationContext)
        Futures.addCallback(
            future,
            object : FutureCallback<List<BackupFile>> {
                override fun onSuccess(files: List<BackupFile>) {
                    runOnUiThread { applyBackupFiles(files) }
                }

                override fun onFailure(t: Throwable) {}
            },
            ContextCompat.getMainExecutor(application),
        )
    }

    /**
     * `BackupFileAdapter.setFiles`, and the `notifyDataSetChanged` with it: the rows are rebuilt from
     * the files and the avatars are fetched for the addresses that are new.
     */
    private fun applyBackupFiles(files: List<BackupFile>) {
        this.backupFiles = files
        state = state.copy(rows = files.map { rowOf(it) })
        loadAvatars(files)
    }

    /**
     * `BackupFileAdapter.onBindViewHolder`'s two `setText`s: `account_jid` is the bare address and
     * `account_status` is the app and the file's timestamp, formatted with the row's own context and
     * the same `FORMAT_SHOW_DATE or FORMAT_SHOW_YEAR`.
     */
    private fun rowOf(backupFile: BackupFile): ImportBackupRow {
        val header = backupFile.header
        return ImportBackupRow(
            key = backupFile.uri.toString(),
            jid = header.getJid().asBareJid().toString(),
            status =
                String.format(
                    "%s · %s",
                    header.getApp(),
                    DateUtils.formatDateTime(
                        this,
                        header.getTimestamp(),
                        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR,
                    ),
                ),
        )
    }

    /**
     * `BackupFileAdapter`'s avatar worker, kept off the main thread for the same reason: the host's
     * `textAvatar` is `AvatarService`'s, which reads. The host's answer is published to the state and
     * the row draws it; a row whose address already has one is not asked twice.
     */
    private fun loadAvatars(files: List<BackupFile>) {
        val size = resources.getDimensionPixelSize(R.dimen.avatar)
        for (file in files) {
            val jid = file.header.getJid().asBareJid().toString()
            if (avatars.containsKey(jid)) {
                continue
            }
            AVATAR_LOADER.execute {
                val avatar = UiHost.installed().textAvatar(jid, size)
                runOnUiThread { avatars = avatars + (jid to avatar) }
            }
        }
    }

    /** A row was tapped: its file's password dialog. It is the adapter's `OnItemClickedListener`. */
    private fun onBackupFileClicked(row: ImportBackupRow) {
        val backupFile = backupFiles.firstOrNull { it.uri.toString() == row.key } ?: return
        showEnterPasswordDialog(backupFile, false)
    }

    private fun openBackupFileFromUri(uri: Uri, finishOnCancel: Boolean) {
        val backupFileFuture = BackupFile.readAsync(this, uri)
        Futures.addCallback(
            backupFileFuture,
            object : FutureCallback<BackupFile> {
                override fun onSuccess(backupFile: BackupFile) {
                    val file = backupFile
                    if (UiHost.installed().quicksy()) {
                        if (file.header.getJid().getDomain() != Config.QUICKSY_DOMAIN) {
                            showMessage(getString(R.string.non_quicksy_backup))
                            return
                        }
                    }
                    showEnterPasswordDialog(file, finishOnCancel)
                }

                override fun onFailure(t: Throwable) {
                    Log.d(Config.LOGTAG, "could not open backup file $uri", t)
                    showBackupThrowable(t)
                }
            },
            ContextCompat.getMainExecutor(application),
        )
    }

    private fun showBackupThrowable(throwable: Throwable) {
        if (throwable is BackupFileHeader.OutdatedBackupFileVersion) {
            showMessage(getString(R.string.outdated_backup_file_format))
        } else if (throwable is IOException || throwable is IllegalArgumentException) {
            showMessage(getString(R.string.not_a_backup_file))
        } else if (throwable is SecurityException) {
            Log.d(Config.LOGTAG, "not able to parse backup file", throwable)
            showMessage(getString(R.string.sharing_application_not_grant_permission))
        }
    }

    /**
     * The old `showEnterPasswordDialog`: the same URI is remembered for the instance state, the same
     * log line is written, and the dialog is opened over the same file.
     */
    private fun showEnterPasswordDialog(backupFile: BackupFile, finishOnCancel: Boolean) {
        this.currentRestoreDialog = backupFile.uri
        Log.d(Config.LOGTAG, "attempting to import " + backupFile.uri)
        this.dialogFile = backupFile
        this.dialogFinishOnCancel = finishOnCancel
        dialog = ImportBackupPasswordState(jid = backupFile.header.getJid().toString())
    }

    /**
     * The dialog's positive button. An empty password is the old `account_password_layout.setError`
     * and nothing else happens; a password starts the import and closes the dialog, which is what
     * `d.dismiss()` did. The remembered URI stays set, exactly as it did.
     */
    private fun restore(password: String, includeKeys: Boolean) {
        val backupFile = this.dialogFile ?: return
        if (password.isEmpty()) {
            dialog = dialog?.copy(error = getString(R.string.please_enter_password))
            return
        }
        importBackup(backupFile, password, includeKeys)
        dialog = null
    }

    /** The dialog's negative button: forget the URI, and leave the screen when it was opened by a share. */
    private fun cancelPassword() {
        this.currentRestoreDialog = null
        dialog = null
        if (this.dialogFinishOnCancel) {
            finish()
        }
    }

    /**
     * One transient message, where `Snackbar.make(binding.coordinator, …)` used to put it. The token
     * is what makes two identical messages two messages to the screen.
     */
    private fun showMessage(text: String) {
        messageToken += 1
        state = state.copy(message = ImportBackupMessage(messageToken, text))
    }

    private fun importBackup(backupFile: BackupFile, password: String, includeOmemo: Boolean) {
        val id =
            UiHost.installed()
                .startImport(this, password, backupFile.uri, includeOmemo)
        this.currentWorkRequest = id
        monitorWorkRequest(id)
    }

    private fun monitorWorkRequest(uuid: UUID?) {
        if (uuid == null) {
            return
        }
        Log.d(Config.LOGTAG, "monitorWorkRequest(" + uuid + ")")
        val workInfoLiveData = WorkManager.getInstance(this).getWorkInfoByIdLiveData(uuid)
        workInfoLiveData.observe(this) { workInfo ->
            val info = workInfo ?: return@observe
            val workState = info.state
            if (workState.isFinished) {
                this.currentWorkRequest = null
            }
            if (workState == WorkInfo.State.FAILED) {
                val data = info.outputData
                val reason = UiHost.installed().importFailure(data.getString("reason"))
                when (reason) {
                    UiHost.BackupFailure.DECRYPTION_FAILED -> onBackupDecryptionFailed()
                    UiHost.BackupFailure.ACCOUNT_ALREADY_EXISTS -> onAccountAlreadySetup()
                    else -> onBackupRestoreFailed()
                }
            } else if (workState == WorkInfo.State.SUCCEEDED) {
                onBackupRestored()
            }
        }
    }

    private fun onAccountAlreadySetup() {
        showMessage(getString(R.string.account_already_setup))
    }

    private fun onBackupRestored() {
        val intent = Intent(this, ConversationListActivity::class.java)
        intent.addFlags(
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TASK,
        )
        startActivity(intent)
        finish()
    }

    private fun onBackupDecryptionFailed() {
        showMessage(getString(R.string.unable_to_decrypt_backup))
    }

    private fun onBackupRestoreFailed() {
        showMessage(getString(R.string.unable_to_restore_backup))
    }

    /**
     * Whether the Compose tree is the dark one. The stored preference is what decides it - "a stored
     * preference always wins over the system" - and it reaches the resources through
     * `AppCompatDelegate.setDefaultNightMode`, applied at start-up and in `BaseActivity`, so the
     * Activity's own configuration is the answer and the screen consults no setting.
     */
    private fun darkTheme(): Boolean {
        val mode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    companion object {
        /**
         * The avatar loader, once per process: the deleted adapter had one shared `AsyncTask` pool for
         * the same work, and a spinner of its own per Activity would leak a thread per rotation.
         */
        private val AVATAR_LOADER: ExecutorService = Executors.newSingleThreadExecutor()
    }
}
