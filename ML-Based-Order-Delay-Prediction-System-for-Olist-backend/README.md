# Python Analytics Runtime

This Python project provides the model-serving runtime, a reusable tabular training engine, and the rule-execution worker runtime. The token-protected internal training endpoint fits a real scikit-learn pipeline and atomically persists its artifact with dataset-version and field lineage; the Java model-management service remains the user-facing authorization and registry boundary. The rule worker validates frozen workbook plans, resolves the READY dataset execution snapshot, compiles whitelisted operations to bounded read-only ClickHouse preview queries, and reports node/run status. It can claim queued PREVIEW runs with a renewable lease and fencing token; FULL materialization and Temporal workflows are still not implemented. This service does not own orders, jobs, or model-registry metadata.

Install the lightweight serving runtime with `pip install -r requirements.txt`. Install development/test tools with `pip install -e ".[test]"`. The reusable CPU training engine and its tests require `pip install -e ".[training,test]"`; optional dependency groups are also available for `workers`, `analytics`, `features` (Feast with Redis), and `ml`. Install only what a worker image needs.

In Compose, set the same randomly generated `MODEL_TRAINING_INTERNAL_TOKEN` for `dataset-service`, `model-serving`, and `model-management-service`. The authenticated Java API accepts `POST /api/v1/models/training-runs`, persists an idempotent queued run, and returns `202 Accepted`; its worker claims the run with a renewable lease and sends Python only an owner-scoped `dataSource` descriptor. Python streams the selected columns from the dataset-service training export endpoint, runs bounded scikit-learn training with random/temporal/group splitting, and atomically stores an owner-scoped artifact plus manifest v2. The private Python endpoints `POST /internal/v1/model-training-runs`, `GET /internal/v1/model-artifacts/{id}/manifest`, and `GET /internal/v1/model-artifacts/{id}/export.zip` reject requests without the internal token. Artifacts live in the persistent `model-training-artifacts` volume. Java owns version registration, approval/deprecation (`DRAFT → APPROVED → DEPRECATED`), and owner-scoped manifest/ZIP proxying.

Run locally with `python -m uvicorn app.main:app --reload --port 8090`. Health probes are available at `/health/live` and `/health/ready`; Prometheus metrics are exposed at `/metrics`.

Validate a frozen rule-run plan without starting it:

```powershell
python -m app.workers.rule_execution --run-id <run-uuid> --validate-only --base-url http://localhost:8082
```

See `app/workers/README.md` and `../docs/rule-workbook/worker-execution.md` for the queue-consumer commands, execution boundaries, and remaining materialization/Temporal work.
