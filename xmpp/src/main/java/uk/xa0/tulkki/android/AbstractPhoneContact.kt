package uk.xa0.tulkki.android

import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.text.TextUtils

/**
 * The two fields an address-book row carries into the roster: where it lives and what it is called.
 *
 * <p>Ported from Java by the `port` stage (`port-5`, the `:data.model` row; the same model file set
 * `port-2`'s "31 of 78" carries). The decisions, recorded rather than inherited:
 *
 * 1. **All three reads are nullable, and that is the Java callers' own width.** `Contact.kt:762-764`
 *    feeds them to `setSystemAccount(Uri?)`, `setSystemName(String?)` and `setPhotoUri(String?)`,
 *    all nullable; `PhoneNumberContact.findByUri` compares `needle == contact.getLookupUri()`; and
 *    `ContactsContract.Contacts.getLookupUri` answers `null` for an empty lookup key, which is a row
 *    the cursor reader must survive. Java declared `String`/`Uri` and carried the null anyway.
 * 2. **[rating] stays `open`** - Java's method was not final and the class is extended by
 *    `JabberIdContact` and `:app`'s `PhoneNumberContact`, neither of which overrides it - and its
 *    body is Java's `TextUtils.isEmpty` arithmetic, which tolerates both nulls.
 * 3. **The constructor stays `protected`**, as Java's was: both subclasses call `super(cursor)`, and
 *    one of them lives in `:app`, where a Kotlin `protected` primary constructor is still reachable
 *    from a subclass. Java's `int` row id widened to `getLookupUri`'s `long` implicitly; Kotlin
 *    spells that widening `.toLong()`.
 * 4. **No statics, so no `@JvmStatic`; nothing here is an override.** Upstream names this class
 *    `eu.siacs.conversations.android.AbstractPhoneContact`; under the rename that is
 *    `uk.xa0.tulkki.android.AbstractPhoneContact`, and port-13's `PhoneContactRef` slice moved it
 *    here from `uk.xa0.tulkki.data.model`.
 *
 * <p>**Why it lives in the island now.** `PhoneContactRef` existed only because the model was in
 * `:data`: the island's `XmppConnectionService.loadPhoneContacts` receives the family from
 * `DataStatics.loadJabberIdContacts` and hands each entry straight back to
 * `ContactRef.setPhoneContact`, reading no member of it. Once the class is an island type the island
 * can name it directly, so the marker is deleted and `ContactRef.setPhoneContact` takes this class.
 * `JabberIdContact` (`:data`) and `:app`'s `PhoneNumberContact` are its subclasses; nothing in `:ui`
 * named either the ref or this class, which is what made the move mechanical.
 */
abstract class AbstractPhoneContact protected constructor(cursor: Cursor) {

    private val lookupUri: Uri?
    private val displayName: String?
    private val photoUri: String?

    init {
        val phoneId = cursor.getInt(cursor.getColumnIndex(ContactsContract.Data._ID))
        val lookupKey = cursor.getString(cursor.getColumnIndex(ContactsContract.Data.LOOKUP_KEY))
        lookupUri = ContactsContract.Contacts.getLookupUri(phoneId.toLong(), lookupKey)
        displayName = cursor.getString(cursor.getColumnIndex(ContactsContract.Data.DISPLAY_NAME))
        photoUri = cursor.getString(cursor.getColumnIndex(ContactsContract.Data.PHOTO_URI))
    }

    fun getLookupUri(): Uri? = lookupUri

    fun getDisplayName(): String? = displayName

    fun getPhotoUri(): String? = photoUri

    open fun rating(): Int =
        (if (TextUtils.isEmpty(displayName)) 0 else 2) + (if (TextUtils.isEmpty(photoUri)) 0 else 1)
}
