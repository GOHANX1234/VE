package com.target.sampleapp

import android.app.Activity
import android.os.Bundle
import android.util.Log

class TargetMainActivity : Activity() {
    companion object {
        const val TAG = "TargetMainActivity"
        var lastCreatedInstance: TargetMainActivity? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lastCreatedInstance = this
        Log.d(TAG, "TargetMainActivity onCreate invoked successfully! Package: $packageName")
    }
}
