"""Read-only ClickHouse preview execution for typed rule nodes."""

from __future__ import annotations

import base64
import csv
import hashlib
import io
import math
import re
import tempfile
from dataclasses import dataclass, replace
from datetime import date, datetime
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any, Dict, Mapping, Optional, Protocol, Sequence, Tuple

from app.workers.rule_execution.errors import (
    ExecutionContractUnavailableError,
    RuleExecutionError,
)
from app.workers.rule_execution.models import NodeOutcome, RuleOutputArtifact
from app.workers.rule_execution.plan import RuleNode
from app.workers.rule_execution.ports import PreparedExecution
from app.workers.rule_execution.snapshot import (
    ExecutionSnapshot,
    SnapshotColumn,
    SnapshotTable,
)

_SAFE_IDENTIFIER = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
_MAX_LITERAL_LENGTH = 10_000
_MAX_METRIC_SAMPLE_ROWS = 25
_MAX_PREVIEW_ROWS = 5_000
_MAX_OUTPUT_ROWS = 100_000
_MAX_OUTPUT_BYTES = 256 * 1024 * 1024
_MAX_AGGREGATE_GROUPS = 100
_MAX_AGGREGATE_METRICS = 100
_MAX_PREDICATE_DEPTH = 24
_MAX_DERIVE_DEPTH = 20
_MAX_DERIVE_NODES = 200
_MAX_DERIVE_ARGUMENTS = 50
SUPPORTED_PREVIEW_NODE_KINDS = frozenset(
    {
        "CHECK",
        "TRANSFORM",
        "FILTER",
        "PROJECT",
        "DEDUPE",
        "JOIN",
        "AGGREGATE",
        "DERIVE",
    }
)
_MAX_UINT64 = (1 << 64) - 1
_MAX_SAFE_JOIN_FANOUT = 1_000


@dataclass(frozen=True)
class QueryResult:
    column_names: Tuple[str, ...]
    rows: Tuple[Tuple[Any, ...], ...]


class QueryClient(Protocol):
    def query(
        self,
        sql: str,
        *,
        parameters: Optional[Mapping[str, Any]] = None,
        settings: Optional[Mapping[str, Any]] = None,
    ) -> QueryResult:
        ...

    def raw_stream(
        self,
        sql: str,
        *,
        parameters: Optional[Mapping[str, Any]] = None,
        settings: Optional[Mapping[str, Any]] = None,
    ) -> Sequence[bytes]:
        ...


@dataclass(frozen=True)
class RelationColumn:
    column_id: str
    field_id: Optional[str]
    source_name: str
    sql_name: str
    logical_type: str
    clickhouse_type: str


@dataclass(frozen=True)
class CompiledRelation:
    sql: str
    parameters: Mapping[str, Any]
    columns: Tuple[RelationColumn, ...]


@dataclass(frozen=True)
class DerivedExpression:
    sql: str
    parameters: Mapping[str, Any]
    family: str


@dataclass(frozen=True)
class JoinDiagnostics:
    sql: str
    parameters: Mapping[str, Any]
    cardinality: str
    duplicate_policy: str
    guards: Mapping[str, Any]


@dataclass(frozen=True)
class CompiledNode:
    relation: CompiledRelation
    input_relation: Optional[CompiledRelation]
    diagnostic_sql: Optional[str] = None
    diagnostic_parameters: Mapping[str, Any] = None
    diagnostic_label: Optional[str] = None
    fail_when_diagnostic_positive: bool = False
    join_diagnostics: Optional[JoinDiagnostics] = None

    def __post_init__(self) -> None:
        if self.diagnostic_parameters is None:
            object.__setattr__(self, "diagnostic_parameters", {})


class ClickHouseConnectQueryClient:
    """Small adapter around the official ``clickhouse-connect`` client."""

    def __init__(
        self,
        *,
        host: str,
        port: int,
        username: str,
        password: str,
        secure: bool = False,
        connect_timeout_seconds: float = 10.0,
        send_receive_timeout_seconds: float = 30.0,
    ) -> None:
        try:
            import clickhouse_connect
        except ImportError as exception:
            raise ExecutionContractUnavailableError(
                "clickhouse-connect is not installed; install the workers dependency group"
            ) from exception
        self._client = clickhouse_connect.get_client(
            host=host,
            port=port,
            username=username,
            password=password,
            secure=secure,
            connect_timeout=connect_timeout_seconds,
            send_receive_timeout=send_receive_timeout_seconds,
        )

    def query(
        self,
        sql: str,
        *,
        parameters: Optional[Mapping[str, Any]] = None,
        settings: Optional[Mapping[str, Any]] = None,
    ) -> QueryResult:
        result = self._client.query(
            sql,
            parameters=dict(parameters or {}),
            settings=dict(settings or {}),
        )
        return QueryResult(
            column_names=tuple(result.column_names),
            rows=tuple(tuple(row) for row in result.result_rows),
        )

    def raw_stream(
        self,
        sql: str,
        *,
        parameters: Optional[Mapping[str, Any]] = None,
        settings: Optional[Mapping[str, Any]] = None,
    ):
        return self._client.raw_stream(
            sql,
            parameters=dict(parameters or {}),
            settings=dict(settings or {}),
        )

    def close(self) -> None:
        close = getattr(self._client, "close", None)
        if callable(close):
            close()


