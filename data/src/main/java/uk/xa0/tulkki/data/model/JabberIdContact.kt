package uk.xa0.tulkki.data.model

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Build
import android.provider.ContactsContract
import android.util.Log
import java.util.Collections
import java.util.HashMap
import uk.xa0.tulkki.android.AbstractPhoneContact
import uk.xa0.tulkki.data.utils.ContactListIntegration
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid

/**
 * One address-book entry whose IM field is a Jabber id.
 *
 * <p>Ported from Java by the `port` stage (port-2). The decisions, recorded rather than inherited:
 *
 * 1. **It still `extends AbstractPhoneContact`** - the family port-13 moved into the island
 *    (`uk.xa0.tulkki.android`), where it takes the place of the deleted `PhoneContactRef` marker;
 *    `JabberIdRef`, which used to sit between the two, is gone (point 6) - and its private
 *    constructor keeps Java's shape: `super(cursor)` first, then the Jabber id parsed out of the
 *    cursor's `Im.DATA` column. Kotlin has no multi-catch, so
 *    `catch (IllegalArgumentException | NullPointerException e)` becomes two branches that both
 *    `throw IllegalArgumentException(e)` - the same exception with the same cause, which is what the
 *    field initialiser needs because every path must produce a `Jid`.
 * 2. **The three private statics become companion `private val`s.** `PROJECTION` and `SELECTION_ARGS`
 *    are `arrayOf(...)`; `SELECTION` is built by concatenation and **cannot** be a Kotlin `const val`,
 *    because `ContactsContract`'s constants are Java `static final` fields rather than Kotlin
 *    compile-time constants. Java's `String.valueOf(int)` is Kotlin's `.toString()`, which is the
 *    same call. No Java caller names any of the three.
 * 3. **`load` is a companion `@JvmStatic`, and that is the file's whole interop debt**, because
 *    `DataStaticsHost:492` calls `JabberIdContact.load(context)`. **Interop debt: one `@JvmStatic`.**
 * 4. **The try-with-resources becomes `use`, with Java's null check *inside* the block.** Java's
 *    `if (cursor == null) return Collections.emptyMap();` sits inside the resource block, so a null
 *    cursor answers an empty map and not `null`; `cursor.use { }` on the nullable result keeps that,
 *    and the outer `catch (Exception)` still wraps the query and the close exactly as Java's did.
 * 5. **The return stays `Map<Jid, JabberIdContact>`.** Kotlin's `Map` writes the same
 *    `java.util.Map<Jid, JabberIdContact>` signature Java's did, so `DataStaticsHost`'s
 *    `Map<Jid, ? extends AbstractPhoneContact>` assignment is unchanged.
 *
 * 6. **The map is keyed by each entry's own JID, and that is the whole contract now.** port-13
 *    deleted `JabberIdRef` on this fact: with the value only the family marker, the island's merge
 *    looks the roster contact up by `entry.getKey()`, so `key == value.getJid()` is what makes the
 *    swap behaviourless. `JabberIdContactMapKeyTest` pins the keying as a source pin, because `load`
 *    cannot run off-device - a `ContentResolver` behind `READ_CONTACTS`, on a `Cursor` whose
 *    constructor is private.
 *
 * `rating()` is inherited from [AbstractPhoneContact]; nothing in the tree extends `JabberIdContact`,
 * so Kotlin's implicit `final` is Java's own shape.
 */
class JabberIdContact private constructor(cursor: Cursor) : AbstractPhoneContact(cursor) {

    private val jid: Jid =
        try {
            Jid.of(
                cursor.getString(
                    cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Im.DATA),
                ),
            )
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(e)
        } catch (e: NullPointerException) {
            throw IllegalArgumentException(e)
        }

    fun getJid(): Jid = jid

    companion object {

        private val PROJECTION =
            arrayOf(
                ContactsContract.Data._ID,
                ContactsContract.Data.DISPLAY_NAME,
                ContactsContract.Data.PHOTO_URI,
                ContactsContract.Data.LOOKUP_KEY,
                ContactsContract.CommonDataKinds.Im.DATA,
            )

        private val SELECTION: String =
            ContactsContract.Data.MIMETYPE +
                "=? AND (" +
                ContactsContract.CommonDataKinds.Im.PROTOCOL +
                "=? or (" +
                ContactsContract.CommonDataKinds.Im.PROTOCOL +
                "=? and lower(" +
                ContactsContract.CommonDataKinds.Im.CUSTOM_PROTOCOL +
                ")=?))"

        private val SELECTION_ARGS =
            arrayOf(
                ContactsContract.CommonDataKinds.Im.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Im.PROTOCOL_JABBER.toString(),
                ContactsContract.CommonDataKinds.Im.PROTOCOL_CUSTOM.toString(),
                "xmpp",
            )

        @JvmStatic
        fun load(context: Context): Map<Jid, JabberIdContact> {
            if (!ContactListIntegration.isContactListIntegration(context) ||
                (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
                        PackageManager.PERMISSION_GRANTED
                )
            ) {
                return Collections.emptyMap()
            }
            try {
                return context.getContentResolver()
                    .query(
                        ContactsContract.Data.CONTENT_URI,
                        PROJECTION,
                        SELECTION,
                        SELECTION_ARGS,
                        null,
                    )
                    .use { cursor ->
                        if (cursor == null) {
                            return Collections.emptyMap()
                        }
                        val contacts = HashMap<Jid, JabberIdContact>()
                        while (cursor.moveToNext()) {
                            try {
                                val contact = JabberIdContact(cursor)
                                val preexisting = contacts.put(contact.getJid(), contact)
                                if (preexisting == null ||
                                    preexisting.rating() < contact.rating()
                                ) {
                                    contacts.put(contact.getJid(), contact)
                                }
                            } catch (e: IllegalArgumentException) {
                                Log.d(Config.LOGTAG, "unable to create jabber id contact")
                            }
                        }
                        contacts
                    }
            } catch (e: Exception) {
                Log.d(Config.LOGTAG, "unable to query", e)
                return Collections.emptyMap()
            }
        }
    }
}
