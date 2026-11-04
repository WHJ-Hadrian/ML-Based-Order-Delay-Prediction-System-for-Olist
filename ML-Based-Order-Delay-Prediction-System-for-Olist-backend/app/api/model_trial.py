"""Internal endpoint for evaluating registered models on independent datasets."""

from __future__ import annotations

import math
from typing import Any
from uuid import UUID

import numpy as np
import pandas as pd
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, ConfigDict, Field

from app.api.model_training import get_artifact_store, require_internal_training_token
from app.ml.training.artifacts import ModelArtifactStore
from app.ml.training.metrics import evaluate_predictions

router = APIRouter(prefix="/internal/v1/model-trials", include_in_schema=False)
MAX_TRIAL_CELLS = 2_000_000
MAX_CELL_CHARACTERS = 8_192
MAX_TRIAL_SAMPLES = 50


class ModelTrialRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    owner_id: str = Field(alias="ownerId", min_length=1, max_length=255)
    artifact_id: UUID = Field(alias="artifactId")
    rows: list[dict[str, Any]] = Field(min_length=1, max_length=100_000)
    target_column: str | None = Field(default=None, alias="targetColumn", min_length=1, max_length=255)


class ModelTrialResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    task: str
    target_column: str | None = Field(alias="targetColumn")
    predicted_rows: int = Field(alias="predictedRows")
    evaluated_rows: int = Field(alias="evaluatedRows")
    ignored_target_rows: int = Field(alias="ignoredTargetRows")
    metrics: dict[str, float]
    confusion_matrix: dict[str, Any] | None = Field(alias="confusionMatrix")
    evaluation_samples: tuple[dict[str, Any], ...] = Field(alias="evaluationSamples")


@router.post("", response_model=ModelTrialResponse, status_code=201)
def create_model_trial(
    request: ModelTrialRequest,
    _: None = Depends(require_internal_training_token),
    artifact_store: ModelArtifactStore = Depends(get_artifact_store),
) -> ModelTrialResponse:
    artifact_id = str(request.artifact_id)
    try:
        manifest = artifact_store.manifest(artifact_id, owner_id=request.owner_id)
        pipeline = artifact_store.load_pipeline(artifact_id, owner_id=request.owner_id)
    except FileNotFoundError as error:
        raise HTTPException(status_code=404, detail="模型产物不存在") from error
    except RuntimeError as error:
        raise HTTPException(status_code=409, detail="模型产物完整性校验失败") from error

    feature_columns = tuple(manifest.get("featureColumns", ()))
    task = manifest.get("task")
    if not feature_columns or task not in ("classification", "regression"):
        raise HTTPException(status_code=409, detail="模型产物缺少有效的任务或特征定义")
    if request.target_column in feature_columns:
        raise HTTPException(status_code=422, detail="评估目标字段不能同时作为模型特征")

    expected_columns = set(feature_columns)
    if request.target_column:
        expected_columns.add(request.target_column)
    if len(request.rows) * len(expected_columns) > MAX_TRIAL_CELLS:
        raise HTTPException(status_code=422, detail="试炼数据超过单次行数与字段数资源上限")

    for row in request.rows:
        if set(row) != expected_columns:
            raise HTTPException(status_code=422, detail="试炼数据字段必须与模型特征及所选目标字段完全匹配")
        for value in row.values():
            if isinstance(value, (dict, list, tuple, set)):
                raise HTTPException(status_code=422, detail="试炼字段必须是标量值")
            if isinstance(value, str) and len(value) > MAX_CELL_CHARACTERS:
                raise HTTPException(status_code=422, detail="单个试炼字段值不能超过 8,192 个字符")

    frame = pd.DataFrame(request.rows, columns=list(expected_columns))
    try:
        predictions = pipeline.predict(frame.loc[:, list(feature_columns)])
    except (TypeError, ValueError) as error:
        raise HTTPException(status_code=422, detail=f"试炼字段无法用于当前模型：{error}") from error

    metrics: dict[str, float] = {}
    confusion: dict[str, Any] | None = None
    evaluated_rows = 0
    ignored_target_rows = 0
    actual_values: list[Any] = [None] * len(request.rows)
    if request.target_column:
        raw_target = pd.Series([row[request.target_column] for row in request.rows])
        if task == "classification":
            normalized_target = raw_target.map(
                lambda value: pd.NA if value is None or not str(value).strip() else str(value).strip()
            )
            valid = normalized_target.notna().to_numpy()
            actual = normalized_target.loc[valid].astype(str).reset_index(drop=True)
            predicted = pd.Series(predictions[valid]).astype(str)
            actual_values = [None if not is_valid else str(value) for value, is_valid in zip(
                normalized_target.tolist(), valid, strict=True
            )]
        else:
            numeric_target = pd.to_numeric(raw_target, errors="coerce").to_numpy(dtype=float)
            valid = np.isfinite(numeric_target)
            actual = numeric_target[valid]
            predicted = np.asarray(predictions, dtype=float)[valid]
            actual_values = [float(value) if is_valid else None for value, is_valid in zip(
                numeric_target.tolist(), valid, strict=True
            )]
        evaluated_rows = int(valid.sum())
        ignored_target_rows = len(request.rows) - evaluated_rows
        if evaluated_rows:
            metrics, confusion = evaluate_predictions(task, actual, predicted)

    samples = tuple(
        {
            "row": index + 1,
            "actual": actual_values[index],
            "predicted": _json_value(predictions[index]),
        }
        for index in range(min(len(request.rows), MAX_TRIAL_SAMPLES))
    )
    if any(not math.isfinite(value) for value in metrics.values()):
        raise HTTPException(status_code=422, detail="评估指标出现非有限数值，请检查测试数据")

    return ModelTrialResponse(
        task=task,
        target_column=request.target_column,
        predicted_rows=len(request.rows),
        evaluated_rows=evaluated_rows,
        ignored_target_rows=ignored_target_rows,
        metrics=metrics,
        confusion_matrix=confusion,
        evaluation_samples=samples,
    )


def _json_value(value: Any) -> Any:
    return value.item() if isinstance(value, np.generic) else value
