# EasyAccess 初赛交接验证记录（v0.9.0）

更新日期：2026-09-27
设备：Redmi 23049RAD8C
模型：阿里云百炼 `qwen3.8-max-0902`
部署：Android Debug APK + Render 免费 Web Service FastAPI

## 本版变化

- 后端已从阿里云函数计算迁移至 Render；2026-09-27 公网 `/healthz` 返回 `status=ok`，`/api/health` 返回 `mock_mode=false`、`model_configured=true`、`save_screenshots=false` 和模型 `qwen3.8-max-0902`。
- Android 通过 `android/local.properties` 读取云端地址和客户端令牌，真实值不进入 Git。
- 云函数 Python 3.10 部署包补充 `exceptiongroup` 依赖。
- Android 首页和悬浮面板完成第一轮视觉优化，版本升级为 `0.9.0`。
- 云端版提示不再要求“确认电脑后端运行”，改为网络/云端服务提示。

## 自动化与构建结果

| 项目 | 结果 |
| --- | --- |
| 后端 `pytest backend/tests -q` | 23 passed，2 条第三方弃用警告 |
| Web `npm run type-check` | 通过 |
| Web `npm run build` | 通过 |
| Android `assembleDebug` | 通过 |
| Android `lintDebug` | 通过 |
| Android v0.9.0 覆盖安装 | 成功 |

## 真机界面结构检查

- 首页标题、云端 AI 状态、语音入口、输入框和常用任务均正常显示。
- 主操作按钮在测试设备上的实际高度约 176 px，触控区域充足。
- 页面使用可滚动结构；首屏展示完整“开始帮助”和部分“常用操作”。
- 未发现文字乱码、控件重叠或按钮不可点击。

## 安全检查

- `.env`、`android/local.properties` 和 `artifacts/` 均被 Git 忽略。
- 百炼 API Key 仅保存于云函数环境变量。
- Android 只包含初赛用客户端令牌，不包含百炼 API Key。
- 默认不保存截图；App 不自动点击，不执行支付、转账、授权、删除或发送消息。

## 尚待真机完成的云端验收

1. 断开电脑和 ADB 后，使用手机网络完成一次通用 AI 任务。
2. 查看函数计算日志，确认请求成功且无截图正文、密钥或个人信息。
3. 重复关键任务至少三次，记录平均/P95 响应时间、首次结构成功率和修复率。
4. 验证网络断开、模型超时和额度不足时的提示与 Mock 备用方案。

本记录证明 v0.9.0 工程可构建并完成云端接线，不代表已达到规格书要求的正式统计指标。
