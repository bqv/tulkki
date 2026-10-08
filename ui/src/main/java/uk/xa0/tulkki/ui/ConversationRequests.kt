package uk.xa0.tulkki.ui

/**
 * The conversation screen's request codes, its attachment choices and the quick-action preference
 * key.
 *
 * <p>They were `public static final` fields on `ConversationFragment`, which made the whole class
 * the contract: `SendButtonAction` imported five attachment choices, `TrustKeysActivity` one more,
 * `SendButtonTool` the quick-action key and `ConversationListActivity` two request codes. A class
 * that is 7,300 lines of screen cannot also be the module's constants table, so the codes live here
 * as Kotlin `const val`s - static fields on the JVM, so a Java caller still reads them unqualified
 * through a static import and no compiled shape moved.
 *
 * <p>The names are the ones that were there. `REQUEST_*` is a request code handed back to the
 * activity's `onActivityResult`, `ATTACHMENT_CHOICE_*` is one arm of the attach menu, and the two
 * families are kept apart deliberately: `0x02xx` and `0x03xx` never collide, which is what lets one
 * `onActivityResult` switch read both.
 *
 * @see ConversationLookup
 */
object ConversationRequests {
    const val REQUEST_TRUST_KEYS_NONE = 0x0
    const val REQUEST_SEND_MESSAGE = 0x0201
    const val REQUEST_DECRYPT_PGP = 0x0202
    const val REQUEST_ENCRYPT_MESSAGE = 0x0207
    const val REQUEST_TRUST_KEYS_TEXT = 0x0208
    const val REQUEST_TRUST_KEYS_ATTACHMENTS = 0x0209
    const val REQUEST_START_DOWNLOAD = 0x0210
    const val REQUEST_ADD_EDITOR_CONTENT = 0x0211
    const val REQUEST_COMMIT_ATTACHMENTS = 0x0212
    const val REQUEST_START_AUDIO_CALL = 0x213
    const val REQUEST_START_VIDEO_CALL = 0x214
    const val REQUEST_PICK_DATE = 0x0215

    const val ATTACHMENT_CHOICE_CHOOSE_IMAGE = 0x0301
    const val ATTACHMENT_CHOICE_TAKE_PHOTO = 0x0302
    const val ATTACHMENT_CHOICE_CHOOSE_FILE = 0x0303
    const val ATTACHMENT_CHOICE_RECORD_VOICE = 0x0304
    const val ATTACHMENT_CHOICE_LOCATION = 0x0305
    const val ATTACHMENT_CHOICE_INVALID = 0x0306
    const val ATTACHMENT_CHOICE_RECORD_VIDEO = 0x0307
    const val ATTACHMENT_CHOICE_EDIT_PHOTO = 0x0308
    const val ATTACHMENT_CHOICE_LIVE_LOCATION = 0x0309

    /**
     * The last arm the send button resolved, remembered under this key so `quick_action` `recent`
     * can offer it again. The value is a `SendButtonAction` name; a stale or unknown one falls back
     * to [SendButtonAction.TEXT].
     */
    const val RECENTLY_USED_QUICK_ACTION = "recently_used_quick_action"
}
