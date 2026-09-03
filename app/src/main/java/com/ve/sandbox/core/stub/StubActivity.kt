package com.ve.sandbox.core.stub

import android.app.Activity
import android.os.Bundle

/**
 * Empty stub activity declared in the host AndroidManifest.xml.
 * Used for substituting target APK activities at runtime during Phase 3.
 */
open class StubActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }
}

class StubSingleTopActivity : StubActivity()
class StubSingleTaskActivity : StubActivity()
class StubSingleInstanceActivity : StubActivity()
