package uk.xa0.tulkki.libs

/**
 * Marks the drawable `uk.xa0.tulkki.app.services.AvatarService` draws for a contact it has no
 * picture for.
 *
 * 3.7 pair 3: the one `:ui` caller that asks the question - `uk.xa0.tulkki.ui.adapter.StoryAdapter`,
 * which scores a contact with a real picture above one with a letter tile - used to test
 * `instanceof AvatarService.TextDrawable`, naming the composition root to ask about a drawable it
 * had already been handed. The class itself cannot move down: `:xmpp`'s `XmppConnectionService`
 * names it too, and an island naming `:ui` is the direction pair 11 just closed. A marker in
 * `:libs` is the home both sides may name, and it says exactly what the callers mean: this avatar
 * is a placeholder, not a picture.
 *
 * **Converted from Java (2026-10-08, lane E).** An empty Kotlin `interface` is the Java one with
 * the same public surface - `javap` shows `public interface uk.xa0.tulkki.libs.TextAvatar {}`, the
 * same FQN - and it generates **no** `DefaultImpls`, because only an interface with default
 * members gets one. Every namer is Kotlin (`AvatarService`, `StoryAdapter`, `ServiceConstruction`)
 * or a comment in `XmppConnectionService.java`; nothing Java-tests the marker beyond the code that
 * did not change.
 */
interface TextAvatar
