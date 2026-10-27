"""Validation and deterministic scheduling for frozen rule-workbook plans."""

from __future__ import annotations

import re
from collections import defaultdict
from dataclasses import dataclass
from heapq import heappop, heappush
from typing import Any, Dict, FrozenSet, List, Mapping, Optional, Set, Tuple

from app.workers.rule_execution.errors import PlanValidationError
from app.workers.rule_execution.models import RunExecution

NODE_KINDS: FrozenSet[str] = frozenset(
    {
        "CHECK",
        "TRANSFORM",
        "PROJECT",
        "FILTER",
        "DEDUPE",
        "JOIN",
        "AGGREGATE",
        "DERIVE",
        "GATE",
        "OUTPUT",
    }
)

_IDENTIFIER = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
_OUTPUT_NODE_KIND = "OUTPUT"


@dataclass(frozen=True)
class PlanIssue:
    code: str
    message: str
    node_id: Optional[str] = None


@dataclass(frozen=True)
class RuleNode:
    """A validated node from the immutable compiled plan."""

    node_id: str
    kind: str
    name: str
    stage: str
    enabled: bool
    inputs: Tuple[Mapping[str, Any], ...]
    config: Mapping[str, Any]
    depends_on: Tuple[str, ...]
    output_alias: Optional[str]
    raw: Mapping[str, Any]


@dataclass(frozen=True)
class ExecutionPlan:
    """A plan that passed the worker-side safety checks."""

    execution: RunExecution
    nodes: Mapping[str, RuleNode]
    ordered_node_ids: Tuple[str, ...]
    dependencies: Mapping[str, Tuple[str, ...]]
    edges: FrozenSet[Tuple[str, str]]

    @property
    def ordered_nodes(self) -> Tuple[RuleNode, ...]:
        return tuple(self.nodes[node_id] for node_id in self.ordered_node_ids)

    @property
    def enabled_node_ids(self) -> Tuple[str, ...]:
        return tuple(
            node_id
            for node_id in self.ordered_node_ids
            if self.nodes[node_id].enabled
        )


