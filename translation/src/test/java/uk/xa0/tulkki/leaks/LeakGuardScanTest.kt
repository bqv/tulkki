package uk.xa0.tulkki.leaks

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.TreeMap
import java.util.regex.Pattern
import org.junit.Assert
import org.junit.Test

/**
 * The concealment rule as a build-time ratchet rather than a convention.
 *
 * <p>The rule is one sentence - a received message's original is stored and never drawn readable - but
 * it is enforced by many small decisions, and every one of them begins with somebody reading a body.
 * A review cannot keep that true across a rewrite: the same call that is a projection today is a leak
 * tomorrow if it is copied into a notification, a preview, a pin or an export. So this test reads the
 * <em>sources</em> and refuses any body read that is not on a named list, which turns "every display
 * site projects" from a habit into something a compiler run enforces.
 *
 * <p>What it is not: a proof that an allow-listed site is correct. It is a proof that no site is
 * <em>unreviewed</em> - each row is a decision somebody made and can be asked about, and the row's
 * count means a second call with the same name in the same file is a failure too, not a free ride.
 * The comments above each group say what that file's reads are for; the tests below the list pin the
 * shapes the audit found most dangerous (a bubble's second half, the preview's action branch, and the
 * body readers' count).
 *
 * <p>The scan is deliberately textual and deliberately dumb: it reads {@code src/main/java} and
 * {@code src/tulkki/java}, keeps only files whose declared package is ours and whose last package
 * segments are {@code services}, {@code ui}, {@code worker} or {@code utils}, strips comments (so a
 * comment that merely names an accessor is not a hit) and counts calls to the three body accessors.
 * It runs on the JVM beside the rest of the suite: no device, no build, and it survives the Kotlin
 * migration because it keys on the declared package and the file's simple name rather than on a
 * directory.
 */
class LeakGuardScanTest {

    /**
     * One reviewed read: a file, which accessor it calls, and how many calls of that name the file is
     * allowed to have. The count is the ratchet - adding a call to a file that already has one fails
     * even though the file is listed.
     */
    private class Allowed(val type: String, val accessor: String, val count: Int)

    private fun row(type: String, accessor: String, count: Int): Allowed {
        return Allowed(type, accessor, count)
    }

