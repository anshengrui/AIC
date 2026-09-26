from copy import deepcopy
from datetime import datetime, timezone
from threading import Lock
from typing import Any, Dict, List
from uuid import uuid4


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


class SessionNotFound(KeyError):
    pass


class InMemorySessionStore:
    def __init__(self) -> None:
        self._sessions: Dict[str, Dict[str, Any]] = {}
        self._lock = Lock()

    def create(self, task_text: str, mode: str) -> Dict[str, Any]:
        now = utc_now()
        session_id = str(uuid4())
        session = {
            "session_id": session_id,
            "task_text": task_text.strip(),
            "mode": mode,
            "state": "CREATED",
            "task_status": "not_started",
            "step_number": 1,
            "deviation_count": 0,
            "created_at": now,
            "updated_at": now,
            "latest_analysis": None,
            "history": [self._event("CREATED", "session_created", now)],
        }
        with self._lock:
            self._sessions[session_id] = session
        return deepcopy(session)

    def get(self, session_id: str) -> Dict[str, Any]:
        with self._lock:
            if session_id not in self._sessions:
                raise SessionNotFound(session_id)
            return deepcopy(self._sessions[session_id])

    def transition(self, session_id: str, state: str, reason: str, **updates: Any) -> Dict[str, Any]:
        with self._lock:
            if session_id not in self._sessions:
                raise SessionNotFound(session_id)
            session = self._sessions[session_id]
            now = utc_now()
            session.update(updates)
            session["state"] = state
            session["updated_at"] = now
            session["history"].append(self._event(state, reason, now))
            return deepcopy(session)

    @staticmethod
    def _event(state: str, reason: str, timestamp: datetime) -> Dict[str, Any]:
        return {"state": state, "reason": reason, "timestamp": timestamp}


session_store = InMemorySessionStore()

