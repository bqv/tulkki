package uk.xa0.tulkki.data.translation

import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Message

/**
 * `translation/`'s write-back statements (S5-6): the three translation columns of one message, and the
 * detected language of one conversation.
 *
 * <p>**Why these two tables are one object.** Both are the same job - writing back into a row the
 * interface already has loaded, so a screen redraws without a reload - and both are addressed by the
 * row's own key. The message half updates exactly the three translation columns **by uuid and nothing
 * else**, which is what stops a write-back clobbering the rest of a row it did not read; the
 * conversation half updates one column by the conversation's uuid, for the same reason.
 *
 * <p>`TranslationStore` composed all four statements itself out of `TranslationTables`' and the
 * models' own names, holding the writable handle to do it; this is the write-back slice of
 * `docs/MIGRATION.md`, "Design: the data layer" §6 step 6, and [MessageTranslationStore] is the only
 * caller.
 */
internal object MessageTranslationQueries {

    const val MESSAGE_TABLE = Message.TABLENAME
    const val MESSAGE_UUID = Message.UUID
    const val TRANSLATED_BODY = Message.TRANSLATED_BODY
    const val TRANSLATION_LANG = Message.TRANSLATION_LANG
    const val TRANSLATION_STATE = Message.TRANSLATION_STATE

    /** The state of a message that carries no translation: what `forget` writes back. */
    const val TRANSLATION_NONE = Message.TRANSLATION_NONE

    const val CONVERSATION_TABLE = Conversation.TABLENAME
    const val CONVERSATION_UUID = Conversation.UUID
    const val DETECTED_LANGUAGE = Conversation.DETECTED_LANGUAGE

    /**
     * One message's answer, by uuid. The three columns are the whole statement: a write-back that
     * named a fourth would be the one way this path could clobber a row it never read.
     */
    @JvmField
    val WRITE_TRANSLATION: String =
        "UPDATE " +
            MESSAGE_TABLE +
            " SET " +
            TRANSLATED_BODY +
            " = ?, " +
            TRANSLATION_LANG +
            " = ?, " +
            TRANSLATION_STATE +
            " = ? WHERE " +
            MESSAGE_UUID +
            " = ?"

    /**
     * One message's stored translation goes, and nothing else does: an edit replaced the text the
     * translation was made from, so keeping it would show the owner a rendering of words that are no
     * longer there. The message's own text is untouched - this is about the translation, not the
     * message.
     */
    @JvmField
    val CLEAR_TRANSLATION: String =
        "UPDATE " +
            MESSAGE_TABLE +
            " SET " +
            TRANSLATED_BODY +
            " = NULL, " +
            TRANSLATION_LANG +
            " = NULL, " +
            TRANSLATION_STATE +
            " = " +
            TRANSLATION_NONE +
            " WHERE " +
            MESSAGE_UUID +
            " = ?"

    /** The conversation's own detected language, or no row when it has none yet. */
    @JvmField
    val READ_CONVERSATION_LANGUAGE: String =
        "SELECT " + DETECTED_LANGUAGE + " FROM " + CONVERSATION_TABLE + " WHERE " + CONVERSATION_UUID + " = ?"

    /** The conversation's detected language, written back: one column, by the conversation's uuid. */
    @JvmField
    val WRITE_CONVERSATION_LANGUAGE: String =
        "UPDATE " +
            CONVERSATION_TABLE +
            " SET " +
            DETECTED_LANGUAGE +
            " = ? WHERE " +
            CONVERSATION_UUID +
            " = ?"
}
