"""Shared fixtures and fakes for rule-execution worker tests."""

from __future__ import annotations

from typing import Any, Dict, List, Mapping, Optional, Sequence, Tuple

from app.workers.rule_execution.errors import RuleRunApiError
from app.workers.rule_execution.models import NodeOutcome, RunExecution
from app.workers.rule_execution.plan import RuleNode
from app.workers.rule_execution.ports import PreparedExecution
from app.workers.rule_execution.snapshot import ExecutionSnapshot

RUN_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
WORKBOOK_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
VERSION_ID = "cccccccc-cccc-cccc-cccc-cccccccccccc"
DATASET_VERSION_ID = "dddddddd-dddd-dddd-dddd-dddddddddddd"
ORDERS_TABLE_ID = "11111111-1111-1111-1111-111111111111"
CUSTOMERS_TABLE_ID = "22222222-2222-2222-2222-222222222222"


def make_check_node(enabled: bool = True, depends_on: Optional[Sequence[str]] = None) -> Dict[str, Any]:
    return {
        "id": "field_checks:check",
        "kind": "CHECK",
        "name": "order id must exist",
        "stage": "CHECK",
        "enabled": enabled,
        "inputs": [
            {
                "type": "TABLE",
                "datasetVersionId": DATASET_VERSION_ID,
                "tableId": ORDERS_TABLE_ID,
                "alias": "orders",
            }
        ],
        "config": {
            "schemaVersion": 1,
            "scope": "ROW",
            "predicate": {"op": "IS_NULL", "args": []},
            "effect": "QUARANTINE_ROWS",
            "outputAlias": "orders_checked",
        },
        "dependsOn": list(depends_on or ()),
        "output": {"alias": "orders_checked"},
        "policy": {"blocking": "NONE", "onError": "FAIL_RUN"},
    }


def make_join_node() -> Dict[str, Any]:
    return {
        "id": "relations:join",
        "kind": "JOIN",
        "name": "join customers",
        "stage": "RELATE",
        "enabled": True,
        "inputs": [
            {
                "type": "NODE_OUTPUT",
                "nodeId": "field_checks:check",
                "outputAlias": "orders_checked",
                "alias": "orders_checked",
            },
            {
                "type": "TABLE",
                "datasetVersionId": DATASET_VERSION_ID,
                "tableId": CUSTOMERS_TABLE_ID,
                "alias": "customers",
            },
        ],
        "config": {
            "schemaVersion": 1,
            "leftAlias": "orders_checked",
            "rightAlias": "customers",
            "leftKeys": ["customer_id"],
            "rightKeys": ["customer_id"],
            "joinType": "LEFT",
            "cardinality": "N:1",
            "outputAlias": "orders_with_customers",
        },
        "dependsOn": [],
        "output": {"alias": "orders_with_customers"},
        "policy": {"blocking": "NONE", "onError": "FAIL_RUN"},
    }


def make_gate_node() -> Dict[str, Any]:
    return {
        "id": "gates:gate",
        "kind": "GATE",
        "name": "join match rate",
        "stage": "VALIDATE",
        "enabled": True,
        "inputs": [],
        "config": {
            "schemaVersion": 1,
            "metricRef": "relations:join.match_rate",
            "comparator": "GTE",
            "threshold": 0.95,
            "effect": "BLOCK_PUBLISH",
        },
        "dependsOn": [],
        "output": None,
        "policy": {"blocking": "RUN", "onError": "FAIL_RUN"},
    }


def make_output_node() -> Dict[str, Any]:
    return {
        "id": "outputs:output",
        "kind": "OUTPUT",
        "name": "training data",
        "stage": "OUTPUT",
        "enabled": True,
        "inputs": [
            {
                "type": "NODE_OUTPUT",
                "nodeId": "relations:join",
                "outputAlias": "orders_with_customers",
                "alias": "orders_with_customers",
            }
        ],
        "config": {
            "schemaVersion": 1,
            "mode": "MATERIALIZE_VERSION",
            "format": "PARQUET",
            "name": "order_training",
        },
        "dependsOn": [],
        "output": None,
        "policy": {"blocking": "RUN", "onError": "FAIL_RUN"},
    }


def make_nodes_and_edges() -> Tuple[List[Dict[str, Any]], List[Dict[str, str]]]:
    return (
        [make_check_node(), make_join_node(), make_gate_node(), make_output_node()],
        [
            {"from": "field_checks:check", "to": "relations:join"},
            {"from": "relations:join", "to": "gates:gate"},
            {"from": "relations:join", "to": "outputs:output"},
        ],
    )


def make_execution_payload(
    nodes: Optional[Sequence[Mapping[str, Any]]] = None,
    edges: Optional[Sequence[Mapping[str, str]]] = None,
    status: str = "QUEUED",
    run_dataset_version_id: str = DATASET_VERSION_ID,
    plan_dataset_version_id: Optional[str] = None,
    node_count: Optional[int] = None,
) -> Dict[str, Any]:
    if nodes is None or edges is None:
        default_nodes, default_edges = make_nodes_and_edges()
        nodes = default_nodes if nodes is None else nodes
        edges = default_edges if edges is None else edges
    plan_dataset = plan_dataset_version_id or run_dataset_version_id
    return {
        "id": RUN_ID,
        "workbookId": WORKBOOK_ID,
        "workbookVersionId": VERSION_ID,
        "datasetVersionId": run_dataset_version_id,
        "workbookVersionContentHash": "sha256:workbook-v1",
        "mode": "PREVIEW",
        "status": status,
        "sampleSize": 100,
        "seed": 42,
        "compiledPlan": {
            "schemaVersion": 1,
            "compiler": "rule-workbook-compiler/v1",
            "workbookId": WORKBOOK_ID,
            "datasetVersionId": plan_dataset,
            "nodes": list(nodes),
            "edges": list(edges),
            "nodeCount": len(nodes) if node_count is None else node_count,
        },
    }


