# Python Worker Pools

Long-running work is dispatched through Temporal, not through HTTP requests. Keep separate worker deployments and task queues for dataset ingestion, rule execution, model training, and batch prediction so CPU, memory, and accelerator limits can be scaled independently. Workflows pass job IDs and object-store URIs; file contents stay in object storage.

## Rule execution core

`app/workers/rule_execution/` now contains the transport-neutral core for one frozen rule run:

- `plan.py`: independently validates the frozen `compiledPlan`, checks dataset-version binding, node IDs, supported kinds, output aliases, references, edges, cycles, and disabled-node dependencies, then produces a deterministic topological order.
- `runner.py`: calls preflight, starts a `QUEUED` run, reports `RUNNING` / terminal node states, and only finishes `SUCCEEDED` after every enabled node reports `SUCCEEDED` or `SKIPPED`.
- `client.py`: HTTP client for `GET execution`, `POST start`, `PUT node`, and `POST finish` on the dataset-service rule-run control plane.
- `snapshot.py`: typed `READY` execution snapshot with physical table/column lookup, logical/ClickHouse types, unique IDs, and hash fields.
- `ports.py`: `ExecutionPreflight`, `ExecutionBackend`, `RuleRunLoader`, and `RunProgressSink` boundaries. A real ClickHouse adapter plugs into `ExecutionBackend`; execution-snapshot resolution plugs into `ExecutionPreflight`.
- `adapters.py`: `HttpExecutionPreflight` resolves and validates the READY execution snapshot; the backend-gated preflight and `UnavailableExecutionBackend` fail closed before `start` until a ClickHouse adapter is configured. They never report fake success.
- `__main__.py`: CLI for loading and validating a frozen plan without mutating the run.

Validate a run plan against a local dataset service:

```powershell
python -m app.workers.rule_execution --run-id <run-uuid> --validate-only --base-url http://localhost:8082
```

Actual execution is intentionally not enabled yet. The dataset service now provides the `READY` execution snapshot (including `clickhouseDatabase`, physical table/column names, types, and hashes), and `HttpExecutionPreflight` validates it. A ClickHouse backend implementing `ExecutionBackend` still needs to be added; until then the execution CLI validates the snapshot, fails closed before `start`, and leaves the run `QUEUED`. Use `--check-snapshot` to inspect the snapshot without starting a run.

The worker refuses to start a run that is already `RUNNING` or terminal. Temporal should use a deterministic workflow ID such as `rule-run-{runId}` so two workers cannot process the same run concurrently; the current control-plane API has no worker lease token.

See `docs/rule-workbook/worker-execution.md` for the full contract and unblock checklist.
