package uk.xa0.tulkki.ui.conversation

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Stream
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.projection.UiDoubtHold

/**
 * The Compose surfaces the live conversation hosts: item 17's banner, item 16's switch and the
 * composer, what the fragment draws for each, and what its actions do.
 *
 * <p>The module hosts no Activity, no Fragment and no Robolectric, so a `ComposeView` cannot be
 * rendered here - the same reason [uk.xa0.tulkki.ui.TranslationFailuresScreenTest] compares the host
 * as source text. What can be decided on the JVM is split the way the code is: the banner is
 * [ConversationHost.notices], a pure function of four facts; item 16's switch is
 * [ConversationHost.doubtHold], a pure function of two, which the composer carries as one of its own
 * fields; the composer is [ConversationHost.composer], a pure function of the draft, the pair, the
 * switch and the reply; and the things a fragment must do with them - the deep link, the stored
 * answer, the "nothing spends" rule and the one send path - are read off the fragment, because a
 * source reading is the strongest check this module can make of a host.
 *
 * <p>Why these cells exist at all: `ui-9` wrote each surface and left it unhosted, so every cell that
 * pinned its drawing lived in the Compose screen's own tests while the app drew the Java bar. These
 * pin the hosting - the off state, the failure that turns the line on, the one action the banner
 * carries, the value in force the switch shows and the composer whose draft and send reach the one
 * path - so a surface cannot quietly go back to being written and unreachable.
 */
class ConversationHostTest {

    /** Off is a plain client: no banner, no notice, no line - the interpreter's own predicate. */
    @Test
    fun offOffersNoNotice() {
        Assert.assertTrue(
            "off, the surface must not be drawn at all",
            ConversationHost.notices(
                    interpreting = false,
                    languageKnown = false,
                    keyConfigured = true,
                    failures = true)
                .isEmpty(),
        )
    }

    /** A known language is silent until something has failed within it. */
    @Test
    fun aKnownLanguageWithNothingFailedOffersNoNotice() {
        Assert.assertTrue(
            ConversationHost.notices(
                    interpreting = true,
                    languageKnown = true,
                    keyConfigured = true,
                    failures = false)
                .isEmpty(),
        )
    }

    /** And with a failure it is item 17's own sentence, and the list's own way in. */
    @Test
    fun aFailureWithAKnownLanguageDrawsTheFailedBanner() {
        val notices =
            ConversationHost.notices(
                interpreting = true,
                languageKnown = true,
                keyConfigured = true,
                failures = true)
        Assert.assertEquals(1, notices.size)
        Assert.assertEquals(R.string.tulkki_state_failed, notices.first().words)
        Assert.assertEquals(listOf(NoticeAction.SEE_FAILURES), notices.first().actions)
    }

    /** The other two states are hosted too, so the banner is one surface and not a failure-only one. */
    @Test
    fun anUnknownLanguageStillOffersItsFix() {
        val withKey =
            ConversationHost.notices(
                interpreting = true,
                languageKnown = false,
                keyConfigured = true,
                failures = false)
        Assert.assertEquals(listOf(NoticeAction.CHANGE_LANGUAGE), withKey.first().actions)
        val withoutKey =
            ConversationHost.notices(
                interpreting = true,
                languageKnown = false,
                keyConfigured = false,
                failures = false)
        Assert.assertEquals(listOf(NoticeAction.OPEN_SETTINGS), withoutKey.first().actions)
    }

    /**
     * `SEE_FAILURES` reaches the settings row's own screen: the conversation passes its uuid, the
     * activity hosts the same fragment with it, and the fragment turns it into the state's own filter.
     * A parallel list built for the banner - a second fragment, a second read - fails every clause.
     */
    @Test
    fun seeFailuresReachesTheSettingsRowsOwnFailuresScreen() {
        val fragment = read("ui/src/main/java/uk/xa0/tulkki/ui/ConversationFragment.kt")
        Assert.assertTrue(
            "the banner's link must name the conversation it came from",
            fragment.contains("SettingsActivity.EXTRA_FAILURES_CONVERSATION, currentConversation.getUuid()"),
        )
        val activity = read("ui/src/main/java/uk/xa0/tulkki/ui/activity/SettingsActivity.kt")
        Assert.assertTrue(
            "the activity must host the settings row's fragment, not a second screen",
            activity.contains("TranslationFailuresFragment.forConversation(failuresConversation)"),
        )
        val screen = read("ui/src/main/java/uk/xa0/tulkki/ui/TranslationFailuresFragment.kt")
        Assert.assertTrue(
            "and the fragment must feed the conversation into the state's per-conversation filter",
            screen.contains("FailuresState(blocker, waiting, rows, conversationFilter())"),
        )
        val settingsRow = read("ui/src/main/java/uk/xa0/tulkki/ui/TulkkiSettingsFragment.kt")
        Assert.assertTrue(
            "the settings row must reach the same fragment class",
            settingsRow.contains("TranslationFailuresFragment()"),
        )
    }

    /**
     * Nothing on the banner spends: every action it carries is navigation - the picker, the settings
     * screen, the failures list - and no route re-attempts a translation or a send. The retry stays
     * the owner's tap on a row, so this is the cell that would catch a "retry now" creeping on to the
     * banner's own fix list.
     */
    @Test
    fun noBannerActionSpendsACall() {
        val fragment = read("ui/src/main/java/uk/xa0/tulkki/ui/ConversationFragment.kt")
        // `public` is the truth: the Compose banner reaches this through
        // `ConversationEvents.onNoticeAction`, which the fragment implements (it passes itself as the
        // events at `ConversationHost.showPage`), so the declaration cannot be private.
        val handler = method(fragment, "override fun onNoticeAction(")
        Assert.assertTrue("the picker is the unknown language's fix", handler.contains("showLanguagePicker()"))
        Assert.assertTrue("the key's fix is the settings screen", handler.contains("openTulkkiSettings()"))
        Assert.assertTrue("the failure's fix is the list", handler.contains("openTranslationFailures()"))
        for (spend in SPENDING) {
            Assert.assertFalse("a banner action must not run $spend", handler.contains(spend))
        }
    }

