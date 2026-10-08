package org.signal.argon2

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Arrays
import java.util.LinkedHashMap
import java.util.regex.Pattern
import org.junit.Assert
import org.junit.Test

/**
 * What the suite can hold about the Argon2 demotion, and the two things it cannot be allowed to imply.
 *
 * <p>port-16 moves the app's Argon2 from the in-tree C (through its JNI wrapper) to
 * {@code com.lambdapioneer.argon2kt:argon2kt:1.6.0}. Byte-identity with the captured vectors is the
 * gate, and its proof is a <em>source-level</em> one: argon2kt vendors the same phc-winner-argon2
 * reference C, and that C was compiled here and compared against
 * {@code crypto/src/test/resources/argon2/known-answer.txt} - all seven tags and all seven encoded
 * strings identical at the app's parameters, no encoding change ({@code tools/argon2-vectors
 * --against}). **The last gate is one on-device known-answer run**, because the published AAR ships
 * four bionic ABIs and cannot execute on the JVM; that test belongs to the integration lane.
 *
 * <p><strong>So this class deliberately does not execute a hash.</strong> A cell here that produced a
 * tag would be either a fake or an {@code UnsatisfiedLinkError}, and a suite that appeared to prove
 * the database key would be worse than one that names the gap. What it does hold:
 *
 * <ul>
 *   <li><strong>the configuration pin</strong> - the call passes every parameter, and
 *       <strong>{@code parallelism} explicitly</strong>. argon2kt's own default is 2, so a port that
 *       let the default through would change the key and the {@code $argon2id$v=19$…$…} string with no
 *       error. This is the mutation the pin exists to catch;
 *   <li><strong>the refusal contract</strong>, which is executable on the JVM because it is decided
 *       <em>before</em> argon2kt is touched: a 7-byte salt is {@code ARGON2_SALT_TOO_SHORT} and the
 *       caller's output buffer comes back exactly as it went in (the property the capture proves by
 *       seeding the buffer for N1). A buffer holding half a tag is a different bug from one holding
 *       none.
 * </ul>
 *
 * <p><strong>Where the configuration pin reads its evidence.</strong> The class was Java and is
 * Kotlin now, and the pins that used to be source text have been moved to the <em>compiled</em>
 * class file, which is language-neutral by construction:
 *
 * <ul>
 *   <li>the argon2kt entry point is <em>called</em> - a {@code Methodref} in the constant pool whose
 *       descriptor is the eight-argument {@code hash}, not an import line that could be unused;
 *   <li>the sixth argument of that call is the method's own {@code parallelism} parameter - the
 *       bytecode loads local 2 there, where a constant or another argument would be a different
 *       instruction. A source regex could only check the spelling {@code parallelism}; this checks
 *       the value that actually arrives;
 *   <li>the mode and version are the intended ones - {@code Argon2Mode.ARGON2_ID} and
 *       {@code Argon2Version.V13} are {@code Fieldref}s the class reads, again not source text;
 *   <li>no native loader - the source has no {@code loadLibrary}, and the compiled class carries no
 *       reference or string naming one.
 * </ul>
 *
 * <p>The class's declared source is still located, because a port that left no source would be a
 * different defect; but it may be {@code .java} or {@code .kt} and nothing here pins which, or how
 * the Kotlin is spelled.
 */
class Argon2KnownAnswerTest {