def validate_execution_plan(execution: RunExecution) -> ExecutionPlan:
    """Validate the frozen plan and return a deterministic topological schedule.

    The dataset service owns compilation. This second validation is deliberately
    independent: a worker must not execute a corrupted, mixed-dataset, cyclic or
    unsupported plan merely because it arrived over an authenticated API.
    """

    issues: List[PlanIssue] = []
    plan = execution.compiled_plan
    if not isinstance(plan, Mapping):
        raise PlanValidationError(
            [PlanIssue("PLAN_INVALID", "compiledPlan must be a JSON object")]
        )

    if plan.get("schemaVersion") != 1:
        issues.append(
            PlanIssue(
                "SCHEMA_VERSION_UNSUPPORTED",
                "worker only accepts schemaVersion=1, got {0}".format(
                    plan.get("schemaVersion")
                ),
            )
        )

    plan_dataset_version_id = _text(plan.get("datasetVersionId"))
    if plan_dataset_version_id is None:
        issues.append(
            PlanIssue("PLAN_DATASET_VERSION_REQUIRED", "compiledPlan is missing datasetVersionId")
        )
    elif plan_dataset_version_id != execution.dataset_version_id:
        issues.append(
            PlanIssue(
                "PLAN_DATASET_VERSION_MISMATCH",
                "compiledPlan datasetVersionId {0} does not match run datasetVersionId {1}".format(
                    plan_dataset_version_id, execution.dataset_version_id
                ),
            )
        )

    raw_nodes = plan.get("nodes")
    if not isinstance(raw_nodes, list):
        issues.append(PlanIssue("PLAN_NODES_INVALID", "compiledPlan.nodes must be an array"))
        raise PlanValidationError(issues)

    nodes: Dict[str, RuleNode] = {}
    node_order: List[str] = []
    alias_to_node_id: Dict[str, str] = {}

    for index, raw_node in enumerate(raw_nodes):
        node = _parse_node(raw_node, index, execution, issues)
        if node is None:
            continue
        if node.node_id in nodes:
            issues.append(
                PlanIssue(
                    "NODE_ID_DUPLICATE",
                    "node id {0} appears more than once".format(node.node_id),
                    node.node_id,
                )
            )
            continue
        nodes[node.node_id] = node
        node_order.append(node.node_id)
        if node.output_alias is not None:
            previous = alias_to_node_id.get(node.output_alias)
            if previous is not None:
                issues.append(
                    PlanIssue(
                        "OUTPUT_ALIAS_DUPLICATE",
                        "output alias {0} is also produced by node {1}".format(
                            node.output_alias, previous
                        ),
                        node.node_id,
                    )
                )
            else:
                alias_to_node_id[node.output_alias] = node.node_id

    declared_edges = _parse_edges(plan.get("edges"), nodes, issues)
    dependencies: Dict[str, Tuple[str, ...]] = {}
    derived_edges: Set[Tuple[str, str]] = set()

    for node in nodes.values():
        node_dependencies: Set[str] = set()

        for upstream_id in node.depends_on:
            upstream = nodes.get(upstream_id)
            if upstream is None:
                issues.append(
                    PlanIssue(
                        "DEPENDENCY_NOT_FOUND",
                        "explicit dependency {0} does not exist".format(upstream_id),
                        node.node_id,
                    )
                )
                continue
            if upstream.kind == _OUTPUT_NODE_KIND:
                issues.append(
                    PlanIssue(
                        "OUTPUT_HAS_DEPENDENTS",
                        "OUTPUT node {0} cannot be a dependency".format(upstream_id),
                        node.node_id,
                    )
                )
                continue
            node_dependencies.add(upstream_id)
            derived_edges.add((upstream_id, node.node_id))

        for node_input in node.inputs:
            if node_input.get("type") != "NODE_OUTPUT":
                continue
            upstream_id = _text(node_input.get("nodeId"))
            expected_alias = _text(node_input.get("outputAlias"))
            upstream = nodes.get(upstream_id) if upstream_id is not None else None
            if upstream is None:
                issues.append(
                    PlanIssue(
                        "UPSTREAM_NODE_NOT_FOUND",
                        "input references missing upstream node {0}".format(upstream_id),
                        node.node_id,
                    )
                )
                continue
            if upstream.kind == _OUTPUT_NODE_KIND:
                issues.append(
                    PlanIssue(
                        "OUTPUT_HAS_DEPENDENTS",
                        "OUTPUT node {0} cannot feed another node".format(upstream.node_id),
                        node.node_id,
                    )
                )
                continue
            if upstream.output_alias != expected_alias:
                issues.append(
                    PlanIssue(
                        "UPSTREAM_OUTPUT_NOT_FOUND",
                        "node {0} does not expose output {1}".format(
                            upstream.node_id, expected_alias
                        ),
                        node.node_id,
                    )
                )
                continue
            node_dependencies.add(upstream.node_id)
            derived_edges.add((upstream.node_id, node.node_id))

        if node.kind == "GATE":
            metric_ref = _text(node.config.get("metricRef"))
            if metric_ref is not None:
                prefix = metric_ref.split(".", 1)[0]
                upstream_id = alias_to_node_id.get(prefix, prefix)
                upstream = nodes.get(upstream_id)
                if upstream is None:
                    issues.append(
                        PlanIssue(
                            "GATE_METRIC_NOT_FOUND",
                            "metric reference {0} does not resolve to an upstream node".format(
                                metric_ref
                            ),
                            node.node_id,
                        )
                    )
                elif upstream.kind == _OUTPUT_NODE_KIND:
                    issues.append(
                        PlanIssue(
                            "OUTPUT_HAS_DEPENDENTS",
                            "OUTPUT node {0} cannot produce a gate metric".format(
                                upstream.node_id
                            ),
                            node.node_id,
                        )
                    )
                else:
                    node_dependencies.add(upstream.node_id)
                    derived_edges.add((upstream.node_id, node.node_id))

        ordered_dependencies = tuple(
            dependency for dependency in node_order if dependency in node_dependencies
        )
        dependencies[node.node_id] = ordered_dependencies

    _compare_edges(declared_edges, derived_edges, issues)

    for node in nodes.values():
        if not node.enabled:
            continue
        for dependency in dependencies[node.node_id]:
            if not nodes[dependency].enabled:
                issues.append(
                    PlanIssue(
                        "ENABLED_DEPENDS_ON_DISABLED",
                        "enabled node depends on disabled node {0}".format(dependency),
                        node.node_id,
                    )
                )

    ordered_node_ids = _topological_order(node_order, dependencies, nodes, issues)

    raw_node_count = plan.get("nodeCount")
    if raw_node_count is not None:
        if isinstance(raw_node_count, bool) or not isinstance(raw_node_count, int):
            issues.append(PlanIssue("NODE_COUNT_INVALID", "nodeCount must be an integer"))
        elif raw_node_count != len(raw_nodes):
            issues.append(
                PlanIssue(
                    "NODE_COUNT_MISMATCH",
                    "nodeCount is {0}, but nodes contains {1} entries".format(
                        raw_node_count, len(raw_nodes)
                    ),
                )
            )

    if issues:
        raise PlanValidationError(issues)

    return ExecutionPlan(
        execution=execution,
        nodes=nodes,
        ordered_node_ids=ordered_node_ids,
        dependencies=dependencies,
        edges=frozenset(derived_edges),
    )


