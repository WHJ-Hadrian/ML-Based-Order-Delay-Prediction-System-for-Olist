import numpy as np
import pandas as pd
import pytest

from app.ml.training.engine import ModelTrainingEngine, TrainingSpec


def test_classification_trains_mixed_features_and_returns_holdout_metrics() -> None:
    rows = [
        {
            "age": None if index % 9 == 0 else 18 + index % 50,
            "segment": "returning" if index % 4 < 2 else "new",
            "purchased": "yes" if index % 4 < 2 else "no",
        }
        for index in range(80)
    ]

    result = ModelTrainingEngine().train(
        pd.DataFrame(rows),
        TrainingSpec(
            task="classification",
            target_column="purchased",
            feature_columns=("age", "segment"),
            algorithm="random_forest",
            random_state=17,
        ),
    )

    assert result.task == "classification"
    assert result.feature_types == {"age": "numeric", "segment": "categorical"}
    assert result.train_rows + result.test_rows == 80
    assert result.metrics["accuracy"] >= 0.8
    assert set(result.metrics) == {
        "accuracy",
        "balanced_accuracy",
        "precision_weighted",
        "recall_weighted",
        "f1_weighted",
    }
    assert result.confusion_matrix["labels"] == ["no", "yes"]
    assert len(result.confusion_matrix["values"]) == 2
    assert result.evaluation_samples
    assert result.pipeline.predict(pd.DataFrame([{"age": 30, "segment": "new"}]))[0] in {
        "no",
        "yes",
    }


def test_regression_trains_with_missing_features_and_drops_invalid_targets() -> None:
    rows = [
        {
            "amount": None if index % 11 == 0 else float(index),
            "region": "east" if index % 3 else "west",
            "revenue": None if index == 0 else float(index * 2 + (index % 3)),
        }
        for index in range(90)
    ]
    rows[1]["revenue"] = None
    rows[2]["revenue"] = None
    rows[5]["revenue"] = "not a number"

    result = ModelTrainingEngine().train(
        pd.DataFrame(rows),
        TrainingSpec(
            task="regression",
            target_column="revenue",
            feature_columns=("amount", "region"),
            algorithm="ridge_regression",
            test_size=0.25,
        ),
    )

    assert result.task == "regression"
    assert result.train_rows + result.test_rows == 86
    assert result.dropped_target_rows == 4
    assert all(sample["row"] > 3 for sample in result.evaluation_samples)
    assert set(result.metrics) == {"mae", "rmse", "r2"}
    assert all(np.isfinite(metric) for metric in result.metrics.values())
    assert result.confusion_matrix is None


@pytest.mark.parametrize(
    ("kwargs", "message"),
    [
        (
            {
                "task": "classification",
                "target_column": "label",
                "feature_columns": ("feature", "label"),
                "algorithm": "random_forest",
            },
            "目标.*泄漏",
        ),
        (
            {
                "task": "regression",
                "target_column": "label",
                "feature_columns": ("feature",),
                "algorithm": "logistic_regression",
            },
            "不支持该算法",
        ),
        (
            {
                "task": "classification",
                "target_column": "label",
                "feature_columns": ("feature",),
                "algorithm": "random_forest",
                "test_size": 0.8,
            },
            "测试集比例",
        ),
    ],
)
def test_training_spec_rejects_unsafe_or_invalid_configuration(kwargs, message) -> None:
    with pytest.raises(ValueError, match=message):
        TrainingSpec(**kwargs)


def test_classification_rejects_rare_class_instead_of_silent_unstratified_split() -> None:
    frame = pd.DataFrame(
        {
            "feature": range(20),
            "label": ["common"] * 19 + ["rare"],
        }
    )

    with pytest.raises(ValueError, match="每个目标类别至少需要 2 条记录"):
        ModelTrainingEngine().train(
            frame,
            TrainingSpec(
                task="classification",
                target_column="label",
                feature_columns=("feature",),
                algorithm="random_forest",
            ),
        )