class ClickHousePreviewBackend:
    """Compile and execute bounded, read-only previews for typed rule nodes.

    The backend never accepts SQL text from a plan. Identifiers come only from
    the validated execution snapshot, operations are selected from fixed
    whitelists, and values are passed as ClickHouse query parameters.
    """

    def __init__(
        self,
        client: QueryClient,
        *,
        max_sample_rows: int = 5_000,
        max_rows_to_read: int = 1_000_000,
        max_bytes_to_read: int = 256 * 1024 * 1024,
        max_execution_time_seconds: int = 30,
        max_output_rows: int = _MAX_OUTPUT_ROWS,
        max_output_bytes: int = _MAX_OUTPUT_BYTES,
    ) -> None:
        if max_sample_rows < 1 or max_sample_rows > _MAX_PREVIEW_ROWS:
            raise ValueError(
                "max_sample_rows must be between 1 and {0}".format(_MAX_PREVIEW_ROWS)
            )
        if min(
            max_rows_to_read,
            max_bytes_to_read,
            max_execution_time_seconds,
        ) < 1:
            raise ValueError("ClickHouse query resource limits must be positive")
        if not 1 <= max_output_rows <= _MAX_OUTPUT_ROWS:
            raise ValueError("max_output_rows exceeds the FULL output row limit")
        if not 1 <= max_output_bytes <= _MAX_OUTPUT_BYTES:
            raise ValueError("max_output_bytes exceeds the FULL output byte limit")
        self._client = client
        self._max_sample_rows = max_sample_rows
        self._max_output_rows = max_output_rows
        self._max_output_bytes = max_output_bytes
        self._settings = {
            "readonly": 1,
            "join_use_nulls": 1,
            "max_rows_to_read": max_rows_to_read,
            "max_bytes_to_read": max_bytes_to_read,
            "max_execution_time": max_execution_time_seconds,
        }
        self._outputs_by_run: Dict[str, Dict[str, CompiledRelation]] = {}
        self._artifacts_by_run: Dict[str, list[Path]] = {}

    def execute(self, node: RuleNode, prepared: PreparedExecution) -> NodeOutcome:
        execution = prepared.execution
        if execution.mode not in {"PREVIEW", "FULL"}:
            raise ExecutionContractUnavailableError(
                "unsupported ClickHouse rule execution mode"
            )
        if execution.mode == "PREVIEW":
            sample_size = execution.sample_size
            if sample_size is None or sample_size < 1 or sample_size > self._max_sample_rows:
                raise RuleExecutionError(
                    "preview sampleSize must be between 1 and {0}".format(
                        self._max_sample_rows
                    )
                )
            seed = execution.seed
            if seed is None or seed < 0 or seed > _MAX_UINT64:
                raise RuleExecutionError("preview seed must be an unsigned 64-bit integer")
        supported = SUPPORTED_PREVIEW_NODE_KINDS | ({"OUTPUT"} if execution.mode == "FULL" else set())
        if node.kind not in supported:
            raise ExecutionContractUnavailableError(
                "ClickHouse preview does not support node kind {0} yet".format(
                    node.kind
                )
            )

        outputs = self._outputs_by_run.setdefault(execution.run_id, {})
        compiler = _NodeCompiler(prepared, outputs)
        if node.kind == "OUTPUT":
            return self._execute_output(node, prepared, compiler)
        compiled = compiler.compile(node)
        if compiled.join_diagnostics is not None:
            return self._execute_join(node, prepared, compiled, outputs)
        input_rows = (
            self._count(compiled.input_relation)
            if compiled.input_relation is not None
            else None
        )
        output_rows = self._count(compiled.relation)
        metrics: Dict[str, Any] = {
            "mode": execution.mode,
            "inputRows": input_rows,
            "outputRows": output_rows,
            "columns": [
                {
                    "columnId": column.column_id,
                    "fieldId": column.field_id,
                    "name": column.source_name,
                    "logicalType": column.logical_type,
                }
                for column in compiled.relation.columns
            ],
        }
        if execution.mode == "PREVIEW":
            metrics["requestedSampleRows"] = sample_size
            metrics["sample"] = self._sample(
                compiled.relation,
                min(sample_size, _MAX_METRIC_SAMPLE_ROWS),
                seed,
            )

        diagnostic_count = None
        if compiled.diagnostic_sql is not None:
            diagnostic_count = self._scalar(
                compiled.diagnostic_sql,
                compiled.diagnostic_parameters,
            )
            metrics[compiled.diagnostic_label or "matchedRows"] = diagnostic_count
            if input_rows:
                metrics["matchedRate"] = diagnostic_count / input_rows
            else:
                metrics["matchedRate"] = 0.0

        if node.kind == "DEDUPE" and input_rows is not None:
            metrics["duplicateRowsRemoved"] = max(input_rows - output_rows, 0)

        if compiled.fail_when_diagnostic_positive and diagnostic_count:
            return NodeOutcome.failed(
                "quality check matched {0} preview rows".format(diagnostic_count)
            )

        if node.output_alias:
            outputs[node.node_id] = compiled.relation
        return NodeOutcome.succeeded(metrics=metrics)

    def _execute_join(
        self,
        node: RuleNode,
        prepared: PreparedExecution,
        compiled: CompiledNode,
        outputs: Dict[str, CompiledRelation],
    ) -> NodeOutcome:
        diagnostics = compiled.join_diagnostics
        if diagnostics is None:
            raise RuleExecutionError("JOIN diagnostics are unavailable")
        result = self._client.query(
            diagnostics.sql,
            parameters=diagnostics.parameters,
            settings=self._settings,
        )
        names = (
            "left_rows",
            "right_rows",
            "matched_left_rows",
            "left_duplicate_key_groups",
            "right_duplicate_key_groups",
            "max_right_key_multiplicity",
        )
        if result.column_names != names or len(result.rows) != 1:
            raise RuleExecutionError("ClickHouse JOIN diagnostics returned an invalid shape")
        if len(result.rows[0]) != len(names):
            raise RuleExecutionError("ClickHouse JOIN diagnostics returned an invalid row shape")
        values = {
            name: _nonnegative_integer(value, "JOIN diagnostic {0}".format(name))
            for name, value in zip(names, result.rows[0])
        }
        left_rows = values["left_rows"]
        matched_left_rows = values["matched_left_rows"]
        match_rate = matched_left_rows / left_rows if left_rows else 0.0
        left_unique = values["left_duplicate_key_groups"] == 0
        right_unique = values["right_duplicate_key_groups"] == 0
        max_fanout = values["max_right_key_multiplicity"]

        hard_violation = _join_cardinality_violation(
            diagnostics,
            left_unique=left_unique,
            right_unique=right_unique,
        )
        if hard_violation is not None:
            return NodeOutcome.failed(hard_violation)
        if max_fanout > _MAX_SAFE_JOIN_FANOUT:
            return NodeOutcome.failed(
                "JOIN preview fanout {0} exceeds the hard safety limit {1}".format(
                    max_fanout, _MAX_SAFE_JOIN_FANOUT
                )
            )
        guard_warnings = _join_guard_warnings(
            diagnostics,
            match_rate=match_rate,
            right_unique=right_unique,
            max_fanout=max_fanout,
        )
        if guard_warnings and diagnostics.guards.get("onViolation", "FAIL_RUN") != "WARN":
            return NodeOutcome.failed(guard_warnings[0])

        output_rows = self._count(compiled.relation)
        metrics: Dict[str, Any] = {
            "mode": prepared.execution.mode,
            "inputRows": left_rows,
            "leftRows": left_rows,
            "rightRows": values["right_rows"],
            "matchedLeftRows": matched_left_rows,
            "matchRate": match_rate,
            "leftKeyUnique": left_unique,
            "rightKeyUnique": right_unique,
            "leftDuplicateKeyGroups": values["left_duplicate_key_groups"],
            "rightDuplicateKeyGroups": values["right_duplicate_key_groups"],
            "maxFanout": max_fanout,
            "outputRows": output_rows,
            "averageOutputRowsPerLeftRow": output_rows / left_rows if left_rows else 0.0,
            "columns": [
                {
                    "columnId": column.column_id,
                    "fieldId": column.field_id,
                    "name": column.source_name,
                    "logicalType": column.logical_type,
                }
                for column in compiled.relation.columns
            ],
        }
        if prepared.execution.mode == "PREVIEW":
            sample_size = prepared.execution.sample_size or 1
            seed = prepared.execution.seed or 0
            metrics["requestedSampleRows"] = sample_size
            metrics["sample"] = self._sample(
                compiled.relation,
                min(sample_size, _MAX_METRIC_SAMPLE_ROWS),
                seed,
            )
        if guard_warnings:
            metrics["guardWarnings"] = guard_warnings
        if node.output_alias:
            outputs[node.node_id] = compiled.relation
        return NodeOutcome.succeeded(metrics=metrics)

    def _count(self, relation: CompiledRelation) -> int:
        sql = "SELECT count() AS row_count FROM ({0}) AS preview_count".format(
            relation.sql
        )
        return self._scalar(sql, relation.parameters)

    def cleanup_run(self, run_id: str) -> None:
        for path in self._artifacts_by_run.pop(run_id, []):
            try:
                path.unlink(missing_ok=True)
            except OSError:
                pass
        self._outputs_by_run.pop(run_id, None)

    def _execute_output(
        self, node: RuleNode, prepared: PreparedExecution, compiler: "_NodeCompiler"
    ) -> NodeOutcome:
        config = node.config
        if str(config.get("mode", "")).upper() != "MATERIALIZE_VERSION":
            raise ExecutionContractUnavailableError(
                "FULL currently supports OUTPUT mode MATERIALIZE_VERSION only"
            )
        if str(config.get("format", "")).upper() != "CSV":
            raise ExecutionContractUnavailableError(
                "FULL currently supports CSV output only"
            )
        name = config.get("name")
        if not isinstance(name, str) or not name.strip() or len(name.strip()) > 255:
            raise RuleExecutionError("OUTPUT name must contain 1 to 255 characters")
        if len(node.inputs) != 1:
            raise RuleExecutionError("OUTPUT requires exactly one input")
        relation = compiler._resolve_input(node.inputs[0], node.node_id)
        row_count = self._count(relation)
        if row_count > self._max_output_rows:
            raise RuleExecutionError(
                "OUTPUT has {0} rows; the FULL output limit is {1}".format(
                    row_count, self._max_output_rows
                )
            )
        temporary = tempfile.NamedTemporaryFile(prefix="rule-output-", suffix=".csv", delete=False)
        path = Path(temporary.name)
        temporary.close()
        digest = hashlib.sha256()
        size = 0
        try:
            with path.open("wb") as output:
                header = io.StringIO(newline="")
                csv.writer(header, lineterminator="\n").writerow(
                    _unique_display_names(relation.columns).values()
                )
                size = _write_bounded(
                    output,
                    digest,
                    header.getvalue().encode("utf-8"),
                    size,
                    self._max_output_bytes,
                )
                raw_stream = getattr(self._client, "raw_stream", None)
                if not callable(raw_stream):
                    raise ExecutionContractUnavailableError(
                        "ClickHouse client does not support bounded CSV streaming"
                    )
                sql = "SELECT * FROM ({0}) AS output_relation FORMAT CSV".format(relation.sql)
                chunks = raw_stream(
                    sql,
                    parameters=relation.parameters,
                    settings=self._settings,
                )
                for chunk in chunks:
                    if not isinstance(chunk, bytes):
                        raise RuleExecutionError("ClickHouse CSV stream returned non-byte data")
                    size = _write_bounded(output, digest, chunk, size, self._max_output_bytes)
            self._artifacts_by_run.setdefault(prepared.execution.run_id, []).append(path)
        except Exception:
            path.unlink(missing_ok=True)
            raise
        artifact = RuleOutputArtifact(
            node_id=node.node_id,
            name=name.strip(),
            file_path=str(path),
            sha256=digest.hexdigest(),
            size_bytes=size,
            row_count=row_count,
            column_count=len(relation.columns),
        )
        return NodeOutcome.succeeded(
            metrics={
                "mode": "FULL",
                "format": "CSV",
                "rowCount": row_count,
                "columnCount": len(relation.columns),
                "sizeBytes": size,
                "sha256": artifact.sha256,
            },
            output_artifact=artifact,
        )

    def _scalar(self, sql: str, parameters: Mapping[str, Any]) -> int:
        result = self._client.query(
            sql,
            parameters=parameters,
            settings=self._settings,
        )
        if len(result.rows) != 1 or len(result.rows[0]) != 1:
            raise RuleExecutionError("ClickHouse scalar query returned an invalid shape")
        return _nonnegative_integer(result.rows[0][0], "ClickHouse scalar query")

    def _sample(
        self,
        relation: CompiledRelation,
        limit: int,
        seed: int,
    ) -> list[Mapping[str, Any]]:
        parameters = dict(relation.parameters)
        parameters["result_limit"] = limit
        parameters["sample_seed"] = seed
        hash_columns = ", ".join(
            _quote_identifier(column.sql_name) for column in relation.columns
        )
        result = self._client.query(
            (
                "SELECT * FROM ({0}) AS preview_sample "
                "ORDER BY sipHash64(toString(tuple({1})), "
                "{{sample_seed:UInt64}}) LIMIT {{result_limit:UInt32}}"
            ).format(relation.sql, hash_columns),
            parameters=parameters,
            settings=self._settings,
        )
        expected_column_names = tuple(column.sql_name for column in relation.columns)
        if result.column_names != expected_column_names:
            raise RuleExecutionError("ClickHouse sample query returned unexpected columns")
        display_names = _unique_display_names(relation.columns)
        rows = []
        for raw_row in result.rows:
            if len(raw_row) != len(expected_column_names):
                raise RuleExecutionError("ClickHouse sample query returned an invalid row shape")
            row: Dict[str, Any] = {}
            for index, sql_name in enumerate(result.column_names):
                display_name = display_names.get(sql_name, sql_name)
                row[display_name] = _json_value(raw_row[index])
            rows.append(row)
        return rows


