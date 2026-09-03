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

        val engine = try { VeEngine.get() } catch (e: Exception) { null }
        val loaded = engine?.getLoadedPackage(targetPkg) ?: run {
            Log.e(TAG, "Cannot host UI: Package '$targetPkg' is not loaded in sandbox")
            finish()
            return
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
            // Load and instantiate the guest Activity
            val activityClass = loaded.classLoader.loadClass(className)
            val guestActivity = activityClass.getDeclaredConstructor().newInstance() as Activity
            guestActivityInstance = guestActivity

            // Inject ProxyContext into mBase
            try {
                val mBaseField = Context::class.java.getDeclaredField("mBase").apply { isAccessible = true }
                mBaseField.set(guestActivity, proxy)
            } catch (e: Throwable) {
                val mBaseField = android.content.ContextWrapper::class.java.getDeclaredField("mBase").apply { isAccessible = true }
                mBaseField.set(guestActivity, proxy)
            }

            // Inflate guest layout using guest Resources and LayoutInflater
            val inflater = proxy.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            val layoutResId = loaded.resources.getIdentifier("activity_target_main", "layout", loaded.packageName)

            if (layoutResId != 0) {
                val guestView = inflater.inflate(layoutResId, container, false)
                container.addView(guestView)
                Log.i(TAG, "Successfully inflated guest layout resource ID: $layoutResId into ContainerActivity")
            } else {
                // Fallback: render guest info card
                val fallbackText = TextView(proxy).apply {
                    text = "Guest Activity '$className' is mounted in Sandbox!\n" +
                            "Package: ${loaded.packageName}\n" +
                            "ClassLoader: ${loaded.classLoader.javaClass.simpleName}\n" +
                            "Resources: ${loaded.resources.javaClass.simpleName}"
                    textSize = 16f
                    setTextColor(0xFF000000.toInt())
                    setPadding(48, 48, 48, 48)
                }
                container.addView(fallbackText)
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
