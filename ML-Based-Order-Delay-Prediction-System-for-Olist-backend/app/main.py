from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api import prediction, chat, data

app = FastAPI(
    title="Olist 订单延误预测系统 API",
    version="0.1.0",
    description="基于机器学习的订单配送延误预测 + LangChain 智能助手",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:5173"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(prediction.router, prefix="/api", tags=["prediction"])
app.include_router(chat.router, prefix="/api", tags=["chat"])
app.include_router(data.router, prefix="/api", tags=["data"])


@app.get("/")
def root():
    return {"status": "ok", "service": "olist-delay-prediction"}


@app.get("/health")
def health():
    return {"status": "healthy"}