"""Command-line entry point for inspecting and safely starting a rule run."""

from __future__ import annotations

import argparse
import json
import logging
import sys
from typing import Optional, Sequence

from app.config import settings
from app.workers.rule_execution.adapters import (
    BackendGatedExecutionPreflight,
    HttpExecutionPreflight,
    UnavailableExecutionBackend,
)
from app.workers.rule_execution.client import RuleRunApiClient
from app.workers.rule_execution.errors import PlanValidationError, RuleExecutionError
from app.workers.rule_execution.runner import RuleRunExecutor

_MISSING_BACKEND_REASON = (
    "rule execution is not enabled yet: the READY execution snapshot is "
    "validated, but a ClickHouse execution backend must be configured before a "
    "run can start; the worker refuses to start or invent success"
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
        try:
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
        except RuleExecutionError as exception:
            print(str(exception), file=sys.stderr)
            return 2


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python -m app.workers.rule_execution",
        description=(
            "Validate a frozen rule-workbook plan. Actual execution stays disabled "
            "until the ClickHouse execution adapter is implemented."
        ),
    )
    parser.add_argument("--run-id", required=True, help="rule run UUID")
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
    return parser


if __name__ == "__main__":
    raise SystemExit(main())
