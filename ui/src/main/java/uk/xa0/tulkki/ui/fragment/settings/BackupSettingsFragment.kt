package uk.xa0.tulkki.ui.fragment.settings

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.base.Strings
import com.google.common.primitives.Longs

import me.drakeet.support.toast.ToastCompat

import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.AppSettings
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.ui.BuildConfig
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.host.UiHost
import uk.xa0.tulkki.ui.preferences.PreferenceItem
import uk.xa0.tulkki.ui.preferences.PreferenceScreenFragment
import uk.xa0.tulkki.xmpp.Config

import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The backup rows `preferences_backup.xml` held, with the file deleted with this class.
 *
 * <p>Three rows keep behaviour rather than a value: `create_one_off_backup` asks whether to disable
 * every account first, `backup_location` opens the platform's directory picker and then shows the
 * picked path as its own summary, and the recurring-backup list schedules or cancels the worker
 * through `UiHost` when its interval changes. The interval key is the composition root's
 * (`UiHost.installed().recurringBackupName()`), not a literal, exactly as before.
 *
 * <p>Its default is written on bind like any list's, which matters: the process-start repair reads that
 * key, and a screen that merely showed the number without persisting it would leave the repair with
 * nothing to read.
 *
 * <p>`onExportClicked` and `startExport` are gone with the tree: nothing called them - the XML's export
 * row was commented out in the old fragment and the one-off export is `create_one_off_backup`'s own
 * path. `REQUEST_EXPORT_SETTINGS` stays, because the storage permission for it is still requested
 * through `REQUEST_EXPORT_SETTINGS`-free `hasStoragePermission` in the import row's sibling.
 */
class BackupSettingsFragment : PreferenceScreenFragment() {