    /**
     * Every body read in a {@code services}, {@code ui}, {@code worker} or {@code utils} file, with
     * what it is for.
     *
     * <p>Read it as the answer to "why may this file read a body at all?". The two shapes that must
     * never appear again: a read whose result is drawn, and a read whose result leaves the app. The
     * rows that are neither are decisions (a display choice made in one place, a null check, a
     * comparison that decides whether a menu row is offered) or reads of something that is not a
     * {@code Message} at all - {@code XmppUri.getBody()}, {@code Invite.getBody()} and
     * {@code Call.getBody()} share the method name and none of them is a message.
     */
    private val ALLOWED =
            listOf(
                    // The notification's own text comes from UIHelper.getDisplayedBody /
                    // getMessagePreview; these are the nick-highlight decision (a boolean, nothing is
                    // drawn from the body), the "is there a body at all" null check, the translation
                    // queue's eligibility question and a null check on a reaction's parent.
                    row("uk.xa0.tulkki.app.services.NotificationService", "getBody", 4),
                    row("uk.xa0.tulkki.app.services.NotificationService", "getQuoteableBody", 1),
                    // The service's own plumbing: the decrypted PGP body, whether an edit replaced a
                    // body, and the two comparisons that decide "this message is only a link".
                    row("uk.xa0.tulkki.xmpp.services.XmppConnectionService", "getBody", 1),
                    row("uk.xa0.tulkki.xmpp.services.XmppConnectionService", "getRawBody", 3),
                    // The package the audit's own sentence was about and the scan could not see:
                    // UIHelper.getMessagePreview is the one preview/notification projection, and
                    // leaving `utils` out of SCANNED_PACKAGE kept the seam that carries the rule off
                    // the list entirely - no reviewed row, no count, and a hole (the /me branch, on
                    // getBody() with the reply fallback still in it) that no check could have caught.
                    // The eight reads: the deleted-body sentinel, an RTP session status, two
                    // "did this need translating" questions, the /me branch (getBody(true), pinned by
                    // thePreviewActionBranchDropsTheReplyFallback below), getDisplayedBody's two
                    // inbox-line projection reads, and the location-question comparison (a boolean,
                    // nothing drawn).
                    row("uk.xa0.tulkki.ui.utils.UIHelper", "getBody", 8),
                    // Not prose: the geo-URI probe and the coordinate readers, none of which is drawn.
                    // The `getQuoteableBody` row went with the map-preview removal: `MapPreviewUri`
                    // was the only reader of a quoted body here, and the services commit deleted it
                    // with the remote preview it built.
                    row("uk.xa0.tulkki.ui.utils.GeoHelper", "getRawBody", 3),
                    // The composer's reply-quote builder. Reviewed because the scan now sees it, not
                    // because it is safe: docs/MIGRATION.md "The leak audit" item 3 records that
                    // prepareQuote's raw read puts a concealed original into the composer, and that
                    // fix belongs to the quote path, not to this one. It stays a named, known-open
                    // read rather than a silent one.
                    row("uk.xa0.tulkki.data.utils.MessageUtils", "getQuoteableBody", 2),
                    // The two live reads are both getBody(true). The composer's reply preview
                    // (`replyPreviewQuote`) hands the referenced row's fallback-free text to
                    // `ReplyQuote.of`, and the correction editor (`correctMessage`) pre-fills the
                    // owner's own outgoing words through `HeldSend.draftOf`. The fifth read was the
                    // message menu's link-scheme probe, whose ShareUtil.concealed guard went with the
                    // menu: the Java list is GONE and MessageAdapter draws no bubble, so the
                    // long-press menu could never open and the Compose rows own the gesture. The
                    // pin's two reads - its own getBody and its getRawBody - went with the :ui port
                    // of the fragment. The third getBody(true) was the Java reply preview's
                    // second-half strip (`showReplyPreviewSecondHalf`), deleted when the preview
                    // became Compose (24d87eb1dd): `MessageProjection.replyPreview` builds a bare
                    // `UiQuote` and reads no bottom half at all, so the referenced original has no
                    // field to reach - the read went with the surface, not into an unreviewed one.
                    // The count is therefore two getBody(true) and no raw read (measured against
                    // ui/ConversationFragment.java, 2026-10-08). The ratchet moved down, not up:
                    // no unreviewed read appeared.
                    row("uk.xa0.tulkki.ui.ConversationFragment", "getBody", 2),
                    // The reply-fallback strip on the Compose path. The message seam's host is the one
                    // place that holds the entity, so it is the one place that can read the declared
                    // `urn:xmpp:reply:0` span's body. The two reads: `Message.getBody(true)` -
                    // `MessageAdapter.halves()`'s strip into `MessageFacts.strippedBody`, which the
                    // halves and a resolved quote are built from - and `Message.getBody()`, the composed
                    // body `MessageFacts.replyFallback` hands `ReplyFallback.of` so the declared span can
                    // be placed for a reply whose target is gone (the Java
                    // `MessageAdapter.replyFallback`). Neither reaches a view raw: the strip is the
                    // fallback-free side, and the fallback is `ReplyQuote.unresolved`'s, covered whenever
                    // the interpreter is on.
                    row("uk.xa0.tulkki.ui.XmppActivity", "getBody", 2),
                    // An XmppUri's body, from a link that opened the app - not a Message.
                    row("uk.xa0.tulkki.ui.ConversationListActivity", "getBody", 1),
                    // The search screen's own body reads, five of them, on its two paths. The quote
                    // (`quote`): `message.getBody()` is only the decision input to `DisplayedBody.of`,
                    // and what may leave is `message.getBody(true)` - the fallback-free text
                    // `ShareText.of` carries when nothing was translated - or the translation, or a
                    // refusal when the body is covered, which the caller reports instead of writing
                    // the original anywhere. The results (`searchItems`/`displayedBody`): the
                    // `Message.DATE_SEPARATOR_BODY == message.getBody()` sentinel comparison (a
                    // boolean), the body handed to `DisplayedBody.of` for the row's one display
                    // decision, and the "did this need translating?" question
                    // (`DisplayedBody.needsTranslation`, a boolean). A row draws the cover caption
                    // when `displayed.isBlurred()` and `displayed.text()` otherwise, so a body that
                    // needed translating and did not get one is never drawn raw. f9c3b428fa put the
                    // last three reads in search's own adapter; 41bb1db164 made the screen Compose and
                    // deleted the adapter, so those three are here now beside the quote path's two.
                    row("uk.xa0.tulkki.ui.SearchActivity", "getBody", 5),
                    row("uk.xa0.tulkki.ui.ShareViaAccountActivity", "getBody", 1),
                    // An Invite's body (a mediated invitation's message), not a Message.
                    row("uk.xa0.tulkki.ui.StartConversationActivity", "getBody", 7),
                    row("uk.xa0.tulkki.ui.UriHandlerActivity", "getBody", 1),
                    // A Call's body, not a Message. The read moved with the calls conversion: the
                    // deleted `CallsAdapter` parsed `RtpSessionStatus.of(call.getBody())` to pick the
                    // row's status icon and its missed colour, and the host that now builds the
                    // Compose row does the same one read.
                    row("uk.xa0.tulkki.ui.CallsActivity", "getBody", 1),
                    // MessageAdapter's two rows went with its rendering: the adapter is an API-only
                    // stub now (the Compose list draws the rows) and reads no body at all. Its
                    // `halves()` pin is not dropped - it is repointed at the path that replaced it,
                    // `theComposeHalvesAreBuiltFromTheBodyWithoutItsReplyFallback` below.
                    // An XmppUri's body inside the linkifier.
                    row("uk.xa0.tulkki.ui.util.MyLinkify", "getBody", 2),
                    // Compares the composer's text with the message being corrected - the owner's own
                    // outgoing row.
                    row("uk.xa0.tulkki.ui.util.SendButtonTool", "getBody", 1),
                    // The display decision for a share or a copy (two reads: the decision and the
                    // "needed?" question), the reply-fallback strip getBody(true) on the share and
                    // the copy, and the URL/geo branches that are not prose; the text itself comes
                    // from ShareText. One raw read remains, ShareUtil.kt:50: the geo branch's
                    // Intent.EXTRA_TEXT, a location and not prose. The other two raw reads lived in
                    // copyUrlToClipboard and went with the trim wave that deleted the method and
                    // its last caller, so the ratchet moved down, not up.
                    row("uk.xa0.tulkki.ui.util.ShareUtil", "getBody", 4),
                    row("uk.xa0.tulkki.ui.util.ShareUtil", "getRawBody", 1))

