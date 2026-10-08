package uk.xa0.tulkki.crypto

import android.app.PendingIntent
import android.content.Intent
import android.util.Log

import androidx.annotation.StringRes

import com.google.common.base.Joiner
import com.google.common.base.Splitter
import com.google.common.base.Strings

import org.openintents.openpgp.OpenPgpError
import org.openintents.openpgp.OpenPgpSignatureResult
import org.openintents.openpgp.util.OpenPgpApi

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.io.File

import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.xmpp.utils.AsciiArmor

class PgpEngine(
    private val api: OpenPgpApi?,
    private val mXmppConnectionService: XmppConnectionService
) : uk.xa0.tulkki.xmpp.services.PgpEnginePort {

    private fun requireApi(): OpenPgpApi = api ?: throw NullPointerException("api")

    fun <T : OmemoMessage> encrypt(message: T, callback: PgpCallback<T>) {
        val params = Intent()
        params.setAction(OpenPgpApi.ACTION_ENCRYPT)
        val conversation =
            message.getOmemoConversation() ?: throw NullPointerException("conversation")
        val omemoAccount =
            conversation.getAccount() ?: throw NullPointerException("account")
        val store = omemoAccount.getPgpStore() ?: throw NullPointerException("store")
        if (conversation.getMode() == OmemoConversation.MODE_SINGLE) {
            val keys = longArrayOf(
                conversation.getContact().getPgpKeyId(),
                omemoAccount.getPgpId()
            )
            params.putExtra(OpenPgpApi.EXTRA_KEY_IDS, keys)
        } else {
            params.putExtra(OpenPgpApi.EXTRA_KEY_IDS, conversation.getMucOptions().getPgpKeyIds())
        }

        if (!message.needsUploading()) {
            params.putExtra(OpenPgpApi.EXTRA_REQUEST_ASCII_ARMOR, true)
            val body: String =
                if (message.hasFileOnRemoteHost()) {
                    message.getFileUrl() ?: throw NullPointerException("fileUrl")
                } else {
                    message.getBody()
                }
            val isStream: InputStream =
                ByteArrayInputStream(body.toByteArray(Charset.defaultCharset()))
            val os: OutputStream = ByteArrayOutputStream()
            requireApi().executeApiAsync(params, isStream, os) { result ->
                when (result.getIntExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR)) {
                    OpenPgpApi.RESULT_CODE_SUCCESS -> {
                        try {
                            os.flush()
                            val encryptedMessageBody = ArrayList<String>()
                            val lines = os.toString().split("\n")
                            for (i in 2 until lines.size - 1) {
                                if (!lines[i].contains("Version")) {
                                    encryptedMessageBody.add(lines[i].trim())
                                }
                            }
                            message.setEncryptedBody(Joiner.on('\n').join(encryptedMessageBody))
                            message.setEncryption(OmemoMessage.ENCRYPTION_DECRYPTED)
                            store.sendMessage(message)
                            callback.success(message)
                        } catch (e: IOException) {
                            callback.error(R.string.openpgp_error, message)
                        }
                    }
                    OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED ->
                        callback.userInputRequired(
                            result.getParcelableExtra<PendingIntent>(OpenPgpApi.RESULT_INTENT),
                            message
                        )
                    OpenPgpApi.RESULT_CODE_ERROR -> {
                        val error =
                            result.getParcelableExtra<OpenPgpError>(OpenPgpApi.RESULT_ERROR)
                        val errorMessage = error?.message
                        @StringRes val res: Int =
                            if (errorMessage != null &&
                                errorMessage.startsWith("Bad key for encryption")
                            ) {
                                R.string.bad_key_for_encryption
                            } else {
                                R.string.openpgp_error
                            }
                        logError(omemoAccount, error)
                        callback.error(res, message)
                    }
                }
            }
        } else {
            try {
                val inputFile = store.getFile(message, true)
                val outputFile = store.getFile(message, false)
                outputFile.getParentFile().mkdirs()
                outputFile.createNewFile()
                val isStream: InputStream = FileInputStream(inputFile)
                val os: OutputStream = FileOutputStream(outputFile)
                requireApi().executeApiAsync(params, isStream, os) { result ->
                    when (
                        result.getIntExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR)
                    ) {
                        OpenPgpApi.RESULT_CODE_SUCCESS -> {
                            try {
                                os.flush()
                            } catch (ignored: IOException) {
                                //ignored
                            }
                            store.close(os)
                            store.sendMessage(message)
                            callback.success(message)
                        }
                        OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED ->
                            callback.userInputRequired(
                                result.getParcelableExtra<PendingIntent>(OpenPgpApi.RESULT_INTENT),
                                message
                            )
                        OpenPgpApi.RESULT_CODE_ERROR -> {
                            logError(
                                omemoAccount,
                                result.getParcelableExtra<OpenPgpError>(OpenPgpApi.RESULT_ERROR)
                            )
                            callback.error(R.string.openpgp_error, message)
                        }
                    }
                }
            } catch (e: IOException) {
                callback.error(R.string.openpgp_error, message)
            }
        }
    }

    fun fetchKeyId(account: OmemoAccount, status: String?, signature: String?): Long {
        if (signature == null || api == null) {
            return 0L
        }
        val params = Intent()
        params.setAction(OpenPgpApi.ACTION_DECRYPT_VERIFY)
        try {
            params.putExtra(OpenPgpApi.RESULT_DETACHED_SIGNATURE, AsciiArmor.decode(signature))
        } catch (e: Exception) {
            Log.d(Config.LOGTAG, "unable to parse signature", e)
            return 0L
        }
        val isStream: InputStream =
            ByteArrayInputStream(Strings.nullToEmpty(status).toByteArray(Charset.defaultCharset()))
        val os = ByteArrayOutputStream()
        val result = requireApi().executeApi(params, isStream, os)
        when (result.getIntExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR)) {
            OpenPgpApi.RESULT_CODE_SUCCESS -> {
                val sigResult =
                    result.getParcelableExtra<OpenPgpSignatureResult>(
                        OpenPgpApi.RESULT_SIGNATURE
                    )
                //TODO unsure that sigResult.getResult() is either 1, 2 or 3
                return if (sigResult != null) {
                    sigResult.keyId
                } else {
                    0L
                }
            }
            OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED -> return 0L
            OpenPgpApi.RESULT_CODE_ERROR -> {
                logError(account, result.getParcelableExtra<OpenPgpError>(OpenPgpApi.RESULT_ERROR))
                return 0L
            }
        }
        return 0L
    }

    fun <T : OmemoAccount> chooseKey(account: T, callback: PgpCallback<T>) {
        val p = Intent()
        p.setAction(OpenPgpApi.ACTION_GET_SIGN_KEY_ID)
        requireApi().executeApiAsync(p, null, null) { result ->
            when (result.getIntExtra(OpenPgpApi.RESULT_CODE, 0)) {
                OpenPgpApi.RESULT_CODE_SUCCESS -> callback.success(account)
                OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED ->
                    callback.userInputRequired(
                        result.getParcelableExtra<PendingIntent>(OpenPgpApi.RESULT_INTENT),
                        account
                    )
                OpenPgpApi.RESULT_CODE_ERROR -> {
                    logError(
                        account,
                        result.getParcelableExtra<OpenPgpError>(OpenPgpApi.RESULT_ERROR)
                    )
                    callback.error(R.string.openpgp_error, account)
                }
            }
        }
    }

    fun generateSignature(
        intent: Intent?,
        account: OmemoAccount,
        status: String,
        callback: PgpCallback<String>
    ) {
        if (account.getPgpId() == 0L) {
            return
        }
        val params = intent ?: Intent()
        params.setAction(OpenPgpApi.ACTION_CLEARTEXT_SIGN)
        params.putExtra(OpenPgpApi.EXTRA_REQUEST_ASCII_ARMOR, true)
        params.putExtra(OpenPgpApi.EXTRA_SIGN_KEY_ID, account.getPgpId())
        val isStream: InputStream =
            ByteArrayInputStream(status.toByteArray(Charset.defaultCharset()))
        val os: OutputStream = ByteArrayOutputStream()
        Log.d(
            Config.LOGTAG,
            account.getJid().asBareJid().toString() + ": signing status message \"" + status + "\""
        )
        requireApi().executeApiAsync(params, isStream, os) { result ->
            when (result.getIntExtra(OpenPgpApi.RESULT_CODE, 0)) {
                OpenPgpApi.RESULT_CODE_SUCCESS -> {
                    val signature = ArrayList<String>()
                    try {
                        os.flush()
                        var sig = false
                        for (line in Splitter.on('\n').split(os.toString())) {
                            if (sig) {
                                if (line.contains("END PGP SIGNATURE")) {
                                    sig = false
                                } else {
                                    if (!line.contains("Version")) {
                                        signature.add(line.trim())
                                    }
                                }
                            }
                            if (line.contains("BEGIN PGP SIGNATURE")) {
                                sig = true
                            }
                        }
                    } catch (e: IOException) {
                        callback.error(R.string.openpgp_error, null)
                        return@executeApiAsync
                    }
                    callback.success(Joiner.on('\n').join(signature))
                }
                OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED ->
                    callback.userInputRequired(
                        result.getParcelableExtra<PendingIntent>(OpenPgpApi.RESULT_INTENT),
                        status
                    )
                OpenPgpApi.RESULT_CODE_ERROR -> {
                    val error = result.getParcelableExtra<OpenPgpError>(OpenPgpApi.RESULT_ERROR)
                    if (error != null && "signing subkey not found!" == error.message) {
                        callback.error(0, null)
                    } else {
                        logError(account, error)
                        callback.error(uk.xa0.tulkki.xmpp.R.string.unable_to_connect_to_keychain, null)
                    }
                }
            }
        }
    }

    fun <T : OmemoContact> hasKey(contact: T, callback: PgpCallback<T>) {
        val params = Intent()
        params.setAction(OpenPgpApi.ACTION_GET_KEY)
        params.putExtra(OpenPgpApi.EXTRA_KEY_ID, contact.getPgpKeyId())
        requireApi().executeApiAsync(params, null, null) { result ->
            when (result.getIntExtra(OpenPgpApi.RESULT_CODE, 0)) {
                OpenPgpApi.RESULT_CODE_SUCCESS -> callback.success(contact)
                OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED ->
                    callback.userInputRequired(
                        result.getParcelableExtra<PendingIntent>(OpenPgpApi.RESULT_INTENT),
                        contact
                    )
                OpenPgpApi.RESULT_CODE_ERROR -> {
                    logError(
                        contact.getAccount(),
                        result.getParcelableExtra<OpenPgpError>(OpenPgpApi.RESULT_ERROR)
                    )
                    callback.error(R.string.openpgp_error, contact)
                }
            }
        }
    }

    override fun getIntentForKey(pgpKeyId: Long): PendingIntent? {
        val params = Intent()
        params.setAction(OpenPgpApi.ACTION_GET_KEY)
        params.putExtra(OpenPgpApi.EXTRA_KEY_ID, pgpKeyId)
        val outputStream = ByteArrayOutputStream()
        val inputStream = ByteArrayInputStream(ByteArray(0))
        val result = requireApi().executeApi(params, inputStream, outputStream)
        return result.getParcelableExtra<PendingIntent>(OpenPgpApi.RESULT_INTENT)
    }

    // -- uk.xa0.tulkki.xmpp.services.PgpEnginePort: the island's half of this class ----------------------
    //
    // The island may name neither PgpCallback (a :crypto port) nor the Omemo* types the generic
    // bounds ride on, so the port takes the callback as an Object and these adapters cast it back.
    // fetchKeyId's two overloads differ in a parameter's type, which is what keeps them apart.

    override fun fetchKeyId(account: Any?, status: String?, signature: String?): Long =
        fetchKeyId(account as OmemoAccount, status, signature)

    override fun <T> encrypt(message: T, callback: Any?) {
        encrypt(message as OmemoMessage, callback as PgpCallback<OmemoMessage>)
    }

    override fun <T> chooseKey(account: T, callback: Any?) {
        chooseKey(account as OmemoAccount, callback as PgpCallback<OmemoAccount>)
    }

    override fun <T> hasKey(contact: T, callback: Any?) {
        hasKey(contact as OmemoContact, callback as PgpCallback<OmemoContact>)
    }

    override fun generateSignature(
        intent: Intent?,
        account: Any?,
        status: String,
        callback: Any?
    ) {
        generateSignature(
            intent,
            account as OmemoAccount,
            status,
            callback as PgpCallback<String>
        )
    }

    companion object {

        private fun logError(account: OmemoAccount, error: OpenPgpError?) {
            if (error != null) {
                error.describeContents()
                Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid().toString() +
                        ": OpenKeychain error '" + error.message + "' code=" + error.errorId +
                        " class=" + error.javaClass.name
                )
            } else {
                Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid().toString() + ": OpenKeychain error with no message"
                )
            }
        }
    }
}
