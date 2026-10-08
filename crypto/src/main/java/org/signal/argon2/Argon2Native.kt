package org.signal.argon2

import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2KtResult
import com.lambdapioneer.argon2kt.Argon2Mode
import com.lambdapioneer.argon2kt.Argon2Version
import java.util.Arrays

/**
 * The Argon2 primitive the app actually runs, now over `argon2kt` rather than behind the NDK.
 *
 * **Why this is no longer `native`.** port-16 demotes the in-tree argon2 build: the JNI wrapper
 * (`app/src/main/jni/org_signal_argon2_Argon2Native.c`), the C tree it wrapped and the `argon2`
 * module in `Android.mk` are gone, and this class is the same seam with a Kotlin body over
 * `com.lambdapioneer.argon2kt:argon2kt:1.6.0`. The class, its package and every method signature
 * are unchanged on purpose - [Argon2] above it and `Argon2KeyDerivation` in `:data` need no edit,
 * and the SQLCipher password path keeps the contract it had.
 *
 * **Byte-identity, and where its proof lives.** argon2kt vendors the same phc-winner-argon2
 * reference C the app ships today, and its source was compiled here and compared against the
 * captured vectors at the app's parameters: all seven tags *and* all seven encoded strings
 * identical, no encoding change (`tools/argon2-vectors --against`; the capture is
 * `crypto/src/test/resources/argon2/known-answer.txt`). The AAR's own binaries are Android-only
 * (four bionic ABIs), so they cannot execute in a JVM test: the suite holds the *configuration*
 * pin ([Argon2KnownAnswerTest]), and the last gate is one on-device known-answer run that confirms
 * the published binaries behave as their source does. Nothing in this file is proven by the JVM
 * suite executing Argon2.
 *
 * **`p = 4` is the parameter that must be explicit.** argon2kt's own default is 2; a call that let
 * the default through would change the derived key - and the `$argon2id$v=19$m=65536,t=3,p=4$…`
 * string - with no error anywhere. The app's value reaches [hash] as its `parallelism` argument and
 * is passed on unchanged; do not replace it with an argon2kt overload that omits it.
 *
 * **The C's contract is preserved, not paraphrased.** [hash] validates in `core.c`'s own order and
 * returns the same negative `ARGON2_*` codes for everything Java can see - so a 7-byte salt is
 * still `ARGON2_SALT_TOO_SHORT`, and the caller's output buffer is still untouched on a refusal,
 * which is the property the capture proves by seeding the buffer before N1. The tags and the
 * encoded string themselves come from argon2kt, which is the point: the encoder is the vendored
 * C's, not a second implementation of it.
 *
 * The Java original was `final class Argon2Native` with only static members; Kotlin's nearest
 * package-private spelling is an `internal` object, so the JVM surface widens from package-private
 * to public exactly as `internal` always does in bytecode while Kotlin callers outside `:crypto`
 * still cannot see it. The `@JvmStatic` annotations keep the Java test's `Argon2Native.hash(...)`
 * call sites spelling-identical.
 */
internal object Argon2Native {

    const val OK = 0

    // The C's codes (argon2.h, "Error codes"), because callers and tests see them.
    const val OUTPUT_PTR_NULL = -1
    const val OUTPUT_TOO_SHORT = -2
    const val PWD_PTR_MISMATCH = -18
    const val SALT_PTR_MISMATCH = -19
    const val SALT_TOO_SHORT = -6
    const val SALT_TOO_LONG = -7
    const val SECRET_TOO_LONG = -11
    const val TIME_TOO_SMALL = -12
    const val TIME_TOO_LARGE = -13
    const val MEMORY_TOO_LITTLE = -14
    const val MEMORY_TOO_MUCH = -15
    const val LANES_TOO_FEW = -16
    const val LANES_TOO_MANY = -17
    const val INCORRECT_PARAMETER = -25
    const val INCORRECT_TYPE = -26
    const val MISSING_ARGS = -30
    const val DECODING_FAIL = -32
    const val VERIFY_MISMATCH = -35

