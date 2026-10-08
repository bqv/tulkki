package uk.xa0.tulkki.ui.pinnedmessage

import android.content.Context
import android.database.Cursor
import android.util.Log

import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.reflect.TypeToken

import io.ipfs.cid.Cid

import uk.xa0.tulkki.data.DatabaseBackend
import uk.xa0.tulkki.data.model.PinnedMessage

import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.lang.reflect.Type
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class PinnedMessageRepository(context: Context) {

    private val context: Context = context.getApplicationContext()
    private val executorService: ExecutorService = Executors.newSingleThreadExecutor()

    init {
        migrateFromJsonToDb()
    }

    private fun getDatabaseBackend(): DatabaseBackend {
        return DatabaseBackend.getInstance(context)
    }

    private fun migrateFromJsonToDb() {
        executorService.submit(Runnable {
            val file = File(context.getFilesDir(), PINNED_MESSAGES_FILE_V2)
            if (file.exists()) {
                Log.i(TAG, "Migrating pinned messages from JSON to DB.")
                try {
                    FileInputStream(file).use { fis ->
                        InputStreamReader(fis, StandardCharsets.UTF_8).use { reader ->
                            val gsonBuilder = GsonBuilder()
                            gsonBuilder.registerTypeHierarchyAdapter(
                                ByteArray::class.java, ByteArrayToBase64TypeAdapter())
                            val gson = gsonBuilder.create()

                            val listType: Type = object : TypeToken<ArrayList<PinnedMessage>>() {}.type
                            val loadedMessages: List<PinnedMessage>? = gson.fromJson(reader, listType)

                            if (loadedMessages != null) {
                                for (pm in loadedMessages) {
                                    var decryptedText: String? = null
                                    if (pm.encryptedContent != null && pm.iv != null) {
                                        val decryptedBytes = CryptoUtils.decrypt(pm.iv, pm.encryptedContent)
                                        if (decryptedBytes != null) {
                                            decryptedText = String(decryptedBytes, StandardCharsets.UTF_8)
                                        }
                                    }
                                    val accountUuid =
                                        getDatabaseBackend().getAccountUuidForConversation(pm.conversationUuid)
                                    getDatabaseBackend().pinMessage(
                                        pm.messageUuid,
                                        pm.conversationUuid,
                                        accountUuid,
                                        decryptedText,
                                        pm.cid?.toString(),
                                        pm.timestamp)
                                }
                                Log.i(TAG, "Successfully migrated " + loadedMessages.size + " pinned messages.")
                            }
                            if (!file.delete()) {
                                Log.w(TAG, "Failed to delete old pinned messages JSON file.")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during migration from JSON to DB", e)
                }
            }
        })
    }

    fun pinMessage(
        messageUuid: String,
        conversationUuid: String,
        plaintextBody: String?,
        cid: Cid?,
        listener: OnPinCompleteListener?
    ) {
        executorService.submit(Runnable {
            try {
                val accountUuid = getDatabaseBackend().getAccountUuidForConversation(conversationUuid)
                getDatabaseBackend().pinMessage(
                    messageUuid, conversationUuid, accountUuid, plaintextBody, cid?.toString(),
                    System.currentTimeMillis())
                listener?.onPinComplete(true)
            } catch (e: Exception) {
                Log.e(TAG, "Error pinning message", e)
                listener?.onPinComplete(false)
            }
        })
    }

    fun unpinMessage(messageUuid: String, listener: OnUnpinCompleteListener?) {
        executorService.submit(Runnable {
            try {
                getDatabaseBackend().unpinMessage(messageUuid)
                listener?.onUnpinComplete(true)
            } catch (e: Exception) {
                Log.e(TAG, "Error unpinning message", e)
                listener?.onUnpinComplete(false)
            }
        })
    }

    fun getAllDecryptedPinnedMessagesForConversation(conversationUuid: String): List<DecryptedPinnedMessageData> {
        val result = ArrayList<DecryptedPinnedMessageData>()
        try {
            getDatabaseBackend().getPinnedMessages(conversationUuid)?.use { cursor ->
                while (cursor.moveToNext()) {
                    result.add(fromCursor(cursor))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting all pinned messages", e)
        }
        return result
    }

    private fun fromCursor(cursor: Cursor): DecryptedPinnedMessageData {
        val messageUuid = cursor.getString(cursor.getColumnIndexOrThrow(PinnedMessage.MESSAGE_UUID))
        val conversationUuid = cursor.getString(cursor.getColumnIndexOrThrow(PinnedMessage.CONVERSATION_UUID))
        val body = cursor.getString(cursor.getColumnIndexOrThrow(PinnedMessage.BODY))
        val timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(PinnedMessage.TIMESTAMP))
        val cidString = cursor.getString(cursor.getColumnIndexOrThrow(PinnedMessage.CID))
        var cid: Cid? = null
        if (cidString != null) {
            try {
                cid = Cid.decode(cidString)
            } catch (ignored: Exception) {
            }
        }
        return DecryptedPinnedMessageData(messageUuid, conversationUuid, body, timestamp, cid)
    }

    interface OnPinCompleteListener {
        fun onPinComplete(success: Boolean)
    }

    interface OnUnpinCompleteListener {
        fun onUnpinComplete(success: Boolean)
    }

    class DecryptedPinnedMessageData(
        @JvmField val messageUuid: String,
        @JvmField val conversationUuid: String,
        @JvmField val plaintextBody: String?,
        @JvmField val timestamp: Long,
        @JvmField val cid: Cid?,
    )

    private class ByteArrayToBase64TypeAdapter :
        JsonSerializer<ByteArray>, JsonDeserializer<ByteArray> {

        override fun deserialize(
            json: JsonElement,
            typeOfT: Type,
            context: JsonDeserializationContext
        ): ByteArray {
            return android.util.Base64.decode(json.asString, android.util.Base64.NO_WRAP)
        }

        override fun serialize(
            src: ByteArray,
            typeOfSrc: Type,
            context: JsonSerializationContext
        ): JsonElement {
            return JsonPrimitive(android.util.Base64.encodeToString(src, android.util.Base64.NO_WRAP))
        }
    }

    companion object {
        private const val TAG = "PinnedMsgRepo"
        private const val PINNED_MESSAGES_FILE_V2 = "pinned_messages_v2.enc.json"
    }
}
