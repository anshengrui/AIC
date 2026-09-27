# EasyAccess 云端后端部署（Render 主用 / 阿里云备选）

本方案将 Android 客户端、EasyAccess FastAPI 服务和百炼模型分离。百炼 API Key 只保存在云端，不能写入 APK 或提交到 Git。

当前状态（2026-09-27）：FastAPI 已迁移到 Render 免费 Web Service，公开地址为 `https://easy-access-api.onrender.com`。公网 `/healthz` 已返回 `status=ok`，`/api/health` 已确认真实模型 `qwen3.8-max-0902` 配置成功、`MOCK_MODE=false`、`SAVE_SCREENSHOTS=false`。百炼 API Key 和客户端令牌仍只保存在 Render 环境变量及开发者本机，不进入仓库。

## 一、部署前验证

在项目根目录执行：

```powershell
.\.venv\Scripts\python.exe -m pytest backend\tests
.\.venv\Scripts\python.exe backend\server.py
```

本机访问 `http://127.0.0.1:9000/healthz`，应返回 `{"status":"ok"}`。

如果电脑安装了 Docker，还可以验证容器：

```powershell
docker build -t easyaccess-api .
docker run --rm -p 9000:9000 --env-file .env easyaccess-api
```

## 二、Render 免费 Web Service（当前主用）

在 Render 创建 `Web Service`，连接本仓库或使用公开 Git 地址，配置如下：

- Language：`Docker`
- Branch：`main`
- Root Directory：留空
- Docker Build Context Directory：`.`
- Dockerfile Path：`./Dockerfile`
- Health Check Path：`/healthz`
- Instance Type：`Free`
- Auto-Deploy：如果通过 GitHub App 连接仓库则选 `On Commit`；通过 `Public Git Repository` URL 创建时，后续代码更新需要在 Render 手动执行 `Manual Deploy -> Deploy latest commit`
- Docker Command、Pre-Deploy Command、Build Filters：留空

Render 会自动注入 `PORT`，无需手动设置。免费实例连续 15 分钟没有入站请求时会休眠；下次请求会自动唤醒，冷启动可能需要约一分钟，并不需要用户每 15 分钟手动刷新。正式演示前 1～2 分钟访问一次 `/healthz` 即可预热。

### 阿里云函数计算备选

如果之后恢复阿里云函数计算，可以继续使用根目录 `Dockerfile`，或上传 `artifacts/easyaccess-fc-python310.zip`。部署包由以下命令重新生成：

```powershell
.\scripts\build-fc-package.ps1
```

脚本按照 Linux CPython 3.10 安装 `backend/requirements-fc.txt`。不要直接把 Windows 本机的 `site-packages` 压缩上传。

## 三、云端环境变量

```text
APP_ENV=production
EASYACCESS_API_TOKEN=<随机长字符串>
LLM_API_KEY=<百炼 API Key>
LLM_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
LLM_MODEL=qwen3.8-max-0902
LLM_TIMEOUT_SECONDS=60
LLM_MAX_OUTPUT_TOKENS=800
LLM_ENABLE_THINKING=false
LLM_LOW_CONFIDENCE_THRESHOLD=0.60
MAX_IMAGE_MB=8
SAVE_SCREENSHOTS=false
MOCK_MODE=false
```

`EASYACCESS_API_TOKEN` 建议使用至少 32 字节的随机值。它用于减少公开测试地址被随意调用的风险，但放入 APK 的固定令牌仍可能被提取，因此仅适合初赛小范围演示。正式发布时应改用用户登录和短期令牌。

## 四、部署后验证

当前 Render HTTPS 地址：

```powershell
Invoke-RestMethod https://easy-access-api.onrender.com/healthz
Invoke-RestMethod https://easy-access-api.onrender.com/api/health
```

健康接口不需要令牌，也不会返回模型 API Key。业务接口在设置了 `EASYACCESS_API_TOKEN` 后必须携带 `X-EasyAccess-Key` 请求头。

## 五、生成连接云端的 APK

把下列内容加入本机的 `android/local.properties`：

```text
EASYACCESS_API_BASE_URL=https://easy-access-api.onrender.com/api
EASYACCESS_API_TOKEN=<与云端相同的随机长字符串>
```

然后重新构建 APK：

```powershell
Set-Location android
.\gradlew.bat assembleDebug
```

不要提交 `android/local.properties`。该文件已经被 Android 工程的 `.gitignore` 排除。

## 六、真机验收

1. 关闭电脑上的 FastAPI 服务；
2. 关闭 USB 调试或断开电脑；
3. 手机切换到移动网络；
4. 启动 EasyAccess 并执行一次 AI 识别；
5. 在 Render Logs 中确认请求成功，并确认日志没有保存截图或密钥；
6. 查看 Render 使用量，确认服务仍为 `Free` 实例。

正式演示前若担心冷启动，提前访问一次 `/healthz` 并保持演示期间有正常请求即可。不要使用高频自动刷新来规避平台休眠规则。
