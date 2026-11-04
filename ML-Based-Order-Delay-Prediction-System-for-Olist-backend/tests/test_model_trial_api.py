from __future__ import annotations

from uuid import uuid4

import pandas as pd
from fastapi.testclient import TestClient

from app.api.model_training import get_artifact_store
from app.config import settings
from app.main import app
from app.ml.training.artifacts import ModelArtifactStore
from app.ml.training.engine import ModelTrainingEngine, TrainingSpec


def _stored_model(store: ModelArtifactStore, owner_id: str = "user-1") -> str:
    frame = pd.DataFrame(
        {
            "amount": range(40),
            "segment": ["new", "returning"] * 20,
            "label": ["low", "high"] * 20,
        }
    )
    result = ModelTrainingEngine().train(
        frame,
        TrainingSpec(
            task="classification",
            target_column="label",
            feature_columns=("amount", "segment"),
            algorithm="random_forest",
            random_state=7,
        ),
    )
    manifest = store.save(
        result,
        owner_id=owner_id,
        dataset_id=str(uuid4()),
        dataset_version_id=str(uuid4()),
        table_id=str(uuid4()),
    )
    return manifest["artifactId"]


def _rows(include_target: bool = True) -> list[dict]:
    rows = [
        {"amount": index, "segment": "new" if index % 2 == 0 else "returning"}
        for index in range(8)
    ]
    if include_target:
        for index, row in enumerate(rows):
            row["label"] = "low" if index % 2 == 0 else "high"
    return rows


def test_model_trial_endpoint_requires_internal_service_token() -> None:
    response = TestClient(app).post("/internal/v1/model-trials", json={})

    assert response.status_code == 503


def test_model_trial_predicts_and_evaluates_with_owner_scoped_artifact(tmp_path, monkeypatch) -> None:
    monkeypatch.setattr(settings, "model_training_internal_token", "test-service-token")
    store = ModelArtifactStore(tmp_path)
    artifact_id = _stored_model(store)
    app.dependency_overrides[get_artifact_store] = lambda: store
    try:
        response = TestClient(app).post(
            "/internal/v1/model-trials",
            json={
                "ownerId": "user-1",
                "artifactId": artifact_id,
                "rows": _rows(),
                "targetColumn": "label",
            },
            headers={"Authorization": "Bearer test-service-token"},
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 201, response.text
    result = response.json()
    assert result["predictedRows"] == 8
    assert result["evaluatedRows"] == 8
    assert result["ignoredTargetRows"] == 0
    assert result["metrics"]["accuracy"] == 1
    assert result["confusionMatrix"]["labels"] == ["high", "low"]
    assert len(result["evaluationSamples"]) == 8


def test_model_trial_supports_prediction_only_and_rejects_other_owners(tmp_path, monkeypatch) -> None:
    monkeypatch.setattr(settings, "model_training_internal_token", "test-service-token")
    store = ModelArtifactStore(tmp_path)
    artifact_id = _stored_model(store)
    app.dependency_overrides[get_artifact_store] = lambda: store
    client = TestClient(app)
    headers = {"Authorization": "Bearer test-service-token"}
    try:
        prediction = client.post(
            "/internal/v1/model-trials",
            json={"ownerId": "user-1", "artifactId": artifact_id, "rows": _rows(False)},
            headers=headers,
        )
        wrong_owner = client.post(
            "/internal/v1/model-trials",
            json={"ownerId": "user-2", "artifactId": artifact_id, "rows": _rows(False)},
            headers=headers,
        )
    finally:
        app.dependency_overrides.clear()

    assert prediction.status_code == 201, prediction.text
    assert prediction.json()["predictedRows"] == 8
    assert prediction.json()["evaluatedRows"] == 0
    assert prediction.json()["metrics"] == {}
    assert wrong_owner.status_code == 404


def test_model_trial_rejects_extra_fields_and_target_leakage(tmp_path, monkeypatch) -> None:
    monkeypatch.setattr(settings, "model_training_internal_token", "test-service-token")
    store = ModelArtifactStore(tmp_path)
    artifact_id = _stored_model(store)
    app.dependency_overrides[get_artifact_store] = lambda: store
    client = TestClient(app)
    headers = {"Authorization": "Bearer test-service-token"}
    try:
        extra = _rows(False)
        extra[0]["other"] = "not selected"
        extra_response = client.post(
            "/internal/v1/model-trials",
            json={"ownerId": "user-1", "artifactId": artifact_id, "rows": extra},
            headers=headers,
        )
        leakage_response = client.post(
            "/internal/v1/model-trials",
            json={
                "ownerId": "user-1",
                "artifactId": artifact_id,
                "rows": _rows(False),
                "targetColumn": "amount",
            },
            headers=headers,
        )
    finally:
        app.dependency_overrides.clear()

    assert extra_response.status_code == 422
    assert leakage_response.status_code == 422
