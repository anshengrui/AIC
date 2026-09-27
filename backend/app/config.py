import os
from dataclasses import dataclass

from dotenv import load_dotenv


load_dotenv()


def _as_bool(value: str, default: bool) -> bool:
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


@dataclass(frozen=True)
class Settings:
    app_env: str = os.getenv("APP_ENV", "development")
    api_access_token: str = os.getenv("EASYACCESS_API_TOKEN", "")
    llm_api_key: str = os.getenv("LLM_API_KEY", "")
    llm_base_url: str = os.getenv("LLM_BASE_URL", "https://example.invalid/v1")
    llm_model: str = os.getenv("LLM_MODEL", "multimodal-model-name")
    llm_timeout_seconds: float = float(os.getenv("LLM_TIMEOUT_SECONDS", "60"))
    llm_max_output_tokens: int = int(os.getenv("LLM_MAX_OUTPUT_TOKENS", "900"))
    llm_enable_thinking: bool = _as_bool(os.getenv("LLM_ENABLE_THINKING"), False)
    llm_low_confidence_threshold: float = float(
        os.getenv("LLM_LOW_CONFIDENCE_THRESHOLD", "0.60")
    )
    database_url: str = os.getenv("DATABASE_URL", "sqlite:///./easyaccess.db")
    max_image_mb: int = int(os.getenv("MAX_IMAGE_MB", "8"))
    save_screenshots: bool = _as_bool(os.getenv("SAVE_SCREENSHOTS"), False)
    mock_mode: bool = _as_bool(os.getenv("MOCK_MODE"), True)

    @property
    def model_configured(self) -> bool:
        return bool(
            self.llm_api_key
            and self.llm_api_key != "replace_me"
            and self.llm_base_url
            and "example.invalid" not in self.llm_base_url
            and self.llm_model
            and self.llm_model != "multimodal-model-name"
        )

    @property
    def analysis_mode(self) -> str:
        return "mock" if self.mock_mode else "model"


settings = Settings()