    private companion object {

        const val RESOURCE = "crypto/src/test/resources/argon2/known-answer.txt"

        const val PACKAGE = "crypto/src/main/java/org/signal/argon2"

        const val SIMPLE_NAME = "Argon2Native"

        const val CLASS_RESOURCE = "/org/signal/argon2/Argon2Native.class"

        const val ARGON2KT = "com/lambdapioneer/argon2kt/Argon2Kt"

        /**
         * The eight-argument {@code Argon2Kt.hash} the class must call, exactly: mode, salt, pwd, tCost,
         * mCost, then an {@code int} in the parallelism slot, then the output length and the version. The
         * descriptor is part of the pin - an overload that omits the parallelism would be a shorter
         * descriptor, or the {@code hash$default} synthetic Kotlin emits when it takes a default.
         */
        const val ARGON2KT_HASH_DESCRIPTOR =
            "(Lcom/lambdapioneer/argon2kt/Argon2Mode;[B[BIIIILcom/lambdapioneer/argon2kt/Argon2Version;)" +
                "Lcom/lambdapioneer/argon2kt/Argon2KtResult;"

        /** {@code hash}'s own JVM shape, the method the bytecode pin reads. */
        const val HASH_DESCRIPTOR = "(III[B[B[BLjava/lang/StringBuffer;II)I"

        fun repositoryRoot(): Path {
            var directory: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
            while (directory != null) {
                if (Files.isRegularFile(directory.resolve("settings.gradle.kts"))
                        || Files.isRegularFile(directory.resolve("settings.gradle"))) {
                    return directory
                }
                directory = directory.parent
            }
            throw AssertionError(
                "could not find settings.gradle.kts above " + System.getProperty("user.dir"))
        }

        /**
         * The class's declared source, in either spelling. The pin used to name
         * {@code Argon2Native.java} and so forbade the port; what it meant was "the implementation has a
         * declared source", which is what is asserted now - exactly one, and it must declare the class.
         */
        fun implementationSource(): Path {
            val directory = repositoryRoot().resolve(PACKAGE)
            val found = ArrayList<Path>()
            for (extension in arrayOf("java", "kt")) {
                val candidate = directory.resolve(SIMPLE_NAME + "." + extension)
                if (Files.isRegularFile(candidate)) {
                    found.add(candidate)
                }
            }
            Assert.assertEquals(
                "the class must have exactly one declared source under " + directory + ": " + found,
                1,
                found.size)
            return found[0]
        }

        fun source(path: Path): String =
            String(Files.readAllBytes(path), StandardCharsets.UTF_8)

        fun compiledClass(): ByteArray {
            val input = Argon2Native::class.java.getResourceAsStream(CLASS_RESOURCE)
            Assert.assertNotNull(
                "the compiled class must sit on the test classpath at " + CLASS_RESOURCE, input)
            return input!!.use { it.readAllBytes() }
        }

        fun capture(): List<String> {
            val path = repositoryRoot().resolve(RESOURCE)
            Assert.assertTrue(
                "the capture must be where the test points: " + path, Files.isRegularFile(path))
            return Files.readAllLines(path, StandardCharsets.UTF_8)
        }

        fun parameterLine(lines: List<String>): String {
            for (line in lines) {
                if (line.startsWith("parameters ")) {
                    return line
                }
            }
            Assert.fail("the capture carries no `parameters` line")
            return ""
        }
    }

    /**
     * The pin that used to say "the file must be Argon2Native.java", language-neutral: the class has
     * one declared source, in either spelling, and that source declares it.
     */
    @Test
    fun theImplementationHasExactlyOneDeclaredSource() {
        val sourcePath = implementationSource()
        Assert.assertTrue(
            "the declaration must be in the source the pin found: " + sourcePath,
            Pattern.compile("\\b(?:class|object)\\s+" + SIMPLE_NAME + "\\b")
                .matcher(source(sourcePath))
                .find())
    }

