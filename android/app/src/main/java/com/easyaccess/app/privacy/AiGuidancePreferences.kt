package com.easyaccess.app.privacy

import android.content.Context

object AiGuidancePreferences {
    private const val PREFERENCES_NAME = "easyaccess_privacy"
    private const val KEY_AI_GUIDANCE_ENABLED = "ai_guidance_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AI_GUIDANCE_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AI_GUIDANCE_ENABLED, enabled)
            .apply()
    }
}