class _NodeCompiler:
    def __init__(
        self,
        prepared: PreparedExecution,
        outputs: Mapping[str, CompiledRelation],
    ) -> None:
        self._prepared = prepared
        self._snapshot = prepared.snapshot
        self._outputs = outputs
        self._counter = 0
        self._parameter_prefix = "node"

    def compile(self, node: RuleNode) -> CompiledNode:
        self._parameter_prefix = re.sub(r"[^A-Za-z0-9_]", "_", node.node_id)
        if node.kind == "CHECK":
            return self._compile_check(node)
        if node.kind == "TRANSFORM":
            return self._compile_transform(node)
        if node.kind == "FILTER":
            return self._compile_filter(node)
        if node.kind == "PROJECT":
            return self._compile_project(node)
        if node.kind == "DEDUPE":
            return self._compile_dedupe(node)
        if node.kind == "JOIN":
            return self._compile_join(node)
        if node.kind == "AGGREGATE":
            return self._compile_aggregate(node)
        if node.kind == "DERIVE":
            return self._compile_derive(node)
        raise ExecutionContractUnavailableError(
            "ClickHouse preview does not support node kind {0} yet".format(node.kind)
        )

    def _single_input(self, node: RuleNode) -> CompiledRelation:
        if len(node.inputs) != 1:
            raise RuleExecutionError(
                "node {0} requires exactly one input for preview execution".format(
                    node.node_id
                )
            )
        return self._resolve_input(node.inputs[0], node.node_id)

    def _resolve_input(
        self, raw_input: Mapping[str, Any], node_id: str
    ) -> CompiledRelation:
        input_type = str(raw_input.get("type", "")).upper()
        if input_type == "TABLE":
            table_id = str(raw_input.get("tableId", ""))
            return self._source_relation(self._snapshot.table(table_id))
        if input_type == "NODE_OUTPUT":
            upstream_id = str(raw_input.get("nodeId", ""))
            relation = self._outputs.get(upstream_id)
            if relation is None:
                raise RuleExecutionError(
                    "node {0} requires unavailable upstream output {1}".format(
                        node_id, upstream_id
                    )
                )
            return relation
        raise RuleExecutionError(
            "node {0} has unsupported input type {1}".format(node_id, input_type)
        )

    def _inputs_by_alias(self, node: RuleNode) -> Mapping[str, CompiledRelation]:
        inputs: Dict[str, CompiledRelation] = {}
        for raw_input in node.inputs:
            alias = _text(raw_input.get("alias"))
            if alias is None or not _SAFE_IDENTIFIER.match(alias):
                raise RuleExecutionError("JOIN inputs require safe, non-empty aliases")
            if alias in inputs:
                raise RuleExecutionError("JOIN input aliases must be unique")
            inputs[alias] = self._resolve_input(raw_input, node.node_id)
        return inputs

    def _source_relation(self, table: SnapshotTable) -> CompiledRelation:
        columns = tuple(_relation_column(column) for column in table.columns)
        select_list = ", ".join(
            "{0} AS {1}".format(
                _quote_identifier(column.physical_name),
                _quote_identifier(relation_column.sql_name),
            )
            for column, relation_column in zip(table.columns, columns)
        )
        database = _quote_identifier(self._snapshot.clickhouse_database)
        physical_table = _quote_identifier(table.physical_table)
        if self._prepared.execution.mode == "FULL":
            parameters: Dict[str, Any] = {}
            sql = "SELECT {select_list} FROM {database}.{table}".format(
                select_list=select_list,
                database=database,
                table=physical_table,
            )
        else:
            parameters = {
                "preview_seed": self._prepared.execution.seed or 0,
                "preview_limit": self._prepared.execution.sample_size,
            }
            hash_columns = ", ".join(
                _quote_identifier(column.physical_name) for column in table.columns
            )
            sql = (
                "SELECT {select_list} FROM {database}.{table} "
                "ORDER BY sipHash64(toString(tuple({hash_columns})), "
                "{{preview_seed:UInt64}}) LIMIT {{preview_limit:UInt32}}"
            ).format(
                select_list=select_list,
                database=database,
                table=physical_table,
                hash_columns=hash_columns,
            )
        return CompiledRelation(sql=sql, parameters=parameters, columns=columns)

    def _compile_check(self, node: RuleNode) -> CompiledNode:
        source = self._single_input(node)
        config = node.config
        scope = str(config.get("scope", "ROW")).upper()
        if scope != "ROW":
            raise ExecutionContractUnavailableError(
                "ClickHouse preview currently supports ROW checks only"
            )
        predicate_sql, predicate_parameters = self._predicate(
            config.get("predicate"), source, node.node_id
        )
        parameters = _merge_parameters(source.parameters, predicate_parameters)
        diagnostic_sql = "SELECT countIf({0}) FROM ({1}) AS check_input".format(
            predicate_sql, source.sql
        )
        effect = str(config.get("effect", "WARN")).upper()
        relation = source
        diagnostic_label = "matchedRows"
        if effect in {"DROP_ROWS", "QUARANTINE_ROWS"}:
            relation = CompiledRelation(
                sql="SELECT * FROM ({0}) AS check_input WHERE NOT ({1})".format(
                    source.sql, predicate_sql
                ),
                parameters=parameters,
                columns=source.columns,
            )
            diagnostic_label = (
                "quarantinedRows" if effect == "QUARANTINE_ROWS" else "droppedRows"
            )
        elif effect == "FLAG_ROWS":
            flag_name = str(
                (config.get("effectParams") or {}).get("flagField", "")
            ).strip()
            if not _SAFE_IDENTIFIER.match(flag_name):
                raise RuleExecutionError("FLAG_ROWS requires a safe flagField")
            flag_column = RelationColumn(
                column_id="{0}:flag".format(node.node_id),
                field_id=None,
                source_name=flag_name,
                sql_name=_synthetic_column_name(node.node_id, "flag"),
                logical_type="BOOLEAN",
                clickhouse_type="UInt8",
            )
            relation = CompiledRelation(
                sql="SELECT *, toUInt8({0}) AS {1} FROM ({2}) AS check_input".format(
                    predicate_sql,
                    _quote_identifier(flag_column.sql_name),
                    source.sql,
                ),
                parameters=parameters,
                columns=source.columns + (flag_column,),
            )
        elif effect not in {"OBSERVE", "WARN", "FAIL_RUN", "BLOCK_PUBLISH"}:
            raise RuleExecutionError("unsupported CHECK effect {0}".format(effect))
        return CompiledNode(
            relation=relation,
            input_relation=source,
            diagnostic_sql=diagnostic_sql,
            diagnostic_parameters=parameters,
            diagnostic_label=diagnostic_label,
            fail_when_diagnostic_positive=effect == "FAIL_RUN",
        )

    def _compile_transform(self, node: RuleNode) -> CompiledNode:
        source = self._single_input(node)
        config = node.config
        if config.get("condition") is not None:
            raise ExecutionContractUnavailableError(
                "conditional transforms are not implemented in preview yet"
            )
        target = self._resolve_field(config.get("target"), source)
        operation = str(config.get("operation", "")).upper()
        params = config.get("params") if isinstance(config.get("params"), Mapping) else {}
        expression, expression_parameters, transformed_column = self._transform_expression(
            operation, target, params, node.node_id
        )
        select_terms = []
        output_columns = []
        for column in source.columns:
            if column.column_id == target.column_id:
                select_terms.append(
                    "{0} AS {1}".format(expression, _quote_identifier(column.sql_name))
                )
                output_columns.append(transformed_column)
            else:
                select_terms.append(_quote_identifier(column.sql_name))
                output_columns.append(column)
        parameters = _merge_parameters(source.parameters, expression_parameters)
        return CompiledNode(
            relation=CompiledRelation(
                sql="SELECT {0} FROM ({1}) AS transform_input".format(
                    ", ".join(select_terms), source.sql
                ),
                parameters=parameters,
                columns=tuple(output_columns),
            ),
            input_relation=source,
        )

    def _compile_filter(self, node: RuleNode) -> CompiledNode:
        source = self._single_input(node)
        predicate_sql, predicate_parameters = self._predicate(
            node.config.get("predicate"), source, node.node_id
        )
        parameters = _merge_parameters(source.parameters, predicate_parameters)
        return CompiledNode(
            relation=CompiledRelation(
                sql="SELECT * FROM ({0}) AS filter_input WHERE {1}".format(
                    source.sql, predicate_sql
                ),
                parameters=parameters,
                columns=source.columns,
            ),
            input_relation=source,
        )

    def _compile_project(self, node: RuleNode) -> CompiledNode:
        source = self._single_input(node)
        selected_refs = node.config.get("select")
        dropped_refs = node.config.get("drop")
        selected_ids = self._field_ids(selected_refs, source)
        dropped_ids = self._field_ids(dropped_refs, source)
        columns = tuple(
            column
            for column in source.columns
            if (not selected_ids or column.column_id in selected_ids)
            and column.column_id not in dropped_ids
        )
        if not columns:
            raise RuleExecutionError("PROJECT would remove every input field")
        rename = node.config.get("rename")
        if rename is not None:
            if not isinstance(rename, Mapping):
                raise RuleExecutionError("PROJECT rename must be an object")
            columns = tuple(self._renamed_column(column, rename) for column in columns)
            names = [column.source_name for column in columns]
            if len(names) != len(set(names)):
                raise RuleExecutionError("PROJECT rename creates duplicate field names")
        sql = "SELECT {0} FROM ({1}) AS project_input".format(
            ", ".join(_quote_identifier(column.sql_name) for column in columns),
            source.sql,
        )
        return CompiledNode(
            relation=CompiledRelation(
                sql=sql,
                parameters=source.parameters,
                columns=columns,
            ),
            input_relation=source,
        )

    def _compile_dedupe(self, node: RuleNode) -> CompiledNode:
        source = self._single_input(node)
        keys = tuple(
            self._resolve_field(reference, source)
            for reference in _require_list(node.config.get("keys"), "DEDUPE keys")
        )
        if not keys:
            raise RuleExecutionError("DEDUPE requires at least one key")
        keep = str(node.config.get("keep", "FIRST")).upper()
        if keep not in {"FIRST", "LAST"}:
            raise ExecutionContractUnavailableError(
                "ClickHouse preview currently supports DEDUPE FIRST or LAST only"
            )
        direction = "ASC" if keep == "FIRST" else "DESC"
        partition = ", ".join(_quote_identifier(column.sql_name) for column in keys)
        all_columns = ", ".join(
            _quote_identifier(column.sql_name) for column in source.columns
        )
        sql = (
            "SELECT {columns} FROM ("
            "SELECT *, row_number() OVER (PARTITION BY {partition} "
            "ORDER BY sipHash64(toString(tuple({all_columns})), "
            "{{preview_seed:UInt64}}) {direction}) AS __row_number "
            "FROM ({source}) AS dedupe_input"
            ") AS dedupe_ranked WHERE __row_number = 1"
        ).format(
            columns=all_columns,
            partition=partition,
            all_columns=all_columns,
            direction=direction,
            source=source.sql,
        )
        return CompiledNode(
            relation=CompiledRelation(
                sql=sql,
                parameters=source.parameters,
                columns=source.columns,
            ),
            input_relation=source,
        )

    def _compile_join(self, node: RuleNode) -> CompiledNode:
        if len(node.inputs) != 2:
            raise RuleExecutionError("JOIN requires exactly two inputs")
        inputs = self._inputs_by_alias(node)
        left_alias = _text(node.config.get("leftAlias"))
        right_alias = _text(node.config.get("rightAlias"))
        if left_alias is None or left_alias not in inputs:
            raise RuleExecutionError("JOIN leftAlias does not resolve to an input")
        if right_alias is None or right_alias not in inputs or right_alias == left_alias:
            raise RuleExecutionError("JOIN rightAlias does not resolve to a distinct input")
        left = inputs[left_alias]
        right = inputs[right_alias]
        overlapping_ids = {column.column_id for column in left.columns}.intersection(
            column.column_id for column in right.columns
        )
        if overlapping_ids:
            raise ExecutionContractUnavailableError(
                "ClickHouse preview does not support JOIN inputs with overlapping column identities"
            )

        left_refs = _require_list(node.config.get("leftKeys"), "JOIN leftKeys")
        right_refs = _require_list(node.config.get("rightKeys"), "JOIN rightKeys")
        if not left_refs or len(left_refs) != len(right_refs):
            raise RuleExecutionError("JOIN requires equally sized, non-empty key lists")
        left_keys = tuple(self._resolve_field(reference, left) for reference in left_refs)
        right_keys = tuple(self._resolve_field(reference, right) for reference in right_refs)
        for left_key, right_key in zip(left_keys, right_keys):
            if not _join_types_compatible(left_key, right_key):
                raise RuleExecutionError(
                    "JOIN key types are incompatible: {0} and {1}".format(
                        left_key.logical_type, right_key.logical_type
                    )
                )

        join_type = str(node.config.get("joinType", "INNER")).upper()
        if join_type not in {"INNER", "LEFT", "RIGHT", "FULL"}:
            raise RuleExecutionError("unsupported JOIN type {0}".format(join_type))
        cardinality = str(node.config.get("cardinality", "")).upper()
        if cardinality not in {"1:1", "1:N", "N:1", "N:N"}:
            raise RuleExecutionError("JOIN requires a valid cardinality declaration")
        duplicate_policy = str(node.config.get("duplicatePolicy", "FAIL")).upper()
        if duplicate_policy != "FAIL":
            raise ExecutionContractUnavailableError(
                "JOIN duplicatePolicy {0} is not implemented in preview yet".format(
                    duplicate_policy
                )
            )
        guards = _join_guards(node.config.get("guards"))

        conditions = [
            "join_left.{0} = join_right.{1}".format(
                _quote_identifier(left_key.sql_name),
                _quote_identifier(right_key.sql_name),
            )
            for left_key, right_key in zip(left_keys, right_keys)
        ]
        condition_sql = " AND ".join(conditions)
        select_terms = [
            "join_left.{0} AS {0}".format(_quote_identifier(column.sql_name))
            for column in left.columns
        ] + [
            "join_right.{0} AS {0}".format(_quote_identifier(column.sql_name))
            for column in right.columns
        ]
        parameters = _merge_parameters(left.parameters, right.parameters)
        relation = CompiledRelation(
            sql=(
                "SELECT {columns} FROM ({left}) AS join_left {join_type} JOIN "
                "({right}) AS join_right ON {condition}"
            ).format(
                columns=", ".join(select_terms),
                left=left.sql,
                join_type=join_type,
                right=right.sql,
                condition=condition_sql,
            ),
            parameters=parameters,
            columns=left.columns + right.columns,
        )
        left_group = ", ".join(
            "{0}".format(_quote_identifier(column.sql_name)) for column in left_keys
        )
        right_group = ", ".join(
            "{0}".format(_quote_identifier(column.sql_name)) for column in right_keys
        )
        left_non_null = " AND ".join(
            "{0} IS NOT NULL".format(_quote_identifier(column.sql_name))
            for column in left_keys
        )
        right_non_null = " AND ".join(
            "{0} IS NOT NULL".format(_quote_identifier(column.sql_name))
            for column in right_keys
        )
        grouped_join_condition = " AND ".join(
            "left_key_set.{0} = right_key_counts.{1}".format(
                _quote_identifier(left_key.sql_name),
                _quote_identifier(right_key.sql_name),
            )
            for left_key, right_key in zip(left_keys, right_keys)
        )
        diagnostics_sql = (
            "SELECT "
            "(SELECT count() FROM ({left}) AS stats_left) AS left_rows, "
            "(SELECT count() FROM ({right}) AS stats_right) AS right_rows, "
            "(SELECT count() FROM ({left}) AS join_left ANY INNER JOIN ({right}) "
            "AS join_right ON {condition}) AS matched_left_rows, "
            "(SELECT count() FROM (SELECT {left_group} FROM ({left}) AS left_keys "
            "WHERE {left_non_null} GROUP BY {left_group} HAVING count() > 1)) "
            "AS left_duplicate_key_groups, "
            "(SELECT count() FROM (SELECT {right_group} FROM ({right}) AS right_keys "
            "WHERE {right_non_null} GROUP BY {right_group} HAVING count() > 1)) "
            "AS right_duplicate_key_groups, "
            "(SELECT ifNull(max(right_key_counts.key_count), 0) FROM "
            "(SELECT {right_group}, count() AS key_count FROM ({right}) AS fanout_right "
            "WHERE {right_non_null} GROUP BY {right_group}) AS right_key_counts "
            "INNER JOIN (SELECT {left_group} FROM ({left}) AS fanout_left "
            "WHERE {left_non_null} GROUP BY {left_group}) AS left_key_set "
            "ON {grouped_join_condition}) "
            "AS max_right_key_multiplicity"
        ).format(
            left=left.sql,
            right=right.sql,
            condition=condition_sql,
            left_group=left_group,
            right_group=right_group,
            left_non_null=left_non_null,
            right_non_null=right_non_null,
            grouped_join_condition=grouped_join_condition,
        )
        return CompiledNode(
            relation=relation,
            input_relation=left,
            join_diagnostics=JoinDiagnostics(
                sql=diagnostics_sql,
                parameters=parameters,
                cardinality=cardinality,
                duplicate_policy=duplicate_policy,
                guards=guards,
            ),
        )

    def _compile_aggregate(self, node: RuleNode) -> CompiledNode:
        source = self._single_input(node)
        group_refs = _require_list(node.config.get("groupBy"), "AGGREGATE groupBy")
        if len(group_refs) > _MAX_AGGREGATE_GROUPS:
            raise RuleExecutionError(
                "AGGREGATE groupBy cannot contain more than {0} fields".format(
                    _MAX_AGGREGATE_GROUPS
                )
            )
        group_columns = tuple(
            self._resolve_field(reference, source) for reference in group_refs
        )
        group_ids = [column.column_id for column in group_columns]
        if len(group_ids) != len(set(group_ids)):
            raise RuleExecutionError("AGGREGATE groupBy fields must be unique")

        raw_metrics = _require_list(node.config.get("metrics"), "AGGREGATE metrics")
        if not raw_metrics:
            raise RuleExecutionError("AGGREGATE requires at least one metric")
        if len(raw_metrics) > _MAX_AGGREGATE_METRICS:
            raise RuleExecutionError(
                "AGGREGATE cannot contain more than {0} metrics".format(
                    _MAX_AGGREGATE_METRICS
                )
            )
        metric_expressions = []
        metric_columns = []
        metric_names = set()
        group_names = {column.source_name for column in group_columns}
        for index, raw_metric in enumerate(raw_metrics):
            if not isinstance(raw_metric, Mapping):
                raise RuleExecutionError("AGGREGATE metrics must be objects")
            name = _text(raw_metric.get("name"))
            if name is None or len(name) > 255:
                raise RuleExecutionError(
                    "AGGREGATE metric names must be non-empty and at most 255 characters"
                )
            if name in metric_names or name in group_names:
                raise RuleExecutionError(
                    "AGGREGATE metric name {0} is duplicated or conflicts with a group field".format(
                        name
                    )
                )
            metric_names.add(name)
            operation = str(raw_metric.get("op", "")).upper()
            if operation not in {
                "COUNT",
                "COUNT_DISTINCT",
                "SUM",
                "AVG",
                "MIN",
                "MAX",
                "MEDIAN",
            }:
                raise RuleExecutionError(
                    "unsupported AGGREGATE operation {0}".format(operation)
                )
            params = raw_metric.get("params")
            if params is not None and (not isinstance(params, Mapping) or params):
                raise ExecutionContractUnavailableError(
                    "AGGREGATE metric params are not implemented in preview yet"
                )
            field_ref = raw_metric.get("field")
            field = None
            if not (operation == "COUNT" and field_ref == "*"):
                if field_ref is None or field_ref == "*":
                    raise RuleExecutionError(
                        "AGGREGATE operation {0} requires a field".format(operation)
                    )
                field = self._resolve_field(field_ref, source)
            expression, logical_type, clickhouse_type = _aggregate_expression(
                operation, field
            )
            metric_column = RelationColumn(
                column_id="{0}:metric:{1}".format(node.node_id, index),
                field_id=None,
                source_name=name,
                sql_name=_synthetic_column_name(node.node_id, "metric_{0}".format(index)),
                logical_type=logical_type,
                clickhouse_type=clickhouse_type,
            )
            metric_expressions.append(
                "{0} AS {1}".format(
                    expression, _quote_identifier(metric_column.sql_name)
                )
            )
            metric_columns.append(metric_column)

        select_terms = [
            _quote_identifier(column.sql_name) for column in group_columns
        ] + metric_expressions
        sql = "SELECT {0} FROM ({1}) AS aggregate_input".format(
            ", ".join(select_terms), source.sql
        )
        if group_columns:
            sql += " GROUP BY {0}".format(
                ", ".join(
                    _quote_identifier(column.sql_name) for column in group_columns
                )
            )
        parameters = source.parameters
        having = node.config.get("having")
        if having is not None:
            metric_relation = CompiledRelation(
                sql="",
                parameters={},
                columns=tuple(metric_columns),
            )
            having_sql, having_parameters = self._predicate(
                having, metric_relation, node.node_id
            )
            sql += " HAVING {0}".format(having_sql)
            parameters = _merge_parameters(parameters, having_parameters)

        return CompiledNode(
            relation=CompiledRelation(
                sql=sql,
                parameters=parameters,
                columns=group_columns + tuple(metric_columns),
            ),
            input_relation=source,
        )

    def _compile_derive(self, node: RuleNode) -> CompiledNode:
        source = self._single_input(node)
        output_field = _text(node.config.get("outputField"))
        if output_field is None or len(output_field) > 255:
            raise RuleExecutionError(
                "DERIVE outputField must be non-empty and at most 255 characters"
            )
        data_type = str(node.config.get("dataType", "")).upper()
        if data_type not in {
            "STRING",
            "INTEGER",
            "DECIMAL",
            "BOOLEAN",
            "DATE",
            "DATETIME",
        }:
            raise RuleExecutionError(
                "DERIVE dataType must be STRING, INTEGER, DECIMAL, BOOLEAN, DATE, or DATETIME"
            )
        target_family = data_type
        replace_existing = node.config.get("replaceExisting", False)
        if not isinstance(replace_existing, bool):
            raise RuleExecutionError("DERIVE replaceExisting must be boolean")
        existing = [
            column for column in source.columns if column.source_name == output_field
        ]
        if existing and not replace_existing:
            raise RuleExecutionError(
                "DERIVE outputField already exists; set replaceExisting=true explicitly"
            )
        if len(existing) > 1:
            raise RuleExecutionError(
                "DERIVE cannot replace an ambiguous duplicate output field"
            )

        expression = self._derive_expression(
            node.config.get("expression"),
            source,
            depth=0,
            budget=[0],
        )
        if not _derive_types_compatible(target_family, expression.family):
            raise RuleExecutionError(
                "DERIVE expression type {0} is incompatible with declared dataType {1}".format(
                    expression.family, data_type
                )
            )
        output_sql, clickhouse_type = _coerce_derived_expression(
            expression.sql,
            target_family,
        )
        output_column = RelationColumn(
            column_id="{0}:derived".format(node.node_id),
            field_id=None,
            source_name=output_field,
            sql_name=_synthetic_column_name(node.node_id, "derived"),
            logical_type=data_type,
            clickhouse_type=clickhouse_type,
        )

        select_terms = []
        output_columns = []
        replaced = False
        for column in source.columns:
            if column.source_name == output_field:
                select_terms.append(
                    "{0} AS {1}".format(
                        output_sql,
                        _quote_identifier(output_column.sql_name),
                    )
                )
                output_columns.append(output_column)
                replaced = True
            else:
                select_terms.append(_quote_identifier(column.sql_name))
                output_columns.append(column)
        if not replaced:
            select_terms.append(
                "{0} AS {1}".format(
                    output_sql,
                    _quote_identifier(output_column.sql_name),
                )
            )
            output_columns.append(output_column)

        return CompiledNode(
            relation=CompiledRelation(
                sql="SELECT {0} FROM ({1}) AS derive_input".format(
                    ", ".join(select_terms), source.sql
                ),
                parameters=_merge_parameters(
                    source.parameters,
                    expression.parameters,
                ),
                columns=tuple(output_columns),
            ),
            input_relation=source,
        )

    def _derive_expression(
        self,
        raw: Any,
        relation: CompiledRelation,
        *,
        depth: int,
        budget: list[int],
    ) -> DerivedExpression:
        if depth > _MAX_DERIVE_DEPTH:
            raise RuleExecutionError(
                "DERIVE expression depth cannot exceed {0}".format(_MAX_DERIVE_DEPTH)
            )
        budget[0] += 1
        if budget[0] > _MAX_DERIVE_NODES:
            raise RuleExecutionError(
                "DERIVE expression cannot contain more than {0} nodes".format(
                    _MAX_DERIVE_NODES
                )
            )
        if isinstance(raw, str):
            return _derive_field_expression(self._resolve_field(raw, relation))
        if not isinstance(raw, Mapping):
            raise RuleExecutionError("DERIVE expression must be a structured object")

        operation = str(raw.get("op", raw.get("type", ""))).upper()
        if operation == "FIELD":
            reference = raw.get("field", raw)
            return _derive_field_expression(self._resolve_field(reference, relation))
        if operation == "LITERAL":
            return self._derive_literal_expression(raw)
        if operation == "DATE_DIFF_DAYS":
            left = self._derive_expression(
                raw.get("left"), relation, depth=depth + 1, budget=budget
            )
            right = self._derive_expression(
                raw.get("right"), relation, depth=depth + 1, budget=budget
            )
            left = _derive_temporal_expression(left)
            right = _derive_temporal_expression(right)
            return DerivedExpression(
                sql="dateDiff('day', {0}, {1})".format(right.sql, left.sql),
                parameters=_merge_parameters(left.parameters, right.parameters),
                family="INTEGER",
            )
        if operation == "DATE_PART":
            part = str(raw.get("part", "")).upper()
            functions = {
                "YEAR": "toYear",
                "QUARTER": "toQuarter",
                "MONTH": "toMonth",
                "WEEK": "toISOWeek",
                "DAY": "toDayOfMonth",
                "DAY_OF_WEEK": "toDayOfWeek",
                "DAY_OF_YEAR": "toDayOfYear",
                "HOUR": "toHour",
                "MINUTE": "toMinute",
                "SECOND": "toSecond",
            }
            function = functions.get(part)
            if function is None:
                raise RuleExecutionError("unsupported DERIVE DATE_PART {0}".format(part))
            operand = raw.get("value", raw.get("field", _arg(raw, 0)))
            value = self._derive_expression(
                operand, relation, depth=depth + 1, budget=budget
            )
            value = _derive_temporal_expression(value)
            return DerivedExpression(
                sql="{0}({1})".format(function, value.sql),
                parameters=value.parameters,
                family="INTEGER",
            )
        if operation in {"CONCAT", "COALESCE"}:
            arguments = _derive_arguments(raw, operation, minimum=2)
            compiled = tuple(
                self._derive_expression(
                    argument,
                    relation,
                    depth=depth + 1,
                    budget=budget,
                )
                for argument in arguments
            )
            parameters = _merge_derived_parameters(compiled)
            if operation == "CONCAT":
                sql = "concat({0})".format(
                    ", ".join("toString({0})".format(item.sql) for item in compiled)
                )
                family = "STRING"
            else:
                family = _derive_common_family(compiled, operation)
                sql = "coalesce({0})".format(
                    ", ".join(item.sql for item in compiled)
                )
            return DerivedExpression(sql=sql, parameters=parameters, family=family)
        if operation == "CASE_WHEN":
            raw_branches = raw.get("branches", raw.get("cases"))
            branches = _require_list(raw_branches, "DERIVE CASE_WHEN branches")
            if not branches or len(branches) > _MAX_DERIVE_ARGUMENTS:
                raise RuleExecutionError(
                    "DERIVE CASE_WHEN requires 1 to {0} branches".format(
                        _MAX_DERIVE_ARGUMENTS
                    )
                )
            fragments = []
            result_expressions = []
            parameters: Mapping[str, Any] = {}
            for branch in branches:
                if not isinstance(branch, Mapping):
                    raise RuleExecutionError(
                        "DERIVE CASE_WHEN branches must be objects"
                    )
                condition_sql, condition_parameters = self._predicate(
                    branch.get("when"), relation, "derive_case"
                )
                result = self._derive_expression(
                    branch.get("then"),
                    relation,
                    depth=depth + 1,
                    budget=budget,
                )
                fragments.extend((condition_sql, result.sql))
                result_expressions.append(result)
                parameters = _merge_parameters(parameters, condition_parameters)
                parameters = _merge_parameters(parameters, result.parameters)
            fallback_raw = raw.get("else", raw.get("otherwise"))
            if fallback_raw is None:
                raise RuleExecutionError("DERIVE CASE_WHEN requires an else expression")
            fallback = self._derive_expression(
                fallback_raw,
                relation,
                depth=depth + 1,
                budget=budget,
            )
            result_expressions.append(fallback)
            parameters = _merge_parameters(parameters, fallback.parameters)
            family = _derive_common_family(result_expressions, operation)
            return DerivedExpression(
                sql="multiIf({0}, {1})".format(
                    ", ".join(fragments), fallback.sql
                ),
                parameters=parameters,
                family=family,
            )
        if operation == "ARITHMETIC":
            operator = str(raw.get("operator", raw.get("operation", ""))).upper()
            functions = {
                "ADD": "plus",
                "SUBTRACT": "minus",
                "MULTIPLY": "multiply",
                "DIVIDE": "divide",
                "MODULO": "modulo",
                "POWER": "pow",
            }
            function = functions.get(operator)
            if function is None:
                raise RuleExecutionError(
                    "unsupported DERIVE arithmetic operator {0}".format(operator)
                )
            arguments = _derive_binary_arguments(raw, operation)
            left = self._derive_expression(
                arguments[0], relation, depth=depth + 1, budget=budget
            )
            right = self._derive_expression(
                arguments[1], relation, depth=depth + 1, budget=budget
            )
            _require_derive_families(
                operation, (left, right), {"INTEGER", "FLOAT", "DECIMAL"}
            )
            right_sql = (
                "nullIf({0}, 0)".format(right.sql)
                if operator in {"DIVIDE", "MODULO"}
                else right.sql
            )
            family = (
                "FLOAT"
                if operator in {"DIVIDE", "POWER"}
                else _derive_common_family((left, right), operation)
            )
            return DerivedExpression(
                sql="{0}({1}, {2})".format(function, left.sql, right_sql),
                parameters=_merge_parameters(left.parameters, right.parameters),
                family=family,
            )
        if operation == "DISTANCE_HAVERSINE":
            arguments = raw.get("args")
            if arguments is None:
                arguments = [
                    raw.get("longitude1"),
                    raw.get("latitude1"),
                    raw.get("longitude2"),
                    raw.get("latitude2"),
                ]
            arguments = _require_list(
                arguments, "DERIVE DISTANCE_HAVERSINE arguments"
            )
            if len(arguments) != 4 or any(item is None for item in arguments):
                raise RuleExecutionError(
                    "DERIVE DISTANCE_HAVERSINE requires longitude1, latitude1, longitude2, latitude2"
                )
            compiled = tuple(
                self._derive_expression(
                    argument,
                    relation,
                    depth=depth + 1,
                    budget=budget,
                )
                for argument in arguments
            )
            _require_derive_families(
                operation, compiled, {"INTEGER", "FLOAT", "DECIMAL"}
            )
            return DerivedExpression(
                sql="greatCircleDistance({0})".format(
                    ", ".join(item.sql for item in compiled)
                ),
                parameters=_merge_derived_parameters(compiled),
                family="FLOAT",
            )
        raise RuleExecutionError(
            "unsupported DERIVE expression operation {0}".format(operation)
        )

    def _derive_literal_expression(
        self, raw: Mapping[str, Any]
    ) -> DerivedExpression:
        value = raw.get("value")
        declared_type = _text(raw.get("dataType"))
        family = (
            _derive_data_type_family(declared_type.upper())
            if declared_type is not None
            else _infer_literal_family(value)
        )
        if family is None:
            raise RuleExecutionError("DERIVE literal has an unsupported dataType")
        value = _literal_value(value)
        name = self._next_parameter_name()
        if family == "BOOLEAN":
            if not isinstance(value, bool):
                raise RuleExecutionError("DERIVE BOOLEAN literal must be true or false")
            return DerivedExpression(
                sql="{{{0}:UInt8}}".format(name),
                parameters={name: 1 if value else 0},
                family=family,
            )
        if family == "INTEGER":
            if isinstance(value, bool):
                raise RuleExecutionError("DERIVE INTEGER literal cannot be boolean")
            try:
                decimal_value = Decimal(str(value))
            except (InvalidOperation, TypeError, ValueError) as exception:
                raise RuleExecutionError("DERIVE INTEGER literal is invalid") from exception
            if (
                not decimal_value.is_finite()
                or decimal_value != decimal_value.to_integral_value()
            ):
                raise RuleExecutionError("DERIVE INTEGER literal is invalid")
            return DerivedExpression(
                sql="{{{0}:Int64}}".format(name),
                parameters={name: int(decimal_value)},
                family=family,
            )
        if family == "FLOAT":
            normalized = float(value)
            if not math.isfinite(normalized):
                raise RuleExecutionError("DERIVE FLOAT literal must be finite")
            return DerivedExpression(
                sql="{{{0}:Float64}}".format(name),
                parameters={name: normalized},
                family=family,
            )
        if family == "DECIMAL":
            try:
                normalized_decimal = Decimal(str(value))
            except (InvalidOperation, TypeError, ValueError) as exception:
                raise RuleExecutionError("DERIVE DECIMAL literal is invalid") from exception
            if not normalized_decimal.is_finite():
                raise RuleExecutionError("DERIVE DECIMAL literal must be finite")
            return DerivedExpression(
                sql="toDecimal128OrNull({{{0}:String}}, 12)".format(name),
                parameters={name: str(normalized_decimal)},
                family=family,
            )
        if family == "DATE":
            return DerivedExpression(
                sql="toDateOrNull({{{0}:String}})".format(name),
                parameters={name: str(value)},
                family=family,
            )
        if family == "DATETIME":
            return DerivedExpression(
                sql="parseDateTime64BestEffortOrNull({{{0}:String}})".format(name),
                parameters={name: str(value)},
                family=family,
            )
        return DerivedExpression(
            sql="{{{0}:String}}".format(name),
            parameters={name: str(value)},
            family="STRING",
        )

    def _predicate(
        self,
        raw: Any,
        relation: CompiledRelation,
        node_id: str,
        depth: int = 0,
    ) -> Tuple[str, Mapping[str, Any]]:
        if depth > _MAX_PREDICATE_DEPTH:
            raise RuleExecutionError(
                "predicate nesting cannot exceed {0} levels".format(
                    _MAX_PREDICATE_DEPTH
                )
            )
        if not isinstance(raw, Mapping):
            raise RuleExecutionError("node {0} predicate must be an object".format(node_id))
        op = str(raw.get("op", "")).upper()
        if op in {"AND", "OR"}:
            children = _require_list(raw.get("predicates"), "{0} predicates".format(op))
            if not children:
                raise RuleExecutionError("{0} predicate requires children".format(op))
            fragments = []
            parameters: Mapping[str, Any] = {}
            for child in children:
                fragment, child_parameters = self._predicate(
                    child, relation, node_id, depth + 1
                )
                fragments.append("({0})".format(fragment))
                parameters = _merge_parameters(parameters, child_parameters)
            return (" {0} ".format(op)).join(fragments), parameters
        if op == "NOT":
            nested = raw.get("predicate")
            fragment, parameters = self._predicate(
                nested, relation, node_id, depth + 1
            )
            return "NOT ({0})".format(fragment), parameters

        field = self._resolve_field(raw.get("field") or _first_arg(raw), relation)
        column_sql = _quote_identifier(field.sql_name)
        if op == "IS_NULL":
            return "{0} IS NULL".format(column_sql), {}
        if op in {"IS_EMPTY", "IS_BLANK"}:
            function = "trimBoth" if op == "IS_BLANK" else "toString"
            return "{0}(ifNull(toString({1}), '')) = ''".format(function, column_sql), {}
        if op in {"EQ", "NE", "GT", "GTE", "LT", "LTE"}:
            value = raw.get("value", _arg(raw, 1))
            placeholder, parameters = self._parameter(value, field)
            operator = {"EQ": "=", "NE": "!=", "GT": ">", "GTE": ">=", "LT": "<", "LTE": "<="}[op]
            left = _typed_column_expression(column_sql, field)
            return "{0} {1} {2}".format(left, operator, placeholder), parameters
        if op == "BETWEEN":
            lower = raw.get("value", _arg(raw, 1))
            upper = raw.get("upperValue", _arg(raw, 2))
            lower_sql, lower_parameters = self._parameter(lower, field)
            upper_sql, upper_parameters = self._parameter(upper, field)
            return (
                "{0} BETWEEN {1} AND {2}".format(
                    _typed_column_expression(column_sql, field), lower_sql, upper_sql
                ),
                _merge_parameters(lower_parameters, upper_parameters),
            )
        if op in {"IN", "NOT_IN"}:
            values = raw.get("value")
            if values is None:
                values = list(raw.get("args", ()))[1:]
            values = _require_list(values, "{0} values".format(op))
            if not values:
                raise RuleExecutionError("{0} requires at least one value".format(op))
            placeholders = []
            parameters: Mapping[str, Any] = {}
            for value in values:
                placeholder, item_parameters = self._parameter(value, field)
                placeholders.append(placeholder)
                parameters = _merge_parameters(parameters, item_parameters)
            keyword = "NOT IN" if op == "NOT_IN" else "IN"
            return (
                "{0} {1} ({2})".format(
                    _typed_column_expression(column_sql, field),
                    keyword,
                    ", ".join(placeholders),
                ),
                parameters,
            )
        if op == "REGEX_MATCH":
            value = raw.get("value", _arg(raw, 1))
            placeholder, parameters = self._string_parameter(value)
            return (
                "match(ifNull(toString({0}), ''), {1})".format(column_sql, placeholder),
                parameters,
            )
        if op == "DATE_PARSEABLE":
            return (
                "parseDateTime64BestEffortOrNull(toString({0})) IS NOT NULL".format(
                    column_sql
                ),
                {},
            )
        raise ExecutionContractUnavailableError(
            "ClickHouse preview does not support predicate operation {0} yet".format(op)
        )

    def _resolve_field(self, raw: Any, relation: CompiledRelation) -> RelationColumn:
        if isinstance(raw, str):
            matches = [column for column in relation.columns if column.source_name == raw]
        elif isinstance(raw, Mapping):
            column_id = _text(raw.get("columnId"))
            field_id = _text(raw.get("fieldId"))
            name = _text(raw.get("nameSnapshot")) or _text(raw.get("name"))
            if column_id:
                matches = [column for column in relation.columns if column.column_id == column_id]
            elif field_id:
                matches = [column for column in relation.columns if column.field_id == field_id]
            elif name:
                matches = [column for column in relation.columns if column.source_name == name]
            else:
                matches = []
        else:
            matches = []
        if len(matches) != 1:
            raise RuleExecutionError("field reference does not resolve to one input column")
        return matches[0]

    def _field_ids(self, raw: Any, relation: CompiledRelation) -> set[str]:
        if raw is None:
            return set()
        return {
            self._resolve_field(reference, relation).column_id
            for reference in _require_list(raw, "field list")
        }

    def _renamed_column(
        self, column: RelationColumn, rename: Mapping[str, Any]
    ) -> RelationColumn:
        value = rename.get(column.column_id, rename.get(column.source_name))
        if value is None:
            return column
        name = _text(value)
        if name is None or len(name) > 255:
            raise RuleExecutionError("PROJECT rename values must be non-empty names")
        return replace(column, source_name=name)

    def _transform_expression(
        self,
        operation: str,
        target: RelationColumn,
        params: Mapping[str, Any],
        node_id: str,
    ) -> Tuple[str, Mapping[str, Any], RelationColumn]:
        column = _quote_identifier(target.sql_name)
        if operation == "TRIM":
            return "trimBoth(toString({0}))".format(column), {}, target
        if operation == "LOWER":
            return "lowerUTF8(toString({0}))".format(column), {}, target
        if operation == "UPPER":
            return "upperUTF8(toString({0}))".format(column), {}, target
        if operation == "FILL_VALUE":
            placeholder, parameters = self._parameter(params.get("value"), target)
            expression = "if({0} IS NULL OR toString({0}) = '', {1}, {0})".format(
                column, placeholder
            )
            return expression, parameters, target
        if operation == "REPLACE":
            find_sql, find_parameters = self._string_parameter(params.get("find"))
            replacement = params.get("replacement", params.get("replace"))
            replace_sql, replace_parameters = self._string_parameter(replacement)
            return (
                "replaceAll(toString({0}), {1}, {2})".format(
                    column, find_sql, replace_sql
                ),
                _merge_parameters(find_parameters, replace_parameters),
                target,
            )
        if operation == "REGEX_REPLACE":
            find_sql, find_parameters = self._string_parameter(params.get("find"))
            replacement = params.get("replacement", params.get("replace"))
            replace_sql, replace_parameters = self._string_parameter(replacement)
            return (
                "replaceRegexpAll(toString({0}), {1}, {2})".format(
                    column, find_sql, replace_sql
                ),
                _merge_parameters(find_parameters, replace_parameters),
                target,
            )
        if operation == "CAST":
            data_type = str(params.get("dataType", "")).upper()
            casts = {
                "STRING": ("toString", "STRING", "String"),
                "INT64": ("toInt64OrNull", "INT64", "Nullable(Int64)"),
                "FLOAT64": ("toFloat64OrNull", "FLOAT64", "Nullable(Float64)"),
                "DATE": ("parseDateTime64BestEffortOrNull", "DATE", "Nullable(DateTime64)"),
                "BOOLEAN": ("toUInt8OrNull", "BOOLEAN", "Nullable(UInt8)"),
            }
            cast = casts.get(data_type)
            if cast is None:
                raise RuleExecutionError("unsupported CAST target {0}".format(data_type))
            function, logical_type, clickhouse_type = cast
            return (
                "{0}(toString({1}))".format(function, column),
                {},
                replace(
                    target,
                    logical_type=logical_type,
                    clickhouse_type=clickhouse_type,
                ),
            )
        raise ExecutionContractUnavailableError(
            "ClickHouse preview does not support transform operation {0} yet".format(
                operation
            )
        )

    def _parameter(
        self, value: Any, column: RelationColumn
    ) -> Tuple[str, Mapping[str, Any]]:
        value = _literal_value(value)
        name = self._next_parameter_name()
        logical_type = column.logical_type.upper()
        clickhouse_type = column.clickhouse_type.upper()
        if "BOOL" in logical_type or "UINT8" in clickhouse_type:
            if isinstance(value, bool):
                normalized = value
            elif isinstance(value, str) and value.strip().lower() in {
                "true", "1", "yes", "false", "0", "no"
            }:
                normalized = value.strip().lower() in {"true", "1", "yes"}
            elif isinstance(value, int) and value in {0, 1}:
                normalized = bool(value)
            else:
                raise RuleExecutionError("comparison value must be boolean")
            return "{{{0}:UInt8}}".format(name), {name: 1 if normalized else 0}
        if "INT" in logical_type or "INT" in clickhouse_type:
            if isinstance(value, bool):
                raise RuleExecutionError("boolean is not a valid integer comparison value")
            try:
                numeric_value = Decimal(str(value))
            except (InvalidOperation, TypeError, ValueError) as exception:
                raise RuleExecutionError("comparison value must be an integer") from exception
            if (
                not numeric_value.is_finite()
                or numeric_value != numeric_value.to_integral_value()
            ):
                raise RuleExecutionError("comparison value must be an integer")
            normalized = int(numeric_value)
            return "{{{0}:Int64}}".format(name), {name: normalized}
        if any(token in logical_type or token in clickhouse_type for token in ("FLOAT", "DECIMAL")):
            if isinstance(value, bool):
                raise RuleExecutionError("boolean is not a valid numeric comparison value")
            try:
                normalized = float(value)
            except (TypeError, ValueError) as exception:
                raise RuleExecutionError("comparison value must be numeric") from exception
            if not math.isfinite(normalized):
                raise RuleExecutionError("comparison value must be finite")
            return "{{{0}:Float64}}".format(name), {name: normalized}
        if "DATE" in logical_type or "DATE" in clickhouse_type:
            normalized = str(value)
            return (
                "parseDateTime64BestEffortOrNull({{{0}:String}})".format(name),
                {name: normalized},
            )
        return "{{{0}:String}}".format(name), {name: str(value)}

    def _string_parameter(self, value: Any) -> Tuple[str, Mapping[str, Any]]:
        normalized = _literal_value(value)
        name = self._next_parameter_name()
        return "{{{0}:String}}".format(name), {name: str(normalized)}

    def _next_parameter_name(self) -> str:
        self._counter += 1
        return "{0}_value_{1}".format(self._parameter_prefix, self._counter)


