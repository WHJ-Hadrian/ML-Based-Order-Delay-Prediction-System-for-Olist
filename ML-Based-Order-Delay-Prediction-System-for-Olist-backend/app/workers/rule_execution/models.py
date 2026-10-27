"""Transport-neutral models for rule runs and node outcomes."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping, Optional, Tuple

from app.workers.rule_execution.errors import RuleExecutionError

RUN_QUEUED = "QUEUED"
RUN_RUNNING = "RUNNING"
RUN_SUCCEEDED = "SUCCEEDED"
RUN_FAILED = "FAILED"
RUN_CANCELLED = "CANCELLED"

NODE_RUNNING = "RUNNING"
NODE_SUCCEEDED = "SUCCEEDED"
NODE_FAILED = "FAILED"
NODE_SKIPPED = "SKIPPED"

TERMINAL_NODE_STATUSES = frozenset({NODE_SUCCEEDED, NODE_FAILED, NODE_SKIPPED})


@dataclass(frozen=True)
class RunExecution:
    """The immutable execution payload returned by the dataset service."""

    run_id: str
    workbook_id: str
    workbook_version_id: str
    dataset_version_id: str
    workbook_version_content_hash: str
    mode: str
    status: str
    sample_size: Optional[int]
    seed: Optional[int]
    compiled_plan: Mapping[str, Any]

    @classmethod
    def from_api_payload(cls, payload: Mapping[str, Any]) -> "RunExecution":
        if not isinstance(payload, Mapping):
            raise RuleExecutionError("rule-run execution response must be a JSON object")
        plan = payload.get("compiledPlan")
        if not isinstance(plan, Mapping):
            raise RuleExecutionError("rule-run execution response is missing compiledPlan")

        return cls(
            run_id=_required_text(payload, "id"),
            workbook_id=_required_text(payload, "workbookId"),
            workbook_version_id=_required_text(payload, "workbookVersionId"),
            dataset_version_id=_required_text(payload, "datasetVersionId"),
            workbook_version_content_hash=_required_text(
                payload, "workbookVersionContentHash"
            ),
            mode=_required_text(payload, "mode").upper(),
            status=_required_text(payload, "status").upper(),
            sample_size=_optional_int(payload.get("sampleSize"), "sampleSize"),
            seed=_optional_int(payload.get("seed"), "seed"),
            compiled_plan=plan,
        )


@dataclass(frozen=True)
class NodeOutcome:
    """A terminal node result produced by an execution backend."""

    status: str
    metrics: Optional[Mapping[str, Any]] = None
    sample_uri: Optional[str] = None
    error_message: Optional[str] = None

    def __post_init__(self) -> None:
        normalized = self.status.strip().upper()
        if normalized not in TERMINAL_NODE_STATUSES:
            raise ValueError(
                "node outcome status must be SUCCEEDED, FAILED or SKIPPED, got {0}".format(
                    self.status
                )
            )
        object.__setattr__(self, "status", normalized)

    @classmethod
    def succeeded(
        cls,
        metrics: Optional[Mapping[str, Any]] = None,
        sample_uri: Optional[str] = None,
    ) -> "NodeOutcome":
        return cls(NODE_SUCCEEDED, metrics=metrics, sample_uri=sample_uri)

    @classmethod
    def failed(cls, message: str) -> "NodeOutcome":
        return cls(NODE_FAILED, error_message=message)

    @classmethod
    def skipped(cls, message: Optional[str] = None) -> "NodeOutcome":
        return cls(NODE_SKIPPED, error_message=message)


@dataclass(frozen=True)
class RunOutcome:
    """The local outcome reported by one worker invocation."""

    run_id: str
    status: str
    completed_node_ids: Tuple[str, ...]
    error_message: Optional[str] = None

    def __post_init__(self) -> None:
        normalized = self.status.strip().upper()
        if normalized not in {"VALIDATED", RUN_SUCCEEDED, RUN_FAILED}:
            raise ValueError("unsupported local run outcome: {0}".format(self.status))
        object.__setattr__(self, "status", normalized)


def _required_text(payload: Mapping[str, Any], field: str) -> str:
    value = payload.get(field)
    if not isinstance(value, str) or not value.strip():
        raise RuleExecutionError(
            "rule-run execution response is missing non-empty {0}".format(field)
        )
    return value.strip()


def _optional_int(value: Any, field: str) -> Optional[int]:
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, int):
        raise RuleExecutionError(
            "rule-run execution response field {0} must be an integer".format(field)
        )
    return value
