package uk.xa0.tulkki.annotation

/**
 * Marks an XML model that `:xmpp`'s `generateExtensionIndex` scans into the extension index
 * `ExtensionFactory` reads.
 *
 * **Why BINARY retention, not RUNTIME or SOURCE.** The index is built by scanning *compiled*
 * classes, so the annotation has to survive into the classfile. `AnnotationRetention.BINARY` is
 * Java's `CLASS` retention - present in the classfile, invisible to reflection at runtime - which
 * is exactly what ClassGraph reads. `RUNTIME` would also be visible to the scan but is more than
 * the scan needs; `SOURCE` would leave the classfiles bare, the scan would find no annotated
 * types, and `generateExtensionIndex`'s empty-index guard would fail the build rather than ship a
 * registry that resolves nothing.
 *
 * **The two parameters keep their names and their empty defaults.** The scan reads them by name
 * from the classfile and treats a missing or empty `namespace` as a build failure (a model that
 * declares no namespace could never be resolved by name), while a missing or empty `name` falls
 * back to the class's hyphenated simple name. A non-empty default here would quietly change which
 * elements parse, so neither default may move.
 *
 * **The FQN is load-bearing.** `xmpp/build.gradle` matches the exact string
 * `uk.xa0.tulkki.annotation.XmlElement` when it scans, so a package or class rename has to move
 * together with that constant. The mismatch is loud rather than silent - the scan would find no
 * annotated classes and the task throws before writing the index - but the string and this type
 * are one fact.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class XmlElement(val name: String = "", val namespace: String = "")