    /**
     * The switch is item 16's, and off it is not drawn at all: `null` is the same shape the composer
     * gives the interpreter-off composer, so a plain client gets no switch that would do nothing.
     */
    @Test
    fun offOffersNoSwitch() {
        Assert.assertNull(
            "off, the surface must not be drawn at all",
            ConversationHost.doubtHold(interpreting = false, stored = true),
        )
    }

    /**
     * And the switch shows the value **in force**: "never chose" follows the shipped default, which
     * is on, so a room the owner never touched reads as held rather than as a third position. The
     * resolution is `UiDoubtHold`'s, which is the one place in this module that may answer it.
     */
    @Test
    fun theSwitchShowsTheValueInForce() {
        Assert.assertEquals(
            "never chose follows the build's default",
            UiDoubtHold(inForce = true),
            ConversationHost.doubtHold(interpreting = true, stored = null),
        )
        Assert.assertEquals(
            "an explicit on is the owner's",
            UiDoubtHold(inForce = true),
            ConversationHost.doubtHold(interpreting = true, stored = true),
        )
        Assert.assertEquals(
            "and an explicit off is too",
            UiDoubtHold(inForce = false),
            ConversationHost.doubtHold(interpreting = true, stored = false),
        )
    }

    /**
     * The live composer draws the switch `ui-9` wrote, and stores only the owner's answer: the one
     * route to the surface is the composer's own top row, the value read is the entity's own tri-state
     * resolved by [ConversationHost.doubtHold], and the write is the conversation's own field,
     * persisted the way the language override is. A hand-built switch, a read of the in-force value out
     * of the fragment, or a second host beside the composer's row is a second answer to a question with
     * one home.
     */
    @Test
    fun theLiveSwitchIsTheComposersRowAndStoresTheOwnersAnswer() {
        val fragment = read("ui/src/main/java/uk/xa0/tulkki/ui/ConversationFragment.kt")
        val refresh = method(fragment, "private fun refreshTulkkiComposer(")
        Assert.assertTrue(
            "the switch rides the composer's one state write, resolved by the host",
            refresh.contains("ConversationHost.doubtHold(interpreting(), currentConversation.getDoubtHold())") &&
                refresh.contains("ConversationHost.composer("),
        )
        Assert.assertTrue(
            "the stored column is what the fragment reads",
            refresh.contains("currentConversation.getDoubtHold()"),
        )
        Assert.assertFalse(
            "and no second switch host survives beside it",
            fragment.contains("refreshTulkkiDoubtHold") ||
                fragment.contains("tulkkiDoubtHold") ||
                fragment.contains("showDoubtHold"),
        )
        // `public` is the truth here too: the Compose composer reaches this through
        // `ConversationEvents.onDoubtHoldChanged`. The write itself lives in `storeDoubtHold`, the one
        // home this handler shares with the composer's own switch, so the pin follows the delegation.
        val changed = method(fragment, "override fun onDoubtHoldChanged(")
        Assert.assertTrue(
            "the interface handler delegates to the one home for the owner's answer",
            changed.contains("storeDoubtHold(hold)"),
        )
        val store = method(fragment, "private fun storeDoubtHold(")
        Assert.assertTrue(
            "the owner's answer goes to the conversation's own field",
            store.contains("currentConversation.setDoubtHold(hold)"),
        )
        Assert.assertTrue(
            "and is persisted the way every other conversation field is",
            store.contains("updateConversation(currentConversation)"),
        )
        Assert.assertFalse(
            "the fragment must not resolve \"never chose\" itself",
            fragment.contains("DoubtHold.inForce("),
        )
        Assert.assertTrue(
            "and no second switch view is drawn: the layout that could hold one is gone",
            theLayoutIsGone(),
        )
    }

