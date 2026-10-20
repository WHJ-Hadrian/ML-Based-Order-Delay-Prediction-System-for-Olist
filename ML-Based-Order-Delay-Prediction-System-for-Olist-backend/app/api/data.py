from fastapi import APIRouter

router = APIRouter()


@router.get("/data/ping")
def ping():
    return {"msg": "data api ok"}