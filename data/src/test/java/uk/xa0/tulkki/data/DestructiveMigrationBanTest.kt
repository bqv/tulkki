package uk.xa0.tulkki.data

import org.junit.Assert
import org.junit.Test

/**
 * Ban test 1 of 3 (S5-1): `fallbackToDestructiveMigration` never appears.
 *
 * The owner's phone holds the only copy of years of conversations, with the OMEMO identities
 * attached to them. A destructive fallback turns a schema mistake into silent deletion of that file,
 * and the whole reason the adoption in this commit is safe is that its failure mode is a *refused
 * open* instead. A convention would not survive the first hurried writer; this does.
 *
 * The scan covers every module's <em>main</em> source set, not `src/&#42;*`: this file has to name the
 * three banned symbols to ban them, so a scan that included test sources would match itself and have
 * to keep an allow-list, and an allow-list is the thing that makes a ban test worthless. A
 * destructive fallback in a test cannot ship.
 */
class DestructiveMigrationBanTest {

    private val banned = arrayOf(
        "fallbackToDestructiveMigration",
        "fallbackToDestructiveMigrationOnDowngrade",
        "fallbackToDestructiveMigrationFrom",
    )

    private val mainSourceSets = arrayOf(
        "app/src/main",
        "ui/src/main",
        "translation/src/main",
        "data/src/main",
        "xmpp/src/main",
        "crypto/src/main",
    )

    @Test
    fun noDestructiveMigrationFallbackExistsAnywhere() {
        val hits = ArrayList<String>()
        var scanned = 0
        for (sources in mainSourceSets) {
            for (file in RepoFiles.sourcesUnder(sources)) {
                scanned++
                val text = RepoFiles.read(file)
                for (needle in banned) {
                    if (text.contains(needle)) {
                        hits.add(RepoFiles.name(file) + ": " + needle)
                    }
                }
            }
        }
        Assert.assertTrue("scanned only " + scanned + " files", scanned > 500)
        Assert.assertEquals(
            "a destructive migration fallback deletes the owner's history instead of failing: " +
                "the database must refuse to open, not recreate itself",
            emptyList<String>(),
            hits)
    }
}
