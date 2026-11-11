"""Algorithm catalog and estimator factory for tabular training."""

from __future__ import annotations

from typing import Any

import numpy as np
from sklearn.base import BaseEstimator, ClassifierMixin, clone
from sklearn.calibration import CalibratedClassifierCV
from sklearn.ensemble import RandomForestClassifier, RandomForestRegressor
from sklearn.preprocessing import LabelEncoder
from sklearn.linear_model import LinearRegression, LogisticRegression, Ridge
from sklearn.neighbors import KNeighborsClassifier
from sklearn.svm import SVC
from sklearn.tree import DecisionTreeClassifier, DecisionTreeRegressor

try:
    from lightgbm import LGBMClassifier, LGBMRegressor

    LIGHTGBM_AVAILABLE = True
except Exception:  # pragma: no cover - optional dependency
    LGBMClassifier = LGBMRegressor = None
    LIGHTGBM_AVAILABLE = False

try:
    from xgboost import XGBClassifier, XGBRegressor

    XGBOOST_AVAILABLE = True
except Exception:  # pragma: no cover - optional dependency
    XGBClassifier = XGBRegressor = None
    XGBOOST_AVAILABLE = False

CLASSIFICATION_ALGORITHMS = (
    "random_forest",
    "logistic_regression",
    "decision_tree",
    "xgboost",
    "lightgbm",
    "svm",
    "knn",
)

REGRESSION_ALGORITHMS = (
    "random_forest",
    "ridge_regression",
    "linear_regression",
    "decision_tree",
    "xgboost_regression",
    "lightgbm_regression",
)

SCALED_ALGORITHMS = {
    "logistic_regression",
    "ridge_regression",
    "linear_regression",
    "svm",
    "knn",
}

# SVM and KNN have quadratic or near-quadratic inference cost. Keep training
# bounded so a 100k-row table cannot exhaust memory or block the service.
TRAINING_ROW_LIMITS = {
    "svm": 20_000,
    "knn": 30_000,
}


def algorithms_for(task: str) -> tuple[str, ...]:
    return CLASSIFICATION_ALGORITHMS if task == "classification" else REGRESSION_ALGORITHMS


def requires_scaling(algorithm: str) -> bool:
    return algorithm in SCALED_ALGORITHMS


def training_row_limit(algorithm: str, requested: int) -> int:
    return min(requested, TRAINING_ROW_LIMITS.get(algorithm, requested))


class LabelEncodedClassifier(ClassifierMixin, BaseEstimator):
    """Wrap estimators such as XGBoost that require numeric class labels."""

    def __init__(self, estimator: Any):
        self.estimator = estimator

    def fit(self, features: Any, target: Any) -> "LabelEncodedClassifier":
        self.encoder_ = LabelEncoder().fit(target)
        self.estimator_ = clone(self.estimator)
        self.estimator_.fit(features, self.encoder_.transform(target))
        self.classes_ = self.encoder_.classes_
        return self

    def predict(self, features: Any) -> Any:
        encoded = self.estimator_.predict(features)
        return self.encoder_.inverse_transform(encoded)

    def predict_proba(self, features: Any) -> Any:
        return self.estimator_.predict_proba(features)

    def __getattr__(self, name: str) -> Any:
        if name in {"estimator", "estimator_", "encoder_", "classes_"}:
            raise AttributeError(name)
        return getattr(self.estimator_, name)