def _parse_node(
    raw_node: Any,
    index: int,
    execution: RunExecution,
    issues: List[PlanIssue],
) -> Optional[RuleNode]:
    if not isinstance(raw_node, Mapping):
        issues.append(
            PlanIssue("NODE_INVALID", "node at index {0} must be an object".format(index))
        )
        return None

    node_id = _text(raw_node.get("id"))
    if node_id is None:
        issues.append(
            PlanIssue("NODE_ID_REQUIRED", "node at index {0} is missing id".format(index))
        )
        return None

    kind = _text(raw_node.get("kind"))
    if kind is None:
        issues.append(PlanIssue("NODE_KIND_REQUIRED", "node is missing kind", node_id))
        return None
    kind = kind.upper()
    if kind not in NODE_KINDS:
        issues.append(
            PlanIssue(
                "NODE_KIND_UNSUPPORTED",
                "node kind {0} is not supported by this worker".format(kind),
                node_id,
            )
        )
        return None

    config = raw_node.get("config")
    if not isinstance(config, Mapping):
        issues.append(
            PlanIssue("NODE_CONFIG_INVALID", "node config must be an object", node_id)
        )
        config = {}

    inputs = _parse_inputs(raw_node.get("inputs"), node_id, execution, issues)
    depends_on = _parse_dependencies(raw_node.get("dependsOn"), node_id, issues)
    output_alias = _parse_output_alias(raw_node.get("output"), node_id, issues)

    enabled_value = raw_node.get("enabled", True)
    if not isinstance(enabled_value, bool):
        issues.append(PlanIssue("NODE_ENABLED_INVALID", "enabled must be a boolean", node_id))
        enabled_value = True

    return RuleNode(
        node_id=node_id,
        kind=kind,
        name=_text(raw_node.get("name")) or kind + " " + node_id,
        stage=(_text(raw_node.get("stage")) or "").upper(),
        enabled=enabled_value,
        inputs=inputs,
        config=config,
        depends_on=depends_on,
        output_alias=output_alias,
        raw=raw_node,
    )


