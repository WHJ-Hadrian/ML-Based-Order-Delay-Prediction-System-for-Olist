"""Reusable, configuration-driven tabular model training."""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Any, Literal

import numpy as np
import pandas as pd
from sklearn.compose import ColumnTransformer
from sklearn.ensemble import RandomForestClassifier, RandomForestRegressor
from sklearn.impute import SimpleImputer
from sklearn.linear_model import LogisticRegression, Ridge
from sklearn.model_selection import train_test_split
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler
from pandas.api.types import is_bool_dtype, is_numeric_dtype

from app.ml.training.metrics import evaluate_predictions

TaskType = Literal["classification", "regression"]
Algorithm = Literal["random_forest", "logistic_regression", "ridge_regression"]

MIN_TRAINING_ROWS = 12
MAX_TRAINING_ROWS = 100_000
MAX_FEATURES = 200
MAX_EVALUATION_SAMPLES = 50
MAX_CATEGORY_COUNT = 100


@dataclass(frozen=True, slots=True)
class TrainingSpec:
    task: TaskType
    target_column: str
    feature_columns: tuple[str, ...]
    algorithm: Algorithm
    test_size: float = 0.2
    random_state: int = 42

    def __post_init__(self) -> None:
        if self.task not in ("classification", "regression"):
            raise ValueError("任务类型必须是 classification 或 regression")
        if not self.target_column or not self.target_column.strip():
            raise ValueError("目标字段不能为空")
        if not self.feature_columns:
            raise ValueError("至少选择一个特征字段")
        if len(self.feature_columns) > MAX_FEATURES:
            raise ValueError(f"特征字段不能超过 {MAX_FEATURES} 个")
        if any(not name or not name.strip() for name in self.feature_columns):
            raise ValueError("特征字段名称不能为空")
        if len(set(self.feature_columns)) != len(self.feature_columns):
            raise ValueError("特征字段不能重复")
        if self.target_column in self.feature_columns:
            raise ValueError("目标字段不能同时作为特征字段，避免目标泄漏")
        if (
            isinstance(self.test_size, bool)
            or not isinstance(self.test_size, (int, float))
            or not math.isfinite(self.test_size)
            or not 0.1 <= self.test_size <= 0.4
        ):
            raise ValueError("测试集比例需在 0.1 到 0.4 之间")
        if (
            isinstance(self.random_state, bool)
            or not isinstance(self.random_state, int)
            or not 0 <= self.random_state <= 2**32 - 1
        ):
            raise ValueError("随机种子必须是 0 到 2^32-1 之间的整数")

        allowed = {
            "classification": {"random_forest", "logistic_regression"},
            "regression": {"random_forest", "ridge_regression"},
        }
        if self.algorithm not in allowed[self.task]:
            raise ValueError("所选算法与任务类型不兼容")


@dataclass(frozen=True, slots=True)
class TrainingResult:
    pipeline: Pipeline
    task: TaskType
    algorithm: Algorithm
    target_column: str
    feature_columns: tuple[str, ...]
    feature_types: dict[str, str]
    test_size: float
    random_state: int
    metrics: dict[str, float]
    train_rows: int
    test_rows: int
    dropped_target_rows: int
    confusion_matrix: dict[str, Any] | None
    evaluation_samples: tuple[dict[str, Any], ...]