    /**
     * The mutation that matters: the parallelism is an argument, not a default. The bytecode of
     * {@code hash} must call the eight-argument argon2kt overload, and the value in the parallelism
     * argument slot must be the method's own {@code parallelism} parameter (local 2 in a static
     * method with {@code tCost}, {@code mCost}, {@code parallelism} first). A constant, or another
     * argument, is a different instruction and fails here - which is strictly more than the old
     * source regex could say, because it checks the value the compiler actually passes.
     */
    @Test
    fun theCallPassesEveryParameterIncludingParallelism() {
        val classFile = Argon2NativeClassFile(compiledClass())

        val call = classFile.methodReference(ARGON2KT, "hash", ARGON2KT_HASH_DESCRIPTOR)
        Assert.assertTrue(
            "the class must call the eight-argument Argon2Kt.hash with an int in the " +
                "parallelism slot - an overload that omits parallelism silently takes " +
                "argon2kt's default of 2",
            call >= 0)

        val code = classFile.code("hash", HASH_DESCRIPTOR)
        Assert.assertNotNull("the compiled hash method must be in the class file", code)
        val codeBytes = code!!
        val instructions = decode(codeBytes)
        var invoke = -1
        for (i in instructions.indices) {
            val instruction = instructions[i]
            if (instruction[1] == 0xB6 && u2(codeBytes, instruction[0] + 1) == call) {
                invoke = i
                break
            }
        }
        Assert.assertTrue(
            "the compiled hash method must actually invoke Argon2Kt.hash, not merely name it",
            invoke >= 4)
        Assert.assertEquals(
            "the value just below the returned hash length is the hash array load",
            0x19,
            instructions[invoke - 3][1] and 0xFF)
        Assert.assertEquals(
            "then the output-buffer length (arg 7)", 0xBE, instructions[invoke - 2][1] and 0xFF)
        Assert.assertEquals(
            "then the version load (arg 8)", 0x19, instructions[invoke - 1][1] and 0xFF)
        val parallel = instructions[invoke - 4]
        Assert.assertTrue(
            "and arg 6, the parallelism slot, must load the method's own `parallelism` " +
                "parameter (JVM local 2); a constant or another argument would be a " +
                "different instruction, and the derived key would change",
            isIntLoadOfLocal(parallel[1] and 0xFF, codeBytes, parallel[0], 2))
    }

    /**
     * The implementation must be the dependency the row names, not a second implementation of it.
     * Every fact is read from the compiled class: the entry point is called, the mode and version
     * the class reads are the intended enum members, and nothing loads a native library.
     */
    @Test
    fun theImplementationIsArgon2kt() {
        val classFile = Argon2NativeClassFile(compiledClass())
        Assert.assertEquals(
            "the class file must be the class under test",
            "org/signal/argon2/Argon2Native",
            classFile.thisName())
        Assert.assertTrue(
            "the argon2kt entry point must be called from the compiled class",
            classFile.methodReference(ARGON2KT, "hash", ARGON2KT_HASH_DESCRIPTOR) >= 0)
        Assert.assertTrue(
            "the compiled class must read Argon2Mode.ARGON2_ID",
            classFile.hasReference("com/lambdapioneer/argon2kt/Argon2Mode", "ARGON2_ID"))
        Assert.assertTrue(
            "the compiled class must read Argon2Version.V13",
            classFile.hasReference("com/lambdapioneer/argon2kt/Argon2Version", "V13"))

        val sourceText = source(implementationSource())
        Assert.assertFalse(
            "the JNI loader must be gone: the C it loaded is no longer built",
            sourceText.contains("loadLibrary"))
        Assert.assertFalse(
            "and the compiled class must not carry a native-loader reference or string either",
            classFile.namesAConstantContaining("loadLibrary"))
    }

    /**
     * The capture's parameters, and the shape of the string argon2kt's encoder must produce: the
     * parameters live in the encoded output, so a wrong `p` is visible there as well as in the key.
     */
    @Test
    fun theCapturePinsTheParametersAndTheEncodedShape() {
        val lines = capture()
        Assert.assertEquals(
            "the app's parameters, including p = 4",
            "parameters t=3 m=65536 p=4 out=32 type=argon2id version=19",
            parameterLine(lines))

        var answers = 0
        for (line in lines) {
            val encoded = line.indexOf("|enc=\$argon2id\$")
            if (encoded < 0) {
                continue
            }
            answers++
            val value = line.substring(encoded + "|enc=".length)
            Assert.assertTrue(
                "every answer's encoding carries type, version, m, t and p: " + value,
                value.startsWith("\$argon2id\$v=19\$m=65536,t=3,p=4\$"))
        }
        Assert.assertEquals("V1-V7", 7, answers)
    }

