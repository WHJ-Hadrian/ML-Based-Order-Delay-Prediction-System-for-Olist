"""Fail-closed orchestration for one frozen rule run."""

from __future__ import annotations

import logging
from typing import List, Optional

from app.workers.rule_execution.errors import (
    RuleExecutionError,
    RunNotExecutableError,
)
from app.workers.rule_execution.models import (
    NODE_FAILED,
    NODE_RUNNING,
    RUN_FAILED,
    RUN_QUEUED,
    RUN_RUNNING,
    RUN_SUCCEEDED,
    NodeOutcome,
    RunExecution,
    RunOutcome,
)
from app.workers.rule_execution.plan import (
    ExecutionPlan,
    RuleNode,
    validate_execution_plan,
)
from app.workers.rule_execution.ports import (
    ExecutionBackend,
    ExecutionPreflight,
    PreparedExecution,
    RuleRunLoader,
    RunProgressSink,
)
from app.workers.rule_execution.snapshot import ExecutionSnapshot

_MAX_ERROR_MESSAGE_LENGTH = 2000


class RuleRunExecutor:
    """Load, validate, execute and report a rule run.

    The executor never invents a successful node report. A run only finishes as
    ``SUCCEEDED`` after every enabled plan node has reported ``SUCCEEDED`` or
    ``SKIPPED`` and the backend has returned no failure.
    """

    def __init__(
        self,
        run_loader: RuleRunLoader,
        progress_sink: RunProgressSink,
        preflight: ExecutionPreflight,
        backend: ExecutionBackend,
        logger: Optional[logging.Logger] = None,
    ) -> None:
        self._run_loader = run_loader
        self._progress_sink = progress_sink
        self._preflight = preflight
        self._backend = backend
        self._logger = logger or logging.getLogger(__name__)

    def load_plan(self, run_id: str) -> ExecutionPlan:
        raw_execution = self._run_loader.get_execution(run_id)
        execution = RunExecution.from_api_payload(raw_execution)
        if execution.run_id != run_id:
            raise RuleExecutionError(
                "execution response run id {0} does not match requested run {1}".format(
                    execution.run_id, run_id
                )
            )
        return validate_execution_plan(execution)

    def validate_only(self, run_id: str) -> RunOutcome:
        """Fetch and validate a plan without starting or mutating the run."""

        plan = self.load_plan(run_id)
        return RunOutcome(
            run_id=plan.execution.run_id,
            status="VALIDATED",
            completed_node_ids=(),
            error_message=None,
        )

    def execute(self, run_id: str) -> RunOutcome:
        plan = self.load_plan(run_id)
        execution = plan.execution
        if execution.status != RUN_QUEUED:
            raise RunNotExecutableError(
                "run {0} is {1}; this worker only starts QUEUED runs and does not "
                "silently resume or overwrite an existing execution".format(
                    execution.run_id, execution.status
                )
            )

        prepared = self._preflight.prepare(execution)
        self._validate_prepared_snapshot(execution, prepared)
        self._logger.info(
            "starting rule run %s with %d plan nodes",
            execution.run_id,
            len(plan.ordered_node_ids),
        )
        started = self._progress_sink.start(execution.run_id)
        started_status = _status(started)
        if started_status != RUN_RUNNING:
            raise RunNotExecutableError(
                "dataset service did not start run {0}; status is {1}".format(
                    execution.run_id, started_status or "missing"
                )
            )

        completed: List[str] = []
        for node in plan.ordered_nodes:
            if not node.enabled:
                try:
                    self._report_node(execution.run_id, node, NodeOutcome.skipped())
                except RuleExecutionError as report_error:
                    return self._fail_run(
                        execution.run_id,
                        node,
                        _error_message(
                            "failed to report disabled node {0} as SKIPPED: {1}".format(
                                node.node_id, report_error
                            )
                        ),
                        completed,
                        existing_node_status=None,
                    )
                completed.append(node.node_id)
                continue

            try:
                self._progress_sink.report_node(
                    execution.run_id,
                    node.node_id,
                    node.kind,
                    NODE_RUNNING,
                )
            except RuleExecutionError as report_error:
                return self._fail_run(
                    execution.run_id,
                    node,
                    _error_message(
                        "failed to report node {0} as RUNNING: {1}".format(
                            node.node_id, report_error
                        )
                    ),
                    completed,
                    existing_node_status=None,
                )
            try:
                outcome = self._backend.execute(node, prepared)
                if not isinstance(outcome, NodeOutcome):
                    raise RuleExecutionError(
                        "execution backend returned {0} for node {1}; expected NodeOutcome".format(
                            type(outcome).__name__, node.node_id
                        )
                    )
            except Exception as exception:
                message = _error_message(
                    "node {0} failed: {1}".format(node.node_id, exception)
                )
                return self._fail_run(
                    execution.run_id,
                    node,
                    message,
                    completed,
                    existing_node_status=None,
                )

            try:
                self._report_node(execution.run_id, node, outcome)
            except RuleExecutionError as report_error:
                return self._fail_run(
                    execution.run_id,
                    node,
                    _error_message(
                        "failed to report node {0} as {1}: {2}".format(
                            node.node_id, outcome.status, report_error
                        )
                    ),
                    completed,
                    existing_node_status=None,
                )
            completed.append(node.node_id)
            if outcome.status == NODE_FAILED:
                return self._fail_run(
                    execution.run_id,
                    node,
                    _error_message(
                        outcome.error_message
                        or "node {0} reported FAILED without an error message".format(
                            node.node_id
                        )
                    ),
                    completed,
                    existing_node_status=NODE_FAILED,
                )

        try:
            finished = self._progress_sink.finish(execution.run_id, RUN_SUCCEEDED)
        except RuleExecutionError as finish_error:
            return self._abort_success_finish(
                execution.run_id,
                _error_message(
                    "all nodes reported, but finishing the run as SUCCEEDED failed: {0}".format(
                        finish_error
                    )
                ),
                completed,
            )
        finished_status = _status(finished)
        if finished_status != RUN_SUCCEEDED:
            return self._abort_success_finish(
                execution.run_id,
                _error_message(
                    "dataset service did not finish run {0} as SUCCEEDED; status is {1}".format(
                        execution.run_id, finished_status or "missing"
                    )
                ),
                completed,
            )
        self._logger.info("rule run %s succeeded", execution.run_id)
        return RunOutcome(
            run_id=execution.run_id,
            status=RUN_SUCCEEDED,
            completed_node_ids=tuple(completed),
            error_message=None,
        )

    def _fail_run(
        self,
        run_id: str,
        node: RuleNode,
        message: str,
        completed: List[str],
        existing_node_status: Optional[str],
    ) -> RunOutcome:
        if existing_node_status != NODE_FAILED:
            try:
                self._progress_sink.report_node(
                    run_id,
                    node.node_id,
                    node.kind,
                    NODE_FAILED,
                    error_message=message,
                )
            except RuleExecutionError as report_error:
                message = _error_message(
                    "{0}; additionally failed to report node failure: {1}".format(
                        message, report_error
                    )
                )

        try:
            finished = self._progress_sink.finish(run_id, RUN_FAILED, message)
        except RuleExecutionError as finish_error:
            raise RuleExecutionError(
                "node {0} failed and the run could not be marked FAILED: {1}".format(
                    node.node_id, finish_error
                )
            ) from finish_error

        finished_status = _status(finished)
        if finished_status != RUN_FAILED:
            raise RuleExecutionError(
                "dataset service did not finish run {0} as FAILED; status is {1}".format(
                    run_id, finished_status or "missing"
                )
            )
        self._logger.error("rule run %s failed: %s", run_id, message)
        return RunOutcome(
            run_id=run_id,
            status=RUN_FAILED,
            completed_node_ids=tuple(completed),
            error_message=message,
        )

    def _abort_success_finish(
        self,
        run_id: str,
        message: str,
        completed: List[str],
    ) -> RunOutcome:
        try:
            finished = self._progress_sink.finish(run_id, RUN_FAILED, message)
        except RuleExecutionError as finish_error:
            raise RuleExecutionError(
                "run {0} could not be marked FAILED after a success-finish failure: {1}".format(
                    run_id, finish_error
                )
            ) from finish_error

        finished_status = _status(finished)
        if finished_status != RUN_FAILED:
            raise RuleExecutionError(
                "dataset service did not finish run {0} as FAILED after a success-finish "
                "failure; status is {1}".format(run_id, finished_status or "missing")
            )
        self._logger.error("rule run %s failed: %s", run_id, message)
        return RunOutcome(
            run_id=run_id,
            status=RUN_FAILED,
            completed_node_ids=tuple(completed),
            error_message=message,
        )

    def _report_node(
        self,
        run_id: str,
        node: RuleNode,
        outcome: NodeOutcome,
    ) -> None:
        self._progress_sink.report_node(
            run_id,
            node.node_id,
            node.kind,
            outcome.status,
            metrics=outcome.metrics,
            sample_uri=outcome.sample_uri,
            error_message=outcome.error_message,
        )

    @staticmethod
    def _validate_prepared_snapshot(
        execution: RunExecution,
        prepared: PreparedExecution,
    ) -> None:
        if not isinstance(prepared, PreparedExecution):
            raise RuleExecutionError(
                "execution preflight returned {0}; expected PreparedExecution".format(
                    type(prepared).__name__
                )
            )
        if prepared.execution.run_id != execution.run_id:
            raise RuleExecutionError(
                "preflight resolved run {0}, but worker requested {1}".format(
                    prepared.execution.run_id, execution.run_id
                )
            )
        if prepared.execution.dataset_version_id != execution.dataset_version_id:
            raise RuleExecutionError(
                "preflight resolved dataset version {0}, but run uses {1}".format(
                    prepared.execution.dataset_version_id,
                    execution.dataset_version_id,
                )
            )
        if not isinstance(prepared.snapshot, ExecutionSnapshot):
            raise RuleExecutionError(
                "preflight returned {0}; expected ExecutionSnapshot".format(
                    type(prepared.snapshot).__name__
                )
            )
        if prepared.snapshot.dataset_version_id != execution.dataset_version_id:
            raise RuleExecutionError(
                "execution snapshot belongs to dataset version {0}, but run uses {1}".format(
                    prepared.snapshot.dataset_version_id, execution.dataset_version_id
                )
            )
        if prepared.snapshot.status != "READY":
            raise RuleExecutionError(
                "execution snapshot for dataset version {0} is {1}, not READY".format(
                    execution.dataset_version_id, prepared.snapshot.status
                )
            )
        if not prepared.snapshot.manifest_hash or not prepared.snapshot.schema_hash:
            raise RuleExecutionError(
                "execution snapshot for dataset version {0} is missing manifest/schema hashes".format(
                    execution.dataset_version_id
                )
            )


def _status(payload: object) -> Optional[str]:
    if not isinstance(payload, dict):
        return None
    status = payload.get("status")
    return status.strip().upper() if isinstance(status, str) else None


def _error_message(value: str) -> str:
    message = value.strip() or "rule run failed"
    if len(message) > _MAX_ERROR_MESSAGE_LENGTH:
        return message[: _MAX_ERROR_MESSAGE_LENGTH - 3] + "..."
    return message


__all__ = ["RuleRunExecutor"]
