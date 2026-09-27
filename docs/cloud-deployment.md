# EasyAccess 云端后端部署（阿里云函数计算）

本方案将 Android 客户端、EasyAccess FastAPI 服务和百炼模型分离。百炼 API Key 只保存在云端，不能写入 APK 或提交到 Git。

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

## 二、函数计算建议配置

- 地域：华北 2（北京）
- 类型：Web 服务 / Web 函数
- 运行端口：`9000`
- 健康检查路径：`/healthz`
- CPU：先使用 1 vCPU
- 内存：先使用 1 GB
- 超时时间：60 秒以上
- 最小实例数：开发和普通测试阶段设为 `0`
- 最大实例数：初赛阶段限制为 `2`

可以选择以下任一部署方式：

1. 连接 GitHub 仓库并使用根目录 `Dockerfile` 构建；
2. 上传 `backend` 代码包，启动命令设置为 `python3 server.py`。

若使用代码包，必须把 `backend/requirements.txt` 中的依赖一并安装到代码包，或使用平台的自动依赖安装功能。

## 三、云端环境变量

```text
APP_ENV=production
EASYACCESS_API_TOKEN=<随机长字符串>
LLM_API_KEY=<百炼 API Key>
LLM_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
LLM_MODEL=qwen3.8-flash
LLM_TIMEOUT_SECONDS=60
LLM_MAX_OUTPUT_TOKENS=800
LLM_ENABLE_THINKING=false
LLM_LOW_CONFIDENCE_THRESHOLD=0.60
MAX_IMAGE_MB=8
SAVE_SCREENSHOTS=false
MOCK_MODE=false
PORT=9000
```

`EASYACCESS_API_TOKEN` 建议使用至少 32 字节的随机值。它用于减少公开测试地址被随意调用的风险，但放入 APK 的固定令牌仍可能被提取，因此仅适合初赛小范围演示。正式发布时应改用用户登录和短期令牌。

## 四、部署后验证

假设平台提供的 HTTPS 地址是 `https://example.fcapp.run`：

```powershell
Invoke-RestMethod https://example.fcapp.run/healthz
Invoke-RestMethod https://example.fcapp.run/api/health
```

健康接口不需要令牌，也不会返回模型 API Key。业务接口在设置了 `EASYACCESS_API_TOKEN` 后必须携带 `X-EasyAccess-Key` 请求头。

## 五、生成连接云端的 APK

把下列内容加入本机的 `android/local.properties`：

```text
EASYACCESS_API_BASE_URL=https://example.fcapp.run/api
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
5. 在函数计算日志中确认请求成功，并确认日志没有保存截图或密钥；
6. 查看费用与用量，确认最小实例数仍为 `0`。

正式演示前若冷启动影响体验，可临时把最小实例数调整为 `1`；演示结束后立即改回 `0`，否则会持续产生费用。
