# Python Worker Pools

Keep separate worker deployments and task queues for dataset ingestion, rule execution, model training, and batch prediction so CPU, memory, and accelerator limits can be scaled independently. The current rule PREVIEW worker uses the dataset-service claim/lease API; future durable cross-service workflows can be wrapped by Temporal and should pass IDs and object-store URIs rather than file contents.

## Rule execution core

`app/workers/rule_execution/` now contains the transport-neutral core for one frozen rule run:

- `plan.py`: independently validates the frozen `compiledPlan`, checks dataset-version binding, node IDs, supported kinds, output aliases, references, edges, cycles, and disabled-node dependencies, then produces a deterministic topological order.
- `runner.py`: calls preflight, starts a `QUEUED` run, reports `RUNNING` / terminal node states, and only finishes `SUCCEEDED` after every enabled node reports `SUCCEEDED` or `SKIPPED`.
- `client.py`: HTTP client for manual user-scoped execution and the internal claim/renew/fenced progress API.
- `snapshot.py`: typed `READY` execution snapshot with physical table/column lookup, logical/ClickHouse types, unique IDs, and hash fields.
- `ports.py`: `ExecutionPreflight`, `ExecutionBackend`, `RuleRunLoader`, and `RunProgressSink` boundaries.
- `clickhouse_backend.py`: parameterized, read-only ClickHouse previews for `CHECK`, `TRANSFORM`, `FILTER`, `PROJECT`, `DEDUPE`, guarded `JOIN`, typed `AGGREGATE`, and structured `DERIVE`; identifiers resolve only through the READY execution snapshot.
- `queue_worker.py`: claims one queued PREVIEW run at a time, renews its lease in the background, and fences stale workers by claim token plus execution attempt.
- `adapters.py`: `HttpExecutionPreflight` resolves and validates the READY execution snapshot; the backend-gated preflight and `UnavailableExecutionBackend` keep execution disabled unless explicitly configured.
- `__main__.py`: CLI for loading and validating a frozen plan without mutating the run.

Validate a run plan against a local dataset service:

```powershell
python -m app.workers.rule_execution --run-id <run-uuid> --validate-only --base-url http://localhost:8082
```

Preview execution is opt-in. Install `.[workers]`, configure `OLIST_RULE_EXECUTION_ENABLED=true` and the ClickHouse settings from `.env.example`, then invoke the CLI for a queued PREVIEW run. The database account must be read-only; each query also requests `readonly=1` and applies row, byte, and time limits. Values use query parameters, while physical identifiers come only from the validated READY snapshot.

The current compiler supports `CHECK`, `TRANSFORM`, `FILTER`, `PROJECT`, `DEDUPE`, `JOIN`, `AGGREGATE`, and `DERIVE`, with a deterministic seeded input sample capped at 5,000 rows. JOIN accepts declared aliases, stable field references, `INNER / LEFT / RIGHT / FULL`, cardinality, the `FAIL` duplicate policy, and match-rate/fanout/uniqueness guards. It validates both key types and declared cardinality before querying the joined result; `KEEP_FIRST`, `KEEP_LAST`, and `AGGREGATE` duplicate policies still fail closed.

AGGREGATE supports up to 100 group fields and 100 typed metrics using `COUNT`, `COUNT_DISTINCT`, `SUM`, `AVG`, `MIN`, `MAX`, and `MEDIAN`. HAVING is a structured predicate over metric outputs only. Metric display names never become SQL identifiers, literal values remain query parameters, and metric `params` are currently rejected unless empty. Numeric aggregation requires a compatible native ClickHouse numeric type; DECIMAL `SUM` / `AVG` / `MEDIAN` therefore require a real ClickHouse Decimal column and never cast string-backed decimals through Float64. No arbitrary SQL or implicit lossy conversion is accepted.

DERIVE supports the versioned expression tree documented in `docs/rule-workbook/schemas.md`: field and literal values, date difference/parts, concat, coalesce, case branches, arithmetic, and great-circle distance. Operations and date parts are fixed whitelists, constants are parameters, output SQL names are synthesized, and expressions are bounded to 20 levels / 200 nodes. Existing fields are replaced only with explicit `replaceExisting=true`; divide/modulo by zero yield null. String-backed typed source fields use explicit nullable parsing instead of accepting a user cast or executable expression.

`GATE` and other node kinds outside the list above still fail closed. `QUARANTINE_ROWS` reports the quarantined row count and filters the main preview output, but does not yet persist a quarantine branch. DEDUPE `FIRST`/`LAST` use deterministic hash ordering because imported source tables do not retain a source-row ordinal; they do not mean original file order or latest timestamp.

`FULL` runs support the same relation nodes plus terminal `OUTPUT` nodes in `MATERIALIZE_VERSION` + `CSV` mode. Outputs are streamed from ClickHouse to bounded temporary CSV files and registered as new dataset versions only after every enabled node succeeds. Each output is limited to 100,000 rows; all uploaded outputs together are limited to 256 MiB and 20 output nodes. FULL runs are queue-only and cannot be automatically retried after a lease expires, because durable output idempotency and recovery are not implemented. `EXPORT`, `QUARANTINE`, Parquet output, `GATE`, and persisted quarantine branches remain unsupported.

Use `--check-snapshot` to inspect the snapshot without starting a run. `--validate-only` also remains read-only; neither diagnostic mode opens a ClickHouse connection.

Enable and execute a queued PREVIEW run:

```powershell
$env:OLIST_RULE_EXECUTION_ENABLED = "true"
python -m app.workers.rule_execution --run-id <run-uuid> --base-url http://localhost:8082
```

Run one queue-consumer iteration with the internal token shared with dataset-service:

```powershell
$env:OLIST_RULE_EXECUTION_ENABLED = "true"
$env:OLIST_RULE_RUN_API_TOKEN = "<RULE_WORKER_INTERNAL_TOKEN>"
python -m app.workers.rule_execution --consume-once --worker-id local-preview-worker
```

Use `--consume` for a long-running consumer. Queue consumers claim `ALL` modes by default; set `--queue-mode PREVIEW` or `--queue-mode FULL` to isolate a worker pool. A claim contains a random claim token, an incremented execution attempt, and an expiring lease. Every internal read, start, node report, renewal, and finish call carries that fencing state. If a PREVIEW worker dies after starting, a new worker may reclaim the expired run, clear the old attempt's node reports, and rerun it because the current backend is read-only and does not materialize outputs. An expired FULL run is marked FAILED and is not replayed automatically.

See `docs/rule-workbook/worker-execution.md` for the full contract and unblock checklist.