    /**
     * The composer is hosted and its two verbs reach the one path. `ui-9` wrote `ConversationComposer`
     * and `ConversationEvents.onDraftChanged`/`onSend`, and the send decision moved to
     * `ui/composer/ComposerSend.kt` - but nothing called them, so the gate, the hold and the prompt on
     * the Compose side were unreachable. This is the cell that would catch the host being torn out
     * again: the fragment sets the composer's content, its draft is drawn back into the controlled
     * field, its send runs the very `sendMessage()` the Java row ran, and its attach affordance opens
     * the Java menu rather than inventing one.
     */
    @Test
    fun theComposerIsHostedAndItsDraftAndSendReachTheOnePath() {
        val host = read("ui/src/main/java/uk/xa0/tulkki/ui/conversation/ConversationHost.kt")
        Assert.assertTrue(
            "the host must be able to set the page's content",
            host.contains("fun showPage("),
        )
        Assert.assertTrue(
            "and to assemble its state, so the fragment cannot hand over a half-filled composer",
            host.contains("fun composer(") &&
                host.contains("draft: TextFieldValue,") &&
                host.contains("language: UiLanguagePair?,") &&
                host.contains("reply: UiQuote?,"),
        )
        Assert.assertTrue(
            "and to build the field's own value from the three facts a Java caller has",
            host.contains("fun draft(text: String, selectionStart: Int, selectionEnd: Int): TextFieldValue"),
        )
        Assert.assertTrue(
            "and to read the caret back, because `TextRange` is a value class Java cannot unpack",
            host.contains("fun selectionStart(draft: TextFieldValue): Int") &&
                host.contains("fun selectionEnd(draft: TextFieldValue): Int"),
        )
        val fragment = read("ui/src/main/java/uk/xa0/tulkki/ui/ConversationFragment.kt")
        val setup = method(fragment, "private fun setupTulkkiPage(")
        Assert.assertTrue(
            "the live conversation draws its one page, and the composer is inside it",
            setup.contains("ConversationHost.showRoot(") &&
                setup.contains("ConversationHost.showPage(") &&
                setup.contains("tulkkiPageInputs"),
        )
        Assert.assertTrue(
            "and the Java text row it replaced is gone with the layout, so there is no second field",
            theLayoutIsGone() && !fragment.contains("binding.textinput"),
        )
        // The controlled field: the draft the owner types is written back, or the field never shows it.
        val draft = method(fragment, "override fun onDraftChanged(")
        Assert.assertTrue(
            "the typed draft reaches the one write, which remembers it and draws it back",
            draft.contains("writeComposerDraft(draft)"),
        )
        val write = method(fragment, "private fun writeComposerDraft(draft: TextFieldValue)")
        Assert.assertTrue(
            "and that write is the draft's one home, with the typing machine the field used to run",
            write.contains("this.tulkkiDraft = draft") &&
                write.contains("triggerKeyboardEvents(draft.text.length)") &&
                write.contains("refreshTulkkiComposer()"),
        )
        // The lift: the field carries text and caret, and there is no mirror to carry them into.
        Assert.assertTrue(
            "the draft is a TextFieldValue, not words alone",
            fragment.contains("private var tulkkiDraft: TextFieldValue"),
        )
        Assert.assertTrue(
            "and the one reading of it is that value, not a field it is projected from",
            flat(fragment)
                .contains("private fun tulkkiComposerDraft(): TextFieldValue = this.tulkkiDraft"),
        )
        Assert.assertFalse(
            "so the mirror the send path used to read is gone with the Java field",
            fragment.contains("mirrorComposerDraft") || fragment.contains("adoptComposerFieldDraft"),
        )
        // `public` is the truth: the interface's handler is reached from the Compose send affordance.
        val send = method(fragment, "override fun onSend(")
        Assert.assertTrue(
            "the Compose send runs the one send path and no gate of its own",
            send.contains("sendMessage()"),
        )
        Assert.assertFalse(
            "the Compose side must not decide the outgoing rule itself",
            send.contains("ComposerSend.carriage(") || send.contains("ComposerGate"),
        )
        // The marker names the no-argument override: the Fragment lifecycle's `onAttach(Activity)` is
        // a different method and the first one by that prefix in the file. The anchor is the composer
        // now: the send button it used to be is deleted, and a `GONE` view anchored the popup at its
        // own origin rather than at the composer the owner taps. The composer is drawn by the page's
        // composition and has no view, so the page measures its own drawn corner and the anchor is
        // that measurement - the page's `ComposeView` (`pagerController.page1`) is the whole screen,
        // and anchoring there put the attach menu at the top of the page rather than over the input
        // row the owner tapped.
        val attach = method(fragment, "override fun onAttach() {")
        Assert.assertTrue(
            "the attach affordance opens the one Java menu",
            attach.contains("popAttachMenu()"),
        )
        Assert.assertTrue(
            "and the anchor is the drawn composer, not the deleted `GONE` send button",
            method(fragment, "private fun popAttachMenu(")
                .contains("showRowMenuAt(tulkkiComposerAnchor(), items)") &&
                flat(method(fragment, "private fun tulkkiComposerAnchor()"))
                    .contains("tulkkiComposerSession.anchorInWindow"),
        )
    }

    /**
     * The request-to-speak affordance is the muted conversation's only way out, and it is drawn
     * exactly when the request is possible. The Java `requestVoice` button sat in the `GONE` row with
     * the send path's field, so a room the owner could not write in had no route to the ask at all.
     * The cell pins the four halves: the composer carries `canWrite`, the request replaces the send
     * icon exactly when it is false, the tap is the interface's own verb, and the fragment answers it
     * with the one service call while no Java button survives.
     */
    @Test
    fun theRequestToSpeakAffordanceAppearsExactlyWhenWritingIsImpossible() {
        val composer = read("ui/src/main/java/uk/xa0/tulkki/ui/conversation/UiComposer.kt")
        Assert.assertTrue(
            "the composer carries the host's answer to whether a message is possible",
            composer.contains("val canWrite: Boolean = true"),
        )
        val drawing = read("ui/src/main/java/uk/xa0/tulkki/ui/conversation/ConversationComposer.kt")
        Assert.assertTrue(
            "and the request replaces the send icon exactly when it is false",
            drawing.contains("if (composer.canWrite)") && drawing.contains("RequestVoice(events)"),
        )
        val events = read("ui/src/main/java/uk/xa0/tulkki/ui/conversation/ConversationEvents.kt")
        Assert.assertTrue(
            "the ask is a verb on the interface, so the screen and the host cannot disagree",
            events.contains("fun onRequestVoice()"),
        )
        val fragment = read("ui/src/main/java/uk/xa0/tulkki/ui/ConversationFragment.kt")
        Assert.assertTrue(
            "the live tap is the one service call and its confirmation",
            // The Java wrote the call on one line; the Kotlin file gives each argument its own, so
            // the call itself is compared with its whitespace collapsed.
            flat(method(fragment, "override fun onRequestVoice("))
                .contains(
                    "requestVoice( currentConversation.getAccount() ?: throw NullPointerException(), currentConversation.getJid() ?: throw NullPointerException(),",
                ),
        )
        Assert.assertFalse(
            "and no Java request button survives beside the Compose one",
            fragment.contains("binding.requestVoice"),
        )
        Assert.assertTrue(
            "nor is one drawn: the layout that could hold it is gone",
            theLayoutIsGone(),
        )
    }