    /**
     * The packages the rule is about: the layers that draw something or hand something out. It is the
     * seam that matters rather than the directory, which is why {@code utils} is here: the audit names
     * {@code UIHelper.getMessagePreview} as one of the four seams that carry the rule, so a scan that
     * cannot read {@code uk.xa0.tulkki.app.utils} cannot see the file it is named for. The entities and the
     * persistence layer are not scanned - they are where the original is <em>supposed</em> to live.
     */
    private val SCANNED_PACKAGE =
            Pattern.compile("(^|\\.)(services|ui|worker|utils)(\\.|$)")

    private val ACCESSOR = Pattern.compile("\\.(getBody|getRawBody|getQuoteableBody)\\(")

    private val LOG_CALL = Pattern.compile("Log\\.[dviwe]\\(")

    @Test
    fun everyBodyReadIsOnTheReviewedList() {
        val sources = sources()
        val found = accessorCounts(sources)
        val allowed = allowedCounts()

        val keys = TreeMap<String, Int>()
        keys.putAll(found)
        keys.putAll(allowed)

        val problems = StringBuilder()
        var missing = 0
        for (entry in keys.entries) {
            val key = entry.key
            val expected = allowed[key] ?: 0
            val actual = found[key] ?: 0
            if (expected == actual) {
                continue
            }
            missing++
            problems.append("  ")
                    .append(key)
                    .append(": allow-listed ")
                    .append(expected)
                    .append(", found ")
                    .append(actual)
                    .append('\n')
            if (actual > 0) {
                for (line in lines(sources, typeOf(key), accessorOf(key))) {
                    problems.append("      ").append(line).append('\n')
                }
            }
        }

        val sites = found.values.sum()
        System.out.println(
                "leak scan: "
                        + found.size
                        + " reviewed file/accessor pairs, "
                        + sites
                        + " body reads, "
                        + allowed.size
                        + " allow-listed rows")
        Assert.assertTrue(
                "body reads changed without being reviewed (allow-list in LeakGuardScanTest):\n"
                        + problems,
                missing == 0)

        // A row whose file no longer reads a body is dead weight and a lie about the code; the two
        // lists are the same set, which the comparison above already enforces. What is left to check
        // is that every allow-listed type still exists at all: a file that was renamed or deleted
        // must fail here rather than silently covering nothing.
        val types = ArrayList<String>()
        for (source in sources) {
            types.add(source.type)
        }
        for (allowedRow in ALLOWED) {
            Assert.assertTrue(
                    "the allow-list names a type that no longer exists: " + allowedRow.type,
                    types.contains(allowedRow.type))
        }
    }

