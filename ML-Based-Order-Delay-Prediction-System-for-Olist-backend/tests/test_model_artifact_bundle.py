from __future__ import annotations

import io
import json
import zipfile

import pandas as pd

from app.ml.training.artifacts import ModelArtifactStore
from app.ml.training.engine import ModelTrainingEngine, TrainingSpec


def _stored(tmp_path):
    frame = pd.DataFrame(
        {"feature": range(40), "label": ["yes" if index % 2 else "no" for index in range(40)]}
    )
    result = ModelTrainingEngine().train(
        frame,
        TrainingSpec(
            task="classification",
            target_column="label",
            feature_columns=("feature",),
            algorithm="random_forest",
            explain=False,
        ),
        dataset_fingerprint="fingerprint-1",
    )
    store = ModelArtifactStore(tmp_path)
    manifest = store.save(
        result,
        owner_id="user-1",
        dataset_id="dataset-1",
        dataset_version_id="version-1",
        table_id="table-1",
        dataset_fingerprint="fingerprint-1",
    )
    return store, manifest


def test_manifest_v2_records_lineage_split_metrics_and_runtime(tmp_path) -> None:
    _, manifest = _stored(tmp_path)

    assert manifest["schemaVersion"] == 2
    assert manifest["datasetFingerprint"] == "fingerprint-1"
    assert manifest["splitSummary"]["strategy"] == "random"
    assert manifest["metricDetails"]["sampleCount"] == manifest["testRows"]
    assert manifest["runtime"]["scikitLearn"]
    assert manifest["artifact"]["sha256"] == manifest["artifactSha256"]


def test_export_bundle_contains_manifest_pipeline_and_readme(tmp_path) -> None:
    store, manifest = _stored(tmp_path)
    output = io.BytesIO()

    store.write_bundle(manifest["artifactId"], owner_id="user-1", output=output)

    output.seek(0)
    with zipfile.ZipFile(output) as bundle:
        assert set(bundle.namelist()) == {"manifest.json", "pipeline.joblib", "README.txt"}
        exported = json.loads(bundle.read("manifest.json"))
        assert exported["artifactId"] == manifest["artifactId"]