    /**
     * Thread selection is the Compose marker's: the Java `threadIdenticonLayout` and its two children
     * sat in the `GONE` row, so the whole surface was drawn and tappable nowhere. The cell pins the
     * four halves - the typed state, the two separate gestures, the interface verbs and the
     * fragment's own bodies - and that no Java marker view is left to be a second answer.
     */
    @Test
    fun theThreadMarkerIsDrawnWithItsOwnTwoGesturesAndTheJavaOneIsGone() {
        val composer = read("ui/src/main/java/uk/xa0/tulkki/ui/conversation/UiComposer.kt")
        Assert.assertTrue(
            "the composer carries the marker's typed state, null drawing no marker",
            composer.contains("val thread: UiThread? = null"),
        )
        val drawing = read("ui/src/main/java/uk/xa0/tulkki/ui/conversation/ConversationComposer.kt")
        Assert.assertTrue(
            "and draws it with its own separate tap and long press",
            drawing.contains("composer.thread?.let") &&
                drawing.contains("events.onThreadTap()") &&
                drawing.contains("events.onThreadLongPress()"),
        )
        val events = read("ui/src/main/java/uk/xa0/tulkki/ui/conversation/ConversationEvents.kt")
        Assert.assertTrue(
            "the two gestures are two verbs on the interface",
            events.contains("fun onThreadTap()") && events.contains("fun onThreadLongPress()"),
        )
        val fragment = read("ui/src/main/java/uk/xa0/tulkki/ui/ConversationFragment.kt")
        Assert.assertFalse(
            "no Java identicon view survives beside the Compose marker",
            fragment.contains("binding.threadIdenticon"),
        )
        Assert.assertTrue(
            "the tap is the Java click's own body",
            method(fragment, "override fun onThreadTap(").contains("newThread()"),
        )
        Assert.assertTrue(
            "and the long press is the Java long-click's",
            method(fragment, "override fun onThreadLongPress(")
                .contains("newThreadTutorialToast"),
        )
        Assert.assertTrue(
            "and no marker is drawn: the layout that could hold one is gone",
            theLayoutIsGone(),
        )
    }

    /**
     * The `:shortcode:`/`@` autocomplete is **retired**, and the reason is the field it was bound to:
     * `Autocomplete` attaches to the Java `EditMessage`, which the Compose composer hides and which
     * therefore receives no input, so neither popup could fire. Re-hosting it is a Compose popup the
     * redesign owns, not a port, and the migration licence records the removal rather than leaving
     * dead wiring. The drawn field carries the one half that *is* portable - the IME's send action -
     * because the `EditText` listener that answered it could never run either.
     */
    @Test
    fun theAutocompleteIsRetiredAndTheDrawnFieldCarriesTheImeAction() {
        val fragment = read("ui/src/main/java/uk/xa0/tulkki/ui/ConversationFragment.kt")
        Assert.assertFalse(
            "no `Autocomplete` popup is bound to the hidden field any more",
            fragment.contains("Autocomplete.<"),
        )
        val drawing = read("ui/src/main/java/uk/xa0/tulkki/ui/conversation/ConversationComposer.kt")
        Assert.assertTrue(
            "the drawn field carries the send IME action the hidden field's listener owned",
            drawing.contains("imeAction = ImeAction.Send") && drawing.contains("KeyboardActions(onSend"),
        )
    }

    /**
     * The drawn list takes the fragment's own update path, not only the file's watch. `refresh()` is
     * the one notification a send and an arrival both end in, and it re-read the Java list and
     * notified the `GONE` adapter alone: the Compose list - the one that is drawn - was fed by
     * `ConversationRead.stream` and nothing else, so a row appended after that single subscription
     * appeared only if the file's `Flow` re-emitted. This is the cell that would have caught that: the
     * notification path must write the drawn session with the same read the list is built from, the
     * first fill must not wait on the watch either, and the read must run off the main thread because
     * Room's blocking query refuses it.
     */
    @Test
    fun theDrawnListIsFedByTheHostsUpdatePathAndNotOnlyByTheFileWatch() {
        val fragment = read("ui/src/main/java/uk/xa0/tulkki/ui/ConversationFragment.kt")
        val refresh = method(fragment, "private fun refresh(notifyConversationRead: Boolean) {")
        Assert.assertTrue(
            "the one message-update notification must write the drawn session",
            refresh.contains("refreshTulkkiMessageRows()"),
        )
        val setup = method(fragment, "private fun refreshTulkkiMessages(")
        Assert.assertTrue(
            "the first fill must not open on the loading shape while the watch has not emitted",
            setup.contains("refreshTulkkiMessageRows()"),
        )
        val rows = method(fragment, "private fun refreshTulkkiMessageRows(")
        Assert.assertTrue(
            "and it must write the session with the read the list is built from",
            rows.contains("ConversationRead.read(host, uuid)") && rows.contains("session.update(rows)"),
        )
        Assert.assertTrue(
            "the read must run off the main thread - Room's blocking query refuses it",
            rows.contains("backgroundExecutor.execute {") && rows.contains("runOnUiThread {"),
        )
        val read = read("ui/src/main/java/uk/xa0/tulkki/ui/conversation/ConversationRead.kt")
        Assert.assertTrue(
            "the pushed read is the read object's own statement, not a second SQL spelling",
            read.contains("fun read(context: Context, conversation: String): List<MessageSnapshot>") &&
                read.contains("MessageSnapshots.get(context).read(conversation)"),
        )
    }