def _parse_inputs(
    raw_inputs: Any,
    node_id: str,
    execution: RunExecution,
    issues: List[PlanIssue],
) -> Tuple[Mapping[str, Any], ...]:
    if raw_inputs is None:
        return ()
    if not isinstance(raw_inputs, list):
        issues.append(PlanIssue("NODE_INPUTS_INVALID", "inputs must be an array", node_id))
        return ()

    parsed: List[Mapping[str, Any]] = []
    aliases: Set[str] = set()
    for index, raw_input in enumerate(raw_inputs):
        if not isinstance(raw_input, Mapping):
            issues.append(
                PlanIssue(
                    "NODE_INPUT_INVALID",
                    "input at index {0} must be an object".format(index),
                    node_id,
                )
            )
            continue

        input_type = _text(raw_input.get("type"))
        input_type = input_type.upper() if input_type is not None else None
        if input_type not in {"TABLE", "NODE_OUTPUT"}:
            issues.append(
                PlanIssue(
                    "NODE_INPUT_TYPE_INVALID",
                    "input type must be TABLE or NODE_OUTPUT",
                    node_id,
                )
            )
            continue

        alias = _text(raw_input.get("alias"))
        if alias is None:
            issues.append(PlanIssue("NODE_INPUT_ALIAS_REQUIRED", "input alias is required", node_id))
            continue
        if not _IDENTIFIER.match(alias):
            issues.append(
                PlanIssue(
                    "NODE_INPUT_ALIAS_INVALID",
                    "input alias {0} is not a safe SQL identifier".format(alias),
                    node_id,
                )
            )
            continue
        if alias in aliases:
            issues.append(
                PlanIssue(
                    "NODE_INPUT_ALIAS_DUPLICATE",
                    "input alias {0} is duplicated".format(alias),
                    node_id,
                )
            )
            continue
        aliases.add(alias)

        normalized = dict(raw_input)
        normalized["type"] = input_type
        normalized["alias"] = alias

        if input_type == "TABLE":
            table_id = _text(raw_input.get("tableId"))
            if table_id is None:
                issues.append(
                    PlanIssue("TABLE_ID_REQUIRED", "TABLE input is missing tableId", node_id)
                )
                continue
            input_dataset_version_id = _text(raw_input.get("datasetVersionId"))
            if input_dataset_version_id is None:
                issues.append(
                    PlanIssue(
                        "TABLE_DATASET_VERSION_REQUIRED",
                        "TABLE input is missing datasetVersionId",
                        node_id,
                    )
                )
                continue
            if input_dataset_version_id != execution.dataset_version_id:
                issues.append(
                    PlanIssue(
                        "TABLE_DATASET_VERSION_MISMATCH",
                        "TABLE input uses dataset version {0}, run uses {1}".format(
                            input_dataset_version_id, execution.dataset_version_id
                        ),
                        node_id,
                    )
                )
                continue
            normalized["tableId"] = table_id
            normalized["datasetVersionId"] = input_dataset_version_id
        else:
            upstream_id = _text(raw_input.get("nodeId"))
            output_alias = _text(raw_input.get("outputAlias"))
            if upstream_id is None or output_alias is None:
                issues.append(
                    PlanIssue(
                        "NODE_OUTPUT_REFERENCE_INVALID",
                        "NODE_OUTPUT input requires nodeId and outputAlias",
                        node_id,
                    )
                )
                continue
            normalized["nodeId"] = upstream_id
            normalized["outputAlias"] = output_alias

        parsed.append(normalized)

    return tuple(parsed)


def _parse_dependencies(
    raw_dependencies: Any,
    node_id: str,
    issues: List[PlanIssue],
) -> Tuple[str, ...]:
    if raw_dependencies is None:
        return ()
    if not isinstance(raw_dependencies, list):
        issues.append(
            PlanIssue("NODE_DEPENDENCIES_INVALID", "dependsOn must be an array", node_id)
        )
        return ()

    dependencies: List[str] = []
    for raw_dependency in raw_dependencies:
        dependency = _text(raw_dependency)
        if dependency is None:
            issues.append(
                PlanIssue(
                    "NODE_DEPENDENCY_INVALID",
                    "dependsOn entries must be non-empty strings",
                    node_id,
                )
            )
            continue
        if dependency == node_id:
            issues.append(
                PlanIssue("NODE_DEPENDENCY_SELF", "node cannot depend on itself", node_id)
            )
            continue
        if dependency not in dependencies:
            dependencies.append(dependency)
    return tuple(dependencies)


