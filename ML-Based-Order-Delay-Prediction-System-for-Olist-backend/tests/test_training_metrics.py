from __future__ import annotations

import numpy as np

from app.ml.training.metrics import evaluate_detailed, evaluate_predictions


def test_classification_detail_adds_auc_logloss_and_per_class_metrics() -> None:
    actual = ["no", "no", "yes", "yes", "yes", "no"]
    predicted = ["no", "yes", "yes", "yes", "no", "no"]
    probabilities = np.array(
        [[0.8, 0.2], [0.4, 0.6], [0.2, 0.8], [0.1, 0.9], [0.7, 0.3], [0.9, 0.1]]
    )

    metrics, detail, matrix = evaluate_detailed(
        "classification", actual, predicted, probabilities=probabilities, classes=["no", "yes"]
    )

    assert set(metrics) == {
        "accuracy",
        "balanced_accuracy",
        "precision_weighted",
        "recall_weighted",
        "f1_weighted",
    }
    assert 0.0 <= detail["rocAuc"] <= 1.0
    assert detail["logLoss"] > 0
    assert detail["precisionMacro"] >= 0
    assert detail["perClass"]["yes"]["support"] == 3
    assert detail["topConfusions"]
    assert matrix["labels"] == ["no", "yes"]


def test_regression_detail_adds_error_diagnostics_without_changing_legacy_metrics() -> None:
    actual = [10.0, 20.0, 30.0, 40.0]
    predicted = [9.0, 22.0, 28.0, 44.0]

    legacy, _ = evaluate_predictions("regression", actual, predicted)
    metrics, detail, matrix = evaluate_detailed("regression", actual, predicted)

    assert metrics == legacy
    assert matrix is None
    assert detail["medianAbsoluteError"] >= 0
    assert detail["mapePercent"] > 0
    assert detail["rmsle"] >= 0
    assert len(detail["largestErrors"]) == 4
