package com.easyaccess.app.model

import android.graphics.Rect

/**
 * A privacy-minimized snapshot of an accessibility node.
 * Password values and editable text values are intentionally not collected.
 */
data class UiNodeSnapshot(
    val text: String,
    val contentDescription: String,
    val className: String,
    val viewId: String,
    val clickable: Boolean,
    val enabled: Boolean,
    val editable: Boolean,
    val password: Boolean,
    val bounds: Rect,
    val depth: Int,
) {
    val safeLabel: String
        get() = when {
            password -> "[密码控件]"
            text.isNotBlank() -> text
            contentDescription.isNotBlank() -> contentDescription
            else -> ""
        }
}

data class UiObservation(
    val packageName: String,
    val appName: String,
    val capturedAtMillis: Long,
    val nodes: List<UiNodeSnapshot>,
)
