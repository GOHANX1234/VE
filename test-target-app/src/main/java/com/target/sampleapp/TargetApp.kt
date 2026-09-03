package com.target.sampleapp

import android.app.Application
import android.util.Log

class TargetApp : Application() {
    companion object {
        const val TAG = "TargetApp"
        var isCreated = false
    }

    override fun onCreate() {
        super.onCreate()
        isCreated = true
        Log.d(TAG, "TargetApp onCreate called! PackageName = $packageName, filesDir = $filesDir")
    }
}
