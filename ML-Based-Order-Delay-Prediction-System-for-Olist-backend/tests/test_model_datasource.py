from __future__ import annotations

import httpx
import pytest

from app.config import settings
from app.ml.training.datasource import DatasetExportSource, DatasetSourceError, load_training_data


def _source() -> DatasetExportSource:
    return DatasetExportSource(
        dataset_version_id="11111111-1111-1111-1111-111111111111",
        table_id="22222222-2222-2222-2222-222222222222",
        owner_id="user-1",
        columns=("feature", "label"),
        max_rows=100,
    )


def _client(handler) -> httpx.Client:
    return httpx.Client(transport=httpx.MockTransport(handler))


def test_dataset_export_is_streamed_through_the_internal_training_endpoint(monkeypatch) -> None:
    monkeypatch.setattr(settings, "model_training_internal_token", "service-token")
    monkeypatch.setattr(settings, "dataset_service_url", "http://dataset-service:8082")
    captured = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["path"] = request.url.path
        captured["columns"] = request.url.params["columns"]
        captured["token"] = request.headers["X-Model-Training-Token"]
        rows = "\n".join(f"{index},{'yes' if index % 2 else 'no'}" for index in range(40))
        return httpx.Response(200, text=f"feature,label\n{rows}\n")

    with _client(handler) as client:
        loaded = load_training_data(
            rows=None,
            source=_source(),
            required_columns=("feature", "label"),
            max_rows=100_000,
            max_cells=2_000_000,
            http_client=client,
        )

    assert captured["path"] == (
        "/internal/model-training/dataset-versions/"
        "11111111-1111-1111-1111-111111111111/tables/"
        "22222222-2222-2222-2222-222222222222/export.csv"
    )
    assert captured["columns"] == "feature,label"
    assert captured["token"] == "service-token"
    assert len(loaded.frame) == 40
    assert list(loaded.frame.columns) == ["feature", "label"]
    assert len(loaded.fingerprint) == 64
    assert loaded.source["type"] == "datasetExport"


def test_dataset_export_maps_auth_failure_and_rejects_bounds(monkeypatch) -> None:
    monkeypatch.setattr(settings, "model_training_internal_token", "service-token")
    monkeypatch.setattr(settings, "dataset_service_url", "http://dataset-service:8082")

    def unauthorized(_: httpx.Request) -> httpx.Response:
        return httpx.Response(401, json={"message": "invalid token"})

    with _client(unauthorized) as client:
        with pytest.raises(DatasetSourceError) as error:
            load_training_data(
                rows=None,
                source=_source(),
                required_columns=("feature", "label"),
                max_rows=100_000,
                max_cells=2_000_000,
                http_client=client,
            )
    assert error.value.status_code == 502

    def too_many(_: httpx.Request) -> httpx.Response:
        rows = "\n".join(f"{index},yes" for index in range(20))
        return httpx.Response(200, text=f"feature,label\n{rows}\n")

    with _client(too_many) as client:
        with pytest.raises(DatasetSourceError, match="上限"):
            load_training_data(
                rows=None,
                source=DatasetExportSource(
                    dataset_version_id="11111111-1111-1111-1111-111111111111",
                    table_id="22222222-2222-2222-2222-222222222222",
                    owner_id="user-1",
                    columns=("feature", "label"),
                    max_rows=10,
                ),
                required_columns=("feature", "label"),
                max_rows=10,
                max_cells=2_000_000,
                http_client=client,
            )


def test_dataset_export_requires_columns_to_match_training_fields(monkeypatch) -> None:
    monkeypatch.setattr(settings, "model_training_internal_token", "service-token")
    monkeypatch.setattr(settings, "dataset_service_url", "http://dataset-service:8082")

    def unexpected(_: httpx.Request) -> httpx.Response:
        raise AssertionError("column mismatch must be rejected before the export request")

    with _client(unexpected) as client:
        with pytest.raises(DatasetSourceError, match="完全一致"):
            load_training_data(
                rows=None,
                source=_source(),
                required_columns=("feature", "label", "target"),
                max_rows=100_000,
                max_cells=2_000_000,
                http_client=client,
            )


def test_inline_rows_are_hashed_and_validated() -> None:
    rows = [{"feature": index, "label": "yes"} for index in range(12)]
    loaded = load_training_data(
        rows=rows,
        source=None,
        required_columns=("feature", "label"),
        max_rows=100_000,
        max_cells=100,
    )

    assert loaded.row_count == 12
    assert loaded.source["type"] == "inlineRows"
    with pytest.raises(DatasetSourceError, match="资源上限"):
        load_training_data(
            rows=rows,
            source=None,
            required_columns=("feature", "label"),
            max_rows=100_000,
            max_cells=10,
        )
