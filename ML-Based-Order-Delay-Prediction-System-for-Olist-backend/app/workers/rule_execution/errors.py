"""Errors exposed by the rule execution worker."""

from __future__ import annotations

from typing import TYPE_CHECKING, Any, Optional, Sequence

if TYPE_CHECKING:
    from app.workers.rule_execution.plan import PlanIssue


class RuleExecutionError(RuntimeError):
    """Base class for rule execution failures."""


class PlanValidationError(RuleExecutionError):
    """The frozen execution plan is malformed or unsafe to execute."""

    def __init__(self, issues: Sequence["PlanIssue"]) -> None:
        self.issues = tuple(issues)
        details = "; ".join(
            "{0}{1}: {2}".format(
                issue.code,
                " [{0}]".format(issue.node_id) if issue.node_id else "",
                issue.message,
            )
            for issue in self.issues
        )
        super().__init__("frozen plan validation failed: {0}".format(details))


class RunNotExecutableError(RuleExecutionError):
    """The run is not in a state this worker is allowed to execute."""


class ExecutionContractUnavailableError(RuleExecutionError):
    """A required dataset or execution adapter contract is not available yet."""


class ExecutionSnapshotError(RuleExecutionError):
    """The execution snapshot is malformed, not READY, or for another version."""


class RuleRunApiError(RuleExecutionError):
    """The dataset-service rejected a rule-run request."""

    def __init__(
        self,
        message: str,
        status_code: Optional[int] = None,
        detail: Optional[Any] = None,
    ) -> None:
        self.status_code = status_code
        self.detail = detail
        super().__init__(message)
