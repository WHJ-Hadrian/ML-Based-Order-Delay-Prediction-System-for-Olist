"""Command-line entry point for inspecting and safely starting a rule run."""

from __future__ import annotations

import argparse
import json
import logging
import signal
import socket
import sys
import threading
import uuid
from typing import Optional, Sequence

from app.config import settings
from app.workers.rule_execution.adapters import (
    BackendGatedExecutionPreflight,
    HttpExecutionPreflight,
    UnavailableExecutionBackend,
)
from app.workers.rule_execution.clickhouse_backend import (
    ClickHouseConnectQueryClient,
    ClickHousePreviewBackend,
    SUPPORTED_PREVIEW_NODE_KINDS,
)
from app.workers.rule_execution.client import RuleRunApiClient
from app.workers.rule_execution.errors import PlanValidationError, RuleExecutionError
from app.workers.rule_execution.runner import RuleRunExecutor
from app.workers.rule_execution.queue_worker import RuleQueueWorker

_MISSING_BACKEND_REASON = (
    "rule execution is disabled: set OLIST_RULE_EXECUTION_ENABLED=true and "
    "configure the read-only ClickHouse connection before a run can start"
)


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = _parser()
    args = parser.parse_args(argv)
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )

    token = args.token if args.token is not None else settings.rule_run_api_token
    with RuleRunApiClient(
        base_url=args.base_url,
        token=token,
        timeout_seconds=args.timeout,
    ) as client:
        clickhouse_client = None
        try:
            if args.consume_once or args.consume:
                if args.validate_only or args.check_snapshot:
                    parser.error("queue consumption cannot be combined with inspection flags")
                if not settings.rule_execution_enabled:
                    raise RuleExecutionError(
                        "queue consumption requires OLIST_RULE_EXECUTION_ENABLED=true"
                    )
                worker_id = args.worker_id or "{0}-{1}".format(
                    socket.gethostname(), uuid.uuid4().hex[:12]
                )
                worker = RuleQueueWorker(
                    client=client,
                    execute_claim=_execute_claim,
                    worker_id=worker_id,
                    lease_seconds=args.lease_seconds,
                    poll_seconds=args.poll_seconds,
                    mode=args.queue_mode,
                )
                if args.consume_once:
                    outcome = worker.consume_once()
                    payload = (
                        {"claimed": False}
                        if outcome is None
                        else {
                            "claimed": True,
                            "runId": outcome.run_id,
                            "status": outcome.status,
                            "completedNodeIds": list(outcome.completed_node_ids),
                            "errorMessage": outcome.error_message,
                        }
                    )
                    print(json.dumps(payload, ensure_ascii=False, indent=2))
                    return (
                        0
                        if outcome is None or outcome.status in {"SUCCEEDED", "CANCELLED"}
                        else 2
                    )

                shutdown = threading.Event()
                signal.signal(signal.SIGTERM, lambda *_: shutdown.set())
                if hasattr(signal, "SIGINT"):
                    signal.signal(signal.SIGINT, lambda *_: shutdown.set())
                worker.consume_forever(shutdown)
                return 0

            executor = RuleRunExecutor(
                run_loader=client,
                progress_sink=client,
                preflight=BackendGatedExecutionPreflight(
                    client,
                    backend_available=False,
                    reason=_MISSING_BACKEND_REASON,
                ),
                backend=UnavailableExecutionBackend(_MISSING_BACKEND_REASON),
            )
            plan = executor.load_plan(args.run_id)
            if args.check_snapshot:
                snapshot = HttpExecutionPreflight(client).prepare(plan.execution).snapshot
                print(
                    json.dumps(
                        {
                            "runId": plan.execution.run_id,
                            "datasetVersionId": snapshot.dataset_version_id,
                            "status": snapshot.status,
                            "manifestHash": snapshot.manifest_hash,
                            "schemaHash": snapshot.schema_hash,
                            "clickhouseDatabase": snapshot.clickhouse_database,
                            "tableCount": len(snapshot.tables),
                            "nodeCount": len(plan.ordered_node_ids),
                        },
                        ensure_ascii=False,
                        indent=2,
                    )
                )
                return 0
            if args.validate_only:
                print(
                    json.dumps(
                        {
                            "runId": plan.execution.run_id,
                            "status": "VALIDATED",
                            "datasetVersionId": plan.execution.dataset_version_id,
                            "nodeCount": len(plan.ordered_node_ids),
                            "orderedNodes": [
                                {
                                    "id": node.node_id,
                                    "kind": node.kind,
                                    "enabled": node.enabled,
                                    "dependencies": list(
                                        plan.dependencies[node.node_id]
                                    ),
                                }
                                for node in plan.ordered_nodes
                            ],
                        },
                        ensure_ascii=False,
                        indent=2,
                    )
                )
                return 0

            if settings.rule_execution_enabled:
                supported_kinds = _supported_kinds(plan.execution.mode)
                unsupported_kinds = sorted(
                    {
                        node.kind
                        for node in plan.ordered_nodes
                        if node.enabled
                        and node.kind not in supported_kinds
                    }
                )
                if unsupported_kinds:
                    raise RuleExecutionError(
                        "ClickHouse preview cannot start because the plan contains "
                        "unsupported node kinds: {0}".format(", ".join(unsupported_kinds))
                    )
                try:
                    clickhouse_client = ClickHouseConnectQueryClient(
                        host=settings.clickhouse_host,
                        port=settings.clickhouse_port,
                        username=settings.clickhouse_username,
                        password=settings.clickhouse_password,
                        secure=settings.clickhouse_secure,
                    )
                except Exception as exception:
                    raise RuleExecutionError(
                        "failed to initialize ClickHouse connection: {0}".format(
                            exception
                        )
                    ) from exception
                executor = RuleRunExecutor(
                    run_loader=client,
                    progress_sink=client,
                    preflight=HttpExecutionPreflight(client),
                    backend=ClickHousePreviewBackend(
                        clickhouse_client,
                        max_sample_rows=settings.rule_preview_max_sample_rows,
                        max_rows_to_read=settings.rule_query_max_rows_to_read,
                        max_bytes_to_read=settings.rule_query_max_bytes_to_read,
                        max_execution_time_seconds=(
                            settings.rule_query_max_execution_time_seconds
                        ),
                        max_output_rows=settings.rule_output_max_rows,
                        max_output_bytes=settings.rule_output_max_bytes,
                    ),
                )

            outcome = executor.execute(args.run_id)
            print(
                json.dumps(
                    {
                        "runId": outcome.run_id,
                        "status": outcome.status,
                        "completedNodeIds": list(outcome.completed_node_ids),
                        "errorMessage": outcome.error_message,
                    },
                    ensure_ascii=False,
                    indent=2,
                )
            )
            return 0 if outcome.status == "SUCCEEDED" else 2
        except PlanValidationError as exception:
            print(
                json.dumps(
                    {
                        "error": "PLAN_VALIDATION_FAILED",
                        "message": str(exception),
                        "issues": [
                            {
                                "code": issue.code,
                                "nodeId": issue.node_id,
                                "message": issue.message,
                            }
                            for issue in exception.issues
                        ],
                    },
                    ensure_ascii=False,
                    indent=2,
                ),
                file=sys.stderr,
            )
            return 2
        except (RuleExecutionError, ValueError) as exception:
            print(str(exception), file=sys.stderr)
            return 2
        finally:
            if clickhouse_client is not None:
                clickhouse_client.close()


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python -m app.workers.rule_execution",
        description=(
            "Validate or execute a frozen rule-workbook plan. ClickHouse execution "
            "is opt-in; FULL materialization currently produces CSV dataset versions."
        ),
    )
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--run-id", help="rule run UUID for one-time manual execution")
    mode.add_argument(
        "--consume-once",
        action="store_true",
        help="claim and execute at most one queued run, then exit",
    )
    mode.add_argument(
        "--consume",
        action="store_true",
        help="continuously claim queued runs until SIGTERM or SIGINT",
    )
    parser.add_argument(
        "--validate-only",
        action="store_true",
        help="fetch and validate the frozen plan without mutating the run",
    )
    parser.add_argument(
        "--check-snapshot",
        action="store_true",
        help=(
            "fetch and validate the READY execution snapshot without mutating "
            "the run"
        ),
    )
    parser.add_argument(
        "--base-url",
        default=settings.rule_run_api_base_url,
        help="dataset-service or gateway base URL",
    )
    parser.add_argument(
        "--token",
        default=None,
        help="bearer token; defaults to OLIST_RULE_RUN_API_TOKEN",
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=settings.rule_run_api_timeout_seconds,
        help="HTTP timeout in seconds",
    )
    parser.add_argument("--verbose", action="store_true", help="enable debug logging")
    parser.add_argument("--worker-id", default=None, help="stable worker identifier")
    parser.add_argument(
        "--queue-mode",
        choices=("ALL", "PREVIEW", "FULL"),
        default="ALL",
        help="run modes this queue consumer may claim",
    )
    parser.add_argument(
        "--lease-seconds", type=int, default=60, help="claim lease duration (15-300 seconds)"
    )
    parser.add_argument(
        "--poll-seconds", type=float, default=2.0, help="idle queue poll interval (0.1-60 seconds)"
    )
    return parser


