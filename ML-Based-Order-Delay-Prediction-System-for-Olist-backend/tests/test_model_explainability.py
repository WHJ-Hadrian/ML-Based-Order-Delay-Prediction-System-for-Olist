from __future__ import annotations

import pandas as pd

from app.ml.training.engine import ModelTrainingEngine, TrainingSpec
from app.ml.training.explainability import explain_model


def _trained():
    frame = pd.DataFrame(
        {
            "useful": [index % 5 for index in range(80)],
            "noise": ["a", "b", "c", "d"] * 20,
            "label": ["yes" if index % 2 else "no" for index in range(80)],
        }
    )
    result = ModelTrainingEngine().train(
        frame,
        TrainingSpec(
            task="classification",
            target_column="label",
            feature_columns=("useful", "noise"),
            algorithm="random_forest",
            random_state=3,
            explain=True,
        ),
    )
    return result, frame


def test_explanations_are_bounded_and_use_shap_when_available() -> None:
    result, frame = _trained()
    explanation = explain_model(
        result.pipeline,
        frame.loc[:, ["useful", "noise"]],
        frame["label"],
        task="classification",
        random_state=3,
        max_rows=40,
        n_repeats=2,
    )

    assert explanation["available"] is True
    assert explanation["sampledRows"] == 40
    assert {item["feature"] for item in explanation["globalImportance"]} == {"useful", "noise"}
    assert explanation["globalImportance"][0]["rank"] == 1
    assert explanation["shap"]["computed"] is True
    assert explanation["localExplanation"]["available"] is True
    assert explanation["localExplanation"]["contributions"]


def test_training_result_carries_explanation_and_split_summary() -> None:
    result, _ = _trained()

    assert result.split_summary["strategy"] == "random"
    assert result.explainability["available"] is True
    assert result.metric_details["sampleCount"] == result.test_rows
    assert result.training_seconds >= 0
