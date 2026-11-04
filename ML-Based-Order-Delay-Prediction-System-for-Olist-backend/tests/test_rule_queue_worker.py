from typing import Any, Dict, Mapping, Optional

from app.workers.rule_execution.models import RunOutcome
from app.workers.rule_execution.errors import ClaimLostError
from app.workers.rule_execution.queue_worker import RuleQueueWorker


class FakeQueueClient:
    def __init__(self, claim: Mapping[str, Any]) -> None:
        self.claim = dict(claim)
        self.active: Optional[Mapping[str, Any]] = None
        self.claim_calls = []
        self.finish_calls = []

    def claim_next(self, worker_id: str, lease_seconds: int, mode: str):
        self.claim_calls.append((worker_id, lease_seconds, mode))
        return dict(self.claim)

    def activate_claim(self, claim: Mapping[str, Any]) -> None:
        self.active = dict(claim)

    def clear_claim(self) -> None:
        self.active = None

    def renew_lease(self, lease_seconds: int = 60) -> Dict[str, Any]:
        return {"leaseSeconds": lease_seconds}

    def finish(self, run_id: str, status: str, error_message: Optional[str] = None):
        self.finish_calls.append((run_id, status, error_message))
        return {"status": status}


def test_consume_once_returns_none_when_queue_is_empty() -> None:
    client = FakeQueueClient({"claimed": False})
    worker = RuleQueueWorker(client, lambda *_: None, "worker-a")  # type: ignore[arg-type]

    assert worker.consume_once() is None
    assert client.claim_calls == [("worker-a", 60, "ALL")]


def test_consume_once_activates_claim_and_executes_run() -> None:
    claim = {
        "claimed": True,
        "runId": "run-a",
        "ownerId": "owner-a",
        "claimToken": "token-a",
        "executionAttempt": 2,
    }
    client = FakeQueueClient(claim)
    observed = []

    def execute(active_client, run_id: str) -> RunOutcome:
        observed.append((active_client.active, run_id))
        return RunOutcome(run_id, "SUCCEEDED", ("node-a",))

    worker = RuleQueueWorker(client, execute, "worker-a")  # type: ignore[arg-type]
    outcome = worker.consume_once()

    assert outcome is not None
    assert outcome.status == "SUCCEEDED"
    assert observed == [(claim, "run-a")]
    assert client.active is None


def test_consume_once_marks_claimed_run_failed_when_execution_setup_fails() -> None:
    claim = {
        "claimed": True,
        "runId": "run-a",
        "ownerId": "owner-a",
        "claimToken": "token-a",
        "executionAttempt": 1,
    }
    client = FakeQueueClient(claim)

    def fail(*_):
        raise RuntimeError("clickhouse unavailable")

    worker = RuleQueueWorker(client, fail, "worker-a")  # type: ignore[arg-type]
    outcome = worker.consume_once()

    assert outcome is not None
    assert outcome.status == "FAILED"
    assert "clickhouse unavailable" in (outcome.error_message or "")
    assert client.finish_calls == [("run-a", "FAILED", "clickhouse unavailable")]


def test_consume_once_stops_without_reporting_failure_when_claim_is_lost() -> None:
    claim = {
        "claimed": True,
        "runId": "run-a",
        "ownerId": "owner-a",
        "claimToken": "token-a",
        "executionAttempt": 1,
    }
    client = FakeQueueClient(claim)

    def cancelled(*_):
        raise ClaimLostError("rule run was cancelled", status_code=409)

    worker = RuleQueueWorker(client, cancelled, "worker-a")  # type: ignore[arg-type]
    outcome = worker.consume_once()

    assert outcome is not None
    assert outcome.status == "CANCELLED"
    assert "cancelled" in (outcome.error_message or "")
    assert client.finish_calls == []
    assert client.active is None


def test_consume_once_does_not_overwrite_cancel_when_failure_finish_is_fenced() -> None:
    claim = {
        "claimed": True,
        "runId": "run-a",
        "ownerId": "owner-a",
        "claimToken": "token-a",
        "executionAttempt": 1,
    }
    client = FakeQueueClient(claim)

    def fail(*_):
        raise RuntimeError("query failed after cancellation")

    def fenced_finish(run_id, status, error_message=None):
        client.finish_calls.append((run_id, status, error_message))
        raise ClaimLostError("rule run was cancelled", status_code=409)

    client.finish = fenced_finish  # type: ignore[method-assign]
    worker = RuleQueueWorker(client, fail, "worker-a")  # type: ignore[arg-type]
    outcome = worker.consume_once()

    assert outcome is not None
    assert outcome.status == "CANCELLED"
    assert client.finish_calls[0][1] == "FAILED"
