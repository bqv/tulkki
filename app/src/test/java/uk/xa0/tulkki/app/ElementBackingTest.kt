package uk.xa0.tulkki.app

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.xa0.tulkki.xml.Element

/**
 * Tulkki: the XML layer's two ways to fill an element - a **live view** and a **copy** - and the
 * thirteen stanza parsers that now use the first one.
 *
 * <p>This is the port of upstream `27877a1876` ("use bindTo for live views and replaceChildren for
 * copies"), and the distinction is the whole reason that commit is not a cleanup: our parsers used
 * `setAttributes(...) + setChildren(...)`, which **copies each list** (`setChildren` built `new
 * ArrayList<>(children)` twice), while `bindTo(original)` **shares the backing lists by reference**.
 * The refactor therefore flips the parsers from copies to views - deliberately, because the stanza a
 * parser hands back has to keep reaching the element that is actually serialized (the ICE-candidate
 * fix in `WebRTCDataChannelTransportInfo` assumes exactly that).
 *
 * <p>The behavioural cell below is **green by construction**: it pins semantics that were already in
 * `Element` before this refactor, and it exists so the semantics the parsers now rely on cannot drift
 * unnoticed. The source pin is the part that would have failed before the change: it reddens if a
 * parser goes back to the copy pair, if `withCandidates` (the one deliberate copy that survives,
 * `IceUdpTransportInfo:161`) is turned into a view, or if `setChildren` is reintroduced anywhere.
 */
class ElementBackingTest {

    private companion object {

        val PARSERS: List<String> =
            listOf(
                "Content",
                "FileTransferDescription",
                "GenericDescription",
                "GenericTransportInfo",
                "Group",
                "IbbTransportInfo",
                "IceUdpTransportInfo",
                "OmemoVerifiedIceUdpTransportInfo",
                "Proceed",
                "Propose",
                "RtpDescription",
                "SocksByteStreamsTransportInfo",
                "WebRTCDataChannelTransportInfo")

        const val STANZAS = "xmpp/src/main/java/uk/xa0/tulkki/xmpp/jingle/stanzas/"

        /**
         * The named stanza source under either spelling.
         *
         * <p>A pin names the path its text lives at, never the language it is written in, and the
         * Kotlin port moves a parser from `.java` to `.kt` without moving the path - so the pin
         * resolves whichever spelling the tree holds, and a parser that is genuinely gone still fails
         * when [flattened] tries to read it.
         */
        fun stanza(root: Path, name: String): Path {
            val java = root.resolve(STANZAS + name + ".java")
            return if (Files.exists(java)) java else root.resolve(STANZAS + name + ".kt")
        }

        fun flattened(file: Path): String =
            String(Files.readAllBytes(file), StandardCharsets.UTF_8).replace(Regex("\\s+"), " ")

        /** The checkout root, found the way this module's other source pins find it. */
        fun projectRoot(): Path {
            var directory: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
            var level = 0
            while (level < 6 && directory != null) {
                if (Files.isRegularFile(directory.resolve("settings.gradle.kts"))
                        || Files.isRegularFile(directory.resolve("settings.gradle"))) {
                    return directory
                }
                directory = directory.parent
                level++
            }
            throw AssertionError(
                "could not find settings.gradle.kts above " + System.getProperty("user.dir"))
        }
    }

    @Test
    fun bindToSharesTheBackingListsAndReplaceChildrenCopiesThem() {
        val wire = Element("transport", "urn:xmpp:jingle:transports:ice-udp:1")
        wire.addChild(Element("candidate"))

        val bound = Element("transport")
        bound.bindTo(wire)
        assertEquals("bindTo takes the children that were there", 1, bound.getChildren().size)

        val copied = Element("transport")
        copied.replaceChildren(wire.getChildren())
        assertEquals("replaceChildren takes them too", 1, copied.getChildren().size)

        // The wire side grows afterwards: the bound element is a view and sees it, the copy does not.
        wire.addChild(Element("candidate"))
        assertEquals("a live view follows the original", 2, bound.getChildren().size)
        assertEquals("a copy does not", 1, copied.getChildren().size)

        // And the other direction: a child added through the view reaches the original.
        bound.addChild(Element("candidate"))
        assertEquals("the view writes through to the original", 3, wire.getChildren().size)
        assertEquals("the copy still does not", 1, copied.getChildren().size)
    }

    @Test
    fun theThirteenParsersUseTheLiveRoadAndNothingCallsSetChildrenAnymore() {
        val root = projectRoot()
        for (parser in PARSERS) {
            val source = flattened(stanza(root, parser))
            assertTrue(
                parser + " must fill its element with bindTo",
                source.contains("bindTo("))
            assertFalse(
                parser + " must no longer copy through setChildren",
                source.contains("setChildren("))
        }

        val iceUdp = flattened(stanza(root, "IceUdpTransportInfo"))
        assertTrue(
            "withCandidates keeps upstream's deliberate copy",
            iceUdp.contains("replaceChildren(this.getChildren())"))

        val reintroduced = ArrayList<String>()
        Files.walk(root.resolve("xmpp/src/main/java")).use { walk ->
            for (file in walk.filter { Files.isRegularFile(it) }.collect(Collectors.toList())) {
                val name = file.fileName.toString()
                if (!name.endsWith(".java") && !name.endsWith(".kt")) {
                    continue
                }
                if (flattened(file).contains("setChildren(")) {
                    reintroduced.add(root.relativize(file).toString())
                }
            }
        }
        assertTrue(
            "Element.setChildren is deleted and may not come back: " + reintroduced,
            reintroduced.isEmpty())
    }
}