    /** argon2.h's limits, the ones Java can reach. */
    private const val MIN_OUTLEN = 4
    private const val MIN_MEMORY = 8
    private const val MAX_MEMORY = 0x7FFFFFFF
    private const val MIN_TIME = 1
    private const val MAX_TIME = 0x7FFFFFFF
    private const val MIN_LANES = 1
    private const val MAX_LANES = 0xFFFFFF
    private const val MIN_SALT_LENGTH = 8

    /**
     * One raw hash, with the C's validation and error codes.
     *
     * @param tCost iterations
     * @param mCost memory in KiB
     * @param parallelism lanes - **passed through exactly, never defaulted**
     * @param pwd the password; the public API rejects a null before this is reached
     * @param salt the salt; the public API rejects a null before this is reached
     * @param hash the output buffer, `hash.size` bytes; left untouched on any failure
     * @param encoded receives the PHC string, or may be `null` for a raw-only hash
     * @param argon2Type the C's type number (0 d, 1 i, 2 id)
     * @param version the C's version number (0x10 or 0x13)
     * @return [OK], or the C's negative code
     */
    @JvmStatic
    fun hash(
        tCost: Int,
        mCost: Int,
        parallelism: Int,
        pwd: ByteArray?,
        salt: ByteArray?,
        hash: ByteArray?,
        encoded: StringBuffer?,
        argon2Type: Int,
        version: Int
    ): Int {
        if (hash == null) {
            return OUTPUT_PTR_NULL
        }
        val rc = validate(hash.size, if (pwd == null) -1 else pwd.size, salt, tCost, mCost, parallelism)
        if (rc != OK) {
            return rc
        }
        val mode = mode(argon2Type)
        val hashVersion = hashVersion(version)
        if (mode == null || hashVersion == null) {
            return INCORRECT_TYPE
        }
        // validate() has already refused a null password or salt; these are what let the compiler
        // see the non-null types argon2kt declares, and they answer the same code validate would.
        val password = pwd ?: return PWD_PTR_MISMATCH
        val saltBytes = salt ?: return SALT_PTR_MISMATCH

        // Computed into argon2kt's own array and copied only on success: `hash` is the caller's
        // buffer, and a refusal must leave it exactly as it was (the capture's N1 case seeds it to
        // prove that).
        val result: Argon2KtResult = Argon2Kt().hash(
            mode,
            password,
            saltBytes,
            tCost,
            mCost,
            // Explicit, and of the essence: argon2kt's default is 2 and would change the key silently.
            parallelism,
            hash.size,
            hashVersion
        )
        val raw = result.rawHashAsByteArray()
        if (raw.size != hash.size) {
            return OUTPUT_TOO_SHORT
        }
        if (encoded != null) {
            val text = result.encodedOutputAsString()
            // argon2kt declares a non-null String, so this is dead by contract; it is kept because
            // the Java seam answered DECODING_FAIL for a null and a caller could still see one if
            // the library ever broke that contract.
            @Suppress("SENSELESS_COMPARISON")
            if (text == null) {
                return DECODING_FAIL
            }
            encoded.setLength(0)
            encoded.append(text)
        }
        System.arraycopy(raw, 0, hash, 0, hash.size)
        Arrays.fill(raw, 0.toByte())
        return OK
    }

    /**
     * Does this encoded string reproduce for this password? Nothing in the app calls it today; it is
     * the C's `argon2_verify`, kept because this class is the API the JNI published.
     */
    @JvmStatic
    fun verify(encoded: String?, pwd: ByteArray?, argon2Type: Int): Int {
        if (encoded == null || pwd == null) {
            return MISSING_ARGS
        }
        val mode = mode(argon2Type) ?: return INCORRECT_TYPE
        return try {
            if (Argon2Kt().verify(mode, encoded, pwd)) OK else VERIFY_MISMATCH
        } catch (e: RuntimeException) {
            // argon2kt refuses an unparseable encoding the way the C decodes one: a mismatch to report.
            DECODING_FAIL
        }
    }