    /**
     * The refusal, executable on the JVM: the C's validation runs before argon2kt is touched, so this
     * needs no native library - and it asserts the buffer-untouched property, not merely the code.
     */
    @Test
    fun aRefusedSaltLeavesTheOutputBufferUntouched() {
        val password = "salasana".toByteArray(StandardCharsets.US_ASCII)
        val sevenByteSalt = "1234567".toByteArray(StandardCharsets.US_ASCII)
        val out = ByteArray(32)
        Arrays.fill(out, 0xA5.toByte())
        val seed = out.clone()
        val encoded = StringBuffer()

        val rc = Argon2Native.hash(3, 65536, 4, password, sevenByteSalt, out, encoded, 2, 0x13)

        Assert.assertEquals("the C's ARGON2_SALT_TOO_SHORT", Argon2Native.SALT_TOO_SHORT, rc)
        Assert.assertArrayEquals("the caller's buffer must be exactly as it was", seed, out)
        Assert.assertEquals("and nothing is encoded for a refusal", 0, encoded.length)
        Assert.assertEquals(
            "the message names the reason", "Salt is too short", Argon2Native.resultToString(rc))
    }

    /** The bounds the C checks are the same ones here: a caller cannot get a tag outside them. */
    @Test
    fun theCValidationBoundsAreKept() {
        val password = "salasana".toByteArray(StandardCharsets.US_ASCII)
        val salt = "12345678".toByteArray(StandardCharsets.US_ASCII)
        val out = ByteArray(32)

        Assert.assertEquals(
            "an output shorter than 4 bytes",
            Argon2Native.OUTPUT_TOO_SHORT,
            Argon2Native.hash(3, 65536, 4, password, salt, ByteArray(3), null, 2, 0x13))
        Assert.assertEquals(
            "memory below 8 KiB",
            Argon2Native.MEMORY_TOO_LITTLE,
            Argon2Native.hash(3, 4, 1, password, salt, out, null, 2, 0x13))
        Assert.assertEquals(
            "memory below 8 x lanes",
            Argon2Native.MEMORY_TOO_LITTLE,
            Argon2Native.hash(3, 64, 16, password, salt, out, null, 2, 0x13))
        Assert.assertEquals(
            "zero iterations",
            Argon2Native.TIME_TOO_SMALL,
            Argon2Native.hash(0, 65536, 4, password, salt, out, null, 2, 0x13))
        Assert.assertEquals(
            "zero lanes",
            Argon2Native.LANES_TOO_FEW,
            Argon2Native.hash(3, 65536, 0, password, salt, out, null, 2, 0x13))
        Assert.assertEquals(
            "a type neither knows",
            Argon2Native.INCORRECT_TYPE,
            Argon2Native.hash(3, 65536, 4, password, salt, out, null, 7, 0x13))
    }

    /**
     * The compiled class, read as bytes so nothing initialises it (argon2kt's {@code Argon2Kt} would
     * load a bionic-only library if it were resolved). Only the constant pool and the one method's
     * code are interpreted.
     */
    private class Argon2NativeClassFile(private val bytes: ByteArray) {

        private val utf8: Array<String?>
        private val tags: IntArray
        private val classReference: IntArray
        private val refClass: IntArray
        private val refNameType: IntArray
        private val nameTypeName: IntArray
        private val nameTypeDescriptor: IntArray
        private val references = ArrayList<String>()
        private val codeByMember = LinkedHashMap<String, ByteArray>()
        private var className: String? = null

