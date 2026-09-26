package com.easyaccess.app.guidance

import com.easyaccess.app.accessibility.TargetApps

enum class GuidanceTask(
    val id: String,
    val title: String,
    val controlTitle: String,
    val modelGoal: String,
    val packageName: String,
    val isGeneric: Boolean = false,
) {
    TAOBAO_LOGISTICS(
        id = "taobao_logistics",
        title = "淘宝：查看物流",
        controlTitle = "淘宝物流帮助",
        modelGoal = "帮助用户在淘宝查看已购买商品的物流。安全路线通常是：我的淘宝 → 我的订单或待收货 → 选择订单 → 查看物流。如果当前是商品详情、搜索或其他无关页面，应优先识别并引导点击左上角返回按钮，回到能继续该任务的页面；不要把购买、立即付款或加入购物车当作下一步。",
        packageName = TargetApps.TAOBAO,
    ),
    TAOBAO_CUSTOMER_SERVICE(
        id = "taobao_customer_service",
        title = "淘宝：找到官方客服",
        controlTitle = "淘宝客服帮助",
        modelGoal = "帮助用户在淘宝找到平台官方客服。安全路线通常是：我的淘宝 → 官方客服、平台客服或客服小蜜。只引导进入官方客服页面；不要选择商家客服，不要读取聊天输入，不要代替用户输入或发送消息。进入客服页面后提示用户自行选择问题并结束引导。",
        packageName = TargetApps.TAOBAO,
    ),
    ALIPAY_TRANSIT(
        id = "alipay_transit",
        title = "支付宝：找到乘车码",
        controlTitle = "支付宝出行帮助",
        modelGoal = "帮助用户在支付宝找到出行或乘车码入口。遇到无关页面时，优先引导安全返回；不要建议付款、充值、授权或输入密码。进入出行页面后，只提示用户自行选择出行方式和地点。",
        packageName = TargetApps.ALIPAY,
    ),
    WECHAT_CONTACT(
        id = "wechat_contact",
        title = "微信：搜索联系人",
        controlTitle = "微信联系人帮助",
        modelGoal = "帮助用户在微信进入通讯录并找到右上角搜索入口。遇到聊天、发现或其他无关页面时，优先识别返回按钮或底部通讯录入口。进入搜索页面后，提示用户自行输入联系人姓名并结束引导；不要代替用户发送消息。",
        packageName = TargetApps.WECHAT,
    ),
    TAOBAO_AI(
        id = "taobao_ai",
        title = "淘宝：AI 通用帮助",
        controlTitle = "淘宝 AI 帮助",
        modelGoal = "在淘宝中帮助用户完成下面的自定义目标。每次只给一个安全步骤；页面不相关时引导返回。不要建议购买、付款、输入密码或验证码，也不要代替用户发送消息。",
        packageName = TargetApps.TAOBAO,
        isGeneric = true,
    ),
    ALIPAY_AI(
        id = "alipay_ai",
        title = "支付宝：AI 通用帮助",
        controlTitle = "支付宝 AI 帮助",
        modelGoal = "在支付宝中帮助用户完成下面的自定义目标。每次只给一个安全步骤；页面不相关时引导返回。遇到付款、转账、密码、验证码、授权或身份信息时必须暂停或停止。",
        packageName = TargetApps.ALIPAY,
        isGeneric = true,
    ),
    WECHAT_AI(
        id = "wechat_ai",
        title = "微信：AI 通用帮助",
        controlTitle = "微信 AI 帮助",
        modelGoal = "在微信中帮助用户完成下面的自定义目标。每次只给一个安全步骤；页面不相关时引导返回。不要读取聊天输入，不要代替用户输入或发送消息，涉及通讯录授权时必须先确认。",
        packageName = TargetApps.WECHAT,
        isGeneric = true,
    );

    companion object {
        fun fromId(id: String?): GuidanceTask? = entries.firstOrNull { it.id == id }
    }
}
