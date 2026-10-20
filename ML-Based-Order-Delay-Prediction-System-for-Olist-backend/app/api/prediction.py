from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

router = APIRouter()


class PredictRequest(BaseModel):
    order_id: str | None = None
    customer_state: str | None = None
    seller_state: str | None = None
    freight_value: float | None = None


class PredictResponse(BaseModel):
    delay_probability: float
    risk_level: str


@router.post("/predict", response_model=PredictResponse)
def predict(req: PredictRequest):
    raise HTTPException(status_code=501, detail="预测服务尚未实现")