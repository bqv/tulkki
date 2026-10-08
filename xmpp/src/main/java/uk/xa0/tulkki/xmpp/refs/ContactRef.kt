package uk.xa0.tulkki.xmpp.refs

import android.content.Context
import android.telecom.PhoneAccountHandle
import uk.xa0.tulkki.android.AbstractPhoneContact
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.jingle.RtpCapability
import uk.xa0.tulkki.xmpp.pep.Avatar
import uk.xa0.tulkki.xmpp.pep.UserTune
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.libs.PresenceRef
import uk.xa0.tulkki.libs.PresencesRef

/**
 * Tulkki: the XMPP island's view of `uk.xa0.tulkki.data.model.Contact`.
 *
 * Declared in the island and implemented by the model class in `:data`
 * (`docs/WORKSTREAMS.md` round 151). It arrived empty with part 7 - its first consumer,
 * `OnContactStatusChanged`, is signature-only - and grows by ruling 3: every member here is read by a
 * call site, and the `OptionsRef` bits are copied because they are compile-time `int`s with no
 * identity rather than enums with one.
 *
 * Where a `:ui` file has to name this type it must write it **fully qualified in the
 * signature and import nothing** (rounds 151/161, pair 11's four `extends` clauses): an import
 * of an island type in a `:ui` file is one more `ui-reaches-island` site, and that rule is
 * decided before `allow` is consulted, so `allow` cannot legalise it.
 */
interface ContactRef {

    /**
     * Tulkki: the island's view of `uk.xa0.tulkki.data.model.Contact.Options`.
     *
     * The bits are compile-time `int`s, so they are copied rather than mapped: unlike an enum they
     * carry no identity, and `getOption` compares by value. The values are the model's own.
     */
    interface OptionsRef {

        companion object {
            @JvmField
            val TO: Int = 0

            @JvmField
            val FROM: Int = 1

            @JvmField
            val ASKING: Int = 2

            @JvmField
            val PREEMPTIVE_GRANT: Int = 3

            @JvmField
            val IN_ROSTER: Int = 4

            @JvmField
            val PENDING_SUBSCRIPTION_REQUEST: Int = 5

            @JvmField
            val DIRTY_PUSH: Int = 6

            @JvmField
            val DIRTY_DELETE: Int = 7

            @JvmField
            val SYNCED_VIA_OTHER: Int = 9

            @JvmField
            val FOLLOWED: Int = 10

        }

    }

    fun getJid(): Jid

    fun canInferPresence(): Boolean

    fun getOption(option: Int): Boolean

    fun setOption(option: Int)

    fun resetOption(option: Int)

    fun setServerName(serverName: String?)

    fun parseGroupsFromElement(element: Element)

    fun parseSubscriptionFromElement(element: Element)

    fun setLastResource(resource: String?)

    fun isBlocked(): Boolean

    // -- the contact's presence surface ------------------------------------------------------------
    //
    // Grown by part 11 for `PresenceParser` alone, which is the first island file that reads a
    // contact's presences rather than only naming the contact. Every member is a read or a write at a
    // call site in that file; nothing here is added ahead of a consumer.

    /** Return-position drag: the model answers with its own `Presences`, which implements the ref. */
    fun getPresences(): PresencesRef

    /**
     * The overload the island calls; `:data` adapts it to its own `updatePresence(String, Presence)`
     * with one cast, because a class may not have two methods differing only in a parameter type that
     * erases the same way - these do not, so the overload is legal.
     */
    fun updatePresence(resource: String, presence: PresenceRef)

    /** `boolean`, not `void`: the island branches on the answer and the class must mirror it. */
    fun setLastseen(timestamp: Long): Boolean

    fun flagActive()

    fun flagInactive()

    /** `boolean`, not `void`: the class's own declaration, mirrored here. */
    fun setPgpKeyId(keyId: Long): Boolean

    fun clearPresences()

    fun removePresence(resource: String)

    /** `boolean`, not `void`: the class's own declaration, mirrored here. */
    fun setPresenceName(presenceName: String?): Boolean

    /** `boolean`, not `void`: the class's own declaration, mirrored here. */
    fun setAvatar(avatar: Avatar?): Boolean

    fun isSelf(): Boolean

    fun mutualPresenceSubscription(): Boolean

    /**
     * **Distinctly named on purpose** - see [PresenceRef]'s class comment.
     *
     * `PresenceParser` compares the answer with `==` against `PresenceRef.StatusRef.OFFLINE`, and
     * the model's `getShownStatus()` returns the model enum. An island `getShownStatus()` could not be
     * an override of it (same name, same parameters, different return type), so the name adapts and
     * `Contact` supplies the mapping. Identity stays true inside the island and the model enum keeps
     * serving `:ui`/`:app`/`:translation` unchanged.
     */
    fun shownStatus(): PresenceRef.StatusRef

