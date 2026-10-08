package uk.xa0.tulkki.libs

import java.util.Arrays

/**
 * The download/upload state machine a message carries.
 *
 * **Why it lives in `:libs` (2026-10-08).** It was
 * `uk.xa0.tulkki.xmpp.refs.TransferableRef`, and `:data` carried an empty `Transferable`
 * sub-interface whose whole job was to give `:ui` and `:app` a name for the same objects that did
 * not reach into an island. `:libs` is the one module in the order that every side may reach, so
 * moving the type here retires **both** names instead of keeping one: the island, `:data`, `:ui` and
 * `:app` all name `uk.xa0.tulkki.libs.Transferable` now, and nothing stands between them. No rule
 * counts `:libs`, so `:ui`'s sites stop writing the fully-qualified spelling the old arrangement
 * forced on them.
 *
 * **Converted from Java (2026-10-08, lane E), because the reason it stayed Java was measured and it
 * does not hold.** The old note read "synthesis reads a Java type, it does not override one". What
 * that named is Kotlin's Java interop: for a Kotlin *reader*, a Java getter `getStatus()` also
 * yields the synthetic property `status`. It is not a processor, and it generates nothing for the
 * implementors - their five members are `override fun`s and stay `override fun`s against a Kotlin
 * interface, which is what Kotlin requires of a Java interface today anyway. The one thing a Kotlin
 * declaration removes is the *property spelling* on a Kotlin reader (`transferable.fileSize`), and
 * no call site in the tree uses it: `git grep` finds only `.getFileSize()`, `.getStatus()`,
 * `.getProgress()`, `.start()` and `.cancel()`. The placeholder's own note - `override val status`
 * "overrides nothing" - is about Kotlin's inability to turn a function into an overriding property,
 * and it holds identically against a Java and a Kotlin supertype; the functions were always the
 * right spelling. All five implementors (`HttpDownloadConnection`, `HttpUploadConnection`,
 * `JingleFileTransferConnection`, `BobTransfer`, `TransferablePlaceholder`) already declare
 * `override`, and every one of them answers `Long?` from `getFileSize()`, which is why this
 * interface declares `Long?` too - every reader (`Message.getFileParams`, `XmppActivity`'s
 * `Offered`/`Downloading`) already takes that `Long?`.
 *
 * **The ten constants, and the one measured delta.** Kotlin has no interface constants: an
 * interface property may not have an initializer, and `const val` is not allowed in an interface
 * body (measured with kotlinc 2.3.21). All ten members therefore live in the companion object as
 * `@JvmField val`s - the one Kotlin spelling that puts a `public static final` field of the same
 * name, type and descriptor back on the interface itself, so `Transferable.STATUS_OFFER` and
 * `Transferable.VALID_CRYPTO_EXTENSIONS` resolve from Java exactly as they did (`javac` verified
 * against the Kotlin). A Kotlin `@JvmField` interface-companion property is only allowed when
 * **every** companion property is one, so a later `const val` added here is a compile error, not a
 * silent change. The delta: the eight `int`s are no longer Java compile-time constants - the Java
 * emitted a `ConstantValue` attribute, the Kotlin emits a plain `static final int` set in the
 * interface's `<clinit>`, and Kotlin call sites read it with `getstatic` instead of an inlined
 * literal - so a Java `switch` case or an annotation argument could no longer name them. No `.java`
 * file in the tree reads a constant of this interface (every Java mention is a type reference), so
 * nothing that compiles today stops compiling; the Kotlin call sites read the same values.
 *
 * `Arrays.asList`, not `List.of`: the call sites are `contains(extension)` on a filename-derived
 * value, `List.of` rejects a `null` there where `Arrays.asList` answers `false`, and this move must
 * not alter behaviour.
 *
 * The constants live here and nowhere else - `:data`'s `MimeUtils` reads
 * `VALID_CRYPTO_EXTENSIONS` through this type, and `AbstractConnectionManager` qualifies its own
 * read - so the two lists cannot drift, which is the ruling that moved them off the model
 * interface in the first place.
 *
 * `Message.transferable` is declared this type, and the island's `MessageRef` declares its getter
 * and setter as this type too, so there is no narrowing cast anywhere on the path and nothing left
 * that can throw the `ClassCastException` the old ref-and-model pair could.
 */
interface Transferable {

    fun start(): Boolean

    fun getStatus(): Int

    fun getFileSize(): Long?

    fun getProgress(): Int

    fun cancel()

    companion object {

        @JvmField
        val VALID_IMAGE_EXTENSIONS: List<String> = Arrays.asList("webp", "jpeg", "jpg", "png", "jpe")

        @JvmField
        val VALID_CRYPTO_EXTENSIONS: List<String> = Arrays.asList("pgp", "gpg", "otr")

        // The status ints. Compile-time values with no identity - `HttpDownloadConnection` seeds
        // its own field with `STATUS_UNKNOWN` and `JingleFileTransferConnection` compares and
        // returns them - so a single declaration is the whole contract and no enum has to exist.
        // `@JvmField` rather than `const val`: the two `List` constants cannot be `const`, and an
        // interface companion may not mix the two spellings.

        @JvmField
        val STATUS_UNKNOWN = 0x200

        @JvmField
        val STATUS_CHECKING = 0x201

        @JvmField
        val STATUS_FAILED = 0x202

        @JvmField
        val STATUS_OFFER = 0x203

        @JvmField
        val STATUS_DOWNLOADING = 0x204

        @JvmField
        val STATUS_OFFER_CHECK_FILESIZE = 0x206

        @JvmField
        val STATUS_UPLOADING = 0x207

        @JvmField
        val STATUS_CANCELLED = 0x208
    }
}