        init {
            Assert.assertEquals("a class file starts with 0xCAFEBABE", 0xCAFEBABE.toInt(), u4(bytes, 0))
            val constantPoolCount = u2(bytes, 8)
            utf8 = arrayOfNulls(constantPoolCount)
            tags = IntArray(constantPoolCount)
            classReference = IntArray(constantPoolCount)
            refClass = IntArray(constantPoolCount)
            refNameType = IntArray(constantPoolCount)
            nameTypeName = IntArray(constantPoolCount)
            nameTypeDescriptor = IntArray(constantPoolCount)

            var at = 10
            var i = 1
            while (i < constantPoolCount) {
                val tag = u1(bytes, at)
                at++
                tags[i] = tag
                when (tag) {
                    1 -> {
                        val length = u2(bytes, at)
                        at += 2
                        utf8[i] = String(bytes, at, length, StandardCharsets.UTF_8)
                        at += length
                    }
                    3, 4 -> at += 4
                    5, 6 -> {
                        at += 8
                        i++
                    }
                    7 -> {
                        classReference[i] = u2(bytes, at)
                        at += 2
                    }
                    8, 16, 19, 20 -> at += 2
                    9, 10, 11 -> {
                        refClass[i] = u2(bytes, at)
                        refNameType[i] = u2(bytes, at + 2)
                        at += 4
                    }
                    12 -> {
                        nameTypeName[i] = u2(bytes, at)
                        nameTypeDescriptor[i] = u2(bytes, at + 2)
                        at += 4
                    }
                    15 -> at += 3
                    17, 18 -> at += 4
                    else -> throw AssertionError("unknown constant pool tag " + tag + " at " + i)
                }
                i++
            }
            for (index in 1 until constantPoolCount) {
                if (tags[index] == 9 || tags[index] == 10 || tags[index] == 11) {
                    references.add(
                        owner(index) + "." + name(index) + ":" + descriptor(index))
                }
            }

            at += 6 // access flags, this class, super class
            className = utf8[classReference[u2(bytes, at - 4)]]
            val interfaces = u2(bytes, at)
            at += 2 + 2 * interfaces
            val fields = u2(bytes, at)
            at += 2
            at = skipMembers(bytes, at, fields)
            val methods = u2(bytes, at)
            at += 2
            for (m in 0 until methods) {
                at += 2 // access flags
                val methodName = utf8[u2(bytes, at)]
                val methodDescriptor = utf8[u2(bytes, at + 2)]
                at += 4
                val attributes = u2(bytes, at)
                at += 2
                for (a in 0 until attributes) {
                    val attribute = utf8[u2(bytes, at)]
                    val attributeLength = u4(bytes, at + 2)
                    at += 6
                    if ("Code" == attribute) {
                        val codeLength = u4(bytes, at + 4)
                        codeByMember[methodName + methodDescriptor] =
                            Arrays.copyOfRange(bytes, at + 8, at + 8 + codeLength)
                    }
                    at += attributeLength
                }
            }
            Assert.assertNotNull("the class file must declare its own name", className)
        }

        private fun owner(index: Int): String? = utf8[classReference[refClass[index]]]

        private fun name(index: Int): String? = utf8[nameTypeName[refNameType[index]]]

        private fun descriptor(index: Int): String? =
            utf8[nameTypeDescriptor[refNameType[index]]]

        fun thisName(): String? = className

        /** The constant-pool index of the given method reference, or -1. */
        fun methodReference(owner: String, name: String, descriptor: String): Int {
            val wanted = owner + "." + name + ":" + descriptor
            for (index in 1 until tags.size) {
                if (tags[index] == 10 && wanted == references[indexOf(index)]) {
                    return index
                }
            }
            return -1
        }

        private fun indexOf(constantPoolIndex: Int): Int {
            var seen = 0
            for (index in 1 until tags.size) {
                if (tags[index] == 9 || tags[index] == 10 || tags[index] == 11) {
                    if (index == constantPoolIndex) {
                        return seen
                    }
                    seen++
                }
            }
            return -1
        }

        fun hasReference(owner: String, name: String): Boolean {
            val prefix = owner + "." + name + ":"
            for (reference in references) {
                if (reference.startsWith(prefix)) {
                    return true
                }
            }
            return false
        }

        fun namesAConstantContaining(needle: String): Boolean {
            for (value in utf8) {
                if (value != null && value.contains(needle)) {
                    return true
                }
            }
            return false
        }

