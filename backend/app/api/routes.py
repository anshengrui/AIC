from typing import Dict

import httpx
from fastapi import APIRouter, File, HTTPException, UploadFile, status

from app.config import settings
from app.llm import (
    ModelOutputInvalid,
    ModelProviderError,
    ModelTimeout,
    OpenAICompatibleVisionProvider,
)
from app.schemas.models import (
    AnalysisResponse,
    CompletionReport,
    ConfirmRequest,
    FeedbackRequest,
    FeedbackResponse,
    HealthResponse,
    RiskLevel,
    SessionCreate,
    SessionState,
    SessionView,
    TaskStatus,
)
from app.services.mock_engine import build_mock_analysis
from app.services.session_store import SessionNotFound, session_store


router = APIRouter()
_model_http_client = None


def get_model_provider() -> OpenAICompatibleVisionProvider:
    global _model_http_client
    if _model_http_client is None or _model_http_client.is_closed:
        _model_http_client = httpx.AsyncClient(
            timeout=httpx.Timeout(settings.llm_timeout_seconds, connect=10.0)
        )
    return OpenAICompatibleVisionProvider(settings, client=_model_http_client)


async def close_model_provider() -> None:
    global _model_http_client
    if _model_http_client is not None and not _model_http_client.is_closed:
        await _model_http_client.aclose()
    _model_http_client = None


def api_error(code: str, message: str, http_status: int) -> HTTPException:
    return HTTPException(status_code=http_status, detail={"code": code, "message": message})


def get_session_or_404(session_id: str) -> Dict[str, object]:
    try:
        return session_store.get(session_id)
    except SessionNotFound as exc:
        raise api_error("SESSION_NOT_FOUND", "会话不存在或已失效", status.HTTP_404_NOT_FOUND) from exc


def as_session_view(session: Dict[str, object]) -> SessionView:
    return SessionView.model_validate(session)


@router.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    return HealthResponse(
        status="ok",
        mock_mode=settings.mock_mode,
        model_configured=settings.model_configured,
        save_screenshots=settings.save_screenshots,
        analysis_mode=settings.analysis_mode,
        model_name=settings.llm_model if settings.model_configured else None,
    )


@router.post("/sessions", response_model=SessionView, status_code=status.HTTP_201_CREATED)
def create_session(payload: SessionCreate) -> SessionView:
    session = session_store.create(payload.task_text, payload.mode.value)
    return as_session_view(session)


@router.post("/sessions/{session_id}/analyze", response_model=AnalysisResponse)
async def analyze_session(session_id: str, image: UploadFile = File(...)) -> AnalysisResponse:
    session = get_session_or_404(session_id)
    if session["state"] in {SessionState.COMPLETED.value, SessionState.BLOCKED.value}:
        raise api_error(
            "SESSION_STATE_CONFLICT",
            "当前会话已结束，不能继续分析截图",
            status.HTTP_409_CONFLICT,
        )

    allowed_types = {"image/png", "image/jpeg", "image/jpg"}
    if image.content_type not in allowed_types:
        raise api_error("IMAGE_INVALID", "仅支持 PNG/JPG 图片", status.HTTP_415_UNSUPPORTED_MEDIA_TYPE)

    limit = settings.max_image_mb * 1024 * 1024
    content = await image.read(limit + 1)
    await image.close()
    if not content or len(content) > limit:
        raise api_error(
            "IMAGE_INVALID",
            "图片为空或超过 %d MB 限制" % settings.max_image_mb,
            status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
        )
    is_png = content.startswith(b"\x89PNG\r\n\x1a\n")
    is_jpeg = content.startswith(b"\xff\xd8\xff")
    if not (is_png or is_jpeg):
        raise api_error("IMAGE_INVALID", "文件内容不是有效的 PNG/JPG 图片", status.HTTP_400_BAD_REQUEST)

    session_store.transition(session_id, SessionState.OBSERVING.value, "image_validated")
    if settings.mock_mode:
        raw_result = build_mock_analysis(
            session_id=session_id,
            task_text=str(session["task_text"]),
            mode=str(session["mode"]),
            step_number=int(session["step_number"]),
        )
        result = AnalysisResponse.model_validate(raw_result)
        transition_reason = "mock_analysis_completed"
    else:
        if not settings.model_configured:
            raise api_error(
                "MODEL_NOT_CONFIGURED",
                "真实模型模式尚未配置，请检查 LLM_API_KEY、LLM_BASE_URL 和 LLM_MODEL",
                status.HTTP_503_SERVICE_UNAVAILABLE,
            )
        provider = get_model_provider()
        try:
            result = await provider.analyze(
                image_bytes=content,
                mime_type="image/png" if is_png else "image/jpeg",
                session_id=session_id,
                task_text=str(session["task_text"]),
                mode=str(session["mode"]),
            )
        except ModelTimeout as exc:
            raise api_error(
                "MODEL_TIMEOUT",
                "模型响应超时，请稍后重试；系统没有执行任何操作",
                status.HTTP_504_GATEWAY_TIMEOUT,
            ) from exc
        except ModelOutputInvalid as exc:
            raise api_error(
                "MODEL_OUTPUT_INVALID",
                "模型结果未通过安全结构校验，请换一张更清晰的截图",
                status.HTTP_502_BAD_GATEWAY,
            ) from exc
        except ModelProviderError as exc:
            raise api_error(
                "MODEL_UNAVAILABLE",
                "模型服务暂时不可用，请稍后重试或切回 Mock 模式",
                status.HTTP_502_BAD_GATEWAY,
            ) from exc
        transition_reason = "model_analysis_completed"

    if result.risk.level == RiskLevel.CRITICAL:
        next_state = SessionState.BLOCKED
    elif result.risk.confirmation_required:
        next_state = SessionState.WAITING_CONFIRMATION
    else:
        next_state = SessionState.WAITING_FEEDBACK
    session_store.transition(
        session_id,
        next_state.value,
        transition_reason,
        task_status=result.task_status.value,
        latest_analysis=result.model_dump(mode="python"),
    )
    return result


