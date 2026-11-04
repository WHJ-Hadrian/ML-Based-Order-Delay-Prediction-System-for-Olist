"""Tests for bounded, parameterized ClickHouse rule previews."""

from __future__ import annotations

from dataclasses import replace
from typing import Any, Mapping, Optional, Sequence

import pytest

from app.workers.rule_execution.clickhouse_backend import (
    ClickHousePreviewBackend,
    QueryResult,
)
from app.workers.rule_execution.errors import (
    ExecutionContractUnavailableError,
    RuleExecutionError,
)
from app.workers.rule_execution.plan import RuleNode
from app.workers.rule_execution.ports import PreparedExecution
from app.workers.rule_execution.snapshot import ExecutionSnapshot
from tests.rule_execution_support import (
    CUSTOMERS_TABLE_ID,
    DATASET_VERSION_ID,
    ORDERS_TABLE_ID,
    make_execution_payload,
    make_snapshot_payload,
    parse_execution,
)

ORDER_ID_COLUMN_ID = "33333333-3333-3333-3333-333333333333"
STATUS_COLUMN_ID = "77777777-7777-7777-7777-777777777777"
ACTIVE_COLUMN_ID = "88888888-8888-8888-8888-888888888888"
CUSTOMER_ID_COLUMN_ID = "55555555-5555-5555-5555-555555555555"
DELIVERED_AT_COLUMN_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
ESTIMATED_AT_COLUMN_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"


class ScriptedQueryClient:
    def __init__(self, responses: list[QueryResult]) -> None:
        self.responses = list(responses)
        self.calls: list[tuple[str, Mapping[str, Any], Mapping[str, Any]]] = []

    def query(
        self,
        sql: str,
        *,
        parameters: Optional[Mapping[str, Any]] = None,
        settings: Optional[Mapping[str, Any]] = None,
    ) -> QueryResult:
        self.calls.append((sql, dict(parameters or {}), dict(settings or {})))
        if not self.responses:
            raise AssertionError("unexpected ClickHouse query: {0}".format(sql))
        return self.responses.pop(0)


def test_filter_then_project_keeps_values_bound_and_reuses_upstream_sql() -> None:
    literal = "delivered') OR 1 = 1 --"
    client = ScriptedQueryClient(
        [
            _scalar(3),
            _scalar(1),
            _rows(
                (ORDER_ID_COLUMN_ID, STATUS_COLUMN_ID, ACTIVE_COLUMN_ID),
                ("o1", literal, 1),
            ),
            _scalar(1),
            _scalar(1),
            _rows((ORDER_ID_COLUMN_ID,), ("o1",)),
        ]
    )
    backend = ClickHousePreviewBackend(client)
    prepared = _prepared()
    filter_node = _node(
        "filter",
        "FILTER",
        {
            "predicate": {
                "op": "EQ",
                "field": {"columnId": STATUS_COLUMN_ID},
                "value": literal,
            }
        },
        output_alias="filtered",
    )
    project_node = _node(
        "project",
        "PROJECT",
        {"select": [{"columnId": ORDER_ID_COLUMN_ID}], "drop": []},
        input_node=("filter", "filtered"),
        output_alias="projected",
    )

    filter_outcome = backend.execute(filter_node, prepared)
    project_outcome = backend.execute(project_node, prepared)

    assert filter_outcome.status == "SUCCEEDED"
    assert project_outcome.metrics["columns"][0]["name"] == "order_id"
    assert literal not in "\n".join(sql for sql, _, _ in client.calls)
    assert any(literal in parameters.values() for _, parameters, _ in client.calls)
    project_sql = client.calls[4][0]
    assert "project_input" in project_sql
    assert "filter_input WHERE" in project_sql
    assert "ORDER BY sipHash64" in client.calls[2][0]
    assert client.calls[2][1]["sample_seed"] == 42
    assert all(call[2]["readonly"] == 1 for call in client.calls)


def test_projected_out_field_cannot_be_referenced_downstream() -> None:
    client = ScriptedQueryClient(
        [_scalar(2), _scalar(2), _rows((ORDER_ID_COLUMN_ID,), ("o1",))]
    )
    backend = ClickHousePreviewBackend(client)
    prepared = _prepared()
    backend.execute(
        _node(
            "project",
            "PROJECT",
            {"select": [{"columnId": ORDER_ID_COLUMN_ID}], "drop": []},
            output_alias="projected",
        ),
        prepared,
    )

    with pytest.raises(RuleExecutionError, match="does not resolve"):
        backend.execute(
            _node(
                "filter",
                "FILTER",
                {
                    "predicate": {
                        "op": "EQ",
                        "field": {"columnId": STATUS_COLUMN_ID},
                        "value": "delivered",
                    }
                },
                input_node=("project", "projected"),
                output_alias="filtered",
            ),
            prepared,
        )
    assert not client.responses


@pytest.mark.parametrize(
    ("effect", "metric_name"),
    [("DROP_ROWS", "droppedRows"), ("QUARANTINE_ROWS", "quarantinedRows")],
)
def test_check_reports_row_effect_metrics(effect: str, metric_name: str) -> None:
    client = ScriptedQueryClient(
        [
            _scalar(4),
            _scalar(3),
            _rows(
                (ORDER_ID_COLUMN_ID, STATUS_COLUMN_ID, ACTIVE_COLUMN_ID),
                ("o1", "delivered", 1),
            ),
            _scalar(1),
        ]
    )
    backend = ClickHousePreviewBackend(client)
    outcome = backend.execute(
        _node(
            "check",
            "CHECK",
            {
                "scope": "ROW",
                "predicate": {
                    "op": "IS_NULL",
                    "field": {"columnId": STATUS_COLUMN_ID},
                },
                "effect": effect,
            },
            output_alias="checked",
        ),
        _prepared(),
    )

    assert outcome.status == "SUCCEEDED"
    assert outcome.metrics["inputRows"] == 4
    assert outcome.metrics["outputRows"] == 3
    assert outcome.metrics[metric_name] == 1
    assert outcome.metrics["matchedRate"] == 0.25


