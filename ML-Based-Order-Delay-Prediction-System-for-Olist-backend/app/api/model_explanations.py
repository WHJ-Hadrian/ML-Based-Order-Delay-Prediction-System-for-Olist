"""Internal, owner-scoped single-row explanations and what-if scoring."""

from __future__ import annotations

from typing import Any
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, ConfigDict, Field

from app.api.model_training import get_artifact_store, require_internal_training_token
from app.ml.training.artifacts import ModelArtifactStore
from app.ml.training.explainability import background_frame, explain_sample

router = APIRouter(prefix="/internal/v1/model-explanations", include_in_schema=False)
MAX_EXPLANATION_ROWS = 20
MAX_CELL_CHARACTERS = 8_192


class ModelExplanationRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    owner_id: str = Field(alias="ownerId", min_length=1, max_length=255)
    artifact_id: UUID = Field(alias="artifactId")
    rows: list[dict[str, Any]] = Field(min_length=1, max_length=MAX_EXPLANATION_ROWS)
    threshold: float | None = Field(default=None, ge=0.0, le=1.0)
    include_counterfactuals: bool = Field(default=True, alias="includeCounterfactuals")


class ModelExplanationResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    task: str
    algorithm: str | None
    target_column: str | None = Field(alias="targetColumn")
    feature_columns: list[str] = Field(alias="featureColumns")
    feature_types: dict[str, str] = Field(alias="featureTypes")
    threshold: float | None
    background_size: int = Field(alias="backgroundSize")
    results: list[dict[str, Any]]


@router.post("", response_model=ModelExplanationResponse)
def explain_rows(
    request: ModelExplanationRequest,
    _: None = Depends(require_internal_training_token),
    artifact_store: ModelArtifactStore = Depends(get_artifact_store),
) -> ModelExplanationResponse:
    artifact_id = str(request.artifact_id)
    try:
        manifest = artifact_store.manifest(artifact_id, owner_id=request.owner_id)
        pipeline = artifact_store.load_pipeline(artifact_id, owner_id=request.owner_id)
    except FileNotFoundError as error:
        raise HTTPException(status_code=404, detail="模型产物不存在") from error
    except RuntimeError as error:
        raise HTTPException(status_code=409, detail="模型产物完整性校验失败") from error

    feature_columns = [str(name) for name in manifest.get("featureColumns", ())]
    feature_types = {
        str(name): str(kind) for name, kind in (manifest.get("featureTypes") or {}).items()
    }
    task = str(manifest.get("task") or "classification")
    if not feature_columns or task not in ("classification", "regression"):
        raise HTTPException(status_code=409, detail="模型产物缺少有效的任务或特征定义")
    for name in feature_columns:
        feature_types.setdefault(name, "categorical")

    threshold = request.threshold
    if threshold is None and manifest.get("decisionThreshold") is not None:
        threshold = float(manifest["decisionThreshold"])

    explainability = manifest.get("explainability") or {}
    background = background_frame(explainability.get("backgroundSample"), feature_types)

    rows = request.rows
    if len(rows) > 1 and request.include_counterfactuals:
        rows = rows[:1]
    prepared = [_normalize_row(row, feature_columns) for row in rows]
    results: list[dict[str, Any]] = []
    for index, row in enumerate(prepared):
        try:
            explanation = explain_sample(
                pipeline,
                row,
                feature_types=feature_types,
                task=task,
                threshold=threshold,
                background=background,
                include_counterfactuals=request.include_counterfactuals,
            )
        except (TypeError, ValueError) as error:
            raise HTTPException(
                status_code=422,
                detail=f"第 {index + 1} 行数据无法用于当前模型：{error}",
            ) from error
        if index > 0:
            explanation["counterfactuals"] = []
        results.append(explanation)

    return ModelExplanationResponse(
        task=task,
        algorithm=manifest.get("algorithm"),
        target_column=manifest.get("targetColumn"),
        feature_columns=feature_columns,
        feature_types=feature_types,
        threshold=threshold,
        background_size=0 if background is None else len(background),
        results=results,
    )


def _normalize_row(row: dict[str, Any], feature_columns: list[str]) -> dict[str, Any]:
    normalized: dict[str, Any] = {}
    for name in feature_columns:
        value = row.get(name)
        if isinstance(value, (dict, list, tuple, set)):
            raise HTTPException(status_code=422, detail=f"字段 {name} 必须是标量值")
        if isinstance(value, str) and len(value) > MAX_CELL_CHARACTERS:
            raise HTTPException(
                status_code=422, detail=f"字段 {name} 的取值不能超过 8,192 个字符"
            )
        normalized[name] = value
    return normalized