def build_estimator(algorithm: str, task: str, random_state: int, target: Any | None = None) -> Any:
    if algorithm == "logistic_regression":
        return LogisticRegression(
            max_iter=1500, random_state=random_state, class_weight="balanced"
        )
    if algorithm == "ridge_regression":
        return Ridge(alpha=1.0)
    if algorithm == "linear_regression":
        return LinearRegression()
    if algorithm == "decision_tree":
        if task == "classification":
            return DecisionTreeClassifier(
                max_depth=14,
                min_samples_leaf=5,
                random_state=random_state,
                class_weight="balanced",
            )
        return DecisionTreeRegressor(
            max_depth=14, min_samples_leaf=5, random_state=random_state
        )
    if algorithm == "xgboost":
        _require_xgboost()
        return LabelEncodedClassifier(XGBClassifier(**_xgb_params(random_state, target)))
    if algorithm == "xgboost_regression":
        _require_xgboost()
        return XGBRegressor(**_xgb_regression_params(random_state))
    if algorithm == "lightgbm":
        _require_lightgbm()
        return LGBMClassifier(**_lgbm_params(random_state))
    if algorithm == "lightgbm_regression":
        _require_lightgbm()
        return LGBMRegressor(**_lgbm_regression_params(random_state))
    if algorithm == "svm":
        return CalibratedClassifierCV(
            SVC(
                kernel="rbf",
                C=1.0,
                gamma="scale",
                class_weight="balanced",
                cache_size=512,
                random_state=random_state,
            ),
            method="sigmoid",
            cv=3,
            ensemble=False,
        )
    if algorithm == "knn":
        return KNeighborsClassifier(n_neighbors=5, weights="distance", n_jobs=1)
    if task == "classification":
        return RandomForestClassifier(
            n_estimators=160,
            min_samples_leaf=2,
            random_state=random_state,
            n_jobs=1,
            class_weight="balanced_subsample",
        )
    return RandomForestRegressor(
        n_estimators=160,
        min_samples_leaf=2,
        random_state=random_state,
        n_jobs=1,
    )


def _xgb_params(random_state: int, target: Any | None) -> dict[str, Any]:
    params: dict[str, Any] = {
        "n_estimators": 320,
        "max_depth": 6,
        "learning_rate": 0.05,
        "subsample": 0.9,
        "colsample_bytree": 0.9,
        "tree_method": "hist",
        "eval_metric": "logloss",
        "n_jobs": 1,
        "random_state": random_state,
    }
    weight = _scale_pos_weight(target)
    if weight is not None:
        params["scale_pos_weight"] = weight
    return params


def _xgb_regression_params(random_state: int) -> dict[str, Any]:
    return {
        "n_estimators": 360,
        "max_depth": 6,
        "learning_rate": 0.05,
        "subsample": 0.9,
        "colsample_bytree": 0.9,
        "tree_method": "hist",
        "n_jobs": 1,
        "random_state": random_state,
    }


def _lgbm_params(random_state: int) -> dict[str, Any]:
    return {
        "n_estimators": 400,
        "learning_rate": 0.05,
        "num_leaves": 63,
        "subsample": 0.9,
        "colsample_bytree": 0.9,
        "class_weight": "balanced",
        "n_jobs": 1,
        "verbosity": -1,
        "random_state": random_state,
    }


def _lgbm_regression_params(random_state: int) -> dict[str, Any]:
    return {
        "n_estimators": 400,
        "learning_rate": 0.05,
        "num_leaves": 63,
        "subsample": 0.9,
        "colsample_bytree": 0.9,
        "n_jobs": 1,
        "verbosity": -1,
        "random_state": random_state,
    }


def _scale_pos_weight(target: Any | None) -> float | None:
    if target is None:
        return None
    values = np.asarray(target)
    if len(values) == 0:
        return None
    unique, counts = np.unique(values.astype(str), return_counts=True)
    if len(unique) != 2:
        return None
    positive = int(counts[1])
    negative = int(counts[0])
    if positive <= 0:
        return None
    return max(1.0, round(negative / positive, 6))


def _require_xgboost() -> None:
    if not XGBOOST_AVAILABLE:
        raise ValueError("当前环境未安装 xgboost，请先安装后再选择该算法")


def _require_lightgbm() -> None:
    if not LIGHTGBM_AVAILABLE:
        raise ValueError("当前环境未安装 lightgbm，请先安装后再选择该算法")
