package com.ve.sandbox.core.loader

import android.os.Build
import android.util.Log
import dalvik.system.DexClassLoader
import java.io.File

/**
 * Manages dynamic runtime classloading of the guest APK's dex bytecode.
 *
 * Android Internals Mental Model:
 * 1. Android's Dalvik/ART executes compiled Dalvik bytecode (.dex files) packed inside APKs.
 * 2. Java standard ClassLoaders (URLClassLoader) look for .class files, which ART does NOT execute.
 * 3. Android provides DexClassLoader and PathClassLoader (descendants of BaseDexClassLoader).
 * 4. DexClassLoader reads dex bytecode from one or multiple APKs (joined with File.pathSeparator),
 *    extracts / JITs / translates them to native code in an optimized directory, and loads classes.
 * 5. Parent delegation:
 *    The parent class loader is set to the host application's ClassLoader.
 *    When a guest class references an Android framework class (e.g. android.app.Activity,
 *    android.os.Bundle), the parent loader resolves it from the system bootclasspath.
 *    When the guest app references its own classes (e.g. com.target.sampleapp.TargetMainActivity),
 *    the parent fails and DexClassLoader resolves it from the guest APK!
 */
class VirtualClassLoader(
    val apkPaths: List<String>,
    val nativeLibDir: String?,
    val cacheDir: File,
    parentClassLoader: ClassLoader
) {
    companion object {
        private const val TAG = "VeClassLoader"
    }

    private val dexPathString = apkPaths.joinToString(File.pathSeparator)
    private val optimizedDir = File(cacheDir, "dex_opt").apply { mkdirs() }

    init {
        // Android 14 (API level 34) Security Hardening:
        // Dynamically loaded DEX/APK files must be read-only.
        // Otherwise, ART throws: SecurityException: Writable dex file '...' is not allowed.
        for (path in apkPaths) {
            val f = File(path)
            if (f.exists()) {
                f.setReadOnly()
            }
        }
    }

    val classLoader: ClassLoader = createDexClassLoader(
        dexPathString,
        optimizedDir.absolutePath,
        buildLibrarySearchPath(nativeLibDir),
        parentClassLoader
    )

    private fun buildLibrarySearchPath(baseLibDir: String?): String? {
        if (baseLibDir == null) return null
        val dir = File(baseLibDir)
        if (!dir.exists()) return null

        val paths = mutableListOf<String>()
        val supportedAbis = Build.SUPPORTED_ABIS ?: emptyArray()
        for (abi in supportedAbis) {
            val abiDir = File(dir, abi)
            if (abiDir.exists() && abiDir.isDirectory) {
                paths.add(abiDir.absolutePath)
            }
        }
        dir.listFiles()?.forEach { sub ->
            if (sub.isDirectory && !paths.contains(sub.absolutePath)) {
                paths.add(sub.absolutePath)
            }
        }
        paths.add(dir.absolutePath)
        return paths.joinToString(File.pathSeparator)
    }

    private fun createDexClassLoader(
        dexPath: String,
        optDir: String,
        libPath: String?,
        parent: ClassLoader
    ): ClassLoader {
        return try {
            DexClassLoader(dexPath, optDir, libPath, parent)
        } catch (stubEx: RuntimeException) {
            // Check if running in desktop JVM (unit test environment without Android runtime)
            if (stubEx.message?.contains("Stub") == true) {
                Log.w(TAG, "Running under Android stub jar, falling back to parent classloader for testing")
                parent
            } else {
                throw stubEx
            }
        }
    }

    fun loadClass(className: String): Class<*> {
        return classLoader.loadClass(className)
    }

    fun findClassOrNull(className: String): Class<*>? {
        return try {
            classLoader.loadClass(className)
        } catch (e: ClassNotFoundException) {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> createInstance(className: String): T {
        val clazz = loadClass(className)
        val constructor = clazz.getDeclaredConstructor().apply { isAccessible = true }
        return constructor.newInstance() as T
    }
}
