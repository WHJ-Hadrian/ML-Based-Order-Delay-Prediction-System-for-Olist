from copy import deepcopy

import pytest

from app.workers.rule_execution.errors import ExecutionSnapshotError
from app.workers.rule_execution.snapshot import ExecutionSnapshot
from tests.rule_execution_support import (
    CUSTOMERS_TABLE_ID,
    DATASET_VERSION_ID,
    ORDERS_TABLE_ID,
    make_snapshot_payload,
)


def test_snapshot_resolves_physical_tables_and_columns() -> None:
    snapshot = ExecutionSnapshot.from_payload(
        make_snapshot_payload(), expected_dataset_version_id=DATASET_VERSION_ID
    )

    assert snapshot.status == "READY"
    assert snapshot.manifest_hash == "sha256:manifest"
    assert snapshot.clickhouse_database == "olist_datasets"
    assert snapshot.table(ORDERS_TABLE_ID).physical_table == "t_orders"
    assert snapshot.table(CUSTOMERS_TABLE_ID).table_key == "customers"
    assert (
        snapshot.column_by_field_id("44444444-4444-4444-4444-444444444444").physical_name
        == "c001"
    )
    assert (
        snapshot.column_by_id("55555555-5555-5555-5555-555555555555").source_name
        == "customer_id"
    )


def test_snapshot_rejects_another_dataset_version() -> None:
    with pytest.raises(ExecutionSnapshotError):
        ExecutionSnapshot.from_payload(
            make_snapshot_payload(),
            expected_dataset_version_id="eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee",
        )


def test_snapshot_requires_ready_status() -> None:
    with pytest.raises(ExecutionSnapshotError):
        ExecutionSnapshot.from_payload(make_snapshot_payload(status="UPLOADED"))


def test_snapshot_rejects_duplicate_column_ids() -> None:
    payload = deepcopy(make_snapshot_payload())
    payload["tables"][1]["columns"].append(
        dict(payload["tables"][0]["columns"][0])
    )

    with pytest.raises(ExecutionSnapshotError):
        ExecutionSnapshot.from_payload(payload)


def test_snapshot_rejects_unsafe_physical_identifiers() -> None:
    payload = make_snapshot_payload()
    payload["tables"][0]["columns"][0]["physicalName"] = "c001; DROP TABLE orders"

    with pytest.raises(ExecutionSnapshotError):
        ExecutionSnapshot.from_payload(payload)


def test_snapshot_requires_hashes_and_non_empty_tables() -> None:
    missing_hash = make_snapshot_payload()
    missing_hash.pop("schemaHash")
    with pytest.raises(ExecutionSnapshotError):
        ExecutionSnapshot.from_payload(missing_hash)

    empty_tables = make_snapshot_payload()
    empty_tables["tables"] = []
    with pytest.raises(ExecutionSnapshotError):
        ExecutionSnapshot.from_payload(empty_tables)