    /**
     * The live defect the audit found, on the path that now draws the bubble. A sent reply's second half
     * is drawn from the row's own body, and that body is the quote it carries plus the translated text -
     * so the readable half showed the peer's original underneath the translation. The Java
     * {@code MessageAdapter.halves()} closed it with {@code message.getBody(true)}; the Compose swap
     * replaced the adapter with {@code MessageProjection}, whose halves were built from
     * {@code snapshot.bodies.original}, the composed text - which is why the pin went with the method
     * rather than being repointed, and why this scan reported nothing: the projector calls no body
     * accessor at all, so {@code getBody(true)} had no {@code :ui} twin to find.
     *
     * <p>It is ported the only way the host can do it and no other. {@code MessageProjection} hands
     * {@code BubbleHalves.of} the seam's fallback-free body ({@code MessageFacts.strippedBody}), whose
     * live implementation is the {@code XmppActivity} read on the allow-list above; the same answer is
     * {@code ReplyQuote.of}'s referenced body, so a quote of a reply-to-a-reply is stripped too. The
     * strip cannot live in the projection itself: the declared span is in the entity's payload tree and
     * no {@code MessageSnapshot} column carries it, and {@code :data} is not this lane's to change.
     *
     * <p>A source test, and this is the coverage the fix can have: the read goes through
     * {@code android.util.Pair} and the live host needs the service, so what is checkable here is the
     * one fact that makes the argument load-bearing - the expression the halves are built from.
     */
    @Test
    fun theComposeHalvesAreBuiltFromTheBodyWithoutItsReplyFallback() {
        val text = source("MessageProjection").text
        val start = text.indexOf("val halves =")
        Assert.assertTrue("MessageProjection's halves are gone; move this pin with them", start >= 0)
        val end = text.indexOf("val displayed =", start)
        Assert.assertTrue("the halves expression has no end; move this pin with it", end > start)
        val halves = text.substring(start, end)
        Assert.assertTrue(
                "the halves must read the fallback-free body from the seam:\n" + halves,
                halves.contains("facts.strippedBody(message)"))
        Assert.assertFalse(
                "the halves read the composed snapshot body, so a sent reply's readable second half "
                        + "would show the quoted original:\n"
                        + halves,
                halves.contains("message.bodies.original"))

        val quote = text.indexOf("val resolved =")
        Assert.assertTrue("the quote's ReplyQuote.of is gone; move this pin with it", quote >= 0)
        val quoteEnd = text.indexOf("val id = MessageId(referenced.id)", quote)
        Assert.assertTrue("the ReplyQuote.of expression has no end; move this pin with it", quoteEnd > quote)
        val referenced = text.substring(quote, quoteEnd)
        Assert.assertTrue(
                "and the quoted row's body is stripped the same way:\n" + referenced,
                referenced.contains("facts.strippedBody(referenced)"))
        Assert.assertFalse(
                "the quote reads the referenced row's composed body:\n" + referenced,
                referenced.contains("referenced.bodies.original"))
    }