def test_fail_run_check_returns_failed_outcome() -> None:
    client = ScriptedQueryClient(
        [
            _scalar(4),
            _scalar(4),
            _rows(
                (ORDER_ID_COLUMN_ID, STATUS_COLUMN_ID, ACTIVE_COLUMN_ID),
                ("o1", None, 1),
            ),
            _scalar(1),
        ]
    )
    outcome = ClickHousePreviewBackend(client).execute(
        _node(
            "check",
            "CHECK",
            {
                "scope": "ROW",
                "predicate": {
                    "op": "IS_NULL",
                    "field": {"columnId": STATUS_COLUMN_ID},
                },
                "effect": "FAIL_RUN",
            },
        ),
        _prepared(),
    )

    assert outcome.status == "FAILED"
    assert "matched 1" in outcome.error_message


def test_dedupe_reports_removed_rows_and_uses_deterministic_order() -> None:
    client = ScriptedQueryClient(
        [
            _scalar(5),
            _scalar(3),
            _rows(
                (ORDER_ID_COLUMN_ID, STATUS_COLUMN_ID, ACTIVE_COLUMN_ID),
                ("o1", "delivered", 1),
            ),
        ]
    )
    outcome = ClickHousePreviewBackend(client).execute(
        _node(
            "dedupe",
            "DEDUPE",
            {"keys": [{"columnId": ORDER_ID_COLUMN_ID}], "keep": "FIRST"},
            output_alias="deduped",
        ),
        _prepared(),
    )

    assert outcome.metrics["duplicateRowsRemoved"] == 2
    assert "row_number() OVER" in client.calls[1][0]
    assert "sipHash64" in client.calls[1][0]


def test_boolean_values_use_uint8_parameters() -> None:
    client = ScriptedQueryClient(
        [
            _scalar(2),
            _scalar(1),
            _rows((ORDER_ID_COLUMN_ID, STATUS_COLUMN_ID, ACTIVE_COLUMN_ID), ("o1", "ok", 1)),
        ]
    )
    ClickHousePreviewBackend(client).execute(
        _node(
            "filter_bool",
            "FILTER",
            {
                "predicate": {
                    "op": "EQ",
                    "field": {"columnId": ACTIVE_COLUMN_ID},
                    "value": "true",
                }
            },
            output_alias="active_rows",
        ),
        _prepared(),
    )

    assert any(":UInt8}" in sql for sql, _, _ in client.calls)
    assert any(1 in parameters.values() for _, parameters, _ in client.calls)


def test_oversized_preview_and_unsupported_full_node_fail_before_querying() -> None:
    client = ScriptedQueryClient([])
    backend = ClickHousePreviewBackend(client, max_sample_rows=5)
    prepared = _prepared()

    with pytest.raises(RuleExecutionError, match="between 1 and 5"):
        backend.execute(
            _node("filter", "FILTER", {"predicate": {"op": "IS_NULL", "field": "status"}}),
            replace(prepared, execution=replace(prepared.execution, sample_size=6)),
        )
    with pytest.raises(ExecutionContractUnavailableError, match="GATE"):
        backend.execute(
            _node("gate", "GATE", {}),
            replace(prepared, execution=replace(prepared.execution, mode="FULL")),
        )
    with pytest.raises(ExecutionContractUnavailableError, match="GATE"):
        ClickHousePreviewBackend(client).execute(_node("gate", "GATE", {}), prepared)
    with pytest.raises(RuleExecutionError, match="unsigned 64-bit"):
        ClickHousePreviewBackend(client).execute(
            _node("filter", "FILTER", {"predicate": {"op": "IS_NULL", "field": "status"}}),
            replace(prepared, execution=replace(prepared.execution, seed=-1)),
        )
    with pytest.raises(ValueError, match="between 1 and 5000"):
        ClickHousePreviewBackend(client, max_sample_rows=5001)
    assert not client.calls


def test_join_reports_quality_metrics_and_preserves_both_schemas() -> None:
    client = ScriptedQueryClient(
        [
            _join_diagnostics(4, 3, 3, 0, 0, 1),
            _scalar(4),
            _rows(
                (
                    ORDER_ID_COLUMN_ID,
                    STATUS_COLUMN_ID,
                    ACTIVE_COLUMN_ID,
                    CUSTOMER_ID_COLUMN_ID,
                ),
                ("customer-1", "delivered", 1, "customer-1"),
            ),
        ]
    )

    outcome = ClickHousePreviewBackend(client).execute(
        _join_node(
            guards={"rightKeyUnique": True, "maxFanout": 1, "minMatchRate": 0.5}
        ),
        _prepared(),
    )

    assert outcome.status == "SUCCEEDED"
    assert outcome.metrics["leftRows"] == 4
    assert outcome.metrics["rightRows"] == 3
    assert outcome.metrics["matchRate"] == 0.75
    assert outcome.metrics["rightKeyUnique"] is True
    assert outcome.metrics["maxFanout"] == 1
    assert outcome.metrics["outputRows"] == 4
    assert len(outcome.metrics["columns"]) == 4
    diagnostics_sql = client.calls[0][0]
    assert "ANY INNER JOIN" in diagnostics_sql
    assert "right_duplicate_key_groups" in diagnostics_sql
    assert "LEFT JOIN" in client.calls[1][0]
    assert all(call[2]["readonly"] == 1 for call in client.calls)
    assert all(call[2]["join_use_nulls"] == 1 for call in client.calls)


