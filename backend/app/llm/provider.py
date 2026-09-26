import base64
import json
import logging
import time
from typing import Any, Dict, Optional

import httpx
from pydantic import ValidationError

from app.config import Settings
from app.schemas.models import AnalysisResponse
from app.services.risk_engine import apply_risk_override, evaluate_risk

from .prompts import SYSTEM_PROMPT, analysis_prompt, repair_prompt


logger = logging.getLogger("uvicorn.error")


class ModelProviderError(RuntimeError):
    """The provider could not return a usable response."""


class ModelTimeout(ModelProviderError):
    """The provider exceeded the configured timeout."""


class ModelOutputInvalid(ModelProviderError):
    """The provider returned invalid structured output twice."""


class OpenAICompatibleVisionProvider:
    """OpenAI-compatible adapter for Qwen-VL and similar vision models."""

    def __init__(self, settings: Settings, client: Optional[httpx.AsyncClient] = None) -> None:
        self.settings = settings
        self._client = client

    async def analyze(
        self,
        *,
        image_bytes: bytes,
        mime_type: str,
        session_id: str,
        task_text: str,
        mode: str,
    ) -> AnalysisResponse:
        image_data = base64.b64encode(image_bytes).decode("ascii")
        messages = [
            {"role": "system", "content": SYSTEM_PROMPT},
            {
                "role": "user",
                "content": [
                    {
                        "type": "image_url",
                        "image_url": {"url": f"data:{mime_type};base64,{image_data}"},
                    },
                    {
                        "type": "text",
                        "text": analysis_prompt(task_text, mode, session_id),
                    },
                ],
            },
        ]
        raw_output = await self._chat(messages, stage="analysis")
        try:
            result = self._validate(raw_output, session_id)
        except (json.JSONDecodeError, ValidationError, ValueError, TypeError) as exc:
            logger.info(
                "model_output_remote_repair_requested validation_type=%s",
                type(exc).__name__,
            )
            repaired = await self._chat(
                [
                    {"role": "system", "content": SYSTEM_PROMPT},
                    {"role": "user", "content": repair_prompt(raw_output, session_id)},
                ],
                stage="repair",
            )
            try:
                result = self._validate(repaired, session_id)
            except (json.JSONDecodeError, ValidationError, ValueError, TypeError) as exc:
                raise ModelOutputInvalid("模型输出连续两次未通过结构校验") from exc

        return self._apply_local_guards(result, task_text)

    async def _chat(self, messages: list[Dict[str, Any]], *, stage: str) -> str:
        endpoint = f"{self.settings.llm_base_url.rstrip('/')}/chat/completions"
        payload = {
            "model": self.settings.llm_model,
            "messages": messages,
            "temperature": 0,
            "max_tokens": self.settings.llm_max_output_tokens,
            "enable_thinking": self.settings.llm_enable_thinking,
            "response_format": {"type": "json_object"},
        }
        headers = {
            "Authorization": f"Bearer {self.settings.llm_api_key}",
            "Content-Type": "application/json",
        }
        owns_client = self._client is None
        timeout = httpx.Timeout(self.settings.llm_timeout_seconds, connect=10.0)
        client = self._client or httpx.AsyncClient(timeout=timeout)
        started_at = time.perf_counter()
        try:
            response = await client.post(endpoint, headers=headers, json=payload)
            response.raise_for_status()
            logger.info(
                "model_call_completed stage=%s model=%s status=%s elapsed_ms=%d",
                stage,
                self.settings.llm_model,
                response.status_code,
                int((time.perf_counter() - started_at) * 1000),
            )
            body = response.json()
            content = body["choices"][0]["message"]["content"]
            if isinstance(content, list):
                content = "".join(
                    str(item.get("text", "")) for item in content if isinstance(item, dict)
                )
            if not isinstance(content, str) or not content.strip():
                raise ModelProviderError("模型返回内容为空")
            return content.strip()
        except httpx.TimeoutException as exc:
            logger.warning(
                "model_call_timeout stage=%s model=%s timeout_type=%s elapsed_ms=%d",
                stage,
                self.settings.llm_model,
                type(exc).__name__,
                int((time.perf_counter() - started_at) * 1000),
            )
            raise ModelTimeout("模型响应超时") from exc
        except (httpx.HTTPError, KeyError, IndexError, TypeError, ValueError) as exc:
            raise ModelProviderError("模型服务暂时不可用") from exc
        finally:
            if owns_client:
                await client.aclose()

    @staticmethod
    def _extract_json(raw_output: str) -> Dict[str, Any]:
        text = raw_output.strip()
        if text.startswith("```"):
            first_newline = text.find("\n")
            last_fence = text.rfind("```")
            if first_newline >= 0 and last_fence > first_newline:
                text = text[first_newline + 1 : last_fence].strip()
        try:
            parsed = json.loads(text)
        except json.JSONDecodeError:
            start = text.find("{")
            end = text.rfind("}")
            if start < 0 or end <= start:
                raise
            parsed = json.loads(text[start : end + 1])
        if not isinstance(parsed, dict):
            raise ValueError("模型输出必须是 JSON 对象")
        return parsed

    def _validate(self, raw_output: str, session_id: str) -> AnalysisResponse:
        payload = self._extract_json(raw_output)
        before_normalization = json.dumps(
            payload,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        self._normalize_payload(payload)
        after_normalization = json.dumps(
            payload,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        if before_normalization != after_normalization:
            logger.info("model_output_locally_normalized")
        payload["session_id"] = session_id
        payload["analysis_source"] = "model"
        payload["model_name"] = self.settings.llm_model
        result = AnalysisResponse.model_validate(payload)
        target_id = result.recommended_action.element_id
        if result.recommended_action.action_type.value == "tap" and target_id not in {
            element.id for element in result.elements
        }:
            raise ValueError("tap 动作必须引用已识别元素")
        return result

    @staticmethod
    def _normalize_payload(payload: Dict[str, Any]) -> None:
        action_aliases = {
            "click": "tap",
            "press": "tap",
            "点击": "tap",
            "go_back": "back",
            "return": "back",
            "返回": "back",
            "finish": "stop",
            "done": "stop",
            "结束": "stop",
        }
        status_aliases = {
            "ongoing": "in_progress",
            "processing": "in_progress",
            "success": "completed",
            "done": "completed",
            "finished": "completed",
            "stopped": "blocked",
            "unknown": "uncertain",
        }
        risk_aliases = {
            "safe": "low",
            "none": "low",
            "moderate": "medium",
            "danger": "critical",
            "dangerous": "critical",
        }
        role_aliases = {
            "icon": "image",
            "navigation": "tab",
            "nav": "tab",
            "menu": "button",
            "label": "text",
        }

        page = payload.get("page")
        if isinstance(page, dict) and "confidence" in page:
            page["confidence"] = OpenAICompatibleVisionProvider._normalize_score(
                page["confidence"]
            )

        elements = payload.get("elements")
        if isinstance(elements, list):
            for element in elements:
                if not isinstance(element, dict):
                    continue
                role = element.get("role")
                if isinstance(role, str):
                    normalized_role = role.strip().lower()
                    element["role"] = role_aliases.get(normalized_role, normalized_role)
                for key in ("goal_relevance", "confidence"):
                    if key in element:
                        element[key] = OpenAICompatibleVisionProvider._normalize_score(
                            element[key]
                        )
                if "clickable" in element:
                    element["clickable"] = (
                        OpenAICompatibleVisionProvider._normalize_boolean(
                            element["clickable"]
                        )
                    )
                normalized_bbox = OpenAICompatibleVisionProvider._normalize_bbox(
                    element.get("bbox")
                )
                if normalized_bbox is not None:
                    element["bbox"] = normalized_bbox
                risk_hints = element.get("risk_hints")
                if isinstance(risk_hints, str):
                    element["risk_hints"] = [risk_hints]

        action = payload.get("recommended_action")
        if isinstance(action, dict):
            action_type = action.get("action_type")
            if isinstance(action_type, str):
                normalized_action = action_type.strip().lower()
                action["action_type"] = action_aliases.get(
                    normalized_action, normalized_action
                )
            element_id = action.get("element_id")
            if isinstance(element_id, str) and element_id.strip().lower() in {
                "",
                "null",
                "none",
            }:
                action["element_id"] = None
            if "confidence" in action:
                action["confidence"] = (
                    OpenAICompatibleVisionProvider._normalize_score(
                        action["confidence"]
                    )
                )

        risk = payload.get("risk")
        if isinstance(risk, dict):
            level = risk.get("level")
            if isinstance(level, str):
                normalized_risk = level.strip().lower()
                risk["level"] = risk_aliases.get(normalized_risk, normalized_risk)
            if "confirmation_required" in risk:
                risk["confirmation_required"] = (
                    OpenAICompatibleVisionProvider._normalize_boolean(
                        risk["confirmation_required"]
                    )
                )
            categories = risk.get("categories")
            if isinstance(categories, str):
                risk["categories"] = [categories]

        task_status = payload.get("task_status")
        if isinstance(task_status, str):
            normalized_status = task_status.strip().lower()
            payload["task_status"] = status_aliases.get(
                normalized_status, normalized_status
            )

    @staticmethod
    def _normalize_score(value: Any) -> Any:
        if isinstance(value, bool):
            return value
        if isinstance(value, str):
            text = value.strip()
            is_percent = text.endswith("%")
            if is_percent:
                text = text[:-1].strip()
            try:
                number = float(text)
            except ValueError:
                return value
            if is_percent or 1 < number <= 100:
                number /= 100
            return max(0.0, min(1.0, number))
        if isinstance(value, (int, float)):
            number = float(value)
            if 1 < number <= 100:
                number /= 100
            return max(0.0, min(1.0, number))
        return value

    @staticmethod
    def _normalize_boolean(value: Any) -> Any:
        if isinstance(value, str):
            normalized = value.strip().lower()
            if normalized in {"true", "yes", "1", "是"}:
                return True
            if normalized in {"false", "no", "0", "否"}:
                return False
        return value

    @staticmethod
    def _normalize_bbox(value: Any) -> Optional[list[float]]:
        if not isinstance(value, list) or len(value) != 4:
            return None
        numbers: list[float] = []
        for point in value:
            if isinstance(point, bool):
                return None
            try:
                numbers.append(float(point))
            except (TypeError, ValueError):
                return None
        if any(point > 1 for point in numbers) and all(
            0 <= point <= 100 for point in numbers
        ):
            numbers = [point / 100 for point in numbers]
        x1, y1, x2, y2 = numbers
        if not all(0 <= point <= 1 for point in numbers):
            return None
        if x2 <= x1 or y2 <= y1:
            return None
        return numbers

    def _apply_local_guards(
        self, result: AnalysisResponse, task_text: str
    ) -> AnalysisResponse:
        payload = result.model_dump(mode="python")
        user_goal_marker = "用户当前目标："
        user_goal = (
            task_text.split(user_goal_marker, 1)[1].strip()
            if user_goal_marker in task_text
            else task_text.strip()
        )
        target_id = result.recommended_action.element_id
        target_element = next(
            (element for element in result.elements if element.id == target_id),
            None,
        )
        evidence = [
            user_goal,
            result.recommended_action.instruction,
            result.recommended_action.expected_next_state,
        ]
        if target_element is not None:
            evidence.extend([target_element.text, *target_element.risk_hints])
        risk = evaluate_risk(evidence)
        payload["recommended_action"] = apply_risk_override(
            payload["recommended_action"], risk
        )
        payload["risk"] = {
            "level": risk["level"],
            "categories": risk["categories"],
            "confirmation_required": risk["confirmation_required"],
        }
        if risk["level"] == "critical":
            payload["task_status"] = "blocked"
        elif not risk["confirmation_required"] and min(
            result.page.confidence, result.recommended_action.confidence
        ) < self.settings.llm_low_confidence_threshold:
            payload["recommended_action"] = {
                "action_type": "ask_user",
                "element_id": None,
                "instruction": "我还不能确定当前页面，请停一下并提供更清晰、完整的页面截图。",
                "expected_next_state": "clearer_screenshot_required",
                "confidence": max(
                    result.page.confidence, result.recommended_action.confidence
                ),
            }
            payload["task_status"] = "uncertain"
        return AnalysisResponse.model_validate(payload)
