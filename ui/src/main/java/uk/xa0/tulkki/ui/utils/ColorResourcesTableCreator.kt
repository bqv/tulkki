/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.xa0.tulkki.ui.utils

import android.content.Context
import androidx.annotation.ColorInt
import java.io.ByteArrayOutputStream
import java.util.Collections

/**
 * This class consists of definitions of resource data structures and helps creates a Color
 * Resources Table on the fly. It is a Java replicate of the framework's code, see
 * frameworks/base/include/ResourceTypes.h.
 */
internal object ColorResourcesTableCreator {

    fun create(context: Context, colorMapping: Map<Int, Int>): ByteArray {
        if (colorMapping.entries.isEmpty()) {
            throw IllegalArgumentException("No color resources provided for harmonization.")
        }
        val applicationPackageInfo =
            PackageInfo(APPLICATION_PACKAGE_ID.toInt(), context.packageName)

        val colorResourceMap = HashMap<PackageInfo, MutableList<ColorResource>>()
        var colorResource: ColorResource? = null
        for ((key, value) in colorMapping.entries) {
            colorResource = ColorResource(
                key,
                context.resources.getResourceEntryName(key),
                value
            )
            if (context.resources.getResourceTypeName(key) != RESOURCE_TYPE_NAME_COLOR) {
                throw IllegalArgumentException(
                    "Non color resource found: name=" +
                        colorResource.name +
                        ", typeId=" +
                        Integer.toHexString(colorResource.typeId.toInt() and 0xFF)
                )
            }
            val packageInfo = when {
                colorResource.packageId == ANDROID_PACKAGE_ID -> ANDROID_PACKAGE_INFO
                colorResource.packageId == APPLICATION_PACKAGE_ID -> applicationPackageInfo
                else -> throw IllegalArgumentException(
                    "Not supported with unknown package id: " + colorResource.packageId
                )
            }
            colorResourceMap.getOrPut(packageInfo) { ArrayList() }.add(colorResource)
        }
        // Resource Type Ids are assigned by aapt arbitrarily, for each new type the next available
        // number is assigned and used. The type id will be the same for resources that are the same
        // type.
        typeIdColor = (colorResource ?: throw NullPointerException("colorResource")).typeId
        if (typeIdColor == 0.toByte()) {
            throw IllegalArgumentException("No color resources found for harmonization.")
        }
        val outputStream = ByteArrayOutputStream()
        ResTable(colorResourceMap).writeTo(outputStream)
        return outputStream.toByteArray()
    }
}

private const val HEADER_TYPE_RES_TABLE: Short = 0x0002
private const val HEADER_TYPE_STRING_POOL: Short = 0x0001
private const val HEADER_TYPE_PACKAGE: Short = 0x0200
private const val HEADER_TYPE_TYPE: Short = 0x0201
private const val HEADER_TYPE_TYPE_SPEC: Short = 0x0202

private const val ANDROID_PACKAGE_ID: Byte = 0x01
private const val APPLICATION_PACKAGE_ID: Byte = 0x7F

private const val RESOURCE_TYPE_NAME_COLOR = "color"

private var typeIdColor: Byte = 0

private val ANDROID_PACKAGE_INFO = PackageInfo(ANDROID_PACKAGE_ID.toInt(), "android")

private val COLOR_RESOURCE_COMPARATOR = Comparator<ColorResource> { res1, res2 ->
    res1.entryId - res2.entryId
}

/**
 * A Table chunk contains: a set of Packages, where a Package is a collection of Resources and a
 * set of strings used by the Resources contained in those Packages.
 *
 * The set of strings are contained in a StringPool chunk. Each Package is contained in a
 * corresponding Package chunk. The StringPool chunk immediately follows the Table chunk header.
 * The Package chunks follow the StringPool chunk.
 */
private class ResTable(colorResourceMap: Map<PackageInfo, MutableList<ColorResource>>) {
    private val header: ResChunkHeader
    private val packageCount: Int = colorResourceMap.size
    private val stringPool = StringPoolChunk()
    private val packageChunks = ArrayList<PackageChunk>()

