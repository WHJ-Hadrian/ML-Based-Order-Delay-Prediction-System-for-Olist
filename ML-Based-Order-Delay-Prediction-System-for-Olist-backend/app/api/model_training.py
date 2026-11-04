"""Internal model-training entrypoint for the model-management service."""

from __future__ import annotations

import hmac
from typing import Any, Literal
from uuid import UUID

import pandas as pd
from fastapi import APIRouter, Depends, Header, HTTPException
from pydantic import BaseModel, ConfigDict, Field

from app.config import settings
from app.ml.training.artifacts import ModelArtifactStore
from app.ml.training.engine import ModelTrainingEngine, TrainingSpec

router = APIRouter(prefix="/internal/v1/model-training-runs", include_in_schema=False)
training_engine = ModelTrainingEngine()
MAX_TRAINING_CELLS = 2_000_000
MAX_CELL_CHARACTERS = 8_192


class TrainingRunRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    owner_id: str = Field(alias="ownerId", min_length=1, max_length=255)
    dataset_id: UUID = Field(alias="datasetId")
    dataset_version_id: UUID = Field(alias="datasetVersionId")
    table_id: UUID = Field(alias="tableId")
    rows: list[dict[str, Any]] = Field(min_length=12, max_length=100_000)
    task: Literal["classification", "regression"]
    algorithm: Literal["random_forest", "logistic_regression", "ridge_regression"]
    target_column: str = Field(alias="targetColumn", min_length=1, max_length=255)
    feature_columns: list[str] = Field(alias="featureColumns", min_length=1, max_length=200)
    test_size: float = Field(default=0.2, alias="testSize", strict=True, ge=0.1, le=0.4)
    random_state: int = Field(default=42, alias="randomState", strict=True, ge=0, le=2**32 - 1)


class TrainingRunResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    artifact_id: str = Field(alias="artifactId")
    dataset_id: UUID = Field(alias="datasetId")
    dataset_version_id: UUID = Field(alias="datasetVersionId")
    table_id: UUID = Field(alias="tableId")
    task: str
    algorithm: str
    target_column: str = Field(alias="targetColumn")
    feature_columns: tuple[str, ...] = Field(alias="featureColumns")
    feature_types: dict[str, str] = Field(alias="featureTypes")
    test_size: float = Field(alias="testSize")
    random_state: int = Field(alias="randomState")
    metrics: dict[str, float]
    train_rows: int = Field(alias="trainRows")
    test_rows: int = Field(alias="testRows")
    dropped_target_rows: int = Field(alias="droppedTargetRows")
    confusion_matrix: dict[str, Any] | None = Field(alias="confusionMatrix")
    evaluation_samples: tuple[dict[str, Any], ...] = Field(alias="evaluationSamples")


def require_internal_training_token(
    authorization: str | None = Header(default=None, alias="Authorization"),
) -> None:
    configured_token = settings.model_training_internal_token
    if not configured_token:
        raise HTTPException(status_code=503, detail="内部模型训练入口未配置服务令牌")
    expected = f"Bearer {configured_token}"
    if authorization is None or not hmac.compare_digest(authorization, expected):
        raise HTTPException(
            status_code=401,
            detail="无权调用内部模型训练入口",
            headers={"WWW-Authenticate": "Bearer"},
        )


def get_artifact_store() -> ModelArtifactStore:
    return ModelArtifactStore(settings.model_artifact_directory)


@router.post("", response_model=TrainingRunResponse, status_code=201)
def create_training_run(
    request: TrainingRunRequest,
    _: None = Depends(require_internal_training_token),
    artifact_store: ModelArtifactStore = Depends(get_artifact_store),
) -> TrainingRunResponse:
    feature_columns = tuple(request.feature_columns)
    required_columns = (*feature_columns, request.target_column)
    if len(set(required_columns)) != len(required_columns):
        raise HTTPException(status_code=422, detail="目标字段不能重复出现在特征字段中")
    if len(request.rows) * len(required_columns) > MAX_TRAINING_CELLS:
        raise HTTPException(status_code=422, detail="训练数据超过单次行数与字段数资源上限")
    required_set = set(required_columns)
    for row in request.rows:
        missing_columns = required_set - set(row)
        if missing_columns:
            raise HTTPException(
                status_code=422,
                detail=f"训练数据缺少所选字段：{', '.join(sorted(missing_columns))}",
            )
        extra_columns = set(row) - required_set
        if extra_columns:
            raise HTTPException(status_code=422, detail="训练数据包含未选择的字段")
        for value in row.values():
            if isinstance(value, (dict, list, tuple, set)):
                raise HTTPException(status_code=422, detail="训练字段必须是标量值")
            if isinstance(value, str) and len(value) > MAX_CELL_CHARACTERS:
                raise HTTPException(status_code=422, detail="单个训练字段值不能超过 8,192 个字符")

    frame = pd.DataFrame(request.rows)
    missing_columns = [name for name in required_columns if name not in frame.columns]
    if missing_columns:
        raise HTTPException(
            status_code=422,
            detail=f"训练数据缺少所选字段：{', '.join(missing_columns)}",
        )

    try:
        spec = TrainingSpec(
            task=request.task,
            target_column=request.target_column,
            feature_columns=feature_columns,
            algorithm=request.algorithm,
            test_size=request.test_size,
            random_state=request.random_state,
        )
        result = training_engine.train(frame.loc[:, required_columns], spec)
    except ValueError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error

    manifest = artifact_store.save(
        result,
        owner_id=request.owner_id,
        dataset_id=str(request.dataset_id),
        dataset_version_id=str(request.dataset_version_id),
        table_id=str(request.table_id),
    )
    return TrainingRunResponse(
        artifact_id=manifest["artifactId"],
        dataset_id=request.dataset_id,
        dataset_version_id=request.dataset_version_id,
        table_id=request.table_id,
        task=result.task,
        algorithm=result.algorithm,
        target_column=result.target_column,
        feature_columns=result.feature_columns,
        feature_types=result.feature_types,
        test_size=result.test_size,
        random_state=result.random_state,
        metrics=result.metrics,
        train_rows=result.train_rows,
        test_rows=result.test_rows,
        dropped_target_rows=result.dropped_target_rows,
        confusion_matrix=result.confusion_matrix,
        evaluation_samples=result.evaluation_samples,
    )
