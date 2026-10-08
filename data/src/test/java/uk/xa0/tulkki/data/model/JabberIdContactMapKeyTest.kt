package uk.xa0.tulkki.data.model

import java.util.regex.Matcher
import java.util.regex.Pattern
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.RepoFiles

/**
 * Tulkki: the address-book map is keyed by each entry's own JID - a **source pin**, and this is why
 * it has to be one.
 *
 * `JabberIdContact.load(Context)` builds the `Map<Jid, JabberIdContact>` the island's roster merge
 * reads, and port-13 deletes `JabberIdRef` on the strength of that keying: with the ref gone the port
 * hands over `Map<Jid, ? extends AbstractPhoneContact>` (the family class port-13 moved into the
 * island as `PhoneContactRef`'s replacement), so the merge looks a contact up by the entry's
 * **key** instead of by a `getJid()` the parent marker cannot offer. The equality of key and
 * value-jid is therefore load-bearing, and no JVM cell can run it: `load` is gated behind
 * `ContactListIntegration` and `READ_CONTACTS`, and its only path to a map is
 * `context.getContentResolver().query(...)` - an Android stub off-device - behind a `Cursor` whose
 * constructor is private. So the producer's keying is pinned in the source, and the assertion is
 * shaped to survive reformatting rather than to freeze a line: in the whitespace-collapsed file,
 * **every** `contacts.put(` must pass `contact.getJid()` as its key, and the map's declared key type
 * must be `Jid`.
 *
 * What it catches: an edit that keys the map by anything else - a display name, a phone number, a
 * normalised jid - which would leave the merge looking contacts up by a key that is no longer the
 * entry's JID. Nothing else in the tree can see that, and the swap it guards is invisible to the
 * compiler too, because the value type is the same either way.
 *
 * It is deliberately not a count: a third insert, correctly keyed, must not redden this cell - only
 * an insert that is not, or a map whose key type has moved off `Jid`.
 */
class JabberIdContactMapKeyTest {

    private val source = "data/src/main/java/uk/xa0/tulkki/data/model/JabberIdContact.kt"

    @Test
    fun everyEntryInTheAddressBookMapIsKeyedByItsOwnJid() {
        val flat = RepoFiles.read(source).replace(Regex("\\s+"), " ")

        val inserts = count(Pattern.compile("contacts\\.put\\(").matcher(flat))
        Assert.assertTrue(
            "no insert found in " + source + " - the pin would pass while proving nothing",
            inserts > 0)

        val keyedByJid =
            count(Pattern.compile("contacts\\.put\\(\\s*contact\\.getJid\\(\\)").matcher(flat))
        Assert.assertEquals(
            "every insert into " + source + " must key the map by the entry's own JID",
            inserts,
            keyedByJid)

        Assert.assertTrue(
            "the map the island reads must have Jid keys", flat.contains("HashMap<Jid,"))
    }

    private fun count(matcher: Matcher): Int {
        var n = 0
        while (matcher.find()) {
            n++
        }
        return n
    }
}
