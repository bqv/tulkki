package uk.xa0.tulkki.data.utils

import org.junit.Assert
import org.junit.Test

class MimeUtilsTest {

    @Test
    fun wasmMimeType() {
        Assert.assertEquals(
            "application/wasm", MimeUtils.guessMimeTypeFromExtension("wasm"))
    }

    @Test
    fun wasmExtension() {
        Assert.assertEquals("wasm", MimeUtils.guessExtensionFromMimeType("application/wasm"))
    }
}
