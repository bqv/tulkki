package uk.xa0.tulkki.ui.conversationlist

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Stream
import org.junit.Assert
import org.junit.Test
import androidx.compose.ui.graphics.Color
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.projection.CallKind
import uk.xa0.tulkki.ui.projection.EncryptionKind
import uk.xa0.tulkki.ui.projection.UiPresence

/**
 * `ui-8`'s cells over the conversation-list screen's own tables, and the one reading of its source.
 *
 * <p>The three tables are `@StringRes` mappings and nothing else, so a JVM test reaches them without a
 * device: the two typed preview cases (§2.2.1 #7/#8) and the three filters. They exist so that no kind
 * and no filter can render as a blank or as another one's words - the "one table, so there is one
 * answer" rule `TranslationText` and `SettingsEditors` keep on the screens beside this one.
 *
 * <p>The fourth cell is textual because this module hosts no Activity, no Fragment and no Robolectric,
 * so a Composable cannot be run here: what it can hold is that the screen still has one drawing for each
 * of §3.5's four states and none of them is a fake row.
 */
class ConversationListScreenTest {

    @Test
    fun everyEncryptionKindHasItsOwnWords() {
        val labels =
            EncryptionKind.entries.associateWith { encryptionLabel(it) }
        Assert.assertEquals(
            "the five kinds §2.2.1 #7 defines, each with the tree's own words",
            mapOf(
                EncryptionKind.PGP to R.string.pgp_message,
                EncryptionKind.OTR to R.string.otr_message,
                EncryptionKind.PGP_DECRYPTION_FAILED to R.string.decryption_failed,
                EncryptionKind.OMEMO_NOT_FOR_THIS_DEVICE to R.string.not_encrypted_for_this_device,
                EncryptionKind.OMEMO_DECRYPTION_FAILED to R.string.omemo_decryption_failed,
            ),
            labels,
        )
        Assert.assertEquals("no two kinds share a line", EncryptionKind.entries.size, labels.values.toSet().size)
    }

    @Test
    fun everyCallKindHasItsOwnWords() {
        val labels = CallKind.entries.associateWith { callLabel(it) }
        Assert.assertEquals(
            mapOf(
                CallKind.MISSED to R.string.missed_call,
                CallKind.INCOMING to R.string.incoming_call,
                CallKind.OUTGOING to R.string.outgoing_call,
            ),
            labels,
        )
        Assert.assertEquals("a missed call is not an answered one", CallKind.entries.size, labels.values.toSet().size)
    }

    @Test
    fun theThreeFiltersHaveTheirOwnLabels() {
        val labels = ConversationFilter.entries.associateWith { filterLabel(it) }
        Assert.assertEquals(
            "`all_chats` is the string the tree's list already used; the other two are Tulkki's",
            mapOf(
                ConversationFilter.ALL to R.string.all_chats,
                ConversationFilter.UNREAD to R.string.tulkki_list_unread,
                ConversationFilter.GROUPS to R.string.tulkki_list_groups,
            ),
            labels,
        )
        Assert.assertEquals(ConversationFilter.entries.size, labels.values.toSet().size)
    }

    /**
     * The two gestures are touch behaviour, which §7.3 lists as a device question and no JVM test can
     * reach: what this cell holds is that they exist, that they are wired to the vocabulary the host
     * implements, and that the menu draws the table's entries rather than a list of its own. Claiming
     * more than this - a screenshot that "proves" a long press - is the failure mode this row keeps
     * naming.
     */
    @Test
    fun theGesturesAreWiredToTheEventsVocabulary() {
        val screen = read("src/main/java/uk/xa0/tulkki/ui/conversationlist/ConversationListScreen.kt")
        for (gesture in GESTURES) {
            Assert.assertTrue("the screen no longer has $gesture", screen.contains(gesture))
        }
    }

    @Test
    fun theScreenHasOneDrawingForEachOfTheFourStates() {
        val screen = read("src/main/java/uk/xa0/tulkki/ui/conversationlist/ConversationListScreen.kt")
        for (drawing in DRAWINGS) {
            Assert.assertTrue("the screen no longer draws $drawing", screen.contains(drawing))
        }
    }

