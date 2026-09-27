# EasyAccess Android 原型（v0.9.0）

这是 EasyAccess 的 Android 真机原型。它不会持续录屏，而是在用户主动开始帮助后读取无障碍控件；本机规则无法定位时，可经独立授权截取当前单帧屏幕，并调用云端或本地多模态后端返回一个安全的下一步。

## 当前范围

- 淘宝：`com.taobao.taobao`
- 支付宝：`com.eg.android.AlipayGphone`
- 微信：`com.tencent.mm`

当前版本监听窗口状态、窗口内容和滚动事件，在页面稳定后读取控件树。它只在内存中保存最近一次观察结果，不持久化页面内容。

为降低隐私风险，当前实现不会读取密码控件内容，也不会采集普通输入框内的文字。AI 界面识别默认关闭；开启前会明确告知截图将发送到 EasyAccess 多模态后端。后端默认不保存截图，百炼 API Key 不会写入 APK。

## 首次运行

1. 使用 Android Studio 打开 `android/` 目录。
2. 等待 Gradle 同步完成。
3. 在 Android 8.0 或更高版本的真机/模拟器上运行 `app`。
4. 点击“打开无障碍设置”。
5. 找到“EasyAccess 界面辅助”并开启。
6. 按需开启“本机视觉识别”和“AI 界面识别”。
7. 选择快捷任务，或输入/说出包含淘宝、支付宝或微信的自定义目标。

## 配置后端

复制 `android/local.properties.example` 中的字段到本机 `android/local.properties`：

```properties
sdk.dir=你的Android SDK路径
EASYACCESS_API_BASE_URL=https://easy-access-api.onrender.com/api
EASYACCESS_API_TOKEN=与云端相同的客户端令牌
```

也可在本地开发时把地址设置为 `http://127.0.0.1:8000/api`，并通过 `adb reverse tcp:8000 tcp:8000` 连接电脑后端。

`local.properties` 被 Git 忽略。不要把云端域名、令牌、API Key 或该文件截图提交到仓库和聊天记录。

## 构建和安装

```powershell
$env:JAVA_HOME = '你的 JDK 17 路径'
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

首次安装后开启无障碍服务。以后只要手机能访问云端 HTTPS 地址，就不需要连接开发电脑；ADB 只用于安装、日志和开发调试。

## 已验证能力

- 检测三个目标 App 和页面切换。
- 读取可访问控件或使用本机 OCR，必要时调用多模态模型。
- 显示高对比高亮、单步文字和语音提示。
- 支持四个稳定快捷任务及淘宝、支付宝、微信的通用 AI 目标。
- 页面变化后清除旧结果并重新观察。
- 对转账、验证码等严重风险停止引导。

## 当前限制

- 不会自动点击任何第三方 App。
- 当前是 Debug APK，尚未完成正式签名和上架准备。
- 固定客户端令牌可能从 APK 中被提取，仅适合初赛小范围演示。
- 模型坐标可能存在偏差，第三方界面变化或入口不在可见区域时可能需要重试。
- 正式比赛仍需保留 Web Mock 和录屏备用，不能依赖真实第三方账号与网络。

## 安全边界

EasyAccess 只做页面观察、解释、高亮和语音提示，不执行付款、转账、发送消息、删除、授权等动作。支付、密码、验证码、身份信息和高风险隐私授权由本地规则强制确认或停止。