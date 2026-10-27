"""Explicit fail-closed adapters used until dataset execution contracts exist."""

from __future__ import annotations

from app.workers.rule_execution.errors import ExecutionContractUnavailableError
from app.workers.rule_execution.models import NodeOutcome, RunExecution
from app.workers.rule_execution.plan import RuleNode
from app.workers.rule_execution.ports import ExecutionSnapshotLoader, PreparedExecution
from app.workers.rule_execution.snapshot import ExecutionSnapshot


class UnavailableExecutionPreflight:
    """Preflight that refuses to start a run without a READY snapshot contract."""

    def __init__(self, reason: str) -> None:
        self._reason = reason

    def prepare(self, execution: RunExecution) -> PreparedExecution:
        raise ExecutionContractUnavailableError(self._reason)


class UnavailableExecutionBackend:
    """Backend placeholder that never pretends a node succeeded."""

    def __init__(self, reason: str) -> None:
        self._reason = reason

    def execute(self, node: RuleNode, prepared: PreparedExecution) -> NodeOutcome:
        raise ExecutionContractUnavailableError(self._reason)


class HttpExecutionPreflight:
    """Resolve and validate the frozen READY snapshot from the dataset service."""

    def __init__(self, loader: ExecutionSnapshotLoader) -> None:
        self._loader = loader

    def prepare(self, execution: RunExecution) -> PreparedExecution:
        payload = self._loader.get_execution_snapshot(execution.dataset_version_id)
        snapshot = ExecutionSnapshot.from_payload(
            payload,
            expected_dataset_version_id=execution.dataset_version_id,
        )
        return PreparedExecution(execution=execution, snapshot=snapshot)


class BackendGatedExecutionPreflight:
    """Validate the READY snapshot, then gate execution on backend availability.

    While the ClickHouse execution backend is not implemented this preflight
    still resolves and validates the dataset snapshot, but raises before the
    run is started so the run stays ``QUEUED``.
    """

    def __init__(
        self,
        loader: ExecutionSnapshotLoader,
        backend_available: bool,
        reason: str,
    ) -> None:
        self._snapshot_preflight = HttpExecutionPreflight(loader)
        self._backend_available = backend_available
        self._reason = reason

    def prepare(self, execution: RunExecution) -> PreparedExecution:
        prepared = self._snapshot_preflight.prepare(execution)
        if not self._backend_available:
            raise ExecutionContractUnavailableError(self._reason)
        return prepared


__all__ = [
    "BackendGatedExecutionPreflight",
    "HttpExecutionPreflight",
    "UnavailableExecutionBackend",
    "UnavailableExecutionPreflight",
]
