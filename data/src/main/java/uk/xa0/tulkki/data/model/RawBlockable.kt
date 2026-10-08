package uk.xa0.tulkki.data.model

import android.content.Context
import java.util.Locale
import java.util.regex.Pattern
import uk.xa0.tulkki.data.utils.DisplayNames
import uk.xa0.tulkki.libs.Jid

/**
 * A block entry that is not a contact: an account plus the JID it blocks, built by `:ui`'s
 * `BlocklistActivity` for the rows a roster lookup cannot answer.
 *
 * <p>Ported from Java by the `port` stage (port-2). The decisions, recorded rather than inherited:
 *
 * 1. **All eleven members are `override`s, and Kotlin demands it.** The two interfaces this class
 *    implements are Kotlin now ([ListItem] and [Blockable]), so Java's advisory `@Override` becomes
 *    mandatory on every member - `getBlockedJid`/`getJid`/`getAccount` from [Blockable]'s own
 *    redeclarations included.
 * 2. **The two constructor properties are named `accountValue`/`jidValue`.** `getJid()` and
 *    `getAccount()` are interface members this class must implement, and a Kotlin `private val jid`
 *    would generate a private `getJid()` beside the explicit override - the clash
 *    [TransferablePlaceholder]'s `statusValue` records. The Java constructor's shape is unchanged, so
 *    `:ui`'s two `new RawBlockable(account, jid)` sites (`BlocklistActivity:61`, `:95`) still compile.
 * 3. **`needle.toLowerCase(Locale.US).trim()` keeps Java's `trim()`, via a private helper.** Kotlin's
 *    `trim()` removes by `Char.isWhitespace`, which also matches `isSpaceChar`, where Java's removes
 *    every char `<= ' '`; the two disagree on the non-breaking-space family, and this is a
 *    user-typed search needle, so the difference is reachable. The helper is the same one `:translation`
 *    declares per file (`private fun String.javaTrim(): String = trim { it <= ' ' }`,
 *    `ComposerGate.kt:257` and seven siblings), redeclared here because `:data` may not name
 *    `:translation`. `lowercase(Locale.US)` is Kotlin's exact delegation to
 *    `java.lang.String.toLowerCase(Locale)`, so only the trim needed the helper.
 * 4. **`needle.split("\\s+")` was a *literal* split, and it is now Java's own call.** Kotlin has no
 *    `split(regex: String)` overload - the two `String` forms are
 *    `split(vararg delimiters: String, …)`, which takes each string as a **literal** delimiter, and
 *    `split(regex: Regex, …)`. So the Java call had become a split on the three-character string
 *    `\s+`, and `parts` was always the whole needle. `Pattern.compile("\\s+").split(input, 0)` is
 *    what `String.split(String)` *is*, and the `.toRegex()` shape that stood between the two was
 *    replaced by it because **Kotlin's `Regex.split(input, 0)` keeps trailing empty fields where
 *    Java's `Pattern.split(input, 0)` drops them** - measured, `new Regex(":").split("a:", 0)` is
 *    `[a, ""]` while `"a:".split(":")` is `[a]`. The `javaTrim()` above happens to make that
 *    unreachable here; "happens to be unreachable" is not a translation. See `067ce2c2a8` for the
 *    disassembly that proves the literal binding.
 * 5. **`Collections.emptyList()` is `emptyList()`.** Both are immutable and always empty, and Kotlin's
 *    is the identity the JDK's singleton answers for any list comparison.
 * 6. **`compareToIgnoreCase` is not reachable through Kotlin's `String`, and that was measured.**
 *    The first attempt wrote `getDisplayName().compareToIgnoreCase(...)` and the compiler answered
 *    `e: RawBlockable.kt:79:26 Unresolved reference 'compareToIgnoreCase'`; Kotlin's
 *    `compareTo(other, ignoreCase = true)` is the same call - it delegates to
 *    `java.lang.String.compareToIgnoreCase` - and is what the file writes.
 * 7. **`match`'s `needle` is `String?`, and the guard is spelled `needle == null || needle.isEmpty()`.**
 *    Java's `TextUtils.isEmpty(needle)` answered `true` for `null` - "no filter matches everything" -
 *    and `:ui`'s `StartConversationActivity.filterContacts` passes a search box value that is `null`
 *    until something is typed; the non-null parameter this port's first draft declared threw there
 *    (see the `FIXED:` on `Bookmark.match`). The guard is `TextUtils.isEmpty`'s own answer
 *    (`x == null || x.length() == 0`), in [Contact] clause 2's spelling, and it is also what lets the
 *    compiler see the non-null path to `lowercase`; the `TextUtils` import went with it.
 *
 * Nothing is static and no Java caller reads a field as a field, so this file adds **zero** interop
 * debt.
 */
class RawBlockable(
    private val accountValue: Account,
    private val jidValue: Jid,
) : ListItem, Blockable {

    override fun isBlocked(): Boolean = true

    override fun isDomainBlocked(): Boolean = throw AssertionError("not implemented")

    override fun getBlockedJid(): Jid = jidValue

    override fun getDisplayName(): String =
        if (jidValue.isFullJid()) {
            (jidValue.getResource() ?: throw NullPointerException())
        } else {
            jidValue.toString()
        }

    override fun getJid(): Jid = jidValue

    override fun getTags(context: Context): List<ListItem.Tag> = emptyList()

    override fun match(context: Context, needle: String?): Boolean {
        if (needle == null || needle.isEmpty()) {
            return true
        }
        val parts = Pattern.compile("\\s+").split(needle.lowercase(Locale.US).javaTrim(), 0)
        for (part in parts) {
            if (!jidValue.toString().contains(part)) {
                return false
            }
        }
        return true
    }

    override fun getAccount(): Account = accountValue

    override fun getAvatarBackgroundColor(): Int = DisplayNames.getColorForName(jidValue.toString())

    override fun getAvatarName(): String = getDisplayName()

    override fun compareTo(other: ListItem): Int =
        getDisplayName().compareTo(other.getDisplayName(), ignoreCase = true)
}

/** Java's `String.trim()`: every char `<= ' '`, not Kotlin's whitespace set. */
private fun String.javaTrim(): String = trim { it <= ' ' }
