package uk.xa0.tulkki.data

/**
 * Tulkki: 3.7 C5-R1 - the first-party view of the file backend.
 *
 * C5-R1 retypes `XmppConnectionService.getFileBackend()` to the island's `uk.xa0.tulkki.xmpp.refs.FileBackendRef`,
 * because the island may not name the model. `:ui`, `:app` and `:data` bind the same call to the concrete
 * [FileBackend] - one declared return type cannot be both, and `getFile` in particular cannot answer a ref to
 * the island and a `DownloadableFile` to first-party code at once - so first-party code reaches the model
 * through this holder instead. It is **not** a second view of the ref: it hands back the one object the
 * island's accessor answers.
 *
 * **Why this holder is installed rather than self-populating.** The `AccountRegistry` precedent is a
 * `:data`-owned static precisely because it needs nothing from the island; a [FileBackend] needs the service at
 * construction, which only `:app` can supply, so [install] is called by `DataStaticsHost.newFileBackend` - the
 * very field initializer whose object every caller already holds. There is no "empty holder" window in
 * practice: a first-party caller can only reach [get] through an `XmppConnectionService` reference, and that
 * reference exists only after its field initializers ran.
 *
 * Nothing here is serialised. It is one in-memory reference, never a copy: copying is how a second source of
 * truth appears.
 */
object FileBackends {

    @Volatile
    private var instance: FileBackend? = null

    /** Called once per service create, from `DataStaticsHost.newFileBackend`. */
    @JvmStatic
    fun install(fileBackend: FileBackend) {
        instance = fileBackend
    }

    /**
     * The concrete view `:ui`/`:app`/`:data` hold. It is the same object the island's `getFileBackend()`
     * answers: [install] and that accessor are fed by the same field initializer, so the two identity claims
     * are structural rather than arguments about some other code path.
     */
    @JvmStatic
    fun get(): FileBackend {
        val fileBackend = instance
        if (fileBackend == null) {
            throw IllegalStateException("FileBackends.get() before the service installed it")
        }
        return fileBackend
    }
}