    /** The C's `argon2_error_message`. */
    @JvmStatic
    fun resultToString(argonResult: Int): String = when (argonResult) {
        OK -> "OK"
        OUTPUT_PTR_NULL -> "Output pointer is NULL"
        OUTPUT_TOO_SHORT -> "Output is too short"
        -3 -> "Output is too long"
        -4 -> "Password is too short"
        -5 -> "Password is too long"
        SALT_TOO_SHORT -> "Salt is too short"
        SALT_TOO_LONG -> "Salt is too long"
        -8 -> "Associated data is too short"
        -9 -> "Associated data is too long"
        -10 -> "Secret is too short"
        SECRET_TOO_LONG -> "Secret is too long"
        TIME_TOO_SMALL -> "Time cost is too small"
        TIME_TOO_LARGE -> "Time cost is too large"
        MEMORY_TOO_LITTLE -> "Memory cost is too small"
        MEMORY_TOO_MUCH -> "Memory cost is too large"
        LANES_TOO_FEW -> "Too few lanes"
        LANES_TOO_MANY -> "Too many lanes"
        PWD_PTR_MISMATCH -> "Password pointer is NULL, but password length is not 0"
        SALT_PTR_MISMATCH -> "Salt pointer is NULL, but salt length is not 0"
        -20 -> "Secret pointer is NULL, but secret length is not 0"
        -21 -> "Associated data pointer is NULL, but ad length is not 0"
        -22 -> "Memory allocation error"
        -23 -> "The free memory callback is NULL"
        -24 -> "The allocate memory callback is NULL"
        INCORRECT_PARAMETER -> "Argon2_Context context is NULL"
        INCORRECT_TYPE -> "There is no such version of Argon2"
        -27 -> "Output pointer mismatch"
        -28 -> "Not enough threads"
        -29 -> "Too many threads"
        MISSING_ARGS -> "Missing arguments"
        -31 -> "Encoding failed"
        DECODING_FAIL -> "Decoding failed"
        -33 -> "Threading failure"
        -34 -> "Some of encoded parameters are too long or too short"
        VERIFY_MISMATCH -> "The password does not match the supplied hash"
        else -> "Unknown error code"
    }

    /**
     * `core.c`'s `validate_inputs`, in its own order and with its own codes. `pwdlen` is -1 for a
     * null password, which is the C's `ARGON2_PWD_PTR_MISMATCH`. The C's maximums for the password
     * and the salt are 0xFFFFFFFF, which no Java array can reach, so they are not restated.
     *
     * It runs before argon2kt is touched, which is what makes the refused case's contract exact: a
     * 7-byte salt never reaches the library, so there is no way for a partial tag to be written.
     */
    private fun validate(outlen: Int, pwdlen: Int, salt: ByteArray?, tCost: Int, mCost: Int, lanes: Int): Int {
        if (outlen < MIN_OUTLEN) {
            return OUTPUT_TOO_SHORT
        }
        if (pwdlen < 0) {
            return PWD_PTR_MISMATCH
        }
        if (salt == null) {
            return SALT_PTR_MISMATCH
        }
        if (salt.size < MIN_SALT_LENGTH) {
            return SALT_TOO_SHORT
        }
        if (mCost < MIN_MEMORY) {
            return MEMORY_TOO_LITTLE
        }
        if (mCost > MAX_MEMORY) {
            return MEMORY_TOO_MUCH
        }
        if (lanes > 0 && mCost < 8 * lanes) {
            return MEMORY_TOO_LITTLE
        }
        if (tCost < MIN_TIME) {
            return TIME_TOO_SMALL
        }
        if (tCost > MAX_TIME) {
            return TIME_TOO_LARGE
        }
        if (lanes < MIN_LANES) {
            return LANES_TOO_FEW
        }
        if (lanes > MAX_LANES) {
            return LANES_TOO_MANY
        }
        return OK
    }

    /** The C's type number as argon2kt's mode, or null for a number neither knows. */
    private fun mode(argon2Type: Int): Argon2Mode? = when (argon2Type) {
        0 -> Argon2Mode.ARGON2_D
        1 -> Argon2Mode.ARGON2_I
        2 -> Argon2Mode.ARGON2_ID
        else -> null
    }

    /** The C's version number as argon2kt's version, or null for a number neither knows. */
    private fun hashVersion(version: Int): Argon2Version? = when (version) {
        0x10 -> Argon2Version.V10
        0x13 -> Argon2Version.V13
        else -> null
    }
}
