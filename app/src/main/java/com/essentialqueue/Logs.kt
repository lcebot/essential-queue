package com.essentialqueue

import android.util.Log

/**
 * All logging goes through here so a single compile-time flag controls it.
 * The functions are inline and take the message as a lambda, so with BuildConfig.ENABLE_LOGS false
 * the message is never built, and R8 drops the dead branch and its strings from the release APK.
 */
object Logs {
    const val TAG = "EssentialQueue"

    inline fun i(message: () -> String) {
        if (BuildConfig.ENABLE_LOGS) Log.i(TAG, message())
    }

    inline fun d(message: () -> String) {
        if (BuildConfig.ENABLE_LOGS) Log.d(TAG, message())
    }
}
