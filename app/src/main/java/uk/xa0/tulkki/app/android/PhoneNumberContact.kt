package uk.xa0.tulkki.app.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import com.google.common.collect.HashMultimap
import com.google.common.collect.ImmutableMap
import com.google.common.collect.Multimap
import io.michaelrocks.libphonenumber.android.NumberParseException
import uk.xa0.tulkki.android.AbstractPhoneContact
import uk.xa0.tulkki.app.utils.PhoneNumberUtilWrapper
import java.util.ArrayList
import java.util.HashMap

/**
 * The device's address book, read once and keyed by normalized phone number.
 *
 * <p>**The four `getX()` methods stay methods.** `ContactListSyncService` (Kotlin) calls
 * `phoneContact.getPhoneNumber()` and `phoneContact.getTags()` on the object, and a Kotlin property
 * cannot be called that way, so the Java's accessors keep their `fun getX()` spelling rather than
 * becoming properties; the private backing fields generate no getters of their own, so there is no
 * platform declaration clash.
 *
 * <p>The constructor's `try` is an `init` block: `phoneNumber`, `typeLabel` and `groups` are `val`s
 * assigned once inside it, and both catch arms throw, which is what satisfies Kotlin's definite
 * assignment. The Java's `NumberParseException | NullPointerException` multi-catch is two identical
 * arms, its usual Kotlin spelling.
 *
 * <p>The three cursors keep try-with-resources as `?.use { ... }` even though the Java's null test
 * (`groups != null && groups.moveToNext()`) made the close a formality; the outer
 * `catch (Exception)` still answers the empty map, exactly as the Java did.
 */
class PhoneNumberContact private constructor(
        context: Context,
        cursor: Cursor,
        groups: Collection<String>
) : AbstractPhoneContact(cursor) {

    private val phoneNumber: String
    private val typeLabel: String
    private val groups: Collection<String>

    init {
        try {
            phoneNumber =
                    PhoneNumberUtilWrapper.normalize(
                            context,
                            cursor.getString(
                                    cursor.getColumnIndex(
                                            ContactsContract.CommonDataKinds.Phone.NUMBER)))
            typeLabel =
                    ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                                    context.resources,
                                    cursor.getInt(
                                            cursor.getColumnIndex(
                                                    ContactsContract.CommonDataKinds.Phone.TYPE)),
                                    cursor.getString(
                                            cursor.getColumnIndex(
                                                    ContactsContract.CommonDataKinds.Phone.LABEL)))
                            .toString()
            this.groups = groups
        } catch (e: NumberParseException) {
            throw IllegalArgumentException(e)
        } catch (e: NullPointerException) {
            throw IllegalArgumentException(e)
        }
    }

    fun getPhoneNumber(): String = phoneNumber

    fun getTypeLabel(): String = typeLabel

    fun getTags(): Collection<String> {
        val tags: MutableCollection<String> = ArrayList(groups)
        tags.add(typeLabel)
        return tags
    }

    fun getGroups(): Collection<String> = groups

    companion object {

        fun load(context: Context): ImmutableMap<String, PhoneNumberContact> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    context.checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
                            PackageManager.PERMISSION_GRANTED) {
                return ImmutableMap.of()
            }

            val groupTitles = HashMap<String, String>()
            try {
                context.contentResolver
                        .query(
                                ContactsContract.Groups.CONTENT_URI,
                                arrayOf(
                                        ContactsContract.Groups._ID,
                                        ContactsContract.Groups.TITLE),
                                null,
                                null,
                                null)
                        ?.use { groups ->
                            while (groups.moveToNext()) {
                                groupTitles[groups.getString(0)] = groups.getString(1)
                            }
                        }
            } catch (e: Exception) {
                return ImmutableMap.of()
            }

            val contactGroupMap: Multimap<String, String> = HashMultimap.create()
            try {
                context.contentResolver
                        .query(
                                ContactsContract.Data.CONTENT_URI,
                                arrayOf(
                                        ContactsContract.Data.CONTACT_ID,
                                        ContactsContract.CommonDataKinds.GroupMembership
                                                .GROUP_ROW_ID),
                                ContactsContract.Data.MIMETYPE + "=?",
                                arrayOf(
                                        ContactsContract.CommonDataKinds.GroupMembership
                                                .CONTENT_ITEM_TYPE),
                                null)
                        ?.use { contactGroups ->
                            while (contactGroups.moveToNext()) {
                                val groupTitle = groupTitles[contactGroups.getString(1)]
                                if (groupTitle != null) {
                                    contactGroupMap.put(contactGroups.getString(0), groupTitle)
                                }
                            }
                        }
            } catch (e: Exception) {
                return ImmutableMap.of()
            }

            val projection =
                    arrayOf(
                            ContactsContract.Data._ID,
                            ContactsContract.Data.CONTACT_ID,
                            ContactsContract.Data.DISPLAY_NAME,
                            ContactsContract.Data.PHOTO_URI,
                            ContactsContract.Data.LOOKUP_KEY,
                            ContactsContract.CommonDataKinds.Phone.TYPE,
                            ContactsContract.CommonDataKinds.Phone.LABEL,
                            ContactsContract.CommonDataKinds.Phone.NUMBER)
            val contacts = HashMap<String, PhoneNumberContact>()
            try {
                context.contentResolver
                        .query(
                                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                                projection,
                                null,
                                null,
                                null)
                        ?.use { cursor ->
                            while (cursor.moveToNext()) {
                                try {
                                    val contact =
                                            PhoneNumberContact(
                                                    context,
                                                    cursor,
                                                    contactGroupMap.get(cursor.getString(1)))
                                    val preexisting = contacts[contact.getPhoneNumber()]
                                    if (preexisting == null ||
                                            preexisting.rating() < contact.rating()) {
                                        contacts[contact.getPhoneNumber()] = contact
                                    }
                                } catch (ignored: IllegalArgumentException) {
                                }
                            }
                        }
            } catch (e: Exception) {
                return ImmutableMap.of()
            }
            return ImmutableMap.copyOf(contacts)
        }

        fun findByUriOrNumber(
                haystack: Collection<PhoneNumberContact>,
                uri: Uri,
                number: String?
        ): PhoneNumberContact? {
            val byUri = findByUri(haystack, uri)
            return if (byUri != null || number == null) byUri else findByNumber(haystack, number)
        }

        fun findByUri(
                haystack: Collection<PhoneNumberContact>,
                needle: Uri
        ): PhoneNumberContact? {
            for (contact in haystack) {
                if (needle == contact.getLookupUri()) {
                    return contact
                }
            }
            return null
        }

        private fun findByNumber(
                haystack: Collection<PhoneNumberContact>,
                needle: String
        ): PhoneNumberContact? {
            for (contact in haystack) {
                if (needle == contact.getPhoneNumber()) {
                    return contact
                }
            }
            return null
        }
    }
}
