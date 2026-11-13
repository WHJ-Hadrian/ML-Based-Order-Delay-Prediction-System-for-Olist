from fastapi import FastAPI
from prometheus_client import CONTENT_TYPE_LATEST, Counter, generate_latest
from starlette.requests import Request
from starlette.responses import Response

from app.api.analysis_reports import router as analysis_reports_router
from app.api.health import router as health_router
from app.api.model_artifacts import router as model_artifacts_router
from app.api.model_explanations import router as model_explanations_router
from app.api.model_reports import router as model_reports_router
from app.api.model_training import router as model_training_router
from app.api.model_trial import router as model_trial_router
from app.config import settings

app = FastAPI(
    title=settings.service_name,
    version="0.1.0",
    docs_url=None,
    redoc_url=None,
)
app.include_router(health_router, tags=["operations"])
app.include_router(model_training_router, tags=["internal-model-training"])
app.include_router(model_trial_router, tags=["internal-model-trial"])
app.include_router(model_artifacts_router, tags=["internal-model-artifacts"])
app.include_router(model_explanations_router, tags=["internal-model-explanations"])
app.include_router(model_reports_router, tags=["internal-model-reports"])
app.include_router(analysis_reports_router, tags=["internal-analysis-reports"])

http_requests = Counter(
    "http_requests_total",
    "Total HTTP requests",
    ("method", "route", "status_code"),
)


@app.middleware("http")
async def record_request_metrics(request: Request, call_next):
    response = await call_next(request)
    route = request.scope.get("route")
    route_path = getattr(route, "path", "unmatched")
    http_requests.labels(request.method, route_path, response.status_code).inc()
    return response


@app.get("/metrics", include_in_schema=False)
def metrics() -> Response:
    return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)
