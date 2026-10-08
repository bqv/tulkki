package uk.xa0.tulkki.ui.fragment.settings

import android.app.KeyguardManager
import android.content.Context
import android.content.DialogInterface
import android.text.Editable
import android.text.InputType
import android.util.Log
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

import androidx.appcompat.app.AlertDialog

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.common.base.Strings

import uk.xa0.tulkki.crypto.OmemoSetting
import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.AppSettings
import uk.xa0.tulkki.data.DatabaseBackend
import uk.xa0.tulkki.data.updb.UnifiedPushDatabase
import uk.xa0.tulkki.data.utils.FileHelper
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.XmppActivity
import uk.xa0.tulkki.ui.preferences.PreferenceItem
import uk.xa0.tulkki.ui.preferences.PreferenceScreenFragment
import uk.xa0.tulkki.xmpp.services.MemorizingTrustManager
import uk.xa0.tulkki.xmpp.utils.CryptoHelper

import java.security.KeyStoreException
import java.util.ArrayList
import java.util.Arrays
import java.util.Collections

/**
 * The security rows `preferences_security.xml` held, over the same three categories, with the file
 * deleted with this class.
 *
 * <p>The dialogs are untouched: they were never layouts, they are `MaterialAlertDialogBuilder` with
 * views built in code, and a preference tree that no longer exists does not change them. What changed
 * is where their answers land - a dialog that used to write `pref.isChecked` now writes the preference
 * and re-renders the row, and the two rows with a live summary (`omemo`, `database_encryption`) compute
 * it in `buildItems` from the same reading.
 *
 * <p>The startup-password switch is the one row that refuses its own write: its `onPreferenceChange`
 * returns false and the dialog does the work, exactly as the old listener did. Its enabled state and
 * its two summary sentences are `updateStartupPasswordPreferenceState`'s, and the `omemo` row's summary
 * is the old `OmemoSummaryProvider`'s three-way choice.
 */
class SecuritySettingsFragment : PreferenceScreenFragment() {

    override fun buildItems(): List<PreferenceItem> {
        val items = ArrayList<PreferenceItem>()
        items.add(header(R.string.pref_category_e2ee))
        items.add(omemo())
        items.add(
            switchItem(
                AppSettings.BTBV,
                R.string.pref_blind_trust_before_verification,
                R.string.pref_blind_trust_before_verification_summary,
                R.drawable.ic_verified_user_24dp,
                resources.getBoolean(uk.xa0.tulkki.xmpp.R.bool.btbv),
            )
        )
        items.add(
            switchItem(
                ENABLE_OTR_ENCRYPTION,
                R.string.pref_enable_otr,
                R.string.pref_enable_otr_summary,
                R.drawable.ic_lock_24dp,
                resources.getBoolean(R.bool.enable_otr),
            )
        )
        items.add(
            PreferenceItem(
                key = DELETE_OMEMO_IDENTITIES,
                title = getString(R.string.pref_delete_omemo_identities),
                summary = getString(R.string.pref_delete_omemo_identities_summary),
                icon = R.drawable.outline_delete_red_24,
            )
        )
        items.add(
            timeframeList(
                AppSettings.OMEMO_AUTO_EXPIRY,
                R.string.pref_omemo_auto_expiry,
                R.string.pref_omemo_auto_expiry_summary,
                R.drawable.ic_auto_delete_24dp,
                R.array.omemo_auto_expiry_values,
                resources.getInteger(uk.xa0.tulkki.xmpp.R.integer.omemo_auto_expiry).toString(),
            )
        )

        items.add(header(R.string.pref_category_server_connection))
        items.add(
            switchItem(
                AppSettings.DANE_ENFORCED,
                R.string.pref_enforce_dane,
                R.string.pref_enforce_dane_summary,
                R.drawable.shield_verified_big,
                resources.getBoolean(uk.xa0.tulkki.data.R.bool.enforce_dane),
            )
        )
        items.add(
            switchItem(
                AppSettings.REQUIRE_TLS_V1_3,
                R.string.strong_transport_security,
                R.string.require_tls_v1_3,
                R.drawable.ic_enhanced_encryption_24dp,
                resources.getBoolean(uk.xa0.tulkki.data.R.bool.require_tls_v1_3),
            )
        )
        items.add(
            switchItem(
                AppSettings.TRUST_SYSTEM_CA_STORE,
                R.string.pref_title_trust_system_ca_store,
                R.string.pref_title_trust_system_ca_store_summary,
                R.drawable.ic_assured_workload_24dp,
                resources.getBoolean(uk.xa0.tulkki.data.R.bool.trust_system_ca_store),
            )
        )
        items.add(
            PreferenceItem(
                key = REMOVE_TRUSTED_CERTIFICATES,
                title = getString(R.string.pref_remove_trusted_certificates_title),
                summary = getString(R.string.pref_remove_trusted_certificates_summary),
                icon = R.drawable.ic_delete_24dp,
            )
        )
        items.add(
            switchItem(
                AppSettings.REQUIRE_CHANNEL_BINDING,
                R.string.detect_mim,
                R.string.detect_mim_summary,
                R.drawable.ic_private_connectivity_24dp,
                resources.getBoolean(uk.xa0.tulkki.data.R.bool.require_channel_binding),
            )
        )

        items.add(header(R.string.pref_category_on_this_device))
        items.add(
            timeframeList(
                AppSettings.AUTOMATIC_MESSAGE_DELETION,
                R.string.pref_automatically_delete_messages,
                R.string.pref_automatically_delete_messages_description,
                R.drawable.ic_auto_delete_24dp,
                R.array.automatic_message_deletion_values,
                resources.getInteger(uk.xa0.tulkki.xmpp.R.integer.automatic_message_deletion).toString(),
            )
        )
        items.add(
            PreferenceItem(
                key = DATABASE_ENCRYPTION,
                title = getString(R.string.pref_database_encryption),
                summary = getString(databaseEncryptionSummary()),
                icon = R.drawable.ic_lock_24dp,
            )
        )
        val startup = startupPasswordState()
        items.add(
            switchItem(
                AppSettings.REQUIRE_PASSWORD_ON_STARTUP,
                R.string.pref_require_startup_password_title,
                startup.summary,
                R.drawable.ic_lock_24dp,
                false,
                enabled = startup.enabled,
            )
        )
        return items
    }

