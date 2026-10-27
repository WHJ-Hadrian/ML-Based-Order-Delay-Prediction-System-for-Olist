"""Typed execution snapshot used to resolve logical IDs to physical columns."""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any, Dict, Mapping, Optional, Tuple

from app.workers.rule_execution.errors import ExecutionSnapshotError

_PHYSICAL_IDENTIFIER = re.compile(
    r"^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)?$"
)


@dataclass(frozen=True)
class SnapshotColumn:
    """One physical column and its logical identity in a dataset version."""

    column_id: str
    field_id: Optional[str]
    source_name: str
    physical_name: str
    logical_type: str
    clickhouse_type: str
    nullable: bool


@dataclass(frozen=True)
class SnapshotTable:
    """One physical table in an immutable dataset version."""

    table_id: str
    table_key: str
    physical_table: str
    row_count: Optional[int]
    columns: Tuple[SnapshotColumn, ...]

    def column_by_id(self, column_id: str) -> SnapshotColumn:
        for column in self.columns:
            if column.column_id == column_id:
                return column
        raise ExecutionSnapshotError(
            "table {0} has no column {1}".format(self.table_id, column_id)
        )

    def column_by_field_id(self, field_id: str) -> SnapshotColumn:
        for column in self.columns:
            if column.field_id == field_id:
                return column
        raise ExecutionSnapshotError(
            "table {0} has no field binding {1}".format(self.table_id, field_id)
        )

    def column_by_source_name(self, source_name: str) -> SnapshotColumn:
        for column in self.columns:
            if column.source_name == source_name:
                return column
        raise ExecutionSnapshotError(
            "table {0} has no source column {1}".format(self.table_id, source_name)
        )


@dataclass(frozen=True)
class ExecutionSnapshot:
    """Immutable READY snapshot for exactly one dataset version."""

    dataset_version_id: str
    status: str
    manifest_hash: str
    schema_hash: str
    clickhouse_database: str
    row_count: Optional[int]
    tables: Tuple[SnapshotTable, ...]

    def table(self, table_id: str) -> SnapshotTable:
        for table in self.tables:
            if table.table_id == table_id:
                return table
        raise ExecutionSnapshotError(
            "dataset version {0} has no table {1}".format(
                self.dataset_version_id, table_id
            )
        )

    def column_by_id(self, column_id: str) -> SnapshotColumn:
        for table in self.tables:
            for column in table.columns:
                if column.column_id == column_id:
                    return column
        raise ExecutionSnapshotError(
            "dataset version {0} has no column {1}".format(
                self.dataset_version_id, column_id
            )
        )

    def column_by_field_id(self, field_id: str) -> SnapshotColumn:
        for table in self.tables:
            for column in table.columns:
                if column.field_id == field_id:
                    return column
        raise ExecutionSnapshotError(
            "dataset version {0} has no field binding {1}".format(
                self.dataset_version_id, field_id
            )
        )

    @classmethod
    def from_payload(
        cls,
        payload: Mapping[str, Any],
        expected_dataset_version_id: Optional[str] = None,
    ) -> "ExecutionSnapshot":
        if not isinstance(payload, Mapping):
            raise ExecutionSnapshotError("execution snapshot must be a JSON object")

        dataset_version_id = _required_text(payload, "datasetVersionId", "snapshot")
        if (
            expected_dataset_version_id is not None
            and dataset_version_id != expected_dataset_version_id
        ):
            raise ExecutionSnapshotError(
                "execution snapshot belongs to dataset version {0}, expected {1}".format(
                    dataset_version_id, expected_dataset_version_id
                )
            )

        status = _required_text(payload, "status", "snapshot").upper()
        if status != "READY":
            raise ExecutionSnapshotError(
                "execution snapshot for dataset version {0} is {1}, not READY".format(
                    dataset_version_id, status
                )
            )

        manifest_hash = _required_text(payload, "manifestHash", "snapshot")
        schema_hash = _required_text(payload, "schemaHash", "snapshot")
        clickhouse_database = _required_text(payload, "clickhouseDatabase", "snapshot")
        row_count = _optional_int(payload.get("rowCount"), "snapshot.rowCount")

        raw_tables = payload.get("tables")
        if not isinstance(raw_tables, list) or not raw_tables:
            raise ExecutionSnapshotError("execution snapshot.tables must be a non-empty array")

        tables = []
        table_ids = set()
        column_ids = set()
        field_ids = set()
        for table_index, raw_table in enumerate(raw_tables):
            table = _parse_table(raw_table, table_index, column_ids, field_ids)
            if table.table_id in table_ids:
                raise ExecutionSnapshotError(
                    "execution snapshot table id {0} is duplicated".format(table.table_id)
                )
            table_ids.add(table.table_id)
            tables.append(table)

        return cls(
            dataset_version_id=dataset_version_id,
            status=status,
            manifest_hash=manifest_hash,
            schema_hash=schema_hash,
            clickhouse_database=clickhouse_database,
            row_count=row_count,
            tables=tuple(tables),
        )


