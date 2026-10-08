package uk.xa0.tulkki.data.model

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.refs.BookmarkRef

/**
 * Tulkki: the null write through the ref that crashed on the owner's phone.
 *
 * `BookmarkRef.setConversation(ConversationRef)` is a bare Java parameter, and
 * `XmppConnectionService` clears a bookmark's back-reference with a literal `null` on two paths -
 * archiving a bookmarked room and leaving one. The port's first cut declared the parameter non-null,
 * so Kotlin's parameter check threw
 * `NullPointerException: Parameter specified as non-null is null: ... Bookmark.setConversation`
 * before Java's own null branch could run. The static type is the interface there, so the
 * class-typed overload that accepts that `null` is not visible to the compiler.
 *
 * It is deliberately a pure JVM test: an `Account` and a `Bookmark` are built here without an
 * Android type being touched (`org.json` is a real test-scope dependency of `:data` for exactly this
 * reason), and the cell pins the crash's own call: a null write through the ref, then the null read.
 */
class BookmarkConversationRefTest {

    @Test
    fun aNullWriteThroughTheRefIsANullRead() {
        val account =
            Account(Jid.ofLocalAndDomain("tulkki", "example.org"), "password")
        val bookmark =
            Bookmark(account, Jid.ofLocalAndDomain("room", "conference.example.org"))
        val ref: BookmarkRef = bookmark

        ref.setConversation(null)

        Assert.assertNull(bookmark.getConversation())
    }
}
