package uk.xa0.tulkki.ui.adapter

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.Filter

import com.google.common.collect.ImmutableList
import com.google.common.collect.Ordering

import java.util.ArrayList
import java.util.Locale
import java.util.regex.Pattern

import uk.xa0.tulkki.xmpp.Config

class KnownHostsAdapter : ArrayAdapter<String> {

    private var domains: List<String> = emptyList()

    private val domainFilter: Filter = object : Filter() {

        override fun performFiltering(constraint: CharSequence?): Filter.FilterResults {
            val builder = ImmutableList.Builder<String>()
            // Java's `String.split("@")` drops trailing empty strings; `Pattern.split` is the
            // same call, where Kotlin's own `split` is not.
            val split: Array<String> = if (constraint == null) {
                emptyArray()
            } else {
                Pattern.compile("@").split(constraint.toString())
            }
            if (split.size == 1) {
                val local = split[0].lowercase(Locale.ENGLISH)
                if (Config.QUICKSY_DOMAIN != null && E164_PATTERN.matcher(local).matches()) {
                    builder.add(local + '@' + Config.QUICKSY_DOMAIN.toString())
                } else {
                    for (domain in domains) {
                        builder.add(local + '@' + domain)
                    }
                }
            } else if (split.size == 2) {
                val localPart = split[0].lowercase(Locale.ENGLISH)
                val domainPart = split[1].lowercase(Locale.ENGLISH)
                if (domains.contains(domainPart)) {
                    return Filter.FilterResults()
                }
                for (domain in domains) {
                    if (domain.contains(domainPart)) {
                        builder.add(localPart + "@" + domain)
                    }
                }
            } else {
                return Filter.FilterResults()
            }
            val suggestions = builder.build()
            val filterResults = Filter.FilterResults()
            filterResults.values = suggestions
            filterResults.count = suggestions.size
            return filterResults
        }

        override fun publishResults(constraint: CharSequence?, results: Filter.FilterResults) {
            val suggestions = ImmutableList.Builder<String>()
            val values = results.values
            if (values is Collection<*>) {
                for (item in values) {
                    if (item is String) {
                        suggestions.add(item)
                    }
                }
            }
            clear()
            addAll(suggestions.build())
            notifyDataSetChanged()
        }
    }

    constructor(context: Context, viewResourceId: Int, knownHosts: Collection<String>) :
            super(context, viewResourceId, ArrayList<String>()) {
        domains = Ordering.natural<String>().sortedCopy(knownHosts)
    }

    constructor(context: Context, viewResourceId: Int) :
            super(context, viewResourceId, ArrayList<String>()) {
        domains = ImmutableList.of<String>()
    }

    // `Collection` is Kotlin's here: with no java.util.Collection import a Java collection is passed
    // without a cast, and the JVM signature is unchanged for the Java callers.
    fun refresh(knownHosts: Collection<String>) {
        this.domains = Ordering.natural<String>().sortedCopy(knownHosts)
        notifyDataSetChanged()
    }

    override fun getFilter(): Filter {
        return domainFilter
    }

    companion object {
        private val E164_PATTERN = Pattern.compile("^\\+[1-9]\\d{1,14}$")
    }
}
