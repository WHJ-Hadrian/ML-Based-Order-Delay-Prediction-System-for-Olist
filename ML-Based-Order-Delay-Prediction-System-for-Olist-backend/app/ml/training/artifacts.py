"""Owner-scoped, atomic storage for trained scikit-learn pipelines."""

from __future__ import annotations

import hashlib
import hmac
import json
import os
import platform
import sys
import zipfile
import shutil
import tempfile
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from typing import BinaryIO

import joblib
import numpy
import pandas
import sklearn
from sklearn.pipeline import Pipeline

from app.ml.training.engine import TrainingResult

MAX_ARTIFACT_SIZE_BYTES = 512 * 1024 * 1024
MANIFEST_SCHEMA_VERSION = 2


class ModelArtifactStore:
    def __init__(self, root: str | Path) -> None:
        self._root = Path(root)

    def save(
        self,
        result: TrainingResult,
        *,
        owner_id: str,
        dataset_id: str,
        dataset_version_id: str,
        table_id: str,
        dataset_fingerprint: str | None = None,
    ) -> dict[str, Any]:
        if not owner_id or not owner_id.strip():
            raise ValueError("模型产物必须绑定到有效用户")

        artifact_id = str(uuid.uuid4())
        owner_directory = self._root / hashlib.sha256(owner_id.encode("utf-8")).hexdigest()
        owner_directory.mkdir(parents=True, exist_ok=True)
        temporary_directory = Path(
            tempfile.mkdtemp(prefix=f".{artifact_id}.", dir=owner_directory)
        )
        artifact_path = temporary_directory / "pipeline.joblib"
        try:
            joblib.dump(result.pipeline, artifact_path, compress=3)
            artifact_size = artifact_path.stat().st_size
            if artifact_size > MAX_ARTIFACT_SIZE_BYTES:
                raise ValueError("模型产物超过 512 MB 上限")

            created_at = datetime.now(timezone.utc).isoformat()
            artifact_sha256 = self._sha256(artifact_path)
            manifest = {
                "schemaVersion": MANIFEST_SCHEMA_VERSION,
                "artifactId": artifact_id,
                "ownerId": owner_id,
                "createdAt": created_at,
                "datasetId": dataset_id,
                "datasetVersionId": dataset_version_id,
                "tableId": table_id,
                "datasetFingerprint": dataset_fingerprint,
                "task": result.task,
                "algorithm": result.algorithm,
                "targetColumn": result.target_column,
                "featureColumns": list(result.feature_columns),
                "featureTypes": result.feature_types,
                "trainingConfig": {
                    "testSize": result.test_size,
                    "randomState": result.random_state,
                    "splitStrategy": result.split_summary.get("strategy", "random"),
                    "validationSize": result.split_summary.get("validationSize", 0.0),
                },
                "splitSummary": result.split_summary,
                "testSize": result.test_size,
                "randomState": result.random_state,
                "trainRows": result.train_rows,
                "calibrationRows": result.calibration_rows,
                "validationRows": result.validation_rows,
                "testRows": result.test_rows,
                "droppedTargetRows": result.dropped_target_rows,
                "metrics": result.metrics,
                "decisionThreshold": result.decision_threshold,
                "metricDetails": result.metric_details,
                "validationMetrics": result.validation_metrics,
                "validationMetricDetails": result.validation_metric_details,
                "confusionMatrix": result.confusion_matrix,
                "evaluationSamples": list(result.evaluation_samples),
                "explainability": result.explainability,
                "trainingSeconds": result.training_seconds,
                "artifact": {
                    "file": "pipeline.joblib",
                    "format": "joblib",
                    "sizeBytes": artifact_size,
                    "sha256": artifact_sha256,
                },
                "artifactSizeBytes": artifact_size,
                "artifactSha256": artifact_sha256,
                "runtime": {
                    "python": platform.python_version(),
                    "implementation": platform.python_implementation(),
                    "pandas": pandas.__version__,
                    "numpy": numpy.__version__,
                    "scikitLearn": sklearn.__version__,
                    "platform": sys.platform,
                },
            }
            manifest_path = temporary_directory / "manifest.json"
            with manifest_path.open("w", encoding="utf-8", newline="\n") as output:
                json.dump(manifest, output, ensure_ascii=True, allow_nan=False, separators=(",", ":"))
                output.flush()
                os.fsync(output.fileno())

            final_directory = owner_directory / artifact_id
            os.replace(temporary_directory, final_directory)
            return manifest
        except Exception:
            shutil.rmtree(temporary_directory, ignore_errors=True)
            raise

    def manifest(self, artifact_id: str, *, owner_id: str) -> dict[str, Any]:
        directory = self._artifact_directory(artifact_id, owner_id)
        try:
            manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
        except FileNotFoundError as error:
            raise FileNotFoundError("模型产物不存在") from error

        if not hmac.compare_digest(str(manifest.get("ownerId", "")), owner_id):
            raise FileNotFoundError("模型产物不存在")
        artifact_path = directory / "pipeline.joblib"
        if artifact_path.stat().st_size != manifest.get("artifactSizeBytes"):
            raise RuntimeError("模型产物大小与登记信息不一致")
        if not hmac.compare_digest(self._sha256(artifact_path), str(manifest.get("artifactSha256", ""))):
            raise RuntimeError("模型产物完整性校验失败")
        return manifest

    def load_pipeline(self, artifact_id: str, *, owner_id: str) -> Pipeline:
        directory = self._artifact_directory(artifact_id, owner_id)
        self.manifest(artifact_id, owner_id=owner_id)
        pipeline = joblib.load(directory / "pipeline.joblib")
        if not isinstance(pipeline, Pipeline):
            raise RuntimeError("模型产物不是受支持的 Pipeline")
        return pipeline

    def write_bundle(self, artifact_id: str, *, owner_id: str, output: BinaryIO) -> None:
        """Stream a portable zip bundle containing the manifest and model binary."""
        manifest = self.manifest(artifact_id, owner_id=owner_id)
        directory = self._artifact_directory(artifact_id, owner_id)
        with zipfile.ZipFile(output, mode="w", compression=zipfile.ZIP_DEFLATED) as bundle:
            bundle.writestr(
                "manifest.json",
                json.dumps(manifest, ensure_ascii=True, allow_nan=False, indent=2),
            )
            bundle.write(directory / "pipeline.joblib", arcname="pipeline.joblib")
            bundle.writestr("README.txt", _bundle_readme(manifest))

    def _artifact_directory(self, artifact_id: str, owner_id: str) -> Path:
        if not owner_id or not owner_id.strip():
            raise FileNotFoundError("模型产物不存在")
        try:
            parsed_id = uuid.UUID(artifact_id)
        except (ValueError, TypeError, AttributeError) as error:
            raise FileNotFoundError("模型产物不存在") from error
        owner_directory = hashlib.sha256(owner_id.encode("utf-8")).hexdigest()
        return self._root / owner_directory / str(parsed_id)

    def _sha256(self, path: Path) -> str:
        digest = hashlib.sha256()
        with path.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
        return digest.hexdigest()


def _bundle_readme(manifest: dict[str, Any]) -> str:
    return (
        "Olist tabular model bundle\n"
        f"artifactId: {manifest.get('artifactId')}\n"
        f"task: {manifest.get('task')}\n"
        f"algorithm: {manifest.get('algorithm')}\n"
        f"targetColumn: {manifest.get('targetColumn')}\n"
        f"datasetVersionId: {manifest.get('datasetVersionId')}\n"
        "pipeline.joblib must be loaded with the exact scikit-learn version recorded in runtime.\n"
    )
