package com.easyaccess.app.accessibility

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.abs

class SessionControlView(
    context: Context,
    taskTitle: String,
    private val onRepeat: () -> Unit,
    private val onAiAnalyze: () -> Unit,
    private val onPauseToggle: () -> Unit,
    private val onEnd: () -> Unit,
    private val onMove: (deltaX: Int, deltaY: Int) -> Unit,
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

        addView(dragHandle(taskTitle))
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

    private fun dragHandle(taskTitle: String): AppCompatTextView = object : AppCompatTextView(context) {
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var downRawX = 0f
        private var downRawY = 0f
        private var lastRawX = 0f
        private var lastRawY = 0f
        private var dragging = false

        init {
            text = "≡ 按住这里移动\nEasyAccess 正在帮助\n$taskTitle"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(6), 0, dp(6), dp(10))
            contentDescription = "按住并拖动悬浮控制面板"
            isClickable = true
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    dragging = false
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && (
                            abs(event.rawX - downRawX) >= touchSlop ||
                                abs(event.rawY - downRawY) >= touchSlop
                            )
                    ) {
                        dragging = true
                    }
                    if (dragging) {
                        onMove(
                            (event.rawX - lastRawX).toInt(),
                            (event.rawY - lastRawY).toInt(),
                        )
                    }
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (!dragging) performClick()
                    dragging = false
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
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
