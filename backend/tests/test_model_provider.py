import json
import os
import sys
import unittest

import httpx


BACKEND_ROOT = os.path.dirname(os.path.dirname(__file__))
if BACKEND_ROOT not in sys.path:
    sys.path.insert(0, BACKEND_ROOT)

from app.config import Settings  # noqa: E402
from app.llm import ModelOutputInvalid, OpenAICompatibleVisionProvider  # noqa: E402


def model_payload(
    *,
    page_confidence: float = 0.95,
    action_confidence: float = 0.92,
) -> dict:
    return {
        "session_id": "model-invented-id",
        "page": {
            "page_type": "orders",
            "title": "我的订单",
            "summary": "页面包含待收货入口",
            "confidence": page_confidence,
        },
        "elements": [
            {
                "id": "e1",
                "text": "待收货",
                "role": "tab",
                "bbox": [0.52, 0.12, 0.68, 0.20],
                "clickable": True,
                "goal_relevance": 0.97,
                "confidence": 0.94,
                "risk_hints": [],
            }
        ],
        "recommended_action": {
            "action_type": "tap",
            "element_id": "e1",
            "instruction": "请点击页面上方的“待收货”。",
            "expected_next_state": "pending_receipt_orders",
            "confidence": action_confidence,
        },
        "risk": {
            "level": "low",
            "categories": [],
            "confirmation_required": False,
        },
        "task_status": "in_progress",
    }


def provider_settings() -> Settings:
    return Settings(
        llm_api_key="test-key",
        llm_base_url="https://model.example/v1",
        llm_model="vision-test",
        mock_mode=False,
    )


