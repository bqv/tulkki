package uk.xa0.tulkki.libs

/**
 * Tulkki: the shared view of `:data`'s file-path row - the uuid a message is compared against, the
 * relative path that is statted, and the deleted flag the file sweep writes.
 *
 * Part 17. `Conversation.markAsChanged(List<FilePathInfo>)` is called by `XmppConnectionService`
 * once a file-change sweep has run, and an island type cannot name that model without importing
 * `:data` - a second `island-imports-ours` violation bought to remove the first. So the parameter
 * type lives on this side of the wall instead, exactly as the four `Conversation.OnMessageFound`
 * finders did: the model's class implements this interface and the model method's signature adopts
 * the wildcard, so the call site and the sweep are unchanged.
 *
 * port-13 retired `FilePathRef`. The parent's four names were `convertToAttachments`', and that
 * sweep's only caller is `:app`'s `DataStaticsHost.loadAttachments`, which now asks the model
 * itself. What the file sweep and `markAsChanged` actually read is here: the uuid a message is
 * compared against, the path that is statted, and the deleted flag the sweep writes.
 *
 * Two members of its own, and both are reads or writes of the model's own fields: the `deleted`
 * flag beside the path, and the sweep's writer for it.
 *
 * **Why `:libs` (2026-10-08, lane `G`).** It was
 * `uk.xa0.tulkki.xmpp.refs.FilePathInfoRef` - the island's own name for
 * `uk.xa0.tulkki.data.filepaths.FilePathInfo`, kept only because `:xmpp` may not name `:data`.
 * `:libs` is the one module in the order that every side may reach, so the interface moves here and
 * the ref file is its `git rm`: the third `:libs` retirement after `Avatarable` and `Transferable`.
 * The move is **package-only** - the same four members, spelled the same way - so every namer
 * changes only its import line.
 *
 * **Only the interface moves; the pair stays in `:data`.** `FilePath` and `FilePathInfo` are built
 * by `:data`'s own `FilePathStore` through `internal` constructors, and Kotlin's `internal` is
 * module-scoped, so moving the classes would have forced a widening the by-value rule forbids.
 * `FilePathInfo` therefore stays in `uk.xa0.tulkki.data.filepaths` and implements this interface
 * exactly as it implemented the ref; no constructor, visibility or nullability moves.
 *
 * **Respell from Java (2026-10-08, lane `F`) - the module's last `.java`.** The four members keep
 * the identical JVM surface (`getUuid()Ljava/lang/String;`, `getPath()Ljava/lang/String;`,
 * `deleted()Z`, `setDeleted(Z)Z`), and no caller reads the synthetic-property spelling a Java getter
 * would allow: every site already calls the functions - `FilePathInfo.kt`'s `override fun`s,
 * `FilePathStore.markFilesAsChanged`'s `info.deleted()`/`info.getUuid()`,
 * `DeletedFileCheck`'s `filePath.getPath()`/`setDeleted(...)`, and `Conversation.markAsChanged`'s
 * `file.getUuid()`/`file.deleted()`. Lane `G` had kept the file Java for that spelling; measured,
 * nothing in the tree uses it. `FilePath.getPath()` is inherited and final, and a Kotlin interface
 * accepts an inherited method as its implementation (measured with kotlinc 2.3.21), so
 * `FilePathInfo` needs no change and the class keeps its four members exactly.
 */
interface FilePathInfoRef {

    /** The inherited `uuid` field, stringified as `Conversation.markAsChanged` compares it. */
    fun getUuid(): String

    /** The inherited `path` field: the relative path of the file on disk. */
    fun getPath(): String

    fun deleted(): Boolean

    /** C5-E5: the file sweep writes the flag and keeps the element when it flipped. */
    fun setDeleted(deleted: Boolean): Boolean
}
