from datetime import datetime
from enum import Enum
from typing import List, Literal, Optional

from pydantic import BaseModel, Field, field_validator


class AssistanceMode(str, Enum):
    STANDARD = "standard"
    SENIOR = "senior"
    LOW_VISION_VOICE = "low_vision_voice"


class TaskStatus(str, Enum):
    NOT_STARTED = "not_started"
    IN_PROGRESS = "in_progress"
    BLOCKED = "blocked"
    COMPLETED = "completed"
    UNCERTAIN = "uncertain"


class SessionState(str, Enum):
    CREATED = "CREATED"
    OBSERVING = "OBSERVING"
    GUIDING = "GUIDING"
    WAITING_FEEDBACK = "WAITING_FEEDBACK"
    WAITING_CONFIRMATION = "WAITING_CONFIRMATION"
    BLOCKED = "BLOCKED"
    COMPLETED = "COMPLETED"


class ActionType(str, Enum):
    TAP = "tap"
    TYPE = "type"
    SCROLL = "scroll"
    BACK = "back"
    WAIT = "wait"
    ASK_USER = "ask_user"
    STOP = "stop"


class RiskLevel(str, Enum):
    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"
    CRITICAL = "critical"


class ElementRole(str, Enum):
    BUTTON = "button"
    INPUT = "input"
    LINK = "link"
    TAB = "tab"
    DIALOG = "dialog"
    TEXT = "text"
    IMAGE = "image"
    UNKNOWN = "unknown"


class FeedbackType(str, Enum):
    DONE = "done"
    NOT_FOUND = "not_found"
    UNEXPECTED_PAGE = "unexpected_page"
    WRONG_ACTION = "wrong_action"
    CANCEL = "cancel"


class PageInfo(BaseModel):
    page_type: str
    title: str
    summary: str
    confidence: float = Field(ge=0, le=1)


class UIElement(BaseModel):
    id: str
    text: str
    role: ElementRole
    bbox: List[float]
    clickable: bool
    goal_relevance: float = Field(ge=0, le=1)
    confidence: float = Field(ge=0, le=1)
    risk_hints: List[str] = Field(default_factory=list)

    @field_validator("bbox")
    @classmethod
    def validate_bbox(cls, value: List[float]) -> List[float]:
        if len(value) != 4:
            raise ValueError("bbox must contain [x1, y1, x2, y2]")
        x1, y1, x2, y2 = value
        if any(point < 0 or point > 1 for point in value):
            raise ValueError("bbox values must be normalized to [0, 1]")
        if x2 <= x1 or y2 <= y1:
            raise ValueError("bbox must have positive width and height")
        return value


class RecommendedAction(BaseModel):
    action_type: ActionType
    element_id: Optional[str] = None
    instruction: str
    expected_next_state: str
    confidence: float = Field(ge=0, le=1)


class RiskInfo(BaseModel):
    level: RiskLevel
    categories: List[str] = Field(default_factory=list)
    confirmation_required: bool = False


class AnalysisResponse(BaseModel):
    session_id: str
    page: PageInfo
    elements: List[UIElement]
    recommended_action: RecommendedAction
    risk: RiskInfo
    task_status: TaskStatus
    analysis_source: Literal["mock", "model"] = "mock"
    model_name: Optional[str] = None


class SessionCreate(BaseModel):
    task_text: str = Field(min_length=1, max_length=500)
    mode: AssistanceMode = AssistanceMode.STANDARD


class FeedbackRequest(BaseModel):
    feedback: FeedbackType


class ConfirmRequest(BaseModel):
    approved: bool


class StateEvent(BaseModel):
    state: SessionState
    reason: str
    timestamp: datetime


class SessionView(BaseModel):
    session_id: str
    task_text: str
    mode: AssistanceMode
    state: SessionState
    task_status: TaskStatus
    step_number: int
    deviation_count: int
    created_at: datetime
    updated_at: datetime
    latest_analysis: Optional[AnalysisResponse] = None
    history: List[StateEvent]


class FeedbackResponse(BaseModel):
    session: SessionView
    guidance: str
    needs_new_screenshot: bool


class CompletionReport(BaseModel):
    session_id: str
    task_text: str
    task_status: TaskStatus
    steps: int
    deviations: int
    risk_level: RiskLevel
    summary: str


class HealthResponse(BaseModel):
    status: str
    mock_mode: bool
    model_configured: bool
    save_screenshots: bool
    analysis_mode: Literal["mock", "model"]
    model_name: Optional[str] = None
