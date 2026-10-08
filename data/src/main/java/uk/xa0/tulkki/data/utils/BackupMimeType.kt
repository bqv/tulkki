package uk.xa0.tulkki.data.utils

/**
 * The MIME type of Tulkki's own backup archive, moved down out of `uk.xa0.tulkki.app.worker.ExportBackupWorker` by 3.7 pair 2.
 *
 * `BackupFile` (a reader) and `MimeUtils` (which maps backups to the `ceb` extension) both named the worker just for
 * this string, and neither may name `:app`. The worker keeps its own constant as a forward, because `:ui`'s
 * `MediaAdapter` and `UIHelper` read it there and repointing them would have added a forbidden `:ui`->`:app` import
 * rather than removed one.
 */
object BackupMimeType {

    const val MIME_TYPE = "application/vnd.conversations.backup"
}
