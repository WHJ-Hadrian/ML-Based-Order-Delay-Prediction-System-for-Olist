"""Reusable, configuration-driven tabular model training."""

from __future__ import annotations

import math
import time
from dataclasses import dataclass, field
from typing import Any, Literal

import numpy as np
import pandas as pd
from sklearn.compose import ColumnTransformer
from sklearn.impute import SimpleImputer
from sklearn.metrics import f1_score
from sklearn.model_selection import KFold, StratifiedKFold, train_test_split
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler
from pandas.api.types import is_bool_dtype, is_numeric_dtype

from app.ml.training.algorithms import (
    algorithms_for,
    build_estimator,
    requires_scaling,
    training_row_limit,
)
from app.ml.training.explainability import explain_model
from app.ml.training.metrics import evaluate_detailed, evaluate_predictions
from app.ml.training.splitting import SplitSpec, split_positions

TaskType = Literal["classification", "regression"]
Algorithm = Literal[
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
    split_strategy: Literal["random", "temporal", "group"] = "random"
    validation_size: float = 0.0
    time_column: str | None = None
    group_column: str | None = None
    explain: bool = True
    cv_folds: int = 0

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
        if self.split_strategy == "temporal" and self.time_column in self.feature_columns:
            raise ValueError("时间字段不能同时作为输入特征")
        if self.split_strategy == "group" and self.group_column in self.feature_columns:
            raise ValueError("分组字段不能同时作为输入特征，避免分组泄漏")
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

        if self.cv_folds not in (0, 3, 5, 10):
            raise ValueError("交叉验证折数必须是 0、3、5 或 10")
        if self.algorithm not in algorithms_for(self.task):
            raise ValueError("当前任务类型不支持该算法")
        SplitSpec(
            strategy=self.split_strategy,
            test_size=self.test_size,
            validation_size=self.validation_size,
            random_state=self.random_state,
            time_column=self.time_column,
            group_column=self.group_column,
        )


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
    dataset_fingerprint: str | None = None
    split_summary: dict[str, Any] = field(default_factory=dict)
    metric_details: dict[str, Any] = field(default_factory=dict)
    decision_threshold: float | None = None
    calibration_rows: int = 0
    validation_metrics: dict[str, float] = field(default_factory=dict)
    validation_metric_details: dict[str, Any] = field(default_factory=dict)
    validation_rows: int = 0
    explainability: dict[str, Any] = field(default_factory=dict)
    training_seconds: float = 0.0


class ModelTrainingEngine:
    """Train CPU-bounded scikit-learn pipelines from a selected tabular schema."""

    def train(
        self,
        data: pd.DataFrame,
        spec: TrainingSpec,
        *,
        dataset_fingerprint: str | None = None,
    ) -> TrainingResult:
        started = time.perf_counter()
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

        split = split_positions(
            frame,
            SplitSpec(
                strategy=spec.split_strategy,
                test_size=spec.test_size,
                validation_size=spec.validation_size,
                random_state=spec.random_state,
                time_column=spec.time_column,
                group_column=spec.group_column,
            ),
            target=target,
            task=spec.task,
        )
        x_train = frame.loc[split.train_indices, list(spec.feature_columns)].reset_index(drop=True)
        y_train = target.iloc[split.train_indices].reset_index(drop=True)
        x_test = frame.loc[split.test_indices, list(spec.feature_columns)].reset_index(drop=True)
        y_test = target.iloc[split.test_indices].reset_index(drop=True)
        test_positions = frame[source_row_column].to_numpy(dtype=np.int64)[split.test_indices]

        cv_summary: dict[str, Any] = {}
        x_fit, y_fit = x_train, y_train
        x_calibration = y_calibration = None
        calibration_rows = 0
        threshold_source = "train_f1"
        decision_threshold = None
        if spec.cv_folds >= 3:
            oof_probabilities, cv_summary = self._cross_validate(
                spec, feature_types, x_train, y_train
            )
            decision_threshold = self._threshold_from_scores(
                y_train, oof_probabilities, spec.task
            )
            calibration_rows = len(x_train)
            threshold_source = "cross_validation_oof_f1"
            x_fit, y_fit = self._limit_training_rows(
                x_train, y_train, spec.algorithm, spec.random_state, spec.task
            )
        else:
            if spec.task == "classification" and len(x_train) >= 200:
                try:
                    x_fit, x_calibration, y_fit, y_calibration = train_test_split(
                        x_train, y_train, test_size=0.15,
                        random_state=spec.random_state, stratify=y_train
                    )
                except ValueError:
                    x_fit, x_calibration, y_fit, y_calibration = train_test_split(
                        x_train, y_train, test_size=0.15, random_state=spec.random_state
                    )
                calibration_rows = len(x_calibration)
                threshold_source = "calibration_holdout_f1"
            x_fit, y_fit = self._limit_training_rows(
                x_fit, y_fit, spec.algorithm, spec.random_state, spec.task
            )

        pipeline = self._build_pipeline(spec, feature_types, y_fit)
        try:
            pipeline.fit(x_fit, y_fit)
            if spec.cv_folds < 3:
                if calibration_rows:
                    decision_threshold = self._select_threshold(
                        pipeline, x_calibration, y_calibration, spec.task
                    )
                else:
                    decision_threshold = self._select_threshold(
                        pipeline, x_train, y_train, spec.task
                    )
            probabilities = self._probabilities(pipeline, x_test, spec.task)
            predictions = self._predict_with_threshold(
                pipeline, x_test, spec.task, decision_threshold, probabilities
            )
        except (TypeError, ValueError) as error:
            raise ValueError(f"模型训练失败：{error}") from error

        metrics, matrix = self._evaluate(spec.task, y_test, predictions)
        metrics, metric_details, matrix = evaluate_detailed(
            spec.task,
            y_test,
            predictions,
            probabilities=probabilities,
            classes=getattr(pipeline, "classes_", None),
        )
        if decision_threshold is not None:
            metric_details["decisionThreshold"] = decision_threshold
            metric_details["thresholdSource"] = threshold_source
            metric_details["calibrationRows"] = calibration_rows
        if cv_summary:
            metric_details["crossValidation"] = cv_summary
        validation_metrics: dict[str, float] = {}
        validation_metric_details: dict[str, Any] = {}
        if len(split.validation_indices):
            x_validation = frame.loc[split.validation_indices, list(spec.feature_columns)]
            y_validation = target.iloc[split.validation_indices].reset_index(drop=True)
            validation_probabilities = self._probabilities(pipeline, x_validation, spec.task)
            validation_predictions = self._predict_with_threshold(
                pipeline, x_validation, spec.task, decision_threshold, validation_probabilities
            )
            validation_metrics, validation_metric_details, _ = evaluate_detailed(
                spec.task,
                y_validation,
                validation_predictions,
                probabilities=validation_probabilities,
                classes=getattr(pipeline, "classes_", None),
            )
        explainability = (
            explain_model(
                pipeline,
                x_test,
                y_test,
                task=spec.task,
                random_state=spec.random_state,
                algorithm=spec.algorithm,
            )
            if spec.explain
            else {
                "method": "disabled",
                "available": False,
                "reason": "本次训练未启用模型解释",
                "globalImportance": [],
            }
        )
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
            dataset_fingerprint=dataset_fingerprint,
            split_summary=split.summary,
            metric_details=metric_details,
            decision_threshold=decision_threshold,
            calibration_rows=calibration_rows,
            validation_metrics=validation_metrics,
            validation_metric_details=validation_metric_details,
            validation_rows=len(split.validation_indices),
            explainability=explainability,
            training_seconds=round(time.perf_counter() - started, 4),
        )

    def _select_and_validate_columns(
        self, data: pd.DataFrame, spec: TrainingSpec
    ) -> pd.DataFrame:
        if not isinstance(data, pd.DataFrame):
            raise ValueError("训练输入必须是表格数据")
        if not data.columns.is_unique:
            raise ValueError("数据表存在重名字段，请先在数据准备流程中重命名字段")

        selected_columns = [*spec.feature_columns, spec.target_column]
        if spec.split_strategy == "temporal" and spec.time_column:
            selected_columns.append(spec.time_column)
        if spec.split_strategy == "group" and spec.group_column:
            selected_columns.append(spec.group_column)
        selected_columns = list(dict.fromkeys(selected_columns))
        missing = [name for name in selected_columns if name not in data.columns]
        if missing:
            raise ValueError(f"所选字段不存在于数据表中：{', '.join(missing)}")
        if len(data) > MAX_TRAINING_ROWS:
            raise ValueError(f"单次训练最多处理 {MAX_TRAINING_ROWS} 行，请先通过抽样节点缩小数据量")
        if data.empty:
            raise ValueError("训练数据不能为空")
        return data.loc[:, selected_columns].reset_index(drop=True).copy()

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

    def _build_pipeline(
        self,
        spec: TrainingSpec,
        feature_types: dict[str, str],
        target: pd.Series | None = None,
    ) -> Pipeline:
        scale_numeric = requires_scaling(spec.algorithm)
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
        estimator = build_estimator(
            spec.algorithm, spec.task, spec.random_state, target
        )
        return Pipeline([("preprocess", preprocessor), ("model", estimator)])

    def _limit_training_rows(
        self,
        features: pd.DataFrame,
        target: pd.Series,
        algorithm: str,
        random_state: int,
        task: str,
    ) -> tuple[pd.DataFrame, pd.Series]:
        limit = training_row_limit(algorithm, len(features))
        if limit >= len(features):
            return features.reset_index(drop=True), target.reset_index(drop=True)
        if task == "classification" and target.value_counts().min() >= 2:
            try:
                sampled_features, _, sampled_target, _ = train_test_split(
                    features, target, train_size=limit,
                    random_state=random_state, stratify=target,
                )
                return sampled_features.reset_index(drop=True), sampled_target.reset_index(drop=True)
            except ValueError:
                pass
        sampled = features.sample(n=limit, random_state=random_state)
        return sampled.reset_index(drop=True), target.loc[sampled.index].reset_index(drop=True)

    def _cross_validate(
        self,
        spec: TrainingSpec,
        feature_types: dict[str, str],
        features: pd.DataFrame,
        target: pd.Series,
    ) -> tuple[np.ndarray | None, dict[str, Any]]:
        features, target = self._limit_training_rows(
            features, target, spec.algorithm, spec.random_state, spec.task
        )
        if len(features) < max(30, spec.cv_folds * 5):
            raise ValueError(
                f"交叉验证至少需要 {max(30, spec.cv_folds * 5)} 行有效数据，当前只有 {len(features)} 行"
            )
        if spec.task == "classification":
            splitter = StratifiedKFold(
                n_splits=spec.cv_folds, shuffle=True, random_state=spec.random_state
            )
            split_iterator = splitter.split(features, target)
        else:
            splitter = KFold(
                n_splits=spec.cv_folds, shuffle=True, random_state=spec.random_state
            )
            split_iterator = splitter.split(features)

        fold_metrics: list[dict[str, Any]] = []
        oof_probabilities = (
            np.zeros(len(features), dtype=float) if spec.task == "classification" else None
        )
        for fold, (train_positions, validation_positions) in enumerate(split_iterator, start=1):
            x_fold_train = features.iloc[train_positions].reset_index(drop=True)
            y_fold_train = target.iloc[train_positions].reset_index(drop=True)
            x_fold_validation = features.iloc[validation_positions].reset_index(drop=True)
            y_fold_validation = target.iloc[validation_positions].reset_index(drop=True)
            pipeline = self._build_pipeline(spec, feature_types, y_fold_train)
            pipeline.fit(x_fold_train, y_fold_train)
            threshold = self._select_threshold(
                pipeline, x_fold_train, y_fold_train, spec.task
            )
            probabilities = self._probabilities(pipeline, x_fold_validation, spec.task)
            predictions = self._predict_with_threshold(
                pipeline, x_fold_validation, spec.task, threshold, probabilities
            )
            metrics, detail, _ = evaluate_detailed(
                spec.task,
                y_fold_validation,
                predictions,
                probabilities=probabilities,
                classes=getattr(pipeline, "classes_", None),
            )
            row: dict[str, Any] = {"fold": fold, **metrics}
            for key in (
                "rocAuc", "prAuc", "f1Macro", "precisionMacro", "recallMacro",
                "logLoss", "mapePercent", "residualStd",
            ):
                if detail.get(key) is not None:
                    row[key] = detail[key]
            fold_metrics.append(row)
            if oof_probabilities is not None and probabilities is not None:
                classes = np.asarray(getattr(pipeline, "classes_", ()))
                if len(classes) == 2:
                    oof_probabilities[validation_positions] = probabilities[:, 1]

        numeric_keys = sorted({
            key
            for row in fold_metrics
            for key, value in row.items()
            if key != "fold" and isinstance(value, (int, float)) and np.isfinite(value)
        })
        mean = {
            key: round(float(np.mean([row[key] for row in fold_metrics if key in row])), 6)
            for key in numeric_keys
        }
        std = {
            key: round(float(np.std([row[key] for row in fold_metrics if key in row])), 6)
            for key in numeric_keys
        }
        summary = {
            "folds": spec.cv_folds,
            "strategy": "stratified_kfold" if spec.task == "classification" else "kfold",
            "rowCount": len(features),
            "foldMetrics": fold_metrics,
            "mean": mean,
            "std": std,
            "warnings": [],
        }
        return oof_probabilities, summary

    def _evaluate(
        self, task: TaskType, actual: pd.Series, predicted: np.ndarray
    ) -> tuple[dict[str, float], dict[str, Any] | None]:
        return evaluate_predictions(task, actual, predicted)

    def _probabilities(self, pipeline: Pipeline, features: pd.DataFrame, task: TaskType) -> Any | None:
        if task != "classification" or not hasattr(pipeline, "predict_proba"):
            return None
        try:
            return pipeline.predict_proba(features)
        except (AttributeError, ValueError):
            return None

    def _select_threshold(
        self, pipeline: Pipeline, features: pd.DataFrame, truth: pd.Series, task: TaskType
    ) -> float | None:
        if task != "classification":
            return None
        probabilities = self._probabilities(pipeline, features, task)
        classes = np.asarray(getattr(pipeline, "classes_", ()))
        if probabilities is None or len(classes) != 2:
            return None
        positive = (truth.astype(str).to_numpy() == str(classes[1])).astype(int)
        if positive.min() == positive.max():
            return None
        return self._threshold_from_scores(truth, probabilities[:, 1], task)

    def _threshold_from_scores(
        self, truth: pd.Series, scores: np.ndarray | None, task: TaskType
    ) -> float | None:
        if task != "classification" or scores is None:
            return None
        positive_labels = sorted({str(value) for value in truth.tolist()})
        if len(positive_labels) != 2:
            return None
        positive = (truth.astype(str).to_numpy() == positive_labels[1]).astype(int)
        if positive.min() == positive.max():
            return None
        values = np.asarray(scores, dtype=float)
        if len(values) != len(positive):
            return None
        best_threshold = 0.5
        best_f1 = -1.0
        for threshold in np.linspace(0.05, 0.95, 91):
            predicted = (values >= threshold).astype(int)
            score = float(f1_score(positive, predicted, zero_division=0))
            if score > best_f1 + 1e-12:
                best_f1 = score
                best_threshold = float(threshold)
        return round(best_threshold, 4)

    def _predict_with_threshold(
        self,
        pipeline: Pipeline,
        features: pd.DataFrame,
        task: TaskType,
        threshold: float | None,
        probabilities: Any | None,
    ) -> np.ndarray:
        classes = np.asarray(getattr(pipeline, "classes_", ()))
        if task != "classification" or probabilities is None or not len(classes):
            return pipeline.predict(features)
        if len(classes) == 2 and threshold is not None:
            return np.where(
                probabilities[:, 1] >= float(threshold), classes[1], classes[0]
            )
        return classes[np.argmax(probabilities, axis=1)]

    def _json_value(self, value: Any) -> Any:
        if isinstance(value, np.generic):
            return value.item()
        return value
