package com.ve.sandbox.core.context

import android.content.SharedPreferences
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * An isolated, thread-safe SharedPreferences implementation that persists directly
 * to a dedicated file in the guest application's sandbox directory.
 *
 * Uses standard self-contained XML encoding for maximum portability across
 * ART runtime, unit tests, and all Android API levels without reliance on framework stubs.
 */
class VirtualSharedPreferences(
    private val prefFile: File
) : SharedPreferences {

    companion object {
        private const val TAG = "VirtualSharedPrefs"
        private val writeExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    }

    private val lock = Any()
    private val data = ConcurrentHashMap<String, Any>()
    private val listeners = CopyOnWriteArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()
    private val backupFile = File(prefFile.parentFile, "${prefFile.name}.bak")

    init {
        loadFromDisk()
    }

    private fun loadFromDisk() {
        synchronized(lock) {
            if (backupFile.exists()) {
                prefFile.delete()
                backupFile.renameTo(prefFile)
            }

            if (!prefFile.exists()) return

            try {
                BufferedReader(InputStreamReader(FileInputStream(prefFile), StandardCharsets.UTF_8)).use { reader ->
                    var currentKey: String? = null
                    var currentSet: MutableSet<String>? = null

                    reader.forEachLine { line ->
                        val trimmed = line.trim()
                        when {
                            trimmed.startsWith("<string name=\"") -> {
                                val key = extractAttribute(trimmed, "name")
                                val content = extractTagContent(trimmed, "string")
                                if (key != null && content != null) {
                                    data[key] = unescapeXml(content)
                                }
                            }
                            trimmed.startsWith("<int name=\"") -> {
                                val key = extractAttribute(trimmed, "name")
                                val value = extractAttribute(trimmed, "value")?.toIntOrNull()
                                if (key != null && value != null) data[key] = value
                            }
                            trimmed.startsWith("<long name=\"") -> {
                                val key = extractAttribute(trimmed, "name")
                                val value = extractAttribute(trimmed, "value")?.toLongOrNull()
                                if (key != null && value != null) data[key] = value
                            }
                            trimmed.startsWith("<float name=\"") -> {
                                val key = extractAttribute(trimmed, "name")
                                val value = extractAttribute(trimmed, "value")?.toFloatOrNull()
                                if (key != null && value != null) data[key] = value
                            }
                            trimmed.startsWith("<boolean name=\"") -> {
                                val key = extractAttribute(trimmed, "name")
                                val value = extractAttribute(trimmed, "value")?.toBoolean()
                                if (key != null && value != null) data[key] = value
                            }
                            trimmed.startsWith("<set name=\"") -> {
                                currentKey = extractAttribute(trimmed, "name")
                                currentSet = mutableSetOf()
                            }
                            trimmed.startsWith("<string>") && currentSet != null -> {
                                val item = extractTagContent(trimmed, "string")
                                if (item != null) currentSet?.add(unescapeXml(item))
                            }
                            trimmed == "</set>" && currentKey != null && currentSet != null -> {
                                data[currentKey!!] = currentSet!!
                                currentKey = null
                                currentSet = null
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Error loading SharedPreferences from ${prefFile.absolutePath}", e)
            }
        }
    }

    private fun writeToDisk(): Boolean {
        return synchronized(lock) {
            try {
                prefFile.parentFile?.mkdirs()

                if (prefFile.exists()) {
                    if (!backupFile.exists()) {
                        if (!prefFile.renameTo(backupFile)) {
                            Log.e(TAG, "Failed to rename $prefFile to backup $backupFile")
                            return false
                        }
                    } else {
                        prefFile.delete()
                    }
                }

                PrintWriter(OutputStreamWriter(FileOutputStream(prefFile), StandardCharsets.UTF_8)).use { writer ->
                    writer.println("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>")
                    writer.println("<map>")

                    for ((key, value) in data) {
                        val k = escapeXml(key)
                        when (value) {
                            is String -> {
                                writer.println("    <string name=\"$k\">${escapeXml(value)}</string>")
                            }
                            is Int -> {
                                writer.println("    <int name=\"$k\" value=\"$value\" />")
                            }
                            is Long -> {
                                writer.println("    <long name=\"$k\" value=\"$value\" />")
                            }
                            is Float -> {
                                writer.println("    <float name=\"$k\" value=\"$value\" />")
                            }
                            is Boolean -> {
                                writer.println("    <boolean name=\"$k\" value=\"$value\" />")
                            }
                            is Set<*> -> {
                                writer.println("    <set name=\"$k\">")
                                for (item in value) {
                                    if (item is String) {
                                        writer.println("        <string>${escapeXml(item)}</string>")
                                    }
                                }
                                writer.println("    </set>")
                            }
                        }
                    }

                    writer.println("</map>")
                    writer.flush()
                }

                backupFile.delete()
                true
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to write SharedPreferences to ${prefFile.absolutePath}", e)
                if (prefFile.exists()) {
                    prefFile.delete()
                }
                false
            }
        }
    }

    private fun extractAttribute(tag: String, attr: String): String? {
        val pattern = "$attr=\""
        val start = tag.indexOf(pattern)
        if (start == -1) return null
        val valStart = start + pattern.length
        val end = tag.indexOf("\"", valStart)
        if (end == -1) return null
        return tag.substring(valStart, end)
    }

    private fun extractTagContent(line: String, tag: String): String? {
        val openTag = "<$tag"
        val closeTag = "</$tag>"
        val openEnd = line.indexOf(">", line.indexOf(openTag))
        val closeStart = line.indexOf(closeTag)
        if (openEnd == -1 || closeStart == -1 || closeStart <= openEnd) return null
        return line.substring(openEnd + 1, closeStart)
    }

    private fun escapeXml(str: String): String {
        return str.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun unescapeXml(str: String): String {
        return str.replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
    }

    override fun getAll(): Map<String, *> = HashMap(data)

    override fun getString(key: String, defValue: String?): String? {
        val value = data[key]
        return if (value is String) value else defValue
    }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? {
        val value = data[key]
        return if (value is Set<*>) (value as Set<String>).toSet() else defValues
    }

    override fun getInt(key: String, defValue: Int): Int {
        val value = data[key]
        return if (value is Int) value else defValue
    }

    override fun getLong(key: String, defValue: Long): Long {
        val value = data[key]
        return if (value is Long) value else defValue
    }

    override fun getFloat(key: String, defValue: Float): Float {
        val value = data[key]
        return if (value is Float) value else defValue
    }

    override fun getBoolean(key: String, defValue: Boolean): Boolean {
        val value = data[key]
        return if (value is Boolean) value else defValue
    }

    override fun contains(key: String): Boolean = data.containsKey(key)

    override fun edit(): SharedPreferences.Editor = EditorImpl()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        listeners.remove(listener)
    }

    inner class EditorImpl : SharedPreferences.Editor {
        private val modified = HashMap<String, Any?>()
        private var clear = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
            synchronized(this) {
                if (value == null) modified[key] = null else modified[key] = value
            }
        }

        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = apply {
            synchronized(this) {
                if (values == null) modified[key] = null else modified[key] = HashSet(values)
            }
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply {
            synchronized(this) { modified[key] = value }
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply {
            synchronized(this) { modified[key] = value }
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply {
            synchronized(this) { modified[key] = value }
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply {
            synchronized(this) { modified[key] = value }
        }

        override fun remove(key: String): SharedPreferences.Editor = apply {
            synchronized(this) { modified[key] = this }
        }

        override fun clear(): SharedPreferences.Editor = apply {
            synchronized(this) { clear = true }
        }

        override fun commit(): Boolean {
            val notifyKeys = mutableListOf<String>()
            synchronized(lock) {
                if (clear) {
                    notifyKeys.addAll(data.keys)
                    data.clear()
                    clear = false
                }
                for ((k, v) in modified) {
                    if (v === this) {
                        data.remove(k)
                    } else if (v != null) {
                        data[k] = v
                    } else {
                        data.remove(k)
                    }
                    notifyKeys.add(k)
                }
                modified.clear()
                val success = writeToDisk()
                for (listener in listeners) {
                    for (k in notifyKeys) {
                        listener.onSharedPreferenceChanged(this@VirtualSharedPreferences, k)
                    }
                }
                return success
            }
        }

        override fun apply() {
            val notifyKeys = mutableListOf<String>()
            synchronized(lock) {
                if (clear) {
                    notifyKeys.addAll(data.keys)
                    data.clear()
                    clear = false
                }
                for ((k, v) in modified) {
                    if (v === this) {
                        data.remove(k)
                    } else if (v != null) {
                        data[k] = v
                    } else {
                        data.remove(k)
                    }
                    notifyKeys.add(k)
                }
                modified.clear()
            }
            writeExecutor.execute {
                writeToDisk()
            }
            for (listener in listeners) {
                for (k in notifyKeys) {
                    listener.onSharedPreferenceChanged(this@VirtualSharedPreferences, k)
                }
            }
        }
    }
}
