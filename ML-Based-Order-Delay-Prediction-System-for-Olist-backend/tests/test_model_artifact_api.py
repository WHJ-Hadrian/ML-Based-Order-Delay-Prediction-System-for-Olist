from __future__ import annotations

import io
import zipfile

import pandas as pd
from fastapi.testclient import TestClient

from app.api.model_training import get_artifact_store
from app.config import settings
from app.main import app
from app.ml.training.artifacts import ModelArtifactStore
from app.ml.training.engine import ModelTrainingEngine, TrainingSpec


def _artifact(store: ModelArtifactStore) -> str:
    frame = pd.DataFrame({"feature": range(40), "label": ["yes" if i % 2 else "no" for i in range(40)]})
    result = ModelTrainingEngine().train(
        frame,
        TrainingSpec(
            task="classification",
            target_column="label",
            feature_columns=("feature",),
            algorithm="random_forest",
            explain=False,
        ),
    )
    return store.save(
        result,
        owner_id="user-1",
        dataset_id="dataset-1",
        dataset_version_id="version-1",
        table_id="table-1",
    )["artifactId"]


def test_artifact_manifest_and_bundle_are_owner_scoped(tmp_path, monkeypatch) -> None:
    monkeypatch.setattr(settings, "model_training_internal_token", "test-service-token")
    store = ModelArtifactStore(tmp_path)
    artifact_id = _artifact(store)
    app.dependency_overrides[get_artifact_store] = lambda: store
    client = TestClient(app)
    headers = {"Authorization": "Bearer test-service-token"}
    try:
        manifest = client.get(
            f"/internal/v1/model-artifacts/{artifact_id}/manifest",
            params={"ownerId": "user-1"},
            headers=headers,
        )
        bundle = client.get(
            f"/internal/v1/model-artifacts/{artifact_id}/export.zip",
            params={"ownerId": "user-1"},
            headers=headers,
        )
        forbidden = client.get(
            f"/internal/v1/model-artifacts/{artifact_id}/manifest",
            params={"ownerId": "user-2"},
            headers=headers,
        )
    finally:
        app.dependency_overrides.clear()

    assert manifest.status_code == 200
    assert manifest.json()["schemaVersion"] == 2
    assert bundle.status_code == 200
    with zipfile.ZipFile(io.BytesIO(bundle.content)) as archive:
        assert "pipeline.joblib" in archive.namelist()
    assert forbidden.status_code == 404
