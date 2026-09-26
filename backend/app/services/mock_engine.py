from typing import Dict, Optional

from .risk_engine import apply_risk_override, evaluate_risk


MODE_COPY = {
    "standard": "点击高亮区域中的“{target}”。",
    "senior": "请找到黄色高亮框，慢慢点击其中的“{target}”。这一步只会打开下一页，不会立即扣费。",
    "low_vision_voice": "高亮目标是“{target}”，位于屏幕{position}。请点击一次。",
}


def is_valid_bbox(bbox):
    return (
        isinstance(bbox, list)
        and len(bbox) == 4
        and all(isinstance(value, (int, float)) and 0 <= value <= 1 for value in bbox)
        and bbox[2] > bbox[0]
        and bbox[3] > bbox[1]
    )


def _target_for_step(step_number: int) -> Dict[str, object]:
    if step_number <= 1:
        return {
            "page_type": "profile_center",
            "page_title": "个人中心",
            "summary": "包含账户资料、会员中心和常用服务入口",
            "target": "会员中心",
            "bbox": [0.12, 0.34, 0.88, 0.47],
            "position": "中部偏上",
            "expected": "membership_center",
        }
    return {
        "page_type": "membership_center",
        "page_title": "会员中心",
        "summary": "包含会员状态、权益说明和自动续费管理入口",
        "target": "自动续费管理",
        "bbox": [0.12, 0.63, 0.88, 0.74],
        "position": "下半部分",
        "expected": "subscription_settings",
    }


def build_mock_analysis(
    session_id: str,
    task_text: str,
    mode: str,
    step_number: int,
    feedback: Optional[str] = None,
) -> Dict[str, object]:
    target = _target_for_step(step_number)
    instruction = MODE_COPY.get(mode, MODE_COPY["standard"]).format(
        target=target["target"], position=target["position"]
    )
    if feedback == "not_found":
        instruction = (
            "请重新查看黄色高亮框：它在屏幕%s，文字是“%s”。如果仍看不到，请上传更清晰的截图。"
            % (target["position"], target["target"])
        )

    element = {
        "id": "mock-target-%d" % step_number,
        "text": target["target"],
        "role": "button",
        "bbox": target["bbox"],
        "clickable": True,
        "goal_relevance": 0.96,
        "confidence": 0.94,
        "risk_hints": [],
    }
    action = {
        "action_type": "tap",
        "element_id": element["id"],
        "instruction": instruction,
        "expected_next_state": target["expected"],
        "confidence": 0.93,
    }
    risk = evaluate_risk([task_text, str(target["target"]), instruction])
    action = apply_risk_override(action, risk)

    return {
        "session_id": session_id,
        "page": {
            "page_type": target["page_type"],
            "title": target["page_title"],
            "summary": target["summary"],
            "confidence": 0.95,
        },
        "elements": [element] if action["action_type"] == "tap" else [],
        "recommended_action": action,
        "risk": {
            "level": risk["level"],
            "categories": risk["categories"],
            "confirmation_required": risk["confirmation_required"],
        },
        "task_status": "in_progress" if risk["level"] != "critical" else "blocked",
    }

