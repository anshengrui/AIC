# Mock 核心闭环 API

基础地址：`http://127.0.0.1:8000/api`

## GET `/health`

返回服务状态、Mock 模式、模型是否已配置、当前分析模式和模型名称，不返回密钥。

## POST `/sessions`

```json
{
  "task_text": "帮我关闭自动续费",
  "mode": "senior"
}
```

`mode`：`standard | senior | low_vision_voice`。

## POST `/sessions/{id}/analyze`

使用 `multipart/form-data` 上传：

- `image`：PNG/JPG，大小不超过 `MAX_IMAGE_MB`

Mock 模式不保存图片，只校验媒体类型、大小和存在性。

当 `MOCK_MODE=false` 且模型配置完整时，图片会在内存中编码后发送给多模态模型，并返回：

- `analysis_source=model`
- `model_name`
- 通过 Schema 校验的页面、元素、坐标、动作、风险和任务状态

无效结构只修复重试一次；仍失败时返回 `MODEL_OUTPUT_INVALID`。模型低置信度时返回 `ask_user`，不会高亮猜测位置。

## POST `/sessions/{id}/feedback`

```json
{
  "feedback": "unexpected_page"
}
```

允许值：`done | not_found | unexpected_page | wrong_action | cancel`。

## GET `/sessions/{id}`

返回任务、模式、状态、步骤号、偏航次数、最近观察结果与状态历史。

## POST `/sessions/{id}/confirm`

```json
{
  "approved": false
}
```

仅当状态为 `WAITING_CONFIRMATION` 时有效。

## GET `/sessions/{id}/report`

仅完成态会返回完整摘要；未完成时返回当前进度报告。

## 统一错误结构

```json
{
  "detail": {
    "code": "IMAGE_INVALID",
    "message": "仅支持 PNG/JPG 图片"
  }
}
```

稳定错误码：`IMAGE_INVALID`、`MODEL_NOT_CONFIGURED`、`MODEL_TIMEOUT`、`MODEL_OUTPUT_INVALID`、`MODEL_UNAVAILABLE`、`LOW_CONFIDENCE`、`RISK_CONFIRMATION_REQUIRED`、`SESSION_STATE_CONFLICT`、`SESSION_NOT_FOUND`。