@router.post("/sessions/{session_id}/feedback", response_model=FeedbackResponse)
def submit_feedback(session_id: str, payload: FeedbackRequest) -> FeedbackResponse:
    session = get_session_or_404(session_id)
    if payload.feedback.value == "cancel":
        updated = session_store.transition(
            session_id,
            SessionState.BLOCKED.value,
            "user_cancelled",
            task_status=TaskStatus.BLOCKED.value,
        )
        return FeedbackResponse(
            session=as_session_view(updated),
            guidance="已暂停当前任务，不会继续给出操作指引。",
            needs_new_screenshot=False,
        )
    if session["state"] != SessionState.WAITING_FEEDBACK.value:
        raise api_error(
            "SESSION_STATE_CONFLICT",
            "当前状态不接受反馈，请刷新会话后重试",
            status.HTTP_409_CONFLICT,
        )

    if payload.feedback.value == "not_found":
        raw_result = build_mock_analysis(
            session_id=session_id,
            task_text=str(session["task_text"]),
            mode=str(session["mode"]),
            step_number=int(session["step_number"]),
            feedback="not_found",
        )
        result = AnalysisResponse.model_validate(raw_result)
        updated = session_store.transition(
            session_id,
            SessionState.WAITING_FEEDBACK.value,
            "target_not_found_rephrased",
            latest_analysis=result.model_dump(mode="python"),
        )
        return FeedbackResponse(
            session=as_session_view(updated),
            guidance=result.recommended_action.instruction,
            needs_new_screenshot=False,
        )

    if payload.feedback.value in {"unexpected_page", "wrong_action"}:
        reason = "unexpected_page_reported" if payload.feedback.value == "unexpected_page" else "wrong_action_reported"
        updated = session_store.transition(
            session_id,
            SessionState.OBSERVING.value,
            reason,
            task_status=TaskStatus.IN_PROGRESS.value,
            deviation_count=int(session["deviation_count"]) + 1,
            latest_analysis=None,
        )
        return FeedbackResponse(
            session=as_session_view(updated),
            guidance="已丢弃原页面假设。请上传当前页面截图，我会从当前位置重新规划。",
            needs_new_screenshot=True,
        )

    next_step = int(session["step_number"]) + 1
    if next_step > 2:
        updated = session_store.transition(
            session_id,
            SessionState.COMPLETED.value,
            "mock_goal_verified",
            task_status=TaskStatus.COMPLETED.value,
            step_number=next_step,
        )
        return FeedbackResponse(
            session=as_session_view(updated),
            guidance="Mock 路径已完成并记录。",
            needs_new_screenshot=False,
        )

    updated = session_store.transition(
        session_id,
        SessionState.OBSERVING.value,
        "step_reported_done",
        task_status=TaskStatus.IN_PROGRESS.value,
        step_number=next_step,
        latest_analysis=None,
    )
    return FeedbackResponse(
        session=as_session_view(updated),
        guidance="已记录完成。请上传进入新页面后的截图，继续第 %d 步。" % next_step,
        needs_new_screenshot=True,
    )


@router.get("/sessions/{session_id}", response_model=SessionView)
def get_session(session_id: str) -> SessionView:
    return as_session_view(get_session_or_404(session_id))


@router.post("/sessions/{session_id}/confirm", response_model=FeedbackResponse)
def confirm_risk(session_id: str, payload: ConfirmRequest) -> FeedbackResponse:
    session = get_session_or_404(session_id)
    if session["state"] != SessionState.WAITING_CONFIRMATION.value:
        raise api_error(
            "SESSION_STATE_CONFLICT",
            "当前会话没有待确认的风险动作",
            status.HTTP_409_CONFLICT,
        )
    if payload.approved:
        updated = session_store.transition(
            session_id,
            SessionState.WAITING_FEEDBACK.value,
            "risk_acknowledged",
        )
        guidance = "已记录确认；系统仍只提供引导，不会替你执行操作。"
    else:
        updated = session_store.transition(
            session_id,
            SessionState.BLOCKED.value,
            "risk_rejected",
            task_status=TaskStatus.BLOCKED.value,
        )
        guidance = "已暂停敏感操作。"
    return FeedbackResponse(
        session=as_session_view(updated), guidance=guidance, needs_new_screenshot=False
    )


@router.get("/sessions/{session_id}/report", response_model=CompletionReport)
def get_report(session_id: str) -> CompletionReport:
    session = get_session_or_404(session_id)
    latest = session.get("latest_analysis") or {}
    risk = latest.get("risk", {}) if isinstance(latest, dict) else {}
    completed = session["task_status"] == TaskStatus.COMPLETED.value
    return CompletionReport(
        session_id=session_id,
        task_text=str(session["task_text"]),
        task_status=TaskStatus(str(session["task_status"])),
        steps=int(session["step_number"]),
        deviations=int(session["deviation_count"]),
        risk_level=RiskLevel(str(risk.get("level", "low"))),
        summary="任务已在 Mock 路径中完成。" if completed else "任务尚未完成，请继续提交当前页面截图。",
    )
