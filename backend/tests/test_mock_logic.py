import os
import sys
import unittest


BACKEND_ROOT = os.path.dirname(os.path.dirname(__file__))
if BACKEND_ROOT not in sys.path:
    sys.path.insert(0, BACKEND_ROOT)

from app.services.mock_engine import build_mock_analysis, is_valid_bbox  # noqa: E402
from app.services.risk_engine import apply_risk_override, evaluate_risk  # noqa: E402


class BBoxTests(unittest.TestCase):
    def test_accepts_normalized_positive_area_box(self):
        self.assertTrue(is_valid_bbox([0.1, 0.2, 0.8, 0.9]))

    def test_rejects_out_of_range_or_zero_area_box(self):
        self.assertFalse(is_valid_bbox([-0.1, 0.2, 0.8, 0.9]))
        self.assertFalse(is_valid_bbox([0.1, 0.2, 0.1, 0.9]))


class RiskEngineTests(unittest.TestCase):
    def test_low_risk_navigation_is_not_blocked(self):
        risk = evaluate_risk(["打开会员中心"])
        self.assertEqual("low", risk["level"])
        self.assertFalse(risk["confirmation_required"])

    def test_sensitive_data_requires_confirmation(self):
        risk = evaluate_risk(["允许读取通讯录"])
        self.assertEqual("high", risk["level"])
        self.assertTrue(risk["confirmation_required"])

    def test_transfer_is_stopped(self):
        risk = evaluate_risk(["向陌生账户转账并提供验证码"])
        action = apply_risk_override(
            {
                "action_type": "tap",
                "element_id": "e1",
                "instruction": "继续",
                "expected_next_state": "payment",
                "confidence": 0.9,
            },
            risk,
        )
        self.assertEqual("critical", risk["level"])
        self.assertEqual("stop", action["action_type"])
        self.assertIsNone(action["element_id"])

    def test_payment_password_is_stopped(self):
        risk = evaluate_risk(["请输入支付密码并确认付款"])
        self.assertEqual("critical", risk["level"])
        self.assertFalse(risk["confirmation_required"])
        action = apply_risk_override(
            {
                "action_type": "tap",
                "element_id": "pay",
                "instruction": "确认付款",
                "expected_next_state": "payment",
                "confidence": 0.99,
            },
            risk,
        )
        self.assertEqual("stop", action["action_type"])

    def test_sending_message_requires_confirmation(self):
        risk = evaluate_risk(["点击发送消息"])
        self.assertEqual("high", risk["level"])
        self.assertTrue(risk["confirmation_required"])


class MockEngineTests(unittest.TestCase):
    def test_first_step_has_schema_shaped_result_and_valid_bbox(self):
        result = build_mock_analysis("session-1", "帮我关闭自动续费", "standard", 1)
        self.assertEqual("profile_center", result["page"]["page_type"])
        self.assertEqual("会员中心", result["elements"][0]["text"])
        self.assertTrue(is_valid_bbox(result["elements"][0]["bbox"]))
        self.assertEqual("tap", result["recommended_action"]["action_type"])

    def test_modes_produce_different_guidance(self):
        standard = build_mock_analysis("s", "关闭自动续费", "standard", 1)
        senior = build_mock_analysis("s", "关闭自动续费", "senior", 1)
        low_vision = build_mock_analysis("s", "关闭自动续费", "low_vision_voice", 1)
        instructions = {
            standard["recommended_action"]["instruction"],
            senior["recommended_action"]["instruction"],
            low_vision["recommended_action"]["instruction"],
        }
        self.assertEqual(3, len(instructions))

    def test_not_found_feedback_adds_position_detail(self):
        result = build_mock_analysis("s", "关闭自动续费", "standard", 1, "not_found")
        self.assertIn("中部偏上", result["recommended_action"]["instruction"])


if __name__ == "__main__":
    unittest.main()
