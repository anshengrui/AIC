package com.easyaccess.app.guidance

import android.content.Context

object GuidancePreferences {
    private const val PREFERENCES_NAME = "easyaccess_guidance"
    private const val KEY_SELECTED_TASK = "selected_task"
    private const val KEY_SESSION_ACTIVE = "session_active"
    private const val KEY_SESSION_PAUSED = "session_paused"
    private const val KEY_TRANSIT_MODE = "transit_mode"
    private const val KEY_CUSTOM_GOAL = "custom_goal"

    fun selectedTask(context: Context): GuidanceTask? {
        val id = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_TASK, null)
        return GuidanceTask.fromId(id)
    }

    fun transitMode(context: Context): TransitMode? {
        val id = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TRANSIT_MODE, null)
        return TransitMode.fromId(id)
    }

    fun setTransitMode(context: Context, mode: TransitMode) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TRANSIT_MODE, mode.id)
            .apply()
    }

    fun customGoal(context: Context): String =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_GOAL, "")
            .orEmpty()

    fun modelGoal(context: Context, task: GuidanceTask): String {
        val customGoal = customGoal(context).trim()
        val userGoal = if (task.isGeneric && customGoal.isNotBlank()) {
            customGoal.take(200)
        } else {
            task.title
        }
        return "${task.modelGoal}\n用户当前目标：$userGoal"
    }

    fun displayTitle(context: Context, task: GuidanceTask): String {
        val customGoal = customGoal(context).trim()
        return if (task.isGeneric && customGoal.isNotBlank()) {
            "${task.title.substringBefore('：')}：${customGoal.take(30)}"
        } else {
            task.title
        }
    }

    fun startSession(context: Context, task: GuidanceTask, customGoal: String? = null) {
        val editor = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED_TASK, task.id)
            .putBoolean(KEY_SESSION_ACTIVE, true)
            .putBoolean(KEY_SESSION_PAUSED, false)
        if (task.isGeneric && !customGoal.isNullOrBlank()) {
            editor.putString(KEY_CUSTOM_GOAL, customGoal.trim().take(200))
        } else {
            editor.remove(KEY_CUSTOM_GOAL)
        }
        editor.apply()
    }

    fun isSessionActive(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SESSION_ACTIVE, false)

    fun isPaused(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SESSION_PAUSED, false)

    fun setPaused(context: Context, paused: Boolean) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SESSION_PAUSED, paused)
            .apply()
    }

    fun endSession(context: Context) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SESSION_ACTIVE, false)
            .putBoolean(KEY_SESSION_PAUSED, false)
            .apply()
    }
}
