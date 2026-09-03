package com.ve.sandbox.core.parser

import com.ve.sandbox.core.model.ParsedComponent
import com.ve.sandbox.core.model.ParsedIntentFilter
import com.ve.sandbox.core.model.ParsedManifest
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Stack

/**
 * A direct parser for compiled Android Binary XML (AXML) files, specifically AndroidManifest.xml.
 *
 * Why:
 * Real Android apps installed via adb/PackageManager are parsed by the OS. But inside a container
 * app sandbox, we must inspect the target APK before it runs without system privileges.
 * Android binaries compile XML into binary chunk format (RES_XML_TYPE 0x00080003).
 * This parser decodes the string pool, resource map, element tags, and attributes directly from bytes.
 */
class AndroidBinaryXmlParser {

    companion object {
        private const val RES_XML_TYPE = 0x0003
        private const val RES_STRING_POOL_TYPE = 0x0001
        private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180
        private const val RES_XML_START_NAMESPACE_TYPE = 0x0100
        private const val RES_XML_END_NAMESPACE_TYPE = 0x0101
        private const val RES_XML_START_ELEMENT_TYPE = 0x0102
        private const val RES_XML_END_ELEMENT_TYPE = 0x0103
        private const val RES_XML_CDATA_TYPE = 0x0104

        // Standard Android Resource IDs for manifest attributes
        private const val ATTR_THEME = 0x01010000
        private const val ATTR_LABEL = 0x01010001
        private const val ATTR_ICON = 0x01010002
        private const val ATTR_NAME = 0x01010003
        private const val ATTR_EXPORTED = 0x01010010
        private const val ATTR_PROCESS = 0x01010011
        private const val ATTR_AUTHORITIES = 0x01010018
        private const val ATTR_LAUNCH_MODE = 0x0101001d
        private const val ATTR_SCREEN_ORIENTATION = 0x0101001e
        private const val ATTR_CONFIG_CHANGES = 0x0101001f
        private const val ATTR_MIN_SDK_VERSION = 0x0101020c
        private const val ATTR_VERSION_CODE = 0x0101021b
        private const val ATTR_VERSION_NAME = 0x0101021c
        private const val ATTR_TARGET_SDK_VERSION = 0x01010270

        // TypedValue data types
        private const val TYPE_REFERENCE = 0x01
        private const val TYPE_STRING = 0x03
        private const val TYPE_INT_DEC = 0x10
        private const val TYPE_INT_HEX = 0x11
        private const val TYPE_INT_BOOLEAN = 0x12
    }

    data class RawAttribute(
        val nsIndex: Int,
        val nameIndex: Int,
        val rawValueIndex: Int,
        val dataType: Int,
        val data: Int,
        val name: String,
        val stringValue: String?,
        val resId: Int
    )

