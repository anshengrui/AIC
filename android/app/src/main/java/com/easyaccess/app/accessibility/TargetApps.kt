package com.easyaccess.app.accessibility

object TargetApps {
    const val TAOBAO = "com.taobao.taobao"
    const val ALIPAY = "com.eg.android.AlipayGphone"
    const val WECHAT = "com.tencent.mm"

    val names = mapOf(
        TAOBAO to "淘宝",
        ALIPAY to "支付宝",
        WECHAT to "微信",
    )

    val highlightKeywords = mapOf(
        TAOBAO to listOf("我的淘宝", "购物车", "待收货", "查看物流"),
        ALIPAY to listOf("出行", "乘车码", "扫一扫", "首页"),
        WECHAT to listOf("搜索", "通讯录", "微信", "发现", "我"),
    )

    fun displayName(packageName: String): String = names[packageName] ?: packageName
}
