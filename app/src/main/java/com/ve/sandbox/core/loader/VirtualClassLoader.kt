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
            GuestDexClassLoader(dexPath, optDir, libPath, parent)
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

/**
 * GuestDexClassLoader: Implements Guest-First (Child-First) class loading with
 * Android platform framework delegation.
 *
 * Mental Model:
 * Standard Java/Android ClassLoaders use Parent-First delegation. If the host sandbox app
 * bundles common libraries (kotlin-stdlib, androidx, etc.), the parent (host) ClassLoader
 * would intercept and return the host's version of the class.
 *
 * When an obfuscated guest app or game (e.g. Free Fire Max compiled with IL2CPP / Unity) calls
 * obfuscated or version-specific methods (such as Intrinsics.a(Object, Object)), the host's
 * library would throw NoSuchMethodError because it lacks the guest's specific method signatures.
 *
 * By searching the guest APK(s) FIRST for non-framework classes (kotlin.*, androidx.*, app code),
 * each guest application runs in its own pristine bytecode environment without library conflicts.
 */
open class GuestDexClassLoader(
    dexPath: String,
    optimizedDirectory: String?,
    librarySearchPath: String?,
    parent: ClassLoader
) : DexClassLoader(dexPath, optimizedDirectory, librarySearchPath, parent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        // 1. Return already loaded class in this ClassLoader
        var clazz = findLoadedClass(name)
        if (clazz != null) return clazz

        // 2. Android platform and Java runtime framework classes MUST come from system BootClassLoader
        if (isSystemFrameworkClass(name)) {
            return super.loadClass(name, resolve)
        }

        // 3. Host container classes must be loaded by the host ClassLoader
        if (name.startsWith("com.ve.sandbox.")) {
            return super.loadClass(name, resolve)
        }

        // 4. Guest-First: search the guest APK(s) first for all application and bundled library classes
        // (e.g. kotlin.*, androidx.*, com.dts.*, third-party SDKs)
        try {
            clazz = findClass(name)
            if (clazz != null) {
                return clazz
            }
        } catch (ignored: ClassNotFoundException) {
            // Not in guest APK, proceed to parent fallback
        } catch (ignored: NoClassDefFoundError) {
            // Fallback
        }

        // 5. Fallback to parent ClassLoader
        return super.loadClass(name, resolve)
    }

    private fun isSystemFrameworkClass(name: String): Boolean {
        // Legacy Android support library is bundled in APKs, not part of platform
        if (name.startsWith("android.support.")) return false

        return name.startsWith("android.") ||
                name.startsWith("java.") ||
                name.startsWith("javax.") ||
                name.startsWith("dalvik.") ||
                name.startsWith("sun.") ||
                name.startsWith("libcore.") ||
                name.startsWith("org.apache.http.") ||
                name.startsWith("org.json.") ||
                name.startsWith("org.w3c.dom.") ||
                name.startsWith("org.xml.") ||
                name.startsWith("org.xmlpull.") ||
                name.startsWith("com.android.internal.") ||
                name.startsWith("com.android.org.")
    }
}
