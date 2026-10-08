package uk.xa0.tulkki.data.utils

import java.io.File

class FileWriterException : Exception {

    constructor(file: File) : super(String.format("Could not write to %s", file.absolutePath))

    internal constructor() : super()
}
