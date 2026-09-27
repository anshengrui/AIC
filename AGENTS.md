# EasyAccess 协作与接管规则

## 事实来源与阅读顺序

1. 若本地存在 `EasyAccess_项目交接与执行规格书_v1.0.pdf`，先完整阅读；它是产品定位、数据契约、验收标准和安全边界的单一事实来源。
2. 再阅读 `docs/TEAM_HANDOFF.md`，了解仓库实际进展、已验证结果、架构偏差和下一步优先级。
3. 再阅读 `docs/progress.md`、`docs/PRELIMINARY_SUBMISSION_CHECKLIST.md` 和相关模块文档。
4. PDF 因竞赛资料属性默认不进入公开仓库；缺失时向项目负责人索取，不要自行猜测其要求。

## 不可破坏的边界

- EasyAccess 只观察、解释、高亮和播报，不自动点击，不代替支付、转账、授权、删除或发送消息。
- 所有模型输出必须经过结构化校验；中高风险动作由本地规则覆盖模型建议。
- 不提交 `.env`、`android/local.properties`、API Key、客户端令牌、真实用户截图、订单/聊天/支付信息。
- 不把固定规则冒充成模型能力，不声称已经训练或微调模型。当前实现是多模态模型 API + Prompt/Schema + 确定性规则。
- 默认不保存截图；新增日志时仅记录耗时、错误类型和匿名状态，不记录页面正文或密钥。

## 当前工程入口

- Web：`frontend/`，Vue 3 + TypeScript + Vite。
- API：`backend/`，FastAPI + Pydantic；`MOCK_MODE=true` 可离线演示。
- Android：`android/`，Kotlin；通过无障碍服务观察界面并显示高亮/语音提示。
- 云端：阿里云函数计算运行 `backend/server.py`，端口 `9000`；配置见 `docs/cloud-deployment.md`。

## 修改与验证规则

- 修改前先读相关实现和文档，保留用户已有改动。
- 一次只推进一个清晰里程碑，优先完成 `docs/TEAM_HANDOFF.md` 中的 P0 项。
- 每次改动后运行相应测试，并更新 `README.md`、`docs/progress.md` 或测试报告。
- `main` 必须保持可构建；功能开发使用短分支并通过 Pull Request 合并。
- 遇到规格书与实现冲突时，记录差异并选择改动最小、可验收且安全的方案。

## 基线验证命令

```powershell
.\.venv\Scripts\python.exe -m pytest backend\tests -q
& 'C:\Program Files\nodejs\npm.cmd' --prefix frontend run type-check
& 'C:\Program Files\nodejs\npm.cmd' --prefix frontend run build
$env:JAVA_HOME = 'E:\Android\Jdk17'
.\android\gradlew.bat --gradle-user-home D:\EasyAccessGradleCache -p .\android :app:assembleDebug :app:lintDebug --no-daemon --max-workers=1 --no-build-cache
```

路径因电脑而异时，只调整本地 SDK/JDK/Gradle 路径，不把个人绝对路径写入源码。
