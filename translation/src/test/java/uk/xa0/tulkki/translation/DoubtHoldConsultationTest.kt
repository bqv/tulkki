package uk.xa0.tulkki.translation

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.ArrayList
import java.util.Collections
import org.junit.Assert
import org.junit.Test

/**
 * Tulkki: the doubt-hold switch is consulted in exactly one place, and it is the send path's own hold
 * decision - a **source pin**, because that decision is not reachable from a JVM test
 * (`OutgoingTranslation` is driven by a `Context`, a `Message` and a screen, and its callers are the
 * composer and the held bubble's tap).
 *
 * <p>What it guards is item 16's derived setting: `DoubtHold.holds(doubt, perConversation)` is the one
 * question the send path asks, and the answer it must be given is **this conversation's stored
 * column** (`Conversation.getDoubtHold()`), never a literal - `null` there would mean "the owner never
 * chose" and `false` would turn every room's hold off. The second cell pins the other half of the
 * shape: nothing else in `:translation`'s main sources consults the switch, because a second consumer
 * is how a derived setting becomes two behaviours that can disagree.
 *
 * <p>The display side is deliberately not consulted here at all: a received message draws a probable
 * translation as the translation it probably is, whatever a column says, so no cell should ever find
 * `DoubtHold` on that path.
 */
class DoubtHoldConsultationTest {

    private val SEND_PATH =
            "translation/src/main/java/uk/xa0/tulkki/translation/OutgoingTranslation.kt"

    private val CONSULTATION =
            "DoubtHold.holds(check.doubt, conversation.getDoubtHold())"

    @Test
    fun theSendPathsHoldDecisionAsksTheSwitchForThisConversation() {
        val source = flattened(projectRoot().resolve(SEND_PATH))

        Assert.assertTrue(
                "the send path must ask the switch, and for this conversation's own value: "
                        + CONSULTATION,
                source.contains(CONSULTATION))
    }

    @Test
    fun nothingElseInTheModuleConsultsTheSwitch() {
        val consumers = ArrayList<String>()
        val files = Files.walk(projectRoot().resolve("translation/src/main/java")).use { walk ->
            walk.filter { Files.isRegularFile(it) }.toList()
        }
        for (file in files) {
            val name = file.getFileName().toString()
            if (!name.endsWith(".java") && !name.endsWith(".kt")) {
                continue
            }
            if (name.equals("DoubtHold.kt")) {
                continue
            }
            val source = flattened(file)
            if (source.contains("DoubtHold.holds(") || source.contains("DoubtHold.inForce(")) {
                consumers.add(projectRoot().relativize(file).toString())
            }
        }

        Assert.assertEquals(
                "the switch has exactly one consumer, the send path's hold decision",
                Collections.singletonList(SEND_PATH),
                consumers)
    }

    @Test
    fun theTapsEntryAsksTheKindsBeforeItBuysAnything() {
        val sendPath = flattened(projectRoot().resolve(SEND_PATH))

        Assert.assertTrue(
                "the tap must reach the kind's own decision before the ordinary pipeline runs",
                sendPath.contains("if (sendStoredDoubt(context, message, listener, sender))"))
        Assert.assertEquals(
                "and exactly one call site may ask HeldDoubt's question: the tap's own entry",
                Collections.singletonList(SEND_PATH),
                consumersOf("HeldDoubt.sendsStoredAnswer("))
    }

    /** The module's main sources that name a token, so a disappearing caller is a red cell. */
    private fun consumersOf(token: String): List<String> {
        val consumers = ArrayList<String>()
        val files = Files.walk(projectRoot().resolve("translation/src/main/java")).use { walk ->
            walk.filter { Files.isRegularFile(it) }.toList()
        }
        for (file in files) {
            val name = file.getFileName().toString()
            if (!name.endsWith(".java") && !name.endsWith(".kt")) {
                continue
            }
            if (name.equals("HeldDoubt.kt")) {
                continue
            }
            if (flattened(file).contains(token)) {
                consumers.add(projectRoot().relativize(file).toString())
            }
        }
        return consumers
    }

    private fun flattened(file: Path): String {
        return String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                .replace(Regex("\\s+"), " ")
    }

    /** The checkout root, found the way the module's other source scans find it. */
    private fun projectRoot(): Path {
        var directory: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        for (level in 0 until 6) {
            if (directory != null &&
                    (Files.isRegularFile(directory.resolve("settings.gradle.kts"))
                            || Files.isRegularFile(directory.resolve("settings.gradle")))) {
                return directory
            }
            directory = directory?.getParent()
        }
        throw AssertionError(
                "could not find settings.gradle.kts above " + System.getProperty("user.dir"))
    }
}