    /**
     * The reply seam's quote answer carries no database read. The projection asks it while the
     * composition is running on the main thread, and Room's blocking `MessageSnapshots.readOne`
     * refuses that thread (`performBlocking`'s `assertNotMainThread`) - the body this replaces threw
     * for every reply row. The row is answered from the snapshots the host read off the main thread
     * and cached, which is the same read the drawn list is built from. A source cell, because the
     * live implementation needs the running Activity.
     */
    @Test
    fun theLiveQuoteAnswerComesFromTheHostsCachedRowsAndNotFromTheDatabase() {
        val activity = read("ui/src/main/java/uk/xa0/tulkki/ui/XmppActivity.kt")
        Assert.assertFalse(
            "a blocking Room read must not be reachable from the composition's thread",
            activity.contains("MessageSnapshots.get("),
        )
        Assert.assertTrue(
            "the seam answers from the rows the host read off the main thread",
            activity.contains("messageRows[conversationId]?.get(uuid)"),
        )
        val fragment = read("ui/src/main/java/uk/xa0/tulkki/ui/ConversationFragment.kt")
        Assert.assertTrue(
            "and the host keeps that cache in step with the session's own rows write, not beside it",
            // The Java's `setOnRows(...)` is an assignment to `onRows` plus a SAM constructor, on
            // three lines; collapsed, the one expression is still the anchor.
            flat(method(fragment, "private fun refreshTulkkiMessages("))
                .contains(
                    "onRows = java.util.function.Consumer<List<MessageSnapshot>> { rows -> cacheHost.cacheMessageRows(uuid, rows) }",
                ),
        )
    }

    /**
     * §4.3's anchoring, and the three things it must not confuse. The reader's place is kept by a
     * reading of the *position*, so a place captured when the rows last moved cannot describe a scroll
     * the reader has since left; the reader's own answer about the bottom ("follow the newest row") is
     * written **only when that position moved and nothing else changed**, so a list that merely grew
     * cannot read as a reader who scrolled away; and the place itself is recorded under the
     * item-count guard, because the same index names a different row while a change is being applied.
     * A source cell, because the screen's half of this is a `LazyListState` a JVM test cannot reach.
     *
     * <p>The pin is what the owner asked for: the list follows the newest row - on an arrival, and on
     * the keyboard taking the viewport's lower half - unless the reader moved away from it. The old
     * shape asked the *place* for that answer, and the place is written from layouts that grew, so the
     * grown list and the scrolled reader were the confusion the cell now forbids.
     */
    @Test
    fun theAnchorIsTheReadersPresentPlaceAndThePinIsTheReadersOwnAnswer() {
        val screen = read("ui/src/main/java/uk/xa0/tulkki/ui/conversation/ConversationScreen.kt")
        Assert.assertTrue(
            "the reader's place must be kept by a reading of the position, not by a rows change",
            screen.contains("position =") &&
                screen.contains(
                    "listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset",
                ),
        )
        Assert.assertTrue(
            "the pin is the reader's own answer, written only when the position moved and nothing else changed",
            screen.contains("!changed && reading.position != lastPosition") &&
                screen.contains("pinned = !reading.canScrollForward"),
        )
        Assert.assertTrue(
            "the pin and the keyboard's own height travel inside the reading, not beside it",
            screen.contains("canScrollForward = listState.canScrollForward") &&
                screen.contains("ime = currentIme"),
        )
        Assert.assertTrue(
            "a layout that grew - content, viewport or the keyboard - is not a scroll and must not be recorded as one",
            screen.contains("reading.keys != lastKeys") &&
                screen.contains("reading.viewport != lastViewport") &&
                screen.contains("reading.ime != lastIme") &&
                screen.contains(
                    "!changed && visible.isNotEmpty() && layout.totalItemsCount == placeCount",
                ),
        )
        Assert.assertTrue(
            "a pinned list follows the newest row when the list's shape moves",
            screen.contains("changed && pinned -> listState.scrollToItem(reading.keys.lastIndex)"),
        )
        Assert.assertTrue(
            "and an unpinned one is handed back the row it was on",
            screen.contains("val target = Anchor.after(before, reading.keys)"),
        )
        Assert.assertTrue(
            "the opening is its own sentinel, not the place",
            screen.contains("!opened ->"),
        )
        Assert.assertFalse(
            "and nothing else asks the place whether the reader was at the bottom",
            screen.contains("before.atBottom &&") || screen.contains("before == null && keys.isNotEmpty()"),
        )
    }

    /**
     * The Java row is gone, and only what has a live job left survives it. The send button went first
     * (its anchor and its action dispatch moved to the composer and the one send path), and the field
     * it stood beside is gone with it: the send path reads the Compose draft, so a mirror kept for it
     * would be a second home for the one draft. The Java list is gone with the rest of the layout, and
     * only its adapter stays - instantiated and mutated by the app's update path, drawing nothing.
     */
    @Test
    fun theDeadRowLosesOnlyWhatHasNoLiveJobLeft() {
        val fragment = read("ui/src/main/java/uk/xa0/tulkki/ui/ConversationFragment.kt")
        Assert.assertFalse(
            "the Java send button is gone with its anchor and its action dispatch",
            fragment.contains("binding.textSendButton"),
        )
        Assert.assertTrue(
            "and the row is gone with the layout: no Java field, badge or list view is left to draw",
            theLayoutIsGone() &&
                !fragment.contains("binding.textinput") &&
                !fragment.contains("binding.unreadCountCustomView") &&
                !fragment.contains("binding.scrollToBottomButton") &&
                !fragment.contains("messagesView"),
        )
        Assert.assertTrue(
            "while the adapter that drew nothing is still instantiated and mutated",
            fragment.contains("messageListAdapter.notifyDataSetChanged()"),
        )
    }

