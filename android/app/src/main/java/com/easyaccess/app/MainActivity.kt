package com.easyaccess.app

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.easyaccess.app.accessibility.EasyAccessAccessibilityService
import com.easyaccess.app.guidance.GuidanceEngine
import com.easyaccess.app.guidance.GuidancePreferences
import com.easyaccess.app.guidance.GuidanceTask
import com.easyaccess.app.guidance.TransitMode
import com.easyaccess.app.model.UiObservation
import com.easyaccess.app.privacy.AiGuidancePreferences
import com.easyaccess.app.privacy.VisionPreferences
import com.easyaccess.app.state.UiObservationStore

class MainActivity : AppCompatActivity() {
    private lateinit var serviceStatusView: TextView
    private lateinit var sessionStatusView: TextView
    private lateinit var voiceResultView: TextView
    private lateinit var taskInputView: EditText
    private lateinit var visionStatusView: TextView
    private lateinit var visionToggleButton: Button
    private lateinit var aiStatusView: TextView
    private lateinit var aiToggleButton: Button
    private lateinit var endSessionButton: Button
    private lateinit var developerPanel: LinearLayout
    private lateinit var observationView: TextView

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val spokenText = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (spokenText.isBlank()) {
            voiceResultView.setText(R.string.voice_not_heard)
        } else {
            voiceResultView.text = getString(R.string.voice_heard_format, spokenText)
            taskInputView.setText(spokenText)
            routeVoiceCommand(spokenText)
        }
    }

    private val observationListener: (UiObservation?) -> Unit = { observation ->
        runOnUiThread { renderObservation(observation) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContentView())
    }

    override fun onStart() {
        super.onStart()
        UiObservationStore.subscribe(observationListener)
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
    }

    override fun onStop() {
        UiObservationStore.unsubscribe(observationListener)
        super.onStop()
    }

    private fun buildContentView(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(32))
            setBackgroundColor(Color.rgb(244, 247, 251))
        }

        page.addView(textView("EasyAccess", 34f, true, Color.rgb(17, 54, 88)))
        page.addView(textView(
            "看清位置，听懂步骤，自己完成操作",
            17f,
            false,
            Color.rgb(73, 94, 114),
        ).apply { setPadding(0, dp(2), 0, dp(18)) })

        page.addView(card().apply {
            addView(textView("您想做什么？", 25f, true, Color.rgb(17, 54, 88)))
            addView(actionButton(
                label = "点击说出您的需求",
                color = Color.rgb(17, 112, 73),
                textSizeSp = 21f,
            ).apply {
                setOnClickListener { launchSpeechRecognition() }
            })
            voiceResultView = textView(
                "例如：帮我查看淘宝物流",
                15f,
                false,
                Color.rgb(88, 108, 128),
            ).apply { setPadding(dp(4), dp(10), dp(4), 0) }
            addView(voiceResultView)
            taskInputView = EditText(this@MainActivity).apply {
                hint = "也可以输入：帮我在淘宝申请退款"
                textSize = 18f
                minLines = 2
                maxLines = 4
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(12) }
            }
            addView(taskInputView)
            addView(actionButton(
                label = "开始 AI 通用帮助",
                color = Color.rgb(92, 67, 176),
                textSizeSp = 20f,
            ).apply {
                setOnClickListener {
                    val goal = taskInputView.text.toString().trim()
                    if (goal.isBlank()) {
                        voiceResultView.text = "请先输入需求，并说明使用淘宝、支付宝或微信。"
                    } else {
                        routeGenericGoal(goal)
                    }
                }
            })
        })

        page.addView(card(topMarginDp = 16).apply {
            addView(textView("也可以直接选择", 22f, true, Color.rgb(17, 54, 88)))
            addView(taskButton(GuidanceTask.TAOBAO_LOGISTICS))
            addView(taskButton(GuidanceTask.TAOBAO_CUSTOMER_SERVICE))
            addView(taskButton(GuidanceTask.ALIPAY_TRANSIT))
            addView(taskButton(GuidanceTask.WECHAT_CONTACT))
        })

        page.addView(card(topMarginDp = 16).apply {
            addView(textView("当前帮助", 22f, true, Color.rgb(17, 54, 88)))
            sessionStatusView = textView("", 17f, true, Color.rgb(88, 108, 128))
                .apply { setPadding(0, dp(10), 0, 0) }
            addView(sessionStatusView)
            endSessionButton = actionButton(
                label = "结束当前帮助",
                color = Color.rgb(166, 43, 52),
                textSizeSp = 18f,
            ).apply {
                setOnClickListener {
                    EasyAccessAccessibilityService.endSessionUi()
                    renderStatus()
                }
            }
            addView(endSessionButton)
        })

        page.addView(card(topMarginDp = 16).apply {
            addView(textView("使用准备", 22f, true, Color.rgb(17, 54, 88)))
            serviceStatusView = textView("", 17f, true, Color.rgb(143, 37, 48))
                .apply { setPadding(0, dp(10), 0, 0) }
            addView(serviceStatusView)
            addView(actionButton(
                label = "打开无障碍设置",
                color = Color.rgb(23, 105, 224),
                textSizeSp = 18f,
            ).apply {
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            })

            visionStatusView = textView("", 16f, true, Color.rgb(88, 108, 128))
                .apply { setPadding(0, dp(16), 0, 0) }
            addView(visionStatusView)
            visionToggleButton = actionButton("", Color.rgb(80, 101, 121), 17f).apply {
                setOnClickListener {
                    VisionPreferences.setEnabled(
                        this@MainActivity,
                        !VisionPreferences.isEnabled(this@MainActivity),
                    )
                    renderStatus()
                }
            }
            addView(visionToggleButton)

            aiStatusView = textView("", 16f, true, Color.rgb(88, 108, 128))
                .apply { setPadding(0, dp(16), 0, 0) }
            addView(aiStatusView)
            aiToggleButton = actionButton("", Color.rgb(49, 83, 154), 17f).apply {
                setOnClickListener { toggleAiGuidance() }
            }
            addView(aiToggleButton)
        })

        page.addView(actionButton(
            label = "显示开发测试信息",
            color = Color.rgb(80, 101, 121),
            textSizeSp = 15f,
        ).apply {
            setOnClickListener {
                developerPanel.visibility = if (developerPanel.visibility == View.GONE) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            }
        })

        developerPanel = card(topMarginDp = 10).apply {
            visibility = View.GONE
            addView(textView("开发测试信息 · v0.6", 18f, true, Color.rgb(17, 54, 88)))
            observationView = textView(
                "尚未收到页面信息",
                13f,
                false,
                Color.rgb(58, 82, 104),
            ).apply {
                setPadding(0, dp(8), 0, 0)
                setTextIsSelectable(true)
            }
            addView(observationView)
        }
        page.addView(developerPanel)

        page.addView(textView(
            "EasyAccess 只提供位置和语音指引。支付、发送消息和确认操作始终由您本人完成。",
            14f,
            false,
            Color.rgb(88, 108, 128),
        ).apply { setPadding(dp(4), dp(20), dp(4), 0) })

        return ScrollView(this).apply { addView(page) }
    }

    private fun taskButton(task: GuidanceTask) = actionButton(
        label = task.title,
        color = when (task) {
            GuidanceTask.TAOBAO_LOGISTICS -> Color.rgb(230, 92, 32)
            GuidanceTask.TAOBAO_CUSTOMER_SERVICE -> Color.rgb(196, 78, 27)
            GuidanceTask.ALIPAY_TRANSIT -> Color.rgb(22, 105, 210)
            GuidanceTask.WECHAT_CONTACT -> Color.rgb(17, 145, 73)
            GuidanceTask.TAOBAO_AI,
            GuidanceTask.ALIPAY_AI,
            GuidanceTask.WECHAT_AI,
            -> Color.rgb(92, 67, 176)
        },
        textSizeSp = 19f,
    ).apply {
        setOnClickListener { prepareTask(task) }
    }

    private fun prepareTask(
        task: GuidanceTask,
        transitMode: TransitMode? = null,
        customGoal: String? = null,
    ) {
        if (!EasyAccessAccessibilityService.isRunning) {
            AlertDialog.Builder(this)
                .setTitle("请先开启界面辅助")
                .setMessage("EasyAccess 需要读取当前页面，才能告诉您下一步点击哪里。")
                .setPositiveButton("去开启") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        if (task.isGeneric &&
            (!VisionPreferences.isEnabled(this) || !AiGuidancePreferences.isEnabled(this))
        ) {
            AlertDialog.Builder(this)
                .setTitle("开启 AI 通用帮助")
                .setMessage(
                    "EasyAccess 会在任务进行时发送当前页面的单帧截图到您配置的电脑后端，" +
                        "由多模态模型判断下一步。截图不保存；请勿在密码、验证码或付款页面继续。",
                )
                .setPositiveButton("同意并开始") { _, _ ->
                    VisionPreferences.setEnabled(this, true)
                    AiGuidancePreferences.setEnabled(this, true)
                    startTask(task, transitMode, customGoal)
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        if (task == GuidanceTask.WECHAT_CONTACT && !VisionPreferences.isEnabled(this)) {
            AlertDialog.Builder(this)
                .setTitle("开启本机视觉识别")
                .setMessage(
                    "微信不会提供完整控件信息。EasyAccess 将只在手机本地识别导航文字；截图不保存、不上传。",
                )
                .setPositiveButton("同意并继续") { _, _ ->
                    VisionPreferences.setEnabled(this, true)
                    startTask(task)
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        startTask(task, transitMode, customGoal)
    }

    private fun startTask(
        task: GuidanceTask,
        transitMode: TransitMode? = null,
        customGoal: String? = null,
    ) {
        val launchIntent = packageManager.getLaunchIntentForPackage(task.packageName)
        if (launchIntent == null) {
            AlertDialog.Builder(this)
                .setTitle("没有找到应用")
                .setMessage("请先安装${task.title.substringBefore('：')}。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }

        if (task == GuidanceTask.ALIPAY_TRANSIT && transitMode != null) {
            GuidancePreferences.setTransitMode(this, transitMode)
        }
        GuidancePreferences.startSession(this, task, customGoal)
        EasyAccessAccessibilityService.refreshSessionUi()
        renderStatus()
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launchIntent)
    }

    private fun launchSpeechRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "请说出您想完成的操作")
        }
        try {
            speechLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            AlertDialog.Builder(this)
                .setTitle("暂时无法使用语音")
                .setMessage("手机没有可用的语音识别服务，请先使用下面的任务按钮。")
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    private fun toggleAiGuidance() {
        if (AiGuidancePreferences.isEnabled(this)) {
            AiGuidancePreferences.setEnabled(this, false)
            renderStatus()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("开启 AI 界面识别")
            .setMessage(
                "当本机规则找不到下一步时，EasyAccess 会把当前屏幕截图发送到您配置的电脑后端，" +
                    "由多模态模型分析。截图只用于本次识别，服务器不保存；请勿在密码、验证码或付款页面开启。",
            )
            .setPositiveButton("同意并开启") { _, _ ->
                AiGuidancePreferences.setEnabled(this, true)
                renderStatus()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun routeVoiceCommand(spokenText: String) {
        val task = when {
            spokenText.contains("淘宝") &&
                listOf("客服", "小蜜", "咨询").any(spokenText::contains) ->
                GuidanceTask.TAOBAO_CUSTOMER_SERVICE
            listOf("物流", "快递").any(spokenText::contains) ->
                GuidanceTask.TAOBAO_LOGISTICS
            listOf("乘车码", "公交", "地铁", "出行").any(spokenText::contains) ->
                GuidanceTask.ALIPAY_TRANSIT
            listOf("联系人", "通讯录", "找人").any(spokenText::contains) ->
                GuidanceTask.WECHAT_CONTACT
            else -> null
        }
        if (task == null) {
            routeGenericGoal(spokenText)
        } else {
            val transitMode = when {
                spokenText.contains("公交") -> TransitMode.BUS
                spokenText.contains("地铁") -> TransitMode.SUBWAY
                else -> null
            }
            prepareTask(task, transitMode)
        }
    }

    private fun routeGenericGoal(rawGoal: String): Boolean {
        val goal = rawGoal.trim().take(200)
        val task = when {
            goal.contains("淘宝") -> GuidanceTask.TAOBAO_AI
            goal.contains("支付宝") -> GuidanceTask.ALIPAY_AI
            goal.contains("微信") -> GuidanceTask.WECHAT_AI
            else -> null
        }
        if (task == null) {
            voiceResultView.setText(R.string.voice_not_supported)
            return false
        }
        taskInputView.setText(goal)
        prepareTask(task, customGoal = goal)
        return true
    }

    private fun renderStatus() {
        val running = EasyAccessAccessibilityService.isRunning
        serviceStatusView.setText(
            if (running) R.string.service_enabled_status else R.string.service_disabled_status,
        )
        serviceStatusView.setTextColor(
            if (running) Color.rgb(17, 112, 73) else Color.rgb(143, 37, 48),
        )

        val visionEnabled = VisionPreferences.isEnabled(this)
        visionStatusView.setText(
            if (visionEnabled) R.string.vision_enabled_status else R.string.vision_disabled_status,
        )
        visionToggleButton.setText(
            if (visionEnabled) R.string.disable_on_device_vision else R.string.enable_on_device_vision,
        )

        val aiEnabled = AiGuidancePreferences.isEnabled(this)
        aiStatusView.setText(
            if (aiEnabled) R.string.ai_guidance_enabled_status else R.string.ai_guidance_disabled_status,
        )
        aiStatusView.setTextColor(
            if (aiEnabled) Color.rgb(17, 112, 73) else Color.rgb(88, 108, 128),
        )
        aiToggleButton.setText(
            if (aiEnabled) R.string.disable_ai_guidance else R.string.enable_ai_guidance,
        )

        val active = GuidancePreferences.isSessionActive(this)
        val task = GuidancePreferences.selectedTask(this)
        sessionStatusView.text = if (active && task != null) {
            getString(
                R.string.active_session_format,
                GuidancePreferences.displayTitle(this, task),
            )
        } else {
            getString(R.string.no_active_session)
        }
        sessionStatusView.setTextColor(
            if (active) Color.rgb(17, 112, 73) else Color.rgb(88, 108, 128),
        )
        endSessionButton.visibility = if (active) View.VISIBLE else View.GONE
    }

    private fun renderObservation(observation: UiObservation?) {
        if (observation == null) {
            observationView.text = getString(R.string.no_observation)
            return
        }
        val source = if (observation.nodes.any { it.className == OCR_NODE_CLASS }) {
            "本机视觉识别"
        } else {
            "界面控件"
        }
        observationView.text = buildString {
            append("当前应用：${observation.appName}\n")
            append("识别方式：$source\n")
            append("有效控件：${observation.nodes.size}\n")
            GuidanceEngine.nextInstruction(
                GuidancePreferences.selectedTask(this@MainActivity),
                observation,
                GuidancePreferences.transitMode(this@MainActivity),
            )?.let { append(it) }
        }
    }

    private fun textView(text: String, sizeSp: Float, bold: Boolean, color: Int) =
        TextView(this).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            setLineSpacing(0f, 1.24f)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun actionButton(label: String, color: Int, textSizeSp: Float) =
        Button(this).apply {
            text = label
            textSize = textSizeSp
            isAllCaps = false
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            minHeight = dp(62)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(16).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
        }

    private fun card(topMarginDp: Int = 0) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), Color.rgb(218, 227, 236))
        }
        elevation = dp(2).toFloat()
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(topMarginDp) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val OCR_NODE_CLASS = "EasyAccessOcr"
    }
}