    override fun onPreferenceClick(item: PreferenceItem) {
        when (item.key) {
            DELETE_OMEMO_IDENTITIES -> deleteOmemoIdentities()
            REMOVE_TRUSTED_CERTIFICATES -> showRemoveCertificatesDialog()
            DATABASE_ENCRYPTION -> showDatabaseEncryptionDialog()
        }
    }

    override fun onPreferenceChange(item: PreferenceItem, newValue: Any?): Boolean {
        if (item.key == AppSettings.REQUIRE_PASSWORD_ON_STARTUP) {
            if (java.lang.Boolean.TRUE == newValue) {
                showEnableStartupPasswordDialog()
            } else {
                disableStartupPasswordRequirement()
            }
            return false // applied manually after verification
        }
        return true
    }

    /** The `omemo` list row with the old `OmemoSummaryProvider`'s sentence. */
    private fun omemo(): PreferenceItem {
        val (labels, values) = listFromArrays(R.array.omemo_setting_entries, R.array.omemo_setting_entry_values)
        val default = getString(uk.xa0.tulkki.crypto.R.string.omemo_setting_default)
        val value = preferences.getString(AppSettings.OMEMO, default)
        val summary =
            when (Strings.nullToEmpty(value)) {
                "always" -> getString(R.string.pref_omemo_setting_summary_always)
                "default_off" -> getString(R.string.pref_omemo_setting_summary_default_off)
                else -> getString(R.string.pref_omemo_setting_summary_default_on)
            }
        return PreferenceItem(
            key = AppSettings.OMEMO,
            title = getString(R.string.pref_omemo_setting),
            summary = summary,
            icon = R.drawable.ic_lock_24dp,
            kind = PreferenceItem.Kind.LIST,
            labels = labels,
            values = values,
            value = value,
            defaultValue = default,
        )
    }