def test_join_can_consume_an_upstream_project_output() -> None:
    client = ScriptedQueryClient(
        [
            _scalar(4),
            _scalar(4),
            _rows((ORDER_ID_COLUMN_ID,), ("customer-1",)),
            _join_diagnostics(4, 3, 3, 0, 0, 1),
            _scalar(4),
            _rows(
                (ORDER_ID_COLUMN_ID, CUSTOMER_ID_COLUMN_ID),
                ("customer-1", "customer-1"),
            ),
        ]
    )
    backend = ClickHousePreviewBackend(client)
    prepared = _prepared()
    backend.execute(
        _node(
            "project",
            "PROJECT",
            {"select": [{"columnId": ORDER_ID_COLUMN_ID}], "drop": []},
            output_alias="projected",
        ),
        prepared,
    )
    join_node = _join_node()
    join_node = replace(
        join_node,
        inputs=(
            {
                "type": "NODE_OUTPUT",
                "nodeId": "project",
                "outputAlias": "projected",
                "alias": "orders",
            },
            join_node.inputs[1],
        ),
    )

    outcome = backend.execute(join_node, prepared)

    assert outcome.status == "SUCCEEDED"
    assert len(outcome.metrics["columns"]) == 2
    assert "project_input" in client.calls[3][0]


def test_join_guard_failure_stops_before_materializing_join_result() -> None:
    client = ScriptedQueryClient([_join_diagnostics(10, 4, 4, 0, 1, 3)])

    outcome = ClickHousePreviewBackend(client).execute(
        _join_node(
            cardinality="N:N",
            guards={"maxFanout": 2, "minMatchRate": 0.5},
        ),
        _prepared(),
    )

    assert outcome.status == "FAILED"
    assert "maxFanout" in outcome.error_message
    assert len(client.calls) == 1


def test_join_warn_guard_records_warning_without_blocking_output() -> None:
    client = ScriptedQueryClient(
        [
            _join_diagnostics(10, 4, 4, 0, 1, 3),
            _scalar(12),
            _rows(
                (
                    ORDER_ID_COLUMN_ID,
                    STATUS_COLUMN_ID,
                    ACTIVE_COLUMN_ID,
                    CUSTOMER_ID_COLUMN_ID,
                ),
                ("customer-1", "delivered", 1, "customer-1"),
            ),
        ]
    )

    outcome = ClickHousePreviewBackend(client).execute(
        _join_node(
            cardinality="N:N",
            guards={"maxFanout": 2, "onViolation": "WARN"},
        ),
        _prepared(),
    )

    assert outcome.status == "SUCCEEDED"
    assert outcome.metrics["guardWarnings"] == ["JOIN maxFanout guard failed: 3 > 2"]


def test_unimplemented_join_duplicate_policy_fails_before_querying() -> None:
    client = ScriptedQueryClient([])

    with pytest.raises(ExecutionContractUnavailableError, match="KEEP_FIRST"):
        ClickHousePreviewBackend(client).execute(
            _join_node(duplicate_policy="KEEP_FIRST"),
            _prepared(),
        )
    assert not client.calls


def test_join_rejects_incompatible_key_types_before_querying() -> None:
    client = ScriptedQueryClient([])

    with pytest.raises(RuleExecutionError, match="incompatible"):
        ClickHousePreviewBackend(client).execute(
            _join_node(),
            _prepared(
                customer_logical_type="INTEGER",
                customer_clickhouse_type="Nullable(Int64)",
            ),
        )
    assert not client.calls


def test_join_rejects_inconsistent_physical_key_types_before_querying() -> None:
    client = ScriptedQueryClient([])

    with pytest.raises(RuleExecutionError, match="incompatible"):
        ClickHousePreviewBackend(client).execute(
            _join_node(),
            _prepared(order_clickhouse_type="Nullable(Int64)"),
        )
    assert not client.calls


def test_aggregate_compiles_all_supported_metrics_and_typed_output() -> None:
    metric_names = (
        "row_count",
        "distinct_orders",
        "total_value",
        "average_value",
        "minimum_value",
        "maximum_value",
        "median_value",
    )
    client = ScriptedQueryClient(
        [
            _scalar(7),
            _scalar(2),
            _aggregate_rows(
                (STATUS_COLUMN_ID,),
                len(metric_names),
                ("delivered", 4, 3, 10.0, 2.5, 1, 4, 2.5),
            ),
        ]
    )
    metrics = [
        {"name": "row_count", "op": "COUNT", "field": "*"},
        {
            "name": "distinct_orders",
            "op": "COUNT_DISTINCT",
            "field": {"columnId": ORDER_ID_COLUMN_ID},
        },
    ] + [
        {
            "name": name,
            "op": operation,
            "field": {"columnId": ORDER_ID_COLUMN_ID},
        }
        for name, operation in (
            ("total_value", "SUM"),
            ("average_value", "AVG"),
            ("minimum_value", "MIN"),
            ("maximum_value", "MAX"),
            ("median_value", "MEDIAN"),
        )
    ]

    outcome = ClickHousePreviewBackend(client).execute(
        _aggregate_node(metrics),
        _prepared(
            order_logical_type="INTEGER",
            order_clickhouse_type="Nullable(Int64)",
        ),
    )

    assert outcome.status == "SUCCEEDED"
    assert [column["name"] for column in outcome.metrics["columns"]] == [
        "status",
        *metric_names,
    ]
    logical_types = {
        column["name"]: column["logicalType"]
        for column in outcome.metrics["columns"]
    }
    assert logical_types == {
        "status": "STRING",
        "row_count": "INTEGER",
        "distinct_orders": "INTEGER",
        "total_value": "INTEGER",
        "average_value": "FLOAT",
        "minimum_value": "INTEGER",
        "maximum_value": "INTEGER",
        "median_value": "FLOAT",
    }
    aggregate_sql = client.calls[1][0]
    assert "GROUP BY" in aggregate_sql
    assert "uniqExactIf" in aggregate_sql
    assert "sum(`col_33333333_3333_3333_3333_333333333333`)" in aggregate_sql
    assert "avg(`col_33333333_3333_3333_3333_333333333333`)" in aggregate_sql
    assert "quantileExact(0.5)(`col_33333333_3333_3333_3333_333333333333`)" in aggregate_sql


