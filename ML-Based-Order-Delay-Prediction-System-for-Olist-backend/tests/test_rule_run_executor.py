from dataclasses import replace

import pytest

from app.workers.rule_execution.adapters import UnavailableExecutionPreflight
from app.workers.rule_execution.errors import (
    ExecutionContractUnavailableError,
    PlanValidationError,
    RuleExecutionError,
    RuleRunApiError,
    RunNotExecutableError,
)
from app.workers.rule_execution.models import NodeOutcome
from app.workers.rule_execution.ports import PreparedExecution
from app.workers.rule_execution.runner import RuleRunExecutor
from tests.rule_execution_support import (
    DATASET_VERSION_ID,
    ORDERS_TABLE_ID,
    RUN_ID,
    FakeLoader,
    FakePreflight,
    FakeProgressSink,
    ScriptedBackend,
    make_check_node,
    make_execution_payload,
    make_nodes_and_edges,
    make_output_node,
    make_snapshot,
)


class _WrongSnapshotVersionPreflight:
    def prepare(self, execution) -> PreparedExecution:
        return PreparedExecution(
            execution=execution,
            snapshot=replace(
                make_snapshot(),
                dataset_version_id="eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee",
            ),
        )


class _NotReadySnapshotPreflight:
    def prepare(self, execution) -> PreparedExecution:
        return PreparedExecution(
            execution=execution,
            snapshot=replace(make_snapshot(), status="UPLOADED"),
        )


class _WrongRunPreflight:
    def prepare(self, execution) -> PreparedExecution:
        return PreparedExecution(
            execution=replace(execution, run_id="ffffffff-ffff-ffff-ffff-ffffffffffff"),
            snapshot=make_snapshot(execution.dataset_version_id),
        )


def test_executor_reports_every_node_then_finishes_successfully() -> None:
    nodes, _ = make_nodes_and_edges()
    sink = _sink_for(nodes)
    backend = ScriptedBackend()
    executor, preflight = _executor(make_execution_payload(), sink, backend)

    outcome = executor.execute(RUN_ID)

    assert outcome.status == "SUCCEEDED"
    assert outcome.completed_node_ids == (
        "field_checks:check",
        "relations:join",
        "gates:gate",
        "outputs:output",
    )
    assert backend.calls == list(outcome.completed_node_ids)
    assert len(preflight.calls) == 1
    assert sink.events[0] == ("start", RUN_ID)
    assert sink.events[-1] == ("finish", "SUCCEEDED", None)
    assert [
        (event[1], event[2]) for event in sink.events if event[0] == "report"
    ] == [
        ("field_checks:check", "RUNNING"),
        ("field_checks:check", "SUCCEEDED"),
        ("relations:join", "RUNNING"),
        ("relations:join", "SUCCEEDED"),
        ("gates:gate", "RUNNING"),
        ("gates:gate", "SUCCEEDED"),
        ("outputs:output", "RUNNING"),
        ("outputs:output", "SUCCEEDED"),
    ]


def test_backend_exception_marks_node_and_run_failed_without_success() -> None:
    nodes, _ = make_nodes_and_edges()
    sink = _sink_for(nodes)
    backend = ScriptedBackend(
        {"field_checks:check": RuntimeError("clickhouse unavailable")}
    )
    executor, _ = _executor(make_execution_payload(), sink, backend)

    outcome = executor.execute(RUN_ID)

    assert outcome.status == "FAILED"
    assert outcome.completed_node_ids == ()
    assert backend.calls == ["field_checks:check"]
    assert sink.statuses["field_checks:check"] == "FAILED"
    assert sink.events[-1][0] == "finish"
    assert sink.events[-1][1] == "FAILED"
    assert "clickhouse unavailable" in sink.events[-1][2]
    assert not any(
        event[0] == "finish" and event[1] == "SUCCEEDED" for event in sink.events
    )


def test_node_result_failed_stops_remaining_nodes() -> None:
    nodes, _ = make_nodes_and_edges()
    sink = _sink_for(nodes)
    backend = ScriptedBackend(
        {"relations:join": NodeOutcome.failed("join fanout exceeded")}
    )
    executor, _ = _executor(make_execution_payload(), sink, backend)

    outcome = executor.execute(RUN_ID)

    assert outcome.status == "FAILED"
    assert backend.calls == ["field_checks:check", "relations:join"]
    assert sink.statuses["relations:join"] == "FAILED"
    assert sink.events[-1] == ("finish", "FAILED", "join fanout exceeded")
    assert "gates:gate" not in sink.statuses
    assert "outputs:output" not in sink.statuses


def test_missing_snapshot_preflight_does_not_start_the_run() -> None:
    nodes, _ = make_nodes_and_edges()
    sink = _sink_for(nodes)
    backend = ScriptedBackend()
    executor = RuleRunExecutor(
        run_loader=FakeLoader(make_execution_payload()),
        progress_sink=sink,
        preflight=UnavailableExecutionPreflight("snapshot contract missing"),
        backend=backend,
    )

    with pytest.raises(ExecutionContractUnavailableError):
        executor.execute(RUN_ID)

    assert sink.events == []
    assert backend.calls == []


