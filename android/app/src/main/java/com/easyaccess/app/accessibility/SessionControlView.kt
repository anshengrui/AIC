package com.easyaccess.app.accessibility

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class SessionControlView(
    context: Context,
    taskTitle: String,
    private val onRepeat: () -> Unit,
    private val onAiAnalyze: () -> Unit,
    private val onPauseToggle: () -> Unit,
    private val onEnd: () -> Unit,
) : LinearLayout(context) {
    private val pauseButton: Button

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(12), dp(10), dp(12))
        background = GradientDrawable().apply {
            setColor(Color.rgb(13, 52, 89))
            cornerRadius = dp(18).toFloat()
            setStroke(dp(2), Color.WHITE)
        }
        elevation = dp(8).toFloat()

        addView(TextView(context).apply {
            text = "正在帮助\n$taskTitle"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), 0, dp(4), dp(8))
        })
        addView(controlButton("重复") { onRepeat() })
        addView(controlButton("AI识别") { onAiAnalyze() }.apply {
            setTextColor(Color.rgb(0, 82, 164))
        })
        pauseButton = controlButton("暂停") { onPauseToggle() }
        addView(pauseButton)
        addView(controlButton("结束") { onEnd() }.apply {
            setTextColor(Color.rgb(132, 26, 34))
        })
    }

    fun setPaused(paused: Boolean) {
        pauseButton.text = if (paused) "继续" else "暂停"
        alpha = if (paused) 0.82f else 1f
    }

    private fun controlButton(label: String, action: () -> Unit) = Button(context).apply {
        text = label
        textSize = 16f
        isAllCaps = false
        minHeight = dp(46)
        minWidth = dp(92)
        setOnClickListener { action() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