def test_aggregate_having_is_parameterized_and_metric_names_never_enter_sql() -> None:
    display_name = "count`) FROM secret --"
    client = ScriptedQueryClient(
        [
            _scalar(4),
            _scalar(1),
            _aggregate_rows((), 1, (4,)),
        ]
    )
    node = _aggregate_node(
        [{"name": display_name, "op": "COUNT", "field": "*"}],
        group_by=(),
        having={
            "op": "GTE",
            "field": {"name": display_name},
            "value": 2,
        },
    )

    outcome = ClickHousePreviewBackend(client).execute(node, _prepared())

    assert outcome.status == "SUCCEEDED"
    all_sql = "\n".join(sql for sql, _, _ in client.calls)
    assert display_name not in all_sql
    assert "HAVING" in client.calls[1][0]
    assert any(2 in parameters.values() for _, parameters, _ in client.calls)
    assert outcome.metrics["sample"][0][display_name] == 4


def test_aggregate_output_can_be_filtered_downstream() -> None:
    client = ScriptedQueryClient(
        [
            _scalar(5),
            _scalar(2),
            _aggregate_rows((STATUS_COLUMN_ID,), 1, ("delivered", 3)),
            _scalar(2),
            _scalar(1),
            _aggregate_rows((STATUS_COLUMN_ID,), 1, ("delivered", 3)),
        ]
    )
    backend = ClickHousePreviewBackend(client)
    prepared = _prepared()
    backend.execute(
        _aggregate_node(
            [{"name": "row_count", "op": "COUNT", "field": "*"}],
            output_alias="status_counts",
        ),
        prepared,
    )

    outcome = backend.execute(
        _node(
            "filter_aggregate",
            "FILTER",
            {
                "predicate": {
                    "op": "GT",
                    "field": {"name": "row_count"},
                    "value": 1,
                }
            },
            input_node=("aggregate", "status_counts"),
            output_alias="large_groups",
        ),
        prepared,
    )

    assert outcome.status == "SUCCEEDED"
    assert "aggregate_input" in client.calls[3][0]
    assert any(1 in parameters.values() for _, parameters, _ in client.calls[3:])


def test_aggregate_rejects_numeric_operation_on_string_before_querying() -> None:
    client = ScriptedQueryClient([])

    with pytest.raises(RuleExecutionError, match="requires a numeric field"):
        ClickHousePreviewBackend(client).execute(
            _aggregate_node(
                [
                    {
                        "name": "invalid_sum",
                        "op": "SUM",
                        "field": {"columnId": STATUS_COLUMN_ID},
                    }
                ]
            ),
            _prepared(),
        )
    assert not client.calls


def test_aggregate_rejects_string_backed_decimal_sum_before_querying() -> None:
    client = ScriptedQueryClient([])
    prepared = _prepared(
        order_logical_type="DECIMAL",
        order_clickhouse_type="Nullable(String)",
    )

    with pytest.raises(RuleExecutionError, match="ClickHouse Decimal"):
        ClickHousePreviewBackend(client).execute(
            _aggregate_node(
                [
                    {
                        "name": "total_value",
                        "op": "SUM",
                        "field": {"columnId": ORDER_ID_COLUMN_ID},
                    }
                ]
            ),
            prepared,
        )
    assert not client.calls


def test_aggregate_having_cannot_reference_group_fields() -> None:
    client = ScriptedQueryClient([])

    with pytest.raises(RuleExecutionError, match="does not resolve"):
        ClickHousePreviewBackend(client).execute(
            _aggregate_node(
                [{"name": "row_count", "op": "COUNT", "field": "*"}],
                having={
                    "op": "EQ",
                    "field": {"columnId": STATUS_COLUMN_ID},
                    "value": "delivered",
                },
            ),
            _prepared(),
        )
    assert not client.calls


def test_aggregate_rejects_nonempty_metric_params_before_querying() -> None:
    client = ScriptedQueryClient([])

    with pytest.raises(
        ExecutionContractUnavailableError,
        match="metric params are not implemented",
    ):
        ClickHousePreviewBackend(client).execute(
            _aggregate_node(
                [
                    {
                        "name": "custom_median",
                        "op": "MEDIAN",
                        "field": {"columnId": ORDER_ID_COLUMN_ID},
                        "params": {"quantile": 0.9},
                    }
                ]
            ),
            _prepared(
                order_logical_type="INTEGER",
                order_clickhouse_type="Nullable(Int64)",
            ),
        )
    assert not client.calls


@pytest.mark.parametrize(
    "metrics, expected_message",
    [
        (
            [
                {"name": "row_count", "op": "COUNT", "field": "*"},
                {"name": "row_count", "op": "COUNT", "field": "*"},
            ],
            "duplicated or conflicts",
        ),
        (
            [{"name": "status", "op": "COUNT", "field": "*"}],
            "duplicated or conflicts",
        ),
    ],
)
def test_aggregate_rejects_duplicate_or_group_field_metric_names(
    metrics: list[Mapping[str, Any]],
    expected_message: str,
) -> None:
    client = ScriptedQueryClient([])

    with pytest.raises(RuleExecutionError, match=expected_message):
        ClickHousePreviewBackend(client).execute(
            _aggregate_node(metrics),
            _prepared(),
        )
    assert not client.calls


