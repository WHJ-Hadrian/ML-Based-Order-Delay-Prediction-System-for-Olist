import pytest

from app.workers.rule_execution.adapters import (
    BackendGatedExecutionPreflight,
    HttpExecutionPreflight,
)
from app.workers.rule_execution.errors import (
    ExecutionContractUnavailableError,
    ExecutionSnapshotError,
)
from tests.rule_execution_support import (
    DATASET_VERSION_ID,
    make_execution_payload,
    make_snapshot_payload,
    parse_execution,
)


class FakeSnapshotLoader:
    def __init__(self, payload):
        self.payload = payload
        self.calls = []

    def get_execution_snapshot(self, dataset_version_id):
        self.calls.append(dataset_version_id)
        return self.payload


def test_http_preflight_resolves_ready_snapshot_for_run_version() -> None:
    execution = parse_execution(make_execution_payload())
    loader = FakeSnapshotLoader(make_snapshot_payload())

    prepared = HttpExecutionPreflight(loader).prepare(execution)

    assert loader.calls == [DATASET_VERSION_ID]
    assert prepared.execution is execution
    assert prepared.snapshot.dataset_version_id == DATASET_VERSION_ID
    assert prepared.snapshot.status == "READY"
    assert prepared.snapshot.clickhouse_database == "olist_datasets"
    assert prepared.snapshot.table("11111111-1111-1111-1111-111111111111")


def test_http_preflight_rejects_snapshot_for_another_dataset_version() -> None:
    execution = parse_execution(make_execution_payload())
    payload = make_snapshot_payload(
        dataset_version_id="eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"
    )

    with pytest.raises(ExecutionSnapshotError):
        HttpExecutionPreflight(FakeSnapshotLoader(payload)).prepare(execution)


def test_http_preflight_rejects_non_ready_snapshot() -> None:
    execution = parse_execution(make_execution_payload())
    payload = make_snapshot_payload(status="UPLOADED")

    with pytest.raises(ExecutionSnapshotError):
        HttpExecutionPreflight(FakeSnapshotLoader(payload)).prepare(execution)


def test_backend_gated_preflight_validates_snapshot_before_failing_closed() -> None:
    execution = parse_execution(make_execution_payload())
    loader = FakeSnapshotLoader(make_snapshot_payload())

    with pytest.raises(ExecutionContractUnavailableError):
        BackendGatedExecutionPreflight(
            loader,
            backend_available=False,
            reason="backend missing",
        ).prepare(execution)

    assert loader.calls == [DATASET_VERSION_ID]


def test_backend_gated_preflight_returns_snapshot_when_backend_available() -> None:
    execution = parse_execution(make_execution_payload())
    loader = FakeSnapshotLoader(make_snapshot_payload())

    prepared = BackendGatedExecutionPreflight(
        loader,
        backend_available=True,
        reason="unused",
    ).prepare(execution)

    assert prepared.snapshot.dataset_version_id == DATASET_VERSION_ID
    assert loader.calls == [DATASET_VERSION_ID]