    init {
        for ((key, value) in colorResourceMap.entries) {
            val colorResources = value
            Collections.sort(colorResources, COLOR_RESOURCE_COMPARATOR)
            packageChunks.add(PackageChunk(key, colorResources))
        }
        header = ResChunkHeader(HEADER_TYPE_RES_TABLE, HEADER_SIZE, getOverallSize())
    }

    fun writeTo(outputStream: ByteArrayOutputStream) {
        header.writeTo(outputStream)
        outputStream.write(intToByteArray(packageCount))
        stringPool.writeTo(outputStream)
        for (packageChunk in packageChunks) {
            packageChunk.writeTo(outputStream)
        }
    }

    private fun getOverallSize(): Int {
        var packageChunkSize = 0
        for (packageChunk in packageChunks) {
            packageChunkSize += packageChunk.getChunkSize()
        }
        return HEADER_SIZE + stringPool.getChunkSize() + packageChunkSize
    }

    companion object {
        private const val HEADER_SIZE: Short = 0x000C
    }
}

/** Header that appears at the front of every data chunk in a resource. */
private class ResChunkHeader(
    // Type identifier for this chunk.  The meaning of this value depends
    // on the containing chunk.
    private val type: Short,
    // Size of the chunk header (in bytes).  Adding this value to
    // the address of the chunk allows you to find its associated data
    // (if any).
    private val headerSize: Short,
    // Total size of this chunk (in bytes).  This is the chunkSize plus
    // the size of any data associated with the chunk.  Adding this value
    // to the chunk allows you to completely skip its contents (including
    // any child chunks).  If this value is the same as chunkSize, there is
    // no data associated with the chunk.
    private val chunkSize: Int
) {
    fun writeTo(outputStream: ByteArrayOutputStream) {
        outputStream.write(shortToByteArray(type))
        outputStream.write(shortToByteArray(headerSize))
        outputStream.write(intToByteArray(chunkSize))
    }
}

/**
 * Immediately following the Table header is a StringPool chunk. It consists of StringPool chunk
 * header and StringPool chunk body.
 */
private class StringPoolChunk(private val utf8Encode: Boolean, vararg rawStrings: String) {

    private val header: ResChunkHeader
    private val stringCount: Int
    private val styledSpanCount: Int
    private val stringsStart: Int
    private val styledSpansStart: Int
    private val stringIndex = ArrayList<Int>()
    private val styledSpanIndex = ArrayList<Int>()
    private val strings = ArrayList<ByteArray>()
    private val styledSpans = ArrayList<List<StringStyledSpan>>()

    private val stringsPaddingSize: Int
    private val chunkSize: Int

    constructor(vararg rawStrings: String) : this(false, *rawStrings)

    init {
        var stringOffset = 0
        for (string in rawStrings) {
            val processedString = processString(string)
            stringIndex.add(stringOffset)
            stringOffset += processedString.first.size
            strings.add(processedString.first)
            styledSpans.add(processedString.second)
        }
        var styledSpanOffset = 0
        for (styledSpanList in styledSpans) {
            for (styledSpan in styledSpanList) {
                stringIndex.add(stringOffset)
                val styleString = styledSpan.styleString
                    ?: throw NullPointerException("styleString")
                stringOffset += styleString.size
                strings.add(styleString)
            }
            styledSpanIndex.add(styledSpanOffset)
            // Each span occupies 3 int32, plus one end mark per chunk
            styledSpanOffset += styledSpanList.size * 12 + 4
        }

        // All chunk size needs to be a multiple of 4
        val stringOffsetResidue = stringOffset % 4
        stringsPaddingSize = if (stringOffsetResidue == 0) 0 else 4 - stringOffsetResidue
        stringCount = strings.size
        styledSpanCount = strings.size - rawStrings.size

        val hasStyledSpans = strings.size - rawStrings.size > 0
        if (!hasStyledSpans) {
            // No styled spans, clear relevant data
            styledSpanIndex.clear()
            styledSpans.clear()
        }

        // Int32 per index
        stringsStart =
            HEADER_SIZE +
                stringCount * 4 + // String index
                styledSpanIndex.size * 4 // Styled span index
        val stringsSize = stringOffset + stringsPaddingSize
        styledSpansStart = if (hasStyledSpans) stringsStart + stringsSize else 0
        chunkSize = stringsStart + stringsSize + (if (hasStyledSpans) styledSpanOffset else 0)
        header = ResChunkHeader(HEADER_TYPE_STRING_POOL, HEADER_SIZE, chunkSize)
    }