    /**
     * The reply preview is the Compose one and the Java one is gone, not merely hidden. A `GONE`
     * Java preview would still be a second place a quoted row is drawn, and the preview may only
     * exist at all because the quote's own decision - `ReplyQuote`, then `UiQuote` - covers it. The
     * cell pins both halves: the fragment fills `UiComposer.reply` from the projection and no Java
     * `contextPreview` view is written anywhere, so a re-added Java preview fails here.
     */
    @Test
    fun theReplyPreviewIsTheComposeOneAndTheJavaOneIsGone() {
        val host = read("ui/src/main/java/uk/xa0/tulkki/ui/conversation/ConversationHost.kt")
        Assert.assertTrue(
            "the composer's state takes the reply the fragment opened",
            host.contains("reply: UiQuote?,") && host.contains("reply = reply"),
        )
        val composer = read("ui/src/main/java/uk/xa0/tulkki/ui/conversation/ConversationComposer.kt")
        Assert.assertTrue(
            "and the preview carries its own dismiss",
            composer.contains("composer.reply?.let") && composer.contains("onReplyCancel()"),
        )
        val fragment = read("ui/src/main/java/uk/xa0/tulkki/ui/ConversationFragment.kt")
        val setup = method(fragment, "private fun setupReply(")
        Assert.assertTrue(
            "the fragment fills the quote from the projection, never a view",
            setup.contains("MessageProjection.replyPreview(") && setup.contains("refreshTulkkiComposer()"),
        )
        Assert.assertTrue(
            "and the reply the send path reads is still set",
            setup.contains("currentConversation.setReplyTo(message)"),
        )
        Assert.assertFalse(
            "no Java preview view survives beside the Compose one",
            fragment.contains("binding.contextPreview"),
        )
    }

    /**
     * The formatting bar is wired to the lifted draft, not left callerless. `TextFormatBar` was
     * written and nothing called it, and `showTextFormat` stood down while the Compose composer owned
     * the field so no marker landed in the hidden Java mirror; now the bar is drawn from
     * `UiComposer.formatting`, its four markers, `/me` and `> ` answer through the one
     * `onDraftChanged` the field already emits, and the host's `showTextFormat`/`hideTextFormat`
     * resolve the two-part IME condition into that flag.
     */
    @Test
    fun theFormattingBarIsWiredToTheLiftedDraft() {
        val composer = read("ui/src/main/java/uk/xa0/tulkki/ui/conversation/ConversationComposer.kt")
        Assert.assertTrue(
            "the bar is drawn only when the host resolved the condition",
            composer.contains("if (composer.formatting)") && composer.contains("TextFormatBar("),
        )
        val bar = read("ui/src/main/java/uk/xa0/tulkki/ui/conversation/TextFormatBar.kt")
        Assert.assertTrue(
            "the bar carries the two Java draft verbs and not only the four markers",
            bar.contains("DraftMarkup.me(draft)") && bar.contains("DraftMarkup.quoteLine(draft)"),
        )
        val host = read("ui/src/main/java/uk/xa0/tulkki/ui/conversation/ConversationHost.kt")
        Assert.assertTrue(
            "the host assembles the composer with the bar's visibility",
            host.contains("formatting: Boolean,") && host.contains("formatting = formatting"),
        )
        val fragment = read("ui/src/main/java/uk/xa0/tulkki/ui/ConversationFragment.kt")
        val show = method(fragment, "private fun showTextFormat(")
        Assert.assertTrue(
            "the hosted bar is shown by the same condition that showed the Java row",
            show.contains("this.tulkkiFormatting = true"),
        )
        Assert.assertTrue(
            "and the next read hands it to the composer",
            method(fragment, "private fun refreshTulkkiComposer(").contains("this.tulkkiFormatting"),
        )
        Assert.assertTrue(
            "the close control writes the preference and hides the bar, with its dialog on the bar",
            method(fragment, "override fun onFormattingClose(")
                .contains("putBoolean(\"showtextformatting\", false)"),
        )
    }

    /**
     * The staged strip is the Compose one and its thumbnails are built **off the main thread**: the
     * Java `MediaPreviewAdapter` loaded pixels on an `AsyncTask`, and a lift that called
     * `getPreviewForUri` while assembling the state would drop every image preview on the first
     * re-read. The cell pins the three halves - the background load, the `ImageBitmap` conversion,
     * and the drawing that accepts pixels of either kind - plus the two gestures the fragment owns.
     */
    @Test
    fun theStagedStripIsTheComposeOneWithBackgroundThumbnails() {
        val staged = read("ui/src/main/java/uk/xa0/tulkki/ui/conversation/StagedAttachments.kt")
        Assert.assertTrue(
            "the pixels are built off the main thread",
            staged.contains("withContext(Dispatchers.IO)") && staged.contains("getPreviewForUri"),
        )
        Assert.assertTrue(
            "and converted to the Compose bitmap the strip takes",
            staged.contains("asImageBitmap()"),
        )
        val composer = read("ui/src/main/java/uk/xa0/tulkki/ui/conversation/ConversationComposer.kt")
        Assert.assertTrue(
            "the strip draws pixels whenever the host built them, whichever kind they belong to",
            composer.contains("val thumbnail = attachment.thumbnail") &&
                composer.contains("if (thumbnail != null)"),
        )
        val host = read("ui/src/main/java/uk/xa0/tulkki/ui/conversation/ConversationHost.kt")
        Assert.assertTrue(
            "the composer's state takes the staged strip",
            host.contains("attachments: List<UiPendingAttachment>,") && host.contains("attachments = attachments"),
        )
        val fragment = read("ui/src/main/java/uk/xa0/tulkki/ui/ConversationFragment.kt")
        Assert.assertTrue(
            "the live strip is the staged list, not a second Java one",
            fragment.contains("StagedAttachments(") && fragment.contains("stagedStrip()"),
        )
        Assert.assertTrue(
            "an image tap is the editor and a remove drops exactly that attachment",
            method(fragment, "override fun onAttachmentTap(").contains("editImage(") &&
                method(fragment, "override fun onAttachmentRemoved(").contains("stagedAttachments.remove("),
        )
    }

