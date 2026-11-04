from __future__ import annotations

import pandas as pd
import pytest

from app.ml.training.artifacts import ModelArtifactStore
from app.ml.training.engine import ModelTrainingEngine, TrainingSpec


def _result():
    return ModelTrainingEngine().train(
        pd.DataFrame({"feature": range(40), "target": [index % 2 for index in range(40)]}),
        TrainingSpec(
            task="classification",
            target_column="target",
            feature_columns=("feature",),
            algorithm="random_forest",
        ),
    )


def test_artifacts_are_isolated_by_owner(tmp_path) -> None:
    store = ModelArtifactStore(tmp_path)
    manifest = store.save(
        _result(), owner_id="alice", dataset_id="dataset", dataset_version_id="version", table_id="table"
    )

    assert store.manifest(manifest["artifactId"], owner_id="alice")["ownerId"] == "alice"
    with pytest.raises(FileNotFoundError):
        store.manifest(manifest["artifactId"], owner_id="bob")
    with pytest.raises(FileNotFoundError):
        store.manifest("../../manifest.json", owner_id="alice")