@pytest.mark.parametrize(
    "preflight",
    [
        _WrongSnapshotVersionPreflight(),
        _NotReadySnapshotPreflight(),
        _WrongRunPreflight(),
    ],
)
def test_preflight_snapshot_must_match_run_and_be_ready(preflight) -> None:
    nodes, _ = make_nodes_and_edges()
    sink = _sink_for(nodes)
    backend = ScriptedBackend()
    executor = RuleRunExecutor(
        run_loader=FakeLoader(make_execution_payload()),
        progress_sink=sink,
        preflight=preflight,
        backend=backend,
    )

    with pytest.raises(RuleExecutionError):
        executor.execute(RUN_ID)

    assert sink.events == []
    assert backend.calls == []


def test_invalid_plan_does_not_start_the_run() -> None:
    duplicate = make_check_node()
    payload = make_execution_payload(nodes=[duplicate, dict(duplicate)], edges=[])
    sink = FakeProgressSink({"field_checks:check": True})
    executor, _ = _executor(payload, sink, ScriptedBackend())

    with pytest.raises(PlanValidationError):
        executor.execute(RUN_ID)

    assert sink.events == []


def test_non_queued_run_is_not_silently_resumed() -> None:
    nodes, _ = make_nodes_and_edges()
    sink = _sink_for(nodes)
    preflight = FakePreflight()
    executor = RuleRunExecutor(
        run_loader=FakeLoader(make_execution_payload(status="RUNNING")),
        progress_sink=sink,
        preflight=preflight,
        backend=ScriptedBackend(),
    )

    with pytest.raises(RunNotExecutableError):
        executor.execute(RUN_ID)

    assert preflight.calls == []
    assert sink.events == []


def test_validate_only_does_not_mutate_the_run() -> None:
    nodes, _ = make_nodes_and_edges()
    sink = _sink_for(nodes)
    executor, preflight = _executor(make_execution_payload(), sink, ScriptedBackend())

    outcome = executor.validate_only(RUN_ID)

    assert outcome.status == "VALIDATED"
    assert outcome.completed_node_ids == ()
    assert preflight.calls == []
    assert sink.events == []


def test_disabled_node_is_reported_skipped_and_never_executed() -> None:
    check = make_check_node(enabled=False)
    output = make_output_node()
    output["inputs"] = [
        {
            "type": "TABLE",
            "datasetVersionId": DATASET_VERSION_ID,
            "tableId": ORDERS_TABLE_ID,
            "alias": "orders",
        }
    ]
    nodes = [check, output]
    sink = FakeProgressSink(
        {"field_checks:check": False, "outputs:output": True}
    )
    backend = ScriptedBackend()
    executor, _ = _executor(make_execution_payload(nodes=nodes, edges=[]), sink, backend)

    outcome = executor.execute(RUN_ID)

    assert outcome.status == "SUCCEEDED"
    assert backend.calls == ["outputs:output"]
    assert sink.statuses["field_checks:check"] == "SKIPPED"
    assert sink.statuses["outputs:output"] == "SUCCEEDED"


def test_report_failure_attempts_to_end_the_run_as_failed() -> None:
    nodes, _ = make_nodes_and_edges()
    sink = _ReportFailureSink(
        {node["id"]: node.get("enabled", True) for node in nodes}
    )
    backend = ScriptedBackend()
    executor, _ = _executor(make_execution_payload(), sink, backend)

    outcome = executor.execute(RUN_ID)

    assert outcome.status == "FAILED"
    assert backend.calls == []
    assert sink.statuses["field_checks:check"] == "FAILED"
    assert sink.events[-1][0] == "finish"
    assert sink.events[-1][1] == "FAILED"
    assert "simulated report failure" in sink.events[-1][2]


def test_success_finish_rejection_is_downgraded_to_failed() -> None:
    nodes, _ = make_nodes_and_edges()
    sink = _FinishSuccessRejectionSink(
        {node["id"]: node.get("enabled", True) for node in nodes}
    )
    backend = ScriptedBackend()
    executor, _ = _executor(make_execution_payload(), sink, backend)

    outcome = executor.execute(RUN_ID)

    assert outcome.status == "FAILED"
    assert sink.events[-1][0] == "finish"
    assert sink.events[-1][1] == "FAILED"
    assert not any(
        event[0] == "finish" and event[1] == "SUCCEEDED" for event in sink.events
    )


def _sink_for(nodes: list) -> FakeProgressSink:
    return FakeProgressSink({node["id"]: node.get("enabled", True) for node in nodes})


def _executor(payload, sink, backend):
    preflight = FakePreflight()
    executor = RuleRunExecutor(
        run_loader=FakeLoader(payload),
        progress_sink=sink,
        preflight=preflight,
        backend=backend,
    )
    return executor, preflight


class _ReportFailureSink(FakeProgressSink):
    def report_node(
        self,
        run_id,
        node_id,
        node_kind,
        status,
        metrics=None,
        sample_uri=None,
        error_message=None,
    ):
        if status == "RUNNING" and node_id == "field_checks:check":
            raise RuleRunApiError("simulated report failure", status_code=503)
        return super().report_node(
            run_id,
            node_id,
            node_kind,
            status,
            metrics=metrics,
            sample_uri=sample_uri,
            error_message=error_message,
        )


class _FinishSuccessRejectionSink(FakeProgressSink):
    def finish(self, run_id, status, error_message=None):
        if status == "SUCCEEDED":
            return {"status": "RUNNING"}
        return super().finish(run_id, status, error_message)
