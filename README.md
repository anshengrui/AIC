# EasyAccess 易屏

EasyAccess 是面向数字操作无障碍场景的逐步导航助手。当前版本同时提供离线 Mock 闭环和可配置的多模态模型分析：

1. 选择辅助模式，输入任务并上传 PNG/JPG 截图；
2. FastAPI 返回经过 Schema 校验的页面观察与下一步动作；
3. Vue 前端根据归一化坐标在截图上绘制高亮；
4. 用户提交“已完成 / 没找到 / 页面不一样 / 我点错了”反馈，系统更新会话并给出下一步。

> 项目范围、数据契约和验收标准以根目录 `EasyAccess_项目交接与执行规格书_v1.0.pdf` 为唯一事实来源。

## 当前状态

- 已完成：Mock 闭环、OpenAI 兼容多模态 Provider、Android 实时界面感知、逐步高亮、语音提示、安全拦截和通用 AI 任务。
- 当前 Android 测试版：`0.9.0`，已完成第一轮首页与悬浮面板视觉优化。
- 当前真实模型：阿里云百炼 `qwen3.8-max-0902`；保留 `MOCK_MODE=true` 的离线演示能力。
- FastAPI 后端已迁移到 Render 免费 Web Service；2026-09-27 已验证公网 `/healthz` 和 `/api/health` 正常。客户端令牌仍不进入仓库。
- 四个固定流程、两个通用 AI 任务、安全拦截和页面切换续接已完成一轮真机验收。
- 本机已使用 Python 3.12 创建 `.venv`；系统默认 `python` 仍可能指向旧版，请按下方命令激活虚拟环境。
- 2026-09-27 交接基线：后端测试 23 项通过；Android Debug APK 构建与 Lint 通过；前端类型检查和生产构建通过。

新成员或新的开发模型请先阅读根目录 `AGENTS.md` 和 `docs/TEAM_HANDOFF.md`。初赛仍需补齐的材料与实验见 `docs/PRELIMINARY_SUBMISSION_CHECKLIST.md`。

## Windows 安装

### 后端

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r backend\requirements.txt
Copy-Item .env.example .env
python -m uvicorn app.main:app --reload --app-dir backend
```

健康检查：<http://127.0.0.1:8000/api/health>

### 前端

另开一个 PowerShell：

```powershell
Set-Location frontend
npm install
npm run dev
```

前端地址：<http://127.0.0.1:5173>

Vite 会把 `/api` 代理到 `http://127.0.0.1:8000`。

真实模型的配置和安全链路见 `docs/model-integration.md`。API Key 只能填写在本机 `.env` 中，不能写入源码或 APK。

## Mock 演示步骤

1. 启动前后端，打开前端。
2. 保持任务“帮我关闭自动续费”，点击“载入内置演示图”。
3. 点击“开始识别”，确认截图中的“会员中心”区域出现高亮框。
4. 依次尝试四个反馈按钮，观察右侧步骤说明和会话状态更新。
5. “页面不一样”和“我点错了”会记录偏航并返回重新观察提示。

## 测试

```powershell
python -m unittest discover -s backend\tests -p "test_*.py"
Set-Location frontend
npm run type-check
npm run build
```

安装后端开发依赖后还可运行：

```powershell
python -m pytest backend\tests
```

当前验证基线：后端 `23 passed`；Android `assembleDebug` 与 `lintDebug` 通过；前端 `vue-tsc` 与 Vite 生产构建通过。

## 安全与隐私

- API 密钥只允许从环境变量读取，仓库不提交 `.env`。
- Mock 模式不调用外部模型。
- 默认不保存截图；当前内存会话存储会在后端重启后清空。
- 风险规则优先于 Mock/模型建议；中高风险动作不得直接执行。
- 本项目只提供引导，不接管手机、不模拟自动点击。

## 文档

- `docs/progress.md`：21 天进度与当前风险
- `docs/api.md`：首个里程碑 API 契约
- `docs/test-plan.md`：测试范围和验收方式
- `docs/model-integration.md`：真实多模态模型配置、安全与降级
- `docs/cloud-deployment.md`：Render / 阿里云部署与 Android 云端连接
- `docs/preliminary-scope.md`：初赛三层功能范围
- `docs/preliminary-test-report-v0.8.6.md`：当前 Android 真机验收结果、性能与限制
- `docs/preliminary-test-report-v0.9.0.md`：云端接线、构建和新版界面交接验证
- `docs/TEAM_HANDOFF.md`：队友接手所需的完整项目说明、真实进度和优先级
- `docs/PRELIMINARY_SUBMISSION_CHECKLIST.md`：初赛材料、数据、实验、视频和 PPT 清单
- `submission/README.md`：最终提交包的目录、命名与安全规则