    /**
     * A `ListPreference` whose entries are an int array named by `timeframeValueToName`, with the XML's
     * static summary in front of the value name on a line of its own - the old `setValues` shape.
     */
    private fun timeframeList(
        key: String,
        title: Int,
        summary: Int,
        icon: Int,
        valuesRes: Int,
        default: String,
    ): PreferenceItem {
        val (labels, values) =
            listFromIntArray(valuesRes) { value -> timeframeValueToName(requireContext(), value) }
        val value = preferences.getString(key, default)
        val name = listSummary(labels, values, value)
        return PreferenceItem(
            key = key,
            title = getString(title),
            summary = if (name == null) getString(summary) else getString(summary) + "\n" + name,
            icon = icon,
            kind = PreferenceItem.Kind.LIST,
            labels = labels,
            values = values,
            value = value,
            defaultValue = default,
        )
    }

    private fun header(title: Int): PreferenceItem =
        PreferenceItem(title = getString(title), kind = PreferenceItem.Kind.HEADER)

    /**
     * The database-encryption row's own sentence: whether a password is stored, or the keystore error
     * the old `updateDatabaseEncryptionSummary` reported when reading it threw.
     */
    private fun databaseEncryptionSummary(): Int {
        return try {
            val pw = AppSettings(requireContext()).getDatabasePasswordChars()
            val summary =
                if (pw == null) {
                    R.string.pref_database_encryption_summary_disabled
                } else {
                    R.string.pref_database_encryption_summary_enabled
                }
            if (pw != null) {
                FileHelper.zero(pw)
            }
            summary
        } catch (e: Exception) {
            R.string.title_critical_keystore_error
        }
    }

    /** Whether the startup-password switch can act, and which of its two sentences it says. */
    private data class StartupPassword(val enabled: Boolean, val summary: Int)

    /**
     * `updateStartupPasswordPreferenceState`'s reading. When startup-required mode is on the database is
     * always encrypted (enforced during setup), so the row is enabled and says the plain sentence;
     * otherwise the secure store's own answer decides, and a read that throws greys the row with no
     * change to its sentence.
     */
    private fun startupPasswordState(): StartupPassword {
        val appSettings = AppSettings(requireContext())
        if (appSettings.isPasswordOnStartupRequired()) {
            return StartupPassword(true, R.string.pref_require_startup_password_summary)
        }
        val dbEncrypted: Boolean =
            try {
                val pw = appSettings.getDatabasePasswordChars()
                val present = pw != null
                if (pw != null) {
                    FileHelper.zero(pw)
                }
                present
            } catch (e: Exception) {
                return StartupPassword(false, R.string.pref_require_startup_password_summary)
            }
        return if (!dbEncrypted) {
            StartupPassword(false, R.string.pref_require_startup_password_summary_needs_encryption)
        } else {
            StartupPassword(true, R.string.pref_require_startup_password_summary)
        }
    }