@pytest.mark.parametrize(
    "limit_kind, expected_message",
    [
        (
            "groupBy",
            "groupBy cannot contain more than 100 fields",
        ),
        (
            "metrics",
            "cannot contain more than 100 metrics",
        ),
    ],
)
def test_aggregate_enforces_group_and_metric_limits(
    limit_kind: str,
    expected_message: str,
) -> None:
    client = ScriptedQueryClient([])
    metrics = [{"name": "row_count", "op": "COUNT", "field": "*"}]
    group_by: tuple[Any, ...] = ({"columnId": STATUS_COLUMN_ID},)
    if limit_kind == "groupBy":
        group_by = tuple({"columnId": STATUS_COLUMN_ID} for _ in range(101))
    else:
        metrics = [
            {
                "name": "metric_{0}".format(index),
                "op": "COUNT",
                "field": "*",
            }
            for index in range(101)
        ]
    node = _aggregate_node(metrics, group_by=group_by)

    with pytest.raises(RuleExecutionError, match=expected_message):
        ClickHousePreviewBackend(client).execute(node, _prepared())
    assert not client.calls


def test_derive_date_diff_parses_string_backed_dates_and_emits_typed_output() -> None:
    client = ScriptedQueryClient(
        [
            _scalar(2),
            _scalar(2),
            _derived_rows(
                (
                    ORDER_ID_COLUMN_ID,
                    STATUS_COLUMN_ID,
                    ACTIVE_COLUMN_ID,
                    DELIVERED_AT_COLUMN_ID,
                    ESTIMATED_AT_COLUMN_ID,
                ),
                "derive_delay",
                (
                    "o1",
                    "delivered",
                    1,
                    "2026-01-04 10:00:00",
                    "2026-01-02 10:00:00",
                    "2.000000000000",
                ),
            ),
        ]
    )
    prepared = _prepared(
        extra_order_columns=(
            _snapshot_column(
                DELIVERED_AT_COLUMN_ID,
                "delivered_at",
                "DATETIME",
                "Nullable(String)",
            ),
            _snapshot_column(
                ESTIMATED_AT_COLUMN_ID,
                "estimated_at",
                "DATETIME",
                "Nullable(String)",
            ),
        )
    )
    node = _node(
        "derive_delay",
        "DERIVE",
        {
            "outputField": "delay_days",
            "dataType": "DECIMAL",
            "expression": {
                "op": "DATE_DIFF_DAYS",
                "left": {"type": "FIELD", "columnId": DELIVERED_AT_COLUMN_ID},
                "right": {"type": "FIELD", "columnId": ESTIMATED_AT_COLUMN_ID},
            },
        },
        output_alias="orders_with_delay",
    )

    outcome = ClickHousePreviewBackend(client).execute(node, prepared)

    assert outcome.status == "SUCCEEDED"
    assert outcome.metrics["columns"][-1] == {
        "columnId": "derive_delay:derived",
        "fieldId": None,
        "name": "delay_days",
        "logicalType": "DECIMAL",
    }
    derive_sql = client.calls[1][0]
    assert "dateDiff('day'" in derive_sql
    assert derive_sql.count("parseDateTime64BestEffortOrNull") >= 2
    assert "toDecimal128OrNull" in derive_sql


def test_derive_arithmetic_output_can_be_filtered_downstream() -> None:
    client = ScriptedQueryClient(
        [
            _scalar(4),
            _scalar(4),
            _derived_rows(
                (ORDER_ID_COLUMN_ID, STATUS_COLUMN_ID, ACTIVE_COLUMN_ID),
                "derive_half",
                (4, "delivered", 1, "2.000000000000"),
            ),
            _scalar(4),
            _scalar(2),
            _derived_rows(
                (ORDER_ID_COLUMN_ID, STATUS_COLUMN_ID, ACTIVE_COLUMN_ID),
                "derive_half",
                (4, "delivered", 1, "2.000000000000"),
            ),
        ]
    )
    backend = ClickHousePreviewBackend(client)
    prepared = _prepared(
        order_logical_type="INTEGER",
        order_clickhouse_type="Nullable(Int64)",
    )
    backend.execute(
        _node(
            "derive_half",
            "DERIVE",
            {
                "outputField": "half_order",
                "dataType": "DECIMAL",
                "expression": {
                    "op": "ARITHMETIC",
                    "operator": "DIVIDE",
                    "left": {"type": "FIELD", "columnId": ORDER_ID_COLUMN_ID},
                    "right": {"type": "LITERAL", "value": 2},
                },
            },
            output_alias="orders_with_half",
        ),
        prepared,
    )

    outcome = backend.execute(
        _node(
            "filter_half",
            "FILTER",
            {
                "predicate": {
                    "op": "GT",
                    "field": {"name": "half_order"},
                    "value": 1,
                }
            },
            input_node=("derive_half", "orders_with_half"),
            output_alias="large_half",
        ),
        prepared,
    )

    assert outcome.status == "SUCCEEDED"
    assert "divide(" in client.calls[1][0]
    assert "nullIf(" in client.calls[1][0]
    assert "derive_input" in client.calls[4][0]
    assert any(2 in parameters.values() for _, parameters, _ in client.calls)