def _execute_claim(client: RuleRunApiClient, run_id: str):
    executor = RuleRunExecutor(
        run_loader=client,
        progress_sink=client,
        preflight=HttpExecutionPreflight(client),
        backend=UnavailableExecutionBackend(_MISSING_BACKEND_REASON),
    )
    plan = executor.load_plan(run_id)
    supported_kinds = _supported_kinds(plan.execution.mode)
    unsupported_kinds = sorted(
        {
            node.kind
            for node in plan.ordered_nodes
            if node.enabled and node.kind not in supported_kinds
        }
    )
    if unsupported_kinds:
        raise RuleExecutionError(
            "ClickHouse preview cannot start because the plan contains unsupported "
            "node kinds: {0}".format(", ".join(unsupported_kinds))
        )
    clickhouse_client = ClickHouseConnectQueryClient(
        host=settings.clickhouse_host,
        port=settings.clickhouse_port,
        username=settings.clickhouse_username,
        password=settings.clickhouse_password,
        secure=settings.clickhouse_secure,
    )
    executor = RuleRunExecutor(
        run_loader=client,
        progress_sink=client,
        preflight=HttpExecutionPreflight(client),
        backend=ClickHousePreviewBackend(
            clickhouse_client,
            max_sample_rows=settings.rule_preview_max_sample_rows,
            max_rows_to_read=settings.rule_query_max_rows_to_read,
            max_bytes_to_read=settings.rule_query_max_bytes_to_read,
            max_execution_time_seconds=settings.rule_query_max_execution_time_seconds,
            max_output_rows=settings.rule_output_max_rows,
            max_output_bytes=settings.rule_output_max_bytes,
        ),
    )
    try:
        return executor.execute(run_id)
    finally:
        clickhouse_client.close()


def _supported_kinds(mode: str):
    if mode == "PREVIEW":
        return SUPPORTED_PREVIEW_NODE_KINDS
    if mode == "FULL":
        return SUPPORTED_PREVIEW_NODE_KINDS | {"OUTPUT"}
    raise RuleExecutionError("unsupported rule run mode: {0}".format(mode))


if __name__ == "__main__":
    raise SystemExit(main())