def test_training_rejects_duplicate_headers_and_all_empty_features() -> None:
    engine = ModelTrainingEngine()
    spec = TrainingSpec(
        task="regression",
        target_column="target",
        feature_columns=("feature",),
        algorithm="ridge_regression",
    )

    with pytest.raises(ValueError, match="重名字段"):
        engine.train(
            pd.DataFrame([[1, 2, 3]], columns=["feature", "feature", "target"]),
            spec,
        )

    with pytest.raises(ValueError, match="全部为空"):
        engine.train(
            pd.DataFrame({"feature": [None] * 20, "target": range(20)}),
            spec,
        )


def test_temporal_split_keeps_time_column_out_of_model_features() -> None:
    frame = pd.DataFrame(
        {
            "feature": list(range(60)) * 2,
            "label": ["yes", "no"] * 60,
            "event_time": pd.date_range("2024-01-01", periods=120, freq="h"),
        }
    )

    result = ModelTrainingEngine().train(
        frame,
        TrainingSpec(
            task="classification",
            target_column="label",
            feature_columns=("feature",),
            algorithm="random_forest",
            split_strategy="temporal",
            time_column="event_time",
            test_size=0.25,
            explain=False,
        ),
    )

    assert result.feature_columns == ("feature",)
    assert result.split_summary["strategy"] == "temporal"
    assert result.split_summary["chronological"] is True
    assert result.train_rows + result.test_rows == 120


def _catalog_frame(rows: int = 160) -> pd.DataFrame:
    rng = np.random.default_rng(11)
    features = pd.DataFrame(
        {
            "distance_km": rng.uniform(10, 2500, rows),
            "freight_ratio": rng.uniform(0.05, 0.6, rows),
            "customer_state": rng.choice(["SP", "RJ", "MG", "BA"], rows),
        }
    )
    signal = -0.002 * (features["distance_km"] - 800) + 2 * (features["freight_ratio"] - 0.3)
    probability = 1 / (1 + np.exp(-signal))
    features["late_days"] = np.maximum(0, (features["distance_km"] / 500 - 3)).round(2)
    features["is_delayed"] = (rng.uniform(size=rows) < probability).astype(int)
    return features


@pytest.mark.parametrize(
    "algorithm",
    ["logistic_regression", "decision_tree", "xgboost", "lightgbm", "svm", "knn"],
)
def test_every_classification_algorithm_trains(algorithm) -> None:
    frame = _catalog_frame()
    result = ModelTrainingEngine().train(
        frame,
        TrainingSpec(
            task="classification",
            target_column="is_delayed",
            feature_columns=("distance_km", "freight_ratio", "customer_state"),
            algorithm=algorithm,
            random_state=4,
            explain=False,
        ),
    )

    assert result.algorithm == algorithm
    assert result.metrics["accuracy"] >= 0
    assert result.metrics["f1_weighted"] >= 0
    assert result.confusion_matrix is not None


@pytest.mark.parametrize(
    "algorithm",
    ["linear_regression", "decision_tree", "xgboost_regression", "lightgbm_regression"],
)
def test_every_regression_algorithm_trains(algorithm) -> None:
    frame = _catalog_frame()
    result = ModelTrainingEngine().train(
        frame,
        TrainingSpec(
            task="regression",
            target_column="late_days",
            feature_columns=("distance_km", "freight_ratio", "customer_state"),
            algorithm=algorithm,
            random_state=4,
            explain=False,
        ),
    )

    assert result.algorithm == algorithm
    assert np.isfinite(result.metrics["mae"])
    assert np.isfinite(result.metrics["rmse"])


def test_cross_validation_reports_fold_statistics() -> None:
    frame = _catalog_frame(240)
    result = ModelTrainingEngine().train(
        frame,
        TrainingSpec(
            task="classification",
            target_column="is_delayed",
            feature_columns=("distance_km", "freight_ratio", "customer_state"),
            algorithm="random_forest",
            random_state=4,
            explain=False,
            cv_folds=3,
        ),
    )

    cross_validation = result.metric_details["crossValidation"]
    assert cross_validation["folds"] == 3
    assert len(cross_validation["foldMetrics"]) == 3
    assert "rocAuc" in cross_validation["mean"]
    assert "rocAuc" in cross_validation["std"]
