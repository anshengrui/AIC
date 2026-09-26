import importlib.util
import os
import sys
import unittest
from unittest.mock import patch


BACKEND_ROOT = os.path.dirname(os.path.dirname(__file__))


@unittest.skipUnless(
    sys.version_info >= (3, 11)
    and importlib.util.find_spec("fastapi")
    and importlib.util.find_spec("pydantic")
    and importlib.util.find_spec("httpx"),
    "Python 3.11 and complete FastAPI test dependencies are required",
)
class ApiFlowTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if BACKEND_ROOT not in sys.path:
            sys.path.insert(0, BACKEND_ROOT)
        from fastapi.testclient import TestClient
        from app.config import Settings
        from app.main import app

        cls.client = TestClient(app)
        cls.mock_settings = Settings(mock_mode=True)

    def test_health_does_not_expose_key(self):
        response = self.client.get("/api/health")
        self.assertEqual(200, response.status_code)
        self.assertNotIn("api_key", response.json())

    def test_create_analyze_feedback_flow(self):
        created = self.client.post(
            "/api/sessions",
            json={"task_text": "帮我关闭自动续费", "mode": "standard"},
        )
        self.assertEqual(201, created.status_code)
        session_id = created.json()["session_id"]

        # Minimal valid 1x1 transparent PNG.
        png = (
            b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01"
            b"\x00\x00\x00\x01\x08\x06\x00\x00\x00\x1f\x15\xc4\x89"
        )
        with patch("app.api.routes.settings", self.mock_settings):
            analyzed = self.client.post(
                "/api/sessions/%s/analyze" % session_id,
                files={"image": ("screen.png", png, "image/png")},
            )
        self.assertEqual(200, analyzed.status_code)
        self.assertEqual([0.12, 0.34, 0.88, 0.47], analyzed.json()["elements"][0]["bbox"])

        feedback = self.client.post(
            "/api/sessions/%s/feedback" % session_id,
            json={"feedback": "not_found"},
        )
        self.assertEqual(200, feedback.status_code)
        self.assertFalse(feedback.json()["needs_new_screenshot"])


if __name__ == "__main__":
    unittest.main()
