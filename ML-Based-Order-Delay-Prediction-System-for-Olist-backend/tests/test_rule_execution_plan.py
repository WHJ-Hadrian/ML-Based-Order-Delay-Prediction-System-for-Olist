from typing import Any, Dict, List

import pytest

from app.workers.rule_execution.errors import PlanValidationError
from app.workers.rule_execution.plan import validate_execution_plan
from tests.rule_execution_support import (
    DATASET_VERSION_ID,
    make_check_node,
    make_execution_payload,
    parse_execution,
)


def _issue_codes(exception: PlanValidationError) -> List[str]:
    return [issue.code for issue in exception.issues]


def test_valid_plan_is_topologically_ordered_and_edges_are_checked() -> None:
    execution = parse_execution(make_execution_payload())

    plan = validate_execution_plan(execution)

    assert plan.ordered_node_ids == (
        "field_checks:check",
        "relations:join",
        "gates:gate",
        "outputs:output",
    )
    assert plan.enabled_node_ids == plan.ordered_node_ids
    assert plan.dependencies["relations:join"] == ("field_checks:check",)
    assert plan.dependencies["gates:gate"] == ("relations:join",)
    assert ("relations:join", "outputs:output") in plan.edges


def test_plan_dataset_version_must_match_run() -> None:
    payload = make_execution_payload(
        run_dataset_version_id=DATASET_VERSION_ID,
        plan_dataset_version_id="eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee",
    )

    with pytest.raises(PlanValidationError) as raised:
        validate_execution_plan(parse_execution(payload))

    assert "PLAN_DATASET_VERSION_MISMATCH" in _issue_codes(raised.value)


def test_table_input_cannot_mix_dataset_versions() -> None:
    nodes, edges = _default_nodes_and_edges()
    nodes[0]["inputs"][0]["datasetVersionId"] = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"
    payload = make_execution_payload(nodes=nodes, edges=edges)

    with pytest.raises(PlanValidationError) as raised:
        validate_execution_plan(parse_execution(payload))

    assert "TABLE_DATASET_VERSION_MISMATCH" in _issue_codes(raised.value)


def test_cycle_is_rejected() -> None:
    first = make_check_node(depends_on=["check:second"])
    first["id"] = "check:first"
    first["output"]["alias"] = "first_out"
    first["config"]["outputAlias"] = "first_out"
    second = make_check_node(depends_on=["check:first"])
    second["id"] = "check:second"
    second["output"]["alias"] = "second_out"
    second["config"]["outputAlias"] = "second_out"
    edges = [
        {"from": "check:first", "to": "check:second"},
        {"from": "check:second", "to": "check:first"},
    ]

    with pytest.raises(PlanValidationError) as raised:
        validate_execution_plan(
            parse_execution(make_execution_payload(nodes=[first, second], edges=edges))
        )

    assert "DEPENDENCY_CYCLE" in _issue_codes(raised.value)


def test_edge_list_must_match_node_references() -> None:
    nodes, edges = _default_nodes_and_edges()

    with pytest.raises(PlanValidationError) as raised:
        validate_execution_plan(
            parse_execution(make_execution_payload(nodes=nodes, edges=edges[:-1]))
        )

    assert "PLAN_EDGES_MISMATCH" in _issue_codes(raised.value)


def test_enabled_node_cannot_depend_on_disabled_node() -> None:
    nodes, edges = _default_nodes_and_edges()
    nodes[0]["enabled"] = False

    with pytest.raises(PlanValidationError) as raised:
        validate_execution_plan(
            parse_execution(make_execution_payload(nodes=nodes, edges=edges))
        )

    assert "ENABLED_DEPENDS_ON_DISABLED" in _issue_codes(raised.value)


def test_output_node_cannot_have_dependents() -> None:
    nodes, edges = _default_nodes_and_edges()
    nodes[2]["dependsOn"] = ["outputs:output"]
    edges = edges + [{"from": "outputs:output", "to": "gates:gate"}]

    with pytest.raises(PlanValidationError) as raised:
        validate_execution_plan(
            parse_execution(make_execution_payload(nodes=nodes, edges=edges))
        )

    assert "OUTPUT_HAS_DEPENDENTS" in _issue_codes(raised.value)


def test_duplicate_node_ids_and_unsupported_kinds_are_rejected() -> None:
    duplicate = make_check_node()
    unsupported: Dict[str, Any] = {
        "id": "union:later",
        "kind": "UNION",
        "name": "later",
        "stage": "RELATE",
        "enabled": True,
        "inputs": [],
        "config": {},
        "dependsOn": [],
        "output": None,
    }

    with pytest.raises(PlanValidationError) as raised:
        validate_execution_plan(
            parse_execution(
                make_execution_payload(
                    nodes=[duplicate, dict(duplicate), unsupported], edges=[]
                )
            )
        )

    codes = _issue_codes(raised.value)
    assert "NODE_ID_DUPLICATE" in codes
    assert "NODE_KIND_UNSUPPORTED" in codes


def _default_nodes_and_edges() -> Any:
    from tests.rule_execution_support import make_nodes_and_edges

    return make_nodes_and_edges()