def _relation_column(column: SnapshotColumn) -> RelationColumn:
    return RelationColumn(
        column_id=column.column_id,
        field_id=column.field_id,
        source_name=column.source_name,
        sql_name=_column_name(column.column_id),
        logical_type=column.logical_type,
        clickhouse_type=column.clickhouse_type,
    )


def _column_name(column_id: str) -> str:
    normalized = re.sub(r"[^A-Za-z0-9_]", "_", column_id)
    return "col_{0}".format(normalized)


def _synthetic_column_name(node_id: str, suffix: str) -> str:
    normalized = re.sub(r"[^A-Za-z0-9_]", "_", node_id)
    return "node_{0}_{1}".format(normalized, suffix)


def _quote_identifier(value: str) -> str:
    if not _SAFE_IDENTIFIER.match(value):
        raise RuleExecutionError("unsafe ClickHouse identifier")
    return "`{0}`".format(value)


def _typed_column_expression(sql: str, column: RelationColumn) -> str:
    logical_type = column.logical_type.upper()
    clickhouse_type = column.clickhouse_type.upper()
    if "BOOL" in logical_type or "UINT8" in clickhouse_type:
        return "toUInt8OrNull(toString({0}))".format(sql)
    if "INT" in logical_type or "INT" in clickhouse_type:
        return "toInt64OrNull(toString({0}))".format(sql)
    if any(token in logical_type or token in clickhouse_type for token in ("FLOAT", "DECIMAL")):
        return "toFloat64OrNull(toString({0}))".format(sql)
    if "DATE" in logical_type or "DATE" in clickhouse_type:
        return "parseDateTime64BestEffortOrNull(toString({0}))".format(sql)
    return "ifNull(toString({0}), '')".format(sql)


