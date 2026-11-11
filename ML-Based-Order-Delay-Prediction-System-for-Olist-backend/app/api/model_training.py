"""Internal model-training entrypoint for the model-management service."""

from __future__ import annotations

import hmac
from typing import Any, Literal
from uuid import UUID

from fastapi import APIRouter, Depends, Header, HTTPException
from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.config import settings
from app.ml.training.artifacts import ModelArtifactStore
from app.ml.training.datasource import (
    DatasetExportSource,
    DatasetSourceError,
    load_training_data,
)
from app.ml.training.engine import ModelTrainingEngine, TrainingSpec

router = APIRouter(prefix="/internal/v1/model-training-runs", include_in_schema=False)
training_engine = ModelTrainingEngine()
MAX_TRAINING_CELLS = 2_000_000
MAX_TRAINING_ROWS = 100_000
MAX_CELL_CHARACTERS = 8_192


class DatasetExportSourceRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    dataset_version_id: UUID = Field(alias="datasetVersionId")
    table_id: UUID = Field(alias="tableId")
    owner_id: str = Field(alias="ownerId", min_length=1, max_length=255)
    columns: list[str] = Field(min_length=1, max_length=200)
    max_rows: int = Field(default=100_000, alias="maxRows", strict=True, ge=1, le=100_000)


class TrainingRunRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    owner_id: str = Field(alias="ownerId", min_length=1, max_length=255)
    dataset_id: UUID = Field(alias="datasetId")
    dataset_version_id: UUID = Field(alias="datasetVersionId")
    table_id: UUID = Field(alias="tableId")
    rows: list[dict[str, Any]] | None = Field(default=None, max_length=100_000)
    data_source: DatasetExportSourceRequest | None = Field(default=None, alias="dataSource")
    task: Literal["classification", "regression"]
    algorithm: Literal[
        "random_forest",
        "logistic_regression",
        "decision_tree",
        "xgboost",
        "lightgbm",
        "svm",
        "knn",
        "ridge_regression",
        "linear_regression",
        "xgboost_regression",
        "lightgbm_regression",
    ]
    target_column: str = Field(alias="targetColumn", min_length=1, max_length=255)
    feature_columns: list[str] = Field(alias="featureColumns", min_length=1, max_length=200)
    test_size: float = Field(default=0.2, alias="testSize", strict=True, ge=0.1, le=0.4)
    validation_size: float = Field(default=0.0, alias="validationSize", strict=True, ge=0.0, lt=0.4)
    random_state: int = Field(default=42, alias="randomState", strict=True, ge=0, le=2**32 - 1)
    split_strategy: Literal["random", "temporal", "group"] = Field(default="random", alias="splitStrategy")
    time_column: str | None = Field(default=None, alias="timeColumn", max_length=255)
    group_column: str | None = Field(default=None, alias="groupColumn", max_length=255)
    cv_folds: int = Field(default=0, alias="cvFolds", strict=True)
    explain: bool = True

    @model_validator(mode="after")
    def require_single_data_channel(self) -> "TrainingRunRequest":
        if (self.rows is None) == (self.data_source is None):
            raise ValueError("必须且只能提供 rows 或 dataSource")
        if self.rows is not None and len(self.rows) < 12:
            raise ValueError("行内训练数据至少需要 12 行")
        if self.data_source is not None and self.data_source.owner_id != self.owner_id:
            raise ValueError("dataSource.ownerId 必须与 ownerId 一致")
        return self


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
    schema_version: int = Field(default=2, alias="schemaVersion")
    dataset_fingerprint: str | None = Field(default=None, alias="datasetFingerprint")
    split_summary: dict[str, Any] = Field(default_factory=dict, alias="splitSummary")
    metric_details: dict[str, Any] = Field(default_factory=dict, alias="metricDetails")
    validation_metrics: dict[str, float] = Field(default_factory=dict, alias="validationMetrics")
    validation_metric_details: dict[str, Any] = Field(default_factory=dict, alias="validationMetricDetails")
    validation_rows: int = Field(default=0, alias="validationRows")
    explainability: dict[str, Any] = Field(default_factory=dict, alias="explainability")
    training_seconds: float = Field(default=0.0, alias="trainingSeconds")
    artifact_size_bytes: int | None = Field(default=None, alias="artifactSizeBytes")
    artifact_sha256: str | None = Field(default=None, alias="artifactSha256")


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
    split_columns: list[str] = []
    if request.split_strategy == "temporal" and request.time_column:
        split_columns.append(request.time_column)
    if request.split_strategy == "group" and request.group_column:
        split_columns.append(request.group_column)
    required_columns = tuple(dict.fromkeys((*feature_columns, request.target_column, *split_columns)))
    if len(set(required_columns)) != len(required_columns):
        raise HTTPException(status_code=422, detail="目标字段不能重复出现在特征字段中")
    if request.rows is not None and len(request.rows) * len(required_columns) > MAX_TRAINING_CELLS:
        raise HTTPException(status_code=422, detail="训练数据超过单次行数与字段数资源上限")
    required_set = set(required_columns)
    if request.rows is not None:
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

    export_source = None
    if request.data_source is not None:
        source = request.data_source
        if source.dataset_version_id != request.dataset_version_id or source.table_id != request.table_id:
            raise HTTPException(status_code=422, detail="dataSource 的数据集版本与数据表必须与训练请求一致")
        if set(source.columns) != required_set:
            raise HTTPException(
                status_code=422,
                detail="dataSource.columns 必须与训练特征、目标及切分字段完全一致",
            )
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
            required_columns=required_columns,
            max_rows=MAX_TRAINING_ROWS,
            max_cells=MAX_TRAINING_CELLS,
            timeout_seconds=settings.model_training_dataset_timeout_seconds,
        )
    except DatasetSourceError as error:
        raise HTTPException(status_code=error.status_code, detail=str(error)) from error

    try:
        spec = TrainingSpec(
            task=request.task,
            target_column=request.target_column,
            feature_columns=feature_columns,
            algorithm=request.algorithm,
            test_size=request.test_size,
            validation_size=request.validation_size,
            random_state=request.random_state,
            split_strategy=request.split_strategy,
            time_column=request.time_column,
            group_column=request.group_column,
            explain=request.explain,
            cv_folds=request.cv_folds,
        )
        result = training_engine.train(
            loaded.frame.loc[:, required_columns],
            spec,
            dataset_fingerprint=loaded.fingerprint,
        )
    except ValueError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error

    manifest = artifact_store.save(
        result,
        owner_id=request.owner_id,
        dataset_id=str(request.dataset_id),
        dataset_version_id=str(request.dataset_version_id),
        table_id=str(request.table_id),
        dataset_fingerprint=loaded.fingerprint,
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
        schema_version=manifest["schemaVersion"],
        dataset_fingerprint=result.dataset_fingerprint,
        split_summary=result.split_summary,
        metric_details=result.metric_details,
        validation_metrics=result.validation_metrics,
        validation_metric_details=result.validation_metric_details,
        validation_rows=result.validation_rows,
        explainability=result.explainability,
        training_seconds=result.training_seconds,
        artifact_size_bytes=manifest["artifactSizeBytes"],
        artifact_sha256=manifest["artifactSha256"],
    )