    /**
     * A reply whose target cannot be resolved is still a quote, and it is built the way the Java built
     * it: {@code ReplyQuote.unresolved(interpreter, replyFallback(message))}, never the raw fallback
     * drawn as text. The read that finds that fallback is the seam's second one, so
     * {@code MessageProjection} asks {@code MessageFacts.replyFallback} and no body accessor of its own -
     * which is what keeps {@code ReplyQuote} the decider (covered while the interpreter is on) rather
     * than a parallel concealment rule here.
     *
     * <p>A source test for the reason the halves pin is one: the declared span is payload-tree work and
     * the live host needs the service, so what is checkable off a device is the expression the unresolved
     * quote is built from and the read it is built from.
     */
    @Test
    fun theUnresolvedQuoteIsBuiltFromTheSeamsFallbackRead() {
        val text = source("MessageProjection").text
        val start = text.indexOf("val referenced = facts.quoted(message)")
        Assert.assertTrue("the quote's reference lookup is gone; move this pin with it", start >= 0)
        val end = text.indexOf("val resolved =", start)
        Assert.assertTrue("the unresolved branch has no end; move this pin with it", end > start)
        val unresolved = text.substring(start, end)
        Assert.assertTrue(
                "the reply's own fallback must come from the seam, not from a snapshot body:\n" + unresolved,
                unresolved.contains("facts.replyFallback(message)"))
        Assert.assertTrue(
                "and ReplyQuote.unresolved must stay the decider:\n" + unresolved,
                unresolved.contains("ReplyQuote.unresolved(interpreter,"))
        Assert.assertFalse(
                "the unresolved quote reads a body directly:\n" + unresolved,
                unresolved.contains("bodies.original"))
    }

    /**
     * The same defect as the bubble's second half, on the surface the audit believed was closed:
     * {@code getMessagePreview} read {@code getBody()} into the string its {@code /me} branch returns
     * verbatim, and the {@code QuoteSpan} sweep that takes a reply's fallback out runs only in the
     * final {@code else} - after that return. So an action that carried a reply fallback put the
     * peer's quoted original into {@code MessagingStyle}, the {@code InboxStyle} line and the
     * conversation-list preview.
     *
     * <p>A source test, and it is the only JVM coverage this fix can have. The read itself cannot be
     * exercised off a device: {@code Message.getBody(boolean)} goes through {@code android.util.Pair},
     * which the unit-test runtime stubs out - the reason {@code OutgoingTranslationHoldTest} takes the
     * caller's text as a parameter - and the preview on top of it needs an
     * {@code XmppConnectionService} for {@code context.getString}. What is checkable here is the two
     * facts that make the argument load-bearing, and they are checked structurally rather than as a
     * string match: the branch's read is the fallback-free one, and the sweep that could have cleaned
     * up afterwards runs after the branch's return.
     */
    @Test
    fun thePreviewActionBranchDropsTheReplyFallback() {
        val text = source("UIHelper").text
        // The pin moved to Kotlin with the port (07c382f324): both getMessagePreview overloads keep
        // the Java name, so the 3-arg one this pin is about is anchored on its textColor parameter
        // and walked back to its own fun, rather than on a reflowed parameter list.
        val signature = text.indexOf("@ColorInt textColor: Int")
        Assert.assertTrue(
                "the preview's textColor parameter is gone; move this pin with it", signature >= 0)
        val start = text.lastIndexOf("fun getMessagePreview(", signature)
        Assert.assertTrue("UIHelper.getMessagePreview is gone; move this pin with it", start >= 0)
        val end = text.indexOf("\n    }", start)
        Assert.assertTrue("the preview method has no end; move this pin with it", end > start)
        val method = text.substring(start, end)

        val read = method.indexOf("MessageUtils.filterLtrRtl(message.getBody(true))")
        val sweep = method.indexOf("android.text.style.QuoteSpan::class.java")
        Assert.assertTrue(
                "the /me branch must read the body with its reply fallback already removed:\n" + method,
                read >= 0)
        Assert.assertTrue(
                "the QuoteSpan sweep is the thing that takes a quoted original out, and this test "
                        + "assumes it is still in the preview:\n"
                        + method,
                sweep >= 0)
        Assert.assertTrue(
                "the sweep must stay *after* the /me return, or the branch no longer needs the "
                        + "fallback-free read and this pin has stopped meaning anything",
                read < sweep)
        Assert.assertFalse(
                "getMessagePreview filters the body with its reply fallback still in it; the /me "
                        + "branch returns that string verbatim, before the sweep runs:\n"
                        + method,
                method.contains("MessageUtils.filterLtrRtl(message.getBody())"))
    }

