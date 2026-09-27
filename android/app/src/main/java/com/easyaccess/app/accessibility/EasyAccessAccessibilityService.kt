package com.easyaccess.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.easyaccess.app.ai.ModelGuidanceClient
import com.easyaccess.app.ai.ModelGuidanceResult
import com.easyaccess.app.model.UiNodeSnapshot
import com.easyaccess.app.model.UiObservation
import com.easyaccess.app.guidance.GuidanceEngine
import com.easyaccess.app.guidance.GuidancePreferences
import com.easyaccess.app.guidance.GuidanceTask
import com.easyaccess.app.privacy.AiGuidancePreferences
import com.easyaccess.app.privacy.VisionPreferences
import com.easyaccess.app.state.UiObservationStore
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class EasyAccessAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingCapture: Runnable? = null
    private var pendingPackageName = ""
    private var overlayView: View? = null
    private var lastOverlayKey = ""
    private var sessionControlView: SessionControlView? = null
    private var sessionControlTaskTitle = ""
    private var sessionControlX: Int? = null
    private var sessionControlY: Int? = null
    private var captureGeneration = 0
    private var lastEventPackageName = ""
    private var lastEventNodes: List<UiNodeSnapshot> = emptyList()
    private var textRecognizer: TextRecognizer? = null
    private var visualCaptureInFlight = false
    private var modelGuidanceInFlight = false
    private var modelSearchFeedbackActive = false
    private var modelSearchLongWait: Runnable? = null
    private var pendingModelUncertainRetry: Runnable? = null
    private var modelUncertainAutoRetryCount = 0
    private var lastModelRequestAtMillis = 0L
    private var lastModelPageHash: Long? = null
    private val modelGuidanceClient = ModelGuidanceClient()
    private val modelExecutor = Executors.newSingleThreadExecutor()
    private var textToSpeech: TextToSpeech? = null
    private var textToSpeechReady = false
    private var lastSpokenMessage = ""
    private var taobaoCustomerEntryOffered = false
    private var taobaoCustomerEntryOfferedAtMillis = 0L
    private var taobaoCustomerTargetBounds: Rect? = null
    private var alipayTransitEntryOffered = false
    private var alipayTransitEntryOfferedAtMillis = 0L
    private var wechatSearchEntryOffered = false
    private var wechatSearchEntryOfferedAtMillis = 0L
    private var wechatSearchTargetBounds: Rect? = null
    private val screenshotExecutor = Executor { runnable -> mainHandler.post(runnable) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstanceReference = WeakReference(this)
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeechReady = true
                textToSpeech?.language = Locale.SIMPLIFIED_CHINESE
                textToSpeech?.setSpeechRate(0.85f)
            }
        }
        refreshSessionControls()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString().orEmpty()
        if (packageName !in TargetApps.names) return
        val task = GuidancePreferences.selectedTask(this)
        if (!GuidancePreferences.isSessionActive(this) || task == null) {
            removeSessionControls()
            return
        }
        showSessionControls(task.controlTitle)
        if (GuidancePreferences.isPaused(this) || task.packageName != packageName) return
        val navigationEvent = isNavigationEvent(event)
        if (navigationEvent) resetModelPageState()
        // Keep the screenshot generation stable while the model is reading it.
        // Dynamic pages emit frequent content-change events even when the user has
        // not navigated; allowing those events to advance captureGeneration used to
        // discard a valid model result and replace it with the local "not found" hint.
        if (visualCaptureInFlight || modelGuidanceInFlight) {
            if (navigationEvent) captureGeneration++
            return
        }
        if (task == GuidanceTask.TAOBAO_CUSTOMER_SERVICE &&
            taobaoCustomerEntryOffered &&
            event?.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
        ) {
            val sourceBounds = Rect()
            event.source?.getBoundsInScreen(sourceBounds)
            val targetBounds = taobaoCustomerTargetBounds
            val expandedTarget = targetBounds?.let { Rect(it) }?.apply {
                val margin = (32 * resources.displayMetrics.density).toInt()
                inset(-margin, -margin)
            }
            val clickedLabel = buildString {
                append(event.text.joinToString(" "))
                append(' ')
                append(event.contentDescription?.toString().orEmpty())
                append(' ')
                append(event.source?.text?.toString().orEmpty())
                append(' ')
                append(event.source?.contentDescription?.toString().orEmpty())
            }
            val clickedExpectedEntry =
                listOf("官方客服", "平台客服", "客服小蜜").any(clickedLabel::contains) ||
                    (!sourceBounds.isEmpty &&
                        expandedTarget != null &&
                        Rect.intersects(sourceBounds, expandedTarget))
            if (clickedExpectedEntry &&
                System.currentTimeMillis() - taobaoCustomerEntryOfferedAtMillis >= 250L
            ) {
                mainHandler.postDelayed(::finishTaobaoCustomerService, 500L)
                return
            }
        }
        if (task == GuidanceTask.ALIPAY_TRANSIT &&
            alipayTransitEntryOffered &&
            event?.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
        ) {
            val clickedLabel = buildString {
                append(event.text.joinToString(" "))
                append(' ')
                append(event.contentDescription?.toString().orEmpty())
                append(' ')
                append(event.source?.text?.toString().orEmpty())
                append(' ')
                append(event.source?.contentDescription?.toString().orEmpty())
            }
            if (clickedLabel.contains("出行")) {
                mainHandler.postDelayed(::finishAlipayTransitSelection, 350L)
                return
            }
        }
        if (task == GuidanceTask.WECHAT_CONTACT &&
            wechatSearchEntryOffered &&
            event?.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
        ) {
            val sourceBounds = Rect()
            event.source?.getBoundsInScreen(sourceBounds)
            val targetBounds = wechatSearchTargetBounds
            val expandedTarget = targetBounds?.let { Rect(it) }?.apply {
                val margin = (32 * resources.displayMetrics.density).toInt()
                inset(-margin, -margin)
            }
            val clickedLabel = buildString {
                append(event.text.joinToString(" "))
                append(' ')
                append(event.contentDescription?.toString().orEmpty())
                append(' ')
                append(event.source?.text?.toString().orEmpty())
                append(' ')
                append(event.source?.contentDescription?.toString().orEmpty())
            }
            val clickedExpectedSearch = clickedLabel.contains("搜索") ||
                (!sourceBounds.isEmpty &&
                    expandedTarget != null &&
                    Rect.intersects(sourceBounds, expandedTarget))
            if (clickedExpectedSearch &&
                System.currentTimeMillis() - wechatSearchEntryOfferedAtMillis >= 250L
            ) {
                mainHandler.postDelayed(::finishWechatContactSearch, 250L)
                return
            }
        }

        // Some apps expose useful nodes only on the event source and briefly
        // return an empty active-window root while their page is settling.
        val eventNodes = ArrayList<UiNodeSnapshot>(40)
        event?.source?.let { source ->
            runCatching {
                collectNodes(source, depth = 0, output = eventNodes, seen = HashSet())
            }
        }
        if (eventNodes.isNotEmpty()) {
            lastEventPackageName = packageName
            lastEventNodes = eventNodes
        }

        // Several events are emitted while a page is being laid out. Debounce them
        // so the observation represents the settled page and avoids model spam.
        pendingPackageName = packageName
        captureGeneration++
        pendingCapture?.let(mainHandler::removeCallbacks)
        pendingCapture = Runnable {
            pendingCapture = null
            captureCurrentWindow(
                pendingPackageName,
                captureGeneration,
                attempt = 0,
            )
        }.also {
            mainHandler.postDelayed(it, CAPTURE_THROTTLE_MS)
        }
    }

    override fun onInterrupt() {
        removeOverlay()
    }

    override fun onDestroy() {
        captureGeneration++
        mainHandler.removeCallbacksAndMessages(null)
        removeOverlay()
        removeSessionControls()
        if (activeInstanceReference?.get() === this) activeInstanceReference = null
        textRecognizer?.close()
        textRecognizer = null
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        modelExecutor.shutdownNow()
        UiObservationStore.clear()
        super.onDestroy()
    }

    private fun captureCurrentWindow(
        fallbackPackageName: String,
        generation: Int,
        attempt: Int,
    ) {
        if (generation != captureGeneration) return
        val nodes = ArrayList<UiNodeSnapshot>(80)
        val seen = HashSet<String>()
        val roots = buildList {
            rootInActiveWindow?.let(::add)
            windows.mapNotNullTo(this) { it.root }
        }

        roots.forEach { root ->
            if (root.packageName?.toString() == fallbackPackageName) {
                runCatching { root.refresh() }
                collectNodes(root, depth = 0, output = nodes, seen = seen)
            }
        }

        if (nodes.isEmpty() && lastEventPackageName == fallbackPackageName) {
            nodes += lastEventNodes
        }

        if (nodes.isEmpty() && attempt < EMPTY_RETRY_DELAYS_MS.size) {
            mainHandler.postDelayed(
                {
                    captureCurrentWindow(
                        fallbackPackageName,
                        generation,
                        attempt + 1,
                    )
                },
                EMPTY_RETRY_DELAYS_MS[attempt],
            )
            return
        }

        val observation = UiObservation(
            packageName = fallbackPackageName,
            appName = TargetApps.displayName(fallbackPackageName),
            capturedAtMillis = System.currentTimeMillis(),
            nodes = nodes,
        )
        UiObservationStore.publish(observation)
        val task = GuidancePreferences.selectedTask(this)
        if (task == GuidanceTask.ALIPAY_TRANSIT &&
            alipayTransitEntryOffered &&
            System.currentTimeMillis() - alipayTransitEntryOfferedAtMillis >= 500L &&
            nodes.none { it.safeLabel.contains("出行") }
        ) {
            finishAlipayTransitSelection()
            return
        }
        GuidanceEngine.terminalOutcome(
            task,
            observation,
        )?.let { outcome ->
            val tone = if (outcome.successful) OverlayTone.SUCCESS else OverlayTone.WARNING
            showMessageOverlay(outcome.message, tone, END_OVERLAY_DURATION_MS)
            speakGuidance(outcome.message)
            GuidancePreferences.endSession(this)
            removeSessionControls()
            return
        }
        if (!highlightSuggestedNode(observation)) {
            if (VisionPreferences.isEnabled(this)) {
                if (visualCaptureInFlight) return
                if (requestVisualFallback(fallbackPackageName, generation)) return
                showUnavailableOverlay(com.easyaccess.app.R.string.vision_failed_overlay)
            } else {
                showUnavailableOverlay(com.easyaccess.app.R.string.enable_vision_overlay)
            }
        }
    }

    private fun requestVisualFallback(packageName: String, generation: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || visualCaptureInFlight) return false
        visualCaptureInFlight = true
        removeOverlay()
        lastOverlayKey = ""
        sessionControlView?.visibility = View.INVISIBLE
        mainHandler.postDelayed(
            {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    screenshotExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: ScreenshotResult) {
                            sessionControlView?.visibility = View.VISIBLE
                            val buffer = screenshotResult.hardwareBuffer
                            val bitmap = try {
                                Bitmap.wrapHardwareBuffer(buffer, screenshotResult.colorSpace)
                                    ?.copy(Bitmap.Config.ARGB_8888, false)
                            } finally {
                                buffer.close()
                            }

                            if (bitmap == null) {
                                visualCaptureInFlight = false
                                handleVisualCaptureFailure(packageName, generation)
                                return
                            }
                            recognizeNavigationText(bitmap, packageName, generation)
                        }

                        override fun onFailure(errorCode: Int) {
                            sessionControlView?.visibility = View.VISIBLE
                            visualCaptureInFlight = false
                            handleVisualCaptureFailure(packageName, generation)
                        }
                    },
                )
            },
            MANUAL_CAPTURE_HIDE_OVERLAY_MS,
        )
        return true
    }

    private fun requestManualModelGuidance() {
        val task = GuidancePreferences.selectedTask(this)
        if (task == null || !GuidancePreferences.isSessionActive(this)) {
            showMessageOverlay("请先在 EasyAccess 中选择一个帮助任务。", OverlayTone.WARNING)
            return
        }
        if (!AiGuidancePreferences.isEnabled(this)) {
            showMessageOverlay("请先在 EasyAccess 主界面开启 AI 界面识别。", OverlayTone.WARNING)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            showMessageOverlay("当前安卓版本暂不支持 AI 截图识别。", OverlayTone.WARNING)
            return
        }
        if (visualCaptureInFlight || modelGuidanceInFlight) {
            return
        }
        val packageName = rootInActiveWindow?.packageName?.toString().orEmpty()
        if (packageName != task.packageName) {
            showMessageOverlay("请先打开正在帮助的目标应用。", OverlayTone.WARNING)
            return
        }

        visualCaptureInFlight = true
        removeOverlay()
        sessionControlView?.visibility = View.INVISIBLE
        mainHandler.postDelayed(
            {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    screenshotExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: ScreenshotResult) {
                            sessionControlView?.visibility = View.VISIBLE
                            val buffer = screenshotResult.hardwareBuffer
                            val bitmap = try {
                                Bitmap.wrapHardwareBuffer(buffer, screenshotResult.colorSpace)
                                    ?.copy(Bitmap.Config.ARGB_8888, false)
                            } finally {
                                buffer.close()
                            }
                            visualCaptureInFlight = false
                            if (bitmap == null) {
                                showUnavailableOverlay(com.easyaccess.app.R.string.vision_failed_overlay)
                                return
                            }
                            val started = requestModelGuidance(
                                bitmap = bitmap,
                                packageName = packageName,
                                generation = captureGeneration,
                                task = task,
                                ignoreCooldown = true,
                            )
                            bitmap.recycle()
                            if (!started) {
                                showUnavailableOverlay(com.easyaccess.app.R.string.ai_unavailable_overlay)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            sessionControlView?.visibility = View.VISIBLE
                            visualCaptureInFlight = false
                            showUnavailableOverlay(com.easyaccess.app.R.string.vision_failed_overlay)
                        }
                    },
                )
            },
            MANUAL_CAPTURE_HIDE_OVERLAY_MS,
        )
    }

    private fun handleVisualCaptureFailure(packageName: String, generation: Int) {
        val task = GuidancePreferences.selectedTask(this)
        if (!GuidancePreferences.isSessionActive(this) || task?.packageName != packageName) return
        if (task == GuidanceTask.ALIPAY_TRANSIT) {
            if (alipayTransitEntryOffered) {
                finishAlipayTransitSelection()
            } else {
                val message = "请在支付宝首页点击“出行”。"
                val shown = showMessageOverlay(message, OverlayTone.ACTION)
                if (shown) speakGuidance(message)
            }
        } else if (generation == captureGeneration) {
            showUnavailableOverlay(com.easyaccess.app.R.string.vision_failed_overlay)
        }
    }

    private fun recognizeNavigationText(
        bitmap: Bitmap,
        packageName: String,
        generation: Int,
    ) {
        val recognizer = textRecognizer ?: TextRecognition.getClient(
            ChineseTextRecognizerOptions.Builder().build(),
        ).also { textRecognizer = it }
        val keywords = GuidanceEngine.recognitionKeywords(
            GuidancePreferences.selectedTask(this),
            packageName,
            GuidancePreferences.transitMode(this),
        )

        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val activeTask = GuidancePreferences.selectedTask(this)
                if (!GuidancePreferences.isSessionActive(this) ||
                    activeTask?.packageName != packageName
                ) {
                    return@addOnSuccessListener
                }
                if (generation != captureGeneration) {
                    scheduleFreshCapture(activeTask)
                    return@addOnSuccessListener
                }

                val nodes = ArrayList<UiNodeSnapshot>()
                val seen = HashSet<String>()
                fun addIfNavigationText(text: String, bounds: Rect?) {
                    val safeText = text.trim()
                    val safeBounds = bounds ?: return
                    if (safeText.isBlank() || safeBounds.isEmpty) return
                    if (keywords.none { safeText.contains(it, ignoreCase = true) }) return
                    val key = "$safeBounds|$safeText"
                    if (!seen.add(key)) return
                    nodes += UiNodeSnapshot(
                        text = safeText.take(MAX_TEXT_LENGTH),
                        contentDescription = "",
                        className = OCR_NODE_CLASS,
                        viewId = "on-device-ocr",
                        clickable = false,
                        enabled = true,
                        editable = false,
                        password = false,
                        bounds = Rect(safeBounds),
                        depth = 0,
                    )
                }

                result.textBlocks.forEach { block ->
                    block.lines.forEach { line ->
                        addIfNavigationText(line.text, line.boundingBox)
                        line.elements.forEach { element ->
                            addIfNavigationText(element.text, element.boundingBox)
                        }
                    }
                }

                val isWechatTask = activeTask == GuidanceTask.WECHAT_CONTACT
                val isWechatContactsPage =
                    isWechatTask && nodes.any { node ->
                            node.safeLabel.contains("通讯录") &&
                                node.bounds.centerY() < bitmap.height * 0.28f
                        }
                val topSearchNode = nodes.firstOrNull { node ->
                    node.safeLabel.contains("搜索") &&
                        node.bounds.centerY() < bitmap.height * 0.22f
                }
                val isWechatSearchPage =
                    isWechatTask &&
                        topSearchNode != null &&
                        !isWechatContactsPage
                if (isWechatTask) {
                    nodes.removeAll { it.safeLabel.contains("搜索") }
                }
                if (isWechatSearchPage) {
                    nodes += UiNodeSnapshot(
                        text = "微信联系人搜索页",
                        contentDescription = "",
                        className = OCR_NODE_CLASS,
                        viewId = WECHAT_SEARCH_READY_VIEW_ID,
                        clickable = false,
                        enabled = true,
                        editable = false,
                        password = false,
                        bounds = Rect(topSearchNode!!.bounds),
                        depth = 0,
                    )
                }
                if (isWechatContactsPage) {
                    nodes += UiNodeSnapshot(
                        text = "搜索",
                        contentDescription = "",
                        className = OCR_NODE_CLASS,
                        viewId = "derived-wechat-search",
                        clickable = true,
                        enabled = true,
                        editable = false,
                        password = false,
                        bounds = Rect(
                            (bitmap.width * 0.82f).toInt(),
                            (bitmap.height * 0.035f).toInt(),
                            (bitmap.width * 0.86f).toInt(),
                            (bitmap.height * 0.08f).toInt(),
                        ),
                        depth = 0,
                    )
                }

                val observation = UiObservation(
                    packageName = packageName,
                    appName = TargetApps.displayName(packageName),
                    capturedAtMillis = System.currentTimeMillis(),
                    nodes = nodes,
                )
                UiObservationStore.publish(observation)
                GuidanceEngine.terminalOutcome(
                    GuidancePreferences.selectedTask(this),
                    observation,
                )?.let { outcome ->
                    val tone = if (outcome.successful) OverlayTone.SUCCESS else OverlayTone.WARNING
                    showMessageOverlay(outcome.message, tone, END_OVERLAY_DURATION_MS)
                    speakGuidance(outcome.message)
                    GuidancePreferences.endSession(this)
                    removeSessionControls()
                    return@addOnSuccessListener
                }
                if (!highlightSuggestedNode(observation)) {
                    val modelStarted = requestModelGuidance(
                        bitmap,
                        packageName,
                        generation,
                        activeTask,
                    )
                    if (!modelStarted && !isModelBusyOrCoolingDown()) {
                        showUnavailableOverlay(com.easyaccess.app.R.string.vision_no_match_overlay)
                    }
                }
            }
            .addOnFailureListener {
                if (generation == captureGeneration) {
                    val activeTask = GuidancePreferences.selectedTask(this)
                    if (activeTask == null ||
                        !requestModelGuidance(bitmap, packageName, generation, activeTask)
                    ) {
                        showUnavailableOverlay(com.easyaccess.app.R.string.vision_failed_overlay)
                    }
                }
            }
            .addOnCompleteListener {
                bitmap.recycle()
                visualCaptureInFlight = false
            }
    }

    private fun requestModelGuidance(
        bitmap: Bitmap,
        packageName: String,
        generation: Int,
        task: GuidanceTask,
        ignoreCooldown: Boolean = false,
    ): Boolean {
        if (!AiGuidancePreferences.isEnabled(this) || modelGuidanceInFlight) return false
        val now = System.currentTimeMillis()
        if (!ignoreCooldown &&
            now - lastModelRequestAtMillis < MODEL_REQUEST_COOLDOWN_MS
        ) {
            return false
        }
        val pageHash = perceptualHash(bitmap)
        val previousPageHash = lastModelPageHash
        if (!ignoreCooldown &&
            previousPageHash != null &&
            now - lastModelRequestAtMillis < MODEL_PAGE_DEDUPLICATION_MS &&
            java.lang.Long.bitCount(previousPageHash xor pageHash) <= MODEL_PAGE_HASH_DISTANCE
        ) {
            // The same visual page was already analyzed. Keep the existing advice
            // instead of spending another model request on animated content changes.
            return true
        }
        val modelBitmap = runCatching {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }.getOrNull() ?: return false

        modelGuidanceInFlight = true
        lastModelRequestAtMillis = now
        lastModelPageHash = pageHash
        showModelSearchingFeedback()
        modelExecutor.execute {
            val result = runCatching {
                modelGuidanceClient.analyze(
                    bitmap = modelBitmap,
                    taskText = GuidancePreferences.modelGoal(this, task),
                )
            }
            modelBitmap.recycle()
            mainHandler.post {
                modelGuidanceInFlight = false
                finishModelSearchingFeedback()
                if (!GuidancePreferences.isSessionActive(this) ||
                    GuidancePreferences.selectedTask(this) != task ||
                    task.packageName != packageName
                ) {
                    return@post
                }
                if (generation != captureGeneration) {
                    // The user moved to another page while the model was working.
                    // Never display guidance produced from the previous screenshot.
                    scheduleFreshCapture(task)
                    return@post
                }
                result.fold(
                    onSuccess = ::showModelGuidance,
                    onFailure = {
                        if (lastModelPageHash == pageHash) lastModelPageHash = null
                        showUnavailableOverlay(com.easyaccess.app.R.string.ai_unavailable_overlay)
                    },
                )
            }
        }
        return true
    }

    private fun showModelSearchingFeedback() {
        modelSearchLongWait?.let(mainHandler::removeCallbacks)
        modelSearchFeedbackActive = true
        val message = "正在帮您寻找，请稍候…"
        val shown = showOverlay(
            bounds = null,
            message = message,
            tone = OverlayTone.SEARCHING,
            durationMs = MODEL_LONG_WAIT_DELAY_MS + 1_000L,
            forceReplace = true,
        )
        if (shown) speakGuidance("好的，正在帮您寻找。")

        modelSearchLongWait = Runnable {
            if (!modelSearchFeedbackActive || !modelGuidanceInFlight) return@Runnable
            val longWaitMessage = "还在寻找，请稍候…"
            val longWaitShown = showOverlay(
                bounds = null,
                message = longWaitMessage,
                tone = OverlayTone.SEARCHING,
                durationMs = MODEL_LONG_WAIT_OVERLAY_DURATION_MS,
                forceReplace = true,
            )
            if (longWaitShown) speakGuidance(longWaitMessage)
        }.also {
            mainHandler.postDelayed(it, MODEL_LONG_WAIT_DELAY_MS)
        }
    }

    private fun finishModelSearchingFeedback() {
        modelSearchFeedbackActive = false
        modelSearchLongWait?.let(mainHandler::removeCallbacks)
        modelSearchLongWait = null
        if (overlayView != null && lastOverlayKey.endsWith("|${OverlayTone.SEARCHING}")) {
            removeOverlay()
        }
    }

    private fun isModelBusyOrCoolingDown(): Boolean =
        modelGuidanceInFlight ||
            System.currentTimeMillis() - lastModelRequestAtMillis < MODEL_REQUEST_COOLDOWN_MS

    private fun isNavigationEvent(event: AccessibilityEvent?): Boolean = when (event?.eventType) {
        AccessibilityEvent.TYPE_VIEW_CLICKED,
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
        AccessibilityEvent.TYPE_WINDOWS_CHANGED,
        -> true
        else -> false
    }

    private fun scheduleFreshCapture(task: GuidanceTask) {
        val packageName = rootInActiveWindow?.packageName?.toString().orEmpty()
        if (!GuidancePreferences.isSessionActive(this) ||
            GuidancePreferences.selectedTask(this) != task ||
            packageName != task.packageName
        ) {
            return
        }
        pendingCapture?.let(mainHandler::removeCallbacks)
        pendingPackageName = packageName
        captureGeneration++
        val generation = captureGeneration
        pendingCapture = Runnable {
            pendingCapture = null
            captureCurrentWindow(packageName, generation, attempt = 0)
        }.also {
            mainHandler.postDelayed(it, CAPTURE_THROTTLE_MS)
        }
    }

    private fun perceptualHash(bitmap: Bitmap): Long {
        val sample = Bitmap.createScaledBitmap(bitmap, HASH_SIZE, HASH_SIZE, true)
        val pixels = IntArray(HASH_SIZE * HASH_SIZE)
        sample.getPixels(pixels, 0, HASH_SIZE, 0, 0, HASH_SIZE, HASH_SIZE)
        if (sample !== bitmap) sample.recycle()
        val luminance = IntArray(pixels.size)
        var total = 0L
        pixels.forEachIndexed { index, pixel ->
            val red = pixel shr 16 and 0xff
            val green = pixel shr 8 and 0xff
            val blue = pixel and 0xff
            val value = (red * 30 + green * 59 + blue * 11) / 100
            luminance[index] = value
            total += value
        }
        val average = total / luminance.size
        var hash = 0L
        luminance.forEachIndexed { index, value ->
            if (value >= average) hash = hash or (1L shl index)
        }
        return hash
    }

    private fun showModelGuidance(result: ModelGuidanceResult) {
        val isCompleted = result.taskStatus == "completed"
        val isBlocked = result.taskStatus == "blocked" ||
            result.riskLevel == "critical"
        val shouldEnd = isCompleted || isBlocked || result.actionType == "stop"
        val needsCaution = result.confirmationRequired ||
            result.riskLevel == "high" ||
            result.riskLevel == "critical"
        val isUncertain = result.taskStatus == "uncertain" ||
            result.actionType == "ask_user"
        val shouldAutoRetry = !shouldEnd &&
            isUncertain &&
            modelUncertainAutoRetryCount < MAX_MODEL_UNCERTAIN_AUTO_RETRIES
        val message = when {
            needsCaution -> "AI 安全提醒：${result.instruction}"
            shouldAutoRetry -> "页面正在稳定，我会自动再找一次，请稍候。"
            else -> "AI 建议：${result.instruction}"
        }
        val tone = when {
            isBlocked || needsCaution -> OverlayTone.WARNING
            isCompleted -> OverlayTone.SUCCESS
            else -> OverlayTone.ACTION
        }
        val bounds = if (needsCaution) null else refineModelTargetBounds(result)
        val duration = if (shouldEnd) END_OVERLAY_DURATION_MS else MODEL_GUIDANCE_DURATION_MS
        val shown = showOverlay(
            bounds = bounds,
            message = message,
            tone = tone,
            durationMs = duration,
            forceReplace = true,
        )
        if (shown) speakGuidance(message, force = true)
        if (shouldAutoRetry) {
            modelUncertainAutoRetryCount++
            scheduleModelUncertainRetry()
        } else if (!isUncertain) {
            modelUncertainAutoRetryCount = 0
        }
        if (shouldEnd) {
            resetModelPageState()
            GuidancePreferences.endSession(this)
            removeSessionControls()
        }
    }

    private fun scheduleModelUncertainRetry() {
        val task = GuidancePreferences.selectedTask(this) ?: return
        pendingModelUncertainRetry?.let(mainHandler::removeCallbacks)
        pendingModelUncertainRetry = Runnable {
            pendingModelUncertainRetry = null
            if (!GuidancePreferences.isSessionActive(this) ||
                GuidancePreferences.isPaused(this) ||
                GuidancePreferences.selectedTask(this) != task
            ) {
                return@Runnable
            }
            lastModelPageHash = null
            lastModelRequestAtMillis = 0L
            scheduleFreshCapture(task)
        }.also {
            mainHandler.postDelayed(it, MODEL_UNCERTAIN_RETRY_DELAY_MS)
        }
    }

    private fun resetModelPageState() {
        pendingModelUncertainRetry?.let(mainHandler::removeCallbacks)
        pendingModelUncertainRetry = null
        modelUncertainAutoRetryCount = 0
        lastModelPageHash = null
        lastModelRequestAtMillis = 0L
    }

    private fun refineModelTargetBounds(result: ModelGuidanceResult): Rect? {
        val modelBounds = result.targetBounds ?: return null
        val targetText = normalizeTargetText(result.targetText)
        if (targetText.length < 2) return modelBounds

        val observation = UiObservationStore.latest ?: return modelBounds
        val currentPackage = rootInActiveWindow?.packageName?.toString().orEmpty()
        if (observation.packageName != currentPackage) return modelBounds

        val matchingNodes = observation.nodes.filter { node ->
            if (!node.enabled || node.bounds.isEmpty) return@filter false
            val nodeText = normalizeTargetText(node.safeLabel)
            nodeText.isNotBlank() &&
                (nodeText.contains(targetText, ignoreCase = true) ||
                    targetText.contains(nodeText, ignoreCase = true))
        }
        if (matchingNodes.isEmpty()) return modelBounds

        val exactMatches = matchingNodes.filter {
            normalizeTargetText(it.safeLabel).equals(targetText, ignoreCase = true)
        }
        val preferredMatches = (exactMatches.ifEmpty { matchingNodes }).let { candidates ->
            candidates.filter { it.clickable }.ifEmpty { candidates }
        }
        val modelCenterX = modelBounds.centerX().toLong()
        val modelCenterY = modelBounds.centerY().toLong()
        val bestMatch = preferredMatches.minByOrNull { node ->
            val deltaX = node.bounds.centerX().toLong() - modelCenterX
            val deltaY = node.bounds.centerY().toLong() - modelCenterY
            deltaX * deltaX + deltaY * deltaY
        } ?: return modelBounds
        return Rect(bestMatch.bounds)
    }

    private fun normalizeTargetText(value: String): String =
        value.filter { character ->
            !character.isWhitespace() &&
                character !in "“”\"'：:，,。·（）()【】[]"
        }

    private fun collectNodes(
        node: AccessibilityNodeInfo,
        depth: Int,
        output: MutableList<UiNodeSnapshot>,
        seen: MutableSet<String>,
    ) {
        if (depth > MAX_DEPTH || output.size >= MAX_NODES) return

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val password = node.isPassword
        val editable = node.isEditable

        // Do not collect the value of password fields or editable fields. The
        // latter may contain a search term, name, phone number or message.
        val safeText = if (password || editable) "" else node.text?.toString().orEmpty().trim()
        val safeDescription = if (password) "" else node.contentDescription?.toString().orEmpty().trim()
        val className = node.className?.toString().orEmpty()
        val viewId = node.viewIdResourceName.orEmpty()
        val largerSide = maxOf(bounds.width(), bounds.height())
        val smallerSide = minOf(bounds.width(), bounds.height())
        val isLargeSquareImage =
            (className.endsWith("ImageView") || className.endsWith("FrameLayout")) &&
            smallerSide >= 400 &&
            largerSide > 0 &&
            smallerSide.toFloat() / largerSide >= 0.85f

        val hasUsefulData = safeText.isNotBlank() ||
            safeDescription.isNotBlank() ||
            node.isClickable ||
            editable ||
            isLargeSquareImage

        if (hasUsefulData && !bounds.isEmpty) {
            val key = "$bounds|$className|$viewId|$safeText|$safeDescription"
            if (seen.add(key)) {
                output += UiNodeSnapshot(
                    text = safeText.take(MAX_TEXT_LENGTH),
                    contentDescription = safeDescription.take(MAX_TEXT_LENGTH),
                    className = className,
                    viewId = viewId,
                    clickable = node.isClickable,
                    enabled = node.isEnabled,
                    editable = editable,
                    password = password,
                    bounds = Rect(bounds),
                    depth = depth,
                )
            }
        }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            collectNodes(child, depth + 1, output, seen)
            if (output.size >= MAX_NODES) break
        }
    }

    fun highlightSuggestedNode(
        observation: UiObservation? = UiObservationStore.latest,
        forceSpeak: Boolean = false,
    ): Boolean {
        observation ?: return false
        val keywords = GuidanceEngine.preferredKeywords(
            GuidancePreferences.selectedTask(this),
            observation.packageName,
            GuidancePreferences.transitMode(this),
        )
        val labels = observation.nodes.map { it.safeLabel }
        val hasTaobaoOrderContext = labels.any { label ->
            listOf(
                "待收货",
                "我的订单",
                "全部订单",
                "订单编号",
                "卖家已发货",
                "运输中",
                "已签收",
            ).any { marker -> label.contains(marker, ignoreCase = true) }
        }
        val match = keywords.firstNotNullOfOrNull { keyword ->
            if (GuidancePreferences.selectedTask(this) == GuidanceTask.TAOBAO_LOGISTICS &&
                keyword == "查看物流" &&
                !hasTaobaoOrderContext
            ) {
                return@firstNotNullOfOrNull null
            }
            observation.nodes.firstOrNull { node ->
                node.enabled &&
                    node.safeLabel.contains(keyword, ignoreCase = true) &&
                    !node.bounds.isEmpty
            }?.let { keyword to it }
        }
        val target = match?.second ?: return false
        if (GuidancePreferences.selectedTask(this) == GuidanceTask.ALIPAY_TRANSIT &&
            match.first == "出行" &&
            !alipayTransitEntryOffered
        ) {
            alipayTransitEntryOffered = true
            alipayTransitEntryOfferedAtMillis = System.currentTimeMillis()
        }
        if (GuidancePreferences.selectedTask(this) == GuidanceTask.TAOBAO_CUSTOMER_SERVICE &&
            match.first in listOf("官方客服", "平台客服", "客服小蜜")
        ) {
            taobaoCustomerEntryOffered = true
            taobaoCustomerEntryOfferedAtMillis = System.currentTimeMillis()
            taobaoCustomerTargetBounds = Rect(target.bounds)
        }
        if (GuidancePreferences.selectedTask(this) == GuidanceTask.WECHAT_CONTACT &&
            match.first == "搜索"
        ) {
            wechatSearchEntryOffered = true
            wechatSearchEntryOfferedAtMillis = System.currentTimeMillis()
            wechatSearchTargetBounds = Rect(target.bounds)
        }
        val message = "下一步，请点击“${match.first}”"
        val shown = showOverlay(target.bounds, message, forceReplace = forceSpeak)
        if (shown) speakGuidance(message, force = forceSpeak)
        return shown
    }

    private fun showUnavailableOverlay(messageRes: Int): Boolean {
        val message = getString(messageRes)
        val shown = showMessageOverlay(message, OverlayTone.WARNING)
        if (shown) speakGuidance(message)
        return shown
    }

    private fun showMessageOverlay(
        message: String,
        tone: OverlayTone,
        durationMs: Long = OVERLAY_DURATION_MS,
    ): Boolean = showOverlay(
        bounds = null,
        message = message,
        tone = tone,
        durationMs = durationMs,
    )

    private fun showOverlay(
        bounds: Rect?,
        message: String,
        tone: OverlayTone = OverlayTone.ACTION,
        durationMs: Long = OVERLAY_DURATION_MS,
        forceReplace: Boolean = false,
    ): Boolean {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val overlayKey = "${bounds?.flattenToString()}|$message|$tone"
        if (!forceReplace && overlayKey == lastOverlayKey) return true
        removeOverlay()

        val view = GuidanceOverlayView(this, bounds, message, tone)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        return runCatching {
            windowManager.addView(view, params)
            overlayView = view
            lastOverlayKey = overlayKey
            mainHandler.postDelayed(
                {
                    if (overlayView === view) removeOverlay()
                },
                durationMs,
            )
        }.isSuccess
    }

    private fun speakGuidance(message: String, force: Boolean = false) {
        val isNewMessage = message != lastSpokenMessage
        if (!force && !isNewMessage) return
        lastSpokenMessage = message
        if (isNewMessage) vibrateForNewStep()
        if (textToSpeechReady) {
            textToSpeech?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "easyaccess-guidance")
        }
    }

    private fun vibrateForNewStep() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(80L, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(80L)
        }
    }

    private fun finishAlipayTransitSelection() {
        if (!GuidancePreferences.isSessionActive(this) ||
            GuidancePreferences.selectedTask(this) != GuidanceTask.ALIPAY_TRANSIT
        ) {
            return
        }
        val message = "请选择您的出行方式和地点。接下来请您自行确认，本次引导结束。"
        showMessageOverlay(message, OverlayTone.SUCCESS, END_OVERLAY_DURATION_MS)
        speakGuidance(message, force = true)
        GuidancePreferences.endSession(this)
        removeSessionControls()
    }

    private fun finishTaobaoCustomerService() {
        if (!GuidancePreferences.isSessionActive(this) ||
            GuidancePreferences.selectedTask(this) != GuidanceTask.TAOBAO_CUSTOMER_SERVICE
        ) {
            return
        }
        val message =
            "已进入淘宝官方客服。请您自行选择需要咨询的问题，EasyAccess 不会替您发送消息，本次引导结束。"
        showMessageOverlay(message, OverlayTone.SUCCESS, END_OVERLAY_DURATION_MS)
        speakGuidance(message, force = true)
        GuidancePreferences.endSession(this)
        removeSessionControls()
    }

    private fun finishWechatContactSearch() {
        if (!GuidancePreferences.isSessionActive(this) ||
            GuidancePreferences.selectedTask(this) != GuidanceTask.WECHAT_CONTACT
        ) {
            return
        }
        val message = "已进入搜索页面。请在搜索框中输入联系人姓名，本次引导结束。"
        showMessageOverlay(message, OverlayTone.SUCCESS, END_OVERLAY_DURATION_MS)
        speakGuidance(message, force = true)
        GuidancePreferences.endSession(this)
        removeSessionControls()
    }

    fun refreshSessionControls() {
        captureGeneration++
        finishModelSearchingFeedback()
        resetModelPageState()
        removeOverlay()
        val task = GuidancePreferences.selectedTask(this)
        if (task == null || !GuidancePreferences.isSessionActive(this)) {
            removeSessionControls()
            return
        }
        showSessionControls(task.controlTitle)
    }

    private fun showSessionControls(taskTitle: String) {
        sessionControlView?.let {
            if (sessionControlTaskTitle == taskTitle) {
                it.setPaused(GuidancePreferences.isPaused(this))
                return
            }
            removeSessionControls()
        }
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        lateinit var params: WindowManager.LayoutParams
        lateinit var view: SessionControlView
        view = SessionControlView(
            context = this,
            taskTitle = taskTitle,
            onRepeat = {
                if (!GuidancePreferences.isPaused(this)) {
                    highlightSuggestedNode(forceSpeak = true)
                }
            },
            onAiAnalyze = {
                if (!GuidancePreferences.isPaused(this)) {
                    requestManualModelGuidance()
                }
            },
            onPauseToggle = {
                val paused = !GuidancePreferences.isPaused(this)
                GuidancePreferences.setPaused(this, paused)
                sessionControlView?.setPaused(paused)
                if (paused) {
                    finishModelSearchingFeedback()
                    resetModelPageState()
                    removeOverlay()
                }
                else lastOverlayKey = ""
                speakGuidance(if (paused) "帮助已暂停" else "帮助已继续", force = true)
            },
            onEnd = {
                GuidancePreferences.endSession(this)
                finishModelSearchingFeedback()
                resetModelPageState()
                removeOverlay()
                removeSessionControls()
                val message = "本次帮助已结束"
                showMessageOverlay(message, OverlayTone.SUCCESS, END_OVERLAY_DURATION_MS)
                speakGuidance(message, force = true)
            },
            onMove = { deltaX, deltaY ->
                val margin = (8 * resources.displayMetrics.density).toInt()
                val maxX = (resources.displayMetrics.widthPixels - view.width - margin)
                    .coerceAtLeast(margin)
                val maxY = (resources.displayMetrics.heightPixels - view.height - margin)
                    .coerceAtLeast(margin)
                params.x = (params.x + deltaX).coerceIn(margin, maxX)
                params.y = (params.y + deltaY).coerceIn(margin, maxY)
                sessionControlX = params.x
                sessionControlY = params.y
                runCatching { windowManager.updateViewLayout(view, params) }
            },
        ).apply {
            setPaused(GuidancePreferences.isPaused(this@EasyAccessAccessibilityService))
        }
        lastOverlayKey = ""
        taobaoCustomerEntryOffered = false
        taobaoCustomerEntryOfferedAtMillis = 0L
        taobaoCustomerTargetBounds = null
        alipayTransitEntryOffered = false
        alipayTransitEntryOfferedAtMillis = 0L
        wechatSearchEntryOffered = false
        wechatSearchEntryOfferedAtMillis = 0L
        wechatSearchTargetBounds = null
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = sessionControlX ?: 0
            y = sessionControlY ?: 0
        }
        runCatching {
            windowManager.addView(view, params)
            sessionControlView = view
            sessionControlTaskTitle = taskTitle
            view.post {
                val margin = (8 * resources.displayMetrics.density).toInt()
                val maxX = (resources.displayMetrics.widthPixels - view.width - margin)
                    .coerceAtLeast(margin)
                val maxY = (resources.displayMetrics.heightPixels - view.height - margin)
                    .coerceAtLeast(margin)
                params.x = (sessionControlX ?: maxX).coerceIn(margin, maxX)
                params.y = (sessionControlY ?: ((maxY + margin) / 2)).coerceIn(margin, maxY)
                sessionControlX = params.x
                sessionControlY = params.y
                runCatching { windowManager.updateViewLayout(view, params) }
            }
        }
    }

    private fun removeSessionControls() {
        val view = sessionControlView ?: return
        sessionControlView = null
        sessionControlTaskTitle = ""
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        runCatching { windowManager.removeView(view) }
    }

    fun endSessionFromApp() {
        GuidancePreferences.endSession(this)
        finishModelSearchingFeedback()
        resetModelPageState()
        removeOverlay()
        removeSessionControls()
        speakGuidance("本次帮助已结束", force = true)
    }

    private fun removeOverlay() {
        val view = overlayView ?: return
        overlayView = null
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        runCatching { windowManager.removeView(view) }
    }

    companion object {
        private const val CAPTURE_THROTTLE_MS = 250L
        private const val OVERLAY_DURATION_MS = 8_000L
        private const val MODEL_GUIDANCE_DURATION_MS = 15_000L
        private const val MODEL_LONG_WAIT_DELAY_MS = 8_000L
        private const val MODEL_LONG_WAIT_OVERLAY_DURATION_MS = 15_000L
        private const val MODEL_UNCERTAIN_RETRY_DELAY_MS = 900L
        private const val MAX_MODEL_UNCERTAIN_AUTO_RETRIES = 1
        private const val END_OVERLAY_DURATION_MS = 4_000L
        private const val MODEL_REQUEST_COOLDOWN_MS = 3_000L
        private const val MODEL_PAGE_DEDUPLICATION_MS = 45_000L
        private const val MODEL_PAGE_HASH_DISTANCE = 10
        private const val HASH_SIZE = 8
        private const val MANUAL_CAPTURE_HIDE_OVERLAY_MS = 60L
        private const val MAX_DEPTH = 24
        private const val MAX_NODES = 250
        private const val MAX_TEXT_LENGTH = 120
        private const val OCR_NODE_CLASS = "EasyAccessOcr"
        private const val WECHAT_SEARCH_READY_VIEW_ID = "easyaccess-wechat-search-ready"
        private val EMPTY_RETRY_DELAYS_MS = longArrayOf(100L, 200L)

        @Volatile
        private var activeInstanceReference: WeakReference<EasyAccessAccessibilityService>? = null

        val isRunning: Boolean
            get() = activeInstanceReference?.get() != null

        fun requestHighlight(): Boolean =
            activeInstanceReference?.get()?.highlightSuggestedNode(forceSpeak = true) ?: false

        fun refreshSessionUi() {
            activeInstanceReference?.get()?.refreshSessionControls()
        }

        fun endSessionUi() {
            activeInstanceReference?.get()?.endSessionFromApp()
        }
    }
}