def _merge_parameters(*sources: Mapping[str, Any]) -> Mapping[str, Any]:
    merged: Dict[str, Any] = {}
    for source in sources:
        for key, value in source.items():
            if key in merged and merged[key] != value:
                raise RuleExecutionError("query parameter collision for {0}".format(key))
            merged[key] = value
    return merged


def _nonnegative_integer(value: Any, context: str) -> int:
    if isinstance(value, bool) or not isinstance(value, (int, float, Decimal)):
        raise RuleExecutionError("{0} returned an invalid count".format(context))
    if isinstance(value, Decimal):
        is_integer = value.is_finite() and value == value.to_integral_value()
    elif isinstance(value, float):
        is_integer = math.isfinite(value) and value.is_integer()
    else:
        is_integer = True
    if not is_integer or value < 0:
        raise RuleExecutionError("{0} returned an invalid count".format(context))
    return int(value)


def _aggregate_expression(
    operation: str,
    field: Optional[RelationColumn],
) -> Tuple[str, str, str]:
    if operation == "COUNT" and field is None:
        return "count()", "INTEGER", "UInt64"
    if field is None:
        raise RuleExecutionError(
            "AGGREGATE operation {0} requires a field".format(operation)
        )
    column = _quote_identifier(field.sql_name)
    family = _join_type_family(field)
    if family is None:
        raise RuleExecutionError(
            "AGGREGATE field {0} has an unsupported logical type".format(
                field.source_name
            )
        )
    if operation == "COUNT":
        return "count({0})".format(column), "INTEGER", "UInt64"
    if operation == "COUNT_DISTINCT":
        return (
            "uniqExactIf({0}, {0} IS NOT NULL)".format(column),
            "INTEGER",
            "UInt64",
        )
    if operation in {"SUM", "AVG", "MEDIAN"}:
        if family not in {"INTEGER", "FLOAT", "DECIMAL"}:
            raise RuleExecutionError(
                "AGGREGATE operation {0} requires a numeric field".format(operation)
            )
        _require_numeric_physical_type(field, family)
        typed_column = _aggregate_value_expression(field)
        function = {
            "SUM": "sum",
            "AVG": "avg",
            "MEDIAN": "quantileExact(0.5)",
        }[operation]
        if operation == "SUM":
            logical_type, clickhouse_type = field.logical_type, field.clickhouse_type
        else:
            logical_type, clickhouse_type = "FLOAT", "Float64"
        return (
            "{0}({1})".format(function, typed_column),
            logical_type,
            clickhouse_type,
        )
    if operation in {"MIN", "MAX"}:
        typed_column = _aggregate_value_expression(field)
        logical_type, clickhouse_type = _aggregate_result_type(field, family)
        return (
            "{0}({1})".format(operation.lower(), typed_column),
            logical_type,
            clickhouse_type,
        )
    raise RuleExecutionError("unsupported AGGREGATE operation {0}".format(operation))


