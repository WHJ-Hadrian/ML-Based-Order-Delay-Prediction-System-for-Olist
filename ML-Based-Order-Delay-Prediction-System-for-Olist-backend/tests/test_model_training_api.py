from __future__ import annotations

from uuid import uuid4

import pandas as pd
from fastapi.testclient import TestClient

from app.config import settings
from app.main import app
from app.ml.training.artifacts import ModelArtifactStore
from app.api.model_training import get_artifact_store


def _payload() -> dict:
    return {
        "ownerId": "user-1",
        "datasetId": str(uuid4()),
        "datasetVersionId": str(uuid4()),
        "tableId": str(uuid4()),
        "rows": [
            {
                "feature": index,
                "segment": "returning" if index % 2 else "new",
                "label": "yes" if index % 2 else "no",
            }
            for index in range(40)
        ],
        "task": "classification",
        "algorithm": "random_forest",
        "targetColumn": "label",
        "featureColumns": ["feature", "segment"],
        "testSize": 0.2,
        "randomState": 17,
    }


def test_internal_training_endpoint_requires_service_token() -> None:
    client = TestClient(app)

    response = client.post("/internal/v1/model-training-runs", json=_payload())

    assert response.status_code == 503


def test_internal_training_endpoint_trains_and_persists_owner_scoped_artifact(
    tmp_path, monkeypatch
) -> None:
    monkeypatch.setattr(settings, "model_training_internal_token", "test-service-token")
    store = ModelArtifactStore(tmp_path)
    payload = _payload()
    app.dependency_overrides[get_artifact_store] = lambda: store
    try:
        response = TestClient(app).post(
            "/internal/v1/model-training-runs",
            json=payload,
            headers={"Authorization": "Bearer test-service-token"},
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 201, response.text
    result = response.json()
    assert result["metrics"]["accuracy"] >= 0
    assert result["trainRows"] + result["testRows"] == 40
    assert len(result["evaluationSamples"]) == 8
    manifest = store.manifest(result["artifactId"], owner_id="user-1")
    pipeline = store.load_pipeline(result["artifactId"], owner_id="user-1")
    assert manifest["datasetVersionId"] == payload["datasetVersionId"]
    assert pipeline.predict(pd.DataFrame([{"feature": 3, "segment": "new"}])).size == 1


def test_internal_training_endpoint_rejects_target_leakage_and_unknown_fields(
    tmp_path, monkeypatch
) -> None:
    monkeypatch.setattr(settings, "model_training_internal_token", "test-service-token")
    app.dependency_overrides[get_artifact_store] = lambda: ModelArtifactStore(tmp_path)
    client = TestClient(app)
    headers = {"Authorization": "Bearer test-service-token"}
    try:
        leakage = _payload()
        leakage["featureColumns"] = ["feature", "label"]
        response = client.post("/internal/v1/model-training-runs", json=leakage, headers=headers)
        assert response.status_code == 422

        unknown = _payload()
        unknown["executableCode"] = "not accepted"
        response = client.post("/internal/v1/model-training-runs", json=unknown, headers=headers)
        assert response.status_code == 422
    finally:
        app.dependency_overrides.clear()


def test_internal_training_endpoint_rejects_missing_fields(tmp_path, monkeypatch) -> None:
    monkeypatch.setattr(settings, "model_training_internal_token", "test-service-token")
    app.dependency_overrides[get_artifact_store] = lambda: ModelArtifactStore(tmp_path)
    payload = _payload()
    payload["rows"] = [{"other": index} for index in range(40)]
    try:
        response = TestClient(app).post(
            "/internal/v1/model-training-runs",
            json=payload,
            headers={"Authorization": "Bearer test-service-token"},
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 422
    assert "训练数据缺少所选字段" in response.json()["detail"]


def test_internal_training_endpoint_rejects_unselected_columns_and_oversized_matrices(
    tmp_path, monkeypatch
) -> None:
    monkeypatch.setattr(settings, "model_training_internal_token", "test-service-token")
    app.dependency_overrides[get_artifact_store] = lambda: ModelArtifactStore(tmp_path)
    client = TestClient(app)
    headers = {"Authorization": "Bearer test-service-token"}
    try:
        extra_column = _payload()
        extra_column["rows"][0]["unselected"] = "must not enter training"
        response = client.post(
            "/internal/v1/model-training-runs", json=extra_column, headers=headers
        )
        assert response.status_code == 422

        monkeypatch.setattr("app.api.model_training.MAX_TRAINING_CELLS", 10)
        oversized = _payload()
        response = client.post(
            "/internal/v1/model-training-runs", json=oversized, headers=headers
        )
        assert response.status_code == 422
        assert "资源上限" in response.json()["detail"]
    finally:
        app.dependency_overrides.clear()