    fun writeTo(outputStream: ByteArrayOutputStream) {
        header.writeTo(outputStream)
        outputStream.write(intToByteArray(stringCount))
        outputStream.write(intToByteArray(styledSpanCount))
        outputStream.write(intToByteArray(if (utf8Encode) FLAG_UTF8 else 0))
        outputStream.write(intToByteArray(stringsStart))
        outputStream.write(intToByteArray(styledSpansStart))
        for (index in stringIndex) {
            outputStream.write(intToByteArray(index))
        }
        for (index in styledSpanIndex) {
            outputStream.write(intToByteArray(index))
        }
        for (string in strings) {
            outputStream.write(string)
        }
        if (stringsPaddingSize > 0) {
            outputStream.write(ByteArray(stringsPaddingSize))
        }
        for (styledSpanList in styledSpans) {
            for (styledSpan in styledSpanList) {
                styledSpan.writeTo(outputStream)
            }
            outputStream.write(intToByteArray(STYLED_SPAN_LIST_END))
        }
    }

    fun getChunkSize(): Int = chunkSize

    private fun processString(rawString: String): Pair<ByteArray, List<StringStyledSpan>> {
        // Ignore styled spans, won't be used in our scenario.
        return Pair(
            if (utf8Encode) stringToByteArrayUtf8(rawString) else stringToByteArray(rawString),
            emptyList()
        )
    }

    companion object {
        private const val HEADER_SIZE: Short = 0x001C
        private const val FLAG_UTF8 = 0x00000100

        // Java's int literal 0xFFFFFFFF, written out as -1.
        private const val STYLED_SPAN_LIST_END = -1
    }
}

/** This structure defines a span of style information associated with a string in the pool. */
private class StringStyledSpan {

    var styleString: ByteArray? = null
    private val nameReference = 0
    private val firstCharacterIndex = 0
    private val lastCharacterIndex = 0

    fun writeTo(outputStream: ByteArrayOutputStream) {
        outputStream.write(intToByteArray(nameReference))
        outputStream.write(intToByteArray(firstCharacterIndex))
        outputStream.write(intToByteArray(lastCharacterIndex))
    }
}

/**
 * A Package chunk contains a set of Resources and a set of strings associated with those
 * Resources. The Resources are grouped by type. For each of set of Resources of a given type that
 * the Package chunk contains there is a TypeSpec chunk and one or more Type chunks.
 *
 * The strings are stored in two StringPool chunks: the typeStrings StringPool chunk which
 * contains the names of the types of the Resources defined in the Package; the keyStrings
 * StringPool chunk which contains the names (keys) of the Resources defined in the Package.
 */
private class PackageChunk(
    private val packageInfo: PackageInfo,
    colorResources: List<ColorResource>
) {
    private val header: ResChunkHeader
    private val typeStrings: StringPoolChunk
    private val keyStrings: StringPoolChunk
    private val typeSpecChunk: TypeSpecChunk

    init {
        // Placeholder String type, since only XML color resources will be replaced at runtime.
        typeStrings = StringPoolChunk(false, "?1", "?2", "?3", "?4", "?5", "color")
        val keys = Array(colorResources.size) { colorResources[it].name }
        keyStrings = StringPoolChunk(true, *keys)
        typeSpecChunk = TypeSpecChunk(colorResources)

        header = ResChunkHeader(HEADER_TYPE_PACKAGE, HEADER_SIZE, getChunkSize())
    }

    fun writeTo(outputStream: ByteArrayOutputStream) {
        header.writeTo(outputStream)
        outputStream.write(intToByteArray(packageInfo.id))
        val packageName = packageInfo.name.toCharArray()
        for (i in 0 until PACKAGE_NAME_MAX_LENGTH) {
            if (i < packageName.size) {
                outputStream.write(charToByteArray(packageName[i]))
            } else {
                outputStream.write(charToByteArray('\u0000'))
            }
        }
        outputStream.write(intToByteArray(HEADER_SIZE.toInt())) // Type strings offset
        outputStream.write(intToByteArray(0)) // Last public type
        outputStream.write(
            intToByteArray(HEADER_SIZE + typeStrings.getChunkSize())
        ) // Key strings offset
        outputStream.write(intToByteArray(0)) // Last public key
        outputStream.write(intToByteArray(0)) // Note
        typeStrings.writeTo(outputStream)
        keyStrings.writeTo(outputStream)
        typeSpecChunk.writeTo(outputStream)
    }

    fun getChunkSize(): Int {
        return HEADER_SIZE +
            typeStrings.getChunkSize() +
            keyStrings.getChunkSize() +
            typeSpecChunk.getChunkSizeWithTypeChunk()
    }

    companion object {
        private const val HEADER_SIZE: Short = 0x0120
        private const val PACKAGE_NAME_MAX_LENGTH = 128
    }
}

