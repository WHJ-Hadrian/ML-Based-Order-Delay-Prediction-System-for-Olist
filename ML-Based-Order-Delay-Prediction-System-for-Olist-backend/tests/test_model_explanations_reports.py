from __future__ import annotations

import pandas as pd
from fastapi.testclient import TestClient

from app.api.model_training import get_artifact_store
from app.config import settings
from app.main import app
from app.ml.training.artifacts import ModelArtifactStore
from app.ml.training.engine import ModelTrainingEngine, TrainingSpec


def _artifact(store: ModelArtifactStore) -> str:
    frame = pd.DataFrame(
        {
            "distance_km": [float(index * 37 % 900 + 20) for index in range(90)],
            "customer_state": ["SP", "RJ", "MG"] * 30,
            "label": [1 if index % 3 == 0 else 0 for index in range(90)],
        }
    )
    result = ModelTrainingEngine().train(
        frame,
        TrainingSpec(
            task="classification",
            target_column="label",
            feature_columns=("distance_km", "customer_state"),
            algorithm="random_forest",
            random_state=5,
            explain=True,
        ),
    )
    return store.save(
        result,
        owner_id="user-1",
        dataset_id="dataset-1",
        dataset_version_id="version-1",
        table_id="table-1",
    )["artifactId"]


def test_explanation_endpoint_returns_shap_and_counterfactuals(tmp_path, monkeypatch) -> None:
    monkeypatch.setattr(settings, "model_training_internal_token", "test-service-token")
    store = ModelArtifactStore(tmp_path)
    artifact_id = _artifact(store)
    app.dependency_overrides[get_artifact_store] = lambda: store
    client = TestClient(app)
    headers = {"Authorization": "Bearer test-service-token"}
    try:
        response = client.post(
            "/internal/v1/model-explanations",
            headers=headers,
            json={
                "ownerId": "user-1",
                "artifactId": artifact_id,
                "rows": [{"distance_km": 450.0, "customer_state": "BA"}],
            },
        )
        forbidden = client.post(
            "/internal/v1/model-explanations",
            headers=headers,
            json={
                "ownerId": "user-2",
                "artifactId": artifact_id,
                "rows": [{"distance_km": 450.0, "customer_state": "BA"}],
            },
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200, response.text
    payload = response.json()
    assert payload["featureColumns"] == ["distance_km", "customer_state"]
    result = payload["results"][0]
    assert 0.0 <= result["probability"] <= 1.0
    assert result["contributions"]
    assert result["naturalLanguage"]
    assert result["method"]
    assert forbidden.status_code == 404


def test_report_endpoint_generates_every_format(tmp_path, monkeypatch) -> None:
    monkeypatch.setattr(settings, "model_training_internal_token", "test-service-token")
    store = ModelArtifactStore(tmp_path)
    artifact_id = _artifact(store)
    app.dependency_overrides[get_artifact_store] = lambda: store
    client = TestClient(app)
    headers = {"Authorization": "Bearer test-service-token"}
    signatures = {"pdf": b"%PDF", "docx": b"PK", "html": b"<!doctype", "json": b"{", "csv": None}
    try:
        for format_name, signature in signatures.items():
            response = client.post(
                "/internal/v1/model-reports",
                headers=headers,
                json={
                    "ownerId": "user-1",
                    "artifactId": artifact_id,
                    "format": format_name,
                    "title": "测试报告",
                    "sections": ["overview", "detail", "importance", "conclusion"],
                    "comparison": [
                        {
                            "name": "RF",
                            "algorithm": "random_forest",
                            "metrics": {"accuracy": 0.9},
                            "metricDetails": {"rocAuc": 0.93},
                            "decisionThreshold": 0.4,
                        }
                    ],
                },
            )
            assert response.status_code == 200, (format_name, response.text)
            assert response.content
            if signature is not None:
                assert response.content.startswith(signature), format_name
    finally:
        app.dependency_overrides.clear()