class ModelProviderTests(unittest.IsolatedAsyncioTestCase):
    async def test_valid_model_result_is_tagged_and_session_id_is_authoritative(self):
        seen_body = {}

        def handler(request: httpx.Request) -> httpx.Response:
            seen_body.update(json.loads(request.content))
            content = "```json\n" + json.dumps(model_payload(), ensure_ascii=False) + "\n```"
            return httpx.Response(
                200,
                json={"choices": [{"message": {"content": content}}]},
            )

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            result = await OpenAICompatibleVisionProvider(
                provider_settings(), client
            ).analyze(
                image_bytes=b"image-bytes",
                mime_type="image/png",
                session_id="trusted-session",
                task_text="帮我查看淘宝物流",
                mode="senior",
            )

        self.assertEqual("trusted-session", result.session_id)
        self.assertEqual("model", result.analysis_source)
        self.assertEqual("vision-test", result.model_name)
        image_url = seen_body["messages"][1]["content"][0]["image_url"]["url"]
        self.assertTrue(image_url.startswith("data:image/png;base64,"))
        self.assertFalse(seen_body["enable_thinking"])
        self.assertEqual({"type": "json_object"}, seen_body["response_format"])

    async def test_invalid_first_output_gets_one_repair_attempt(self):
        calls = 0

        def handler(_: httpx.Request) -> httpx.Response:
            nonlocal calls
            calls += 1
            content = "not-json" if calls == 1 else json.dumps(model_payload())
            return httpx.Response(
                200,
                json={"choices": [{"message": {"content": content}}]},
            )

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            result = await OpenAICompatibleVisionProvider(
                provider_settings(), client
            ).analyze(
                image_bytes=b"image-bytes",
                mime_type="image/jpeg",
                session_id="s1",
                task_text="查看物流",
                mode="standard",
            )

        self.assertEqual(2, calls)
        self.assertEqual("待收货", result.elements[0].text)

    async def test_minor_format_variations_are_repaired_locally(self):
        calls = 0
        payload = model_payload()
        payload["page"]["confidence"] = "95%"
        payload["elements"][0]["role"] = "navigation"
        payload["elements"][0]["bbox"] = ["52", "12", "68", "20"]
        payload["elements"][0]["clickable"] = "true"
        payload["elements"][0]["goal_relevance"] = "97%"
        payload["elements"][0]["confidence"] = "94%"
        payload["recommended_action"]["action_type"] = "click"
        payload["recommended_action"]["confidence"] = "92%"
        payload["risk"]["level"] = "safe"
        payload["risk"]["confirmation_required"] = "false"
        payload["task_status"] = "ongoing"

        def handler(_: httpx.Request) -> httpx.Response:
            nonlocal calls
            calls += 1
            return httpx.Response(
                200,
                json={"choices": [{"message": {"content": json.dumps(payload)}}]},
            )

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            result = await OpenAICompatibleVisionProvider(
                provider_settings(), client
            ).analyze(
                image_bytes=b"image-bytes",
                mime_type="image/png",
                session_id="s1",
                task_text="查看物流",
                mode="senior",
            )

        self.assertEqual(1, calls)
        self.assertEqual("tap", result.recommended_action.action_type.value)
        self.assertEqual("tab", result.elements[0].role.value)
        self.assertEqual([0.52, 0.12, 0.68, 0.2], result.elements[0].bbox)
        self.assertEqual("low", result.risk.level.value)

    async def test_two_invalid_outputs_are_rejected(self):
        def handler(_: httpx.Request) -> httpx.Response:
            return httpx.Response(
                200,
                json={"choices": [{"message": {"content": "still-not-json"}}]},
            )

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            with self.assertRaises(ModelOutputInvalid):
                await OpenAICompatibleVisionProvider(
                    provider_settings(), client
                ).analyze(
                    image_bytes=b"image-bytes",
                    mime_type="image/png",
                    session_id="s1",
                    task_text="查看物流",
                    mode="standard",
                )

    async def test_local_risk_rule_overrides_model_action(self):
        def handler(_: httpx.Request) -> httpx.Response:
            return httpx.Response(
                200,
                json={
                    "choices": [
                        {"message": {"content": json.dumps(model_payload())}}
                    ]
                },
            )

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            result = await OpenAICompatibleVisionProvider(
                provider_settings(), client
            ).analyze(
                image_bytes=b"image-bytes",
                mime_type="image/png",
                session_id="s1",
                task_text="帮我向陌生账户转账并输入验证码",
                mode="standard",
            )

        self.assertEqual("critical", result.risk.level.value)
        self.assertEqual("stop", result.recommended_action.action_type.value)
        self.assertEqual("blocked", result.task_status.value)

    async def test_safety_template_and_unrelated_page_text_do_not_trigger_risk(self):
        payload = model_payload()
        payload["page"] = {
            "page_type": "profile",
            "title": "我的淘宝",
            "summary": "页面上还有隐私设置和账户安全入口",
            "confidence": 0.96,
        }
        payload["elements"] = [
            {
                "id": "favorite",
                "text": "收藏",
                "role": "button",
                "bbox": [0.12, 0.30, 0.36, 0.40],
                "clickable": True,
                "goal_relevance": 0.98,
                "confidence": 0.96,
                "risk_hints": [],
            },
            {
                "id": "unrelated",
                "text": "身份信息与支付密码",
                "role": "button",
                "bbox": [0.12, 0.50, 0.55, 0.60],
                "clickable": True,
                "goal_relevance": 0.05,
                "confidence": 0.95,
                "risk_hints": ["privacy"],
            },
        ]
        payload["recommended_action"] = {
            "action_type": "tap",
            "element_id": "favorite",
            "instruction": "请点击“收藏”。",
            "expected_next_state": "favorites",
            "confidence": 0.95,
        }

        def handler(_: httpx.Request) -> httpx.Response:
            return httpx.Response(
                200,
                json={"choices": [{"message": {"content": json.dumps(payload)}}]},
            )

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            result = await OpenAICompatibleVisionProvider(
                provider_settings(), client
            ).analyze(
                image_bytes=b"image-bytes",
                mime_type="image/png",
                session_id="s1",
                task_text=(
                    "每次只给一个安全步骤；不要付款、输入密码或验证码。\n"
                    "用户当前目标：帮我在淘宝查看我的收藏"
                ),
                mode="senior",
            )

        self.assertEqual("low", result.risk.level.value)
        self.assertEqual("tap", result.recommended_action.action_type.value)
        self.assertEqual("favorite", result.recommended_action.element_id)

    async def test_low_confidence_degrades_to_ask_user(self):
        low_confidence = model_payload(page_confidence=0.42)

        def handler(_: httpx.Request) -> httpx.Response:
            return httpx.Response(
                200,
                json={"choices": [{"message": {"content": json.dumps(low_confidence)}}]},
            )

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            result = await OpenAICompatibleVisionProvider(
                provider_settings(), client
            ).analyze(
                image_bytes=b"image-bytes",
                mime_type="image/png",
                session_id="s1",
                task_text="查看物流",
                mode="standard",
            )

        self.assertEqual("ask_user", result.recommended_action.action_type.value)
        self.assertEqual("uncertain", result.task_status.value)


if __name__ == "__main__":
    unittest.main()
