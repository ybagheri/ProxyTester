package com.example.proxytester.utils

import android.content.Context

/**
 * Tiny persisted-settings wrapper. Holds the channel-scan text field's raw
 * content (one or more channel usernames, comma/newline separated) —
 * default matches the Python script this app's channel-scan feature is
 * based on.
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("proxytester_settings", Context.MODE_PRIVATE)

    fun getChannel(): String = prefs.getString(KEY_CHANNEL, DEFAULT_CHANNEL) ?: DEFAULT_CHANNEL

    fun setChannel(value: String) {
        prefs.edit().putString(KEY_CHANNEL, value).apply()
    }

    companion object {
        const val DEFAULT_CHANNEL = "mtpro_xyz"
        private const val KEY_CHANNEL = "channel_username"
    }
}