/**
 * A specification of the resources defined by a particular type.
 *
 * There should be one of these chunks for each resource type.
 *
 * This structure is followed by an array of integers providing the set of configuration change
 * flags (ResTable_config::CONFIG_*) that have multiple resources for that configuration. In
 * addition, the high bit is set if that resource has been made public.
 */
private class TypeSpecChunk(colorResources: List<ColorResource>) {
    private val header: ResChunkHeader
    private val entryCount: Int
    private val entryFlags: IntArray
    private val typeChunk: TypeChunk

    init {
        entryCount = colorResources[colorResources.size - 1].entryId + 1
        val validEntryIds = HashSet<Short>()
        for (colorResource in colorResources) {
            validEntryIds.add(colorResource.entryId)
        }
        entryFlags = IntArray(entryCount)
        // All color resources in the table are marked as PUBLIC.
        for (entryId in 0 until entryCount) {
            if (validEntryIds.contains(entryId.toShort())) {
                entryFlags[entryId] = SPEC_PUBLIC
            }
        }

        header = ResChunkHeader(HEADER_TYPE_TYPE_SPEC, HEADER_SIZE, getChunkSize())

        typeChunk = TypeChunk(colorResources, validEntryIds, entryCount)
    }

    fun writeTo(outputStream: ByteArrayOutputStream) {
        header.writeTo(outputStream)
        outputStream.write(byteArrayOf(typeIdColor, 0x00, 0x00, 0x00))
        outputStream.write(intToByteArray(entryCount))
        for (entryFlag in entryFlags) {
            outputStream.write(intToByteArray(entryFlag))
        }
        typeChunk.writeTo(outputStream)
    }

    fun getChunkSizeWithTypeChunk(): Int {
        return getChunkSize() + typeChunk.getChunkSize()
    }

    private fun getChunkSize(): Int {
        return HEADER_SIZE + entryCount * 4 // Int32 per entry flag
    }

    companion object {
        private const val HEADER_SIZE: Short = 0x0010
        private const val SPEC_PUBLIC = 0x40000000
    }
}

/**
 * A collection of resource entries for a particular resource data type.
 *
 * There may be multiple of these chunks for a particular resource type, supply different
 * configuration variations for the resource values of that type.
 */
private class TypeChunk(
    colorResources: List<ColorResource>,
    entryIds: Set<Short>,
    private val entryCount: Int
) {
    private val header: ResChunkHeader
    private val config = ByteArray(CONFIG_SIZE.toInt())
    private val offsetTable: IntArray
    private val resEntries: Array<ResEntry>

    init {
        this.config[0] = CONFIG_SIZE

        resEntries = Array(colorResources.size) { index ->
            ResEntry(index, colorResources[index].value)
        }

        offsetTable = IntArray(entryCount)
        var currentOffset = 0
        for (entryId in 0 until entryCount) {
            if (entryIds.contains(entryId.toShort())) {
                offsetTable[entryId] = currentOffset
                currentOffset += ResEntry.SIZE
            } else {
                offsetTable[entryId] = OFFSET_NO_ENTRY
            }
        }

        header = ResChunkHeader(HEADER_TYPE_TYPE, HEADER_SIZE, getChunkSize())
    }

    fun writeTo(outputStream: ByteArrayOutputStream) {
        header.writeTo(outputStream)
        outputStream.write(byteArrayOf(typeIdColor, 0x00, 0x00, 0x00))
        outputStream.write(intToByteArray(entryCount))
        outputStream.write(intToByteArray(getEntryStart()))
        outputStream.write(config)
        for (offset in offsetTable) {
            outputStream.write(intToByteArray(offset))
        }
        for (entry in resEntries) {
            entry.writeTo(outputStream)
        }
    }

    fun getChunkSize(): Int {
        return getEntryStart() + resEntries.size * ResEntry.SIZE
    }

    private fun getEntryStart(): Int {
        return HEADER_SIZE + getOffsetTableSize()
    }

    private fun getOffsetTableSize(): Int {
        return offsetTable.size * 4 // One int32 per entry
    }

    companion object {
        private const val OFFSET_NO_ENTRY = -1

        private const val HEADER_SIZE: Short = 0x0054
        private const val CONFIG_SIZE: Byte = 0x40
    }
}