def test_derive_case_concat_and_coalesce_keep_literals_parameterized() -> None:
    injected = "delivered') OR 1 = 1 --"
    client = ScriptedQueryClient(
        [
            _scalar(1),
            _scalar(1),
            _derived_rows(
                (ORDER_ID_COLUMN_ID, STATUS_COLUMN_ID, ACTIVE_COLUMN_ID),
                "derive_segment",
                ("o1", injected, 1, "vip-delivered"),
            ),
        ]
    )
    expression = {
        "op": "CASE_WHEN",
        "branches": [
            {
                "when": {
                    "op": "EQ",
                    "field": {"columnId": STATUS_COLUMN_ID},
                    "value": injected,
                },
                "then": {
                    "op": "CONCAT",
                    "args": [
                        {"type": "LITERAL", "value": "vip-"},
                        {"type": "FIELD", "columnId": STATUS_COLUMN_ID},
                    ],
                },
            }
        ],
        "else": {
            "op": "COALESCE",
            "args": [
                {"type": "FIELD", "columnId": STATUS_COLUMN_ID},
                {"type": "LITERAL", "value": "unknown"},
            ],
        },
    }

    outcome = ClickHousePreviewBackend(client).execute(
        _node(
            "derive_segment",
            "DERIVE",
            {
                "outputField": "segment",
                "dataType": "STRING",
                "expression": expression,
            },
            output_alias="segmented",
        ),
        _prepared(),
    )

    assert outcome.status == "SUCCEEDED"
    all_sql = "\n".join(sql for sql, _, _ in client.calls)
    assert "multiIf(" in all_sql
    assert "concat(" in all_sql
    assert "coalesce(" in all_sql
    assert injected not in all_sql
    assert any(injected in parameters.values() for _, parameters, _ in client.calls)


@pytest.mark.parametrize(
    "expression, data_type, expected_sql",
    [
        (
            {
                "op": "DATE_PART",
                "part": "MONTH",
                "value": {"type": "LITERAL", "value": "2026-11-01"},
            },
            "INTEGER",
            "toMonth(",
        ),
        (
            {
                "op": "DISTANCE_HAVERSINE",
                "longitude1": {"type": "LITERAL", "value": 116.4},
                "latitude1": {"type": "LITERAL", "value": 39.9},
                "longitude2": {"type": "LITERAL", "value": 121.5},
                "latitude2": {"type": "LITERAL", "value": 31.2},
            },
            "DECIMAL",
            "greatCircleDistance(",
        ),
    ],
)
def test_derive_date_part_and_distance_use_whitelisted_functions(
    expression: Mapping[str, Any],
    data_type: str,
    expected_sql: str,
) -> None:
    client = ScriptedQueryClient(
        [
            _scalar(1),
            _scalar(1),
            _derived_rows(
                (ORDER_ID_COLUMN_ID, STATUS_COLUMN_ID, ACTIVE_COLUMN_ID),
                "derive_value",
                ("o1", "delivered", 1, 11),
            ),
        ]
    )

    outcome = ClickHousePreviewBackend(client).execute(
        _node(
            "derive_value",
            "DERIVE",
            {
                "outputField": "derived_value",
                "dataType": data_type,
                "expression": expression,
            },
            output_alias="derived",
        ),
        _prepared(),
    )

    assert outcome.status == "SUCCEEDED"
    assert expected_sql in client.calls[1][0]


def test_derive_replaces_existing_field_only_when_explicit() -> None:
    client = ScriptedQueryClient([])
    config = {
        "outputField": "status",
        "dataType": "STRING",
        "expression": {"type": "LITERAL", "value": "normalized"},
    }

    with pytest.raises(RuleExecutionError, match="replaceExisting=true"):
        ClickHousePreviewBackend(client).execute(
            _node("derive_status", "DERIVE", config),
            _prepared(),
        )
    assert not client.calls

    successful_client = ScriptedQueryClient(
        [
            _scalar(1),
            _scalar(1),
            QueryResult(
                (
                    "col_" + ORDER_ID_COLUMN_ID.replace("-", "_"),
                    "node_derive_status_derived",
                    "col_" + ACTIVE_COLUMN_ID.replace("-", "_"),
                ),
                (("o1", "normalized", 1),),
            ),
        ]
    )
    outcome = ClickHousePreviewBackend(successful_client).execute(
        _node(
            "derive_status",
            "DERIVE",
            {**config, "replaceExisting": True},
            output_alias="normalized_orders",
        ),
        _prepared(),
    )

    assert outcome.status == "SUCCEEDED"
    assert [column["name"] for column in outcome.metrics["columns"]] == [
        "order_id",
        "status",
        "active",
    ]
    assert outcome.metrics["columns"][1]["columnId"] == "derive_status:derived"


def test_derive_string_backed_boolean_uses_strict_token_parsing() -> None:
    client = ScriptedQueryClient(
        [
            _scalar(1),
            _scalar(1),
            _derived_rows(
                (ORDER_ID_COLUMN_ID, STATUS_COLUMN_ID, ACTIVE_COLUMN_ID),
                "derive_flag",
                ("yes", "delivered", 1, 1),
            ),
        ]
    )

    outcome = ClickHousePreviewBackend(client).execute(
        _node(
            "derive_flag",
            "DERIVE",
            {
                "outputField": "normalized_flag",
                "dataType": "BOOLEAN",
                "expression": {"type": "FIELD", "columnId": ORDER_ID_COLUMN_ID},
            },
            output_alias="normalized_flags",
        ),
        _prepared(
            order_logical_type="BOOLEAN",
            order_clickhouse_type="Nullable(String)",
        ),
    )

    assert outcome.status == "SUCCEEDED"
    derive_sql = client.calls[1][0]
    assert "lowerUTF8(trimBoth" in derive_sql
    assert "('true', '1', 'yes')" in derive_sql
    assert "('false', '0', 'no')" in derive_sql