    /**
     * Enabling the startup-password requirement: Ask the user to type their current DB password to
     * prove they know it, then remove it from persistent storage so it lives only in the session and
     * must be re-entered on every cold start.
     */
    private fun showEnableStartupPasswordDialog() {
        val builder = MaterialAlertDialogBuilder(requireActivity())
        builder.setTitle(R.string.pref_require_startup_password_title)
        builder.setMessage(R.string.dialog_enable_startup_password_message)

        val layout = LinearLayout(requireContext())
        layout.orientation = LinearLayout.VERTICAL
        val p = (16 * resources.displayMetrics.density).toInt()
        layout.setPadding(p, p, p, p)

        val input = TextInputEditText(requireContext())
        input.setHint(R.string.dialog_db_password_hint)
        input.isSingleLine = true
        input.inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        layout.addView(input)
        builder.setView(layout)

        builder.setNegativeButton(android.R.string.cancel, null)
        builder.setPositiveButton(android.R.string.ok, null)

        val dialog = builder.create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val text = input.text
            if (text == null || text.length == 0) return@setOnClickListener
            val entered = CharArray(text.length)
            text.getChars(0, text.length, entered, 0)
            text.clear()
            try {
                val stored = AppSettings(requireContext()).getDatabasePasswordChars()
                val match = stored != null && CryptoHelper.isEqual(entered, stored)
                Arrays.fill(entered, '\u0000')
                if (!match) {
                    if (stored != null) Arrays.fill(stored, '\u0000')
                    Toast.makeText(
                            requireContext(),
                            R.string.toast_wrong_startup_password,
                            Toast.LENGTH_SHORT,
                        )
                        .show()
                    return@setOnClickListener
                }
                // Correct - set the flag first so subsequent getDatabasePasswordChars() uses session,
                // then erase the persisted copy from secure storage.
                AppSettings.setSessionPassword(stored)
                Arrays.fill(stored, '\u0000')
                preferences
                    .edit()
                    .putBoolean(AppSettings.REQUIRE_PASSWORD_ON_STARTUP, true)
                    .commit()
                try {
                    AppSettings(requireContext()).clearPersistedDatabasePassword()
                } catch (e: Exception) {
                    Log.e("SecuritySettings", "Could not remove stored DB password", e)
                }
                dialog.dismiss()
                render()
                Toast.makeText(
                        requireContext(),
                        R.string.toast_startup_password_enabled,
                        Toast.LENGTH_SHORT,
                    )
                    .show()
            } catch (e: Exception) {
                Arrays.fill(entered, '\u0000')
                (requireActivity() as XmppActivity).showCriticalErrorDialog()
            }
        }
    }

    /**
     * Disabling the startup-password requirement: Store the session password back to encrypted
     * persistent storage so the service can open the DB automatically on the next cold start.
     */
    private fun disableStartupPasswordRequirement() {
        val sessionPw = AppSettings.getSessionPassword()
        if (sessionPw == null) {
            // Should not happen (user is in settings after unlocking), but guard defensively
            Toast.makeText(
                    requireContext(),
                    R.string.toast_startup_password_session_required,
                    Toast.LENGTH_SHORT,
                )
                .show()
            return
        }
        try {
            // Flip the flag to false FIRST so setDatabasePassword uses the normal persistent path
            preferences
                .edit()
                .putBoolean(AppSettings.REQUIRE_PASSWORD_ON_STARTUP, false)
                .commit()
            // Write the password back to persistent storage (DataStore+Tink)
            AppSettings(requireContext()).setDatabasePassword(sessionPw)
            // Clear the in-memory copy (persistent storage is now the source of truth again)
            AppSettings.clearSessionPassword()
            render()
            Toast.makeText(
                    requireContext(),
                    R.string.toast_startup_password_disabled,
                    Toast.LENGTH_SHORT,
                )
                .show()
        } catch (e: Exception) {
            // Restore the flag if the write failed
            preferences
                .edit()
                .putBoolean(AppSettings.REQUIRE_PASSWORD_ON_STARTUP, true)
                .commit()
            (requireActivity() as XmppActivity).showCriticalErrorDialog()
        }
    }

    /**
     * `TextView.getText` is nullable to Kotlin and the Java dereferenced it at every one of these
     * sites; an EditText always carries an Editable, so this names the NullPointerException the Java
     * would have raised instead of asserting with `!!`.
     */
    private fun editable(input: TextInputEditText): Editable =
        input.text ?: throw NullPointerException("no editable text")

    private fun showDatabaseEncryptionDialog() {
        try {
            val currentPassword = AppSettings(requireContext()).getDatabasePasswordChars()
            if (currentPassword == null) {
                showEnableEncryptionDialog()
            } else {
                showEncryptionOptionsDialog(currentPassword)
            }
        } catch (e: Exception) {
            (requireActivity() as XmppActivity).showCriticalErrorDialog()
        }
    }

    private fun showEnableEncryptionDialog() {
        val builder = MaterialAlertDialogBuilder(requireActivity())
        builder.setTitle(R.string.dialog_set_db_password_title)

        val layout = LinearLayout(requireContext())
        layout.orientation = LinearLayout.VERTICAL
        val padding = (16 * resources.displayMetrics.density).toInt()
        layout.setPadding(padding, padding, padding, padding)

        val warningText = TextView(requireContext())
        warningText.setText(R.string.dialog_db_password_policy_warning)
        warningText.setTextColor(requireContext().getColor(android.R.color.holo_red_dark))
        warningText.setPadding(0, 0, 0, padding)
        layout.addView(warningText)

        val keyguardManager =
            requireContext().getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager?
        if (keyguardManager != null && !keyguardManager.isDeviceSecure) {
            val lockWarningText = TextView(requireContext())
            lockWarningText.setText(R.string.dialog_db_password_no_lock_warning)
            lockWarningText.setTextColor(
                requireContext().getColor(android.R.color.holo_orange_dark)
            )
            lockWarningText.setPadding(0, 0, 0, padding)
            layout.addView(lockWarningText)
        }

        val passwordInput = TextInputEditText(requireContext())
        passwordInput.setHint(R.string.dialog_db_password_hint)
        passwordInput.inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        layout.addView(passwordInput)

        val confirmInput = TextInputEditText(requireContext())
        confirmInput.setHint(R.string.dialog_db_password_confirm_hint)
        confirmInput.inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        layout.addView(confirmInput)

        val noRecoveryCheckbox = CheckBox(requireContext())
        noRecoveryCheckbox.setText(R.string.dialog_db_password_no_recovery_checkbox)
        noRecoveryCheckbox.setPadding(0, padding / 2, 0, 0)
        layout.addView(noRecoveryCheckbox)

        builder.setView(layout)
        builder.setPositiveButton(android.R.string.ok, null)
        builder.setNegativeButton(android.R.string.cancel, null)
        val d = builder.show()
        val okButton = d.getButton(AlertDialog.BUTTON_POSITIVE)
        okButton.isEnabled = false
        noRecoveryCheckbox.setOnCheckedChangeListener { _, isChecked ->
            okButton.isEnabled = isChecked
        }
        okButton.setOnClickListener {
            val password = CharArray(passwordInput.length())
            editable(passwordInput).getChars(0, passwordInput.length(), password, 0)
            val confirm = CharArray(confirmInput.length())
            editable(confirmInput).getChars(0, confirmInput.length(), confirm, 0)

            if (password.isEmpty()) {
                Toast.makeText(requireContext(), "Password cannot be empty", Toast.LENGTH_SHORT)
                    .show()
            } else if (password.size < 8) {
                Toast.makeText(
                        requireContext(),
                        R.string.toast_db_password_error_too_short,
                        Toast.LENGTH_SHORT,
                    )
                    .show()
            } else if (CryptoHelper.isEqual(password, confirm)) {
                editable(passwordInput).clear()
                editable(confirmInput).clear()
                d.dismiss()
                performMigration(null, password)
                FileHelper.zero(confirm)
                return@setOnClickListener
            } else {
                Toast.makeText(
                        requireContext(),
                        R.string.toast_db_password_error_mismatch,
                        Toast.LENGTH_SHORT,
                    )
                    .show()
            }
            FileHelper.zero(password)
            FileHelper.zero(confirm)
        }
    }

    private fun showEncryptionOptionsDialog(currentPassword: CharArray) {
        val builder = MaterialAlertDialogBuilder(requireActivity())
        builder.setTitle(R.string.pref_database_encryption)
        val options =
            arrayOf(
                getString(R.string.dialog_change_db_password_title),
                getString(R.string.dialog_remove_db_password_title),
            )
        builder.setItems(options) { _, which ->
            // Clone so the sub-dialog owns its copy; dismiss listener will zero this original.
            if (which == 0) {
                showChangePasswordDialog(currentPassword.clone())
            } else {
                showDisableEncryptionDialog(currentPassword.clone())
            }
        }
        val d = builder.show()
        d.setOnDismissListener { FileHelper.zero(currentPassword) }
    }

    private fun showChangePasswordDialog(currentPassword: CharArray) {
        val builder = MaterialAlertDialogBuilder(requireActivity())
        builder.setTitle(R.string.dialog_change_db_password_title)

        val layout = LinearLayout(requireContext())
        layout.orientation = LinearLayout.VERTICAL
        val padding = (16 * resources.displayMetrics.density).toInt()
        layout.setPadding(padding, padding, padding, padding)

        val warningText = TextView(requireContext())
        warningText.setText(R.string.dialog_db_password_policy_warning)
        warningText.setTextColor(requireContext().getColor(android.R.color.holo_red_dark))
        warningText.setPadding(0, 0, 0, padding)
        layout.addView(warningText)

        val keyguardManager =
            requireContext().getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager?
        if (keyguardManager != null && !keyguardManager.isDeviceSecure) {
            val lockWarningText = TextView(requireContext())
            lockWarningText.setText(R.string.dialog_db_password_no_lock_warning)
            lockWarningText.setTextColor(
                requireContext().getColor(android.R.color.holo_orange_dark)
            )
            lockWarningText.setPadding(0, 0, 0, padding)
            layout.addView(lockWarningText)
        }

        val currentInput = TextInputEditText(requireContext())
        currentInput.setHint(R.string.dialog_db_password_current_hint)
        currentInput.inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        layout.addView(currentInput)

        val passwordInput = TextInputEditText(requireContext())
        passwordInput.setHint(R.string.dialog_db_password_hint)
        passwordInput.inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        layout.addView(passwordInput)

        val confirmInput = TextInputEditText(requireContext())
        confirmInput.setHint(R.string.dialog_db_password_confirm_hint)
        confirmInput.inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        layout.addView(confirmInput)

        builder.setView(layout)
        builder.setPositiveButton(android.R.string.ok) { _, _ ->
            val current = CharArray(currentInput.length())
            editable(currentInput).getChars(0, currentInput.length(), current, 0)
            val password = CharArray(passwordInput.length())
            editable(passwordInput).getChars(0, passwordInput.length(), password, 0)
            val confirm = CharArray(confirmInput.length())
            editable(confirmInput).getChars(0, confirmInput.length(), confirm, 0)

            if (!CryptoHelper.isEqual(current, currentPassword)) {
                Toast.makeText(
                        requireContext(),
                        R.string.toast_db_password_error_wrong,
                        Toast.LENGTH_SHORT,
                    )
                    .show()
                FileHelper.zero(current)
                FileHelper.zero(password)
                FileHelper.zero(confirm)
            } else if (password.size < 8) {
                Toast.makeText(
                        requireContext(),
                        R.string.toast_db_password_error_too_short,
                        Toast.LENGTH_SHORT,
                    )
                    .show()
                FileHelper.zero(current)
                FileHelper.zero(password)
                FileHelper.zero(confirm)
            } else if (CryptoHelper.isEqual(password, confirm)) {
                editable(currentInput).clear()
                editable(passwordInput).clear()
                editable(confirmInput).clear()
                FileHelper.zero(current)
                FileHelper.zero(confirm)
                // performMigration zeros both in finally
                performMigration(currentPassword, password)
            } else {
                Toast.makeText(
                        requireContext(),
                        R.string.toast_db_password_error_mismatch,
                        Toast.LENGTH_SHORT,
                    )
                    .show()
                FileHelper.zero(current)
                FileHelper.zero(password)
                FileHelper.zero(confirm)
            }
        }
        builder.setNegativeButton(android.R.string.cancel) { _, _ -> FileHelper.zero(currentPassword) }
        builder.show()
    }

    private fun showDisableEncryptionDialog(currentPassword: CharArray) {
        val builder = MaterialAlertDialogBuilder(requireActivity())
        builder.setTitle(R.string.dialog_remove_db_password_title)

        val layout = LinearLayout(requireContext())
        layout.orientation = LinearLayout.VERTICAL
        val padding = (16 * resources.displayMetrics.density).toInt()
        layout.setPadding(padding, padding, padding, padding)

        val warningText = TextView(requireContext())
        warningText.setText(R.string.dialog_db_password_policy_warning)
        warningText.setTextColor(requireContext().getColor(android.R.color.holo_red_dark))
        warningText.setPadding(0, 0, 0, padding)
        layout.addView(warningText)

        val keyguardManager =
            requireContext().getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager?
        if (keyguardManager != null && !keyguardManager.isDeviceSecure) {
            val lockWarningText = TextView(requireContext())
            lockWarningText.setText(R.string.dialog_db_password_no_lock_warning)
            lockWarningText.setTextColor(
                requireContext().getColor(android.R.color.holo_orange_dark)
            )
            lockWarningText.setPadding(0, 0, 0, padding)
            layout.addView(lockWarningText)
        }

        val currentInput = TextInputEditText(requireContext())
        currentInput.setHint(R.string.dialog_db_password_current_hint)
        currentInput.inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        layout.addView(currentInput)

        builder.setView(layout)
        builder.setPositiveButton(android.R.string.ok) { _, _ ->
            val current = CharArray(currentInput.length())
            editable(currentInput).getChars(0, currentInput.length(), current, 0)
            if (CryptoHelper.isEqual(current, currentPassword)) {
                editable(currentInput).clear()
                FileHelper.zero(current)
                // performMigration zeros currentPassword in finally
                performMigration(currentPassword, null)
            } else {
                Toast.makeText(
                        requireContext(),
                        R.string.toast_db_password_error_wrong,
                        Toast.LENGTH_SHORT,
                    )
                    .show()
                FileHelper.zero(current)
            }
        }
        builder.setNegativeButton(android.R.string.cancel) { _, _ -> FileHelper.zero(currentPassword) }
        builder.show()
    }

    private fun performMigration(oldPassword: CharArray?, newPassword: CharArray?) {
        val progressBuilder = MaterialAlertDialogBuilder(requireActivity())
        progressBuilder.setTitle("Database Migration")
        progressBuilder.setMessage("Please wait...")
        progressBuilder.setCancelable(false)
        val progressBar = ProgressBar(requireContext())
        val padding = (16 * resources.displayMetrics.density).toInt()
        progressBar.setPadding(padding, padding, padding, padding)
        progressBuilder.setView(progressBar)
        val progressDialog = progressBuilder.show()

        // Capture the service reference on the UI thread before going to the background.
        val service = requireService()

        Thread {
                try {
                    // The old backend stays alive throughout migrate() - service threads must
                    // remain functional during the multi-second export. closeInstance() is
                    // called at the very end of migrate(), after which getInstance() opens the
                    // new file with the new key.
                    DatabaseBackend.migrate(requireContext(), oldPassword, newPassword)

                    // migrate() succeeded and called closeInstance(). Restore the backend on
                    // this thread immediately (no UI-thread hop) to minimise the window where the
                    // old closed backend is still referenced by service.databaseBackend.
                    val newBackend = DatabaseBackend.getInstance(requireContext())
                    service.swapDatabaseBackend(newBackend)

                    // UnifiedPush is a separate DB - migrate best-effort; don't abort on failure.
                    try {
                        UnifiedPushDatabase.migrate(requireContext(), oldPassword, newPassword)
                    } catch (e: Exception) {
                        Log.e(
                            uk.xa0.tulkki.xmpp.Config.LOGTAG,
                            "UnifiedPush DB migration failed (non-fatal)",
                            e,
                        )
                    }

                    try {
                        newBackend.rebuildMessagesIndex()
                    } catch (e: Exception) {
                        Log.e(
                            uk.xa0.tulkki.xmpp.Config.LOGTAG,
                            "FTS rebuild failed after migration (non-fatal)",
                            e,
                        )
                    }
                    requireActivity().runOnUiThread {
                        progressDialog.dismiss()
                        Toast.makeText(
                                requireContext(),
                                if (newPassword == null) R.string.toast_db_password_success_disabled
                                else R.string.toast_db_password_success_set,
                                Toast.LENGTH_SHORT,
                            )
                            .show()
                        render()
                    }
                } catch (e: Exception) {
                    // migrate() itself failed - closeInstance() was NOT called, so the old
                    // backend is still alive and service.databaseBackend still works. Just show
                    // the error.
                    requireActivity().runOnUiThread {
                        progressDialog.dismiss()
                        MaterialAlertDialogBuilder(requireActivity())
                            .setTitle("Error")
                            .setMessage(e.message)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                } finally {
                    FileHelper.zero(oldPassword)
                    FileHelper.zero(newPassword)
                }
            }
            .start()
    }

    override fun onSharedPreferenceChanged(key: String) {
        super.onSharedPreferenceChanged(key)
        when (key) {
            AppSettings.OMEMO -> {
                OmemoSetting.load(requireContext())
            }
            AppSettings.TRUST_SYSTEM_CA_STORE -> {
                requireService().updateMemorizingTrustManager()
                reconnectAccounts()
            }
            AppSettings.DANE_ENFORCED,
            AppSettings.REQUIRE_CHANNEL_BINDING,
            AppSettings.REQUIRE_TLS_V1_3 -> {
                reconnectAccounts()
            }
            AppSettings.AUTOMATIC_MESSAGE_DELETION -> {
                requireService().expireOldMessages(true)
            }
            AppSettings.OMEMO_AUTO_EXPIRY -> {
                // No immediate action required
            }
        }
    }

    override fun onStart() {
        super.onStart()
        requireActivity().setTitle(R.string.pref_title_security)
    }

    private fun showRemoveCertificatesDialog() {
        val mtm: MemorizingTrustManager = requireService().getMemorizingTrustManager()
        val aliases: ArrayList<String> = Collections.list(mtm.getCertificates())
        if (aliases.isEmpty()) {
            Toast.makeText(requireActivity(), R.string.toast_no_trusted_certs, Toast.LENGTH_LONG)
                .show()
            return
        }
        val selectedItems = ArrayList<Int>()
        val dialogBuilder = MaterialAlertDialogBuilder(requireActivity())
        dialogBuilder.setTitle(getString(R.string.dialog_manage_certs_title))
        dialogBuilder.setMultiChoiceItems(
            aliases.toTypedArray(),
            null,
        ) { dialog, indexSelected, isChecked ->
            if (isChecked) {
                selectedItems.add(indexSelected)
            } else if (selectedItems.contains(indexSelected)) {
                // The Java removed Integer.valueOf(indexSelected); Kotlin's element removal
                // is the same overload.
                selectedItems.remove(indexSelected)
            }
            if (dialog is AlertDialog) {
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled =
                    !selectedItems.isEmpty()
            }
        }

        dialogBuilder.setPositiveButton(
            getString(R.string.dialog_manage_certs_positivebutton)
        ) { _, _ ->
            confirmCertificateDeletion(aliases, selectedItems)
        }
        dialogBuilder.setNegativeButton(uk.xa0.tulkki.data.R.string.cancel, null)
        val removeCertsDialog = dialogBuilder.create()
        removeCertsDialog.show()
        removeCertsDialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
    }

    private fun confirmCertificateDeletion(
        aliases: ArrayList<String>,
        selectedItems: ArrayList<Int>,
    ) {
        val count = selectedItems.size
        if (count == 0) {
            return
        }
        val mtm: MemorizingTrustManager = requireService().getMemorizingTrustManager()
        for (i in 0 until count) {
            try {
                val item = Integer.parseInt(selectedItems[i].toString())
                val alias = aliases[item]
                mtm.deleteCertificate(alias)
            } catch (e: KeyStoreException) {
                Toast.makeText(
                        requireActivity(),
                        "Error: " + e.localizedMessage,
                        Toast.LENGTH_LONG,
                    )
                    .show()
            }
        }
        reconnectAccounts()
        Toast.makeText(
                requireActivity(),
                resources.getQuantityString(R.plurals.toast_delete_certificates, count, count),
                Toast.LENGTH_LONG,
            )
            .show()
    }

    private fun deleteOmemoIdentities() {
        val builder = MaterialAlertDialogBuilder(requireActivity())
        builder.setTitle(R.string.pref_delete_omemo_identities)
        val accounts = ArrayList<CharSequence>()
        for (account in AccountRegistry.get().getAccounts()) {
            if (account.isEnabled()) {
                accounts.add(account.getJid().asBareJid().toString())
            }
        }
        val checkedItems = BooleanArray(accounts.size)
        builder.setMultiChoiceItems(
            accounts.toTypedArray(),
            checkedItems,
        ) { dialog, which, isChecked ->
            checkedItems[which] = isChecked
            val alertDialog = dialog as AlertDialog
            // The Java enabled the button on the first checked box and disabled it when none
            // was left; `any` is the same decision.
            alertDialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled =
                checkedItems.any { it }
        }
        builder.setNegativeButton(uk.xa0.tulkki.data.R.string.cancel, null)
        builder.setPositiveButton(R.string.delete_selected_keys) { _, _ ->
            for (i in checkedItems.indices) {
                if (checkedItems[i]) {
                    try {
                        val jid = Jid.of(accounts[i].toString())
                        val account = AccountRegistry.get().findAccountByJid(jid)
                        if (account != null) {
                            (account.getAxolotlService()
                                    ?: throw NullPointerException("no axolotl service"))
                                .regenerateKeys(true)
                        }
                        Toast.makeText(
                                requireActivity(),
                                R.string.omemo_identities_reset,
                                Toast.LENGTH_LONG,
                            )
                            .show()
                    } catch (e: IllegalArgumentException) {
                        Toast.makeText(
                                requireActivity(),
                                R.string.failed_to_reset_omemo_identities,
                                Toast.LENGTH_LONG,
                            )
                            .show()
                    }
                }
            }
        }
        val dialog = builder.create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
    }

    companion object {
        private const val REMOVE_TRUSTED_CERTIFICATES = "remove_trusted_certificates"
        private const val DELETE_OMEMO_IDENTITIES = "delete_omemo_identities"
        private const val DATABASE_ENCRYPTION = "database_encryption"
        private const val ENABLE_OTR_ENCRYPTION = "enable_otr_encryption"
    }
}