def _parse_output_alias(
    raw_output: Any,
    node_id: str,
    issues: List[PlanIssue],
) -> Optional[str]:
    if raw_output is None:
        return None
    if not isinstance(raw_output, Mapping):
        issues.append(PlanIssue("NODE_OUTPUT_INVALID", "output must be an object or null", node_id))
        return None
    alias = _text(raw_output.get("alias"))
    if alias is None:
        issues.append(PlanIssue("NODE_OUTPUT_ALIAS_REQUIRED", "output is missing alias", node_id))
        return None
    if not _IDENTIFIER.match(alias):
        issues.append(
            PlanIssue(
                "NODE_OUTPUT_ALIAS_INVALID",
                "output alias {0} is not a safe SQL identifier".format(alias),
                node_id,
            )
        )
        return None
    return alias


def _parse_edges(
    raw_edges: Any,
    nodes: Mapping[str, RuleNode],
    issues: List[PlanIssue],
) -> FrozenSet[Tuple[str, str]]:
    if not isinstance(raw_edges, list):
        issues.append(PlanIssue("PLAN_EDGES_INVALID", "compiledPlan.edges must be an array"))
        return frozenset()

    edges: Set[Tuple[str, str]] = set()
    for raw_edge in raw_edges:
        if not isinstance(raw_edge, Mapping):
            issues.append(PlanIssue("PLAN_EDGE_INVALID", "edge must be an object"))
            continue
        source = _text(raw_edge.get("from"))
        target = _text(raw_edge.get("to"))
        if source is None or target is None:
            issues.append(PlanIssue("PLAN_EDGE_INVALID", "edge requires from and to"))
            continue
        if source not in nodes:
            issues.append(
                PlanIssue(
                    "PLAN_EDGE_SOURCE_NOT_FOUND",
                    "edge source {0} does not exist".format(source),
                )
            )
            continue
        if target not in nodes:
            issues.append(
                PlanIssue(
                    "PLAN_EDGE_TARGET_NOT_FOUND",
                    "edge target {0} does not exist".format(target),
                )
            )
            continue
        edges.add((source, target))
    return frozenset(edges)


def _compare_edges(
    declared_edges: FrozenSet[Tuple[str, str]],
    derived_edges: Set[Tuple[str, str]],
    issues: List[PlanIssue],
) -> None:
    if declared_edges == derived_edges:
        return
    missing = sorted(derived_edges - declared_edges)
    extra = sorted(declared_edges - derived_edges)
    issues.append(
        PlanIssue(
            "PLAN_EDGES_MISMATCH",
            "edge list does not match node references; missing={0}, extra={1}".format(
                missing[:5], extra[:5]
            ),
        )
    )


def _topological_order(
    node_order: List[str],
    dependencies: Mapping[str, Tuple[str, ...]],
    nodes: Mapping[str, RuleNode],
    issues: List[PlanIssue],
) -> Tuple[str, ...]:
    index_by_node = {node_id: index for index, node_id in enumerate(node_order)}
    indegree: Dict[str, int] = {node_id: 0 for node_id in node_order}
    dependents: Dict[str, List[str]] = defaultdict(list)

    for node_id in node_order:
        for dependency in dependencies.get(node_id, ()):
            if dependency not in nodes:
                continue
            indegree[node_id] += 1
            dependents[dependency].append(node_id)

    ready: List[Tuple[int, str]] = []
    for node_id in node_order:
        if indegree[node_id] == 0:
            heappush(ready, (index_by_node[node_id], node_id))

    ordered: List[str] = []
    while ready:
        _, node_id = heappop(ready)
        ordered.append(node_id)
        for dependent in dependents.get(node_id, ()):
            indegree[dependent] -= 1
            if indegree[dependent] == 0:
                heappush(ready, (index_by_node[dependent], dependent))

    if len(ordered) != len(node_order):
        cycle_nodes = sorted(node_id for node_id in node_order if indegree[node_id] > 0)
        issues.append(
            PlanIssue(
                "DEPENDENCY_CYCLE",
                "node dependency graph contains a cycle: {0}".format(cycle_nodes),
            )
        )
    return tuple(ordered)


def _text(value: Any) -> Optional[str]:
    if not isinstance(value, str):
        return None
    normalized = value.strip()
    return normalized or None


__all__ = [
    "ExecutionPlan",
    "NODE_KINDS",
    "PlanIssue",
    "RuleNode",
    "validate_execution_plan",
]
