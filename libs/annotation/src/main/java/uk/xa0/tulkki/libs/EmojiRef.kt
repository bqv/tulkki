package uk.xa0.tulkki.libs

/**
 * Tulkki: the island's marker for one emoji - `uk.xa0.tulkki.data.model.Emoji`.
 *
 * <p>3.7 pair 9, part 15. The island's one use of the model type is the callback parameter of
 * `uk.xa0.tulkki.xmpp.services.EmojiSearchPort.makeAdapter`, and the island never reads an emoji at
 * all: the `:ui` autocomplete presenter's lambda consumes one and the `:app` implementation of the
 * port forwards it straight to `EmojiSearch`. So this is a **marker**.
 *
 * <p>That is the whole design decision the coordinator asked for. The model's API is public fields
 * (`unicode`, `tags`, `shortcodes`) rather than getters, so a ref with a member surface would have
 * meant inventing getters on the model to satisfy callers that do not exist; the alternative - an
 * island port that takes the model - is what this replaces, and it cannot be written at all because
 * the island may not name `:data`. A reader that actually needs a field arrives with its own member,
 * by ruling 3, the same way this file arrived empty.
 *
 * <p>**Why `:libs` (2026-10-08, lane `B`).** It was
 * `uk.xa0.tulkki.xmpp.refs.EmojiRef` - the island's own name for
 * `uk.xa0.tulkki.data.model.Emoji`, kept only because `:xmpp` may not name `:data`. `:libs` is the
 * one module in the order that every side may reach, so the interface moves here and the ref file is
 * its `git rm`: the fourth `:libs` retirement after `Avatarable`, `Transferable` and
 * `FilePathInfoRef`, and the second that moves **only an interface**. The move is **package-only** -
 * `javap` prints the same (empty) surface on both sides - so every namer of the type changes only
 * its import line.
 *
 * <p>**The Android type is not in this contract.** The model's one `android.*` use,
 * `android.text.SpannableStringBuilder`, is the return type of `Emoji.toInsert()` - an
 * implementation method of the `:data` class that the island neither names nor calls. The island's
 * only use of the type it stands for is a callback parameter, and the model's fields and its
 * `toInsert()` stay in `:data` (an Android module) exactly where they are: nothing is widened and
 * no rendering path moves.
 *
 * <p>**Respell from Java (2026-10-08, lane `D`) - the module's last `.java`.** The type is an empty
 * marker, so the JVM surface is unchanged: `javap -p` prints the same
 * `public interface uk.xa0.tulkki.libs.EmojiRef {}` before and after, and the identifier stays
 * `EmojiRef`.
 */
interface EmojiRef
