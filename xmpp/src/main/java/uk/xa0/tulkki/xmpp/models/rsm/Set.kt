package uk.xa0.tulkki.xmpp.models.rsm

import com.google.common.base.Strings
import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension
import uk.xa0.tulkki.xmpp.models.Page
import uk.xa0.tulkki.xmpp.models.Range

@XmlElement(namespace = Namespace.RESULT_SET_MANAGEMENT)
class Set : Extension(Set::class.java) {

    fun asPage(): Page {
        val first = getExtension(First::class.java)
        val last = getExtension(Last::class.java)

        val firstId = first?.getContent()
        val lastId = last?.getContent()
        if (firstId.isNullOrEmpty() || lastId.isNullOrEmpty()) {
            throw IllegalStateException("Invalid page. Missing first or last")
        }
        return Page(firstId, lastId, getCount())
    }

    fun isEmpty(): Boolean {
        val first = getExtension(First::class.java)
        val last = getExtension(Last::class.java)
        return first == null && last == null
    }

    fun getCount(): Int? {
        val count = getExtension(Count::class.java)
        return count?.getCount()
    }

    companion object {
        @JvmStatic
        fun of(range: Range, max: Int?): Set {
            val set = Set()
            when (range.order) {
                Range.Order.NORMAL -> {
                    val after = set.addExtension(After())
                    after.setContent(range.id)
                }
                Range.Order.REVERSE -> {
                    val before = set.addExtension(Before())
                    before.setContent(range.id)
                }
                else -> throw IllegalArgumentException("Invalid order")
            }
            if (max != null) {
                set.addExtension(Max()).setMax(max)
            }
            return set
        }
    }
}
