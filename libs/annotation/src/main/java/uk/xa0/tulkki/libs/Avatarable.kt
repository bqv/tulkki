package uk.xa0.tulkki.libs

import androidx.annotation.ColorInt

/**
 * The two facts every avatar in this app is built from: a background colour picked from a name, and
 * the name.
 *
 * **Why it lives in `:libs`** (2026-10-08). It was `uk.xa0.tulkki.data.model.Avatarable`, and the
 * XMPP island could not name it - `:xmpp` may not name `:data` - so
 * `uk.xa0.tulkki.xmpp.refs.AvatarableRef` stood between them: the island's own empty name for the
 * same set of objects. `:libs` is the one module in the order that every side may reach
 * ([AudioDevice] and [TextAvatar] came here by 3.7 pair 3 for exactly this reason), so moving the
 * type here **removes** the ref instead of replacing it, and this commit is its `git rm`. It is not
 * a port and not a second name: it is the type, in the one place all four namers already depend on.
 *
 * [TextAvatar] is the marker `StoryAdapter` asks of a placeholder drawable; this is the marker with
 * a member surface, and they answer different questions - "is this a picture" against "what is
 * drawn".
 *
 * **Nothing about the surface changes.** The two members keep Kotlin's spelling
 * ([getAvatarBackgroundColor], [getAvatarName]), so every implementor's `override fun` and every
 * caller's call site is untouched and only the import line moves. Java was the alternative - the
 * module's other two types are Java, and moving the file to Java would have kept the build file
 * alone - and it was refused because it would have changed what the overrides *are* (Java getters,
 * with the synthetic-property spelling riding along) for a type whose whole contract is those two
 * readings. So `libs/annotation` is no longer a bare `java-library`: it applies
 * `org.jetbrains.kotlin.jvm` and carries `compileOnly androidx.annotation:annotation` for
 * [ColorInt], which is CLASS-retention and needed by nobody downstream.
 *
 * The implementors are `:data`'s models, which sit above `:libs`, so this is the declared
 * `:data -> :libs` direction and no interface is asked of any of them. The XMPP island's
 * `AvatarPort`, `:ui`'s three files and `:app`'s port implementation name this type directly.
 */
interface Avatarable {

    /** The colour a generated avatar for this thing is drawn over. */
    @ColorInt
    fun getAvatarBackgroundColor(): Int

    /** The name a generated avatar for this thing shows, or draws its letters from. */
    fun getAvatarName(): String
}
