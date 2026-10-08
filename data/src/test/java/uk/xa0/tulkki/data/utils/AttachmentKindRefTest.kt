package uk.xa0.tulkki.data.utils

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.libs.AttachmentRef

/**
 * Tulkki: 3.7 C5-E3. `AttachmentRef.TypeRef` is a **compile-time copy** of `Attachment.Type`, for
 * the language reason `AttachmentRef`'s class comment records: the island compares the kind by
 * identity and the model already declares `getType()`, so a class may not have two methods
 * differing only in return type and the island member had to be named `kind()`.
 *
 * A build cannot see the drift this creates. A fifth `Attachment.Type` added without a matching
 * `TypeRef` - or a constant renamed on one side only - compiles clean, and `Attachment.kindOf`'s
 * switch is the only thing that would throw, at runtime, on a path only a real media list reaches.
 * So this pins the mapping name for name, in both directions, plus the constant count that makes the
 * two loops equivalent.
 *
 * It is deliberately a pure JVM test: it calls the static mapper, never a constructor, so nothing
 * here touches `Uri`, `Context` or `Parcel`.
 */
class AttachmentKindRefTest {

    @Test
    fun everyModelTypeHasARefOfTheSameName() {
        for (type in Attachment.Type.values()) {
            val ref = Attachment.kindOf(type)
            Assert.assertNotNull("no TypeRef for " + type.name, ref)
            Assert.assertEquals(type.name, ref.name)
        }
    }

    @Test
    fun everyRefTypeHasAModelTypeOfTheSameName() {
        for (ref in AttachmentRef.TypeRef.values()) {
            Assert.assertEquals(
                ref.name, Attachment.kindOf(Attachment.Type.valueOf(ref.name)).name)
        }
    }

    /** The same set of constants, which is what makes the two loops above equivalent. */
    @Test
    fun theTwoEnumsHaveTheSameConstants() {
        Assert.assertEquals(
            Attachment.Type.values().size, AttachmentRef.TypeRef.values().size)
    }

    /** The copy keeps the model's order as well, so the two lists read the same to a reviewer. */
    @Test
    fun theCopyKeepsTheModelsOrder() {
        val model = Attachment.Type.values()
        val ref = AttachmentRef.TypeRef.values()
        for (i in model.indices) {
            Assert.assertEquals(model[i].name, ref[i].name)
        }
    }
}
