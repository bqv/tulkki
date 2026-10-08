package uk.xa0.tulkki.libs

/**
 * Tulkki: the XMPP island's view of a `:data` model type, declared in the island and implemented by
 * the model class (`docs/WORKSTREAMS.md` round 151). Grown by ruling 3: a member arrives with the
 * call site that reads it.
 *
 * It arrived as a marker in parts 1-7 because the island only ever *built* one of these - with a
 * wire `Element` - and handed it back; construction stays a `DataStatics` member (an interface
 * cannot be constructed), and part 15 added the first members when the island's own story cache
 * became ref-shaped.
 *
 * **Why `:libs` (2026-10-08, lane `G`).** It was `uk.xa0.tulkki.xmpp.refs.StoryRef`. Its whole
 * member surface is JDK plus `uk.xa0.tulkki.libs.Jid`, once `Jid` left the island for the same
 * module, so the interface is `:libs`-expressible - and it has to be, because `:libs` may name
 * nothing (`allow = []`). The move is **package-only** and every namer changes only its import
 * line. **Only the interface moves** - `:data`'s `Story` and `StoryStore`, `StoryCache` and the two
 * `:ui` story screens stay byte-for-byte.
 *
 * **Respell from Java (2026-10-08, lane `G`).** Six members. Four are the model's nullable fields -
 * `Story.kt:71`-`:75` declares `String? getUrl()`, `String? getType()`, `String? getTitle()` and,
 * through its inherited `AbstractEntity.getUuid()`, `String? getUuid()` - while `getPublished()`
 * is a `long` and `getContact()` is the model's non-null `Jid`. The Kotlin spellings are exactly
 * those, so nothing is narrowed. The JVM surface is identical: `String getUuid()`,
 * `long getPublished()`, `Jid getContact()`, `String getUrl()`, `String getTitle()`,
 * `String getType()`.
 *
 * **The sweep is real, and it is five sites.** The Java declarations were platform types, so:
 * `StoryCache.kt:91`, `:198`, `:223` called `equals` on `getUuid()`'s answer - the Java
 * dereferenced it, so the NPE is spelled out; `StoryCache.kt:259` passed it to `retractStory`'s
 * non-null `storyId`, where the Kotlin parameter already checked it; and `StoryAdapter.kt:150`-`:153`
 * put `getUrl()`/`getTitle()`/`getUuid()`/`getType()` into `ArrayList<String>` extras, where a
 * Java `null` element has no Kotlin spelling because `putStringArrayListExtra` is invariant - the
 * four now normalise an absent field to `""` rather than crash the viewer.
 */
interface StoryRef {

    // -- part 15: the six reads the island's story cache and `:ui` make on a story ---------------
    //
    // `XmppConnectionService` now holds and returns `List<StoryRef>`, and `getStories()` is read by
    // five `:ui` screens and two adapters. Every member below is read at one of those sites; the
    // model declares all six with exactly these return types (five of them its own, `getUuid` from
    // `AbstractEntity`), so they are overrides and nothing is adapted.

    fun getUuid(): String?

    fun getPublished(): Long

    /** The bare JID whose feed the story came from; `StoriesActivity` keys its collection on it. */
    fun getContact(): Jid

    fun getUrl(): String?

    fun getTitle(): String?

    fun getType(): String?
}
