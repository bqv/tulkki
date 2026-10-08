package uk.xa0.tulkki.app

import java.util.ArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.EntityCapabilities2
import uk.xa0.tulkki.xmpp.models.data.Data
import uk.xa0.tulkki.xmpp.models.data.Field
import uk.xa0.tulkki.xmpp.models.data.Value
import uk.xa0.tulkki.xmpp.models.disco.info.InfoQuery
import uk.xa0.tulkki.xmpp.models.reactions.Reaction
import uk.xa0.tulkki.xmpp.models.reactions.Reactions

/**
 * Tulkki: the hardening bundle of upstream's `a9658ba076` (port-11's span read, group C), pinned
 * where a JVM test can reach it.
 *
 * <p>Three facts, each about a value a remote party chose:
 *
 * <ol>
 *   <li>a `disco#info` form with no `FORM_TYPE` reads back as `null` - it used to throw
 *       `NoSuchElementException` out of whatever was parsing the stanza;
 *   <li>a `<reaction/>` that is not an emoji is dropped, because it is stored and then drawn as a
 *       reaction chip where the reader expects a short trusted badge;
 *   <li>XEP-0390 refuses to hash a document it cannot fully account for - an element outside the
 *       allow-list, a form with no `FORM_TYPE`, or one carrying `<item/>`/`<reported/>` - because
 *       the caps cache is keyed by that hash and shared between entities, so a collision serves one
 *       entity's features under another's key.
 * </ol>
 *
 * <p>The hash entry point used here is the public `EntityCapabilities2.hash(InfoQuery)`, which is
 * what a caller outside the class can reach; the three abort conditions are asserted through it.
 */
class StanzaHardeningTest {

    @Test
    fun aFormWithoutFormTypeReadsBackAsNull() {
        assertNull("a form a peer sent without FORM_TYPE is not an error", Data().getFormType())
    }

    @Test
    fun aReactionThatIsNotAnEmojiIsDropped() {
        val reactions = Reactions()
        reactions.addExtension(Reaction("not an emoji at all"))
        reactions.addExtension(Reaction("\uD83C\uDF89"))

        val kept = reactions.getReactions()

        assertEquals(
            "only the emoji survives",
            listOf("\uD83C\uDF89"),
            ArrayList(kept))
    }

    /**
     * The refusal asserted by **name** rather than by type, so this cell compiles against the
     * unfixed tree too: before the port there is no `IllegalInfoQueryException` to name, and a cell
     * that cannot compile is not evidence of anything. Before, `hash` returns a hash and the
     * `AssertionError` below is what fails; after, the type is the port's own nested exception.
     */
    private fun assertRefusedToHash(query: InfoQuery) {
        try {
            EntityCapabilities2.hash(query)
        } catch (e: Exception) {
            assertEquals(
                "the refusal must be the XEP-0390 one",
                "uk.xa0.tulkki.xmpp.models.EntityCapabilities2\$IllegalInfoQueryException",
                e.javaClass.name)
            return
        }
        throw AssertionError("a document the algorithm cannot account for must not be hashed")
    }

    @Test
    fun anElementOutsideTheAllowListRefusesToHash() {
        val query = InfoQuery()
        query.addExtension(Reactions())

        assertRefusedToHash(query)
    }

    @Test
    fun aDataFormWithoutFormTypeRefusesToHash() {
        val query = InfoQuery()
        query.addExtension(Data())

        assertRefusedToHash(query)
    }

    @Test
    fun aDataFormCarryingItemRefusesToHash() {
        val form = Data()
        val formType = form.addExtension(Field())
        formType.setFieldName("FORM_TYPE")
        formType.addExtension(Value()).setContent("urn:example:test")
        form.addChild("item", Namespace.DATA)

        val query = InfoQuery()
        query.addExtension(form)

        assertRefusedToHash(query)
    }
}
