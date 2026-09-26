package com.easyaccess.app.privacy

import android.content.Context

object VisionPreferences {
    private const val PREFERENCES_NAME = "easyaccess_privacy"
    private const val KEY_ON_DEVICE_VISION_ENABLED = "on_device_vision_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ON_DEVICE_VISION_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ON_DEVICE_VISION_ENABLED, enabled)
            .apply()
    }
}