def _aggregate_value_expression(column: RelationColumn) -> str:
    sql = _quote_identifier(column.sql_name)
    family = _join_type_family(column)
    if family in {"BOOLEAN", "INTEGER", "FLOAT", "STRING"}:
        expected = {
            "BOOLEAN": "BOOLEAN",
            "INTEGER": "INTEGER",
            "FLOAT": "FLOAT",
            "STRING": "STRING",
        }[family]
        if _clickhouse_join_type_family(column) != expected:
            raise RuleExecutionError(
                "AGGREGATE field {0} has an incompatible physical type".format(
                    column.source_name
                )
            )
        return sql
    if family == "DECIMAL":
        if _clickhouse_join_type_family(column) != "DECIMAL":
            raise RuleExecutionError(
                "decimal aggregation requires a ClickHouse Decimal physical type"
            )
        return sql
    if family in {"DATE", "DATETIME"}:
        if _clickhouse_join_type_family(column) != family:
            raise ExecutionContractUnavailableError(
                "temporal MIN/MAX requires a typed ClickHouse date column"
            )
        return sql
    raise RuleExecutionError(
        "AGGREGATE field {0} has an unsupported logical type".format(
            column.source_name
        )
    )


def _aggregate_result_type(
    column: RelationColumn, family: str
) -> Tuple[str, str]:
    return column.logical_type, column.clickhouse_type


