package uk.xa0.tulkki.libs

/**
 * Tulkki: 3.7 C5-E2 - the XMPP island's view of `uk.xa0.tulkki.data.model.PresenceTemplate`.
 *
 * Declared in the island, implemented by the model class in `:data`. Until this slice the island
 * only *held* a presence template (`XmppConnectionService.changeStatus` took one, persisted it and
 * read `getStatusMessage()`), so the type was named by an import and no ref existed. Two more reads
 * pushed it over: `changeStatus` also asks for the template's status, and `getPresenceTemplates`
 * hands a whole list of them to `:ui`'s autocomplete adapter.
 *
 * **Why `:libs` (2026-10-08, lane `G`).** It was `uk.xa0.tulkki.xmpp.refs.PresenceTemplateRef` - the
 * island's name for `:data`'s `PresenceTemplate` while `:xmpp` could not name `:data`. `:libs` is
 * the one module in the order that every side may reach, so the interface moves here and the ref
 * file is its `git rm`; the move is **package-only** and every namer changes only its import line.
 *
 * **Respell from Java (2026-10-08, lane `G`).** Two members. `getStatusRef()` is the model enum in
 * ref form and non-null, and `PresenceTemplate.getStatusMessage()` is **nullable** (`:data`'s row
 * `MESSAGE` is), so the Kotlin member is `String?` - what the Java platform type already tolerated
 * and what the model already declared. Two call sites read it and the Java dereferenced the
 * answer, so both keep that shape: `PresenceTemplateAdapter.kt:31` and `AccountMaintenance.kt:46`
 * are the only two, and `EditAccountActivity.kt:1322` already writes
 * `getStatusMessage() ?: throw NullPointerException()`. The JVM surface is unchanged:
 * `String getStatusMessage()` and `PresenceRef$StatusRef getStatusRef()`.
 */
interface PresenceTemplateRef {

    /** The row's `MESSAGE`, nullable exactly as `PresenceTemplate.getStatusMessage()` declares it. */
    fun getStatusMessage(): String?

    /**
     * **Distinctly named**: `PresenceTemplate.getStatus()` answers the model enum, and a class may
     * not have two methods differing only in return type, so an island override with this name is
     * impossible. `Presence.Status.toRef()` is the mapping, exactly as it is for
     * `AccountRef.getPresenceStatusRef`.
     */
    fun getStatusRef(): PresenceRef.StatusRef
}
