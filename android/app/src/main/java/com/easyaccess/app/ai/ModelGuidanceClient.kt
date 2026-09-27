package com.easyaccess.app.ai

import android.graphics.Bitmap
import android.graphics.Rect
import com.easyaccess.app.BuildConfig
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.math.roundToInt

data class ModelGuidanceResult(
    val instruction: String,
    val targetBounds: Rect?,
    val targetText: String,
    val actionType: String,
    val taskStatus: String,
    val riskLevel: String,
    val confirmationRequired: Boolean,
)

class ModelGuidanceClient(
    private val baseUrl: String = BuildConfig.EASYACCESS_API_BASE_URL,
    private val apiToken: String = BuildConfig.EASYACCESS_API_TOKEN,
) {
    fun analyze(
        bitmap: Bitmap,
        taskText: String,
        mode: String = "senior",
    ): ModelGuidanceResult {
        val sessionId = createSession(taskText, mode)
        val uploadBitmap = resizeForUpload(bitmap)
        val imageBytes = try {
            ByteArrayOutputStream().use { output ->
                check(uploadBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    "无法压缩当前屏幕"
                }
                output.toByteArray()
            }
        } finally {
            if (uploadBitmap !== bitmap) uploadBitmap.recycle()
        }
        check(imageBytes.size <= MAX_UPLOAD_BYTES) { "当前屏幕图片过大" }
        val response = uploadScreenshot(sessionId, imageBytes)
        return parseResult(response, bitmap.width, bitmap.height)
    }

    private fun resizeForUpload(bitmap: Bitmap): Bitmap {
        val longestSide = maxOf(bitmap.width, bitmap.height)
        if (longestSide <= MAX_UPLOAD_DIMENSION) return bitmap
        val scale = MAX_UPLOAD_DIMENSION.toFloat() / longestSide
        val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun createSession(taskText: String, mode: String): String {
        val body = JSONObject()
            .put("task_text", taskText)
            .put("mode", mode)
            .toString()
        val response = requestJson("/sessions", "POST", body)
        return response.getString("session_id")
    }

    private fun uploadScreenshot(sessionId: String, imageBytes: ByteArray): JSONObject {
        val boundary = "EasyAccess-${UUID.randomUUID()}"
        val connection = openConnection("/sessions/$sessionId/analyze", "POST").apply {
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            doOutput = true
            setChunkedStreamingMode(64 * 1024)
        }
        DataOutputStream(connection.outputStream).use { output ->
            output.writeUtf8("--$boundary\r\n")
            output.writeUtf8("Content-Disposition: form-data; name=\"image\"; filename=\"screen.jpg\"\r\n")
            output.writeUtf8("Content-Type: image/jpeg\r\n\r\n")
            output.write(imageBytes)
            output.writeUtf8("\r\n--$boundary--\r\n")
        }
        return readJsonResponse(connection)
    }

    private fun requestJson(path: String, method: String, body: String): JSONObject {
        val connection = openConnection(path, method).apply {
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            doOutput = true
        }
        connection.outputStream.use { output ->
            output.write(body.toByteArray(StandardCharsets.UTF_8))
        }
        return readJsonResponse(connection)
    }

    private fun openConnection(path: String, method: String): HttpURLConnection =
        (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            if (apiToken.isNotBlank()) {
                setRequestProperty("X-EasyAccess-Key", apiToken)
            }
        }

    private fun readJsonResponse(connection: HttpURLConnection): JSONObject {
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching {
                    JSONObject(text).optJSONObject("detail")?.optString("message")
                }.getOrNull().orEmpty()
                error(message.ifBlank { "AI 服务返回错误（$status）" })
            }
            JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseResult(response: JSONObject, width: Int, height: Int): ModelGuidanceResult {
        val action = response.getJSONObject("recommended_action")
        val actionType = action.getString("action_type")
        val targetId = action.optString("element_id").takeIf { it.isNotBlank() && it != "null" }
        val elements = response.optJSONArray("elements")
        var targetBounds: Rect? = null
        var targetText = ""
        if (targetId != null && elements != null) {
            for (index in 0 until elements.length()) {
                val element = elements.getJSONObject(index)
                if (element.optString("id") != targetId) continue
                targetText = element.optString("text")
                val box = element.optJSONArray("bbox") ?: break
                if (box.length() != 4) break
                val left = (box.getDouble(0).coerceIn(0.0, 1.0) * width).toInt()
                val top = (box.getDouble(1).coerceIn(0.0, 1.0) * height).toInt()
                val right = (box.getDouble(2).coerceIn(0.0, 1.0) * width).toInt()
                val bottom = (box.getDouble(3).coerceIn(0.0, 1.0) * height).toInt()
                if (right > left && bottom > top) targetBounds = Rect(left, top, right, bottom)
                break
            }
        }
        val risk = response.optJSONObject("risk") ?: JSONObject()
        return ModelGuidanceResult(
            instruction = action.optString("instruction", "请根据屏幕提示继续操作"),
            targetBounds = targetBounds,
            targetText = targetText,
            actionType = actionType,
            taskStatus = response.optString("task_status", "uncertain"),
            riskLevel = risk.optString("level", "low"),
            confirmationRequired = risk.optBoolean("confirmation_required", false),
        )
    }

    private fun DataOutputStream.writeUtf8(value: String) {
        write(value.toByteArray(StandardCharsets.UTF_8))
    }

    companion object {
        // During development, `adb reverse tcp:8000 tcp:8000` maps this address
        // to the backend running on the developer computer.
        const val DEFAULT_BASE_URL = "http://127.0.0.1:8000/api"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 75_000
        private const val JPEG_QUALITY = 74
        private const val MAX_UPLOAD_DIMENSION = 1280
        private const val MAX_UPLOAD_BYTES = 8 * 1024 * 1024
    }
}
