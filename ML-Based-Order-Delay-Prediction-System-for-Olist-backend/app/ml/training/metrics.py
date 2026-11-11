"""Shared classification and regression evaluation metrics."""

from __future__ import annotations

from typing import Any

import numpy as np
import pandas as pd
from sklearn.calibration import calibration_curve
from sklearn.metrics import (
    accuracy_score,
    average_precision_score,
    balanced_accuracy_score,
    classification_report,
    confusion_matrix,
    explained_variance_score,
    f1_score,
    log_loss,
    mean_absolute_error,
    mean_squared_error,
    median_absolute_error,
    precision_score,
    precision_recall_curve,
    r2_score,
    recall_score,
    roc_auc_score,
    roc_curve,
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


def evaluate_detailed(
    task: str,
    actual: Any,
    predicted: Any,
    *,
    probabilities: Any | None = None,
    classes: Any | None = None,
) -> tuple[dict[str, float], dict[str, Any], dict[str, Any] | None]:
    """Return the stable legacy metrics plus a richer, additive report."""
    metrics, matrix = evaluate_predictions(task, actual, predicted)
    if task == "classification":
        detail = _classification_detail(actual, predicted, probabilities, classes)
        detail["confusionMatrix"] = matrix
        return metrics, detail, matrix
    detail = _regression_detail(actual, predicted)
    return metrics, detail, None


def _classification_detail(
    actual: Any,
    predicted: Any,
    probabilities: Any | None,
    classes: Any | None,
) -> dict[str, Any]:
    actual_values = pd.Series(actual).astype(str)
    predicted_values = pd.Series(predicted).astype(str)
    labels = sorted(set(actual_values.tolist()) | set(predicted_values.tolist()))
    report = classification_report(
        actual_values,
        predicted_values,
        labels=labels,
        output_dict=True,
        zero_division=0,
    )
    per_class: dict[str, Any] = {}
    for label in labels:
        values = report.get(label, {})
        per_class[label] = {
            "precision": _number(values.get("precision")),
            "recall": _number(values.get("recall")),
            "f1": _number(values.get("f1-score")),
            "support": int(values.get("support", 0)),
        }

    matrix = confusion_matrix(actual_values, predicted_values, labels=labels)
    off_diagonal: list[dict[str, Any]] = []
    for row_index, actual_label in enumerate(labels):
        for column_index, predicted_label in enumerate(labels):
            count = int(matrix[row_index, column_index])
            if row_index != column_index and count:
                off_diagonal.append(
                    {
                        "actual": actual_label,
                        "predicted": predicted_label,
                        "count": count,
                    }
                )
    off_diagonal.sort(key=lambda item: item["count"], reverse=True)

    detail: dict[str, Any] = {
        "sampleCount": int(len(actual_values)),
        "labelCount": len(labels),
        "precisionMacro": _number(
            precision_score(actual_values, predicted_values, average="macro", zero_division=0)
        ),
        "recallMacro": _number(
            recall_score(actual_values, predicted_values, average="macro", zero_division=0)
        ),
        "f1Macro": _number(
            f1_score(actual_values, predicted_values, average="macro", zero_division=0)
        ),
        "perClass": per_class,
        "topConfusions": off_diagonal[:10],
    }

    probability_matrix = _aligned_probabilities(probabilities, classes, labels)
    if probability_matrix is not None:
        try:
            detail["logLoss"] = _number(
                log_loss(actual_values, probability_matrix, labels=labels)
            )
        except (TypeError, ValueError):
            pass
        if len(labels) == 2:
            positive_index = 1
            binary_truth = (actual_values == labels[positive_index]).astype(int)
            positive_scores = probability_matrix[:, positive_index]
            if binary_truth.nunique() == 2:
                detail["rocAuc"] = _number(roc_auc_score(binary_truth, positive_scores))
                detail["prAuc"] = _number(
                    average_precision_score(binary_truth, positive_scores)
                )
        elif len(labels) > 2:
            try:
                detail["rocAucOvrMacro"] = _number(
                    roc_auc_score(
                        actual_values,
                        probability_matrix,
                        labels=labels,
                        multi_class="ovr",
                        average="macro",
                    )
                )
            except (TypeError, ValueError):
                pass
        if len(labels) == 2:
            binary_truth = (actual_values == labels[1]).astype(int)
            positive_scores = probability_matrix[:, 1]
            detail["rocCurve"] = _roc_curve(binary_truth, positive_scores)
            detail["prCurve"] = _pr_curve(binary_truth, positive_scores)
            detail["calibrationCurve"] = _calibration_curve(binary_truth, positive_scores)
            detail["scoreDistribution"] = _score_distribution(binary_truth, positive_scores)
    return {key: value for key, value in detail.items() if value is not None}


def _regression_detail(actual: Any, predicted: Any) -> dict[str, Any]:
    actual_values = np.asarray(actual, dtype=float)
    predicted_values = np.asarray(predicted, dtype=float)
    residuals = actual_values - predicted_values
    detail: dict[str, Any] = {
        "sampleCount": int(len(actual_values)),
        "medianAbsoluteError": _number(median_absolute_error(actual_values, predicted_values)),
        "meanActual": _number(float(np.mean(actual_values))) if len(actual_values) else None,
        "meanPredicted": _number(float(np.mean(predicted_values))) if len(predicted_values) else None,
        "residualStd": _number(float(np.std(residuals))) if len(residuals) > 1 else None,
    }
    if len(actual_values) > 1:
        detail["explainedVariance"] = _number(
            explained_variance_score(actual_values, predicted_values)
        )
    non_zero = np.abs(actual_values) > 1e-12
    if non_zero.any():
        detail["mapePercent"] = _number(
            float(np.mean(np.abs(residuals[non_zero] / actual_values[non_zero])) * 100.0)
        )
    if (
        len(actual_values) > 0
        and np.min(actual_values) >= 0
        and np.min(predicted_values) >= 0
    ):
        try:
            detail["rmsle"] = _number(
                float(np.sqrt(np.mean(np.square(np.log1p(actual_values) - np.log1p(predicted_values)))))
            )
        except (FloatingPointError, ValueError):
            pass
    order = np.argsort(np.abs(residuals))[::-1][:10]
    detail["largestErrors"] = [
        {
            "row": int(index) + 1,
            "actual": _number(float(actual_values[index])),
            "predicted": _number(float(predicted_values[index])),
            "residual": _number(float(residuals[index])),
        }
        for index in order
    ]
    detail["predictedVsActual"] = _scatter_points(actual_values, predicted_values)
    detail["residualPlot"] = _residual_points(actual_values, predicted_values)
    detail["residualDistribution"] = _histogram(residuals, bins=20)
    return {key: value for key, value in detail.items() if value is not None}


def _aligned_probabilities(
    probabilities: Any | None,
    classes: Any | None,
    labels: list[str],
) -> np.ndarray | None:
    if probabilities is None or classes is None:
        return None
    matrix = np.asarray(probabilities, dtype=float)
    class_names = [str(name) for name in np.asarray(classes).tolist()]
    if matrix.ndim != 2 or matrix.shape[1] != len(class_names):
        return None
    if sorted(class_names) != sorted(labels):
        return None
    positions = [class_names.index(label) for label in labels]
    aligned = matrix[:, positions]
    row_sums = aligned.sum(axis=1, keepdims=True)
    positive = row_sums[:, 0] > 0
    if np.any(positive):
        aligned[positive] = aligned[positive] / row_sums[positive]
    return aligned


def _number(value: Any) -> float | None:
    if value is None:
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if np.isfinite(number) else None


def _downsample(count: int, limit: int = 80) -> np.ndarray:
    if count <= limit:
        return np.arange(count)
    return np.unique(np.linspace(0, count - 1, limit).astype(int))


def _roc_curve(truth: np.ndarray, scores: np.ndarray) -> dict[str, Any] | None:
    try:
        fpr, tpr, thresholds = roc_curve(truth, scores)
    except (TypeError, ValueError):
        return None
    positions = _downsample(len(fpr))
    return {
        "fpr": [round(float(value), 6) for value in fpr[positions]],
        "tpr": [round(float(value), 6) for value in tpr[positions]],
        "thresholds": [_threshold(value) for value in thresholds[positions]],
        "auc": _number(roc_auc_score(truth, scores)),
    }


def _pr_curve(truth: np.ndarray, scores: np.ndarray) -> dict[str, Any] | None:
    try:
        precision, recall, thresholds = precision_recall_curve(truth, scores)
    except (TypeError, ValueError):
        return None
    positions = _downsample(len(precision))
    return {
        "precision": [round(float(value), 6) for value in precision[positions]],
        "recall": [round(float(value), 6) for value in recall[positions]],
        "thresholds": [_threshold(value) for value in np.append(thresholds, 1.0)[positions]],
        "auc": _number(average_precision_score(truth, scores)),
    }


def _threshold(value: Any) -> float:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return 1.0
    return round(number, 6) if np.isfinite(number) else 1.0


def _calibration_curve(truth: np.ndarray, scores: np.ndarray) -> dict[str, Any] | None:
    try:
        fraction, mean_predicted = calibration_curve(
            truth, scores, n_bins=10, strategy="quantile"
        )
    except (TypeError, ValueError):
        return None
    return {
        "meanPredicted": [round(float(value), 6) for value in mean_predicted],
        "fractionPositive": [round(float(value), 6) for value in fraction],
    }


def _score_distribution(truth: np.ndarray, scores: np.ndarray) -> dict[str, Any]:
    bins = np.linspace(0.0, 1.0, 21)
    negative, _ = np.histogram(scores[truth == 0], bins=bins)
    positive, _ = np.histogram(scores[truth == 1], bins=bins)
    return {
        "binEdges": [round(float(value), 6) for value in bins],
        "negative": [int(value) for value in negative],
        "positive": [int(value) for value in positive],
    }


def _scatter_points(actual: np.ndarray, predicted: np.ndarray, limit: int = 300) -> dict[str, Any]:
    positions = _downsample(len(actual), limit)
    return {
        "actual": [round(float(actual[index]), 6) for index in positions],
        "predicted": [round(float(predicted[index]), 6) for index in positions],
    }


def _residual_points(actual: np.ndarray, predicted: np.ndarray, limit: int = 300) -> dict[str, Any]:
    residuals = actual - predicted
    positions = _downsample(len(actual), limit)
    return {
        "predicted": [round(float(predicted[index]), 6) for index in positions],
        "residual": [round(float(residuals[index]), 6) for index in positions],
    }


def _histogram(values: np.ndarray, bins: int = 20) -> dict[str, Any]:
    counts, edges = np.histogram(values, bins=bins)
    centers = (edges[:-1] + edges[1:]) / 2
    return {
        "binEdges": [round(float(value), 6) for value in edges],
        "centers": [round(float(value), 6) for value in centers],
        "counts": [int(value) for value in counts],
    }
