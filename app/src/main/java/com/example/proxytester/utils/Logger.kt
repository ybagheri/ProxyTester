package com.example.proxytester.utils

import android.util.Log

object Logger {
    private const val TAG = "ProxyTester"

    fun d(msg: String) = Log.d(TAG, msg)
    fun e(msg: String, t: Throwable? = null) = Log.e(TAG, msg, t)
}