    /**
     * The language chip is the Compose one and its tap is the picker. The Java chip went `GONE` with
     * the row it sat in - the send path's own field is the only thing that row keeps - so leaving the
     * picker on the Java chip's listener made the conversation's language unreachable in the UI, and
     * that is a decision this app makes visible and overridable on purpose. The cell pins the three
     * halves: the drawn chip's two tiers come from `LanguageChip`'s rule, the tap is the interface's
     * own verb, and the fragment answers it with the one picker rather than a second dialog.
     */
    @Test
    fun theLanguageChipIsDrawnWithItsOwnTapAndTheJavaChipIsGone() {
        val composer = read("ui/src/main/java/uk/xa0/tulkki/ui/conversation/ConversationComposer.kt")
        Assert.assertTrue(
            "the chip's two tiers are the LanguageChip rule, not two locals a rewrite could swap",
            composer.contains("LanguageChip.tiers(") && composer.contains("text = tiers.app"),
        )
        Assert.assertTrue(
            "and the tap is the interface's verb, on the block the owner taps",
            composer.contains("events.onLanguageChipTap()"),
        )
        val events = read("ui/src/main/java/uk/xa0/tulkki/ui/conversation/ConversationEvents.kt")
        Assert.assertTrue(
            "the chip's tap is a verb on the interface, so the screen and the host cannot disagree",
            events.contains("fun onLanguageChipTap()"),
        )
        val fragment = read("ui/src/main/java/uk/xa0/tulkki/ui/ConversationFragment.kt")
        Assert.assertTrue(
            "the live chip's tap opens the one picker",
            method(fragment, "override fun onLanguageChipTap(").contains("showLanguagePicker()"),
        )
        Assert.assertFalse(
            "and no Java chip survives beside the Compose one",
            fragment.contains("binding.tulkkiLanguageChip") || fragment.contains("updateLanguageChip"),
        )
        Assert.assertTrue(
            "no second chip is drawn: the layout that could hold one is gone",
            theLayoutIsGone(),
        )
    }

    /**
     * The emoji affordance is the Compose composer's, and it is the picker's only way in. The Java
     * row drew two triggers - `emojiButton` opened the panel, `keyboardButton` closed it - and both
     * sat in the row the Compose composer hides, so the panel `EmojiPanelHost` installed had no
     * reachable trigger at all. The cell pins the three halves: the affordance is drawn in the
     * composer, the tap is the interface's own verb, and the fragment answers it with the panel's
     * controller rather than a second panel.
     */
    @Test
    fun theEmojiAffordanceIsDrawnWithItsOwnTapAndTheJavaTriggersAreGone() {
        val composer = read("ui/src/main/java/uk/xa0/tulkki/ui/conversation/ConversationComposer.kt")
        Assert.assertTrue(
            "the affordance is drawn and emits the interface's verb",
            composer.contains("events.onEmojiTap()"),
        )
        val events = read("ui/src/main/java/uk/xa0/tulkki/ui/conversation/ConversationEvents.kt")
        Assert.assertTrue(
            "the tap is a verb on the interface, so the screen and the host cannot disagree",
            events.contains("fun onEmojiTap()"),
        )
        val fragment = read("ui/src/main/java/uk/xa0/tulkki/ui/ConversationFragment.kt")
        val tap = method(fragment, "override fun onEmojiTap(")
        Assert.assertTrue(
            "the live tap opens and closes the one panel",
            tap.contains("emojiPanelController.open()") && tap.contains("closeEmojiPanel()"),
        )
        Assert.assertFalse(
            "and no Java trigger survives beside the Compose one",
            fragment.contains("memojiButtonListener") ||
                fragment.contains("mkeyboardButtonListener") ||
                fragment.contains("backPressedLeaveEmojiPicker"),
        )
        Assert.assertTrue(
            "no second pair of buttons is drawn: the layout that could hold them is gone",
            theLayoutIsGone(),
        )
    }

    /**
     * The panel is drawn by the conversation page's own composition, not inside a hidden Java row.
     * `EmojiPanelContent` is `EmojiPanelHost.install`'s body without the view, and the page calls it
     * after the composer it belongs to; the layout that could have held a second host, inside the row
     * the deleted composer hid, is gone.
     */
    @Test
    fun theEmojiPanelIsHostedOutsideTheHiddenJavaRow() {
        val host = read("ui/src/main/java/uk/xa0/tulkki/ui/conversation/ConversationHost.kt")
        Assert.assertTrue(
            "the page draws the panel's own door, after the composer it belongs to",
            host.contains("EmojiPanelContent(inputs.emoji, inputs.emojiPicked)") &&
                host.indexOf("ConversationComposer(composer =") <
                    host.indexOf("EmojiPanelContent(inputs.emoji, inputs.emojiPicked)"),
        )
        Assert.assertTrue(
            "and the layout that could hold a second host, inside a hidden Java row, is gone",
            theLayoutIsGone(),
        )
    }

    /**
     * The field's hint is the drawn field's, because that is the one the owner can see. The Java
     * `EditText`'s `setHint` and the `textInputHint` strip both sit inside the row the composer hides,
     * so "You are muted", a private-message addressee and the correction sentence were resolved and
     * then written where nothing draws them. The one resolver now hands its sentence to the drawn
     * field, which falls back to the shipped placeholder when the host has none.
     */
    @Test
    fun theHintReachesTheDrawnFieldNotOnlyTheHiddenRow() {
        val composer = read("ui/src/main/java/uk/xa0/tulkki/ui/conversation/UiComposer.kt")
        Assert.assertTrue(
            "the composer carries the field's hint",
            composer.contains("val hint: String? = null"),
        )
        val drawing = read("ui/src/main/java/uk/xa0/tulkki/ui/conversation/ConversationComposer.kt")
        Assert.assertTrue(
            "and the field draws it, falling back to the shipped placeholder",
            drawing.contains("hint = composer.hint") &&
                drawing.contains("hint ?: stringResource(R.string.tulkki_chat_hint)"),
        )
        val fragment = read("ui/src/main/java/uk/xa0/tulkki/ui/ConversationFragment.kt")
        val hints = method(fragment, "fun updateChatMsgHint(")
        Assert.assertTrue(
            "the one hint resolver is also the one writer of the drawn state",
            hints.contains("this.tulkkiComposerHint = hint") &&
                hints.contains("refreshTulkkiComposer()") &&
                hints.contains("R.string.you_are_not_participating") &&
                hints.contains("R.string.send_private_message_to"),
        )
    }

