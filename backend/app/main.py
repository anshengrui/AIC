from contextlib import asynccontextmanager
import secrets

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app.api.routes import close_model_provider, router
from app.config import settings


@asynccontextmanager
async def lifespan(_app: FastAPI):
    yield
    await close_model_provider()


app = FastAPI(
    title="EasyAccess API",
    version="0.2.0",
    description="Mock-first and multimodal API for accessible digital navigation.",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://127.0.0.1:5173", "http://localhost:5173"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.middleware("http")
async def verify_client_access(request: Request, call_next):
    """Protect public API routes when a deployment token is configured."""
    public_paths = {"/", "/healthz", "/api/health"}
    expected_token = settings.api_access_token.strip()
    if (
        request.method != "OPTIONS"
        and request.url.path not in public_paths
        and expected_token
    ):
        supplied_token = request.headers.get("X-EasyAccess-Key", "")
        if not secrets.compare_digest(supplied_token, expected_token):
            return JSONResponse(
                status_code=401,
                content={
                    "detail": {
                        "code": "CLIENT_UNAUTHORIZED",
                        "message": "客户端访问凭据无效",
                    }
                },
            )
    return await call_next(request)


@app.get("/", include_in_schema=False)
def service_info() -> dict[str, str]:
    return {"service": "EasyAccess API", "status": "ok"}


@app.get("/healthz", include_in_schema=False)
def platform_health() -> dict[str, str]:
    return {"status": "ok"}


app.include_router(router, prefix="/api")