    fun parse(bytes: ByteArray): ParsedManifest {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val rootType = buffer.short.toInt() and 0xFFFF
        val rootHeaderSize = buffer.short.toInt() and 0xFFFF
        val rootSize = buffer.int

        if (rootType != RES_XML_TYPE && rootType != 0x0008) {
            // Some tools write 0x00080003 as uint32
            // Buffer might have 0x00080003
            val altMagic = ((rootHeaderSize shl 16) or rootType)
            if (altMagic != 0x00080003) {
                throw IllegalArgumentException("Invalid AXML file magic: 0x${Integer.toHexString(rootType)} (full 0x${Integer.toHexString(altMagic)})")
            }
        }

        var stringPool: List<String> = emptyList()
        var resourceMap: IntArray = IntArray(0)

        // Result state
        var packageName = ""
        var versionCode = 0L
        var versionName = ""
        var minSdkVersion = 1
        var targetSdkVersion = 1
        var appClass: String? = null
        var appLabel: String? = null
        var appTheme: String? = null

        val permissions = mutableListOf<String>()
        val activities = mutableListOf<ParsedComponent>()
        val services = mutableListOf<ParsedComponent>()
        val receivers = mutableListOf<ParsedComponent>()
        val providers = mutableListOf<ParsedComponent>()

        // Parsing context stack
        val elementStack = Stack<String>()
        var currentComponent: ParsedComponent? = null
        var currentIntentFilters = mutableListOf<ParsedIntentFilter>()
        var currentIntentFilter: ParsedIntentFilter? = null

        while (buffer.hasRemaining()) {
            val chunkStart = buffer.position()
            if (chunkStart + 8 > bytes.size) break

            val chunkType = buffer.short.toInt() and 0xFFFF
            val headerSize = buffer.short.toInt() and 0xFFFF
            val chunkSize = buffer.int

            if (chunkSize <= 0 || chunkStart + chunkSize > bytes.size) {
                break
            }

            when (chunkType) {
                RES_STRING_POOL_TYPE -> {
                    stringPool = parseStringPool(buffer, chunkStart, chunkSize)
                    buffer.position(chunkStart + chunkSize)
                }

                RES_XML_RESOURCE_MAP_TYPE -> {
                    val count = (chunkSize - headerSize) / 4
                    resourceMap = IntArray(count)
                    for (i in 0 until count) {
                        resourceMap[i] = buffer.int
                    }
                    buffer.position(chunkStart + chunkSize)
                }

                RES_XML_START_ELEMENT_TYPE -> {
                    val lineNumber = buffer.int
                    val comment = buffer.int
                    val nsIndex = buffer.int
                    val nameIndex = buffer.int
                    val attrStart = buffer.short.toInt() and 0xFFFF
                    val attrSize = buffer.short.toInt() and 0xFFFF
                    val attrCount = buffer.short.toInt() and 0xFFFF
                    val idIndex = buffer.short.toInt() and 0xFFFF
                    val classIndex = buffer.short.toInt() and 0xFFFF
                    val styleIndex = buffer.short.toInt() and 0xFFFF

                    val tagName = stringPool.getOrNull(nameIndex) ?: "unknown"
                    elementStack.push(tagName)

                    val attributes = mutableListOf<RawAttribute>()
                    for (i in 0 until attrCount) {
                        val aNs = buffer.int
                        val aName = buffer.int
                        val aRawVal = buffer.int
                        val valSize = buffer.short.toInt() and 0xFFFF
                        val res0 = buffer.get().toInt() and 0xFF
                        val dataType = buffer.get().toInt() and 0xFF
                        val data = buffer.int

                        val attrNameStr = stringPool.getOrNull(aName) ?: ""
                        val resId = if (aName in resourceMap.indices) resourceMap[aName] else 0

                        val strVal = when (dataType) {
                            TYPE_STRING -> stringPool.getOrNull(data)
                            TYPE_INT_BOOLEAN -> if (data != 0) "true" else "false"
                            TYPE_INT_DEC, TYPE_INT_HEX -> data.toString()
                            TYPE_REFERENCE -> "@0x${Integer.toHexString(data)}"
                            else -> stringPool.getOrNull(aRawVal)
                        } ?: stringPool.getOrNull(aRawVal)

                        attributes.add(
                            RawAttribute(
                                nsIndex = aNs,
                                nameIndex = aName,
                                rawValueIndex = aRawVal,
                                dataType = dataType,
                                data = data,
                                name = attrNameStr,
                                stringValue = strVal,
                                resId = resId
                            )
                        )
                    }

                    fun getAttr(name: String, resId: Int = 0): String? {
                        return attributes.firstOrNull {
                            it.name == name || (resId != 0 && it.resId == resId)
                        }?.stringValue
                    }

                    fun getAttrInt(name: String, resId: Int = 0, default: Int = 0): Int {
                        val attr = attributes.firstOrNull {
                            it.name == name || (resId != 0 && it.resId == resId)
                        } ?: return default
                        return attr.data
                    }

                    fun getAttrIntOrNull(name: String, resId: Int = 0): Int? {
                        val attr = attributes.firstOrNull {
                            it.name == name || (resId != 0 && it.resId == resId)
                        } ?: return null
                        return when (attr.dataType) {
                            TYPE_INT_DEC, TYPE_INT_HEX -> attr.data
                            TYPE_STRING -> attr.stringValue?.let { parseOrientationString(it) }
                            else -> attr.data
                        }
                    }

                    fun getAttrBool(name: String, resId: Int = 0, default: Boolean = false): Boolean {
                        val attr = attributes.firstOrNull {
                            it.name == name || (resId != 0 && it.resId == resId)
                        } ?: return default
                        return if (attr.dataType == TYPE_INT_BOOLEAN) {
                            attr.data != 0
                        } else {
                            attr.stringValue?.toBoolean() ?: default
                        }
                    }

                    when (tagName) {
                        "manifest" -> {
                            packageName = getAttr("package") ?: ""
                            versionCode = getAttrInt("versionCode", ATTR_VERSION_CODE, 0).toLong()
                            versionName = getAttr("versionName", ATTR_VERSION_NAME) ?: ""
                        }

                        "uses-sdk" -> {
                            minSdkVersion = getAttrInt("minSdkVersion", ATTR_MIN_SDK_VERSION, 1)
                            targetSdkVersion = getAttrInt("targetSdkVersion", ATTR_TARGET_SDK_VERSION, minSdkVersion)
                        }

                        "uses-permission" -> {
                            val perm = getAttr("name", ATTR_NAME)
                            if (perm != null && perm.isNotEmpty()) {
                                permissions.add(perm)
                            }
                        }

                        "application" -> {
                            appClass = getAttr("name", ATTR_NAME)?.let { resolveClassName(packageName, it) }
                            appLabel = getAttr("label", ATTR_LABEL)
                            appTheme = getAttr("theme", ATTR_THEME)
                        }

                        "activity", "activity-alias" -> {
                            val rawName = getAttr("name", ATTR_NAME) ?: ""
                            val compName = resolveClassName(packageName, rawName)
                            val exported = getAttrBool("exported", ATTR_EXPORTED, false)
                            val process = getAttr("process", ATTR_PROCESS)
                            val theme = getAttr("theme", ATTR_THEME)
                            val orientation = getAttrIntOrNull("screenOrientation", ATTR_SCREEN_ORIENTATION)
                            val launchMode = getAttrInt("launchMode", ATTR_LAUNCH_MODE, 0)
                            val configChanges = getAttrInt("configChanges", ATTR_CONFIG_CHANGES, 0)

                            currentComponent = ParsedComponent(
                                name = compName,
                                exported = exported,
                                processName = process,
                                theme = theme,
                                screenOrientation = orientation,
                                launchMode = launchMode,
                                configChanges = configChanges
                            )
                            currentIntentFilters = mutableListOf()
                        }

                        "service" -> {
                            val rawName = getAttr("name", ATTR_NAME) ?: ""
                            val compName = resolveClassName(packageName, rawName)
                            val exported = getAttrBool("exported", ATTR_EXPORTED, false)
                            val process = getAttr("process", ATTR_PROCESS)

                            currentComponent = ParsedComponent(
                                name = compName,
                                exported = exported,
                                processName = process
                            )
                            currentIntentFilters = mutableListOf()
                        }

                        "receiver" -> {
                            val rawName = getAttr("name", ATTR_NAME) ?: ""
                            val compName = resolveClassName(packageName, rawName)
                            val exported = getAttrBool("exported", ATTR_EXPORTED, false)
                            val process = getAttr("process", ATTR_PROCESS)

                            currentComponent = ParsedComponent(
                                name = compName,
                                exported = exported,
                                processName = process
                            )
                            currentIntentFilters = mutableListOf()
                        }

                        "provider" -> {
                            val rawName = getAttr("name", ATTR_NAME) ?: ""
                            val compName = resolveClassName(packageName, rawName)
                            val exported = getAttrBool("exported", ATTR_EXPORTED, false)
                            val process = getAttr("process", ATTR_PROCESS)
                            val authorities = getAttr("authorities", ATTR_AUTHORITIES)

                            currentComponent = ParsedComponent(
                                name = compName,
                                exported = exported,
                                processName = process,
                                authorities = authorities
                            )
                            currentIntentFilters = mutableListOf()
                        }

                        "intent-filter" -> {
                            currentIntentFilter = ParsedIntentFilter()
                        }

                        "action" -> {
                            val actionName = getAttr("name", ATTR_NAME)
                            if (actionName != null && currentIntentFilter != null) {
                                currentIntentFilter?.actions?.add(actionName)
                            }
                        }

                        "category" -> {
                            val catName = getAttr("name", ATTR_NAME)
                            if (catName != null && currentIntentFilter != null) {
                                currentIntentFilter?.categories?.add(catName)
                            }
                        }

                        "data" -> {
                            val scheme = getAttr("scheme")
                            if (scheme != null && currentIntentFilter != null) {
                                currentIntentFilter?.dataSchemes?.add(scheme)
                            }
                        }
                    }

                    buffer.position(chunkStart + chunkSize)
                }

                RES_XML_END_ELEMENT_TYPE -> {
                    val lineNumber = buffer.int
                    val comment = buffer.int
                    val nsIndex = buffer.int
                    val nameIndex = buffer.int

                    val tagName = stringPool.getOrNull(nameIndex) ?: "unknown"
                    if (!elementStack.isEmpty()) {
                        elementStack.pop()
                    }

                    when (tagName) {
                        "intent-filter" -> {
                            currentIntentFilter?.let {
                                currentIntentFilters.add(it)
                            }
                            currentIntentFilter = null
                        }

                        "activity", "activity-alias" -> {
                            currentComponent?.let { comp ->
                                val isLauncher = currentIntentFilters.any { filter ->
                                    filter.actions.contains("android.intent.action.MAIN") &&
                                            filter.categories.contains("android.intent.category.LAUNCHER")
                                }
                                activities.add(comp.copy(isLauncher = isLauncher, intentFilters = currentIntentFilters.toList()))
                            }
                            currentComponent = null
                            currentIntentFilters = mutableListOf()
                        }

                        "service" -> {
                            currentComponent?.let { comp ->
                                services.add(comp.copy(intentFilters = currentIntentFilters.toList()))
                            }
                            currentComponent = null
                            currentIntentFilters = mutableListOf()
                        }

                        "receiver" -> {
                            currentComponent?.let { comp ->
                                receivers.add(comp.copy(intentFilters = currentIntentFilters.toList()))
                            }
                            currentComponent = null
                            currentIntentFilters = mutableListOf()
                        }

                        "provider" -> {
                            currentComponent?.let { comp ->
                                providers.add(comp.copy(intentFilters = currentIntentFilters.toList()))
                            }
                            currentComponent = null
                            currentIntentFilters = mutableListOf()
                        }
                    }

                    buffer.position(chunkStart + chunkSize)
                }

                else -> {
                    // Skip any unhandled chunk
                    buffer.position(chunkStart + chunkSize)
                }
            }
        }

        return ParsedManifest(
            packageName = packageName,
            versionCode = versionCode,
            versionName = versionName,
            minSdkVersion = minSdkVersion,
            targetSdkVersion = targetSdkVersion,
            applicationClassName = appClass,
            applicationLabel = appLabel,
            applicationTheme = appTheme,
            permissions = permissions,
            activities = activities,
            services = services,
            receivers = receivers,
            providers = providers
        )
    }