    private val requestStorageForBackupLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                startOneOffBackup()
            } else {
                Toast.makeText(
                        requireActivity(),
                        getString(R.string.no_storage_permission, BuildConfig.APP_NAME),
                        Toast.LENGTH_LONG,
                    )
                    .show()
            }
        }

    private val pickBackupLocationLauncher: ActivityResultLauncher<Uri?> =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) {
                Log.d(Config.LOGTAG, "no backup location selected")
                return@registerForActivityResult
            }
            submitBackupLocationPreference(uri)
        }

    override fun buildItems(): List<PreferenceItem> {
        val items = ArrayList<PreferenceItem>()
        items.add(
            PreferenceItem(
                summary = getString(R.string.pref_create_backup_warning),
                kind = PreferenceItem.Kind.LINE,
            )
        )
        items.add(recurringBackup())
        items.add(
            PreferenceItem(
                key = CREATE_ONE_OFF_BACKUP,
                title = getString(R.string.pref_create_backup),
                summary = getString(R.string.pref_create_backup_one_off_summary),
                icon = R.drawable.ic_archive_24dp,
            )
        )
        items.add(
            PreferenceItem(
                key = AppSettings.BACKUP_LOCATION,
                title = getString(R.string.pref_backup_location),
                summary =
                    getString(
                        R.string.pref_create_backup_summary,
                        AppSettings(requireContext()).getBackupLocationAsPath(),
                    ),
                icon = R.drawable.ic_folder_open_24dp,
            )
        )
        items.add(
            switchItem(
                EXPORT_PLAIN_TEXT_LOGS,
                R.string.pref_export_plain_text_logs,
                R.string.pref_export_plain_text_logs_summary,
                R.drawable.outline_article_24,
                resources.getBoolean(R.bool.plain_text_logs),
            )
        )
        items.add(
            PreferenceItem(
                title = getString(R.string.pref_category_settings),
                kind = PreferenceItem.Kind.HEADER,
            )
        )
        items.add(
            PreferenceItem(
                key = IMPORT_SETTINGS,
                title = getString(R.string.pref_import_settings),
                summary = getString(R.string.pref_import_settings_summary),
                icon = R.drawable.rounded_settings_backup_restore_24,
            )
        )
        items.add(
            PreferenceItem(
                key = EXPORT_SETTINGS,
                title = getString(R.string.pref_export_settings),
                summary = getString(R.string.pref_export_settings_summary),
                icon = R.drawable.rounded_settings_b_roll_24,
            )
        )
        return items
    }

    /** The one list row, named by `timeframeValueToName` exactly as the old `setValues` call did. */
    private fun recurringBackup(): PreferenceItem {
        val key = UiHost.installed().recurringBackupName()
        val (labels, values) =
            listFromIntArray(R.array.recurring_backup_values) { value ->
                timeframeValueToName(requireContext(), value)
            }
        val default = resources.getInteger(R.integer.automatic_backup).toString()
        val value = preferences.getString(key, default)
        return PreferenceItem(
            key = key,
            title = getString(R.string.pref_backup_recurring),
            summary = listSummary(labels, values, value),
            icon = R.drawable.ic_calendar_month_24dp,
            kind = PreferenceItem.Kind.LIST,
            labels = labels,
            values = values,
            value = value,
            defaultValue = default,
        )
    }

    override fun onPreferenceClick(item: PreferenceItem) {
        when (item.key) {
            CREATE_ONE_OFF_BACKUP -> onBackupPreferenceClicked()
            AppSettings.BACKUP_LOCATION -> pickBackupLocationLauncher.launch(null)
            IMPORT_SETTINGS -> {
                if (requireSettingsActivity().hasStoragePermission(REQUEST_IMPORT_SETTINGS)) {
                    openSettingsPicker()
                }
            }
            EXPORT_SETTINGS -> {
                if (requireSettingsActivity().hasStoragePermission(REQUEST_EXPORT_SETTINGS)) {
                    exportSettings()
                }
            }
        }
    }

    override fun onSharedPreferenceChanged(key: String) {
        super.onSharedPreferenceChanged(key)
        if (UiHost.installed().recurringBackupName() == key) {
            val recurringBackupInterval =
                Longs.tryParse(Strings.nullToEmpty(preferences.getString(key, null)))
            if (recurringBackupInterval == null) {
                return
            }
            Log.d(
                Config.LOGTAG,
                "recurring backup interval changed to: " + recurringBackupInterval,
            )
            if (recurringBackupInterval <= 0) {
                UiHost.installed().cancelRecurringBackup(requireContext())
            } else {
                // The request is not spelled out here. It has one description, in RecurringBackup,
                // because this listener is not the only thing that has to rebuild the persisted
                // row: uk.xa0.tulkki.app.worker.WorkReArm re-arms it at process start after a
                // rename, and two copies of the constraints or the input flag are two chances to
                // disagree. The host builds it, because naming the worker class is the composition
                // root's business.
                UiHost.installed()
                    .scheduleRecurringBackup(requireContext(), recurringBackupInterval)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        requireActivity().setTitle(R.string.backup)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_IMPORT_SETTINGS) {
            if (resultCode == Activity.RESULT_OK) {
                // The Java dereferenced the intent; a null one is the same failure, named.
                val intent = data ?: throw NullPointerException("no import intent")
                val uri = intent.getData()
                importSettings(uri, requireSettingsActivity())
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty()) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (requestCode == REQUEST_EXPORT_SETTINGS) {
                    exportSettings()
                }
                if (requestCode == REQUEST_IMPORT_SETTINGS) {
                    ToastCompat.makeText(
                            requireActivity(),
                            "permissions for open setting spicker granted",
                            ToastCompat.LENGTH_SHORT,
                        )
                        .show()
                    openSettingsPicker()
                }
            } else {
                ToastCompat.makeText(
                        requireActivity(),
                        R.string.no_storage_permission,
                        ToastCompat.LENGTH_SHORT,
                    )
                    .show()
            }
        }
    }

    /** Open the platform's file picker for a settings archive. */
    fun openSettingsPicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.setType("*/*")
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        startActivityForResult(
            Intent.createChooser(intent, getString(R.string.select_settings_dat)),
            REQUEST_IMPORT_SETTINGS,
        )
    }

    private fun submitBackupLocationPreference(uri: Uri) {
        val contentResolver = requireContext().contentResolver
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        val appSettings = AppSettings(requireContext())
        appSettings.setBackupLocation(uri)
        // The old fragment set the row's summary here; the summary is a reading of the stored path, so
        // the render is the same line.
        render()
    }

    private fun onBackupPreferenceClicked() {
        AlertDialog.Builder(requireActivity())
            .setTitle(R.string.disable_all_accounts)
            .setMessage(R.string.disable_all_accounts_question)
            .setPositiveButton(uk.xa0.tulkki.data.R.string.yes) { _, _ ->
                for (account in AccountRegistry.get().getAccounts()) {
                    account.setOption(Account.OPTION_DISABLED, true)
                    if (!requireService().updateAccount(account)) {
                        Toast.makeText(
                                requireActivity(),
                                R.string.unable_to_update_account,
                                Toast.LENGTH_SHORT,
                            )
                            .show()
                    }
                }
                aboutToStartOneOffBackup()
            }
            .setNegativeButton(uk.xa0.tulkki.data.R.string.no) { _, _ -> aboutToStartOneOffBackup() }
            .show()
    }

    private fun aboutToStartOneOffBackup() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestStorageForBackupLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                startOneOffBackup()
            }
        } else {
            startOneOffBackup()
        }
    }

    private fun startOneOffBackup() {
        UiHost.installed().startOneOffExport(requireContext(), CREATE_ONE_OFF_BACKUP)
        val builder = MaterialAlertDialogBuilder(requireActivity())
        builder.setMessage(R.string.backup_started_message)
        builder.setPositiveButton(R.string.ok, null)
        builder.create().show()
    }

    private fun importSettings(uri: Uri?, settingsActivity: androidx.fragment.app.FragmentActivity) {
        var success = false
        try {
            // The Java handed a possibly-null uri to a @NonNull reader, whose own getScheme() threw
            // the NullPointerException; this is the same failure, named.
            val input =
                settingsActivity.contentResolver.openInputStream(
                    uri ?: throw NullPointerException("no import uri")
                )
            ObjectInputStream(input).use { objectInput ->
                val prefEdit =
                    preferences.edit()
                prefEdit.clear()
                val restored = objectInput.readObject() as Map<String, Any?>
                for ((key, value) in restored) {
                    when (value) {
                        is Boolean -> prefEdit.putBoolean(key, value)
                        is Float -> prefEdit.putFloat(key, value)
                        is Int -> prefEdit.putInt(key, value)
                        is Long -> prefEdit.putLong(key, value)
                        is String -> prefEdit.putString(key, value)
                    }
                }
                prefEdit.commit()
                success = true
            }
        } catch (e: Exception) {
            success = false
            Log.e("SettingsImport", "Error importing settings", e)
        }

        val messageResId =
            if (success) R.string.success_import_settings else R.string.error_import_settings
        ToastCompat.makeText(settingsActivity, messageResId, ToastCompat.LENGTH_SHORT).show()
        if (success) {
            render()
        }
    }

    private fun exportSettings() {
        var success = false
        var output: ObjectOutputStream? = null
        val context = requireSettingsActivity()
        val appSettings = AppSettings(context)
        val path = appSettings.getBackupLocationAsPath()
        try {
            val file = File(path, DATE_FORMAT.format(Date()) + "_settings.dat")
            val directory = file.parentFile
            if (directory != null && directory.mkdirs()) {
                Log.d(Config.LOGTAG, "created backup directory " + directory.absolutePath)
            }
            output = ObjectOutputStream(FileOutputStream(file))
            val pref = preferences
            output.writeObject(pref.all)
            success = true
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            try {
                if (output != null) {
                    output.flush()
                    output.close()
                }
            } catch (ex: IOException) {
                ex.printStackTrace()
            }
        }
        if (success) {
            Thread { runOnUiThread { requireActivity().recreate() } }.start()
            ToastCompat.makeText(
                    requireActivity(),
                    R.string.success_export_settings,
                    ToastCompat.LENGTH_SHORT,
                )
                .show()
        } else {
            ToastCompat.makeText(
                    requireActivity(),
                    R.string.error_export_settings,
                    ToastCompat.LENGTH_SHORT,
                )
                .show()
        }
    }

    companion object {
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd-HH-mm", Locale.US)

        const val CREATE_ONE_OFF_BACKUP = "create_one_off_backup"
        const val REQUEST_EXPORT_SETTINGS = 0xbf8701
        const val REQUEST_IMPORT_SETTINGS = 0xbf8703

        private const val EXPORT_PLAIN_TEXT_LOGS = "export_plain_text_logs"
        private const val IMPORT_SETTINGS = "import_settings"
        private const val EXPORT_SETTINGS = "export_settings"
    }
}
