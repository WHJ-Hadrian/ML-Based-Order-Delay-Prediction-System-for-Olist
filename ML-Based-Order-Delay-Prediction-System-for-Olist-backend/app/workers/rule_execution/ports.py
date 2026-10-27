"""Ports that separate run control from the eventual ClickHouse adapter."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Mapping, Optional, Protocol

from app.workers.rule_execution.models import NodeOutcome, RunExecution
from app.workers.rule_execution.plan import RuleNode
from app.workers.rule_execution.snapshot import ExecutionSnapshot


@dataclass(frozen=True)
class PreparedExecution:
    """Resources that must be resolved before a run is allowed to start.

    ``snapshot`` is the validated dataset-service execution snapshot. The
    execution backend is the only layer allowed to interpret physical table and
    column names, and it must use the frozen version and hashes in this object.
    """

    execution: RunExecution
    snapshot: ExecutionSnapshot
    metadata: Mapping[str, Any] = field(default_factory=dict)


class ExecutionPreflight(Protocol):
    """Validate readiness and resolve the immutable execution snapshot."""

    def prepare(self, execution: RunExecution) -> PreparedExecution:
        ...


class ExecutionBackend(Protocol):
    """Execute one node against resolved dataset resources."""

    def execute(self, node: RuleNode, prepared: PreparedExecution) -> NodeOutcome:
        ...


class RuleRunLoader(Protocol):
    def get_execution(self, run_id: str) -> Mapping[str, Any]:
        ...


class ExecutionSnapshotLoader(Protocol):
    def get_execution_snapshot(self, dataset_version_id: str) -> Mapping[str, Any]:
        ...


class RunProgressSink(Protocol):
    def start(self, run_id: str) -> Mapping[str, Any]:
        ...

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
        ...

    def finish(
        self,
        run_id: str,
        status: str,
        error_message: Optional[str] = None,
    ) -> Mapping[str, Any]:
        ...


__all__ = [
    "ExecutionBackend",
    "ExecutionPreflight",
    "ExecutionSnapshotLoader",
    "PreparedExecution",
    "RuleRunLoader",
    "RunProgressSink",
]
