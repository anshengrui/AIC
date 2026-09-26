# EasyAccess 多模态模型接入

当前后端已经支持 OpenAI 兼容的多模态 Chat Completions 接口。默认仍为 `MOCK_MODE=true`，因此没有 API Key 也能完整演示原有闭环。

## 切换到阿里云百炼

1. 在项目根目录复制 `.env.example` 为 `.env`。
2. 只在本机 `.env` 中填写百炼 API Key；不要把密钥写进安卓 APK、源码、截图或聊天记录。
3. 设置 `MOCK_MODE=false`。
4. 重启 FastAPI 后访问 `GET /api/health`，确认：

```json
{
  "status": "ok",
  "mock_mode": false,
  "model_configured": true,
  "save_screenshots": false,
  "analysis_mode": "model",
  "model_name": "qwen3.8-max-0902"
}
```

默认配置使用：

```dotenv
LLM_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
LLM_MODEL=qwen3.8-max-0902
LLM_ENABLE_THINKING=false
MOCK_MODE=false
```

也可以替换为其他 OpenAI 兼容的多模态模型，只需修改 `LLM_BASE_URL`、`LLM_MODEL` 和 `LLM_API_KEY`，业务接口不变。

## 实际处理链路

1. 后端验证 PNG/JPG 类型、文件签名和 8 MB 大小上限。
2. 图片仅在本次请求内转为 Base64 Data URL；默认不落盘。
3. 模型返回页面、元素、归一化坐标、下一步和置信度。
4. Pydantic 按统一 Schema 校验；失败时只进行一次结构修复请求。
5. 本地风险规则覆盖模型结论。转账、验证码等严重风险会强制停止。
6. 页面或动作置信度低于 0.60 时，不显示猜测位置，改为请求更清晰截图。
7. 界面定位默认关闭深度思考，避免高强度推理增加等待时间和 Token；需要复杂推理时再单独开启。

## 演示原则

- 网页原型顶部显示“离线 Mock 模式”或“多模态 AI · 模型名”。
- 每次分析结果显示“Mock演示”或“AI视觉分析”，避免把固定脚本冒充模型结果。
- 无网络、额度不足或模型异常时保留 Mock 演示入口，但两种结果必须明确区分。
- 真实截图验证尚需有效 API Key；在此之前，自动化测试使用本地模拟的模型响应，不产生费用。
