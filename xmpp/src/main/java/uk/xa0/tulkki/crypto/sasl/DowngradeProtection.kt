package uk.xa0.tulkki.crypto.sasl

import com.google.common.base.CharMatcher
import com.google.common.base.Joiner
import com.google.common.base.Strings
import com.google.common.collect.ImmutableList
import com.google.common.collect.Ordering

class DowngradeProtection {

    @JvmField
    val mechanisms: ImmutableList<String>

    @JvmField
    val channelBindings: ImmutableList<String>?

    constructor(mechanisms: Collection<String>, channelBindings: Collection<String>) {
        this.mechanisms = Ordering.natural<String>().immutableSortedCopy(mechanisms)
        this.channelBindings = Ordering.natural<String>().immutableSortedCopy(channelBindings)
    }

    constructor(mechanisms: Collection<String>) {
        this.mechanisms = Ordering.natural<String>().immutableSortedCopy(mechanisms)
        this.channelBindings = null
    }

    fun asHString(): String {
        ensureSaslMechanismFormat(mechanisms)
        ensureNoSeparators(mechanisms)
        if (channelBindings != null) {
            ensureNoSeparators(channelBindings)
            ensureBindingFormat(channelBindings)
            val builder = StringBuilder()
            Joiner.on(SEPARATOR).appendTo(builder, mechanisms)
            builder.append(SEPARATOR_MECHANISM_AND_BINDING)
            Joiner.on(SEPARATOR).appendTo(builder, channelBindings)
            return builder.toString()
        } else {
            return Joiner.on(SEPARATOR).join(mechanisms)
        }
    }

    companion object {
        private const val SEPARATOR = '\u001E'
        private const val SEPARATOR_MECHANISM_AND_BINDING = '\u001F'

        private fun ensureNoSeparators(list: Iterable<String>) {
            for (item in list) {
                if (item.indexOf(SEPARATOR) >= 0 ||
                    item.indexOf(SEPARATOR_MECHANISM_AND_BINDING) >= 0
                ) {
                    throw SecurityException("illegal chars found in list")
                }
            }
        }

        private fun ensureSaslMechanismFormat(names: Iterable<String>) {
            for (name in names) {
                ensureSaslMechanismFormat(name)
            }
        }

        private fun ensureSaslMechanismFormat(name: String) {
            if (Strings.isNullOrEmpty(name)) {
                throw SecurityException("Empty sasl mechanism names are not permitted")
            }
            // https://www.rfc-editor.org/rfc/rfc4422.html#section-3.1
            if (name.length <= 20 &&
                CharMatcher.inRange('A', 'Z')
                    .or(CharMatcher.inRange('0', '9'))
                    .or(CharMatcher.`is`('-'))
                    .or(CharMatcher.`is`('_'))
                    .matchesAllOf(name) &&
                !Character.isDigit(name[0])
            ) {
                return
            }
            throw SecurityException("Encountered illegal sasl name")
        }

        private fun ensureBindingFormat(names: Iterable<String>) {
            for (name in names) {
                ensureBindingFormat(name)
            }
        }

        private fun ensureBindingFormat(name: String) {
            if (Strings.isNullOrEmpty(name)) {
                throw SecurityException("Empty binding names are not permitted")
            }
            // https://www.rfc-editor.org/rfc/rfc5056.html#section-7d
            if (CharMatcher.inRange('A', 'Z')
                    .or(CharMatcher.inRange('a', 'z'))
                    .or(CharMatcher.inRange('0', '9'))
                    .or(CharMatcher.`is`('.'))
                    .or(CharMatcher.`is`('-'))
                    .matchesAllOf(name)
            ) {
                return
            }
            throw SecurityException("Encountered illegal binding name")
        }
    }
}
