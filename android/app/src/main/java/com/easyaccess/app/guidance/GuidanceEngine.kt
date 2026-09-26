package com.easyaccess.app.guidance

import com.easyaccess.app.accessibility.TargetApps
import com.easyaccess.app.model.UiObservation

object GuidanceEngine {
    data class Outcome(
        val message: String,
        val successful: Boolean,
    )

    fun preferredKeywords(
        task: GuidanceTask?,
        packageName: String,
        transitMode: TransitMode? = null,
    ): List<String> {
        if (task?.packageName != packageName) {
            return TargetApps.highlightKeywords[packageName].orEmpty()
        }
        return when (task) {
            GuidanceTask.TAOBAO_LOGISTICS -> listOf("查看物流", "待收货", "我的淘宝")
            GuidanceTask.TAOBAO_CUSTOMER_SERVICE ->
                listOf("官方客服", "平台客服", "客服小蜜", "我的淘宝")
            GuidanceTask.ALIPAY_TRANSIT -> listOf("出行", "首页")
            GuidanceTask.WECHAT_CONTACT -> listOf("搜索", "通讯录", "微信")
            GuidanceTask.TAOBAO_AI,
            GuidanceTask.ALIPAY_AI,
            GuidanceTask.WECHAT_AI,
            -> emptyList()
        }
    }

    fun recognitionKeywords(
        task: GuidanceTask?,
        packageName: String,
        transitMode: TransitMode? = null,
    ): List<String> {
        val navigation = preferredKeywords(task, packageName, transitMode)
        val terminal = when {
            task?.packageName != packageName -> emptyList()
            task == GuidanceTask.TAOBAO_LOGISTICS -> listOf(
                "您还没有相关的订单",
                "没有相关的订单",
                "暂无订单",
                "没有相关订单",
                "没有待收货",
                "还没有订单",
                "暂无待收货",
                "物流详情",
                "物流信息",
                "运输中",
                "已签收",
                "包裹正在",
            )
            task == GuidanceTask.ALIPAY_TRANSIT -> listOf(
                "二维码",
                "刷码乘车",
                "请扫码",
                "乘车码",
            )
            task == GuidanceTask.TAOBAO_CUSTOMER_SERVICE -> listOf(
                "猜你想问",
                "请输入您想咨询的问题",
                "服务大厅",
                "智能小蜜",
            )
            else -> emptyList()
        }
        return (navigation + terminal).distinct()
    }

    fun terminalOutcome(task: GuidanceTask?, observation: UiObservation): Outcome? {
        if (task?.packageName != observation.packageName) return null
        val labels = observation.nodes.map { it.safeLabel }
        fun hasAny(keywords: List<String>) = labels.any { label ->
            keywords.any { keyword -> label.contains(keyword, ignoreCase = true) }
        }

        return when (task) {
            GuidanceTask.TAOBAO_LOGISTICS -> when {
                hasAny(listOf(
                    "您还没有相关的订单",
                    "没有相关的订单",
                    "暂无订单",
                    "没有相关订单",
                    "没有待收货",
                    "还没有订单",
                    "暂无待收货",
                )) ->
                    Outcome("没有找到待收货订单，本次引导结束。", successful = false)
                hasAny(listOf("物流详情", "物流信息", "运输中", "已签收", "包裹正在")) ->
                    Outcome("已找到物流详情，本次引导完成。", successful = true)
                else -> null
            }
            GuidanceTask.TAOBAO_CUSTOMER_SERVICE -> {
                val hasOfficialServiceIdentity = hasAny(
                    listOf("官方客服", "平台客服", "客服小蜜", "智能小蜜"),
                )
                val hasServicePageContent = hasAny(
                    listOf("猜你想问", "请输入您想咨询的问题", "服务大厅", "热门问题"),
                )
                if (hasOfficialServiceIdentity && hasServicePageContent) {
                    Outcome(
                        "已进入淘宝官方客服页面。请您自行选择问题，EasyAccess 不会替您发送消息，本次引导结束。",
                        successful = true,
                    )
                } else {
                    null
                }
            }
            GuidanceTask.WECHAT_CONTACT -> when {
                observation.nodes.any {
                    it.viewId == "easyaccess-wechat-search-ready"
                } -> Outcome(
                    "您已经进入搜索页面。请在搜索框中输入联系人姓名，接下来请您自行输入和选择，本次引导结束。",
                    successful = true,
                )
                else -> null
            }
            GuidanceTask.ALIPAY_TRANSIT -> {
                val hasLargeSquareCodeImage = observation.nodes.any { node ->
                    val width = node.bounds.width()
                    val height = node.bounds.height()
                    val largerSide = maxOf(width, height)
                    val smallerSide = minOf(width, height)
                    (node.className.endsWith("ImageView") ||
                        node.className.endsWith("FrameLayout")) &&
                        smallerSide >= 400 &&
                        largerSide > 0 &&
                        smallerSide.toFloat() / largerSide >= 0.85f
                }
                if (hasLargeSquareCodeImage) {
                    Outcome(
                        "请选择您的出行方式和地点。接下来请您自行确认，本次引导结束。",
                        successful = true,
                    )
                } else {
                    null
                }
            }
            GuidanceTask.TAOBAO_AI,
            GuidanceTask.ALIPAY_AI,
            GuidanceTask.WECHAT_AI,
            -> null
            else -> null
        }
    }

    fun nextInstruction(
        task: GuidanceTask?,
        observation: UiObservation,
        transitMode: TransitMode? = null,
    ): String? {
        if (task?.packageName != observation.packageName) return null
        val labels = observation.nodes.map { it.safeLabel }
        fun has(keyword: String) = labels.any { it.contains(keyword, ignoreCase = true) }

        return when (task) {
            GuidanceTask.TAOBAO_LOGISTICS -> when {
                has("查看物流") -> "下一步：点击“查看物流”。到达物流详情后停止，不执行确认或支付。"
                has("待收货") -> "下一步：点击“待收货”，进入订单列表。"
                has("我的淘宝") -> "下一步：点击“我的淘宝”。"
                else -> "请返回淘宝首页，EasyAccess 会继续寻找“我的淘宝”。"
            }
            GuidanceTask.TAOBAO_CUSTOMER_SERVICE -> when {
                has("官方客服") -> "下一步：点击“官方客服”。进入后由您自行选择问题。"
                has("平台客服") -> "下一步：点击“平台客服”。进入后由您自行选择问题。"
                has("客服小蜜") -> "下一步：点击“客服小蜜”。进入后由您自行选择问题。"
                has("我的淘宝") -> "下一步：点击“我的淘宝”。"
                else -> "请返回淘宝首页，EasyAccess 会继续寻找“我的淘宝”。"
            }
            GuidanceTask.ALIPAY_TRANSIT -> when {
                has("出行") -> "下一步：点击“出行”。"
                else -> "请返回支付宝首页，EasyAccess 会继续寻找“出行”。"
            }
            GuidanceTask.WECHAT_CONTACT -> when {
                has("搜索") -> "下一步：点击“搜索”，由你手动输入联系人姓名并选择结果。"
                has("通讯录") -> "下一步：点击“通讯录”。"
                else -> "请返回微信主界面，EasyAccess 会继续寻找“通讯录”。"
            }
            GuidanceTask.TAOBAO_AI,
            GuidanceTask.ALIPAY_AI,
            GuidanceTask.WECHAT_AI,
            -> null
        }
    }
}