def _parse_table(
    raw_table: Any,
    index: int,
    column_ids: set,
    field_ids: set,
) -> SnapshotTable:
    if not isinstance(raw_table, Mapping):
        raise ExecutionSnapshotError(
            "execution snapshot table at index {0} must be an object".format(index)
        )

    table_id = _required_text(raw_table, "tableId", "snapshot table")
    table_key = _required_text(raw_table, "tableKey", "snapshot table {0}".format(table_id))
    physical_table = _required_text(
        raw_table, "physicalTable", "snapshot table {0}".format(table_id)
    )
    _require_physical_identifier(physical_table, "physicalTable", table_id)
    row_count = _optional_int(
        raw_table.get("rowCount"), "snapshot table {0}.rowCount".format(table_id)
    )

    raw_columns = raw_table.get("columns")
    if not isinstance(raw_columns, list) or not raw_columns:
        raise ExecutionSnapshotError(
            "snapshot table {0}.columns must be a non-empty array".format(table_id)
        )

    columns = []
    source_names = set()
    for column_index, raw_column in enumerate(raw_columns):
        column = _parse_column(raw_column, table_id, column_index)
        if column.column_id in column_ids:
            raise ExecutionSnapshotError(
                "snapshot column id {0} is duplicated".format(column.column_id)
            )
        if column.field_id is not None and column.field_id in field_ids:
            raise ExecutionSnapshotError(
                "snapshot field binding {0} is duplicated".format(column.field_id)
            )
        if column.source_name in source_names:
            raise ExecutionSnapshotError(
                "snapshot table {0} has duplicate source column {1}".format(
                    table_id, column.source_name
                )
            )
        column_ids.add(column.column_id)
        if column.field_id is not None:
            field_ids.add(column.field_id)
        source_names.add(column.source_name)
        columns.append(column)

    return SnapshotTable(
        table_id=table_id,
        table_key=table_key,
        physical_table=physical_table,
        row_count=row_count,
        columns=tuple(columns),
    )


def _parse_column(raw_column: Any, table_id: str, index: int) -> SnapshotColumn:
    if not isinstance(raw_column, Mapping):
        raise ExecutionSnapshotError(
            "snapshot table {0} column at index {1} must be an object".format(
                table_id, index
            )
        )

    column_id = _required_text(raw_column, "columnId", "snapshot column")
    field_id = _optional_text(raw_column.get("fieldId"))
    source_name = _required_text(
        raw_column, "sourceName", "snapshot column {0}".format(column_id)
    )
    physical_name = _required_text(
        raw_column, "physicalName", "snapshot column {0}".format(column_id)
    )
    _require_physical_identifier(physical_name, "physicalName", column_id)
    logical_type = _required_text(
        raw_column, "logicalType", "snapshot column {0}".format(column_id)
    ).upper()
    clickhouse_type = _required_text(
        raw_column, "clickhouseType", "snapshot column {0}".format(column_id)
    )

    nullable = raw_column.get("nullable", True)
    if not isinstance(nullable, bool):
        raise ExecutionSnapshotError(
            "snapshot column {0}.nullable must be a boolean".format(column_id)
        )

    return SnapshotColumn(
        column_id=column_id,
        field_id=field_id,
        source_name=source_name,
        physical_name=physical_name,
        logical_type=logical_type,
        clickhouse_type=clickhouse_type,
        nullable=nullable,
    )


def _required_text(payload: Mapping[str, Any], field: str, context: str) -> str:
    value = payload.get(field)
    if not isinstance(value, str) or not value.strip():
        raise ExecutionSnapshotError(
            "execution {0} is missing non-empty {1}".format(context, field)
        )
    return value.strip()


def _optional_text(value: Any) -> Optional[str]:
    if value is None:
        return None
    if not isinstance(value, str):
        raise ExecutionSnapshotError("optional snapshot identifier must be a string")
    normalized = value.strip()
    return normalized or None


def _optional_int(value: Any, field: str) -> Optional[int]:
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, int):
        raise ExecutionSnapshotError("{0} must be an integer".format(field))
    if value < 0:
        raise ExecutionSnapshotError("{0} must not be negative".format(field))
    return value


def _require_physical_identifier(value: str, field: str, owner: str) -> None:
    if not _PHYSICAL_IDENTIFIER.match(value):
        raise ExecutionSnapshotError(
            "{0} {1} for {2} is not a safe physical identifier".format(field, value, owner)
        )


__all__ = [
    "ExecutionSnapshot",
    "SnapshotColumn",
    "SnapshotTable",
]