    /**
     * The search index must be built from the app-language side, not from the original. What made a
     * body read dangerous was the query, not the storage: with the raw body indexed, searching a
     * foreign word told the owner whether a covered original contained it, one answer at a time.
     * The column name lives in one constant, so this pins the constant and both of its uses.
     */
    @Test
    fun theMessageIndexIsBuiltFromTheAppLanguageSide() {
        val text = source("MessageIndexStore").text
        Assert.assertTrue(
                "the index column must be the stored translation",
                text.contains("MESSAGE_INDEX_COLUMN = Message.TRANSLATED_BODY"))
        Assert.assertFalse("an FTS trigger still writes the raw body", text.contains("NEW.body"))
        Assert.assertFalse(
                "the search still matches the raw body column", text.contains(" body MATCH"))
        Assert.assertTrue(
                "the query must match the column the triggers write",
                text.contains("MESSAGE_INDEX_COLUMN") && text.contains(" MATCH ?)"))
    }

    /**
     * The concealed string must not live in the view after the draw. It cannot be in the text or the
     * content description - that is what the class is for - but it used to be the view's tag, which
     * keeps the original alive for as long as the concealment lasts. The token that replaced it is an
     * identity, and it is released before the bitmap is built.
     */
    @Test
    fun theConcealmentNeverKeepsTheOriginalInTheView() {
        val text = source("ConcealedText").text
        Assert.assertFalse(
                "the view holds the concealed string as a tag",
                text.contains("setTag(R.id.tulkki_concealed_source, original)"))
        val released = count(text, "tulkki_concealed_source, null")
        Assert.assertTrue(
                "apply, clear and draw must each release the token (found "
                        + released
                        + " releases)",
                released >= 3)
    }

    /** A copy of a message is sensitive: API 33 previews it and keeps it in the clipboard history. */
    @Test
    fun theMessageCopyIsMarkedSensitive() {
        Assert.assertTrue(
                "the clipboard writer must mark the clip",
                source("XmppActivity").text.contains("ClipDescription.EXTRA_IS_SENSITIVE"))
        Assert.assertTrue(
                "the message copy must ask for the marking",
                Pattern.compile("copyTextToClipboard\\(text, [\\w.]*R\\.string\\.message, true\\)")
                        .matcher(source("ShareUtil").text)
                        .find())
    }

    /**
     * A log line is a channel out of the app, and it is the one channel with no cover and no tap.
     * Nothing in the tree may print a body; the line this rule was written for
     * ({@code HttpDownloadConnection}'s {@code knownFileSize...body=}) printed a whole one.
     */
    @Test
    fun noLogLineCarriesABody() {
        val offenders = ArrayList<String>()
        for (source in sources()) {
            for (line in source.text.split("\n")) {
                if (LOG_CALL.matcher(line).find() && ACCESSOR.matcher(line).find()) {
                    offenders.add(source.type + ": " + line.trim())
                }
            }
        }
        Assert.assertTrue("a log line prints a message body: " + offenders, offenders.isEmpty())
    }

    // -- the scanner -------------------------------------------------------------------------------

