package uk.xa0.tulkki.data

import java.util.regex.Pattern
import org.junit.Assert
import org.junit.Test

/**
 * Ban test 2 of 3 (S5-1): the Room `_Impl` keep rule is present, and it names the package the
 * `@Database` class actually lives in.
 *
 * Room resolves its generated implementation <em>by name</em> -
 * `Class.forName("<package>.<Db>_Impl")` - so shrinking it away is a crash nothing in this
 * repository can see: the release build is the only thing that would shrink it, and
 * `assembleTulkkiRelease` is deliberately never run here. The rule is also redundant today, because
 * the 3.8 rename put every module root under `uk.xa0.tulkki.**` and `-keep class uk.xa0.** { *; }`
 * already covers it. It is pinned anyway, because a rule that is useless costs nothing and one that
 * is missing costs the app on a build nobody makes.
 *
 * The package half matters more than the line: if the opener moves to another package, the hardcoded
 * rule silently stops covering it, and that is exactly the failure mode a bare "does the string
 * exist" check would miss.
 */
class RoomKeepRuleTest {

    private val keep =
        Pattern.compile("-keep\\s+class\\s+(\\S+?)\\.\\*\\*_Impl\\s*\\{\\s*\\*;\\s*}")

    private val openersPackage =
        Pattern.compile("^package\\s+([\\w.]+)\\s*$", Pattern.MULTILINE)

    @Test
    fun theGeneratedDatabaseImplementationIsKept() {
        val rules = RepoFiles.read("proguard-rules.pro")
        val matcher = keep.matcher(rules)
        Assert.assertTrue(
            "proguard-rules.pro has no `-keep class <package>.**_Impl { *; }` rule; Room finds " +
                "its implementation with Class.forName, so a shrunk _Impl is a crash no test in " +
                "this repo can see",
            matcher.find())

        val keptPackage = matcher.group(1)
        val opener = RepoFiles.read("data/src/main/java/uk/xa0/tulkki/data/HistoryDatabase.kt")
        val pkg = openersPackage.matcher(opener)
        Assert.assertTrue("HistoryDatabase.kt has no package line", pkg.find())
        Assert.assertEquals(
            "the _Impl keep rule must name the package the @Database class is in",
            pkg.group(1),
            keptPackage)
    }
}