    /**
     * The presence dot's colour rule, read from both ends: the three ARGB values come out of the tree's
     * own `UIHelper.getColorForStatus` as text, and `presenceColour` is what the row draws. Hardcoding the
     * three numbers here instead would only prove the copy agrees with itself.
     *
     * <p>The last two assertions are the owner's correction, and they are the half that was actually
     * wrong on the phone: a state the tree drew nothing for must draw nothing, so `OFFLINE` and `UNKNOWN`
     * are alike here while staying different states on the row.
     */
    @Test
    fun thePresenceDotIsTheTreesOwnThreeColours() {
        val rule =
            read("ui/src/main/java/uk/xa0/tulkki/ui/utils/UIHelper.kt")
                .substringAfter("fun getColorForStatus(")
                .substringBefore("\n    }")
        Assert.assertEquals(
            "the tree's own green is what a present contact is drawn in",
            Color(argb(rule, "CHAT")),
            presenceColour(UiPresence.ONLINE),
        )
        Assert.assertEquals(
            "and ONLINE is that same green, which is why the two are one state",
            Color(argb(rule, "ONLINE")),
            presenceColour(UiPresence.ONLINE),
        )
        Assert.assertEquals(
            "the tree's orange is away",
            Color(argb(rule, "AWAY")),
            presenceColour(UiPresence.AWAY),
        )
        Assert.assertEquals(
            "the tree's red is do-not-disturb",
            Color(argb(rule, "DND")),
            presenceColour(UiPresence.DND),
        )
        Assert.assertEquals(
            "and XA is that same red, which is why the two share one state",
            Color(argb(rule, "XA")),
            presenceColour(UiPresence.DND),
        )
        Assert.assertTrue(
            "the rule answers nothing at all for a state it has no case for",
            rule.contains("return null"),
        )
        Assert.assertNull("so an offline contact's row draws no dot", presenceColour(UiPresence.OFFLINE))
        Assert.assertNull("and neither does a presence nobody could read", presenceColour(UiPresence.UNKNOWN))
    }

    /** The ARGB a `Presence.Status.NAME -> 0x…` arm answers inside `getColorForStatus`. */
    private fun argb(rule: String, name: String): Int {
        val match =
            Regex("(?:Presence\\.Status\\.)?$name\\s*->\\s*0x([0-9a-fA-F]{6,8})").find(rule)
                ?: throw AssertionError("UIHelper.getColorForStatus no longer colours $name")
        val value = match.groupValues[1].toLong(16)
        return if (value <= 0xFFFFFFL) (0xFF000000L or value).toInt() else value.toInt()
    }

    private fun read(relative: String): String = String(Files.readAllBytes(locate(relative)), StandardCharsets.UTF_8)

    /**
     * The file `relative` names, found in either layout: the Gradle working directory is the module,
     * and the repository root is found by its `settings.gradle.kts` marker rather than by walking up to
     * the first `src`, which would stop at the module.
     */
    private fun locate(relative: String): Path {
        val root = root()
        val direct = root.resolve(relative)
        if (Files.exists(direct)) {
            return direct
        }
        val directories =
            Files.list(root).use { stream: Stream<Path> -> stream.filter(Files::isDirectory).sorted().toList() }
        for (directory in directories) {
            val name = directory.fileName.toString()
            if (name.startsWith(".") || name == "build" || name == "src") {
                continue
            }
            val candidate = directory.resolve(relative)
            if (Files.exists(candidate)) {
                return candidate
            }
        }
        throw AssertionError("could not locate $relative under $root")
    }

    private fun root(): Path {
        var directory: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        for (level in 0 until 6) {
            if (directory != null &&
                    (Files.isRegularFile(directory.resolve("settings.gradle.kts"))
                            || Files.isRegularFile(directory.resolve("settings.gradle")))) {
                return directory
            }
            directory = directory?.parent
        }
        throw AssertionError("could not find settings.gradle.kts above ${System.getProperty("user.dir")}")
    }

    private companion object {
        /** The two gestures and the three things they emit, by the call that wires each. */
        val GESTURES =
            listOf(
                "combinedClickable(",
                "onLongClick = { menuOpen = true }",
                "DropdownMenu(expanded = menuOpen",
                "ConversationMenu.entries(conversation, conversation.ongoingCall)",
                "SwipeToDismissBox(",
                "events.onAction(ConversationMenu.archive(conversation.kind), conversation.id.uuid)",
                "events.onOpen(conversation.id.uuid)",
                "events.onFilter(candidate)",
            )

        /** §3.5's four states, each by the call that draws it and none of them by a fake row. */
        val DRAWINGS =
            listOf(
                "state.loading -> Skeleton()",
                "state.empty -> Explainer()",
                "StatusLine(state.connection)",
                "LazyColumn(",
            )
    }
}