    /** One source file, already comment-free, with the type its declaration names. */
    private class Source(val type: String, val text: String)

    private fun sources(): List<Source> {
        val root = projectRoot()
        val sources = ArrayList<Source>()
        val seen = ArrayList<Path>()
        for (tree in arrayOf("src/main/java", "src/tulkki/java")) {
            for (base in sourceRoots(root, tree)) {
                val paths = Files.walk(base).use { walk ->
                    walk.filter { Files.isRegularFile(it) }.sorted().toList()
                }
                for (path in paths) {
                    if (seen.contains(path)) {
                        continue
                    }
                    seen.add(path)
                    val name = path.getFileName().toString()
                    if (!name.endsWith(".java") && !name.endsWith(".kt")) {
                        continue
                    }
                    val type = declaredType(String(Files.readAllBytes(path), StandardCharsets.UTF_8), name)
                    if (type == null) {
                        continue
                    }
                    sources.add(
                            Source(
                                    type,
                                    stripComments(String(Files.readAllBytes(path), StandardCharsets.UTF_8))))
                }
            }
        }
        Assert.assertFalse("no sources were read - the root is wrong", sources.isEmpty())
        return sources
    }

    /**
     * Every {@code src/main/java}-shaped tree of the repository, in either layout.
     *
     * <p>At the root in the monolith and one directory down in every module after the split - and
     * this scan's assertions are about the <em>whole</em> tree, so it returns all of them rather than
     * the first one it finds. {@code :libs} is two directories down ({@code libs/annotation}),
     * which is why the module walk goes two levels.
     */
    private fun sourceRoots(root: Path, tree: String): List<Path> {
        val roots = ArrayList<Path>()
        val atRoot = root.resolve(tree)
        if (Files.isDirectory(atRoot)) {
            roots.add(atRoot)
        }
        for (module in modules(root)) {
            val candidate = module.resolve(tree)
            if (Files.isDirectory(candidate)) {
                roots.add(candidate)
            }
        }
        return roots
    }

    /**
     * The directories that can hold a module: the root's own children and their children. Build
     * outputs and the hidden directories (Gradle's and the harness's caches, {@code .git}) are
     * skipped, and {@code src} is skipped because the root's own tree is added by the caller.
     */
    private fun modules(root: Path): List<Path> {
        val out = ArrayList<Path>()
        val first = Files.list(root).use { stream ->
            stream.filter { Files.isDirectory(it) }.sorted().toList()
        }
        for (directory in first) {
            if (skipped(directory)) {
                continue
            }
            out.add(directory)
            val second = Files.list(directory).use { stream ->
                stream.filter { Files.isDirectory(it) }.sorted().toList()
            }
            for (nested in second) {
                if (!skipped(nested)) {
                    out.add(nested)
                }
            }
        }
        return out
    }

    private fun skipped(directory: Path): Boolean {
        val name = directory.getFileName().toString()
        return name.startsWith(".") || name.equals("build") || name.equals("src")
    }

    /**
     * The repository root, found by its <em>marker</em> rather than by a source directory.
     *
     * <p>{@code settings.gradle.kts} is at the repository root in both layouts; {@code src/main/java} is
     * at the root only before the module split and in <em>every module</em> afterwards, so a walk
     * that stops at the first one stops a module in and reads a fraction of the tree - which is
     * exactly the whole-tree reading this scan exists to make. The suite runs in both layouts (the
     * boundary harness builds the split tree from this one), so the locator must be right in both.
     */
    private fun projectRoot(): Path {
        var directory: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        var level = 0
        while (level < 6) {
            val current = directory ?: break
            if (Files.isRegularFile(current.resolve("settings.gradle.kts"))
                    || Files.isRegularFile(current.resolve("settings.gradle"))) {
                return current
            }
            directory = current.getParent()
            level++
        }
        throw AssertionError(
                "could not find settings.gradle.kts above " + System.getProperty("user.dir"))
    }

