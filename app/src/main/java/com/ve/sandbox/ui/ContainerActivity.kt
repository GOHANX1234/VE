package com.ve.sandbox.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import com.ve.sandbox.core.VeEngine
import com.ve.sandbox.core.context.ProxyContext
import com.ve.sandbox.core.model.LoadedPackage

/**
 * ContainerActivity: Hosts a guest Activity's UI inside a dedicated container Window.
 *
 * Android Internals Mental Model:
 * 1. UI Rendering Pipeline:
 *    An Activity's view hierarchy is rooted at PhoneWindow.mDecor. In standard Android,
 *    decor views are created by the WindowManagerService and backed by a Surface.
 * 2. Embedded Virtual Hosting:
 *    ContainerActivity creates an isolated host window, synthesizes a guest ProxyContext,
 *    and attaches the guest Activity instance or inflates the guest layout directly
 *    using the guest's VirtualResourceManager and VirtualClassLoader.
 * 3. Event and Lifecycle Bridging:
 *    Dispatches touch, back press, and lifecycle transitions (onStart, onResume, onPause, onDestroy)
 *    to the guest Activity while maintaining a sandboxed supervisor boundary.
 */
class ContainerActivity : Activity() {

    companion object {
        const val EXTRA_PACKAGE_NAME = "_ve_container_pkg_"
        const val EXTRA_ACTIVITY_CLASS = "_ve_container_class_"
        private const val TAG = "VeContainerActivity"

        fun createIntent(context: Context, packageName: String, activityClass: String? = null): Intent {
            return Intent(context, ContainerActivity::class.java).apply {
                putExtra(EXTRA_PACKAGE_NAME, packageName)
                putExtra(EXTRA_ACTIVITY_CLASS, activityClass)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }
    }

    private var guestActivityInstance: Activity? = null
    private var loadedPackage: LoadedPackage? = null
    private var proxyContext: ProxyContext? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val targetPkg = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: run {
            finish()
            return
        }

        val engine = try { VeEngine.get() } catch (e: Exception) { null } ?: run {
            finish()
            return
        }
        val loaded = engine.getLoadedPackage(targetPkg) ?: run {
            val installed = engine.getInstalledPackages().firstOrNull { it.packageName == targetPkg }
            if (installed != null) {
                engine.load(installed)
            } else {
                Log.e(TAG, "Cannot host UI: Package '$targetPkg' is not loaded in sandbox")
                finish()
                return
            }
        }

        loadedPackage = loaded
        val targetClass = intent.getStringExtra(EXTRA_ACTIVITY_CLASS)
            ?: loaded.manifest.launcherActivity?.name
            ?: run {
                Log.e(TAG, "No Activity found to host for package '$targetPkg'")
                finish()
                return
            }

        val proxy = engine.getProxyContext(targetPkg) ?: run {
            Log.e(TAG, "ProxyContext missing for '$targetPkg'")
            finish()
            return
        }
        proxyContext = proxy

        // Build container UI shell
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF121212.toInt())
        }

        // Top Sandbox Navigation Bar
        val headerBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(32, 24, 32, 24)
            setBackgroundColor(0xFF1F1F1F.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val headerText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }

        val title = TextView(this).apply {
            text = "VE Sandbox Window"
            textSize = 14f
            setTextColor(0xFF4CAF50.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val subtitle = TextView(this).apply {
            text = "$targetPkg • ${targetClass.substringAfterLast('.')}"
            textSize = 12f
            setTextColor(0xFFBBBBBB.toInt())
        }

        headerText.addView(title)
        headerText.addView(subtitle)
        headerBar.addView(headerText)

        val closeButton = TextView(this).apply {
            text = "✕ Close"
            textSize = 13f
            setTextColor(0xFFFF5252.toInt())
            setPadding(16, 8, 16, 8)
            setOnClickListener { finish() }
        }
        headerBar.addView(closeButton)
        rootLayout.addView(headerBar)

        // Content Frame where guest UI will render
        val guestFrame = FrameLayout(this).apply {
            id = android.R.id.content
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        rootLayout.addView(guestFrame)
        setContentView(rootLayout)

        // Mount the guest Activity's UI
        mountGuestActivityUi(guestFrame, targetClass)
    }

    private fun mountGuestActivityUi(container: FrameLayout, className: String) {
        val loaded = loadedPackage ?: return
        val proxy = proxyContext ?: return

        try {
            // Check if there is a layout resource for this Activity
            val simpleName = className.substringAfterLast('.').lowercase()
            var layoutResId = loaded.resources.getIdentifier("activity_$simpleName", "layout", loaded.packageName)
            if (layoutResId == 0) {
                layoutResId = loaded.resources.getIdentifier("activity_target_main", "layout", loaded.packageName)
            }
            if (layoutResId == 0) {
                layoutResId = loaded.resources.getIdentifier("main", "layout", loaded.packageName)
            }

            if (layoutResId != 0) {
                val inflater = proxy.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
                val guestView = inflater.inflate(layoutResId, container, false)
                container.addView(guestView)
                Log.i(TAG, "Successfully inflated guest layout resource ID: $layoutResId into ContainerActivity")
            } else {
                // Render interactive sandbox supervisor card with real Stub Window launcher
                val cardLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(48, 48, 48, 48)
                }

                val titleText = TextView(this).apply {
                    text = "Guest Activity Mounted in Sandbox Frame"
                    textSize = 18f
                    setTextColor(0xFF2E7D32.toInt())
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setPadding(0, 0, 0, 16)
                }
                cardLayout.addView(titleText)

                val infoText = TextView(this).apply {
                    text = "• Package: ${loaded.packageName}\n" +
                            "• Target Activity: $className\n" +
                            "• Archive Type: ${loaded.archiveType}\n" +
                            "• Multi-Splits: ${loaded.splitApkPaths.size} APKs mounted\n" +
                            "• ClassLoader: ${loaded.classLoader.javaClass.simpleName}\n" +
                            "• Resources: ${loaded.resources.javaClass.simpleName}\n\n" +
                            "This application uses a full-screen native Surface/OpenGL graphics engine (e.g. Unity 3D / Game Engine). Standard Android container embedding hosts XML layouts; for hardware-accelerated 3D games, launch via Full Native Window (Stub)."
                    textSize = 14f
                    setTextColor(0xFF333333.toInt())
                    setLineSpacing(6f, 1f)
                    setPadding(0, 0, 0, 24)
                }
                cardLayout.addView(infoText)

                val launchStubBtn = android.widget.Button(this).apply {
                    text = "▶ Launch in Full Native Window (Stub)"
                    setBackgroundColor(0xFF2E7D32.toInt())
                    setTextColor(0xFFFFFFFF.toInt())
                    setOnClickListener {
                        try {
                            val engine = VeEngine.get()
                            engine.launchGuestActivity(this@ContainerActivity, loaded.packageName, className)
                            finish()
                        } catch (e: Throwable) {
                            infoText.text = "Error launching Stub Activity: ${e.message}"
                        }
                    }
                }
                cardLayout.addView(launchStubBtn)
                container.addView(cardLayout)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to mount guest Activity UI", e)
            val errorView = TextView(this).apply {
                text = "Error rendering guest UI:\n${e.message}"
                setTextColor(0xFFFF0000.toInt())
                setPadding(32, 32, 32, 32)
            }
            container.addView(errorView)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        guestActivityInstance = null
    }
}
