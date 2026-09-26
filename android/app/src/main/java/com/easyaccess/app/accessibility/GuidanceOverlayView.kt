package com.easyaccess.app.accessibility

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import kotlin.math.max

enum class OverlayTone {
    ACTION,
    SEARCHING,
    WARNING,
    SUCCESS,
}

class GuidanceOverlayView(
    context: Context,
    private val targetBounds: Rect?,
    private val message: String,
    private val tone: OverlayTone,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private val dimPaint = Paint().apply {
        color = if (tone == OverlayTone.SEARCHING) {
            Color.TRANSPARENT
        } else {
            Color.argb(112, 0, 0, 0)
        }
    }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val outerStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12 * density
        color = Color.rgb(0, 82, 204)
    }
    private val innerStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6 * density
        color = Color.rgb(255, 224, 0)
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = when (tone) {
            OverlayTone.ACTION -> Color.rgb(0, 82, 164)
            OverlayTone.SEARCHING -> Color.rgb(0, 111, 214)
            OverlayTone.WARNING -> Color.rgb(184, 91, 0)
            OverlayTone.SUCCESS -> Color.rgb(15, 112, 69)
        }
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22 * scaledDensity
        isFakeBoldText = true
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        contentDescription = "EasyAccess 提示：$message"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

        val padding = 10 * density
        val overlayLocation = IntArray(2)
        getLocationOnScreen(overlayLocation)
        val overlayLeft = overlayLocation[0]
        val overlayTop = overlayLocation[1]
        val target = targetBounds?.let {
            RectF(
                max(4 * density, it.left - overlayLeft - padding),
                max(4 * density, it.top - overlayTop - padding),
                (it.right - overlayLeft + padding).coerceAtMost(width - 4 * density),
                (it.bottom - overlayTop + padding).coerceAtMost(height - 4 * density),
            )
        }
        target?.let {
            canvas.drawRoundRect(it, 18 * density, 18 * density, clearPaint)
            canvas.drawRoundRect(it, 18 * density, 18 * density, outerStroke)
            canvas.drawRoundRect(it, 18 * density, 18 * density, innerStroke)
        }

        val horizontalMargin = 18 * density
        val textPadding = 16 * density
        val textWidth = (width - 2 * horizontalMargin - 2 * textPadding).toInt()
            .coerceAtLeast(1)
        val textLayout = StaticLayout.Builder
            .obtain(message, 0, message.length, textPaint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(4 * density, 1f)
            .build()
        val bubbleHeight = textLayout.height + 2 * textPadding
        val preferredTop = when {
            target == null -> height * 0.32f
            target.top > bubbleHeight + 48 * density -> target.top - bubbleHeight - 24 * density
            else -> target.bottom + 24 * density
        }
        val bubbleTop = preferredTop.coerceIn(
            24 * density,
            height - bubbleHeight - 24 * density,
        )
        val bubble = RectF(
            horizontalMargin,
            bubbleTop,
            width - horizontalMargin,
            bubbleTop + bubbleHeight,
        )
        canvas.drawRoundRect(bubble, 20 * density, 20 * density, bubblePaint)
        canvas.save()
        canvas.translate(bubble.left + textPadding, bubble.top + textPadding)
        textLayout.draw(canvas)
        canvas.restore()
    }
}
