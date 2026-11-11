"""Internal endpoint for evaluating registered models on independent datasets."""

from __future__ import annotations

import math
from typing import Any
from uuid import UUID

import numpy as np
import pandas as pd
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.api.model_training import (
    DatasetExportSourceRequest,
    get_artifact_store,
    require_internal_training_token,
)
from app.config import settings
from app.ml.training.artifacts import ModelArtifactStore
from app.ml.training.datasource import DatasetExportSource, DatasetSourceError, load_training_data
from app.ml.training.metrics import evaluate_detailed

router = APIRouter(prefix="/internal/v1/model-trials", include_in_schema=False)
MAX_TRIAL_CELLS = 2_000_000
MAX_CELL_CHARACTERS = 8_192
MAX_TRIAL_SAMPLES = 50


class ModelTrialRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    owner_id: str = Field(alias="ownerId", min_length=1, max_length=255)
    artifact_id: UUID = Field(alias="artifactId")
    rows: list[dict[str, Any]] | None = Field(default=None, max_length=100_000)
    data_source: DatasetExportSourceRequest | None = Field(default=None, alias="dataSource")
    target_column: str | None = Field(default=None, alias="targetColumn", min_length=1, max_length=255)

    @model_validator(mode="after")
    def require_single_data_channel(self) -> "ModelTrialRequest":
        if (self.rows is None) == (self.data_source is None):
            raise ValueError("必须且只能提供 rows 或 dataSource")
        if self.rows is not None and not self.rows:
            raise ValueError("试炼数据不能为空")
        if self.data_source is not None and self.data_source.owner_id != self.owner_id:
            raise ValueError("dataSource.ownerId 必须与 ownerId 一致")
        return self


class ModelTrialResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    task: str
    target_column: str | None = Field(alias="targetColumn")
    predicted_rows: int = Field(alias="predictedRows")
    evaluated_rows: int = Field(alias="evaluatedRows")
    ignored_target_rows: int = Field(alias="ignoredTargetRows")
    metrics: dict[str, float]
    metric_details: dict[str, Any] = Field(default_factory=dict, alias="metricDetails")
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
    if request.rows is not None:
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

    export_source = None
    if request.data_source is not None:
        source = request.data_source
        if set(source.columns) != expected_columns:
            raise HTTPException(status_code=422, detail="dataSource.columns 必须与模型特征及所选目标字段完全一致")
        export_source = DatasetExportSource(
            dataset_version_id=str(source.dataset_version_id),
            table_id=str(source.table_id),
            owner_id=source.owner_id,
            columns=tuple(source.columns),
            max_rows=source.max_rows,
        )
    try:
        loaded = load_training_data(
            rows=request.rows,
            source=export_source,
            required_columns=tuple(feature_columns) + ((request.target_column,) if request.target_column else ()),
            max_rows=100_000,
            max_cells=MAX_TRIAL_CELLS,
            timeout_seconds=settings.model_training_dataset_timeout_seconds,
        )
    except DatasetSourceError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error

    frame = loaded.frame
    try:
        probabilities = None
        if task == "classification" and hasattr(pipeline, "predict_proba"):
            probabilities = pipeline.predict_proba(frame.loc[:, list(feature_columns)])
        classes = np.asarray(getattr(pipeline, "classes_", ()))
        threshold = manifest.get("decisionThreshold")
        if probabilities is not None and len(classes) == 2 and threshold is not None:
            predictions = np.where(
                probabilities[:, 1] >= float(threshold), classes[1], classes[0]
            )
        elif probabilities is not None and len(classes):
            predictions = classes[np.argmax(probabilities, axis=1)]
        else:
            predictions = pipeline.predict(frame.loc[:, list(feature_columns)])
    except (TypeError, ValueError) as error:
        raise HTTPException(status_code=422, detail=f"试炼字段无法用于当前模型：{error}") from error

    metrics: dict[str, float] = {}
    metric_details: dict[str, Any] = {}
    confusion: dict[str, Any] | None = None
    evaluated_rows = 0
    ignored_target_rows = 0
    actual_values: list[Any] = [None] * len(frame)
    if request.target_column:
        raw_target = frame[request.target_column]
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
        ignored_target_rows = len(frame) - evaluated_rows
        if evaluated_rows:
            metrics, metric_details, confusion = evaluate_detailed(
                task,
                actual,
                predicted,
                probabilities=None if probabilities is None else probabilities[valid],
                classes=getattr(pipeline, "classes_", None),
            )

    samples = tuple(
        {
            "row": index + 1,
            "actual": actual_values[index],
            "predicted": _json_value(predictions[index]),
        }
        for index in range(min(len(frame), MAX_TRIAL_SAMPLES))
    )
    if any(not math.isfinite(value) for value in metrics.values()):
        raise HTTPException(status_code=422, detail="评估指标出现非有限数值，请检查测试数据")

    return ModelTrialResponse(
        task=task,
        target_column=request.target_column,
        predicted_rows=len(frame),
        evaluated_rows=evaluated_rows,
        ignored_target_rows=ignored_target_rows,
        metrics=metrics,
        metric_details=metric_details,
        confusion_matrix=confusion,
        evaluation_samples=samples,
    )


def _json_value(value: Any) -> Any:
    return value.item() if isinstance(value, np.generic) else value