def test_derive_rejects_unknown_incompatible_and_overdeep_expressions() -> None:
    client = ScriptedQueryClient([])
    backend = ClickHousePreviewBackend(client)

    with pytest.raises(RuleExecutionError, match="unsupported DERIVE expression"):
        backend.execute(
            _node(
                "derive_unknown",
                "DERIVE",
                {
                    "outputField": "unsafe",
                    "dataType": "STRING",
                    "expression": {"op": "SQL", "value": "now()"},
                },
            ),
            _prepared(),
        )
    with pytest.raises(RuleExecutionError, match="incompatible"):
        backend.execute(
            _node(
                "derive_invalid_type",
                "DERIVE",
                {
                    "outputField": "invalid_flag",
                    "dataType": "BOOLEAN",
                    "expression": {"type": "LITERAL", "value": "yes"},
                },
            ),
            _prepared(),
        )

    expression: Mapping[str, Any] = {"type": "LITERAL", "value": "end"}
    for _ in range(22):
        expression = {
            "op": "COALESCE",
            "args": [expression, {"type": "LITERAL", "value": "fallback"}],
        }
    with pytest.raises(RuleExecutionError, match="depth cannot exceed 20"):
        backend.execute(
            _node(
                "derive_deep",
                "DERIVE",
                {
                    "outputField": "too_deep",
                    "dataType": "STRING",
                    "expression": expression,
                },
            ),
            _prepared(),
        )

    predicate: Mapping[str, Any] = {
        "op": "IS_NULL",
        "field": {"columnId": STATUS_COLUMN_ID},
    }
    for _ in range(26):
        predicate = {"op": "NOT", "predicate": predicate}
    with pytest.raises(RuleExecutionError, match="predicate nesting cannot exceed 24"):
        backend.execute(
            _node(
                "derive_deep_case",
                "DERIVE",
                {
                    "outputField": "deep_case",
                    "dataType": "STRING",
                    "expression": {
                        "op": "CASE_WHEN",
                        "branches": [
                            {
                                "when": predicate,
                                "then": {"type": "LITERAL", "value": "yes"},
                            }
                        ],
                        "else": {"type": "LITERAL", "value": "no"},
                    },
                },
            ),
            _prepared(),
        )
    assert not client.calls


@pytest.mark.parametrize("value", [-1, 1.5, float("nan")])
def test_invalid_scalar_counts_fail_closed(value: Any) -> None:
    client = ScriptedQueryClient([_scalar(value)])

    with pytest.raises(RuleExecutionError, match="invalid count"):
        ClickHousePreviewBackend(client).execute(
            _node(
                "filter",
                "FILTER",
                {
                    "predicate": {
                        "op": "IS_NULL",
                        "field": {"columnId": STATUS_COLUMN_ID},
                    }
                },
            ),
            _prepared(),
        )


@pytest.mark.parametrize("value", [float("nan"), float("inf"), float("-inf")])
def test_non_finite_comparison_values_fail_before_querying(value: float) -> None:
    client = ScriptedQueryClient([])

    with pytest.raises(RuleExecutionError, match="finite"):
        ClickHousePreviewBackend(client).execute(
            _node(
                "filter",
                "FILTER",
                {
                    "predicate": {
                        "op": "EQ",
                        "field": {"columnId": ORDER_ID_COLUMN_ID},
                        "value": value,
                    }
                },
            ),
            _prepared(
                order_logical_type="FLOAT",
                order_clickhouse_type="Nullable(Float64)",
            ),
        )
    assert not client.calls


def test_fractional_string_is_not_truncated_for_integer_comparison() -> None:
    client = ScriptedQueryClient([])

    with pytest.raises(RuleExecutionError, match="must be an integer"):
        ClickHousePreviewBackend(client).execute(
            _node(
                "filter",
                "FILTER",
                {
                    "predicate": {
                        "op": "EQ",
                        "field": {"columnId": ORDER_ID_COLUMN_ID},
                        "value": "1.5",
                    }
                },
            ),
            _prepared(
                order_logical_type="INTEGER",
                order_clickhouse_type="Nullable(Int64)",
            ),
        )
    assert not client.calls


@pytest.mark.parametrize(
    "sample_result, message",
    [
        (QueryResult(("wrong_column",), (("o1",),)), "unexpected columns"),
        (
            QueryResult(
                tuple(
                    "col_" + column_id.replace("-", "_")
                    for column_id in (
                        ORDER_ID_COLUMN_ID,
                        STATUS_COLUMN_ID,
                        ACTIVE_COLUMN_ID,
                    )
                ),
                (("o1", "delivered"),),
            ),
            "invalid row shape",
        ),
    ],
)
def test_sample_result_shape_must_match_compiled_relation(
    sample_result: QueryResult,
    message: str,
) -> None:
    client = ScriptedQueryClient([_scalar(2), _scalar(1), sample_result])

    with pytest.raises(RuleExecutionError, match=message):
        ClickHousePreviewBackend(client).execute(
            _node(
                "filter",
                "FILTER",
                {
                    "predicate": {
                        "op": "IS_NULL",
                        "field": {"columnId": STATUS_COLUMN_ID},
                    }
                },
            ),
            _prepared(),
        )


def _prepared(
    *,
    order_logical_type: str = "STRING",
    order_clickhouse_type: str = "Nullable(String)",
    customer_logical_type: str = "STRING",
    customer_clickhouse_type: str = "Nullable(String)",
    extra_order_columns: Sequence[Mapping[str, Any]] = (),
) -> PreparedExecution:
    execution = parse_execution(make_execution_payload(nodes=[], edges=[]))
    snapshot_payload = make_snapshot_payload()
    columns = snapshot_payload["tables"][0]["columns"]
    columns[0]["logicalType"] = order_logical_type
    columns[0]["clickhouseType"] = order_clickhouse_type
    customer_column = snapshot_payload["tables"][1]["columns"][0]
    customer_column["logicalType"] = customer_logical_type
    customer_column["clickhouseType"] = customer_clickhouse_type
    columns.extend(
        [
            {
                "columnId": STATUS_COLUMN_ID,
                "fieldId": "99999999-9999-9999-9999-999999999999",
                "sourceName": "status",
                "physicalName": "c002",
                "logicalType": "STRING",
                "clickhouseType": "Nullable(String)",
                "nullable": True,
            },
            {
                "columnId": ACTIVE_COLUMN_ID,
                "fieldId": None,
                "sourceName": "active",
                "physicalName": "c003",
                "logicalType": "BOOLEAN",
                "clickhouseType": "Nullable(UInt8)",
                "nullable": True,
            },
        ]
    )
    columns.extend(dict(column) for column in extra_order_columns)
    snapshot = ExecutionSnapshot.from_payload(
        snapshot_payload,
        expected_dataset_version_id=DATASET_VERSION_ID,
    )
    return PreparedExecution(execution=execution, snapshot=snapshot)