    /**
     * The overflow menu carries no second language entry. `action_tulkki_language` existed for "the
     * bar that has been dismissed", which the chip never is - it is the field's own row - and it was
     * the one drawn surface that named the conversation's language without the pair. Its removal is
     * only safe while the one picker stays reachable, so the cell pins both halves.
     */
    @Test
    fun theOverflowCarriesNoSecondLanguageEntry() {
        val fragment = read("ui/src/main/java/uk/xa0/tulkki/ui/ConversationFragment.kt")
        Assert.assertFalse(
            "the overflow entry and its title logic are gone",
            fragment.contains("action_tulkki_language") ||
                fragment.contains("updateTulkkiLanguageMenuItem") ||
                fragment.contains("tulkkiLanguageMenuItem"),
        )
        Assert.assertFalse(
            "and the menu that carried it is gone, so no row can carry it again",
            Files.exists(locate("ui/src/main/res/menu/fragment_conversation.xml")),
        )
        Assert.assertTrue(
            "the one picker is still reachable from the chip and from the banner",
            method(fragment, "override fun onLanguageChipTap(").contains("showLanguagePicker()") &&
                method(fragment, "override fun onNoticeAction(").contains("showLanguagePicker()"),
        )
    }

    /**
     * The live list is shown with the owner's drawing switches, not with the screen's own defaults.
     * `ConversationMessages` defaults `avatarsOn = false` and `colorful = true` for a caller that has no
     * settings - a preview, a JVM cell - and `showMessages` passed neither, so a live conversation drew
     * the colourful family for an owner whose `use_green_background` is off by default, and no avatar
     * column at all for one who had `show_avatars` on. The reserved column and the avatar's edge are
     * geometry, so this is a layout the owner never asked for, and a changed switch must re-show the
     * rows rather than wait for the conversation to be left and entered again.
     */
    @Test
    fun theLiveListIsShownWithTheOwnersDrawingSwitches() {
        val host = read("ui/src/main/java/uk/xa0/tulkki/ui/conversation/ConversationHost.kt")
        Assert.assertTrue(
            "the page's own inputs take the drawing switches",
            host.contains("internal var avatarsOn: Boolean") &&
                host.contains("internal var colorful: Boolean") &&
                host.contains("internal var locale: Locale") &&
                host.contains("internal var zone: ZoneId"),
        )
        Assert.assertTrue(
            "and the page draws the list with them, not with the screen's defaults",
            host.contains("avatarsOn = inputs.avatarsOn,") &&
                host.contains("colorful = inputs.colorful,") &&
                host.contains("locale = inputs.locale,") &&
                host.contains("zone = inputs.zone,"),
        )
        val fragment = read("ui/src/main/java/uk/xa0/tulkki/ui/ConversationFragment.kt")
        val show = method(fragment, "private fun refreshTulkkiMessages(")
        Assert.assertTrue(
            "the fragment reads the owner's own answers",
            show.contains("ChatAppearance.of(AppSettings(hostActivity))"),
        )
        Assert.assertTrue(
            "and a changed switch re-shows the rows",
            show.contains("appearance.equals(tulkkiMessagesAppearance)"),
        )
        val defaults = read("data/src/main/res/values/defaults.xml")
        Assert.assertTrue(
            "the shipped appearance is the resources' own default, which is not the screen's",
            defaults.contains("<bool name=\"show_avatars\">false</bool>") &&
                defaults.contains("<bool name=\"use_green_background\">false</bool>") &&
                defaults.contains("<bool name=\"show_formatting_marks\">false</bool>") &&
                read("ui/src/main/java/uk/xa0/tulkki/ui/conversation/ChatAppearance.kt")
                    .contains(
                        "ChatAppearance(avatarsOn = false, colorful = false, formattingMarks = false)",
                    ),
        )
    }

    /**
     * The declaration that starts at [marker], up to its own closing brace. The Kotlin file indents
     * the members carried over from the Java by four spaces and the later additions by none, so the
     * declaration's own indentation - not a fixed four spaces - is what names its end.
     */

    private fun method(source: String, marker: String): String {
        val start = source.indexOf(marker)
        Assert.assertTrue("$marker is not in the fragment", start >= 0)
        val indent = start - (source.lastIndexOf('\n', start) + 1)
        val end = source.indexOf("\n" + " ".repeat(indent) + "}", start)
        Assert.assertTrue("$marker has no closing brace", end > start)
        return source.substring(start, end)
    }

    /**
     * Whether the conversation's layout is gone.
     *
     * Every "no Java view X is drawn" cell in this file used to read
     * `fragment_conversation.xml` and assert the id was not there. The file is deleted with the shell
     * that replaced it: the screen is built in code and composed, so there is no layout left to draw
     * any of them from - which is the strongest form of each of those claims.
     */
    private fun theLayoutIsGone(): Boolean =
        !Files.exists(locate("ui/src/main/res/layout/fragment_conversation.xml"))

    /**
     * [source] with every run of whitespace collapsed to one space. A few calls the Java wrote on one
     * line are one argument per line in Kotlin, and the anchor is the call rather than its line
     * breaks.
     */
    private fun flat(source: String): String = source.replace(Regex("\\s+"), " ")

    private fun read(relative: String): String =
        String(Files.readAllBytes(locate(relative).toAbsolutePath()), StandardCharsets.UTF_8)

    private fun locate(relative: String): Path = root().resolve(relative)

    /** The repository root, found by its `settings.gradle.kts` marker above the module working directory. */
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
        /** Every symbol a translation or a send's retry is spelled with, none of which a fix may run. */
        val SPENDING =
            listOf(
                "requestTranslation(",
                "sendHeldNow(",
                "sendAsWritten(",
                "translateHeldNow(",
            )
    }
}