        fun code(name: String, descriptor: String): ByteArray? = codeByMember[name + descriptor]
    }
}

private fun u1(bytes: ByteArray, at: Int): Int = bytes[at].toInt() and 0xFF

private fun u2(bytes: ByteArray, at: Int): Int = (u1(bytes, at) shl 8) or u1(bytes, at + 1)

private fun u4(bytes: ByteArray, at: Int): Int =
    (u1(bytes, at) shl 24) or
        (u1(bytes, at + 1) shl 16) or
        (u1(bytes, at + 2) shl 8) or
        u1(bytes, at + 3)

/** True when the instruction loads JVM local `local` as an int. */
private fun isIntLoadOfLocal(opcode: Int, code: ByteArray, offset: Int, local: Int): Boolean {
    if (opcode == 0x15) {
        return u1(code, offset + 1) == local
    }
    return opcode == 0x1A + local // iload_0..iload_3
}

/** Instruction byte lengths, with `tableswitch`/`lookupswitch`/`wide` handled separately. */
private fun instructionLength(code: ByteArray, offset: Int): Int {
    val opcode = u1(code, offset)
    if (opcode == 0xAA || opcode == 0xAB) {
        val pad = (4 - ((offset + 1) % 4)) % 4
        val base = offset + 1 + pad
        if (opcode == 0xAA) {
            return 1 + pad + 12 + 4 * (u4(code, base + 8) - u4(code, base + 4) + 1)
        }
        return 1 + pad + 8 + 8 * u4(code, base + 4)
    }
    if (opcode == 0xC4) {
        return if (u1(code, offset + 1) == 0x84) 6 else 4
    }
    return LENGTHS[opcode]
}

private fun decode(code: ByteArray): List<IntArray> {
    val instructions = ArrayList<IntArray>()
    var offset = 0
    while (offset < code.size) {
        instructions.add(intArrayOf(offset, u1(code, offset)))
        offset += instructionLength(code, offset)
    }
    Assert.assertEquals("the code must decode exactly to its end", code.size, offset)
    return instructions
}

private fun skipMembers(bytes: ByteArray, from: Int, count: Int): Int {
    var at = from
    for (i in 0 until count) {
        at += 6 // access flags, name, descriptor
        val attributes = u2(bytes, at)
        at += 2
        for (a in 0 until attributes) {
            val length = u4(bytes, at + 2)
            at += 6 + length
        }
    }
    return at
}

private val LENGTHS: IntArray =
    IntArray(256).apply {
        fill(1)
        this[0x10] = 2 // bipush
        this[0x11] = 3 // sipush
        this[0x12] = 2 // ldc
        this[0x13] = 3 // ldc_w
        this[0x14] = 3 // ldc2_w
        for (opcode in 0x15..0x19) {
            this[opcode] = 2 // iload, lload, fload, dload, aload
        }
        for (opcode in 0x36..0x3A) {
            this[opcode] = 2 // istore, lstore, fstore, dstore, astore
        }
        this[0x84] = 3 // iinc
        for (opcode in 0x99..0xA8) {
            this[opcode] = 3 // if<cond>, if_icmp<cond>, if_acmp<cond>, goto, jsr
        }
        this[0xA9] = 2 // ret
        for (opcode in 0xB2..0xB8) {
            this[opcode] = 3 // getstatic..invokestatic
        }
        this[0xB9] = 5 // invokeinterface
        this[0xBA] = 5 // invokedynamic
        this[0xBB] = 3 // new
        this[0xBC] = 2 // newarray
        this[0xBD] = 3 // anewarray
        this[0xC0] = 3 // checkcast
        this[0xC1] = 3 // instanceof
        this[0xC5] = 4 // multianewarray
        this[0xC6] = 3 // ifnull
        this[0xC7] = 3 // ifnonnull
        this[0xC8] = 5 // goto_w
        this[0xC9] = 5 // jsr_w
    }
