package uk.xa0.tulkki.data.model

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * One edit of a message: the id the edit replaced and the server id it arrived as.
 *
 * <p>Ported from Java by the `port` stage (`port-5`, the `:data.model` row; the same model file set
 * `port-2`'s "31 of 78" carries). The decisions, recorded rather than inherited:
 *
 * 1. **The statics and the constructor keep Java's package-private shape as `internal`.** Java's
 *    `Edit(...)` and `toJson`/`wasPreviouslyEdited*`/`fromJson` were package-private; Kotlin has no
 *    package-private, so the constructor and the four statics are `internal` - `Message.kt`, in this
 *    package and this module, is their only caller (`putEdited` builds one; the instance `toJson`
 *    stays `private`).
 * 2. **No `@JvmStatic`.** Measured: no Java file names `Edit` at all, so the static shape carries
 *    no interop debt. **Interop debt: zero annotations.**
 * 3. **Both fields stay nullable, and [getEditedId] is nullable with them.** [fromJson] puts `null`
 *    into either when the JSON has no key, and the instance [toJson] writes that `null` back through
 *    `JSONObject.put`, which removes the key. Java's `getEditedId()` returned that `null`, and
 *    `Conversation.kt:2058` compares it null-safely (`id == itm.getEditedId()`), so the getter is
 *    `String?`. `Message.getEditedId()`/`getEditedIdWireFormat()` carried Kotlin's platform check on
 *    the old return (measured: `checkNotNullExpressionValue` in the pre-port bytecode) and keep that
 *    NPE with an explicit `!!` - the only two lines this conversion changes outside `Edit.kt`.
 * 4. **[equals] keeps Java's `getClass()` check and its null-tolerant field comparison**, and
 *    [hashCode] keeps Java's `31 *` arithmetic rather than `Objects.hash`, which is a different
 *    number.
 */
class Edit internal constructor(
    private val editedId: String?,
    private val serverMsgId: String?,
) {

    fun getEditedId(): String? = editedId

    private fun toJson(): JSONObject {
        val jsonObject = JSONObject()
        jsonObject.put("edited_id", editedId)
        jsonObject.put("server_msg_id", serverMsgId)
        return jsonObject
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false

        val edit = other as Edit

        if (editedId != null) {
            if (editedId != edit.editedId) return false
        } else if (edit.editedId != null) {
            return false
        }
        return if (serverMsgId != null) serverMsgId == edit.serverMsgId else edit.serverMsgId == null
    }

    override fun hashCode(): Int {
        var result = editedId?.hashCode() ?: 0
        result = 31 * result + (serverMsgId?.hashCode() ?: 0)
        return result
    }

    companion object {

        internal fun toJson(edits: List<Edit>): String {
            val jsonArray = JSONArray()
            for (edit in edits) {
                jsonArray.put(edit.toJson())
            }
            return jsonArray.toString()
        }

        internal fun wasPreviouslyEditedRemoteMsgId(edits: List<Edit>, remoteMsgId: String?): Boolean {
            for (edit in edits) {
                if (edit.editedId != null && edit.editedId == remoteMsgId) {
                    return true
                }
            }
            return false
        }

        internal fun wasPreviouslyEditedServerMsgId(edits: List<Edit>, serverMsgId: String?): Boolean {
            for (edit in edits) {
                if (edit.serverMsgId != null && edit.serverMsgId == serverMsgId) {
                    return true
                }
            }
            return false
        }

        private fun fromJson(jsonObject: JSONObject): Edit {
            val edited = if (jsonObject.has("edited_id")) jsonObject.getString("edited_id") else null
            val serverMsgId = if (jsonObject.has("server_msg_id")) jsonObject.getString("server_msg_id") else null
            return Edit(edited, serverMsgId)
        }

        internal fun fromJson(input: String?): MutableList<Edit> {
            val list = ArrayList<Edit>()
            if (input == null) {
                return list
            }
            try {
                val jsonArray = JSONArray(input)
                for (i in 0 until jsonArray.length()) {
                    list.add(fromJson(jsonArray.getJSONObject(i)))
                }
                return list
            } catch (e: JSONException) {
                return list
            }
        }
    }
}