    /** The declared package plus the file's simple name, or {@code null} when there is no package. */
    private fun declaredType(text: String, fileName: String): String? {
        val matcher = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)").matcher(text)
        if (!matcher.find()) {
            return null
        }
        val simple = fileName.substring(0, fileName.lastIndexOf('.'))
        return matcher.group(1) + "." + simple
    }

    private fun accessorCounts(sources: List<Source>): Map<String, Int> {
        val counts = TreeMap<String, Int>()
        for (source in sources) {
            if (!SCANNED_PACKAGE.matcher(packageOf(source.type)).find()) {
                continue
            }
            for (line in source.text.split("\n")) {
                val matcher = ACCESSOR.matcher(line)
                while (matcher.find()) {
                    val key = source.type + "." + matcher.group(1)
                    counts.put(key, (counts.get(key) ?: 0) + 1)
                }
            }
        }
        return counts
    }

    private fun allowedCounts(): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        for (allowed in ALLOWED) {
            val key = allowed.type + "." + allowed.accessor
            counts.put(key, (counts.get(key) ?: 0) + allowed.count)
        }
        return counts
    }

    private fun lines(
            sources: List<Source>, type: String, accessor: String): List<String> {
        val lines = ArrayList<String>()
        for (source in sources) {
            if (!source.type.equals(type)) {
                continue
            }
            var number = 0
            for (line in source.text.split("\n")) {
                number++
                if (ACCESSOR.matcher(line).find()
                        && line.contains("." + accessor + "(")) {
                    lines.add(number.toString() + ": " + line.trim())
                }
            }
        }
        return lines
    }

    private fun source(simpleName: String): Source {
        for (source in sources()) {
            if (source.type.endsWith("." + simpleName)) {
                return source
            }
        }
        throw AssertionError("no source named " + simpleName + " was read")
    }

    private fun count(text: String, needle: String): Int {
        var found = 0
        var index = text.indexOf(needle)
        while (index >= 0) {
            found++
            index = text.indexOf(needle, index + needle.length)
        }
        return found
    }

    private fun packageOf(type: String): String {
        return type.substring(0, type.lastIndexOf('.'))
    }

    private fun typeOf(key: String): String {
        return key.substring(0, key.lastIndexOf('.'))
    }

    private fun accessorOf(key: String): String {
        return key.substring(key.lastIndexOf('.') + 1)
    }

    /**
     * The source with its comments removed and everything else left alone, so a comment that merely
     * names an accessor is not a hit and a call inside a string literal still is. Java and Kotlin
     * share the two comment forms; Kotlin's raw strings are handled so that the {@code //} inside one
     * does not truncate a line.
     */
    private fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var index = 0
        val length = text.length
        while (index < length) {
            val current = text[index]
            if (current == '"') {
                if (text.startsWith("\"\"\"", index)) {
                    val close = text.indexOf("\"\"\"", index + 3)
                    val end = if (close < 0) length else close + 3
                    out.append(text.substring(index, end))
                    index = end
                    continue
                }
                var cursor = index + 1
                while (cursor < length) {
                    if (text[cursor] == '\\') {
                        cursor += 2
                        continue
                    }
                    if (text[cursor] == '"' || text[cursor] == '\n') {
                        cursor++
                        break
                    }
                    cursor++
                }
                out.append(text.substring(index, Math.min(cursor, length)))
                index = Math.min(cursor, length)
                continue
            }
            if (current == '\'') {
                var cursor = index + 1
                while (cursor < length) {
                    if (text[cursor] == '\\') {
                        cursor += 2
                        continue
                    }
                    if (text[cursor] == '\'' || text[cursor] == '\n') {
                        cursor++
                        break
                    }
                    cursor++
                }
                out.append(text.substring(index, Math.min(cursor, length)))
                index = Math.min(cursor, length)
                continue
            }
            if (text.startsWith("//", index)) {
                val close = text.indexOf('\n', index)
                index = if (close < 0) length else close
                continue
            }
            if (text.startsWith("/*", index)) {
                val close = text.indexOf("*/", index + 2)
                index = if (close < 0) length else close + 2
                continue
            }
            out.append(current)
            index++
        }
        return out.toString()
    }
}
