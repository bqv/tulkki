package uk.xa0.tulkki.data.messages

/**
 * Tulkki (port-45): the search SQL, its bound arguments, and the FTS match string it was built from,
 * un-nested out of `DatabaseBackend` as the precondition of the `messages/` capability move.
 *
 * <p>It was `DatabaseBackend.SearchQuery`, a public nested class of the backend, and it could not
 * move while the FTS query that builds it left the outer class: a type written `Outer.Inner` cannot
 * be handed back by a method that now lives elsewhere. It is a top-level type in the capability
 * package that owns the statement, exactly as `filepaths/`'s `FilePath`/`FilePathInfo` pair was
 * un-nested for the file sweep. The two outside *code* spellings of the nested name were the search
 * tests' two locals, retyped in the same commit. Spellings inside javadoc did not need to change.
 *
 * <p>The fields stay exactly as they were and are still read directly by the search tests and by
 * [SnapshotRepositories], which hands [sql] and [args] to Room's `SimpleSQLiteQuery`. [matchString]
 * is the parsed term the query was built from.
 *
 * <p>**Ported from Java here, in the shape of `filepaths/`'s un-nested pair:** the three public
 * fields stay fields, never properties - Java declared them `public`,
 * and both `MessagesDaoTest` and `SearchInvariantTest` read `query.sql` and `query.args` by name. A
 * plain `val` would move each name to a generated getter and change the JVM surface.
 * **Interop debt: three `@JvmField`s.** The constructor keeps Java's package-private shape as
 * `internal`; Kotlin has no package-private, and [MessageIndexStore], in this package and this
 * module, is its only caller.
 */
class SearchQuery internal constructor(
    @JvmField val sql: String,
    @JvmField val args: Array<String>,
    @JvmField val matchString: String,
)