    private fun resolveClassName(packageName: String, className: String): String {
        return when {
            className.startsWith(".") -> "$packageName$className"
            !className.contains(".") -> "$packageName.$className"
            else -> className
        }
    }

    private fun parseStringPool(buffer: ByteBuffer, chunkStart: Int, chunkSize: Int): List<String> {
        val stringCount = buffer.int
        val styleCount = buffer.int
        val flags = buffer.int
        val stringsStart = buffer.int
        val stylesStart = buffer.int

        val isUtf8 = (flags and (1 shl 8)) != 0

        val offsets = IntArray(stringCount)
        for (i in 0 until stringCount) {
            offsets[i] = buffer.int
        }

        val stringsAbsoluteStart = chunkStart + stringsStart
        val strings = ArrayList<String>(stringCount)

        for (i in 0 until stringCount) {
            val strPos = stringsAbsoluteStart + offsets[i]
            buffer.position(strPos)

            if (isUtf8) {
                // Read UTF-8 string: 1-2 bytes u16 len, 1-2 bytes u8 len, then bytes
                decodeUtf8Length(buffer) // u16 len
                val byteLen = decodeUtf8Length(buffer)
                val strBytes = ByteArray(byteLen)
                buffer.get(strBytes)
                strings.add(String(strBytes, StandardCharsets.UTF_8))
            } else {
                // Read UTF-16 string: 2-4 bytes u16 len, then 16-bit chars
                val charLen = decodeUtf16Length(buffer)
                val byteLen = charLen * 2
                val strBytes = ByteArray(byteLen)
                buffer.get(strBytes)
                strings.add(String(strBytes, StandardCharsets.UTF_16LE))
            }
        }

        return strings
    }

