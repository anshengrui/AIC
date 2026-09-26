from typing import Dict, Iterable, List


RISK_RULES = {
    "critical": {
        "terms": (
            "转账",
            "汇款",
            "验证码",
            "支付密码",
            "登录密码",
            "银行卡密码",
            "输入密码",
            "确认付款",
            "立即付款",
            "绕过安全",
            "屏幕共享",
            "远程控制",
        ),
        "category": "funds_or_security_bypass",
    },
    "high": {
        "terms": (
            "通讯录",
            "身份证",
            "免密支付",
            "删除账号",
            "账号删除",
            "身份信息上传",
            "发送消息",
            "确认发送",
        ),
        "category": "sensitive_data_or_irreversible_action",
    },
    "medium": {
        "terms": ("定位", "相机权限", "通知权限", "麦克风权限"),
        "category": "device_permission",
    },
}


def evaluate_risk(parts: Iterable[str]) -> Dict[str, object]:
    text = " ".join(part for part in parts if part).lower()
    for level in ("critical", "high", "medium"):
        rule = RISK_RULES[level]
        matched: List[str] = [term for term in rule["terms"] if term.lower() in text]
        if matched:
            return {
                "level": level,
                "categories": [rule["category"]],
                "confirmation_required": level in {"medium", "high"},
                "matched_terms": matched,
            }
    return {
        "level": "low",
        "categories": [],
        "confirmation_required": False,
        "matched_terms": [],
    }


def apply_risk_override(action: Dict[str, object], risk: Dict[str, object]) -> Dict[str, object]:
    overridden = dict(action)
    if risk["level"] == "critical":
        overridden.update(
            action_type="stop",
            element_id=None,
            instruction="检测到可能涉及资金或安全控制的严重风险，请停止操作并联系可信人员协助。",
            expected_next_state="blocked",
            confidence=1.0,
        )
    elif risk["confirmation_required"]:
        overridden.update(
            action_type="ask_user",
            element_id=None,
            instruction="此步骤涉及敏感权限或不可逆操作，请先阅读影响并明确确认。",
            expected_next_state="waiting_confirmation",
            confidence=max(float(overridden.get("confidence", 0)), 0.9),
        )
    return overridden
