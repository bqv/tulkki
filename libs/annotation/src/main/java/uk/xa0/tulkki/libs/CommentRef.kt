package uk.xa0.tulkki.libs

/**
 * Tulkki: the XMPP island's view of a `:data` model type, declared in the island and implemented by
 * the model class (`docs/WORKSTREAMS.md` round 151). Grown by ruling 3: a member arrives with the
 * call site that reads it.
 *
 * It is a marker so far because the island only ever *builds* one of these - with a wire `Element` -
 * and hands it back; an interface cannot be constructed, so construction is a `DataStatics` member
 * and no member is needed here yet.
 *
 * **Why `:libs` (2026-10-08, lane `G`).** It was `uk.xa0.tulkki.xmpp.refs.CommentRef` - the
 * island's name for `:data`'s `Comment` while `:xmpp` could not name `:data`. `:libs` is the one
 * module in the order that every side may reach, so the interface moves here and the ref file is
 * its `git rm`; the move is **package-only** and every namer changes only its import line. **Only
 * the interface moves**: the `:data` class and everything behind it stay put, so nothing is widened.
 *
 * This one is an **empty marker** - the island only builds the model and never reads a member - so
 * it is Android-free by construction, like `EmojiRef`.
 *
 * **Respell from Java (2026-10-08, lane `G`).** The type is an empty marker, so the JVM surface is
 * unchanged: `javap -p` prints `public interface uk.xa0.tulkki.libs.CommentRef {}` before and after,
 * and the identifier stays `CommentRef`. Nothing to sweep - an interface with no member declares no
 * nullability and no getter a caller could spell as a property.
 */
interface CommentRef