    // -- part 12: the three members `MessageParser` reads on a contact -----------------------------
    //
    // The first island file that asks a contact for its PEP tune, writes one, and asks whether the
    // contact belongs in the list at all (the JMI receipt rule). All three are the model's own
    // declarations; two are already island vocabulary.
    fun getUserTune(): UserTune?

    fun setUserTune(tune: UserTune?)

    fun showInContactList(): Boolean

    // -- part 14: the two refreshes `injectServiceDiscoveryResult` runs after a write ---------------
    //
    // Once the island has stored a discovery result on a contact's presence it re-derives the
    // contact's RTP capability (the model answers `boolean`, and the island ORs it into "this roster
    // needs a write") and then rebuilds its caps. Both are the model's own declarations.
    fun refreshRtpCapability(): Boolean

    fun refreshCaps()

    /**
     * Tulkki: 3.7 C5-A. `RtpCapability.check(Contact, boolean)`'s fallback asks the contact
     * for the capability it cached, and the return type is already island vocabulary - the model's
     * own declaration is `RtpCapability.Capability`, an island enum - so this needs no mapping
     * and no distinct name. The class's declaration is mirrored exactly; it may answer `null`
     * and the island's caller passes that straight through, as it always did.
     */
    fun getRtpCapability(): RtpCapability.Capability?

    // -- part 15: every member `XmppConnectionService` reads on a contact ---------------------------
    //
    // This file (`:xmpp/services`) used to name the model type outright and is now ref-shaped; the
    // list below is exactly the surface the 60-odd sites in it touch, and nothing here is added
    // ahead of one of them. Where a member's return type is a model type that is *still* imported in
    // that file (`Account`) the call site casts back once instead; where the model type is this
    // class's own (`Contact`) the ref has to answer, which is why `getAccount()` is here.
    fun showInRoster(): Boolean

    /** `ContactDetailsActivity` names the contact in a conversation header - a `:ui` read. */
    fun getDisplayName(): String

    /** `getKnownHosts()` collects the servers a contact lives on; also the model's own server name. */
    fun getServer(): String

    /**
     * Tulkki: the model's own accessor, made covariant by `Account implementing AccountRef` -
     * the same shape as `RosterRef.getAccount()`.
     */
    fun getAccount(): AccountRef

    /** The roster item the roster write sends; the model's own `Element`. */
    fun asElement(): Element

    /** `boolean`, not `void`: `verifyFingerprints` ORs the answer into "this roster needs a write". */
    fun addOtrFingerprint(fingerprint: String): Boolean

    /** The calls switch: `updateContact` reads the edited boolean and writes it to the live one. */
    fun areCallsDisabled(): Boolean

    fun setCallsDisabled(callsDisabled: Boolean)

    /** The two phone-account actions `loadPhoneContacts`/`unregisterPhoneAccounts` run. */
    fun registerAsPhoneAccount(ctx: XmppConnectionService)

    fun unregisterAsPhoneAccount(ctx: Context)

    /**
     * Tulkki: 3.7 C5-E4. `CallIntegrationConnectionService.findPhoneAccount` walks
     * `id.account.getRoster().getContacts()` asking each PSTN-gateway contact for its OS phone
     * account; with `Id.account` an island ref that walk is over `ContactRef`s. The member is the
     * model's own declaration (`Contact.java:787`) and its return type is an Android telecom type
     * rather than a `:data` one, so `Contact` satisfies it with no body.
     */
    fun phoneAccountHandle(): PhoneAccountHandle

    /**
     * Tulkki: the phone-contact merge, in island vocabulary.
     *
     * port-13 deleted `PhoneContactRef`, the marker that stood here while
     * `AbstractPhoneContact` lived in `:data`; the class moved into the island
     * (`uk.xa0.tulkki.android`) and the member now names it directly. The member is an
     * **override** of the model's own `setPhoneContact(AbstractPhoneContact)`: with the ref gone the
     * two signatures are one, so `Contact` carries a single `override` and no forward cast.
     */
    fun setPhoneContact(phoneContact: AbstractPhoneContact): Boolean

    /**
     * Tulkki: C5-E2 - the second, named half of the address-book merge, replacing the
     * `unsetPhoneContactOf(Class<? extends AbstractPhoneContact>)` member this slice deleted.
     *
     * The class parameter was the defect: `Contact.unsetPhoneContact(Class)` keys the option bit on
     * the **class object**, so the island could only have satisfied it by passing a ref's own
     * `.class`, which compiles and silently clears the wrong bit (`Contact.getOption(Class)` maps
     * `JabberIdContact.class` to `SYNCED_VIA_ADDRESS_BOOK` and everything else to `SYNCED_VIA_OTHER`).
     * The island asks the named question and `Contact` answers it with its own class literal.
     */
    fun unsetJabberIdPhoneContact(): Boolean

    /** The two-argument form, used where a PEP avatar was previously omitted. */
    fun setAvatar(avatar: Avatar?, previouslyOmittedPepFetch: Boolean): Boolean
}