def _node(
    node_id: str,
    kind: str,
    config: Mapping[str, Any],
    *,
    input_node: Optional[tuple[str, str]] = None,
    output_alias: Optional[str] = None,
) -> RuleNode:
    if input_node is None:
        node_input = {
            "type": "TABLE",
            "datasetVersionId": DATASET_VERSION_ID,
            "tableId": ORDERS_TABLE_ID,
            "alias": "orders",
        }
    else:
        upstream_id, upstream_alias = input_node
        node_input = {
            "type": "NODE_OUTPUT",
            "nodeId": upstream_id,
            "outputAlias": upstream_alias,
            "alias": upstream_alias,
        }
    return RuleNode(
        node_id=node_id,
        kind=kind,
        name=node_id,
        stage="CLEAN",
        enabled=True,
        inputs=(node_input,),
        config=dict(config),
        depends_on=(),
        output_alias=output_alias,
        raw={},
    )


def _join_node(
    *,
    cardinality: str = "N:1",
    duplicate_policy: str = "FAIL",
    guards: Optional[Mapping[str, Any]] = None,
) -> RuleNode:
    return RuleNode(
        node_id="join",
        kind="JOIN",
        name="join",
        stage="RELATE",
        enabled=True,
        inputs=(
            {
                "type": "TABLE",
                "datasetVersionId": DATASET_VERSION_ID,
                "tableId": ORDERS_TABLE_ID,
                "alias": "orders",
            },
            {
                "type": "TABLE",
                "datasetVersionId": DATASET_VERSION_ID,
                "tableId": CUSTOMERS_TABLE_ID,
                "alias": "customers",
            },
        ),
        config={
            "leftAlias": "orders",
            "rightAlias": "customers",
            "leftKeys": [{"columnId": ORDER_ID_COLUMN_ID}],
            "rightKeys": [{"columnId": CUSTOMER_ID_COLUMN_ID}],
            "joinType": "LEFT",
            "cardinality": cardinality,
            "duplicatePolicy": duplicate_policy,
            "guards": dict(guards or {}),
        },
        depends_on=(),
        output_alias="joined",
        raw={},
    )


def _aggregate_node(
    metrics: list[Mapping[str, Any]],
    *,
    group_by: tuple[Any, ...] = ({"columnId": STATUS_COLUMN_ID},),
    having: Optional[Mapping[str, Any]] = None,
    output_alias: str = "aggregated",
) -> RuleNode:
    config: dict[str, Any] = {
        "groupBy": list(group_by),
        "metrics": [dict(metric) for metric in metrics],
    }
    if having is not None:
        config["having"] = dict(having)
    return _node(
        "aggregate",
        "AGGREGATE",
        config,
        output_alias=output_alias,
    )


def _scalar(value: Any) -> QueryResult:
    return QueryResult(("row_count",), ((value,),))


def _rows(column_ids: tuple[str, ...], row: tuple[Any, ...]) -> QueryResult:
    names = tuple("col_" + column_id.replace("-", "_") for column_id in column_ids)
    return QueryResult(names, (row,))


def _join_diagnostics(
    left_rows: Any,
    right_rows: Any,
    matched_left_rows: Any,
    left_duplicate_key_groups: Any,
    right_duplicate_key_groups: Any,
    max_right_key_multiplicity: Any,
) -> QueryResult:
    return QueryResult(
        (
            "left_rows",
            "right_rows",
            "matched_left_rows",
            "left_duplicate_key_groups",
            "right_duplicate_key_groups",
            "max_right_key_multiplicity",
        ),
        (
            (
                left_rows,
                right_rows,
                matched_left_rows,
                left_duplicate_key_groups,
                right_duplicate_key_groups,
                max_right_key_multiplicity,
            ),
        ),
    )


def _aggregate_rows(
    group_column_ids: tuple[str, ...],
    metric_count: int,
    row: tuple[Any, ...],
) -> QueryResult:
    group_names = tuple(
        "col_" + column_id.replace("-", "_") for column_id in group_column_ids
    )
    metric_names = tuple(
        "node_aggregate_metric_{0}".format(index) for index in range(metric_count)
    )
    return QueryResult(group_names + metric_names, (row,))


def _derived_rows(
    source_column_ids: tuple[str, ...],
    node_id: str,
    row: tuple[Any, ...],
) -> QueryResult:
    source_names = tuple(
        "col_" + column_id.replace("-", "_") for column_id in source_column_ids
    )
    derived_name = "node_{0}_derived".format(node_id.replace("-", "_"))
    return QueryResult(source_names + (derived_name,), (row,))


def _snapshot_column(
    column_id: str,
    source_name: str,
    logical_type: str,
    clickhouse_type: str,
) -> Mapping[str, Any]:
    return {
        "columnId": column_id,
        "fieldId": None,
        "sourceName": source_name,
        "physicalName": "c_" + column_id.replace("-", "_")[:12],
        "logicalType": logical_type,
        "clickhouseType": clickhouse_type,
        "nullable": True,
    }