def _derive_data_type_family(data_type: str) -> Optional[str]:
    normalized = data_type.upper()
    if normalized in {
        "STRING",
        "INTEGER",
        "DECIMAL",
        "BOOLEAN",
        "DATE",
        "DATETIME",
    }:
        return normalized
    if normalized in {"FLOAT", "FLOAT64", "DOUBLE"}:
        return "FLOAT"
    return None


def _derive_field_expression(column: RelationColumn) -> DerivedExpression:
    family = _join_type_family(column)
    if family is None:
        raise RuleExecutionError(
            "DERIVE field {0} has an unsupported logical type".format(
                column.source_name
            )
        )
    sql = _quote_identifier(column.sql_name)
    physical_family = _clickhouse_join_type_family(column)
    if family == physical_family:
        return DerivedExpression(sql=sql, parameters={}, family=family)
    conversions = {
        "BOOLEAN": (
            "multiIf(lowerUTF8(trimBoth(toString({0}))) IN ('true', '1', 'yes'), "
            "toUInt8(1), lowerUTF8(trimBoth(toString({0}))) IN "
            "('false', '0', 'no'), toUInt8(0), CAST(NULL, 'Nullable(UInt8)'))"
        ),
        "INTEGER": "toInt64OrNull(toString({0}))",
        "FLOAT": "toFloat64OrNull(toString({0}))",
        "DECIMAL": "toDecimal128OrNull(toString({0}), 12)",
        "STRING": "toString({0})",
        "DATE": "toDateOrNull(toString({0}))",
        "DATETIME": "parseDateTime64BestEffortOrNull(toString({0}))",
    }
    return DerivedExpression(
        sql=conversions[family].format(sql),
        parameters={},
        family=family,
    )


def _derive_temporal_expression(
    expression: DerivedExpression,
) -> DerivedExpression:
    if expression.family in {"DATE", "DATETIME"}:
        return expression
    if expression.family == "STRING":
        return DerivedExpression(
            sql="parseDateTime64BestEffortOrNull(toString({0}))".format(
                expression.sql
            ),
            parameters=expression.parameters,
            family="DATETIME",
        )
    raise RuleExecutionError(
        "DERIVE temporal operation received incompatible expression type {0}".format(
            expression.family
        )
    )


def _coerce_derived_expression(sql: str, family: str) -> Tuple[str, str]:
    casts = {
        "STRING": ("toString({0})", "Nullable(String)"),
        "INTEGER": ("toInt64OrNull(toString({0}))", "Nullable(Int64)"),
        "FLOAT": ("toFloat64OrNull(toString({0}))", "Nullable(Float64)"),
        "DECIMAL": (
            "toDecimal128OrNull(toString({0}), 12)",
            "Nullable(Decimal(38, 12))",
        ),
        "BOOLEAN": ("toUInt8OrNull(toString({0}))", "Nullable(UInt8)"),
        "DATE": ("toDateOrNull(toString({0}))", "Nullable(Date)"),
        "DATETIME": (
            "parseDateTime64BestEffortOrNull(toString({0}))",
            "Nullable(DateTime64(3))",
        ),
    }
    expression, clickhouse_type = casts[family]
    return expression.format(sql), clickhouse_type


def _derive_types_compatible(target: str, actual: str) -> bool:
    if target == actual or target == "STRING":
        return True
    numeric = {"INTEGER", "FLOAT", "DECIMAL"}
    if target == "DECIMAL" and actual in numeric:
        return True
    if target == "FLOAT" and actual in numeric:
        return True
    if target == "DATETIME" and actual in {"DATE", "DATETIME", "STRING"}:
        return True
    if target == "DATE" and actual in {"DATE", "DATETIME", "STRING"}:
        return True
    return False


def _infer_literal_family(value: Any) -> Optional[str]:
    if isinstance(value, bool):
        return "BOOLEAN"
    if isinstance(value, int):
        return "INTEGER"
    if isinstance(value, (float, Decimal)):
        return "DECIMAL"
    if isinstance(value, datetime):
        return "DATETIME"
    if isinstance(value, date):
        return "DATE"
    if isinstance(value, str):
        return "STRING"
    return None


def _derive_arguments(
    raw: Mapping[str, Any], operation: str, *, minimum: int
) -> Sequence[Any]:
    arguments = _require_list(raw.get("args"), "DERIVE {0} arguments".format(operation))
    if len(arguments) < minimum or len(arguments) > _MAX_DERIVE_ARGUMENTS:
        raise RuleExecutionError(
            "DERIVE {0} requires {1} to {2} arguments".format(
                operation, minimum, _MAX_DERIVE_ARGUMENTS
            )
        )
    return arguments