/**
 * This is the beginning of information about an entry in the resource table. It holds the
 * reference to the name of this entry, and is immediately followed by one of: A Res_value
 * structure, if FLAG_COMPLEX is -not- set. An array of ResTable_map structures, if FLAG_COMPLEX
 * is set. These supply a set of name/value mappings of data.
 */
private class ResEntry(private val keyStringIndex: Int, @ColorInt private val data: Int) {

    fun writeTo(outputStream: ByteArrayOutputStream) {
        outputStream.write(shortToByteArray(ENTRY_SIZE))
        outputStream.write(shortToByteArray(FLAG_PUBLIC))
        outputStream.write(intToByteArray(keyStringIndex))
        outputStream.write(shortToByteArray(VALUE_SIZE))
        outputStream.write(byteArrayOf(0x00, DATA_TYPE_AARRGGBB))
        outputStream.write(intToByteArray(data))
    }

    companion object {
        private const val ENTRY_SIZE: Short = 8
        private const val FLAG_PUBLIC: Short = 0x0002 // Always set to "Public"
        private const val VALUE_SIZE: Short = 8
        private const val DATA_TYPE_AARRGGBB: Byte = 0x1C // Type #aarrggbb

        const val SIZE: Int = ENTRY_SIZE + VALUE_SIZE
    }
}

/** The basic info of a package, which consists of the id and the name of the package. */
private class PackageInfo(val id: Int, val name: String)

/**
 * A Color Resource object, which consists of the id of the package that the resource belongs to;
 * the name and value of the color resource.
 */
private class ColorResource(id: Int, val name: String, @ColorInt val value: Int) {
    val packageId: Byte
    val typeId: Byte
    val entryId: Short

    init {
        entryId = (id and 0xFFFF).toShort()
        typeId = ((id shr 16) and 0xFF).toByte()
        packageId = ((id shr 24) and 0xFF).toByte()
    }
}

private fun shortToByteArray(value: Short): ByteArray {
    return byteArrayOf(
        (value.toInt() and 0xFF).toByte(), ((value.toInt() shr 8) and 0xFF).toByte()
    )
}

private fun charToByteArray(value: Char): ByteArray {
    return byteArrayOf(
        (value.code and 0xFF).toByte(), ((value.code shr 8) and 0xFF).toByte()
    )
}

private fun intToByteArray(value: Int): ByteArray {
    return byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    )
}

private fun stringToByteArray(value: String): ByteArray {
    val chars = value.toCharArray()
    val bytes = ByteArray(chars.size * 2 + 4)
    val lengthBytes = shortToByteArray(chars.size.toShort())
    bytes[0] = lengthBytes[0]
    bytes[1] = lengthBytes[1]
    for (i in chars.indices) {
        val charBytes = charToByteArray(chars[i])
        bytes[i * 2 + 2] = charBytes[0]
        bytes[i * 2 + 3] = charBytes[1]
    }
    bytes[bytes.size - 2] = 0
    bytes[bytes.size - 1] = 0 // EOS
    return bytes
}

private fun stringToByteArrayUtf8(value: String): ByteArray {
    val rawBytes = value.toByteArray(Charsets.UTF_8)
    val stringLength = rawBytes.size.toByte()
    val bytes = ByteArray(rawBytes.size + 3)
    System.arraycopy(rawBytes, 0, bytes, 2, stringLength.toInt())
    bytes[0] = stringLength
    bytes[1] = stringLength
    bytes[bytes.size - 1] = 0 // EOS
    return bytes
}