def parse_execution(payload: Mapping[str, Any]) -> RunExecution:
    return RunExecution.from_api_payload(payload)


def make_snapshot_payload(
    dataset_version_id: str = DATASET_VERSION_ID,
    status: str = "READY",
) -> Dict[str, Any]:
    return {
        "datasetVersionId": dataset_version_id,
        "status": status,
        "manifestHash": "sha256:manifest",
        "schemaHash": "sha256:schema",
        "clickhouseDatabase": "olist_datasets",
        "rowCount": 2,
        "tables": [
            {
                "tableId": ORDERS_TABLE_ID,
                "tableKey": "orders",
                "physicalTable": "t_orders",
                "rowCount": 2,
                "columns": [
                    {
                        "columnId": "33333333-3333-3333-3333-333333333333",
                        "fieldId": "44444444-4444-4444-4444-444444444444",
                        "sourceName": "order_id",
                        "physicalName": "c001",
                        "logicalType": "STRING",
                        "clickhouseType": "Nullable(String)",
                        "nullable": True,
                    }
                ],
            },
            {
                "tableId": CUSTOMERS_TABLE_ID,
                "tableKey": "customers",
                "physicalTable": "t_customers",
                "rowCount": 1,
                "columns": [
                    {
                        "columnId": "55555555-5555-5555-5555-555555555555",
                        "fieldId": "66666666-6666-6666-6666-666666666666",
                        "sourceName": "customer_id",
                        "physicalName": "c001",
                        "logicalType": "STRING",
                        "clickhouseType": "Nullable(String)",
                        "nullable": True,
                    }
                ],
            },
        ],
    }


def make_snapshot(
    dataset_version_id: str = DATASET_VERSION_ID,
    status: str = "READY",
) -> ExecutionSnapshot:
    return ExecutionSnapshot.from_payload(
        make_snapshot_payload(dataset_version_id=dataset_version_id, status=status),
        expected_dataset_version_id=dataset_version_id,
    )


class FakeLoader:
    def __init__(self, payload: Mapping[str, Any]) -> None:
        self.payload = payload
        self.calls: List[str] = []

    def get_execution(self, run_id: str) -> Mapping[str, Any]:
        self.calls.append(run_id)
        return self.payload


class FakePreflight:
    def __init__(self) -> None:
        self.calls: List[RunExecution] = []

    def prepare(self, execution: RunExecution) -> PreparedExecution:
        self.calls.append(execution)
        return PreparedExecution(
            execution=execution,
            snapshot=make_snapshot(execution.dataset_version_id),
        )


class ScriptedBackend:
    def __init__(self, actions: Optional[Mapping[str, Any]] = None) -> None:
        self.actions = dict(actions or {})
        self.calls: List[str] = []

    def execute(self, node: RuleNode, prepared: PreparedExecution) -> NodeOutcome:
        self.calls.append(node.node_id)
        action = self.actions.get(node.node_id, NodeOutcome.succeeded())
        if isinstance(action, Exception):
            raise action
        return action


class FakeProgressSink:
    def __init__(self, enabled_by_node_id: Mapping[str, bool]) -> None:
        self.enabled_by_node_id = dict(enabled_by_node_id)
        self.events: List[Tuple[Any, ...]] = []
        self.statuses: Dict[str, str] = {}
        self.started = False
        self.finished_status: Optional[str] = None

    def start(self, run_id: str) -> Mapping[str, Any]:
        self.events.append(("start", run_id))
        self.started = True
        return {"status": "RUNNING"}

    def report_node(
        self,
        run_id: str,
        node_id: str,
        node_kind: str,
        status: str,
        metrics: Optional[Mapping[str, Any]] = None,
        sample_uri: Optional[str] = None,
        error_message: Optional[str] = None,
    ) -> Mapping[str, Any]:
        if not self.started:
            raise AssertionError("node reported before start")
        if self.finished_status is not None:
            raise RuleRunApiError("run already finished", status_code=409)
        previous = self.statuses.get(node_id)
        if previous in {"SUCCEEDED", "FAILED", "SKIPPED"}:
            raise RuleRunApiError("node already terminal", status_code=409)
        self.statuses[node_id] = status
        self.events.append(
            ("report", node_id, status, metrics, sample_uri, error_message)
        )
        return {"status": status}

    def finish(
        self,
        run_id: str,
        status: str,
        error_message: Optional[str] = None,
    ) -> Mapping[str, Any]:
        if status == "SUCCEEDED":
            incomplete = [
                node_id
                for node_id, enabled in self.enabled_by_node_id.items()
                if enabled and self.statuses.get(node_id) not in {"SUCCEEDED", "SKIPPED"}
            ]
            if incomplete:
                raise RuleRunApiError(
                    "nodes are not successful: {0}".format(incomplete), status_code=409
                )
        self.events.append(("finish", status, error_message))
        self.finished_status = status
        return {"status": status}
