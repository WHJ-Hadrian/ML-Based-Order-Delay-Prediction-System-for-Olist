"""Shared classification and regression evaluation metrics."""

from __future__ import annotations

from typing import Any

import numpy as np
import pandas as pd
from sklearn.metrics import (
    accuracy_score,
    balanced_accuracy_score,
    confusion_matrix,
    f1_score,
    mean_absolute_error,
    mean_squared_error,
    precision_score,
    r2_score,
    recall_score,
)


def evaluate_predictions(
    task: str, actual: Any, predicted: Any
) -> tuple[dict[str, float], dict[str, Any] | None]:
    if task == "classification":
        actual_values = pd.Series(actual).astype(str)
        predicted_values = pd.Series(predicted).astype(str)
        labels = sorted(set(actual_values.tolist()) | set(predicted_values.tolist()))
        matrix = confusion_matrix(actual_values, predicted_values, labels=labels)
        return (
            {
                "accuracy": float(accuracy_score(actual_values, predicted_values)),
                "balanced_accuracy": float(balanced_accuracy_score(actual_values, predicted_values)),
                "precision_weighted": float(
                    precision_score(actual_values, predicted_values, average="weighted", zero_division=0)
                ),
                "recall_weighted": float(
                    recall_score(actual_values, predicted_values, average="weighted", zero_division=0)
                ),
                "f1_weighted": float(
                    f1_score(actual_values, predicted_values, average="weighted", zero_division=0)
                ),
            },
            {"labels": labels, "values": matrix.tolist()},
        )

    actual_values = np.asarray(actual, dtype=float)
    predicted_values = np.asarray(predicted, dtype=float)
    metrics = {
        "mae": float(mean_absolute_error(actual_values, predicted_values)),
        "rmse": float(np.sqrt(mean_squared_error(actual_values, predicted_values))),
    }
    if len(actual_values) > 1:
        metrics["r2"] = float(r2_score(actual_values, predicted_values))
    return metrics, None