def _derive_binary_arguments(
    raw: Mapping[str, Any], operation: str
) -> Tuple[Any, Any]:
    arguments = raw.get("args")
    if arguments is None:
        arguments = (raw.get("left"), raw.get("right"))
    arguments = _require_list(arguments, "DERIVE {0} arguments".format(operation))
    if len(arguments) != 2 or any(argument is None for argument in arguments):
        raise RuleExecutionError(
            "DERIVE {0} requires exactly two arguments".format(operation)
        )
    return arguments[0], arguments[1]


def _require_derive_families(
    operation: str,
    expressions: Sequence[DerivedExpression],
    allowed: set[str],
) -> None:
    invalid = [expression.family for expression in expressions if expression.family not in allowed]
    if invalid:
        raise RuleExecutionError(
            "DERIVE {0} received incompatible expression type {1}".format(
                operation, invalid[0]
            )
        )


def _derive_common_family(
    expressions: Sequence[DerivedExpression], operation: str
) -> str:
    families = {expression.family for expression in expressions}
    if len(families) == 1:
        return next(iter(families))
    numeric = {"INTEGER", "FLOAT", "DECIMAL"}
    if families.issubset(numeric):
        if "FLOAT" in families:
            return "FLOAT"
        if "DECIMAL" in families:
            return "DECIMAL"
        return "INTEGER"
    raise RuleExecutionError(
        "DERIVE {0} result expressions must have compatible types".format(operation)
    )


def _merge_derived_parameters(
    expressions: Sequence[DerivedExpression],
) -> Mapping[str, Any]:
    parameters: Mapping[str, Any] = {}
    for expression in expressions:
        parameters = _merge_parameters(parameters, expression.parameters)
    return parameters


def _require_numeric_physical_type(column: RelationColumn, family: str) -> None:
    if _clickhouse_join_type_family(column) != family:
        if family == "DECIMAL":
            raise RuleExecutionError(
                "decimal aggregation requires a ClickHouse Decimal physical type"
            )
        raise RuleExecutionError(
            "AGGREGATE numeric field {0} has an incompatible physical type".format(
                column.source_name
            )
        )


def _join_guards(raw: Any) -> Mapping[str, Any]:
    if raw is None:
        return {}
    if not isinstance(raw, Mapping):
        raise RuleExecutionError("JOIN guards must be an object")
    unknown = set(raw).difference(
        {"rightKeyUnique", "maxFanout", "minMatchRate", "onViolation"}
    )
    if unknown:
        raise RuleExecutionError(
            "JOIN guards contain unsupported fields: {0}".format(", ".join(sorted(unknown)))
        )
    guards: Dict[str, Any] = {}
    if "rightKeyUnique" in raw:
        if not isinstance(raw["rightKeyUnique"], bool):
            raise RuleExecutionError("JOIN rightKeyUnique guard must be boolean")
        guards["rightKeyUnique"] = raw["rightKeyUnique"]
    if "maxFanout" in raw:
        max_fanout = raw["maxFanout"]
        if (
            isinstance(max_fanout, bool)
            or not isinstance(max_fanout, (int, float, Decimal))
            or not math.isfinite(float(max_fanout))
            or float(max_fanout) < 0
        ):
            raise RuleExecutionError("JOIN maxFanout guard must be a non-negative number")
        guards["maxFanout"] = float(max_fanout)
    if "minMatchRate" in raw:
        min_match_rate = raw["minMatchRate"]
        if (
            isinstance(min_match_rate, bool)
            or not isinstance(min_match_rate, (int, float, Decimal))
            or not math.isfinite(float(min_match_rate))
            or not 0 <= float(min_match_rate) <= 1
        ):
            raise RuleExecutionError("JOIN minMatchRate guard must be between 0 and 1")
        guards["minMatchRate"] = float(min_match_rate)
    on_violation = str(raw.get("onViolation", "FAIL_RUN")).upper()
    if on_violation not in {"WARN", "FAIL_RUN"}:
        raise RuleExecutionError("JOIN onViolation must be WARN or FAIL_RUN")
    guards["onViolation"] = on_violation
    return guards


def _join_cardinality_violation(
    diagnostics: JoinDiagnostics,
    *,
    left_unique: bool,
    right_unique: bool,
) -> Optional[str]:
    if diagnostics.cardinality in {"1:1", "1:N"} and not left_unique:
        return "JOIN left keys violate declared cardinality {0}".format(
            diagnostics.cardinality
        )
    if diagnostics.cardinality in {"1:1", "N:1"} and not right_unique:
        return "JOIN right keys violate declared cardinality {0}".format(
            diagnostics.cardinality
        )
    return None


def _join_guard_warnings(
    diagnostics: JoinDiagnostics,
    *,
    match_rate: float,
    right_unique: bool,
    max_fanout: int,
) -> list[str]:
    warnings = []
    if diagnostics.guards.get("rightKeyUnique") is True and not right_unique:
        warnings.append("JOIN rightKeyUnique guard failed")
    max_allowed_fanout = diagnostics.guards.get("maxFanout")
    if max_allowed_fanout is not None and max_fanout > max_allowed_fanout:
        warnings.append(
            "JOIN maxFanout guard failed: {0} > {1:g}".format(
                max_fanout, max_allowed_fanout
            )
        )
    min_match_rate = diagnostics.guards.get("minMatchRate")
    if min_match_rate is not None and match_rate < min_match_rate:
        warnings.append(
            "JOIN minMatchRate guard failed: {0:.6f} < {1:g}".format(
                match_rate, min_match_rate
            )
        )
    return warnings


def _join_types_compatible(left: RelationColumn, right: RelationColumn) -> bool:
    left_family = _join_type_family(left)
    right_family = _join_type_family(right)
    if left_family is None or left_family != right_family:
        return False
    left_physical = _clickhouse_join_type_family(left)
    right_physical = _clickhouse_join_type_family(right)
    allowed_physical = {
        "BOOLEAN": {"BOOLEAN"},
        "INTEGER": {"INTEGER"},
        "FLOAT": {"FLOAT"},
        "DECIMAL": {"DECIMAL"},
        "STRING": {"STRING"},
        "DATE": {"DATE", "STRING"},
        "DATETIME": {"DATETIME", "STRING"},
    }.get(left_family, set())
    return (
        left_physical is not None
        and left_physical == right_physical
        and left_physical in allowed_physical
    )


def _join_type_family(column: RelationColumn) -> Optional[str]:
    logical_type = column.logical_type.upper()
    if logical_type in {"BOOL", "BOOLEAN"}:
        return "BOOLEAN"
    if logical_type in {"INTEGER", "LONG", "INT64"}:
        return "INTEGER"
    if logical_type in {"FLOAT", "DOUBLE", "FLOAT64"}:
        return "FLOAT"
    if logical_type == "DECIMAL":
        return "DECIMAL"
    if logical_type == "STRING":
        return "STRING"
    if logical_type in {"DATE", "DATETIME"}:
        return logical_type
    return None


def _clickhouse_join_type_family(column: RelationColumn) -> Optional[str]:
    clickhouse_type = column.clickhouse_type.upper().strip()
    while clickhouse_type.startswith(("NULLABLE(", "LOWCARDINALITY(")) and clickhouse_type.endswith(")"):
        clickhouse_type = clickhouse_type[clickhouse_type.index("(") + 1 : -1].strip()
    if clickhouse_type == "STRING" or clickhouse_type.startswith("FIXEDSTRING("):
        return "STRING"
    if clickhouse_type in {"BOOL", "BOOLEAN"} or (
        column.logical_type.upper() in {"BOOL", "BOOLEAN"}
        and clickhouse_type == "UINT8"
    ):
        return "BOOLEAN"
    if re.fullmatch(r"U?INT(8|16|32|64|128|256)", clickhouse_type):
        return "INTEGER"
    if clickhouse_type.startswith(("FLOAT", "BIGNUM")):
        return "FLOAT"
    if clickhouse_type.startswith("DECIMAL"):
        return "DECIMAL"
    if clickhouse_type in {"DATE", "DATE32"}:
        return "DATE"
    if clickhouse_type.startswith(("DATETIME", "TIMESTAMP")):
        return "DATETIME"
    return None


def _literal_value(value: Any) -> Any:
    if isinstance(value, Mapping) and str(value.get("type", "")).upper() == "LITERAL":
        value = value.get("value")
    if value is None or isinstance(value, (Mapping, list, tuple, set)):
        raise RuleExecutionError("comparison value must be a scalar literal")
    if isinstance(value, str) and len(value) > _MAX_LITERAL_LENGTH:
        raise RuleExecutionError("comparison value is too long")
    if isinstance(value, float) and not math.isfinite(value):
        raise RuleExecutionError("comparison value must be finite")
    if isinstance(value, Decimal) and not value.is_finite():
        raise RuleExecutionError("comparison value must be finite")
    return value


def _require_list(value: Any, label: str) -> Sequence[Any]:
    if value is None:
        return ()
    if not isinstance(value, (list, tuple)):
        raise RuleExecutionError("{0} must be an array".format(label))
    return value


def _first_arg(predicate: Mapping[str, Any]) -> Any:
    return _arg(predicate, 0)


def _arg(predicate: Mapping[str, Any], index: int) -> Any:
    args = predicate.get("args")
    if isinstance(args, (list, tuple)) and len(args) > index:
        return args[index]
    return None


def _text(value: Any) -> Optional[str]:
    if not isinstance(value, str):
        return None
    normalized = value.strip()
    return normalized or None


def _unique_display_names(
    columns: Sequence[RelationColumn],
) -> Mapping[str, str]:
    counts: Dict[str, int] = {}
    names: Dict[str, str] = {}
    for column in columns:
        count = counts.get(column.source_name, 0) + 1
        counts[column.source_name] = count
        names[column.sql_name] = (
            column.source_name
            if count == 1
            else "{0} ({1})".format(column.source_name, count)
        )
    return names


def _json_value(value: Any) -> Any:
    if isinstance(value, float) and not math.isfinite(value):
        return str(value)
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, (date, datetime)):
        return value.isoformat()
    if isinstance(value, bytes):
        return base64.b64encode(value).decode("ascii")
    if isinstance(value, (list, tuple)):
        return [_json_value(item) for item in value]
    if isinstance(value, Mapping):
        return {str(key): _json_value(item) for key, item in value.items()}
    return str(value)


def _write_bounded(output, digest, chunk: bytes, size: int, limit: int) -> int:
    next_size = size + len(chunk)
    if next_size > limit:
        raise RuleExecutionError(
            "OUTPUT exceeds the configured {0}-byte CSV limit".format(limit)
        )
    output.write(chunk)
    digest.update(chunk)
    return next_size


__all__ = [
    "ClickHouseConnectQueryClient",
    "ClickHousePreviewBackend",
    "CompiledRelation",
    "QueryClient",
    "QueryResult",
    "RelationColumn",
    "SUPPORTED_PREVIEW_NODE_KINDS",
]
