package uk.xa0.tulkki.xmpp

/**
 * Tulkki: a block-list change, pushed to the UI.
 *
 * Ported from `OnUpdateBlocklist.java`. The nested
 * `Status` enum keeps its two constants and its binary name (`OnUpdateBlocklist$Status`), and the
 * method deliberately keeps Java's same-name-as-the-class spelling - the `@Suppress` that carried
 * the lint id in Java is kept verbatim; Kotlin treats the id as opaque but the reason is unchanged.
 * Six `:ui` activities and `XmppConnectionService.updateBlocklistUi`/`IqParser` call it, always
 * with a non-null constant.
 */
interface OnUpdateBlocklist {
    // Use an enum instead of a boolean to make sure we don't run into the boolean trap
    // (`onUpdateBlocklist(true)' doesn't read well, and could be confusing).
    enum class Status {
        BLOCKED,
        UNBLOCKED,
    }

    @Suppress("MethodNameSameAsClassName")
    fun OnUpdateBlocklist(status: Status)
}