class ModelTrainingEngine:
    """Train CPU-bounded scikit-learn pipelines from a selected tabular schema."""

    def train(self, data: pd.DataFrame, spec: TrainingSpec) -> TrainingResult:
        frame = self._select_and_validate_columns(data, spec)
        original_row_count = len(frame)
        source_row_column = self._source_row_column(frame.columns)
        frame[source_row_column] = np.arange(original_row_count, dtype=np.int64)
        frame = self._normalize_target(frame, spec)
        dropped_target_rows = original_row_count - len(frame)

        if len(frame) < MIN_TRAINING_ROWS:
            raise ValueError(
                f"清理目标字段缺失值后至少需要 {MIN_TRAINING_ROWS} 行，当前为 {len(frame)} 行"
            )
        if len(frame) > MAX_TRAINING_ROWS:
            raise ValueError(f"单次训练最多处理 {MAX_TRAINING_ROWS} 行，请先通过抽样节点缩小数据量")

        feature_types = self._normalize_features(frame, spec.feature_columns)
        target = frame[spec.target_column].reset_index(drop=True)
        if spec.task == "classification":
            target = target.astype(str)
            if target.nunique() < 2:
                raise ValueError("分类任务的目标字段至少需要两个类别")
        else:
            target = pd.to_numeric(target, errors="coerce").reset_index(drop=True)
            valid_target = target.notna() & np.isfinite(target.to_numpy(dtype=float))
            dropped = int((~valid_target).sum())
            if dropped:
                frame = frame.loc[valid_target.to_numpy()].reset_index(drop=True)
                target = target.loc[valid_target].reset_index(drop=True)
                dropped_target_rows += dropped
            if len(frame) < MIN_TRAINING_ROWS:
                raise ValueError(
                    f"清理目标字段后至少需要 {MIN_TRAINING_ROWS} 行，当前为 {len(frame)} 行"
                )

        positions = frame[source_row_column].to_numpy(dtype=np.int64)
        stratify = None
        if spec.task == "classification":
            class_counts = target.value_counts()
            test_rows = math.ceil(len(target) * spec.test_size)
            train_rows = len(target) - test_rows
            if class_counts.min() < 2:
                raise ValueError("每个目标类别至少需要 2 条记录，才能划分训练集和测试集")
            if test_rows < len(class_counts) or train_rows < len(class_counts):
                raise ValueError("当前测试集比例不足以覆盖所有类别，请增加数据或调整测试集比例")
            stratify = target

        try:
            x_train, x_test, y_train, y_test, _, test_positions = train_test_split(
                frame.loc[:, spec.feature_columns],
                target,
                positions,
                test_size=spec.test_size,
                random_state=spec.random_state,
                stratify=stratify,
            )
        except ValueError as error:
            raise ValueError(f"无法划分训练集和测试集：{error}") from error

        pipeline = self._build_pipeline(spec, feature_types)
        try:
            pipeline.fit(x_train, y_train)
            predictions = pipeline.predict(x_test)
        except (TypeError, ValueError) as error:
            raise ValueError(f"字段无法用于当前算法：{error}") from error

        metrics, matrix = self._evaluate(spec.task, y_test, predictions)
        samples = tuple(
            {
                "row": int(position) + 1,
                "actual": self._json_value(actual),
                "predicted": self._json_value(predicted),
            }
            for position, actual, predicted in zip(
                test_positions[:MAX_EVALUATION_SAMPLES],
                y_test.iloc[:MAX_EVALUATION_SAMPLES],
                predictions[:MAX_EVALUATION_SAMPLES],
                strict=True,
            )
        )

        return TrainingResult(
            pipeline=pipeline,
            task=spec.task,
            algorithm=spec.algorithm,
            target_column=spec.target_column,
            feature_columns=spec.feature_columns,
            feature_types=feature_types,
            test_size=spec.test_size,
            random_state=spec.random_state,
            metrics=metrics,
            train_rows=len(x_train),
            test_rows=len(x_test),
            dropped_target_rows=dropped_target_rows,
            confusion_matrix=matrix,
            evaluation_samples=samples,
        )

    def _select_and_validate_columns(
        self, data: pd.DataFrame, spec: TrainingSpec
    ) -> pd.DataFrame:
        if not isinstance(data, pd.DataFrame):
            raise ValueError("训练输入必须是表格数据")
        if not data.columns.is_unique:
            raise ValueError("数据表存在重名字段，请先在数据准备流程中重命名字段")

        missing = [
            name
            for name in (*spec.feature_columns, spec.target_column)
            if name not in data.columns
        ]
        if missing:
            raise ValueError(f"所选字段不存在于数据表中：{', '.join(missing)}")
        if len(data) > MAX_TRAINING_ROWS:
            raise ValueError(f"单次训练最多处理 {MAX_TRAINING_ROWS} 行，请先通过抽样节点缩小数据量")
        if data.empty:
            raise ValueError("训练数据不能为空")
        return data.loc[:, [*spec.feature_columns, spec.target_column]].reset_index(drop=True).copy()

    def _source_row_column(self, columns: pd.Index) -> str:
        name = "__training_source_row__"
        while name in columns:
            name += "_"
        return name

    def _normalize_target(self, frame: pd.DataFrame, spec: TrainingSpec) -> pd.DataFrame:
        if spec.task == "classification":
            values = frame[spec.target_column].astype("string").str.strip()
            frame[spec.target_column] = values.replace("", pd.NA)
            return frame.dropna(subset=[spec.target_column]).reset_index(drop=True)

        numeric = pd.to_numeric(frame[spec.target_column], errors="coerce")
        finite = numeric.notna() & np.isfinite(numeric.to_numpy(dtype=float))
        frame[spec.target_column] = numeric
        return frame.loc[finite].reset_index(drop=True)

    def _normalize_features(self, frame: pd.DataFrame, features: tuple[str, ...]) -> dict[str, str]:
        feature_types: dict[str, str] = {}
        for name in features:
            series = frame[name]
            if is_numeric_dtype(series.dtype) and not is_bool_dtype(series.dtype):
                numeric = pd.to_numeric(series, errors="coerce")
                frame[name] = numeric.replace([np.inf, -np.inf], np.nan)
                if frame[name].notna().any():
                    feature_types[name] = "numeric"
                    continue
                raise ValueError(f"特征字段“{name}”没有可用的非空数值")

            normalized = series.map(lambda value: np.nan if pd.isna(value) else str(value))
            if normalized.notna().any():
                frame[name] = normalized
                feature_types[name] = "categorical"
                continue
            raise ValueError(f"特征字段“{name}”全部为空，无法用于训练")
        return feature_types

    def _build_pipeline(self, spec: TrainingSpec, feature_types: dict[str, str]) -> Pipeline:
        scale_numeric = spec.algorithm in {"logistic_regression", "ridge_regression"}
        transformers = []

        numeric = [name for name, kind in feature_types.items() if kind == "numeric"]
        categorical = [name for name, kind in feature_types.items() if kind == "categorical"]
        if numeric:
            numeric_steps: list[tuple[str, Any]] = [
                ("imputer", SimpleImputer(strategy="median", keep_empty_features=True))
            ]
            if scale_numeric:
                numeric_steps.append(("scaler", StandardScaler()))
            transformers.append(("numeric", Pipeline(numeric_steps), numeric))
        if categorical:
            categorical_pipeline = Pipeline(
                [
                    ("imputer", SimpleImputer(strategy="constant", fill_value="(missing)")),
                    (
                        "encoder",
                        OneHotEncoder(
                            handle_unknown="ignore",
                            max_categories=MAX_CATEGORY_COUNT,
                        ),
                    ),
                ]
            )
            transformers.append(("categorical", categorical_pipeline, categorical))

        preprocessor = ColumnTransformer(transformers, remainder="drop", sparse_threshold=0.3)
        if spec.algorithm == "logistic_regression":
            estimator = LogisticRegression(max_iter=1000, random_state=spec.random_state)
        elif spec.algorithm == "ridge_regression":
            estimator = Ridge(alpha=1.0)
        elif spec.task == "classification":
            estimator = RandomForestClassifier(
                n_estimators=160,
                min_samples_leaf=2,
                random_state=spec.random_state,
                n_jobs=1,
            )
        else:
            estimator = RandomForestRegressor(
                n_estimators=160,
                min_samples_leaf=2,
                random_state=spec.random_state,
                n_jobs=1,
            )
        return Pipeline([("preprocess", preprocessor), ("model", estimator)])

    def _evaluate(
        self, task: TaskType, actual: pd.Series, predicted: np.ndarray
    ) -> tuple[dict[str, float], dict[str, Any] | None]:
        return evaluate_predictions(task, actual, predicted)

    def _json_value(self, value: Any) -> Any:
        if isinstance(value, np.generic):
            return value.item()
        return value
