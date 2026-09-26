from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api.routes import close_model_provider, router


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

app.include_router(router, prefix="/api")
