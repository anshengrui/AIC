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
        setPadding(dp(12), dp(14), dp(12), dp(14))
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(12, 48, 82), Color.rgb(20, 73, 122)),
        ).apply {
            cornerRadius = dp(20).toFloat()
            setStroke(dp(1), Color.rgb(181, 210, 246))
        }
        elevation = dp(10).toFloat()

        addView(TextView(context).apply {
            text = "EasyAccess 正在帮助\n$taskTitle"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(6), 0, dp(6), dp(10))
        })
        addView(controlButton("重复提示") { onRepeat() })
        addView(controlButton("AI 找下一步") { onAiAnalyze() }.apply {
            setTextColor(Color.rgb(0, 82, 164))
        })
        pauseButton = controlButton("暂停帮助") { onPauseToggle() }
        addView(pauseButton)
        addView(controlButton("结束帮助") { onEnd() }.apply {
            setTextColor(Color.rgb(132, 26, 34))
        })
    }

    fun setPaused(paused: Boolean) {
        pauseButton.text = if (paused) "继续帮助" else "暂停帮助"
        alpha = if (paused) 0.82f else 1f
    }

    private fun controlButton(label: String, action: () -> Unit) = Button(context).apply {
        text = label
        textSize = 16f
        isAllCaps = false
        minHeight = dp(50)
        minWidth = dp(116)
        setTextColor(Color.rgb(20, 48, 78))
        setTypeface(typeface, Typeface.BOLD)
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(13).toFloat()
        }
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(7) }
        setOnClickListener { action() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