    private fun decodeUtf8Length(buffer: ByteBuffer): Int {
        val b1 = buffer.get().toInt() and 0xFF
        return if ((b1 and 0x80) != 0) {
            val b2 = buffer.get().toInt() and 0xFF
            ((b1 and 0x7F) shl 8) or b2
        } else {
            b1
        }
    }

    private fun decodeUtf16Length(buffer: ByteBuffer): Int {
        val b1 = buffer.short.toInt() and 0xFFFF
        return if ((b1 and 0x8000) != 0) {
            val b2 = buffer.short.toInt() and 0xFFFF
            ((b1 and 0x7FFF) shl 16) or b2
        } else {
            b1
        }
    }

    private fun parseOrientationString(str: String): Int? {
        return when (str.lowercase()) {
            "unspecified" -> -1
            "landscape" -> 0
            "portrait" -> 1
            "user" -> 2
            "behind" -> 3
            "sensor" -> 4
            "nosensor" -> 5
            "sensorlandscape" -> 6
            "sensorportrait" -> 7
            "reverselandscape" -> 8
            "reverseportrait" -> 9
            "fullsensor" -> 10
            "userlandscape" -> 11
            "userportrait" -> 12
            "fulluser" -> 13
            "locked" -> 14
            else -> str.toIntOrNull()
        }
    }
}
